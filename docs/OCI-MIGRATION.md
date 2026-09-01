# AWS → OCI 무손실 마이그레이션 런북

이 문서는 AWS 백엔드를 OCI로 옮기는 실행 순서와 증거를 정의한다. 값이 있는 환경 파일, dump, object inventory, Terraform state는 커밋하지 않는다. 공개 문서에는 secret·OCID·IP·개인정보를 기록하지 않는다.

## 현재 상태

- 완료: OCI S3 호환 코드, native ARM64 PostGIS 이미지, 제한된 Compose, Object Storage Terraform, DB backup/restore/verify, S3 왕복 SHA-256 검증 스크립트, 제한된 SSH release gateway와 수동 stage/deploy workflow
- 로컬 실증: ARM64 전체 스택 health, Flyway V21, 분리된 빈 DB 복원과 전 테이블 행 수·제약·인덱스·SRID 검증
- 라이브 확인: OCI A1 2 OCPU/12GB·Ubuntu 22.04 ARM64, boot 200GB, 별도 data volume 50GB 중 약 47GB 여유, 기존 NLB 80/443와 backend health 정상, Object Storage bucket 0개, 계획 포트 13880 미사용
- 용량 경계: 기존 k3s CPU request 1,920m/2,000m(96%)와 Pending Pod 2개가 이미 존재한다. Geuneul은 k3s 밖의 별도 rootless cgroup 0.75 CPU·3GB로 격리하지만 실제 host CPU contention은 안정화 관찰 대상이다.
- 대기: AWS paid account 재개 승인, AWS 원본 inventory/export, OCI live bucket·Customer Secret Key, server bootstrap 실제 적용, Caddy route, Vercel cutover

## 불변 조건

1. AWS ECS 쓰기를 멈추기 전에는 최종 dump를 만들지 않는다.
2. RDS snapshot, dump SHA-256, source table counts, S3 source inventory 중 하나라도 없으면 복원을 시작하지 않는다.
3. restore는 앱이 정지된 빈 OCI DB에서만 실행한다.
4. source/target table counts와 object key·size·SHA-256이 모두 일치하기 전에는 Vercel을 바꾸지 않는다.
5. Vercel 사용자 흐름과 rollback을 확인하기 전에는 AWS 원본·snapshot·S3 object를 삭제하지 않는다.
6. 모든 production 변경은 적용 직전 plan 또는 exact target을 다시 확인하고, 값이 있는 로그를 남기지 않는다.

## 1. AWS 계정 복구와 동결

1. Billing 화면에서 paid plan 전환 비용을 승인받고 계정을 재개한다. 2026-09-01 확인 시 account는 suspended/closed 상태이고 미결제 잔액은 0원이므로, 결제 체납 처리보다 plan 재활성화 경로를 따른다.
2. RDS, ECS service/task, S3, ECR, SSM, CloudFront, ALB, ElastiCache, EventBridge inventory를 timestamp와 함께 `.local/oci-migration/aws-inventory/`에 저장한다.
3. 기존 health와 RDS 상태를 읽기 전용으로 확인한다.
4. ECS desired count와 scheduled ingestion을 0/disabled로 바꿔 쓰기를 동결한다. 프론트 BFF가 실패하는 현재 상태를 더 악화시키지 않도록 상태 코드를 기록한다.
5. 암호화된 final manual RDS snapshot을 만든다. snapshot availability를 확인한 뒤에만 논리 export를 시작한다.

RDS는 private subnet이므로 VPC 내부의 one-off ECS export task에서 PostgreSQL 16 `pg_dump`를 실행하고, 임시 S3 migration prefix에 dump·SHA-256·table counts를 업로드한다. RDS snapshot export-to-S3는 Parquet이므로 복원 입력으로 쓰지 않는다.

## 2. AWS 데이터 반출

1. dump와 checksum을 로컬 `.local/oci-migration/database/`로 내려받고 `sha256sum --check`를 통과시킨다.
2. `pg_restore --list`가 archive를 읽는지 확인한다.
3. 사진은 별도 mode-0600 source/target credential 파일로 이동한다.

