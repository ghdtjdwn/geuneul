#!/usr/bin/env bash
# 프로덕션 RDS 무더위쉼터(safetydata.go.kr DSSP-IF-10942) 전국 인제스천 — ECS one-off task.
#
# safetydata 키는 data.go.kr 키와 별개다. 서비스 태스크 정의에 상시 배선하지 않고(런타임 서빙엔 불필요)
# 이 스크립트가 RunTask environment 오버라이드로 SAFETYDATA_SERVICE_KEY를 주입한다(규칙 D: 셸 env로만).
# 좌표(LA/LO)가 내장돼 지오코딩은 거의 안 탄다(결측 폴백만). deactivate-stale=true로 기존 100건 샘플을 대체한다.
#
# 사용법:
#   SAFETYDATA_SERVICE_KEY=<키> ./prod-ingest-shelter.sh
set -euo pipefail

: "${SAFETYDATA_SERVICE_KEY:?SAFETYDATA_SERVICE_KEY 환경변수 필요 (.local/safetydata.env 참고, 규칙 D)}"

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

# 키 + (있으면) 좌표결측 폴백 지오코딩 키를 환경 오버라이드로 주입.
ENV_JSON="{\"name\":\"SAFETYDATA_SERVICE_KEY\",\"value\":\"$SAFETYDATA_SERVICE_KEY\"}"
if [ -n "${KAKAO_REST_API_KEY:-}" ]; then
  ENV_JSON="$ENV_JSON,{\"name\":\"KAKAO_REST_API_KEY\",\"value\":\"$KAKAO_REST_API_KEY\"}"
  echo "==> KAKAO_REST_API_KEY 주입됨 (좌표 결측 폴백 지오코딩 활성)"
fi

echo "==> RunTask 실행 (source=shelter, deactivate-stale=true)"
TASK_ARN=$(aws ecs run-task --region $REGION \
  --cluster $CLUSTER \
  --task-definition geuneul \
  --launch-type FARGATE \
  --network-configuration "awsvpcConfiguration={subnets=[$SUBNET],securityGroups=[$SG],assignPublicIp=ENABLED}" \
  --overrides "{\"containerOverrides\":[{\"name\":\"geuneul\",\"command\":[\"--ingest.source=shelter\",\"--ingest.deactivate-stale=true\",\"--ingest.exit-after=true\",\"--server.port=8081\"],\"environment\":[$ENV_JSON]}]}" \
  --query 'tasks[0].taskArn' --output text)
echo "    task=$TASK_ARN"

echo "==> 완료 대기... (전국 6만건, 수 분)"
aws ecs wait tasks-stopped --region $REGION --cluster $CLUSTER --tasks "$TASK_ARN" || \
  echo "  (aws wait 10분 캡 — 아래 로그/exitCode로 최종 판정)"

EXIT_CODE=$(aws ecs describe-tasks --region $REGION --cluster $CLUSTER --tasks "$TASK_ARN" \
  --query 'tasks[0].containers[0].exitCode' --output text)
echo "==> 종료 코드: $EXIT_CODE"

echo "==> 인제스천 로그:"
TASK_ID="${TASK_ARN##*/}"
aws logs tail /ecs/geuneul --region $REGION --since 30m --format short \
  --log-stream-names "app/geuneul/$TASK_ID" 2>/dev/null | grep -E '\[shelter-api\]|\[ingest\]' || true

[ "$EXIT_CODE" = "0" ] && echo "✅ 성공" || { echo "❌ 실패/미완 — 위 로그 확인"; exit 1; }
