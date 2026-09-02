# ADR-0032 — Vercel 프론트 유지 + OCI ARM64 자가운영 백엔드로 무손실 이전

- 상태: 승인, 구현·로컬 복원 검증 완료 / 라이브 데이터 이전 대기
- 날짜: 2026-09-01
- 관련: ADR-0004(동일 오리진 BFF), ADR-0029(RDS 스냅샷 복원), ADR-0031(세션·업로드 경계), TS-040

## 문제

AWS Free plan 종료로 ECS·RDS·ElastiCache·S3 기반 백엔드가 중단됐다. Vercel 프론트와 BFF는 별도 배포라 계속 열리지만, API와 운영 데이터는 AWS 계정 복구 전까지 접근할 수 없다. 사용자·후기·제보·알림·업로드 claim과 사진을 잃지 않고 이미 보유한 OCI Ampere A1 서버로 옮겨야 한다.

제약은 다음과 같다.

- 프론트 URL·OAuth callback·동일 오리진 BFF 계약은 유지해야 한다.
- 기존 OCI 서버는 Kubernetes와 별도 rootless Compose 서비스를 함께 운영하므로 CPU·메모리·포트·데이터 디렉터리를 격리해야 한다.
- OCI A1은 ARM64다. `postgis/postgis` 공식 이미지는 2026-09 현재 amd64만 지원한다.
- 사진 DB 열에는 AWS S3 raw URL이 저장돼 있다. 일괄 UPDATE는 롤백 범위와 데이터 변경 위험을 키운다.
- AWS 원본은 OCI 복원과 무결성 검증이 끝날 때까지 삭제할 수 없다.
- 정상 운영의 AWS·OCI 인프라 비용은 0원이어야 한다. AWS paid plan은 정지된 원본을 반출하는 짧은 복구 구간에만 허용하고, OCI도 Always Free 실측 한도를 넘기면 컷오버하지 않는다.

## 결정

### 0. 무손실 이전과 상시 0원 운영을 별도 게이트로 보장한다

AWS Free plan이 끝난 계정은 paid plan으로 재개해야 보존된 RDS·S3 원본을 내려받을 수 있다. 이 전환은 AWS를 계속 운영하기 위한 것이 아니라 final dump·snapshot·object manifest를 확보하기 위한 일회성 data rescue다. 반출과 OCI 검증이 끝나면 AWS 비용 리소스를 즉시 정리하고 account를 다시 닫는다.

OCI의 현재 Always Free 한도는 A1 2 OCPU/12GB, home region의 boot+block volume 합계 200GB, Object Storage 합계 20GB다. live metadata에서 compute는 정확히 2 OCPU/12GB지만 attached disk는 boot 200GB와 data 50GB로 합계 250GB다. root filesystem은 약 145GiB가 비어 있고 data volume 실제 사용량은 약 2GiB이므로, Geuneul을 activate하기 전에 기존 data를 boot volume으로 two-pass copy하고 checksum·서비스 read-back을 통과한 뒤 50GB volume을 분리해야 한다. 분리한 volume 삭제는 rollback 관찰 뒤 별도 파괴 승인으로 실행한다. 이 정리가 끝나 실제 boot+block 합계가 200GB 이하가 되기 전에는 Geuneul 운영 배포를 시작하지 않는다.

photos, version history와 database backup lifecycle의 합계도 20GB를 넘을 수 없다. AWS source object 총량과 초기 dump·14일 보존 예상량을 계산해 20GB 미만임을 증명하고, OCI Cost Analysis에서 billable resource가 없음을 확인한다. 둘 중 하나라도 실패하면 retention을 몰래 줄이거나 유료 사용을 허용하지 않고 컷오버를 중단해 용량 결정을 다시 받는다.

### 1. 프론트와 백엔드는 기존처럼 분리한다

