resource "aws_ecs_cluster" "main" {
  name = var.project
  setting {
    name  = "containerInsights"
    value = "disabled" # 비용 절감. 관측성 심화(P4)에서 enabled.
  }
}

resource "aws_cloudwatch_log_group" "app" {
  name              = "/ecs/${var.project}"
  retention_in_days = 14
}

resource "aws_ecs_task_definition" "app" {
  family                   = var.project
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = var.task_cpu
  memory                   = var.task_memory
  execution_role_arn       = aws_iam_role.ecs_task_execution.arn
  task_role_arn            = aws_iam_role.ecs_task.arn

  container_definitions = jsonencode([{
    name         = var.project
    image        = var.container_image != "" ? var.container_image : "${aws_ecr_repository.backend.repository_url}:latest"
    essential    = true
    portMappings = [{ containerPort = 8080, protocol = "tcp" }]
    environment = [
      { name = "DB_HOST", value = aws_db_instance.postgres.address },
      { name = "DB_PORT", value = "5432" },
      { name = "DB_NAME", value = var.db_name },
      { name = "DB_USERNAME", value = var.db_username },
      { name = "PORT", value = "8080" },
      { name = "REDIS_HOST", value = aws_elasticache_cluster.redis.cache_nodes[0].address },
      { name = "REDIS_PORT", value = "6379" },
      { name = "S3_BUCKET_NAME", value = aws_s3_bucket.photos.bucket }, # P2 사진 presign(PhotoService)
      { name = "AWS_REGION", value = var.aws_region },                  # S3Presigner 리전 — ECS가 자동 주입 안 함
      { name = "SPRINGDOC_ENABLED", value = "false" },                  # 공개 운영 Swagger/OpenAPI는 fail-closed
      { name = "PHOTO_CLEANUP_ENABLED", value = "true" },               # 만료·미사용 upload claim/object bounded cleanup
      # AI 요약(P3, 곁다리 — ADR-0010) 비-시크릿 설정. base-url/model은 시크릿이 아니라 SSM이 아닌 평문
      # environment로 둔다(application.yml의 ai.summary.base-url/model 기본값과 동일). 라이브 태스크데프
      # 리비전은 container_definitions가 ignore_changes라 이 apply만으론 반영되지 않고(기존 값이 없으면
      # application.yml 기본값으로 폴백해 동작에는 문제 없음) 수동 rev 등록이 필요하다 — 이 두 줄은
      # fresh apply(신규 계정/재구축) 정합용으로 문서화해 둔다.
      { name = "AI_SUMMARY_BASE_URL", value = "https://api.mistral.ai/v1" },
      { name = "AI_SUMMARY_MODEL", value = "mistral-small-latest" }
    ]
    secrets = [
      { name = "DB_PASSWORD", valueFrom = aws_ssm_parameter.db_password.arn },
      { name = "GEUNEUL_PROXY_SECRET", valueFrom = aws_ssm_parameter.proxy_secret.arn },
      { name = "KMA_SERVICE_KEY", valueFrom = aws_ssm_parameter.kma_service_key.arn },
      { name = "KAKAO_REST_API_KEY", valueFrom = aws_ssm_parameter.kakao_rest_api_key.arn },
      { name = "KAKAO_CLIENT_SECRET", valueFrom = aws_ssm_parameter.kakao_client_secret.arn },
      { name = "GOOGLE_CLIENT_ID", valueFrom = aws_ssm_parameter.google_client_id.arn },
      { name = "GOOGLE_CLIENT_SECRET", valueFrom = aws_ssm_parameter.google_client_secret.arn },
      { name = "JWT_SECRET", valueFrom = aws_ssm_parameter.jwt_secret.arn },
      # P3 공공데이터 무인 동기화(scheduler.tf) — DataGoKrPublicLibraryClient가 다운로드까지 자족 실행하려면
      # RunTask 컨테이너에도 이 값이 있어야 한다(사람이 매번 셸 환경변수로 주입하던 prod-ingest.sh 방식과 달리
      # 스케줄 트리거는 사람이 없다). container_definitions는 ignore_changes라 기존 라이브 태스크데프에는
      # 다음 수동 rev 등록(describe→env/secret 추가→register→update-service, HANDOFF 패턴) 때 반영된다.
      { name = "DATA_GO_KR_SERVICE_KEY", valueFrom = aws_ssm_parameter.datago_service_key.arn },
      { name = "AI_SUMMARY_API_KEY", valueFrom = aws_ssm_parameter.ai_summary_api_key.arn } # P3 AI 요약(곁다리, ADR-0010)
    ]
    logConfiguration = {
      logDriver = "awslogs"
      options = {
        "awslogs-group"         = aws_cloudwatch_log_group.app.name
        "awslogs-region"        = var.aws_region
        "awslogs-stream-prefix" = "app"
      }
    }
  }])

  # 이미지 태그는 CI(deploy.yml)가 갱신 → TF가 되돌리지 않도록.
  lifecycle {
    ignore_changes = [container_definitions]
  }
}

resource "aws_ecs_service" "app" {
  name            = var.project
  cluster         = aws_ecs_cluster.main.id
  task_definition = aws_ecs_task_definition.app.arn
  desired_count   = 1
  launch_type     = "FARGATE"

  # Spring Boot 부팅 동안 ALB 헬스체크 실패로 태스크가 죽지 않도록 유예 (TS-005).
  # N9(ADR-0025)에서 task_cpu를 512(0.5 vCPU)로 승격 — 부팅 93s→70s로 단축(반경 p95도 2.68s→1.39s).
  # 240초 유예는 그대로 둔다(70초 부팅에도 넉넉한 안전 마진 — 스파이크/콜드 이미지 대비).
  health_check_grace_period_seconds = 240

  # 배포 서킷브레이커 + 자동 롤백 (TS-003): 부팅 불가 이미지가 배포되면
  # 크래시루프→ECS 런치 백오프로 수 시간 지연되는 대신, 실패 감지 즉시 직전 버전으로 롤백한다.
  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }

  network_configuration {
    subnets          = aws_subnet.public[*].id
    security_groups  = [aws_security_group.ecs.id]
    assign_public_ip = true # NAT 없이 ECR/인터넷 접근
  }

  load_balancer {
    target_group_arn = aws_lb_target_group.app.arn
    container_name   = var.project
    container_port   = 8080
  }

  depends_on = [aws_lb_listener.http]

  # 배포는 CI가 task_definition 리비전을 갱신 → TF가 되돌리지 않도록.
  # desired_count도 ignore — 배포 직후 수동 조정뿐 아니라, P4 ECS Service Auto Scaling(autoscaling.tf)이
  # 이 값을 부하에 따라 바꿀 때 TF apply가 그걸 되돌려 오토스케일러와 싸우지 않게 한다.
  lifecycle {
    ignore_changes = [task_definition, desired_count]
  }
}