```bash
OBJECT_MIGRATION_CONFIRM=MIGRATE_GEUNEUL_OBJECTS \
  infra/oci/scripts/migrate-objects.sh \
  /absolute/aws-source.env \
  /absolute/oci-target.env \
  /absolute/new-empty-staging-directory
```

스크립트는 AWS를 삭제하지 않고 OCI에서 다시 내려받아 모든 파일 SHA-256까지 대조한다.

## 3. OCI 기반 준비

1. `infra/oci/terraform/terraform.tfvars.example`을 `.local`에 복사해 실제 compartment와 이름을 채운다.
2. `terraform init`, `fmt -check`, `validate`, 저장 plan을 검토한 뒤 photos/backups bucket과 lifecycle만 적용한다.
3. OCI에는 bucket CORS API가 없으므로 `infra/oci/caddy/geuneul.caddy.example`의 `/object-storage/*` gateway를 기존 Caddy에 합친다. Vercel production origin의 OPTIONS/PUT만 CORS로 허용하고, upstream은 고정 OCI S3 endpoint·Host로 설정한다. gateway에는 저장소 자격증명을 두지 않는다.
4. photo app user와 backup writer user에 각각 별도 OCI Customer Secret Key를 만들고 즉시 server mode-0600 environment에 저장한다. app user는 photos bucket만, backup writer는 backups bucket의 non-delete 권한만 가져야 한다. secret은 다시 볼 수 없으므로 안전한 로컬 secret store에도 1회 백업한다.
5. 배포 전용 Ed25519 key pair를 만들고 public key만 서버 임시 경로로 전달한다. private key는 로컬과 GitHub production environment secret에만 저장한다.
6. `GEUNEUL_DEPLOY_PUBLIC_KEY_FILE`을 지정해 `infra/oci/server/bootstrap-ubuntu-rootless.sh`를 root로 실행한다. 스크립트는 기존 `/opt/marketvalley`를 포맷하거나 재마운트하지 않고 전용 rootless user·`/opt/marketvalley/geuneul`·forced-command gateway를 만든다. user cgroup은 memory 3GiB, CPU 75%, swap 0이고 공유 volume root ACL은 해당 user의 path traversal만 허용한다.
7. `/opt/marketvalley/geuneul/shared/production.env`의 모든 placeholder를 실제 값으로 교체하고 mode 0600을 재확인한다. `infra/oci/scripts/validate-runtime.sh`가 성공하기 전에는 release를 activate하지 않는다.
8. GitHub production environment에 `OCI_DEPLOY_HOST`, `OCI_DEPLOY_USER`, `OCI_DEPLOY_SSH_PRIVATE_KEY`, pin된 `OCI_DEPLOY_HOST_KEY`를 등록한다. 첫 workflow dispatch는 반드시 `stage`로 실행한다. workflow는 ARM64 image archive를 checksum 검증해 적재한 뒤 restricted `start-data`로 PostgreSQL·Redis만 `--no-build` 기동하며 app은 시작하지 않는다.
9. `/opt/marketvalley/geuneul/releases/<full-git-sha>/infra/oci/compose.production.yml`을 source of truth로 아래 절차에서 DB를 복원한다. 검증이 끝난 뒤 restricted gateway의 `activate <full-git-sha>`로 같은 release를 시작한다. 이후 배포는 workflow의 `deploy`가 stage·health·자동 복귀를 수행한다.
10. Caddy에 새 HTTPS hostname과 VM private high-port upstream을 추가하고 Caddy validate 후 reload한다.
11. bootstrap 전후 `/opt/marketvalley`의 실제 free space를 기록한다. stage는 12GiB, data start는 10GiB, activate와 상시 health는 8GiB 미만이면 실패해야 한다. 이 gate를 낮추지 않는다.
12. 기존 k3s의 CPU request·Pending Pod와 host `MemAvailable`을 다시 기록한다. Geuneul 기동 뒤 기존 workload의 Pending/Unknown 수가 증가하거나 memory available이 1GiB 아래로 내려가면 activate를 중단하고 Geuneul을 정지한다.

## 4. DB 복원과 병렬 검증

