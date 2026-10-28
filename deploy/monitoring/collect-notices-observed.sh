#!/usr/bin/env bash
# 실행한 수집 프로세스의 종료 결과를 기록한다.
set -uo pipefail
job_type="${1:-basic}"
case "$job_type" in
  basic) main_class=com.zipick.batch.NoticeCollectionApplication; service=collector ;;
  ai) main_class=com.zipick.batch.NoticeAiApplication; service=ai-collector ;;
  *) echo '사용법: collect-notices-observed.sh [basic|ai]' >&2; exit 2 ;;
esac
script_dir="$(cd "$(dirname "$0")" && pwd)"
deploy_dir="$(cd "$script_dir/.." && pwd)"
metrics_dir="${ZIPICK_METRICS_DIR:-/var/lib/zipick/metrics}"
metrics_file="$metrics_dir/zipick_collector_${job_type}.prom"
running_file="$metrics_dir/zipick_collector_${job_type}_running.prom"
umask 077
mkdir -p "$metrics_dir" || exit 1
# 기본 수집과 AI 수집은 같은 DB를 다루므로 관측 스크립트를 통한 중복 실행을 막는다.
if ! command -v flock >/dev/null 2>&1; then
  echo '수집기 중복 실행 방지에 필요한 flock을 찾을 수 없습니다.' >&2
  exit 1
fi
exec 9>"$metrics_dir/.collector.lock" || exit 1
if ! flock -n 9; then
  echo '이미 다른 수집기가 실행 중입니다.' >&2
  exit 75
fi
run_log="$(mktemp "$metrics_dir/.collector-log.XXXXXX")" || exit 1
started="$(date +%s)"
write_running() {
  local value="$1" temp
  temp="$(mktemp "$metrics_dir/.collector-running.XXXXXX")" || return 1
  {
    printf '# HELP zipick_collector_running Whether a collector wrapper is running.\n'
    printf '# TYPE zipick_collector_running gauge\n'
    printf 'zipick_collector_running{job_type="%s"} %s\n' "$job_type" "$value"
    printf '# HELP zipick_collector_started_timestamp_seconds Start time of the last collector run.\n'
    printf '# TYPE zipick_collector_started_timestamp_seconds gauge\n'
    printf 'zipick_collector_started_timestamp_seconds{job_type="%s"} %s\n' "$job_type" "$started"
  } > "$temp"
  chmod 644 "$temp"
  mv -f "$temp" "$running_file"
}
cleanup() { write_running 0 || echo '수집기 종료 상태 기록 실패' >&2; rm -f "$run_log"; }
trap cleanup EXIT
write_running 1 || exit 1
last_success=0
if [ -r "$metrics_file" ]; then
  last_success="$(awk '/^zipick_collector_last_success_timestamp_seconds/ {print $2; exit}' "$metrics_file")"
  case "$last_success" in ''|*[!0-9]*) last_success=0 ;; esac
fi

cd "$deploy_dir" || exit 1
if docker compose -f docker-compose.prod.yml run --rm --no-deps --entrypoint java "$service" \
  -XX:MaxRAMPercentage=40 "-Dloader.main=$main_class" -cp app.jar org.springframework.boot.loader.launch.PropertiesLauncher 2>&1 | tee "$run_log"; then
  result=0
else
  result=$?
fi
ended="$(date +%s)"
success=0
if [ "$result" -eq 0 ]; then success=1; last_success="$ended"; fi

