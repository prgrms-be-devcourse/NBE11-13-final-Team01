#!/usr/bin/env bash
# 로컬 스크립트를 AWS SSM Run Command 로 EC2 에 보내 실행하고, 결과를 그대로 받아 온다.
#
#   INSTANCE_ID=i-0123... ./deploy/ssm-run.sh deploy/remote-deploy.sh "BACKEND_IMAGE=... WEB_IMAGE=..."
#
# SSH 포트를 열지 않고 서버에 명령을 전달하기 위한 수단이다.
# 이 파일만 교체하면 전달 수단(SSH 등)을 바꿀 수 있고 remote-deploy.sh 는 그대로 쓸 수 있다.
set -euo pipefail

INSTANCE_ID="${INSTANCE_ID:?INSTANCE_ID 가 필요합니다}"
SCRIPT="${1:?실행할 스크립트 경로가 필요합니다}"
ENV_LINES="${2:-}"          # "KEY=value" 를 줄바꿈으로 구분. 비밀값은 넣지 않는다.
SSM_TIMEOUT="${SSM_TIMEOUT:-900}"

[ -f "$SCRIPT" ] || { echo "::error::스크립트를 찾을 수 없습니다: $SCRIPT"; exit 1; }

# --- 1. 인스턴스가 SSM 에 붙어 있는지 먼저 확인한다 -------------------------
# 꺼져 있거나 SSM Agent·IAM 역할이 없으면 여기서 즉시 끝낸다. 배포를 시작조차 하지 않는다.
ping_status="$(aws ssm describe-instance-information \
  --filters "Key=InstanceIds,Values=${INSTANCE_ID}" \
  --query 'InstanceInformationList[0].PingStatus' --output text 2>/dev/null || echo "None")"

if [ "$ping_status" != "Online" ]; then
  echo "::error::EC2 가 SSM 에 연결돼 있지 않습니다 (PingStatus=${ping_status})."
  echo "EC2 가 꺼져 있거나, SSM Agent 가 실행 중이 아니거나, 인스턴스 IAM 역할이 없는 경우입니다."
  exit 1
fi
echo "SSM 연결 확인 (PingStatus=Online)"

# --- 2. 환경 변수 + 스크립트 본문을 하나의 명령으로 묶는다 ------------------
payload="$(mktemp)"
trap 'rm -f "$payload" "$params"' EXIT
{
  echo '#!/usr/bin/env bash'
  [ -n "$ENV_LINES" ] && printf 'export %s\n' $ENV_LINES
  cat "$SCRIPT"
} > "$payload"

# 따옴표·줄바꿈이 깨지지 않도록 JSON 은 jq 로 만든다.
params="$(mktemp)"
jq -n --rawfile body "$payload" --arg timeout "$SSM_TIMEOUT" \
  '{commands: [$body], executionTimeout: [$timeout]}' > "$params"

# --- 3. 실행 ---------------------------------------------------------------
command_id="$(aws ssm send-command \
  --instance-ids "$INSTANCE_ID" \
  --document-name AWS-RunShellScript \
  --comment "ZIPICK deploy" \
  --timeout-seconds 600 \
  --parameters "file://${params}" \
  --query 'Command.CommandId' --output text)"
echo "SSM Command ID: ${command_id}"

# --- 4. 끝날 때까지 기다린다 ------------------------------------------------
status="Pending"
deadline=$(( $(date +%s) + SSM_TIMEOUT ))
while [ "$(date +%s)" -lt "$deadline" ]; do
  status="$(aws ssm get-command-invocation \
    --command-id "$command_id" --instance-id "$INSTANCE_ID" \
    --query 'Status' --output text 2>/dev/null || echo "Pending")"
  case "$status" in
    Success|Failed|Cancelled|TimedOut) break ;;
  esac
  sleep 5
done

# --- 5. 서버 출력을 그대로 보여 준다 ----------------------------------------
echo
echo "----------------- 서버 출력 -----------------"
aws ssm get-command-invocation --command-id "$command_id" --instance-id "$INSTANCE_ID" \
  --query 'StandardOutputContent' --output text || true
stderr="$(aws ssm get-command-invocation --command-id "$command_id" --instance-id "$INSTANCE_ID" \
  --query 'StandardErrorContent' --output text 2>/dev/null || true)"
if [ -n "$stderr" ] && [ "$stderr" != "None" ]; then
  echo "----------------- 서버 오류 출력 -----------------"
  echo "$stderr"
fi
echo "---------------------------------------------"
echo "SSM 실행 상태: ${status}"

# SSM 의 출력 조회는 약 24,000자에서 잘린다. 전체 로그는 서버에서 docker compose logs 로 확인한다.
[ "$status" = "Success" ] || { echo "::error::서버에서 배포가 실패했습니다 (${status})."; exit 1; }
