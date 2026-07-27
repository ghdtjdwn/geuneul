# 배포 (AWS — IaC + push 자동배포)

> **ECS Fargate**(관리형 컨테이너) + **RDS PostgreSQL(PostGIS)** + **Terraform**(IaC) + **GitHub Actions OIDC**(키 없는 배포) + **ECR** + **ALB** + **CloudFront**(HTTPS).
> 비용 원칙: 신규 계정 Free plan 크레딧 + ECS 무료 control plane + NAT 게이트웨이 없음(Fargate는 퍼블릭 서브넷, SG로 잠금)으로 최소화한다. RDS·ElastiCache·ALB·Fargate·공인 IPv4는 개별 무료 리소스가 아니며 사용액이 크레딧에서 차감된다.
>
> ⚠️ 모든 비밀(DB 비번·키)은 SSM/환경변수로만. 레포 커밋 금지. `terraform.tfvars`·`*.tfstate`는 gitignore됨.

## 사전 준비
- AWS 계정(신규 Free plan이면 지급·활동 크레딧의 잔액과 만료일을 먼저 확인), AWS CLI 로그인(`aws configure` 또는 SSO).
- Terraform 설치(`brew install terraform`).

## 1. 인프라 프로비저닝 (Terraform)
```bash
cd infra/terraform
cp terraform.tfvars.example terraform.tfvars   # 필수 변수 전부 채우기 (gitignore됨 — db_password 외에도 OAuth/JWT/API 키 등, example 참고)
terraform init
terraform plan       # 생성될 리소스 리뷰 (VPC/RDS/ECS/ALB/ECR/IAM/ElastiCache/S3/EventBridge)
terraform apply
```
apply 후 output 확인:
- `alb_url` — 오리진 URL (공개 진입점은 CloudFront)
- `ecr_repository_url` — 이미지 push 대상
- `github_actions_role_arn` — 다음 단계에 사용

> RDS는 PostGIS를 Flyway `V1__enable_postgis.sql`(CREATE EXTENSION)로 활성화하므로 별도 작업 불필요.

## 2. GitHub → AWS 배포 연결 (OIDC, 키 없음)
1. GitHub 레포 **Settings → Secrets and variables → Actions** 에 시크릿 추가:
   - `AWS_ROLE_ARN` = Terraform output `github_actions_role_arn`
2. 끝. (액세스키를 저장하지 않는다 — OIDC로 그때그때 단기 자격증명 발급. 롤 trust는 `main` 브랜치 sub로 한정.)

## 3. 첫 배포 (이미지 채우기)
`terraform apply` 직후 ECR은 비어있어 ECS 태스크가 아직 못 뜬다. 이미지를 한 번 채우면 서비스가 안정화된다:
- `main`에 `backend/**` 변경을 push하거나, Actions에서 **Deploy (AWS ECS)** 를 `workflow_dispatch`로 수동 실행.
- 워크플로우가 테스트 게이트(실 PostGIS Testcontainers) → 이미지 빌드 → ECR push → ECS 태스크 리비전 갱신 → 롤링 배포.

## 4. 확인 & 이후
- `http://<alb_url>/actuator/health` → `{"status":"UP"}` (Flyway 마이그레이션 성공 = PostGIS·GiST 생성됨)
- `https://<cloudfront_domain>/swagger-ui.html` → 404 (운영 Swagger/OpenAPI 기본 차단). 로컬에서만
  `SPRINGDOC_ENABLED=true`로 활성화한다.
- **이후 `main`에 `backend/**` 변경이 push될 때마다 자동 재배포.** (문서·인프라만 바뀐 push는 `deploy.yml`의 paths 필터로 배포를 트리거하지 않음. CI(test)는 별도 `ci.yml`.)

### 보안 하드닝 rollout (V21, 운영 승인 필요)

이 저장소는 V21과 애플리케이션 변경만 준비한다. 다음 작업은 production migration/env/deploy이므로 실행 전 확인한다.

1. RDS가 `available`, `StorageEncrypted=true`, 자동 백업/PITR가 켜져 있는지 확인한다. 별도 SQL을 수동 실행하지 않는다.
2. backend SSM `/geuneul/proxy_secret`에 32자 이상의 새 값을 설정하고 backend를 먼저 rolling deploy한다.
   이 deploy가 Flyway V21(`users.token_version`, `photo_uploads`, `report_cache_generations`)을 한 번 적용한다.
   값을 명령행 기록·문서·로그에 출력하지 않는다.
