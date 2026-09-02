# ADR-0013. ECS Service Auto Scaling — CPU target tracking, min=1/max=3, 기본 ENABLED

- 상태: AWS 운영 이력. 2026-07-10 실제 활성 상태를 검증했고, 2026-09-03 OCI 이전과 AWS 제거로 [ADR-0032](./0032-oci-arm64-self-hosted-migration.md)가 현재 runtime 결정을 대체한다.
- 관련: `infra/terraform/autoscaling.tf`(신규), `infra/terraform/variables.tf`(`autoscaling_enabled`·
  `autoscaling_max`), `infra/terraform/ecs.tf`(`ignore_changes` 주석 보강), `infra/terraform/outputs.tf`
  (`autoscaling_status`), SPEC.md §7("오토스케일링/HPA=ECS Service Auto Scaling은 P4에서 k6 부하테스트와
  함께 additive하게") · 로드맵 P4, D1(k6 부하테스트+EXPLAIN 인덱스 튜닝, 짝 산출물)

## 문제(Context)

로드맵 P4 심화 산출물 중 하나: k8s HPA에 상당하는 "부하에 따른 ECS 태스크 수 자동 조절". D1(k6 부하테스트)이
부하를 만들고, 이 오토스케일링이 그 부하에 실제로 반응하는 걸 함께 실증하는 짝 구성이다. 결정할 것은 세
가지: (1) 어떤 지표로 스케일할지, (2) min/max와 기본 활성 여부, (3) 기존 `aws_ecs_service`의
`desired_count` 수동 관리와 충돌하지 않게 배선하는 법.

## 결정(Decision)

### 1) 스케일 지표 = CPU 이용률(`ECSServiceAverageCPUUtilization`), ALB `RequestCountPerTarget` 아님

2026-07 웹 검색으로 AWS 공식 문서(target-tracking-create-policy, capacity-autoscaling-best-practice)와
실무 가이드를 확인한 결과, 일반적인 웹 서비스는 "요청 수가 CPU 포화보다 먼저 스파이크를 반영한다"는 이유로
`ALBRequestCountPerTarget`이 더 권장되는 경향이 있다. 하지만 그늘의 ECS 앱 계층은 이 일반론이 그대로
적용되지 않는 두 가지 이유로 CPU 이용률을 택했다:

- **태스크가 이미 0.25 vCPU(`var.task_cpu=256`, `ecs.tf`)로 얇다.** 이 정도로 작은 리소스에서는 애초에
  CPU가 요청 처리의 실제 병목이 되는 지점(포화)이 요청 수보다 훨씬 먼저 온다 — "포화됐는가"를 직접 재는
  지표가 더 정직한 신호다.
- **요청당 비용이 이질적이다.** `/places`(bounds, 다건) vs `/places/{id}`(단건 + survival 조립) vs
  `/recommendations`(2단 검색 + 시나리오 재랭킹) vs AI 한줄 요약(OpenRouter I/O 대기, ADR-0010)은 같은
  "요청 1건"이라도 CPU 소모가 크게 다르다. `RequestCountPerTarget`은 균질한 비용 프로파일을 전제로 목표값을
  튜닝해야 하는데, 그늘은 그 전제가 성립하지 않는다. 반면 PostGIS 반경/kNN 연산 자체는 RDS에서 도는
  별도 리소스라(이 정책의 스케일 대상인 ECS 앱 계층과 무관) — 여기서 잡는 CPU는 Jackson 직렬화·JTS 좌표
  변환·Spring MVC 디스패치 등 "앱 계층 자체의 포화"다.

target_value = **60%** — AWS가 공통적으로 언급하는 권장 범위(50~70%)의 중간값. 스파이크 대응 여유와 과도한
조기 스케일아웃(불필요 비용) 사이 균형.

### 2) min=1 · max=`var.autoscaling_max`(기본 3), 기본 **ENABLED**

Fargate 0.25vCPU/1GB 태스크 기준 max=3이면 최악의 경우도 베이스라인(~$12/월, HANDOFF 운영 치트시트) 대비
최대 3배(~$36/월)로 **유계**다. 그리고 target tracking은 실제 CPU가 목표치를 넘을 때만 스케일아웃하므로,
이 프로젝트의 실제 트래픽(수동 데모·간헐적 E2E)에서는 사실상 항상 min=1에 머문다 — max=3은 "언젠가 부하가
걸렸을 때의 안전판"이지 상시 비용이 아니다.

**기본 활성화(ENABLED)** 는 ADR-0011(공공데이터 주기 동기화)의 초기 검증 단계가 기본 `false`였던 것과
상반된 선택인데, 이유는 두 기능의 위험 성격이 다르기 때문이다:

| | ADR-0011 스케줄 | 이 오토스케일링 |
|---|---|---|
| 위험 | 사람 없이 soft-delete까지 도는 **데이터 변경** | 일시적 **비용 증가**뿐 |
| 가역성 | 잘못되면 데이터가 이미 지워짐(비가역에 가까움) | 스케일인되면 즉시 원복(가역) |
| 관측 가능성 | 조용히 매달 실행(사람이 안 보면 모름) | ECS 콘솔/CloudWatch에 즉시 드러남 |
| 상한 | 없음(전량 soft-delete 가능) | max=3으로 명시적 유계 |

즉 ADR-0011의 초기 기본 `false`는 "위험이 비가역·조용함"에 대한 안전장치였고, 이 오토스케일링의 위험은
"유계·가역적 비용"뿐이라 같은 안전장치를 그대로 복제할 필요가 없다고 판단했다. 그래도 `var.
autoscaling_enabled=false`로 언제든 즉시 끌 수 있는 스위치는 남겨(리소스가 아예 생성되지 않음 —
`desired_count`는 `ecs.tf`의 수동값 1로 고정된다).

### 3) 쿨다운 — scale-out 60초 / scale-in 600초(AWS 권장 300초의 2배)

scale-out은 AWS 문서가 공통으로 제시하는 60초를 그대로 채택(포화 시 빠르게 반응). scale-in은 AWS가 흔히
언급하는 300초보다 넉넉하게 **600초**로 뒀다 — 그늘의 실트래픽은 정상적인 사용자 성장 곡선이 아니라 k6
부하테스트·수동 데모처럼 짧고 뾰족한 버스트 패턴이라, 버스트 꼬리에서 태스크 수가 1↔2로 플래핑하는 걸
막으려면 표준값보다 더 긴 쿨다운이 안전하다(오케스트레이터 지시: "스케일인 쿨다운 넉넉히").

### 4) `desired_count`가 오토스케일러와 싸우지 않게

`aws_ecs_service.app`의 `lifecycle.ignore_changes`에 `desired_count`가 **이미 포함돼 있었다**(배포 후 수동
조정을 위해 과거에 추가된 것으로 보임) — 이 값이 오토스케일러가 바꾸는 값과 정확히 같은 필드라, 추가 코드
변경 없이 그대로 오토스케일링과의 충돌 방지 역할도 겸한다. 다만 그 의도가 코드에 드러나지 않아 주석 한 줄만
보강했다(`ecs.tf`, "이 값도 ignore — ... 오토스케일러와 싸우지 않게 한다").

### 5) IAM 불필요

`aws_appautoscaling_target`/`aws_appautoscaling_policy`는 AWS Application Auto Scaling의
서비스연결역할(`AWSServiceRoleForApplicationAutoScaling_ECSService`)을 최초 사용 시 자동 생성해 쓴다 —
ADR-0011의 EventBridge Scheduler(사람이 만든 커스텀 실행 롤 필요)와 달리 별도 `aws_iam_role` 리소스가 필요
없다(Terraform·AWS 공식 문서 확인).

## 검토한 대안(Alternatives)

| 대안 | 기각 이유 |
|---|---|
| ALB `RequestCountPerTarget` 지표 | 위 §1 — 이질적 요청 비용 프로파일에 균질 목표값을 튜닝해야 해 정확도가 떨어짐. 0.25vCPU 태스크에서는 CPU 포화가 더 직접적인 신호 |
| Step scaling(계단식) | Target tracking이 목표값만 주면 스케일 폭을 AWS가 알아서 계산 — 저트래픽·소규모(max=3) 환경에 계단 임계값을 손수 여러 개 설계할 이유가 없음(과설계, SPEC.md §0.2) |
| min=max=1(사실상 비활성) 기본값 | 오토스케일링이 실제 부하에 반응하지 않아 D1(k6) 검증 목적과 어긋난다. max=3으로 비용을 유계시키면 동작을 검증하면서도 확장 폭을 제한할 수 있다. |
| ADR-0011의 초기 DISABLED 방식처럼 시작 | 위 §2 표 — 위험 성격이 다름(유계·가역 비용 vs 비가역·조용한 데이터 변경). 다만 즉시 끌 수 있는 스위치는 동일하게 제공 |
| 메모리 이용률(`ECSServiceAverageMemoryUtilization`) 추가 병행 | task_memory=1024MB로 CPU(256) 대비 여유가 커(HANDOFF: "512는 빠듯 → 1024 권장") 메모리가 먼저 포화될 가능성이 낮음 — 단일 지표로 충분, 나중에 실측(D1 k6)에서 메모리 포화가 관측되면 추가 검토 |

## 결과(Consequences)

- CPU 60% 이상이 600초 넘게 지속되는 부하(예: k6로 재현)에서 태스크가 최대 3개까지 자동 증설되고, 부하가
  빠지면 600초 쿨다운 뒤 1개로 복귀한다. 운영 AWS에서 target `ECSServiceAverageCPUUtilization=60`,
  min=1, max=3인 정책 생성을 확인했다.
- 비용 영향은 스케일업 상태가 지속될 때만 발생하며 최대 베이스라인의 3배로 유계(§2).
- `var.autoscaling_enabled=false`로 재적용하면 `aws_appautoscaling_target`/`_policy`가 통째로 파괴되고
  `desired_count`는 수동 1로 남는다(리스크 없는 롤백 경로).

## 근거(References)

- AWS 공식: "Use a target metric to scale Amazon ECS services" · "Optimizing Amazon ECS service auto
  scaling"(capacity-autoscaling-best-practice) · "Create a target tracking scaling policy for Amazon ECS
  service auto scaling" — `ECSServiceAverageCPUUtilization` predefined metric, 권장 target 50~70%,
  scale-out/scale-in 쿨다운 개념 확인(2026-07 웹검색).
- 실무 가이드(oneuptime.com, 2026-02) — CPU 60~70% / RequestCountPerTarget 상황별 트레이드오프, scale-out
  60s·scale-in 300s 관례값 교차 확인(이 프로젝트는 버스트 트래픽 특성상 scale-in을 600s로 상향, §3 근거).
- Terraform Registry `aws_appautoscaling_target`/`aws_appautoscaling_policy` 문서 — `resource_id` 포맷
  (`service/{cluster}/{service}`), `target_tracking_scaling_policy_configuration` 블록의
  `scale_in_cooldown`/`scale_out_cooldown` 필드 확인.
- SPEC.md §7(오토스케일링 P4 additive 지시)·§0.2(과설계 금지)·비용 원칙($200 크레딧, EKS/NAT 배제 근거와
  같은 결의 비용 유계 판단). ADR-0011(위험 성격 비교 기준점).
