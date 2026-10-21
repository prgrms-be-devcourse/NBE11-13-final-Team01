#!/usr/bin/env bash
# EC2 에서 실행되는 배포 스크립트 (전체 구성: db · rabbitmq · backend · notification · web).
# GitHub Actions 가 이 파일 내용을 그대로 서버로 보내 실행하며, 사람이 서버에서 직접 실행해도 똑같이 동작한다.
#
#   cd ~/zipick && BACKEND_IMAGE=... NOTIFICATION_IMAGE=... WEB_IMAGE=... COMPOSE_URL=... bash remote-deploy.sh
#
# 안전 규칙 (어기면 운영 데이터가 날아간다)
#   - docker compose down 계열을 쓰지 않는다.
#   - db · rabbitmq 는 --no-recreate 로만 다룬다. 떠 있으면 건드리지 않는다 (데이터와 큐에 남은 메시지를 지킨다).
#   - 운영 DB 볼륨이 없으면 새로 만들지 않고 중단한다. 빈 DB 가 생기는 사고를 막는다.
#   - 운영 DB 에 DDL·초기화 SQL 을 실행하지 않는다. 알림센터 DB(zipick_notification)가 없으면 앱을 바꾸기 전에 멈춘다.
#   - 서버의 .env 는 쓰지 않는다. 읽는 값은 헬스 체크용 HTTP_PORT 하나뿐이다.
set -euo pipefail

APP_DIR="${APP_DIR:-/home/ubuntu/zipick}"
COMPOSE_FILE="${COMPOSE_FILE:-docker-compose.prod.yml}"
# compose 의 `name: zipick` 과 짝을 이룬다. 프로젝트 이름이 바뀌면 이 값도 함께 바꿔야 한다.
DB_VOLUME="${DB_VOLUME:-zipick_db-data}"
HEALTH_PATH="${HEALTH_PATH:-/api/v1/houses/filter-options}"
HEALTH_TIMEOUT="${HEALTH_TIMEOUT:-180}"

: "${BACKEND_IMAGE:?BACKEND_IMAGE 가 필요합니다}"
: "${NOTIFICATION_IMAGE:?NOTIFICATION_IMAGE 가 필요합니다}"
: "${WEB_IMAGE:?WEB_IMAGE 가 필요합니다}"
export BACKEND_IMAGE NOTIFICATION_IMAGE WEB_IMAGE

step() { echo; echo "==> $*"; }
fail() { echo; echo "!! $*" >&2; exit 1; }

cd "$APP_DIR" || fail "배포 디렉터리가 없습니다: $APP_DIR"

# .env 는 서버에서만 관리한다. 없으면 빈 값으로 컨테이너가 뜨는 것을 막기 위해 중단한다.
[ -f .env ] || fail "$APP_DIR/.env 가 없습니다. .env.prod.example 을 복사해 값을 채운 뒤 다시 실행하세요."

# ---------------------------------------------------------------------------
# 1. 운영 DB 볼륨 확인 — 없으면 아무것도 하지 않고 멈춘다
#    볼륨이 없는 상태에서 up 을 돌리면 compose 가 빈 볼륨을 만들고 DB 가 새로 초기화된다.
#    그 경우는 "첫 설치"이므로 CD 가 아니라 담당자가 수동 절차(deploy.sh)로 진행해야 한다.
# ---------------------------------------------------------------------------
step "1/7 운영 DB 볼륨 확인"
if ! docker volume inspect "$DB_VOLUME" > /dev/null 2>&1; then
  echo "현재 존재하는 볼륨:"
  docker volume ls --format '  {{.Name}}' || true
  fail "운영 DB 볼륨 '$DB_VOLUME' 이 없습니다.
   CD 는 새 DB 를 만들지 않습니다. 아직 첫 배포라면 deploy/deploy.sh 로 수동 설치하세요.
   볼륨 이름이 다르다면 DB_VOLUME 환경 변수로 알려 주세요."
