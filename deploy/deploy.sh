#!/usr/bin/env bash
# Mac(Apple Silicon)에서 이미지를 빌드해 EC2(ARM, t4g)로 보내고 띄운다. 서버에는 git 접근이나 빌드가 필요 없다.
#
#   ./deploy/deploy.sh ubuntu@43.200.28.137 ~/.ssh/zipick-key.pem
#
# 서버의 ~/zipick/.env 는 처음 한 번 직접 만들어 둔다 (deploy/.env.prod.example 참고).
# 카카오 JavaScript 키는 빌드 때 필요하므로 VITE_KAKAO_MAP_KEY 환경 변수로 넘기거나 zipick-frontend/.env.local 에서 읽는다.
set -euo pipefail

TARGET="${1:?사용법: deploy.sh ubuntu@<IP> <키 파일>}"
KEY="${2:-$HOME/.ssh/zipick-key.pem}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BUNDLE="$ROOT/build/deploy-bundle"
SSH=(ssh -i "$KEY" -o StrictHostKeyChecking=accept-new "$TARGET")

if [[ -z "${VITE_KAKAO_MAP_KEY:-}" && -f "$ROOT/zipick-frontend/.env.local" ]]; then
  VITE_KAKAO_MAP_KEY="$(grep -E '^VITE_KAKAO_MAP_KEY=' "$ROOT/zipick-frontend/.env.local" | cut -d= -f2- || true)"
fi

echo "==> 1/4 이미지 빌드 (linux/arm64)"
docker build --platform linux/arm64 -t zipick-backend:latest "$ROOT/zipick-backend"
docker build --platform linux/arm64 -t zipick-notification:latest "$ROOT/zipick-notification"
docker build --platform linux/arm64 --build-arg "VITE_KAKAO_MAP_KEY=${VITE_KAKAO_MAP_KEY:-}" -t zipick-web:latest "$ROOT/zipick-frontend"

echo "==> 2/4 배포 묶음 준비"
rm -rf "$BUNDLE" && mkdir -p "$BUNDLE/db"
cp "$ROOT/deploy/docker-compose.prod.yml" "$ROOT/deploy/.env.prod.example" "$ROOT/deploy/collect-notices.sh" "$BUNDLE/"
# DB 가 처음 뜰 때 이름 순서대로 실행된다. 번호가 곧 실행 순서이므로 FK 의존 순서를 지켜야 한다:
#   인증·추천 → 공고 수집 → 주변 인프라(housing 참조) → AI 가이드(users·notice 참조)
cp "$ROOT/zipick-backend/db/local-init.sql"  "$BUNDLE/db/01-local-init.sql"
cp "$ROOT/zipick-backend/db/notice-init.sql" "$BUNDLE/db/02-notice-init.sql"
cp "$ROOT/zipick-backend/db/infra-init.sql"  "$BUNDLE/db/03-infra-init.sql"
cp "$ROOT/zipick-backend/db/guide-init.sql"  "$BUNDLE/db/04-guide-init.sql"
# 알림센터는 별도 DB. 처음 뜰 때 DB 를 만들고(05), 그 DB 에 테이블을 만든다(06)
cat > "$BUNDLE/db/05-notification-db.sql" <<'SQL'
CREATE DATABASE zipick_notification;
SQL
printf '\\connect zipick_notification\n' > "$BUNDLE/db/06-notification-init.sql"
cat "$ROOT/zipick-notification/db/init.sql" >> "$BUNDLE/db/06-notification-init.sql"

echo "==> 3/4 서버로 전송"
"${SSH[@]}" "mkdir -p ~/zipick/db"
scp -i "$KEY" -q "$BUNDLE/docker-compose.prod.yml" "$BUNDLE/.env.prod.example" "$BUNDLE/collect-notices.sh" "$TARGET:~/zipick/"
scp -i "$KEY" -q "$BUNDLE"/db/*.sql "$TARGET:~/zipick/db/"
docker save zipick-backend:latest zipick-notification:latest zipick-web:latest | gzip | "${SSH[@]}" "gunzip | docker load"

echo "==> 4/4 서버에서 실행"
"${SSH[@]}" 'cd ~/zipick \
  && test -f .env || { echo "서버에 ~/zipick/.env 가 없습니다. .env.prod.example 을 복사해 값을 채우세요."; exit 1; }
cd ~/zipick && docker compose -f docker-compose.prod.yml up -d --no-build db && docker compose -f docker-compose.prod.yml up -d --no-build --force-recreate backend notification web && docker image prune -f > /dev/null && docker compose -f docker-compose.prod.yml ps'
