#!/usr/bin/env bash
# 실제 수집기 JVM을 실행하되 첫 외부 API를 로컬의 닫힌 포트로 지정한다.
# 기관·카카오·Gemini에 요청하지 않으며 정상 수집을 수행하지 않는다.
set -euo pipefail
: "${ZIPICK_TEST_JAVA:?Java 실행 파일이 필요합니다}"
: "${ZIPICK_TEST_JAR:?빌드된 백엔드 JAR이 필요합니다}"
: "${ZIPICK_TEST_BACKEND_DIR:?백엔드 폴더가 필요합니다}"
if [ -n "${ZIPICK_TEST_METRICS_DIR:-}" ]; then
  test_dir="$ZIPICK_TEST_METRICS_DIR"
  trap 'rm -f "$test_dir"/result.log' EXIT
else
  test_dir="$(mktemp -d)"
  trap 'rm -f "$test_dir"/*.prom "$test_dir"/.collector.lock "$test_dir"/result.log; rmdir "$test_dir"' EXIT
fi
script_dir="$(cd "$(dirname "$0")/.." && pwd)"
export ZIPICK_METRICS_DIR="$test_dir"
if ! command -v flock >/dev/null 2>&1; then
  flock() { return 0; }
  export -f flock
fi
docker() {
  cd "$ZIPICK_TEST_BACKEND_DIR"
  "$ZIPICK_TEST_JAVA" -Dloader.main=com.zipick.batch.NoticeCollectionApplication \
    -cp "$ZIPICK_TEST_JAR" org.springframework.boot.loader.launch.PropertiesLauncher \
    --myhome.api.service-key=monitoring-test \
    --myhome.api.notice-base-url=http://127.0.0.1:9 \
    --zipick.notification.scheduler-enabled=false \
    --zipick.infra.collection.enabled=false \
    --spring.jpa.hibernate.ddl-auto=validate
}
export -f docker
status=0
bash "$script_dir/collect-notices-observed.sh" basic 2>&1 | tee "$test_dir/result.log" || status=${PIPESTATUS[0]}
if [ "$status" -eq 0 ]; then echo '예상한 테스트 연결 실패가 발생하지 않았습니다.' >&2; exit 1; fi
if ! grep -q '127\.0\.0\.1:9' "$test_dir/result.log"; then
  echo '수집기가 준비 단계에서 실패해 API 연결 실패 검증으로 인정할 수 없습니다.' >&2
  exit 1
fi
grep -q '^zipick_collector_last_run_success{job_type="basic"} 0$' "$test_dir/zipick_collector_basic.prom"
grep -q '^zipick_collector_running{job_type="basic"} 0$' "$test_dir/zipick_collector_basic_running.prom"
echo "실제 수집기 JVM 실패와 모니터링 기록 검증 완료: exit=$status"
