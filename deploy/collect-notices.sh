#!/usr/bin/env bash
# 서버(~/zipick)에서 공고 수집과 주변 인프라 수집을 한 번 실행한다 (compose 의 collector 서비스, 지표 기록 없음).
# 매일 수집은 monitoring/run-daily-collection.sh(기본 → AI → 인프라)를 쓰고, 이 스크립트는 수동 실행용이다.
# 웹 서버와 같은 이미지·DB를 쓰고, 끝나면 컨테이너는 지워진다.
#
#   cd ~/zipick && ./collect-notices.sh
set -euo pipefail
cd "$(dirname "$0")"
run() {
  docker compose -f docker-compose.prod.yml run --rm --no-deps --entrypoint sh collector -c \
    "exec java -XX:MaxRAMPercentage=40 -Dloader.main=$1 -cp app.jar org.springframework.boot.loader.launch.PropertiesLauncher"
}
run com.zipick.batch.NoticeCollectionApplication | grep -E "공고 수집 완료|ERROR|Exception" || true
run com.zipick.batch.InfraCollectionApplication | grep -E "주변 인프라 수집 완료|ERROR|Exception" || true
