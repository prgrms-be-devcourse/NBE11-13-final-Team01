#!/usr/bin/env bash
# 서버 cron 이 하루 한 번 실행한다: 기본 수집 → AI 수집 → 주변 인프라 수집.
# 주변 인프라는 두 수집이 만든 주택의 좌표가 모두 채워진 뒤 마지막에 한 번만 돈다.
set -u
script_dir="$(cd "$(dirname "$0")" && pwd)"
basic_status=0
ai_status=0
infra_status=0
bash "$script_dir/collect-notices-observed.sh" basic || basic_status=$?
# 다른 실행이 이미 진행 중이면 대기 작업까지 중복 실행하지 않는다.
if [ "$basic_status" -eq 75 ]; then exit 75; fi
# 기본 수집이 실패해도 이전 AI 정규화 대기 작업을 재개할 기회를 유지한다.
bash "$script_dir/collect-notices-observed.sh" ai || ai_status=$?
# 앞 단계가 실패해도 인프라는 채운다. 이미 들어온 주택의 인프라가 하루 밀리지 않게 하기 위해서다.
bash "$script_dir/collect-notices-observed.sh" infra || infra_status=$?
if [ "$basic_status" -ne 0 ]; then exit "$basic_status"; fi
if [ "$ai_status" -ne 0 ]; then exit "$ai_status"; fi
exit "$infra_status"