fi
echo "확인: $DB_VOLUME"

# ---------------------------------------------------------------------------
# 2. compose 파일을 배포 대상 커밋 기준으로 맞춘다
#    저장소가 Public 이라 인증 없이 받을 수 있고, URL 에 커밋 SHA 가 박혀 있어
#    "지금 띄우는 이미지"와 "그 이미지를 만든 소스"가 어긋나지 않는다.
# ---------------------------------------------------------------------------
if [ -n "${COMPOSE_URL:-}" ]; then
  step "2/7 compose 파일 갱신"
  curl -fsSL "$COMPOSE_URL" -o "$COMPOSE_FILE.new" \
    || fail "compose 파일을 받지 못했습니다: $COMPOSE_URL"
  # 문법과 변수 치환을 먼저 검증한다. 깨진 파일로 기존 파일을 덮어쓰지 않기 위해서다.
  docker compose -f "$COMPOSE_FILE.new" config -q \
    || fail "받은 compose 파일이 현재 .env 로 해석되지 않습니다. 기존 파일을 그대로 두고 중단합니다."
  [ -f "$COMPOSE_FILE" ] && cp "$COMPOSE_FILE" "$COMPOSE_FILE.bak"
  mv "$COMPOSE_FILE.new" "$COMPOSE_FILE"
  echo "검증 통과. 이전 파일은 $COMPOSE_FILE.bak 로 남겨 두었습니다."
else
  step "2/7 compose 파일 갱신 건너뜀 (COMPOSE_URL 미지정, 서버의 기존 파일 사용)"
  [ -f "$COMPOSE_FILE" ] || fail "$APP_DIR/$COMPOSE_FILE 이 없습니다."
fi

dc() { docker compose -f "$COMPOSE_FILE" "$@"; }

# compose 가 기대한 서비스 5개를 정확히 정의하는지 확인한다.
# 다른 구성(옛 1차 파일 등)을 잘못 가리켰을 때 일부 서비스만 교체되거나 고아 컨테이너가 남는 것을 막는다.
step "3/7 배포 범위 확인"
expected="backend db notification rabbitmq web"
services="$(dc config --services | sort | tr '\n' ' ' | sed 's/ $//')"
echo "compose 가 정의한 서비스: $services"
[ "$services" = "$expected" ] \
  || fail "compose 서비스 목록이 기대와 다릅니다.
   기대: $expected
   실제: $services"
echo "확인: 전체 구성"

# ---------------------------------------------------------------------------
# 3-1. 알림센터 DB 확인 — 없으면 앱을 바꾸기 전에 멈춘다
#    CD 는 DB 를 만들거나 DDL 을 실행하지 않는다. 처음 한 번은 담당자가 수동으로 만든다 (docs/cd-pipeline.md).
#    db 컨테이너 안의 POSTGRES_USER 를 쓰므로 이 스크립트는 .env 의 비밀값을 읽지 않는다.
# ---------------------------------------------------------------------------
step "3-1/7 알림센터 DB 확인"
if [ -n "$(dc ps -q db 2>/dev/null)" ]; then
  dc exec -T db sh -c 'psql -U "$POSTGRES_USER" -d zipick_notification -tAc "select 1 from notification_templates limit 1" > /dev/null' \
    || fail "알림센터 DB(zipick_notification)나 그 테이블이 없습니다.
   서버에서 한 번만: CREATE DATABASE zipick_notification; 후 zipick-notification/db/init.sql 을 적용하세요 (docs/cd-pipeline.md)."
  echo "확인: zipick_notification"
else
  echo "db 컨테이너가 아직 없어 건너뜁니다 (첫 설치는 deploy/deploy.sh 로)."
fi

