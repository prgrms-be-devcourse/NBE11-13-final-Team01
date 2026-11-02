#!/usr/bin/env bash
# 운영자 화면(/admin)의 "수집 실행" 요청(collection_request 테이블의 PENDING)을 집어 collector 컨테이너로 실행한다.
# 웹 서버는 수집을 직접 돌리지 않고 요청 행만 남기므로, 이 스크립트를 앱 서버 cron 에 1분마다 등록한다:
#   * * * * * cd $HOME/zipick && bash monitoring/run-requested-collection.sh >> $HOME/zipick/logs/collector-admin.log 2>&1
# 일일 수집(run-daily-collection.sh)과 같은 flock 을 쓰므로 둘이 겹치면 요청은 PENDING 으로 남았다가 다음 분에 다시 시도한다.
set -uo pipefail
script_dir="$(cd "$(dirname "$0")" && pwd)"
deploy_dir="$(cd "$script_dir/.." && pwd)"
cd "$deploy_dir" || exit 1

# .env 의 DB 접속 정보만 읽는다 (값은 출력하지 않는다)
if [ ! -r .env ]; then echo '.env 를 찾을 수 없습니다.' >&2; exit 1; fi
db_host="$(grep -E '^DB_HOST=' .env | head -1 | cut -d= -f2- | tr -d '"' | tr -d "'")"
db_user="$(grep -E '^DB_USERNAME=' .env | head -1 | cut -d= -f2- | tr -d '"' | tr -d "'")"
db_pass="$(grep -E '^DB_PASSWORD=' .env | head -1 | cut -d= -f2- | tr -d '"' | tr -d "'")"
db_host="${db_host:-db}"
if [ -z "$db_user" ] || [ -z "$db_pass" ]; then echo 'DB_USERNAME / DB_PASSWORD 가 .env 에 없습니다.' >&2; exit 1; fi

# compose 의 db 서비스 이미지(postgres)로 psql 만 실행한다. DB 가 다른 서버에 있어도 같은 방식으로 붙는다
psql_q() {
  docker compose -f docker-compose.prod.yml run --rm --no-deps -T \
    -e PGPASSWORD="$db_pass" --entrypoint psql db \
    -h "$db_host" -U "$db_user" -d zipick -X -q -A -t -v ON_ERROR_STOP=1 -c "$1"
}

# 가장 오래된 PENDING 하나를 RUNNING 으로 바꾸며 집는다 (같은 분에 두 번 돌아도 한 번만 집도록 UPDATE ... RETURNING)
picked="$(psql_q "update collection_request set status='RUNNING', started_at=now(), updated_at=now()
  where collection_request_id = (select collection_request_id from collection_request where status='PENDING' order by collection_request_id limit 1)
  returning collection_request_id, job_type;" 2>/dev/null)" || { echo "$(date +%FT%T) 요청 조회 실패 (DB 연결 확인)"; exit 1; }
picked="$(printf '%s' "$picked" | tr -d '[:space:]')"
[ -n "$picked" ] || exit 0
request_id="${picked%%|*}"
job_type="${picked##*|}"
case "$request_id" in ''|*[!0-9]*) echo "요청 id 를 읽지 못했습니다: $picked" >&2; exit 1 ;; esac
echo "$(date +%FT%T) 요청 #$request_id ($job_type) 시작"

finish() {
  local status="$1" message="$2"
  message="${message//\'/\'\'}"
  psql_q "update collection_request set status='$status', finished_at=now(), updated_at=now(), result_message='$message' where collection_request_id=$request_id;" >/dev/null \
    || echo "$(date +%FT%T) 요청 #$request_id 상태 갱신 실패 ($status)"
}

export COLLECTION_TRIGGER=ADMIN
export COLLECTION_REQUEST_ID="$request_id"
run_job() {
  bash "$script_dir/collect-notices-observed.sh" "$1"
}

result=0
case "$job_type" in
  BASIC) run_job basic || result=$? ;;
  AI)    run_job ai    || result=$? ;;
  INFRA) run_job infra || result=$? ;;
  FULL)
    run_job basic || result=$?
    # 다른 수집이 돌고 있으면(75) AI 까지 미룬다. 기본이 실패해도 AI 의 미뤄진 정규화는 이어서 시도한다
    if [ "$result" -ne 75 ]; then
      ai_result=0; run_job ai || ai_result=$?
      [ "$result" -ne 0 ] || result=$ai_result
    fi ;;
  *) finish FAILED "알 수 없는 종류: $job_type"; exit 2 ;;
esac

if [ "$result" -eq 75 ]; then
  # 일일 수집 등 다른 실행이 잠금을 쥐고 있다. 요청을 되돌려 다음 분에 다시 집게 한다
  psql_q "update collection_request set status='PENDING', started_at=null, updated_at=now() where collection_request_id=$request_id;" >/dev/null
  echo "$(date +%FT%T) 요청 #$request_id 보류 (다른 수집 실행 중)"
  exit 0
fi
if [ "$result" -eq 0 ]; then finish DONE "정상 종료"; else finish FAILED "종료 코드 $result (logs/collector-admin.log 확인)"; fi
echo "$(date +%FT%T) 요청 #$request_id 끝 (코드 $result)"
exit "$result"
