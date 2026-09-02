# OCI production 배포

현재 production은 Vercel frontend/BFF와 OCI Ampere A1 backend로 나뉜다. 이 문서는 반복 가능한 현재 운영 절차만 다룬다. 폐기된 AWS 배포의 사양과 실제 이전 기록은 [AWS → OCI 마이그레이션 기록](./docs/OCI-MIGRATION.md)에 보존한다.

## 운영 구성

```text
Browser → Vercel Next.js BFF → OCI Caddy HTTPS
                                → Spring Boot app
                                   ├─ PostgreSQL + PostGIS
                                   ├─ Redis
                                   └─ OCI Object Storage
```

- Frontend는 Vercel이 Git 연동으로 배포한다.
- Backend·PostgreSQL·Redis는 OCI ARM64 VM의 전용 rootless Compose project다.
- Caddy는 다른 서비스와 공유하는 public edge이며, Geuneul app의 private host bind로만 proxy한다.
- PostgreSQL과 Redis는 Compose internal network에만 있다.
- 실제 host, account, OCID, bucket 이름과 secret은 GitHub Production environment와 server mode-0600 environment에서만 관리한다.

자세한 host·container·storage 구조는 [OCI 운영 구조와 용량](./docs/OCI-RUNTIME.md)을 참고한다.

## 배포 전 조건

1. 배포 대상은 `main`의 full Git SHA여야 한다.
2. GitHub `Production` environment는 main-only policy와 reviewer를 유지한다.
3. Backend test·coverage, OCI shell/Python test, Terraform validation이 통과해야 한다.
4. Server health timer와 최근 36시간 이내 verified backup이 정상이어야 한다.
5. Boot free 40GiB 이상, inode 사용률 90% 미만이어야 한다.
6. 배포 artifact의 ARM64 architecture, revision label, SHA-256이 대상 commit과 일치해야 한다.

## 일반 release

Actions의 **Deploy (OCI ARM64)** workflow를 `main` ref에서 수동 실행하고 operation을 고른다.

| operation | 동작 | 사용 시점 |
|---|---|---|
| `stage` | image와 release file을 검증·전송하지만 service는 바꾸지 않음 | 배포 artifact만 미리 준비할 때 |
| `deploy` | 검증·전송 후 pre-deploy backup을 확인하고 release 활성화 | 일반 production release |

일반 배포는 `deploy`다. Workflow는 직접 shell을 주지 않는 forced-command SSH gateway로만 접근한다. Gateway가 신뢰된 [`release-manager.sh`](./infra/oci/server/release-manager.sh)를 통해 [`remote-release.sh`](./infra/oci/server/remote-release.sh)의 `deploy`를 실행하며 다음 순서를 server 안에서 강제한다.

1. 현재 app을 멈춰 write를 동결한다.
2. PostgreSQL custom dump, checksum, table count를 만든다.
3. OCI Object Storage upload 후 remote size·retention·streaming SHA-256을 확인한다.
4. 새 image의 architecture·revision과 release file을 다시 검증한다.
5. 새 app을 시작하고 readiness를 확인한다.
6. 현재 release marker를 새 full SHA로 바꾼다.

Backup 검증이 Flyway 전에 실패하면 이전 app을 다시 시작한다. Flyway가 실행된 뒤 activation이 실패하면 이전 binary를 자동 기동하지 않는다. Schema와 binary 호환성을 추측한 자동 rollback이 데이터 손상을 만들 수 있기 때문이다.

## 최초 이전 전용 절차

`stage → start-data → restore → activate`는 AWS에서 가져온 DB와 object를 빈 OCI data layer에 넣고 최초 release를 연 마이그레이션 전용 절차였다. 이미 완료됐으며 반복 배포 runbook이 아니다. 실행 증거와 당시 불변 조건은 [OCI 마이그레이션 기록](./docs/OCI-MIGRATION.md)에 있다.

## 검증

배포 workflow와 server health가 성공한 뒤 실제 public path를 확인한다.

```bash
curl --fail --silent --show-error --max-time 20 \
  'https://geuneul.vercel.app/api/places?lat=37.5&lng=127.0&radius=100' \
  >/dev/null

curl --fail --silent --show-error --max-time 20 \
  'https://geuneul.vercel.app/' \
  >/dev/null
```

추가로 확인할 항목:

- OCI origin readiness와 active Git SHA/image revision
- Vercel BFF 장소 목록·상세·리뷰
- 로그인과 logout 뒤 이전 JWT 거부
- 새 사진 conditional PUT, upload claim, 중복 PUT 거부
- SSE와 Redis cache/rate limit
- DB·Redis container health와 restart count
- 최근 logical backup marker, Object Storage HEAD와 checksum
- 같은 VM의 MarketValley와 k3s 기존 health

브라우저 client bundle이 OCI hostname을 포함하면 실패다. 브라우저 API는 Vercel same-origin `/api/*`만 사용해야 한다.

## Backup과 복구

- `geuneul-backup.timer`: 매일 18:15 UTC, 최대 10분 randomized delay.
- `geuneul-health.timer`: 부팅 10분 뒤 시작하고 15분마다 실행.
- Remote logical backup lifecycle: 14일.
- Local 성공 산출물: 기본 3일.
- OCI boot volume backup: 주간 policy.

DB 복구는 production DB를 즉시 비우는 방식이 아니다. 검증된 dump를 별도 빈 PostgreSQL에 single transaction으로 복원하고 Flyway·PostGIS·SRID·table count를 확인한 뒤 전환 계획을 승인한다. Production DB 교체, restart, 실제 restore는 별도 production 변경 확인 대상이다.

## 공공데이터 ingestion

멱등 ingestion code와 PostgreSQL advisory lock은 OCI app에도 남아 있다. 그러나 AWS EventBridge/ECS one-off 자동 실행 경로는 AWS 제거와 함께 종료됐다. 현재 OCI systemd timer에는 backup과 health만 있고 월별 library ingestion 대체 timer는 없다.

- 현재 운영 데이터와 ingestion ledger는 보존돼 있다.
- 날씨는 요청 시 Redis TTL 기준으로 계속 갱신된다.
- 사용자 제보·후기는 API write 즉시 반영된다.
- 공공데이터 snapshot/API 자동 갱신은 OCI scheduler를 별도 설계·검증하기 전까지 실행되지 않는다.

대체 scheduler를 추가할 때는 기존 app image를 one-off로 실행하고 `IngestBatchLock`, secret 분리, complete-response 검증, 실행 원장, soft-delete 안전장치를 그대로 사용해야 한다. 이는 새 production 동작이므로 문서 수정과 별도로 승인·배포한다.

## 비용 경계

- Compute: 기존 Always Free Ampere A1 2 OCPU·12GB.
- Block storage: 200GB boot volume 하나, 추가 Block Volume 0개.
- Object Storage: photos와 backups 합계를 무료 한도 안에서 lifecycle로 관리.
- 별도 OCI Load Balancer, managed DB, 추가 VM은 사용하지 않는다.

OCI Console의 Always Free 표시, Limits/Quotas, Cost Analysis는 월별로 확인한다. 무료 한도는 서비스 정상 여부와 별개이므로 health 200만으로 무과금을 판단하지 않는다.
