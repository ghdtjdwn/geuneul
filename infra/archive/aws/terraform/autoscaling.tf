# P4 심화 — ECS Service Auto Scaling(=k8s HPA 상당). docs/SPEC.md §7 "오토스케일링/HPA는 P4에서 k6 부하테스트와
# 함께 additive하게" · 로드맵 P4. D1(k6 부하테스트+EXPLAIN)이 부하를 만들고, 이 target tracking 정책이 그
# 부하에 실제로 반응하는지를 같이 실증한다(짝 산출물, ADR-0012 참고).
#
# 지표 선택 = CPU 이용률(ECSServiceAverageCPUUtilization), ALB RequestCountPerTarget이 아니라.
# 이유(ADR-0012에 근거 상술): 태스크가 0.5 vCPU(task_cpu=512, ADR-0025)로 승격됐어도 요청당 비용 편차가 크다
# (`/places` bounds 조회 vs `/places/{id}` 단건 vs `/recommendations` 2단 재랭킹 vs AI 요약 외부 I/O 대기) —
# "요청 수"는 이 이질적 비용을 대표하지 못하지만 "그 작은 태스크가 실제로 포화됐는가"는 CPU 이용률이 직접
# 알려준다. PostGIS 반경/kNN 연산 자체는 RDS에서 도는 별도 리소스라 이 정책의 스케일 대상(ECS 앱 계층)과는
# 무관 — 여기서 잡는 건 앱 계층(Jackson 직렬화·JTS 좌표 변환·Spring MVC 디스패치)의 CPU 포화다.
#
# min=1 · max=var.autoscaling_max(기본 3) — Fargate 0.5vCPU/1GB 태스크 기준 3개까지가 상한이라 태스크 계층은
# 베이스라인 약 $20.72/월 대비 최대 3배 수준이며, 태스크별 공인 IPv4 사용액도 추가된다(스케일인되면 즉시
# 원복). ADR-0011(공공데이터 스케줄)이 기본 DISABLED로 시작한 것과 달리, 여기는 기본 **ENABLED**로 뒀다 —
# 그 스케줄의 위험은 "사람 없이 soft-delete까지 도는 데이터 변경"(비가역·조용함)이었지만, 오토스케일링의
# 위험은 "일시적으로 유계·가역적인 비용"뿐이라 리스크 성격이 다르다(ADR-0012 근거 상술). 그래도
# var.autoscaling_enabled=false로 언제든 즉시 끌 수 있게 스위치는 남겨둔다.
resource "aws_appautoscaling_target" "ecs_app" {
  count = var.autoscaling_enabled ? 1 : 0

  max_capacity       = var.autoscaling_max
  min_capacity       = 1
  resource_id        = "service/${aws_ecs_cluster.main.name}/${aws_ecs_service.app.name}"
  scalable_dimension = "ecs:service:DesiredCount"
  service_namespace  = "ecs"
}

resource "aws_appautoscaling_policy" "ecs_app_cpu" {
  count = var.autoscaling_enabled ? 1 : 0

  name               = "${var.project}-cpu-target-tracking"
  policy_type        = "TargetTrackingScaling"
  resource_id        = aws_appautoscaling_target.ecs_app[0].resource_id
  scalable_dimension = aws_appautoscaling_target.ecs_app[0].scalable_dimension
  service_namespace  = aws_appautoscaling_target.ecs_app[0].service_namespace

  target_tracking_scaling_policy_configuration {
    predefined_metric_specification {
      predefined_metric_type = "ECSServiceAverageCPUUtilization"
    }

    # 60% — AWS 권장 범위(50~70%)의 중간값. 스파이크에 대응할 여유와
    # 너무 이른 스케일아웃(불필요한 비용) 사이 균형.
    target_value = 60

    # scale-out은 짧게(60s, AWS 권장) — 포화되면 빠르게 반응.
    scale_out_cooldown = 60

    # scale-in은 AWS 기본 권장(300s)의 2배(600s) — 이 앱의 실트래픽은 정상 사용자 성장 곡선이 아니라
    # k6 부하테스트·수동 데모처럼 짧고 뾰족한 버스트라, 버스트 꼬리에서 태스크 수가 1↔2로 플래핑하는 걸
    # 막으려면 더 넉넉한 쿨다운이 안전하다(오케스트레이터 지시: "스케일인 쿨다운 넉넉히").
    scale_in_cooldown = 600
  }
}
