# ADR-0011. 공공데이터 주기 동기화 무인화 — EventBridge Scheduler(Universal Target) → ECS RunTask + Postgres advisory lock

- 상태: AWS 운영 이력. 2026-07-10 활성·실트리거를 검증했으나 2026-09-03 OCI 이전 뒤 AWS scheduler는 삭제됐다. `IngestBatchLock`과 ingestion code는 유지되지만 OCI 자동 schedule은 아직 없다.
- 관련: `IngestBatchLock`(신규), `IngestionRunner`(dispatch 분리 + 락 배선), `infra/terraform/scheduler.tf`(신규),
  `infra/terraform/ssm.tf`(`datago_service_key`), `infra/terraform/ecs.tf`(`DATA_GO_KR_SERVICE_KEY` secret),
  ADR-0002(멱등 upsert)·ADR-0006(soft-delete diff, `IngestionService.deactivateStale`), SPEC.md 로드맵 P3

## 문제(Context)

로드맵 P3 마지막 조각: "공공데이터 주기 동기화 스케줄(EventBridge→ECS RunTask, 멱등 upsert 재실행 +
스냅샷에서 사라진 행 soft-delete 비활성화 + 오픈API serviceKey로 다운로드까지 무인화)". 멱등 upsert와
soft-delete diff(`deactivateStale`)는 ADR-0002/0006에서 이미 완성돼 있었다 — 이번 ADR의 실제 범위는
**"사람 없이" 그 파이프라인을 정기 실행**하는 오케스트레이션 3가지다:

1. **스케줄러가 무엇으로 ECS 태스크를 어떻게 띄우나** — RunTask에 `--ingest.source=library` 같은 커맨드
   오버라이드를 실어야 하는데, EventBridge/Terraform 생태계에 여러 방식이 있다.
2. **다운로드 무인화의 진짜 블로커는 뭐였나** — 지금까지 `library` 소스는 로컬/사람이 `.local/datago.env`의
   `DATA_GO_KR_SERVICE_KEY`를 셸 환경변수로 넘겨야만 실행됐다(`prod-ingest.sh`가 `KAKAO_REST_API_KEY`를
   그렇게 넘기는 것과 같은 패턴). 스케줄 트리거는 사람이 없으므로 이 값이 **ECS 태스크데프에 상시 배선**돼
   있어야 한다.
3. **동시 실행 방지** — 스케줄이 지연·중복 트리거되거나, 사람이 `prod-ingest.sh`를 스케줄과 겹치게 수동
   실행하면 같은 소스를 두 프로세스가 동시에 upsert/soft-delete할 수 있다. 멱등 upsert 자체는 동시 실행에도
   최종 상태가 수렴하지만(ON CONFLICT), `deactivateStale`의 "이번 스냅샷의 currentExternalIds" 계산 중
   다른 프로세스가 끼어들면 스냅샷 정의가 흔들릴 수 있어 원천적으로 겹치지 않게 막는 게 더 안전하다.

## 결정(Decision)

### 1) 스케줄러→ECS 연결 = EventBridge Scheduler **Universal Target**(`aws-sdk:ecs:runTask`), `ecs_parameters` 블록 아님

2026-07 웹 검색으로 Terraform `aws_scheduler_schedule`의 네이티브 `ecs_parameters` 블록을 확인한 결과,
**컨테이너 오버라이드(`overrides.containerOverrides`)를 지원하지 않는다**
(hashicorp/terraform-provider-aws#34057, 2023년 제기 후 2026-07 기준 여전히 미해결 오픈 이슈 — GitHub에서 직접
확인). `ecs_parameters`만으로는 "어떤 태스크데프를 어떤 네트워크로 띄울지"까지만 되고, 우리가 반드시 필요한
`--ingest.source=library --ingest.deactivate-stale=true --ingest.exit-after=true` 커맨드 오버라이드를 실을
방법이 없다.

대신 **Universal Target**(2023년 출시, ARN 패턴 `arn:aws:scheduler:::aws-sdk:<service>:<action>`)을 썼다 —
AWS SDK의 ECS RunTask 액션을 그대로 호출해 `target.input` JSON에 `cluster`·`taskDefinition`·
`networkConfiguration`·`overrides.containerOverrides`를 전부 담는다(AWS 공식 문서
"Using universal targets in EventBridge Scheduler" 확인). `prod-ingest.sh`가 `aws ecs run-task
--overrides`로 쓰는 것과 정확히 같은 JSON 셰이프(camelCase, ECS RunTask API 그대로)라 두 실행 경로
(수동 스크립트·무인 스케줄)가 같은 정신모델을 공유한다.

