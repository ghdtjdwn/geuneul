# OCI 운영 구조와 용량

이 문서는 2026-09-03 KST 기준 OCI 콘솔, 배포 선언, 마이그레이션 검증 기록을 함께 대조한 운영 인벤토리다. 공개 저장소이므로 IP, OCID, 계정·버킷 이름, SSH 포트와 secret 값은 기록하지 않는다. 아래 표의 `Console`은 이날 OCI Console read-back, `Repo`는 현재 선언, `Cutover`는 실제 이전·복구 검증 기록을 뜻한다. 실시간 셸 측정이 아닌 수치에는 측정 시점을 표시한다.

## 요약

- 한 대의 OCI Ampere A1 ARM64 VM을 세 개의 격리된 운영 영역이 공유한다: Geuneul rootless Compose, MarketValley rootless Compose와 공용 Caddy, ssuAI/ssuMCP k3s.
- Geuneul의 공개 요청은 `Browser → Vercel BFF → Caddy HTTPS → Geuneul app` 순서다. PostgreSQL과 Redis는 외부에 공개되지 않는다.
- Compute는 `VM.Standard.A1.Flex` 2 OCPU·12GB RAM, 네트워크 2Gbps다. 200GB Always Free boot volume 하나만 있고 추가 Block Volume은 0개다.
- 컷오버 직후 파일시스템 실측 여유는 약 140GiB였다. OCI 콘솔의 200GB는 십진 단위 volume 크기이고 파일시스템의 GiB 표시, 파티션·파일시스템 metadata 때문에 `df`의 총량과 다르다. 이 수치는 실시간값이 아니라 마지막 검증값이다.
- Geuneul은 합계 1 CPU·약 2.44GiB의 컨테이너 상한을 가지며, 상위 rootless user에는 0.75 CPU·3GiB·swap 0 제한이 적용된다.

## OCI 인프라 사양

| 항목 | 확인된 구성 | 근거 |
|---|---|---|
| Region / 배치 | South Korea North (Chuncheon), 단일 availability domain·fault domain | Console |
| Compute | `VM.Standard.A1.Flex`, ARM64 Ampere A1, 2 OCPU, 12GB RAM | Console |
| 네트워크 | 최대 2Gbps, 공개 HTTPS는 공용 Caddy edge가 종단 | Console + Repo |
| 운영체제 | Canonical Ubuntu 22.04 ARM64, paravirtualized UEFI boot | Console |
| Boot volume | 200GB, Always Free 표시, Balanced 10 VPU/GB, Oracle-managed encryption | Console |
| Boot 성능 표시 | 최대 12,000 IOPS / 96MB/s | Console |
| 추가 Block Volume | 0개 | Console |
| Boot backup | 주간 정책 연결, 일요일 06:00 로컬 스케줄 | Console |
| OCI agent | Compute Monitoring, Custom Logs Monitoring, Cloud Guard Workload Protection 실행 | Console |
| 확인된 Geuneul release | `203028d648c6cd4dd26a966e7eadaec7c7a87c7c` | Cutover |

Vulnerability Scanning, OS Management Hub, Management Agent, Bastion, Block Volume Management plugin은 비활성 상태다. Boot backup의 cross-region replication과 Full Stack DR도 활성화하지 않았다. 따라서 이 구조는 비용을 최소화한 단일 호스트 운영이지, 다중 리전 재해복구 구성이 아니다.

## 요청과 데이터 흐름

```text
Browser / installed PWA
  → Vercel Next.js
      ├─ 정적 화면·서비스 워커·BFF
      └─ same-origin /api/*
  → OCI shared Caddy edge (TLS/SNI)
      ├─ Geuneul hostname → private host bind → Spring Boot
      ├─ MarketValley hostname → MarketValley Next.js
      └─ /object-storage/* PUT → fixed OCI Object Storage endpoint
  → Geuneul internal data network
      ├─ PostgreSQL 16.15 + PostGIS 3.6.4
      └─ Redis 7.4
```

브라우저 bundle에는 OCI origin을 넣지 않는다. 일반 API는 Vercel BFF만 호출하고, 사진 binary PUT만 짧게 서명된 URL로 Caddy의 고정 gateway를 지난다. Gateway에는 Object Storage 자격증명이 없고 exact Vercel origin만 허용한다. Presigned query credential이 로그에 남지 않도록 upload access log를 제외하고 URI query를 가린다.

## Geuneul 서비스