# ---------------------------------------------------------------------------
# 4. 이미지 내려받기 — 실패하면 기존 컨테이너를 건드리기 전에 멈춘다
# ---------------------------------------------------------------------------
step "4/7 이미지 내려받기"
docker pull -q "$BACKEND_IMAGE"      || fail "백엔드 이미지를 받지 못했습니다: $BACKEND_IMAGE"
docker pull -q "$NOTIFICATION_IMAGE" || fail "알림센터 이미지를 받지 못했습니다: $NOTIFICATION_IMAGE"
docker pull -q "$WEB_IMAGE"          || fail "프론트 이미지를 받지 못했습니다: $WEB_IMAGE"
echo "완료."

# ---------------------------------------------------------------------------
# 5. db · rabbitmq 는 없으면 시작만, 떠 있으면 그대로 → 앱(backend · notification · web)만 교체
#    --no-recreate: 컨테이너와 볼륨을 재생성하지 않는다 (DB 데이터, 큐에 남은 메시지 보존).
#    --no-deps: 앱을 교체할 때 db · rabbitmq 를 건드리지 않는다.
# ---------------------------------------------------------------------------
step "5/7 서비스 교체 (db · rabbitmq 는 재생성하지 않음)"
dc up -d --no-recreate db rabbitmq
dc up -d --no-deps --force-recreate backend notification web

# ---------------------------------------------------------------------------
# 6. 헬스 체크 — 프론트(Caddy)를 거쳐 백엔드까지 실제로 응답하는지 확인한다.
#    /api/v1/houses/filter-options 는 인증이 필요 없고 DB 를 읽으므로
#    web → backend → db 경로가 한 번에 검증된다.
# ---------------------------------------------------------------------------
step "6/7 헬스 체크 (최대 ${HEALTH_TIMEOUT}초)"
PORT="$(grep -E '^HTTP_PORT=' .env | cut -d= -f2- || true)"
BASE="http://localhost:${PORT:-80}"
deadline=$(( $(date +%s) + HEALTH_TIMEOUT ))
healthy=false
while [ "$(date +%s)" -lt "$deadline" ]; do
  if curl -fsS -o /dev/null --max-time 5 "${BASE}${HEALTH_PATH}"; then
    healthy=true
    break
  fi
  sleep 5
done

if [ "$healthy" != true ]; then
  echo
  echo "!! 헬스 체크 실패: ${BASE}${HEALTH_PATH}"
  echo "----- 컨테이너 상태 -----"
  dc ps || true
  echo "----- 최근 로그 -----"
  dc logs --tail 80 backend notification web || true
  echo
  echo "이전 버전으로 되돌리려면 배포 워크플로를 이전 이미지 태그로 다시 실행하세요."
  exit 1
fi

echo "헬스 체크 통과."

# ---------------------------------------------------------------------------
# 7. 알림센터 확인 — compose 의 healthcheck(/health)가 healthy 가 될 때까지 기다린다.
#    메인 서버 응답과 별개로 알림센터가 죽어 있으면 알림 설정 화면이 503 이 된다.
# ---------------------------------------------------------------------------
step "7/7 알림센터 헬스 체크"
deadline=$(( $(date +%s) + 90 ))
nstatus="starting"
while [ "$(date +%s)" -lt "$deadline" ]; do
  cid="$(dc ps -q notification 2>/dev/null || true)"
  nstatus="$( [ -n "$cid" ] && docker inspect -f '{{.State.Health.Status}}' "$cid" 2>/dev/null || echo "missing")"
  [ "$nstatus" = "healthy" ] && break
  sleep 5
done
if [ "$nstatus" != "healthy" ]; then
  echo "!! 알림센터가 healthy 가 아닙니다: $nstatus"
  dc logs --tail 60 notification rabbitmq || true
  exit 1
fi
echo "알림센터 healthy."

# 태그 없는 중간 이미지만 지운다. 이전 버전 이미지는 레지스트리에 남아 있어 언제든 다시 받을 수 있다.
docker image prune -f > /dev/null || true

echo
dc ps
echo
echo "배포 완료"
echo "  backend      : $BACKEND_IMAGE"
echo "  notification : $NOTIFICATION_IMAGE"
echo "  web          : $WEB_IMAGE"