3. 기존 frontend를 유지한 채 backend health·기존 API 호환성과 `POST /auth/logout`의 존재를 먼저 확인한다.
   backend에만 secret이 있는 이 구간은 증명 없는 기존 BFF 요청을 거부하지 않고, 위조 불가능한
   최우측 XFF/TCP peer bucket으로 축약해 rate limit 정밀도만 낮춘다.
4. 같은 값을 Vercel Production `GEUNEUL_PROXY_SECRET`에 설정한 뒤에만 새 frontend/BFF를 배포한다.
   frontend에 secret이 없으면 rate-limited BFF route가 500으로 fail-safe하므로 env 반영 전에 새 BFF를 배포하지 않는다.
5. health와 로그인→`POST /auth/logout`→기존 JWT 401, presign→`If-None-Match: *` S3 PUT→report/review 1회 성공을
   확인한다. 같은 claim 재사용과 발급 기록 없는 HTTPS 사진 URL은 400, 같은 key 재업로드는 S3 412여야 한다.
   cleanup metric `geuneul_photo_cleanup_objects_total`은 local/staging의 Prometheus opt-in 또는 승인된 OTLP exporter에서
   확인한다. 동일 OAuth 계정의 동시 첫 로그인은 user 1행으로 수렴하고, report 생성 직후
   AI summary/popular-times는 새 generation을 조회해야 한다. production `/actuator/prometheus`는 계속 404여야 한다.
6. frontend 배포 후 실패하면 frontend만 직전 deployment로 먼저 rollback하고 호환되는 새 backend는 유지한다.
   backend 검증 단계에서 실패했다면 frontend를 바꾸지 않은 채 ECS를 직전 task definition으로 rollback한다.
   V21은 additive라 table/column을 drop하지 않고 원인 수정 뒤 forward deploy한다.

Backend secret이 없으면 전달 IP 헤더를 신뢰하지 않아 공유 TCP-peer bucket으로 fail-safe한다.
Frontend secret이 없으면 새 BFF가 rate-limited route를 500으로 중단한다. 따라서 backend 먼저 배포·호환 검증 후
frontend secret 주입·BFF 배포 순서를 지켜야 한다.
Cleanup 장애 시 `PHOTO_CLEANUP_ENABLED=false`인 task revision으로 일시 중단할 수 있다. S3 object나 V21 row를 수동 삭제하지
않고 15분 lease retry와 `outcome="failed"` metric을 먼저 확인한다.

## 비용 메모
- 2026-07-27 서울 리전 정가와 라이브 구성 기준 상시 하한은 **약 $89.41/월 + 변동 사용량**이다. Fargate(0.5 vCPU/1GB) $20.72, ALB 기본료 $16.43, RDS 컴퓨트 $20.44 + gp3 20GB $2.62, ElastiCache $18.25, 공인 IPv4 3개 $10.95가 주요 항목이다. ECR·로그·S3·전송·ALB LCU는 별도다.
- 청구서가 $0이어도 사용액이 0인 것은 아니다. Free plan에서는 사용액을 크레딧으로 상쇄하며, 크레딧 소진 또는 플랜 만료 중 먼저 오는 시점에 계정 접근이 중단된다. 2026-07-27 실측은 잔액 $73.15, 최근 정상일 약 $2.94/일로 단순 환산 시 8월 21~22일 소진 예상이다.
- `geuneul-gross-usage-alert` Budget은 크레딧을 제외한 월 총사용량을 보고 실제 $40 또는 예상 $50 초과 시 기존 수신자에게 알린다. 기존 zero-spend Budget은 크레딧 이후 청구 감시용으로 유지한다.
- 오토스케일링(CPU 60% target-tracking, min1/max3)은 태스크가 늘 때마다 Fargate와 공인 IPv4 사용액이 추가된다. 비용 경보와 실제 트래픽을 확인하지 않고 부하를 오래 유지하지 않는다.
- **전체 내리기(상시 컴퓨트 비용 제거)**: `./infra/teardown.sh` — RDS 삭제보호 해제 + final 스냅샷 충돌 정리 + `terraform destroy`를 한 번에. 남긴 RDS 수동/final 스냅샷은 DB 삭제 뒤 백업 스토리지 비용이 생길 수 있으므로 복구 필요성과 비용을 별도로 확인한다. Vercel 프론트는 무료라 그대로 둬도 된다.
- **부활**: `cd infra/terraform && terraform apply` → 첫 배포 → 공공데이터 재적재(아래 '운영 인제스천'). CloudFront 도메인이 새로 발급되면 README 배지·Vercel `GEUNEUL_API_BASE`를 갱신한다.

