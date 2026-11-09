#!/usr/bin/env bash
# 프로덕션 RDS 공공데이터 인제스천 — ECS one-off task (RunTask 커맨드 오버라이드).
#
# RDS는 프라이빗 서브넷이라 로컬에서 직접 접속 불가 → 같은 VPC의 Fargate 태스크로 실행한다.
# 서비스와 동일한 태스크 정의(이미지·SSM 비밀·SG)를 재사용하므로 별도 인프라가 필요 없다.
#
# 사용법:
#   ./prod-ingest.sh <source> <csv-url> <sha256> [charset]
#   ./prod-ingest.sh public_toilet  https://github.com/.../toilets.csv <64자리-sha256> MS949
#   ./prod-ingest.sh cooling_shelter https://.../shelters.csv <64자리-sha256> UTF-8
set -euo pipefail

SOURCE="${1:?source 필요 (cooling_shelter | public_toilet)}"
URL="${2:?csv url 필요}"
SHA256="${3:?csv sha256 필요}"
CHARSET="${4:-UTF-8}"

case "$SOURCE" in
  cooling_shelter|public_toilet) ;;
  *) echo "지원하지 않는 source: $SOURCE" >&2; exit 2 ;;
esac
case "$CHARSET" in
  UTF-8|MS949|EUC-KR) ;;
  *) echo "지원하지 않는 charset: $CHARSET" >&2; exit 2 ;;
esac
if [[ ! "$URL" =~ ^https:// ]]; then
  echo "csv url은 HTTPS여야 합니다" >&2
  exit 2
fi
case "$URL" in
  *[[:space:]]*|*\"*|*\\*|*\?*|*\#*) echo "csv url에 허용되지 않는 문자가 있습니다" >&2; exit 2 ;;
esac
SHA256="$(printf '%s' "$SHA256" | tr '[:upper:]' '[:lower:]')"
if [[ ! "$SHA256" =~ ^[0-9a-f]{64}$ ]]; then
  echo "sha256은 64자리 hex여야 합니다" >&2
  exit 2
fi

REGION=ap-northeast-2
CLUSTER=geuneul

echo "==> 네트워크 파라미터 조회 (terraform 태그 기준)"
SUBNET=$(aws ec2 describe-subnets --region $REGION \
  --filters "Name=tag:Name,Values=geuneul-public-0" \
  --query 'Subnets[0].SubnetId' --output text)
SG=$(aws ec2 describe-security-groups --region $REGION \
  --filters "Name=group-name,Values=geuneul-ecs-sg" \
  --query 'SecurityGroups[0].GroupId' --output text)
echo "    subnet=$SUBNET sg=$SG"

# 지오코딩 필요한 소스(좌표 미제공 포맷)는 KAKAO_REST_API_KEY 환경변수를 가져간다.
# 키는 이 셸의 환경변수로만 전달 — 스크립트/레포에 하드코딩 금지(규칙 D).
ENV_OVERRIDE=""
if [ -n "${KAKAO_REST_API_KEY:-}" ]; then
  if [[ ! "$KAKAO_REST_API_KEY" =~ ^[A-Za-z0-9._~-]+$ ]]; then
    echo "KAKAO_REST_API_KEY 형식이 안전하지 않습니다" >&2
    exit 2
  fi
  ENV_OVERRIDE=",\"environment\":[{\"name\":\"KAKAO_REST_API_KEY\",\"value\":\"$KAKAO_REST_API_KEY\"}]"
  echo "==> KAKAO_REST_API_KEY 주입됨 (지오코딩 활성)"
fi

echo "==> RunTask 실행 (source=$SOURCE charset=$CHARSET)"
TASK_ARN=$(aws ecs run-task --region $REGION \
  --cluster $CLUSTER \
  --task-definition geuneul \
  --launch-type FARGATE \
  --network-configuration "awsvpcConfiguration={subnets=[$SUBNET],securityGroups=[$SG],assignPublicIp=ENABLED}" \
  --overrides "{\"containerOverrides\":[{\"name\":\"geuneul\",\"command\":[\"--ingest.source=$SOURCE\",\"--ingest.url=$URL\",\"--ingest.sha256=$SHA256\",\"--ingest.charset=$CHARSET\",\"--ingest.exit-after=true\",\"--server.port=8081\"]$ENV_OVERRIDE}]}" \
  --query 'tasks[0].taskArn' --output text)
echo "    task=$TASK_ARN"

echo "==> 완료 대기..."
aws ecs wait tasks-stopped --region $REGION --cluster $CLUSTER --tasks "$TASK_ARN"

EXIT_CODE=$(aws ecs describe-tasks --region $REGION --cluster $CLUSTER --tasks "$TASK_ARN" \
  --query 'tasks[0].containers[0].exitCode' --output text)
echo "==> 종료 코드: $EXIT_CODE"

echo "==> 인제스천 로그:"
TASK_ID="${TASK_ARN##*/}"
aws logs tail /ecs/geuneul --region $REGION --since 30m --format short \
  --log-stream-names "app/geuneul/$TASK_ID" 2>/dev/null | grep -E '\[ingest\]' || true

[ "$EXIT_CODE" = "0" ] && echo "✅ 성공" || { echo "❌ 실패 — 위 로그 확인"; exit 1; }