### 2) 스케줄 대상 소스 = `library`만(1개)

CSV 소스(쉼터/화장실)는 스냅샷이 GitHub Release 고정 자산(`--ingest.url`)이라 "주기 재동기화"의 의미가
약하다(자산을 새로 안 올리면 매번 같은 파일을 재적재할 뿐 — 멱등이라 안전하지만 무의미한 실행). 반면
`library`(전국도서관표준데이터, ADR-0006)는 data.go.kr 오픈API가 **원본 자체가 주기적으로 갱신**되고,
서비스가 페이지네이션으로 전량 자체 수집해 파일/URL이 불필요하다 — "다운로드까지 무인화"라는 P3 문구가
정확히 이 소스를 가리킨다. 상권정보(STUDY_CAFE/CAFE)는 이후 계약을 검증했지만, 반경 단위 수집 범위는
운영 판단이 필요하므로 자동 스케줄 대상에서 제외하고 수동 ECS task로 갱신한다.

### 3) 다운로드 무인화 = `DATA_GO_KR_SERVICE_KEY`를 다른 비밀들과 동일하게 SSM SecureString + ECS secrets로 상시 배선

`kma_service_key`·`kakao_rest_api_key` 등과 동일한 패턴(`ssm.tf` SecureString + `ecs.tf` task def
`secrets`)으로 `datago_service_key`를 추가했다 — **일관성**(이미 확립된 패턴 재사용, 새 방식 발명 안 함)과
**무인 실행 가능성**(사람이 셸에서 넘기지 않아도 컨테이너 환경에 항상 존재) 둘 다를 만족한다. 이게 실질적인
"블로커 해소"다 — 인제스천 로직 자체(`PublicLibraryIngestionService`)는 ADR-0006에서 이미 완성돼 있었고,
빠진 건 "서비스키가 무인 환경에도 있는가" 하나였다.

### 4) 동시 실행 방지 = 새 인프라 대신 Postgres 세션 수준 advisory lock(`IngestBatchLock`)

SQS 락 서비스·DynamoDB 조건부쓰기 등 전용 분산락 인프라를 검토했으나, **월 1회 저빈도** 스케줄에 새 인프라를
얹는 건 SPEC.md §0.2(과설계 금지)와 충돌한다. 이미 있는 RDS 하나로 `pg_try_advisory_lock`(세션 수준,
논블로킹)을 쓰면 추가 비용·인프라 없이 상호배제를 얻는다. `IngestionRunner.run()`이 모든 `--ingest.source=`
실행(스케줄·수동 둘 다)에서 이 락을 거치도록 배선했다 — 락을 못 얻으면 **실패가 아니라 "건너뜀"**으로
취급해 `exitCode=0`으로 정상 종료한다(다음 스케줄이 사실상 재시도이므로 알림 노이즈를 만들 이유가 없다).

**구현 함정**: HikariCP 커넥션 풀은 `Connection.close()`를 호출해도 물리 커넥션(=Postgres 백엔드 세션)을
재사용하려고 풀에 반환할 뿐 끊지 않는다. `pg_advisory_lock`류는 **세션 수준**이라, 락을 건 것과 다른
커넥션(풀에서 새로 꺼낸)으로 `pg_advisory_unlock`을 불러도 해제되지 않고, `JdbcTemplate`처럼 매번
열고-닫는 방식을 쓰면 락이 사실상 새어버린다. `IngestBatchLock`은 그래서 `DataSource.getConnection()`으로
얻은 **같은 `Connection` 객체를 잠금부터 해제까지 명시적으로 물고 있다가** 그제서야 `close()`한다.
크래시(JVM/ECS 태스크 강제종료) 시에는 그 물리 커넥션 자체가 끊기므로 Postgres가 세션 종료 시 advisory
lock을 자동 해제 — 영구 데드락은 없다(둘 다 클래스 주석에 근거와 함께 기록).

### 5) 안전장치 이력 — 수동 검증 뒤 기본 활성

