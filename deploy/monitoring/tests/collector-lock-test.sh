#!/usr/bin/env bash
set -euo pipefail
test_dir="$(mktemp -d)"
trap 'rm -f "$test_dir"/*.prom "$test_dir"/.collector.lock; rmdir "$test_dir"' EXIT
script_dir="$(cd "$(dirname "$0")/.." && pwd)"
export ZIPICK_METRICS_DIR="$test_dir"
docker() { sleep 5; }
export -f docker
bash "$script_dir/collect-notices-observed.sh" basic &
first_pid=$!
for _ in {1..50}; do
  [ -f "$test_dir/zipick_collector_basic_running.prom" ] && break
  sleep 0.1
done
grep -q '^zipick_collector_running{job_type="basic"} 1$' "$test_dir/zipick_collector_basic_running.prom"
status=0
bash "$script_dir/collect-notices-observed.sh" ai || status=$?
[ "$status" -eq 75 ]
[ ! -e "$test_dir/zipick_collector_ai_running.prom" ]
wait "$first_pid"
grep -q '^zipick_collector_running{job_type="basic"} 0$' "$test_dir/zipick_collector_basic_running.prom"
echo '실제 flock 중복 실행 방지 검증 완료'
