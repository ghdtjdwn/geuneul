#!/usr/bin/env bash
# 프로덕션 RDS 상권정보(CAFE + STUDY_CAFE) 인제스천 — ECS one-off task (RunTask 커맨드 오버라이드).
#
# 상권정보 오픈API(B553077)는 URL/CSV가 아니라 반경/격자 호출이라 prod-ingest.sh(CSV용)와 분리했다.
# DATA_GO_KR_SERVICE_KEY는 서비스 태스크 정의(rev41+)에 SSM 시크릿으로 이미 배선돼 있어 별도 주입이 불필요하다.
# 카페·스터디카페는 WGS84 좌표를 직접 제공하므로 지오코딩(카카오)은 사실상 안 탄다(결측 시 폴백만).
#
# 사용법:
#   ./prod-ingest-stores.sh <minLng,minLat,maxLng,maxLat> [radiusMeters]
#   ./prod-ingest-stores.sh 126.76,37.42,127.19,37.71 1500      # 서울 전역(동작구 필드테스트 포함)
#   ./prod-ingest-stores.sh 126.90,37.48,126.99,37.52 1500      # 동작구(상도·노량진)만 빠르게
set -euo pipefail

BBOX="${1:?bbox 필요: minLng,minLat,maxLng,maxLat}"
RADIUS="${2:-1500}"

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

# 좌표 결측 폴백 지오코딩용(있으면 주입, 없으면 생략) — 규칙 D: 키는 셸 환경변수로만.
ENV_OVERRIDE=""
if [ -n "${KAKAO_REST_API_KEY:-}" ]; then
  ENV_OVERRIDE=",\"environment\":[{\"name\":\"KAKAO_REST_API_KEY\",\"value\":\"$KAKAO_REST_API_KEY\"}]"
  echo "==> KAKAO_REST_API_KEY 주입됨 (좌표 결측 폴백 지오코딩 활성)"
fi

echo "==> RunTask 실행 (source=stores bbox=$BBOX radius=${RADIUS}m)"
TASK_ARN=$(aws ecs run-task --region $REGION \
  --cluster $CLUSTER \
  --task-definition geuneul \
  --launch-type FARGATE \
  --network-configuration "awsvpcConfiguration={subnets=[$SUBNET],securityGroups=[$SG],assignPublicIp=ENABLED}" \
  --overrides "{\"containerOverrides\":[{\"name\":\"geuneul\",\"command\":[\"--ingest.source=stores\",\"--ingest.bbox=$BBOX\",\"--ingest.radius=$RADIUS\",\"--ingest.exit-after=true\",\"--server.port=8081\"]$ENV_OVERRIDE}]}" \
  --query 'tasks[0].taskArn' --output text)
echo "    task=$TASK_ARN"

echo "==> 완료 대기... (격자 셀 수에 따라 수 분)"
aws ecs wait tasks-stopped --region $REGION --cluster $CLUSTER --tasks "$TASK_ARN"

EXIT_CODE=$(aws ecs describe-tasks --region $REGION --cluster $CLUSTER --tasks "$TASK_ARN" \
  --query 'tasks[0].containers[0].exitCode' --output text)
echo "==> 종료 코드: $EXIT_CODE"

echo "==> 인제스천 로그:"
TASK_ID="${TASK_ARN##*/}"
aws logs tail /ecs/geuneul --region $REGION --since 30m --format short \
  --log-stream-names "app/geuneul/$TASK_ID" 2>/dev/null | grep -E '\[store-api\]|\[ingest\]' || true

[ "$EXIT_CODE" = "0" ] && echo "✅ 성공" || { echo "❌ 실패 — 위 로그 확인"; exit 1; }