| 서비스 | 역할 | 외부 노출 | CPU / memory 상한 | 영속 데이터 |
|---|---|---|---|---|
| `app` | Spring Boot API, OAuth/JWT, 공간검색, UGC, SSE, presign | host private bind의 13880만 Caddy에 연결 | 0.55 CPU / 1GiB | 없음; read-only root FS와 tmpfs |
| `postgres` | 운영 source of truth, PostGIS 공간연산, Flyway schema | Compose internal network의 5432만 사용 | 0.35 CPU / 1.25GiB | rootless Docker volume |
| `redis` | 날씨·조회 cache, 분산 rate limit | Compose internal network의 6379만 사용 | 0.10 CPU / 192MiB | AOF Docker volume, 128MB LRU 상한 |

세 컨테이너 모두 재기동 정책과 health check, PID 상한, 회전되는 JSON log를 가진다. App과 Redis는 Linux capability를 모두 제거하고 `no-new-privileges`를 사용한다. PostgreSQL은 `max_connections=40`, `shared_buffers=256MB`, 1초 이상 query logging으로 이 작은 호스트에 맞췄다.

### 운영 자동화

| 자동화 | 주기 | 검사·동작 |
|---|---|---|
| `geuneul-health.timer` | 부팅 10분 뒤, 이후 15분마다 | app readiness, DB·Redis, 활성 release, disk/inode gate, backup freshness |
| `geuneul-backup.timer` | 매일 18:15 UTC, 최대 10분 분산 | PostgreSQL custom dump, checksum, Object Storage off-host upload·검증 |
| Object Storage lifecycle | 정책 기반 | DB backup 14일, 사진 이전 version 30일 보존 |
| 로컬 backup 정리 | upload 성공 뒤 | 기본 3일 보존 |

배포 health gate는 boot free가 40GiB 미만이거나 inode 사용률이 90%를 넘으면 실패한다. Backup은 36시간 이내 검증 marker가 있어야 정상으로 판정한다.

## 같은 VM의 다른 서비스

### MarketValley 영역

MarketValley는 Geuneul과 다른 rootless Compose project다.

| 컨테이너 | 역할 | 선언된 상한 |
|---|---|---|
| `app` | Next.js/Node 애플리케이션 | 0.75 CPU / 1536MB |
| `proxy` | Caddy TLS edge; MarketValley와 Geuneul hostname routing | 0.15 CPU / 192MB |
| `lifecycle-worker` | 내부 lifecycle endpoint를 60초 간격 호출 | 0.10 CPU / 160MB |

`app_internal`은 외부 egress도 없는 내부망이고 app·proxy egress network를 분리한다. 컨테이너는 read-only root filesystem, tmpfs, capability 제거, PID 상한과 bounded log를 사용한다. MarketValley 데이터 약 2.02GB·약 51,000개 파일은 과금되는 별도 50GB volume에서 boot volume으로 checksum 이관했고, 이전 volume은 삭제했다.

### k3s 영역

호스트에는 ssuAI/ssuMCP용 단일 노드 k3s와 Argo CD GitOps 영역도 있다. 저장소가 선언한 애플리케이션은 backend, PostgreSQL, Redis, Kafka, n8n, Prometheus/Grafana/Alertmanager, Loki/Promtail, Tempo다. GitHub Actions가 ARM64 image를 만들고 Argo CD Image Updater가 Git revision을 기록한 뒤 auto-sync/self-heal한다.

이 목록은 GitOps 저장소의 목표 상태다. 이번 Geuneul 감사에서는 k3s 관리 채널로 각 Pod의 실시간 replica·메모리 사용량을 다시 조회하지 않았으므로 모두 현재 Running이라고 확대 해석하지 않는다. 마지막 마이그레이션 재부팅 검증에서는 k3s node가 Ready였고 기존 공개 서비스 health가 유지됐다.

## 저장 공간

| 구분 | 크기 / 상태 | 설명 |
|---|---|---|
| OCI boot volume | 200GB | VM OS, rootless container image·volume, MarketValley 데이터, k3s 데이터를 공유 |
| 추가 Block Volume | 0GB | 이전 50GB volume은 검증 뒤 삭제 |
| 마지막 파일시스템 여유 | 약 140GiB | MarketValley 이관과 Geuneul 배포 뒤 컷오버 시점 실측; 현재 실시간 `df` 값은 아님 |
| Geuneul DB dump | 약 8.8MB / 회 | 첫 운영 backup 기준, 압축 metadata에 따라 매회 달라짐 |
| Geuneul 사진 | 약 2.31MB | 마이그레이션 당시 object 1개 |
| OCI Object Storage | photos + backups | 합계 20GB 무료 한도 안에서 versioning/lifecycle로 제한 |

