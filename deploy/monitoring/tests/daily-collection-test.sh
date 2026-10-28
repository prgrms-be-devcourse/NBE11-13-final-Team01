#!/usr/bin/env bash
set -euo pipefail
test_dir="$(mktemp -d)"
trap 'rm -f "$test_dir/calls"; rmdir "$test_dir"' EXIT
script_dir="$(cd "$(dirname "$0")/.." && pwd)"
export ZIPICK_TEST_CALLS="$test_dir/calls"
bash() {
  printf '%s\n' "$2" >> "$ZIPICK_TEST_CALLS"
  if [ "$2" = basic ]; then return "$MOCK_BASIC_STATUS"; fi
  return "$MOCK_AI_STATUS"
}
export -f bash

export MOCK_BASIC_STATUS=1 MOCK_AI_STATUS=0
status=0
/usr/bin/env bash "$script_dir/run-daily-collection.sh" || status=$?
[ "$status" -eq 1 ]
[ "$(cat "$test_dir/calls")" = $'basic\nai' ]

: > "$test_dir/calls"
export MOCK_BASIC_STATUS=75 MOCK_AI_STATUS=0
status=0
/usr/bin/env bash "$script_dir/run-daily-collection.sh" || status=$?
[ "$status" -eq 75 ]
[ "$(cat "$test_dir/calls")" = basic ]
echo '기본 수집과 AI 대기 작업의 실행 순서 및 실패 처리 검증 완료'
