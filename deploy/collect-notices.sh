#!/usr/bin/env bash
# 서버(~/zipick)에서 공고 수집을 한 번 실행한다 (compose 의 collector 서비스: 공고 수집 → 주변 인프라).
# 웹 서버와 같은 이미지·DB를 쓰고, 끝나면 컨테이너는 지워진다.
#
#   cd ~/zipick && ./collect-notices.sh
set -euo pipefail
cd "$(dirname "$0")"
docker compose -f docker-compose.prod.yml run --rm --no-deps --entrypoint sh collector -c \
  'exec java -XX:MaxRAMPercentage=40 -Dloader.main=com.zipick.batch.NoticeCollectionApplication -cp app.jar org.springframework.boot.loader.launch.PropertiesLauncher' \
  | grep -E "공고 수집 완료|주변 인프라 수집 완료|ERROR|Exception" || true