직접 재다운로드와 empty-DB restore drill까지 확인한 최신 증거는 2026-09-02 16:31:21 UTC backup(8,784,556 bytes)이다. Daily timer가 이후 backup을 만들 수 있지만 이번 read-only 감사에서는 server 관리 채널을 열지 않았으므로 그보다 최근 object를 확인했다고 주장하지 않는다.

200GB를 1024 단위로 표시하면 약 186GiB이고, 파티션·filesystem 예약 영역과 metadata를 제외하면 `df`의 usable total은 더 작다. 약 140GiB 여유는 넉넉하지만 한 boot volume에 여러 서비스가 모이므로 절대 용량보다 증가율, inode, container image·log 누적을 같이 봐야 한다.

## 배포와 복구 경계

일반 Geuneul release는 `main`에서 수동 workflow의 `deploy`를 실행한다. GitHub가 ARM64 image archive를 만들고 architecture·Git revision·SHA-256을 확인한 뒤 shell을 열 수 없는 forced-command SSH gateway로 전송한다. 배포는 기존 app을 멈춰 쓰기를 동결하고 off-host pre-deploy backup을 확인한 후 새 release를 활성화한다.

`stage`, `start-data`, `activate`는 최초 마이그레이션에서 빈 데이터 계층을 복원하고 첫 release를 여는 절차였다. 현재 반복 배포의 정상 경로는 `deploy`이며, DB schema 변경 뒤 자동 binary rollback은 하지 않는다. 복구는 검증된 dump를 별도 빈 DB에 restore하고 이전 binary와 schema 호환성을 사람이 확인하는 명시적 절차다.

2026-09-03 외부 probe에서 공개 SSH 22는 닫혀 있었다. GitHub 배포는 제한된 gateway 경로만 사용한다. 같은 날 OCI Run Command 감사 command는 전달 후 `ACCEPTED`에 머물고 실행 결과를 만들지 않아 운영 관리 채널로 의존하지 않는다.

## 완료된 것과 남은 운영 항목

완료된 범위는 AWS 데이터·사진의 무결성 이전, OCI cutover, Vercel origin 전환, AWS 과금 resource 제거, backup/restore drill, 추가 Block Volume 제거다. 그러나 운영 시스템에는 “영구 무정비 완료”가 없다.

현재 명시적으로 남은 항목은 다음과 같다.

1. AWS EventBridge가 담당하던 월 1회 도서관 자동 인제스천은 AWS 삭제와 함께 종료됐다. OCI systemd에는 health와 backup timer만 있고 대체 ingestion timer는 아직 없다. 데이터는 그대로 보존되지만 자동 갱신은 재구축 전까지 멈춘다.
2. 단일 VM·단일 region·단일 boot volume이므로 host 또는 region 장애 시 자동 failover가 없다. Logical off-host backup과 주간 boot backup은 복구 수단이지 무중단 HA가 아니다.
3. Boot backup cross-region replication, Full Stack DR, vulnerability scanning과 OS Management Hub는 비활성이다. 무료 비용 경계와 운영 복잡도를 택한 결과다.
4. 현재 exact disk·inode·k3s Pod 사용량을 알려면 제한된 관리자 경로에서 다시 측정해야 한다. 공개 SSH나 실패한 Run Command를 편의상 열어 두지 않는다.

AWS 출발 사양, 데이터 행 수와 checksum, 컷오버 증거는 [OCI 마이그레이션 기록](./OCI-MIGRATION.md), Geuneul 배포 절차는 [DEPLOY.md](../DEPLOY.md)에 있다.

## 공식 한도 참고

Oracle의 현재 Free Tier 문서는 A1 전체 합계를 2 OCPU·12GB로 유지해야 Always Free로 계속 사용할 수 있다고 안내한다. Always Free storage 문서는 home region의 boot+block 합계 200GB, volume backup 5개, Object Storage 합계 20GB와 월 50,000 request를 명시한다. 실제 과금 여부는 문서상 한도만이 아니라 OCI Console의 resource별 `Always Free` 표시와 Cost Analysis를 함께 확인한다.

- [Oracle Cloud Infrastructure Free Tier](https://docs.oracle.com/en-us/iaas/Content/FreeTier/freetier.htm)
- [Always Free Resources](https://docs.oracle.com/en-us/iaas/Content/FreeTier/freetier_topic-Always_Free_Resources.htm)
