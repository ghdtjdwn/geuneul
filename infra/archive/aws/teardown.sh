#!/usr/bin/env bash
# 그늘 AWS 인프라 전체 내리기 → 상시 비용 $0.
#
# 언제: 크레딧 절약/프로젝트 종료 시. 다음 세션에서 "서버 꺼줘/내려줘"라고 하면 이 스크립트를 실행한다.
# 무엇: VPC·ALB·ECS(Fargate)·RDS·ElastiCache·S3·SSM·EventBridge·CloudFront 등 Terraform이 만든 전부를 destroy.
# 남는 것: RDS 데이터 스냅샷(geuneul-db-final, geuneul-db-encrypted)은 부활용으로 남긴다(스토리지 ~$0.4/월, 거의 0).
#         완전 $0을 원하면 아래 '스냅샷까지 삭제' 블록의 주석을 해제한다(그러면 부활 시 데이터 재적재 필요).
# 프론트: Vercel(geuneul.vercel.app)은 무료(hobby)라 그대로 둔다 — 백엔드가 내려가면 데이터만 안 뜨고 셸은 살아있다.
#
# 부활: `cd infra/terraform && terraform apply` → 첫 배포(deploy.yml) → 공공데이터 재적재(DEPLOY.md '운영 인제스천').
#       CloudFront 도메인이 새로 발급되면 README 배지·Vercel GEUNEUL_API_BASE를 갱신한다.
set -uo pipefail
cd "$(dirname "$0")/terraform"

echo "▶ 1/4  RDS 삭제보호 해제 (deletion_protection=true면 destroy가 막힌다)"
aws rds modify-db-instance --db-instance-identifier geuneul-db \
  --no-deletion-protection --apply-immediately >/dev/null 2>&1 || true
aws rds wait db-instance-available --db-instance-identifier geuneul-db 2>/dev/null || true

echo "▶ 2/4  기존 final 스냅샷 제거 (destroy가 같은 이름으로 다시 만들려다 충돌하는 것 방지)"
aws rds delete-db-snapshot --db-snapshot-identifier geuneul-db-final >/dev/null 2>&1 || true

echo "▶ 3/4  terraform destroy (전체 인프라)"
terraform destroy -auto-approve

echo "▶ 4/4  완료 — 상시 비용 \$0."
echo "   데이터 스냅샷(geuneul-db-final 등)은 남겨 뒀다. 부활: 'terraform apply' 후 DEPLOY.md 참고."

# ── 완전 $0 (스냅샷까지 삭제, 부활 시 데이터 재적재 필요) — 필요할 때만 주석 해제 ──
# aws rds delete-db-snapshot --db-snapshot-identifier geuneul-db-final     >/dev/null 2>&1 || true
# aws rds delete-db-snapshot --db-snapshot-identifier geuneul-db-encrypted >/dev/null 2>&1 || true
