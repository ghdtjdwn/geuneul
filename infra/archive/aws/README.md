# AWS 인프라 보관본 (폐기됨)

2026-09-03 production을 OCI Ampere A1으로 이전하면서 AWS ECS Fargate·RDS·ALB·CloudFront·ElastiCache 리소스는 모두 제거했다. 이 디렉터리의 Terraform(`terraform/`), 운영 스크립트(`scripts/`), `teardown.sh`는 그 당시 구성의 원본 기록이며 더 이상 적용하거나 CI에서 검증하지 않는다.

- 현재 운영 IaC는 [`infra/oci/`](../../oci/) 하나다.
- 이전 사양·검증 기록은 [docs/OCI-MIGRATION.md](../../../docs/OCI-MIGRATION.md), 결정 배경은 [ADR-0032](../../../docs/adr/0032-oci-arm64-self-hosted-migration.md)를 참고한다.
- 마지막 변경: 2026-09-02 (`git log -- infra/archive/aws`).