Next.js 프론트와 서버 BFF는 Vercel에 유지한다. `GEUNEUL_API_BASE`만 AWS CloudFront에서 OCI의 HTTPS origin으로 바꾼다. 브라우저는 계속 같은 Vercel `/api/*`만 호출하므로 CORS·OAuth callback·세션 쿠키의 공개 계약이 변하지 않는다.

OCI에서는 기존 public NLB와 Caddy를 재사용해 새 hostname을 SNI로 분기하고, Spring Boot는 VM 사설 주소의 고포트에만 bind한다. 새 load balancer를 만들지 않아 비용과 운영 표면을 늘리지 않는다.

### 2. PostgreSQL·Redis·Spring Boot를 제한된 rootless Compose로 운영한다

Spring Boot, PostgreSQL 16/PostGIS, Redis 7.4를 하나의 Compose 스택으로 묶되 데이터 네트워크는 `internal`로 두고 앱만 egress 네트워크에 연결한다. 앱 1GiB/0.55 CPU, PostgreSQL 1.25GiB/0.35 CPU, Redis 192MiB/0.10 CPU를 상한으로 둔다. 앱은 read-only root filesystem, capability 전체 제거, non-root, PID 제한을 적용한다.

PostgreSQL bootstrap admin과 앱 역할을 분리한다. `geuneul_app`은 DB와 Flyway 객체를 소유하지만 cluster superuser가 아니다. Redis는 외부 포트를 열지 않고 내부 네트워크에서만 사용한다.

### 3. 공식 PostgreSQL ARM64 기반 PostGIS 이미지를 직접 재현한다

`postgis/postgis`의 amd64 이미지를 QEMU로 돌리지 않는다. 공식 multi-architecture `postgres:16.15-bookworm` digest를 기반으로 PGDG의 ARM64 `postgresql-16-postgis-3` 3.6.4 패키지를 정확한 버전으로 설치한다. 같은 Dockerfile은 amd64와 arm64에서 모두 빌드된다.

PostGIS 프로젝트의 공식 Dockerfile도 공식 PostgreSQL base에 PGDG 패키지를 설치하는 구조지만, 배포 이미지는 amd64만 게시한다. 이 결정은 그 공급망을 최소한으로 재현하면서 OCI에서 native ARM64를 보장한다.

### 4. 사진은 OCI Object Storage S3 compatibility API로 옮긴다

AWS SDK v2 client와 presigner에 endpoint override, path-style 설정, OCI region을 주입한다. OCI는 native/S3 compatibility API 모두 bucket CORS 설정을 제공하지 않으므로 브라우저 PUT URL만 기존 Caddy의 `/object-storage/*` gateway origin으로 바꾼다. gateway는 exact Vercel origin preflight와 응답 header만 제공하고, path/query를 고정 OCI endpoint로 전달하면서 upstream Host를 서명 당시 값으로 복원한다. 자격증명이 없으므로 OCI가 SigV4·조건부 생성·타입·길이를 계속 검증한다.

photo app과 backup writer는 별도 OCI IAM user/group/Customer Secret Key를 쓴다. 앱은 photos bucket만 관리하고 backup writer는 backups bucket에서 delete를 제외한 권한만 가진다. 버킷은 Terraform으로 비공개·버전 관리·lifecycle을 설정한다. secret은 서버 mode 0600 환경 파일에만 두고 코드·Terraform state·GitHub에는 넣지 않는다.

DB의 기존 AWS URL은 바꾸지 않는다. `S3_LEGACY_BASE_URLS`가 기존 URL에서 object key를 추출하고 OCI에 대해 새 GET 서명을 만든다. 새 업로드만 OCI raw URL로 저장한다. 따라서 데이터 UPDATE 없이 즉시 롤백할 수 있다.

### 5. 논리 백업과 객체 왕복 검증으로 이전한다

AWS 쓰기를 먼저 멈춘 뒤 PostgreSQL 16 client로 `pg_dump --format=custom --no-owner --no-acl`을 만든다. SHA-256과 모든 public table의 정확한 행 수를 함께 보존한다. OCI restore는 빈 DB에서만 허용하고 한 transaction으로 실행한다. 대상에 미리 설치된 PostGIS의 `spatial_ref_sys` 데이터와 확장 주석은 복원 대상에서 제외한다.

