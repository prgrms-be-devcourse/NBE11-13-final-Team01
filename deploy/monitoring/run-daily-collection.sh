#!/usr/bin/env bash
# 팀의 기존 스케줄러에서 이 파일을 하루 한 번 실행한다.
set -u
script_dir="$(cd "$(dirname "$0")" && pwd)"
basic_status=0
ai_status=0
bash "$script_dir/collect-notices-observed.sh" basic || basic_status=$?
# 다른 실행이 이미 진행 중이면 대기 작업까지 중복 실행하지 않는다.
if [ "$basic_status" -eq 75 ]; then exit 75; fi
# 기본 수집이 실패해도 이전 AI 정규화 대기 작업을 재개할 기회를 유지한다.
bash "$script_dir/collect-notices-observed.sh" ai || ai_status=$?
if [ "$basic_status" -ne 0 ]; then exit "$basic_status"; fi
exit "$ai_status"
