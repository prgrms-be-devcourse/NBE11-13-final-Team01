#!/usr/bin/env bash
# root 로 실행하면 지표 디렉터리 소유자 계정으로 바꿔 다시 실행하는지 확인한다.
set -euo pipefail
test_dir="$(mktemp -d)"
trap 'rm -rf "$test_dir"' EXIT
script_dir="$(cd "$(dirname "$0")/.." && pwd)"
metrics_dir="$test_dir/metrics"
mkdir -p "$metrics_dir"
export ZIPICK_METRICS_DIR="$metrics_dir" ZIPICK_TEST_RUNUSER_LOG="$test_dir/runuser"
owner="$(stat -c %U "$metrics_dir")"

# 계정 전환 전에는 root(0)로 보이게 하고, runuser 를 거친 뒤에는 실제 id 를 쓴다.
id() {
  if [ "${1:-}" = -u ] && [ -z "${ZIPICK_TEST_SWITCHED:-}" ]; then echo 0; else command id "$@"; fi
}
runuser() {
  printf '%s\n' "$2" > "$ZIPICK_TEST_RUNUSER_LOG"
  shift 3
  ZIPICK_TEST_SWITCHED=1 "$@"
}
docker() { echo 'mock collector'; }
export -f id runuser docker

bash "$script_dir/collect-notices-observed.sh" basic
[ "$(cat "$ZIPICK_TEST_RUNUSER_LOG")" = "$owner" ]
grep -q '^zipick_collector_last_run_success{job_type="basic"} 1$' "$metrics_dir/zipick_collector_basic.prom"

# 지표 디렉터리가 없으면 ZIPICK_COLLECTOR_USER 로 실행하고, node-exporter 가 읽을 수 있게 755 로 만든다.
rm -rf "$metrics_dir"
ZIPICK_COLLECTOR_USER="$owner" bash "$script_dir/collect-notices-observed.sh" basic
[ "$(cat "$ZIPICK_TEST_RUNUSER_LOG")" = "$owner" ]
[ "$(stat -c %a "$metrics_dir")" = 755 ]
[ "$(stat -c %a "$metrics_dir/.collector.lock")" = 600 ]

# 지표 디렉터리가 root 소유면 계정을 정하지 않고 중단한다.
stat() { echo root; }
export -f stat
rm -f "$ZIPICK_TEST_RUNUSER_LOG"
status=0
bash "$script_dir/collect-notices-observed.sh" basic 2>/dev/null || status=$?
[ "$status" -eq 1 ]
[ ! -e "$ZIPICK_TEST_RUNUSER_LOG" ]
echo 'root 실행 시 계정 전환 검증 완료'