복원 뒤에는 테이블별 행 수, Flyway 최신 성공, 제약·인덱스 수, PostGIS extension과 장소 SRID 4326을 검증한다. 사진은 AWS→로컬→OCI→별도 검증 다운로드 순서로 이동하며 key·size·각 파일 SHA-256이 모두 같아야 성공으로 판정한다. 어떤 스크립트도 AWS 원본을 삭제하지 않는다.

운영 백업은 매일 custom dump·checksum·행 수를 별도 private Object Storage bucket에 올리고 14일 보존한다. Object Storage의 photos·version history·database backup 합계가 Always Free 20GB를 넘으면 backup 또는 activate를 중단한다. 이전 Block Volume과 그 backup은 zero-cost audit과 별도 파괴 승인 전까지만 rollback 자산으로 보존하고 장기 백업 계층으로 가정하지 않는다.

### 6. 빌드는 GitHub에서, 운영 실행은 제한된 rootless 계정에서 한다

GitHub Actions는 QEMU/buildx로 backend와 PostGIS의 ARM64 image archive를 만들고, image architecture·backend revision label·각 archive SHA-256을 확인한다. release archive는 경로 이동·심볼릭 링크·파일/전체 크기를 제한하는 extractor를 통과한 뒤에만 서버에 저장한다. 배포 SSH key는 shell을 열 수 없고 `stage`, `deploy`, `activate`, `rollback`, `current`만 허용하는 root-owned forced-command gateway에 묶는다.

첫 이전은 `stage`로 image를 적재하고 `start-data`로 PostgreSQL·Redis만 `--no-build` 기동해 애플리케이션과 데이터 복원을 분리한다. DB·객체 무결성 검증 뒤 같은 Git SHA를 `activate`한다. 이후 일반 배포는 `deploy`가 health 확인과 이전 release 자동 복귀까지 수행한다. 전용 rootless 사용자는 전체 0.75 CPU·3GiB·swap 0으로 제한하고, 기존 block volume root에는 해당 사용자만 통과할 수 있는 execute-only ACL을 둔다.

K3s·MarketValley·Geuneul이 같은 200GB boot filesystem을 공유하므로 bootstrap, stage, data start, activate와 상시 health는 모두 boot free 40GiB 이상·inode 사용률 90% 이하를 fail-closed 강제한다. 저장소 구조 검증과 capacity 검증은 분리해 디스크 압박 중에도 기존 release rollback은 가능하게 한다. release archive와 image는 active·rollback·최근 5개만 유지한다. rootless systemd timer가 매일 logical backup을 실행하며 자체 flock, DB 크기 기반 여유 공간 검사, 별도 non-delete credential, off-host HEAD size, 성공 marker를 사용한다. 별도 15분 health timer가 app image/health, host disk·inode, timer와 36시간 backup freshness를 journal failure로 노출한다.

## 검토한 대안

| 대안 | 기각 이유 |
|---|---|
| 새 Always Free A1 인스턴스 | 현재 home region의 가용성과 무료 quota를 보장할 수 없고, 기존 A1·NLB·볼륨을 재사용할 수 있다. |
| OCI 관리형 PostgreSQL | 작은 서비스에 상시 비용이 크고 무료 범위가 아니다. 현재 목표는 보유 A1 활용이다. |
| `postgis/postgis` amd64를 QEMU 실행 | 로컬 부하 실험에서도 에뮬레이션 지연이 확인됐고, DB 운영 경로의 안정성과 예측 가능성을 떨어뜨린다. |
| Kubernetes 안에 추가 배포 | 이미 38개 pod가 있는 cluster와 데이터 장애 범위를 결합한다. 별도 rootless Compose가 단순하고 격리하기 쉽다. |
| DB의 모든 AWS URL을 OCI URL로 UPDATE | 불필요한 데이터 mutation과 롤백 비용이 생긴다. key 호환 계층이면 같은 결과를 낸다. |
| RDS snapshot S3 export로 복원 | AWS snapshot export는 분석용 Parquet이며 PostgreSQL로 직접 복원할 수 없다. 논리 `pg_dump`가 맞다. |
| 버킷 공개 전환 | presigned PUT/GET 계약과 UGC 접근 경계를 약화한다. 비공개 버킷과 자격증명 없는 exact-origin upload gateway를 유지한다. |