```bash
infra/oci/scripts/validate-runtime.sh /absolute/production.env

docker compose --env-file /absolute/production.env \
  -f infra/oci/compose.production.yml up -d --build postgres redis

RESTORE_CONFIRM=RESTORE_GEUNEUL \
  infra/oci/scripts/restore-database.sh \
  /absolute/production.env \
  /absolute/geuneul.dump \
  /absolute/geuneul.dump.sha256

infra/oci/scripts/verify-database.sh \
  /absolute/production.env \
  /absolute/source.table-counts.tsv \
  /absolute/target.table-counts.tsv

docker compose --env-file /absolute/production.env \
  -f infra/oci/compose.production.yml up -d app
```

OCI origin에서 아래를 확인한다.

- readiness `UP`, Flyway 최신 migration success
- `/places`, bounds/radius/kNN search, place detail
- 기존 AWS 사진 GET 서명이 OCI endpoint를 가리키고 실제 이미지가 열림
- 새 report/review 사진 gateway presign PUT, exact-origin preflight, upstream Host 보존, 중복 PUT 412, HEAD claim 검증
- 로그인·로그아웃·기존 JWT·OAuth, report/review/bookmark/follow/notification
- Redis rate limit과 캐시, SSE LISTEN/NOTIFY, scheduled ingestion dry run
- 컨테이너 memory/CPU/PID, PostgreSQL connection와 volume 여유, Caddy access/error log

## 5. Git·CI·배포와 Vercel 컷오버

1. feature branch에서 backend full gate, frontend gate, Terraform validate, shell/Python tests, ARM64 PostGIS smoke와 backend image build를 통과시킨다.
2. secret scan, 의도한 파일만 commit/push, PR checks를 확인하고 merge한다.
3. merge SHA의 OCI workflow를 `stage`로 실행하고 DB/object 복원 뒤 같은 SHA를 `activate`한다. 일반 release부터는 `deploy`를 사용한다. 서버가 보고하는 `current` SHA와 merge SHA가 같아야 한다.
4. Vercel `GEUNEUL_API_BASE`를 OCI HTTPS origin으로 변경하고 production redeploy한다.
5. Vercel same-origin `/api/*`를 통해 위 사용자 흐름을 다시 실행한다. 브라우저가 OCI origin을 직접 API base로 호출하면 실패다.

rollback은 Vercel environment를 기존 CloudFront origin으로 되돌리고 redeploy하는 한 단계다. 단, AWS ECS/RDS가 동작하고 최종 dump 이후 OCI에만 생긴 write가 없을 때만 무손실 rollback이다. 컷오버 직후 write가 생기면 OCI가 새 source of truth이며 AWS로 단순 복귀하지 않는다.

## 6. 백업과 AWS 정리

1. `backup-database.sh`로 첫 OCI logical backup을 만들고 Object Storage HEAD size를 검증한다.
2. 별도 빈 DB에서 최신 backup restore drill을 한 번 더 통과시킨다.
3. 안정화 구간 동안 error rate, health, container restart, DB/Redis memory와 사용자 흐름을 관찰한다.
4. AWS final snapshot·dump·S3 source manifest를 남긴 상태에서 ECS, ElastiCache, ALB/CloudFront, NAT 등 비용 리소스를 Terraform plan으로 단계 정리한다.
5. AWS source S3 삭제는 OCI object SHA-256과 off-host backup을 다시 확인하고 별도 파괴 승인 후에만 한다.
6. 마지막 AWS bill, 잔존 resource inventory, OCI backup/restore evidence, Vercel production URL을 WORKLOG에 기록한다.

`activate`는 첫 backup success marker가 없으면 즉시 한 번 백업하고, 이후 `geuneul-backup.timer`가 매일 실행한다. 아래 상태는 안정화와 운영 점검의 필수 증거다.

```bash
systemctl --user status geuneul-backup.timer geuneul-health.timer
systemctl --user status geuneul-backup.service geuneul-health.service
journalctl --user -u geuneul-backup.service -u geuneul-health.service --since '48 hours ago'
```

health timer는 8GiB free-space, active Git SHA/image, app health, backup timer, 36시간 이내 verified backup marker를 확인한다. Object Storage lifecycle은 remote 14일, 서버 로컬 산출물은 성공한 off-host upload 뒤 기본 3일 보존한다.