if mkdir -p "$metrics_dir" && tmp_file="$(mktemp "$metrics_dir/.zipick_collector.XXXXXX")"; then
  {
    printf '# HELP zipick_collector_last_run_success Last collector process exit status.\n'
    printf '# TYPE zipick_collector_last_run_success gauge\n'
    printf 'zipick_collector_last_run_success{job_type="%s"} %s\n' "$job_type" "$success"
    printf '# HELP zipick_collector_last_success_timestamp_seconds Last successful collector process exit.\n'
    printf '# TYPE zipick_collector_last_success_timestamp_seconds gauge\n'
    printf 'zipick_collector_last_success_timestamp_seconds{job_type="%s"} %s\n' "$job_type" "$last_success"
    printf '# HELP zipick_collector_last_duration_seconds Last collector process duration.\n'
    printf '# TYPE zipick_collector_last_duration_seconds gauge\n'
    printf 'zipick_collector_last_duration_seconds{job_type="%s"} %s\n' "$job_type" "$((ended-started))"
    # 좌표 변환의 기존 완료 로그가 있는 경우에만 카카오 호출 수를 기록한다.
    # 실행 기록이 없으면 0회로 간주하지 않는다.
    kakao_calls="$(awk '/주택 주소 좌표 변환 완료:/ {line=$0; if (sub(/^.*카카오 호출=/, "", line)) {sub(/[^0-9].*$/, "", line); if (line ~ /^[0-9]+$/) {sum+=line; found=1}}} END {if (found) print sum}' "$run_log")"
    if [ -n "$kakao_calls" ]; then
      printf '# HELP zipick_collector_last_kakao_geocoding_calls Kakao geocoding requests in the last collector run.\n'
      printf '# TYPE zipick_collector_last_kakao_geocoding_calls gauge\n'
      printf 'zipick_collector_last_kakao_geocoding_calls{job_type="%s"} %s\n' "$job_type" "$kakao_calls"
    fi
    kakao_errors="$(awk '/카카오 주소 검색 실패로 좌표 변환을 중단합니다|카카오 키워드 주소 보완 실패로 좌표 변환을 중단합니다/ {n++} END {print n+0}' "$run_log")"
    if [ -n "$kakao_calls" ] || [ "$kakao_errors" -gt 0 ]; then
      printf '# HELP zipick_collector_last_kakao_geocoding_errors Kakao geocoding failures recorded in the last collector run.\n'
      printf '# TYPE zipick_collector_last_kakao_geocoding_errors gauge\n'
      printf 'zipick_collector_last_kakao_geocoding_errors{job_type="%s"} %s\n' "$job_type" "$kakao_errors"
    fi
    printf '# HELP zipick_collector_last_source_errors Source collection failures in the last run.\n'
    printf '# TYPE zipick_collector_last_source_errors gauge\n'
    printf 'zipick_collector_last_source_errors{job_type="%s"} %s\n' "$job_type" "$(awk '/COLLECTOR_METRIC event=source_error/ {n++} END {print n+0}' "$run_log")"
    printf '# HELP zipick_collector_last_notice_errors Unique notices with recorded processing errors in the last run.\n'
    printf '# TYPE zipick_collector_last_notice_errors gauge\n'
    printf 'zipick_collector_last_notice_errors{job_type="%s"} %s\n' "$job_type" "$(awk '/COLLECTOR_METRIC event=(notice_error|detail_error)/ {for(i=1;i<=NF;i++) if ($i ~ /^noticeId=/) seen[$i]=1} END {for (i in seen) n++; print n+0}' "$run_log")"
    printf '# HELP zipick_collector_last_detail_errors Basic detail enrichment errors in the last run.\n'
    printf '# TYPE zipick_collector_last_detail_errors gauge\n'
    printf 'zipick_collector_last_detail_errors{job_type="%s"} %s\n' "$job_type" "$(awk '/COLLECTOR_METRIC event=detail_error/ {n++} END {print n+0}' "$run_log")"
    printf '# HELP zipick_collector_last_ai_document_errors Failed or incomplete AI extraction documents in the last run.\n'
    printf '# TYPE zipick_collector_last_ai_document_errors gauge\n'
    for reason in incomplete empty exception; do
      count="$(awk -v reason="$reason" '/COLLECTOR_METRIC event=ai_document_error/ && index($0,"reason=" reason " ") {n++} END {print n+0}' "$run_log")"
      printf 'zipick_collector_last_ai_document_errors{job_type="%s",reason="%s"} %s\n' "$job_type" "$reason" "$count"
    done
    printf '# HELP zipick_collector_last_normalization_deferred Documents deferred from AI normalization.\n'
    printf '# TYPE zipick_collector_last_normalization_deferred gauge\n'
    printf 'zipick_collector_last_normalization_deferred{job_type="%s"} %s\n' "$job_type" "$(awk '/COLLECTOR_METRIC event=normalization_deferred/ {for(i=1;i<=NF;i++) if($i ~ /^count=/) {v=$i; sub(/^count=/,"",v); n+=v}} END {print n+0}' "$run_log")"
    printf '# HELP zipick_collector_last_normalization_errors AI normalization processing errors.\n'
    printf '# TYPE zipick_collector_last_normalization_errors gauge\n'
    printf 'zipick_collector_last_normalization_errors{job_type="%s"} %s\n' "$job_type" "$(awk '/COLLECTOR_METRIC event=normalization_error/ {n++} END {print n+0}' "$run_log")"
    printf '# HELP zipick_collector_last_gemini_http_errors Gemini HTTP errors including retries in the last run.\n'
    printf '# TYPE zipick_collector_last_gemini_http_errors gauge\n'
    printf '# HELP zipick_collector_last_gemini_final_failures Calls that failed after configured retries or were not retryable.\n'
    printf '# TYPE zipick_collector_last_gemini_final_failures gauge\n'
    for stage in extraction normalization; do
      printf 'zipick_collector_last_gemini_http_success{job_type="%s",stage="%s"} %s\n' "$job_type" "$stage" "$(awk -v stage="$stage" '/COLLECTOR_METRIC event=gemini_http_success/ && index($0,"stage=" stage " ") {n++} END {print n+0}' "$run_log")"
      for status in 429 5xx network other; do
        count="$(awk -v kind="$status" -v stage="$stage" '/COLLECTOR_METRIC event=gemini_http_error/ && index($0,"stage=" stage " ") {for(i=1;i<=NF;i++) if($i ~ /^status=/) {s=$i; sub(/^status=/,"",s); if ((kind=="429" && s=="429") || (kind=="5xx" && s ~ /^5[0-9][0-9]$/) || (kind=="network" && (s=="network" || s=="interrupted")) || (kind=="other" && s!="429" && s!~ /^5[0-9][0-9]$/ && s!="network" && s!="interrupted")) n++}} END {print n+0}' "$run_log")"
        printf 'zipick_collector_last_gemini_http_errors{job_type="%s",stage="%s",category="%s"} %s\n' "$job_type" "$stage" "$status" "$count"
        final_count="$(awk -v kind="$status" -v stage="$stage" '/COLLECTOR_METRIC event=gemini_final_failure/ && index($0,"stage=" stage " ") {for(i=1;i<=NF;i++) if($i ~ /^status=/) {s=$i; sub(/^status=/,"",s); if ((kind=="429" && s=="429") || (kind=="5xx" && s ~ /^5[0-9][0-9]$/) || (kind=="network" && s=="network") || (kind=="other" && s!="429" && s!~ /^5[0-9][0-9]$/ && s!="network")) n++}} END {print n+0}' "$run_log")"
        printf 'zipick_collector_last_gemini_final_failures{job_type="%s",stage="%s",category="%s"} %s\n' "$job_type" "$stage" "$status" "$final_count"
      done
    done
  } > "$tmp_file"
  chmod 644 "$tmp_file"
  mv -f "$tmp_file" "$metrics_file"
else
  echo '수집 결과 지표를 기록할 수 없습니다.' >&2
  [ "$result" -ne 0 ] || result=1
fi
exit "$result"