초기에는 `var.ingest_schedule_enabled`를 기본 `false`로 두어 `aws_scheduler_schedule.state`를 `DISABLED`로
생성했다. 실제 RunTask를 한 번 검증한 뒤 2026-07-10에 기본값을 `true`로 승격했고, 현재 운영 스케줄도
`ENABLED`다. 문제가 생기면 `terraform apply -var='ingest_schedule_enabled=false'`로 명시적으로 중지할 수 있다.

## 검토한 대안(Alternatives)

| 대안 | 기각 이유 |
|---|---|
| Lambda가 RunTask를 호출(EventBridge→Lambda→ECS) | 오케스트레이터 지시가 "EKS/Lambda 아님 — ECS Fargate 정합"으로 명시. Universal Target으로 중간 Lambda 없이 직접 RunTask 호출이 가능해져 더더욱 불필요해짐 |
| `ecs_parameters` 네이티브 블록 + 별도 "커맨드 고정 이미지" 태스크데프 | 서비스 태스크데프와 다른 이미지/커맨드를 따로 관리해야 해 배포 파이프라인이 두 갈래로 갈라짐. Universal Target으로 기존 서비스 태스크데프를 그대로 재사용(오버라이드만)하는 편이 `prod-ingest.sh`와도 일관 |
| 분산락(SQS/DynamoDB 조건부쓰기) | 월1회 저빈도에 새 인프라 — 과설계(§0.2). Postgres는 이미 있고 advisory lock은 표준 기능 |
| 스케줄 자체를 겹치지 않게 신뢰(락 없이) | "사람이 prod-ingest.sh를 스케줄과 동시에 돌리는" 경로는 스케줄 설정만으로 못 막는다 — 앱 레벨 가드가 유일하게 두 진입경로(스케줄·수동) 모두를 커버 |
| CSV 소스(쉼터/화장실)도 같이 스케줄 | 스냅샷이 고정 GitHub Release 자산이라 재동기화의 의미가 없음(위 §2) — 필요해지면 이 스케줄을 복제해 `--ingest.source`만 바꾸면 됨(확장점으로 문서화) |

## 결과(Consequences)

- `library` 소스는 사람 개입 없이 매월 2일 KST 04:00에 재수집 → soft-delete diff → 최신 상태로 수렴한다.
- 동시 실행 방지 가드(`IngestBatchLock`)는 `library`뿐 아니라 **모든** `--ingest.source=` 실행(CSV 포함)에
  적용된다 — 스케줄 대상을 나중에 늘려도 재작업 없이 안전.
- Universal Target JSON(`input`)은 Terraform이 타입 체크를 못 해주는 자유 형식 문자열이라, 오타/필드명
  실수는 `terraform validate`로 못 잡는다. 활성화 전 `aws scheduler get-schedule`과 수동 RunTask로
  검증했고, 2026-07-10 실제 정기 트리거의 정상 종료도 확인했다.
- `DATA_GO_KR_SERVICE_KEY`가 이제 상시 ECS 시크릿이라, 회전 시 다른 비밀들과 동일 절차
  (`.local/datago.env` → SSM → 새 태스크데프 rev 등록 → `update-service --force-new-deployment`)를 따른다.

## 근거(References)

- Terraform `aws_scheduler_schedule` 리소스 문서 + `ecs_parameters`가 container overrides 미지원임을
  확인한 이슈: [hashicorp/terraform-provider-aws#34057](https://github.com/hashicorp/terraform-provider-aws/issues/34057)
- AWS 공식: "Using universal targets in EventBridge Scheduler" — `arn:aws:scheduler:::aws-sdk:<service>:<action>`
  패턴과 `input`이 해당 API의 요청 JSON 그대로임을 확인.
- ECS RunTask API 요청 JSON 셰이프(camelCase: `cluster`/`taskDefinition`/`overrides.containerOverrides`) —
  `prod-ingest.sh`가 이미 같은 셰이프로 `aws ecs run-task --overrides`를 쓰고 있어 교차 검증됨.
- Postgres `pg_try_advisory_lock`/`pg_advisory_unlock` 세션 수준 동작 및 커넥션 풀 재사용 함정 — PostgreSQL
  공식 문서 §9.27(Advisory Lock Functions, "세션 수준 락은 unlock 또는 세션 종료까지 유지").
- SPEC.md §0.2(과설계 금지)·§10 로드맵 P3, ADR-0002(멱등)·ADR-0006(soft-delete diff, 오픈API 다운로드 경로).
