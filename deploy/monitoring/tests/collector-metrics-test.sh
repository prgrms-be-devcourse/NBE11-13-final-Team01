#!/usr/bin/env bash
set -euo pipefail
if [ -n "${ZIPICK_TEST_METRICS_DIR:-}" ]; then
  test_dir="$ZIPICK_TEST_METRICS_DIR"
else
  test_dir="$(mktemp -d)"
  trap 'rm -f "$test_dir/zipick_collector_basic.prom" "$test_dir/zipick_collector_ai.prom" "$test_dir/zipick_collector_basic_running.prom" "$test_dir/zipick_collector_ai_running.prom" "$test_dir/.collector.lock"; rmdir "$test_dir"' EXIT
fi
script_dir="$(cd "$(dirname "$0")/.." && pwd)"
export ZIPICK_METRICS_DIR="$test_dir"
# Windows의 Git Bash에 flock이 없을 수 있어 여기서는 실행 흐름만 모의한다.
flock() { return 0; }
export -f flock
docker() {
  printf '%s\n' 'COLLECTOR_METRIC event=notice_error noticeId=42 source=SH' \
    'COLLECTOR_METRIC event=detail_error noticeId=42 source=SH' \
    'COLLECTOR_METRIC event=source_error source=GH' \
    'COLLECTOR_METRIC event=gemini_http_error stage=normalization status=503 model=gemini-3.8-flash' \
    'COLLECTOR_METRIC event=gemini_http_error stage=extraction status=429 model=gemini-3.5-flash-lite' \
    'COLLECTOR_METRIC event=gemini_final_failure stage=normalization status=503' \
    'COLLECTOR_METRIC event=ai_document_error stage=extraction reason=incomplete documentKey=doc1' \
    'COLLECTOR_METRIC event=normalization_deferred count=3' \
    '주택 주소 좌표 변환 완료: 대상=5, 갱신=4, 미해결=1, 카카오 호출=7' \
    '카카오 주소 검색 실패로 좌표 변환을 중단합니다.'
  return "$MOCK_DOCKER_EXIT"
}
export -f docker

export MOCK_DOCKER_EXIT=0
bash "$script_dir/collect-notices-observed.sh"
grep -q '^zipick_collector_last_run_success{job_type="basic"} 1$' "$test_dir/zipick_collector_basic.prom"
grep -q '^zipick_collector_running{job_type="basic"} 0$' "$test_dir/zipick_collector_basic_running.prom"
grep -q '^zipick_collector_last_notice_errors{job_type="basic"} 1$' "$test_dir/zipick_collector_basic.prom"
grep -q '^zipick_collector_last_source_errors{job_type="basic"} 1$' "$test_dir/zipick_collector_basic.prom"
grep -q '^zipick_collector_last_gemini_http_errors{job_type="basic",stage="normalization",category="5xx"} 1$' "$test_dir/zipick_collector_basic.prom"
grep -q '^zipick_collector_last_gemini_http_errors{job_type="basic",stage="extraction",category="429"} 1$' "$test_dir/zipick_collector_basic.prom"
grep -q '^zipick_collector_last_gemini_final_failures{job_type="basic",stage="normalization",category="5xx"} 1$' "$test_dir/zipick_collector_basic.prom"
grep -q '^zipick_collector_last_gemini_final_failures{job_type="basic",stage="extraction",category="429"} 0$' "$test_dir/zipick_collector_basic.prom"
grep -q '^zipick_collector_last_ai_document_errors{job_type="basic",reason="incomplete"} 1$' "$test_dir/zipick_collector_basic.prom"
grep -q '^zipick_collector_last_normalization_deferred{job_type="basic"} 3$' "$test_dir/zipick_collector_basic.prom"
grep -q '^zipick_collector_last_kakao_geocoding_calls{job_type="basic"} 7$' "$test_dir/zipick_collector_basic.prom"
grep -q '^zipick_collector_last_kakao_geocoding_errors{job_type="basic"} 1$' "$test_dir/zipick_collector_basic.prom"
before="$(awk '/^zipick_collector_last_success_timestamp_seconds/ {print $2}' "$test_dir/zipick_collector_basic.prom")"
[ "$before" -gt 0 ]

export MOCK_DOCKER_EXIT=17
if bash "$script_dir/collect-notices-observed.sh"; then
  echo '실패 종료 코드가 전달되지 않았습니다.' >&2
  exit 1
else
  status=$?
  [ "$status" -eq 17 ]
fi
grep -q '^zipick_collector_last_run_success{job_type="basic"} 0$' "$test_dir/zipick_collector_basic.prom"
after="$(awk '/^zipick_collector_last_success_timestamp_seconds/ {print $2}' "$test_dir/zipick_collector_basic.prom")"
[ "$before" = "$after" ]
export MOCK_DOCKER_EXIT=0
bash "$script_dir/collect-notices-observed.sh" ai
grep -q '^zipick_collector_last_run_success{job_type="ai"} 1$' "$test_dir/zipick_collector_ai.prom"
grep -q '^zipick_collector_running{job_type="ai"} 0$' "$test_dir/zipick_collector_ai_running.prom"
if bash "$script_dir/collect-notices-observed.sh" invalid; then exit 1; fi
echo '기본 및 AI 수집기 성공/실패 지표 검증 완료'
