#!/usr/bin/env bash
# EC2 에서 실행되는 배포 스크립트 (전체 구성: db · rabbitmq · backend · notification · web).
# .env 에 DB_HOST 가 있으면 DB 는 별도 서버(zipick-db)에 있는 것으로 보고 로컬 db 컨테이너는 다루지 않는다.
# .env 에 APP_PRIVATE_IP 가 있으면 모니터링 overlay(monitoring/docker-compose.app-exporters.yml)를 함께 적용한다.
# GitHub Actions 가 이 파일 내용을 그대로 서버로 보내 실행하며, 사람이 서버에서 직접 실행해도 똑같이 동작한다.
#
#   cd ~/zipick && BACKEND_IMAGE=... NOTIFICATION_IMAGE=... WEB_IMAGE=... COMPOSE_URL=... bash remote-deploy.sh
#
# 안전 규칙 (어기면 운영 데이터가 날아간다)
#   - docker compose down 계열을 쓰지 않는다.
#   - db · rabbitmq 는 --no-recreate 로만 다룬다. 떠 있으면 건드리지 않는다 (데이터와 큐에 남은 메시지를 지킨다).
#   - 운영 DB 볼륨이 없으면 새로 만들지 않고 중단한다. 빈 DB 가 생기는 사고를 막는다.
#   - 운영 DB 에 DDL·초기화 SQL 을 실행하지 않는다. 알림센터 DB(zipick_notification)가 없으면 앱을 바꾸기 전에 멈춘다.
#   - 서버의 .env 는 쓰지 않는다. 읽는 값은 헬스 체크용 HTTP_PORT, DB 위치(DB_HOST), 지표 노출 주소(APP_PRIVATE_IP)뿐이다.
#   - GHCR 토큰은 SSM Parameter Store(SecureString)에서 꺼내 임시 docker 설정 폴더에만 로그인하고, pull 직후 지운다.
#     토큰은 화면·파일·명령 기록에 남기지 않는다.
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

# DB 위치. 비어 있거나 db 면 이 서버의 db 컨테이너, 아니면 별도 DB 서버다.
DB_HOST="$(grep -E '^DB_HOST=' .env | cut -d= -f2- | tr -d '[:space:]' || true)"
if [ -n "$DB_HOST" ] && [ "$DB_HOST" != db ]; then EXTERNAL_DB=true; else EXTERNAL_DB=false; fi

# 모니터링 서버가 읽을 지표(Actuator 19090 · RabbitMQ 15692 · exporter)를 여는지. overlay 를 빼고 배포하면 이 설정이 되돌아간다.
OVERLAY_FILE="monitoring/docker-compose.app-exporters.yml"
APP_PRIVATE_IP="$(grep -E '^APP_PRIVATE_IP=' .env | cut -d= -f2- | tr -d '[:space:]' || true)"
if [ -n "$APP_PRIVATE_IP" ]; then METRICS=true; else METRICS=false; fi

# ---------------------------------------------------------------------------
# 1. 운영 DB 볼륨 확인 — 없으면 아무것도 하지 않고 멈춘다
#    볼륨이 없는 상태에서 up 을 돌리면 compose 가 빈 볼륨을 만들고 DB 가 새로 초기화된다.
#    그 경우는 "첫 설치"이므로 CD 가 아니라 담당자가 수동 절차(deploy.sh)로 진행해야 한다.
# ---------------------------------------------------------------------------
step "1/7 운영 DB 확인"
if [ "$EXTERNAL_DB" = true ]; then
  # 볼륨은 DB 서버에 있다. 여기서는 앱 서버에서 DB 포트까지 닿는지만 본다 (보안 그룹·DB 서버가 내려가 있으면 멈춘다).
  timeout 5 bash -c "</dev/tcp/${DB_HOST}/5432" 2> /dev/null \
    || fail "DB 서버 ${DB_HOST}:5432 에 연결할 수 없습니다. zipick-db 인스턴스와 보안 그룹을 확인하세요."
  echo "확인: 외부 DB ${DB_HOST}:5432"
elif ! docker volume inspect "$DB_VOLUME" > /dev/null 2>&1; then
  echo "현재 존재하는 볼륨:"
  docker volume ls --format '  {{.Name}}' || true
  fail "운영 DB 볼륨 '$DB_VOLUME' 이 없습니다.
   CD 는 새 DB 를 만들지 않습니다. 아직 첫 배포라면 deploy/deploy.sh 로 수동 설치하세요.
   볼륨 이름이 다르다면 DB_VOLUME 환경 변수로 알려 주세요."
else
  echo "확인: $DB_VOLUME"
fi

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
  if [ "$METRICS" = true ]; then
    # overlay 와 blackbox 설정도 같은 커밋에서 받는다. compose 와 버전이 어긋나지 않게 하기 위해서다.
    base_url="${COMPOSE_URL%/docker-compose.prod.yml}"
    mkdir -p monitoring
    curl -fsSL "$base_url/$OVERLAY_FILE" -o "$OVERLAY_FILE.new" \
      && curl -fsSL "$base_url/monitoring/blackbox.yml" -o monitoring/blackbox.yml.new \
      || fail "모니터링 overlay 를 받지 못했습니다: $base_url/$OVERLAY_FILE"
    docker compose -f "$COMPOSE_FILE" -f "$OVERLAY_FILE.new" config -q \
      || fail "받은 모니터링 overlay 가 현재 .env 로 해석되지 않습니다. 기존 파일을 그대로 두고 중단합니다."
    mv "$OVERLAY_FILE.new" "$OVERLAY_FILE"
    mv monitoring/blackbox.yml.new monitoring/blackbox.yml
    echo "모니터링 overlay 갱신."
  fi