## 운영 인제스천 (공공데이터 → 프로덕션 RDS)
RDS는 프라이빗 서브넷이라 로컬에서 직접 접속할 수 없다(의도된 보안 설계). 적재는 **같은 VPC 안의 ECS one-off task**로 실행한다 — 서비스와 동일한 태스크 정의(이미지·SSM 비밀·SG)를 재사용:

```bash
# 1) 데이터 스냅샷을 URL로 접근 가능하게 (GitHub Release 자산 권장 — 레포 비대화 방지 + 버저닝)
gh release create data-v1 shelters.csv toilets.csv --title "공공데이터 스냅샷 v1" --notes "무더위쉼터/공중화장실 표준데이터"

# 2) 릴리즈 자산의 SHA-256을 고정한 뒤 one-off task 실행
# macOS: shasum -a 256 shelters.csv / Linux: sha256sum shelters.csv
./infra/scripts/prod-ingest.sh cooling_shelter <shelters.csv 릴리즈 URL> <sha256> UTF-8
# 공중화장실은 59,768행 전량 좌표 미제공 → 카카오 지오코딩 필수(ADR-0003).
# REST 키는 셸 환경변수로만 전달(스크립트가 태스크에 주입 — 레포 하드코딩 금지):
KAKAO_REST_API_KEY=<카카오 REST 키> ./infra/scripts/prod-ingest.sh public_toilet <toilets.csv 릴리즈 URL> <sha256> MS949
```
태스크는 다운로드한 바이트의 SHA-256이 기대값과 다르면 DB 변경 전에 실패한다. 멱등(ON CONFLICT upsert)이므로
같은 digest 재실행·검증된 새 스냅샷 갱신 모두 같은 명령이다. 도서관(오픈API 전량 수집)은 EventBridge Scheduler가
월 1회 무인 동기화한다(ADR-0011).

### 데이터 갱신 기준

| 데이터 | 반영 방식 | 현재 운영 기준 |
|---|---|---|
| 도서관 | data.go.kr API → ECS one-off task | 자동. EventBridge Scheduler가 매월 2일 KST 04:00에 전체 수집·soft-delete 동기화 |
| 날씨 | 기상청 API → Redis | 자동. 요청 시 조회하고 30분 TTL이 지나면 다음 요청에서 새 데이터로 갱신 |
| 사용자 제보 | 서비스 API → RDS | 자동. 등록 즉시 반영하며 급증 알림은 SSE로 전달 |
| 무더위쉼터·공중화장실 | GitHub Release CSV → ECS one-off task | 수동. 새 스냅샷을 릴리즈에 올린 뒤 `prod-ingest.sh` 실행 |
| 카페·스터디카페 | 상권정보 API → ECS one-off task | 수동. API 이용 조건과 수집 범위를 확인한 뒤 `prod-ingest-stores.sh` 실행 |

즉, 원본 API가 계속 바뀌는 도서관·날씨와 서비스 안에서 생기는 제보는 자동 반영한다. 고정 CSV 스냅샷이나 이용 조건을 확인해야 하는 상권 데이터는 원본을 검토한 뒤 수동으로 갱신한다. 이 구분은 무의미한 재적재와 외부 API 호출 비용을 피하기 위한 운영 정책이다.

## HTTPS/도메인
공개 진입점은 **CloudFront 기본 도메인(무료 HTTPS)** — ALB(http)는 오리진으로만 쓴다(ADR-0015). 커스텀 도메인이 생기면 CloudFront에 CNAME+ACM(us-east-1)을 붙이거나 ALB 443 리스너로 전환한다.