## 결과와 위험

- AWS paid plan은 원본 반출 구간에만 사용하고, 정상 운영의 AWS·OCI 인프라 비용을 0원으로 만든다.
- frontend/BFF 계약을 유지해 컷오버가 환경변수 한 개로 제한된다.
- AWS OIDC/ECR/ECS 자동 배포를 수동 승인 OCI ARM64 release pipeline으로 교체해, 중단된 AWS로의 오배포를 막고 initial data restore와 app activation을 분리한다.
- 자체 DB 운영 책임(패치, 백업, 복구, 용량, 장애 대응)을 직접 진다.
- 같은 VM의 다른 workload가 급증하면 메모리 경쟁이 생길 수 있다. resource limit, healthcheck, host 지표와 백업 복원 훈련으로 완화한다.
- 라이브 사전 점검에서 기존 k3s CPU request가 1,920m/2,000m(96%)이고 `Insufficient cpu` Pending Pod 2개가 이미 있었다. Geuneul의 별도 cgroup은 Kubernetes 예약량에는 들어가지 않으므로 배치 자체는 가능하지만, 0.75 CPU 상한·점진 기동·기존 Pod 상태 비교를 컷오버 게이트로 둔다. Pending 수 증가나 host memory 1GiB 미만이면 중단한다.
- OCI S3 compatibility의 실제 presigned conditional PUT, Caddy preflight/Host 전달, 중복 PUT 412는 라이브 버킷에서 마지막 계약 테스트를 통과해야 컷오버할 수 있다.
- rootless bootstrap과 restricted release gateway는 로컬 정적·archive 보안 테스트를 통과했지만, 실제 OCI user cgroup·volume ACL·SSH forced command는 production 적용 전 plan과 적용 후 read-back이 필요하다.
- live A1 compute는 Always Free 크기와 일치하지만 현재 attached volume 합계 250GB가 200GB 무료 한도를 넘는다. 기존 workload를 보존하는 data-volume→boot migration, 실제 Cost Analysis 0원, Object Storage 20GB 예산을 먼저 검증해야 한다.
- AWS 원본은 OCI 검증·Vercel 종단 테스트·안정화 구간이 끝난 뒤에만 비용 리소스를 정리한다.

## 검증 근거

- native arm64 custom image에서 PostGIS 3.6.4, `geuneul_app.rolsuper=false`, geometry SRID 4326 확인
- 전체 Compose에서 PostgreSQL·Redis·Spring Boot health와 Flyway V21 적용 확인
- custom dump를 별도 빈 volume에 single-transaction으로 복원한 뒤 전 테이블 행 수, Flyway, 185개 제약, 59개 public index, SRID 4326 일치 확인
- 공식 근거: [PostGIS Docker image 지원 아키텍처와 Dockerfile](https://github.com/postgis/docker-postgis), [OCI Always Free resources](https://docs.oracle.com/en-us/iaas/Content/FreeTier/freetier_topic-Always_Free_Resources.htm), [OCI S3 Compatibility API](https://docs.oracle.com/en-us/iaas/Content/Object/Tasks/s3compatibleapi.htm), [OCI Block Volume backups](https://docs.oracle.com/en-us/iaas/Content/Block/Concepts/blockvolumebackups.htm), [AWS Free plan FAQ](https://docs.aws.amazon.com/awsaccountbilling/latest/aboutv2/free-tier-FAQ.html)