else
  step "2/7 compose 파일 갱신 건너뜀 (COMPOSE_URL 미지정, 서버의 기존 파일 사용)"
  [ -f "$COMPOSE_FILE" ] || fail "$APP_DIR/$COMPOSE_FILE 이 없습니다."
fi

if [ "$METRICS" = true ]; then
  [ -f "$OVERLAY_FILE" ] || fail "APP_PRIVATE_IP 가 있는데 $APP_DIR/$OVERLAY_FILE 이 없습니다."
  dc() { docker compose -f "$COMPOSE_FILE" -f "$OVERLAY_FILE" "$@"; }
else
  dc() { docker compose -f "$COMPOSE_FILE" "$@"; }
fi

# compose 가 기대한 서비스 5개를 정확히 정의하는지 확인한다.
# 다른 구성(옛 1차 파일 등)을 잘못 가리켰을 때 일부 서비스만 교체되거나 고아 컨테이너가 남는 것을 막는다.
step "3/7 배포 범위 확인"
expected="backend db notification rabbitmq web"
# overlay 의 exporter 는 빼고 본 구성 파일만 센다.
services="$(docker compose -f "$COMPOSE_FILE" config --services | sort | tr '\n' ' ' | sed 's/ $//')"
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
if [ "$EXTERNAL_DB" = true ]; then
  # 외부 DB 는 이 서버에서 psql 로 들여다보지 않는다. 없으면 알림센터가 뜨지 못해 7단계에서 걸린다.
  echo "외부 DB 라 건너뜁니다 (DB 서버에서 확인: docs/cd-pipeline.md)."
elif [ -n "$(dc ps -q db 2>/dev/null)" ]; then
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
# 패키지는 비공개다. GHCR_TOKEN_PARAM 이 있으면 Parameter Store 에서 토큰을 꺼내 로그인한다.
# 로그인 정보는 이번 실행 전용 임시 폴더(DOCKER_CONFIG)에만 쓰고, pull 이 끝나면 폴더째 지운다 (~/.docker 에 남지 않음).
if [ -n "${GHCR_TOKEN_PARAM:-}" ]; then
  command -v aws > /dev/null || fail "서버에 AWS CLI 가 없습니다. sudo snap install aws-cli --classic 로 한 번 설치하세요."
  DOCKER_CONFIG="$(mktemp -d)"; export DOCKER_CONFIG
  trap 'rm -rf "$DOCKER_CONFIG"' EXIT
  aws ssm get-parameter --region "${AWS_REGION:-ap-northeast-2}" --name "$GHCR_TOKEN_PARAM" --with-decryption \
      --query Parameter.Value --output text 2>/dev/null \
    | docker login ghcr.io -u "${GHCR_USER:-github-actions}" --password-stdin > /dev/null 2>&1 \
    || fail "GHCR 로그인 실패: 파라미터 $GHCR_TOKEN_PARAM 을 읽지 못했거나 토큰이 만료됐습니다.
   인스턴스 역할(AmazonSSMManagedInstanceCore)과 리전을 확인하세요."
  echo "GHCR 로그인 (임시 설정 폴더)"
fi
docker pull -q "$BACKEND_IMAGE"      || fail "백엔드 이미지를 받지 못했습니다: $BACKEND_IMAGE"
docker pull -q "$NOTIFICATION_IMAGE" || fail "알림센터 이미지를 받지 못했습니다: $NOTIFICATION_IMAGE"
docker pull -q "$WEB_IMAGE"          || fail "프론트 이미지를 받지 못했습니다: $WEB_IMAGE"
if [ -n "${GHCR_TOKEN_PARAM:-}" ]; then
  docker logout ghcr.io > /dev/null 2>&1 || true
  rm -rf "$DOCKER_CONFIG"; unset DOCKER_CONFIG; trap - EXIT
  echo "GHCR 로그아웃, 임시 설정 폴더 삭제"
fi
echo "완료."

# ---------------------------------------------------------------------------
# 5. db · rabbitmq 는 없으면 시작만, 떠 있으면 그대로 → 앱(backend · notification · web)만 교체
#    --no-recreate: 컨테이너와 볼륨을 재생성하지 않는다 (DB 데이터, 큐에 남은 메시지 보존).
#    --no-deps: 앱을 교체할 때 db · rabbitmq 를 건드리지 않는다.
# ---------------------------------------------------------------------------
step "5/7 서비스 교체 (db · rabbitmq 는 재생성하지 않음)"
if [ "$EXTERNAL_DB" = true ]; then
  # 로컬 db 컨테이너는 롤백용으로 멈춰 둔 것이다. 다시 띄우지 않는다.
  dc up -d --no-recreate rabbitmq
else
  dc up -d --no-recreate db rabbitmq
fi
dc up -d --no-deps --force-recreate backend notification web
if [ "$METRICS" = true ]; then
  # exporter 는 상태가 없어 설정이 바뀌었을 때만 다시 만든다.
  dc up -d --no-deps node-exporter cadvisor blackbox-exporter
fi

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
