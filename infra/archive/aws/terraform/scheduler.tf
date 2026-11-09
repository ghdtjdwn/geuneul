# P3 공공데이터 주기 동기화 무인화 — EventBridge Scheduler(월1회) → ECS RunTask(one-off, 기존 태스크데프 재사용).
# docs/SPEC.md 로드맵 P3: "멱등 upsert 재실행 + 스냅샷에서 사라진 행 soft-delete 비활성화 + 오픈API serviceKey로
# 다운로드까지 무인화". 대상은 전국도서관표준데이터(library, domain.ingest.openapi) — 이미 페이지네이션
# 오픈API로 전량 자체 수집하고(파일/URL 불필요), soft-delete diff도 지원한다(ADR-0006). CSV 소스(쉼터/화장실)는
# 스냅샷 URL이 GitHub Release 고정 자산이라 "주기 재동기화"의 의미가 약해 이번 스케줄 대상에서 제외했다
# (필요해지면 이 스케줄을 복제해 --ingest.source만 바꾸면 됨).
#
# 안전장치 이력: var.ingest_schedule_enabled는 처음엔 기본 false(DISABLED 생성 → 사람이 실트리거 1회 검증 후
# 활성화)였고, 2026-07-10 검증 완료 후 기본값을 true로 승격했다(variables.tf 참고). 끄려면 -var ingest_schedule_enabled=false.
#
# 동시 실행 방지는 인프라가 아니라 애플리케이션 레벨(IngestBatchLock, Postgres advisory lock)에서 처리한다 —
# 스케줄이 겹치거나 사람이 prod-ingest.sh를 동시에 돌려도 나중 실행은 즉시 포기(exitCode=0)한다.

# --- EventBridge Scheduler 실행 롤 (RunTask + PassRole만, 최소 권한) ---
resource "aws_iam_role" "ingest_scheduler" {
  name = "${var.project}-ingest-scheduler"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "scheduler.amazonaws.com" }
      Action    = "sts:AssumeRole"
      Condition = {
        # confused deputy 방지(AWS 권장 패턴) — 다른 계정의 스케줄이 이 롤을 assume하지 못하게.
        StringEquals = { "aws:SourceAccount" = data.aws_caller_identity.current.account_id }
      }
    }]
  })
}

resource "aws_iam_role_policy" "ingest_scheduler_run_task" {
  name = "${var.project}-ingest-scheduler-runtask"
  role = aws_iam_role.ingest_scheduler.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid      = "RunTask"
        Effect   = "Allow"
        Action   = ["ecs:RunTask"]
        Resource = "arn:aws:ecs:${var.aws_region}:${data.aws_caller_identity.current.account_id}:task-definition/${var.project}:*"
        Condition = {
          ArnLike = { "ecs:cluster" = aws_ecs_cluster.main.arn }
        }
      },
      {
        # RunTask가 태스크를 실행 롤/태스크 롤로 기동하려면 스케줄러 롤이 그 두 롤을 "전달(PassRole)"할 수
        # 있어야 한다 — ecs.tf/iam.tf가 쓰는 롤을 그대로 재사용(신규 롤 생성 없음).
        Sid      = "PassRoleToEcs"
        Effect   = "Allow"
        Action   = ["iam:PassRole"]
        Resource = [aws_iam_role.ecs_task_execution.arn, aws_iam_role.ecs_task.arn]
        Condition = {
          StringEquals = { "iam:PassedToService" = "ecs-tasks.amazonaws.com" }
        }
      }
    ]
  })
}

# --- 스케줄 본체 ---
#
# Terraform aws_scheduler_schedule의 `ecs_parameters` 네이티브 블록은 2026-07 기준으로도
# containerOverrides를 지원하지 않는다(hashicorp/terraform-provider-aws#34057, 미해결) — ecs_parameters로는
# "어떤 태스크데프를 어떤 네트워크로 띄울지"까지만 되고 커맨드 오버라이드(--ingest.source=... 등)를 못 싣는다.
# 대신 EventBridge Scheduler의 "Universal Target"(ARN 패턴 arn:aws:scheduler:::aws-sdk:<service>:<action>,
# 2023년 출시)을 쓴다 — ECS RunTask API를 SDK 그대로 호출해 input JSON에 overrides까지 전부 실을 수 있다.
# (AWS 공식 문서 "Using universal targets in EventBridge Scheduler" 확인, 2026-07 웹검색.)
resource "aws_scheduler_schedule" "public_data_sync" {
  name        = "${var.project}-public-data-sync"
  group_name  = "default"
  description = "P3 월1회 공공데이터 무인 동기화 — library(전국도서관표준데이터) 재적재 + soft-delete diff. on/off는 var.ingest_schedule_enabled."

  flexible_time_window {
    mode = "OFF" # 정확히 지정 시각에 실행 — 월1회 저빈도라 지연 창을 둘 이유가 없다.
  }

  # 매월 1일 19:00 UTC = 매월 2일 04:00 KST(트래픽 낮은 새벽). 국내 서비스지만 cron 표현식 자체는
  # UTC 기준으로 고정해 서머타임 등 타임존 규칙 변경에 흔들리지 않게 한다(AWS 권장).
  schedule_expression          = "cron(0 19 1 * ? *)"
  schedule_expression_timezone = "UTC"

  state = var.ingest_schedule_enabled ? "ENABLED" : "DISABLED"

  target {
    arn      = "arn:aws:scheduler:::aws-sdk:ecs:runTask"
    role_arn = aws_iam_role.ingest_scheduler.arn

    # EventBridge Scheduler Universal Target(aws-sdk:ecs:runTask)의 input은 AWS API 모델 그대로 **PascalCase**를
    # 요구한다(camelCase면 CreateSchedule이 "missing field TaskDefinition"으로 400 — 실측으로 확인, TS-020).
    input = jsonencode({
      Cluster        = aws_ecs_cluster.main.arn
      TaskDefinition = aws_ecs_task_definition.app.family # family만 지정 = 항상 최신 ACTIVE 리비전(prod-ingest.sh와 동일 관례)
      LaunchType     = "FARGATE"
      Count          = 1
      NetworkConfiguration = {
        AwsvpcConfiguration = {
          Subnets        = aws_subnet.public[*].id
          SecurityGroups = [aws_security_group.ecs.id]
          AssignPublicIp = "ENABLED" # NAT 없음 — 서비스 태스크와 동일 이유(network.tf 주석)
        }
      }
      Overrides = {
        ContainerOverrides = [{
          Name = var.project
          # IngestionRunner CLI 계약(도메인 클래스 주석 참고): library=오픈API 페이지네이션 전량 수집이라
          # 파일/URL 불필요 — serviceKey(DATA_GO_KR_SERVICE_KEY, ecs.tf secrets)만으로 다운로드까지 자족.
          # deactivate-stale=true: 전량 스냅샷 소스라 안전(IngestionService 안전장치, ADR-0006).
          # server.port=8081: 서비스 태스크(8080, ALB 대상)와 겹치지 않는 one-off 전용 포트(prod-ingest.sh 관례).
          Command = [
            "--ingest.source=library",
            "--ingest.trigger=scheduled",
            "--ingest.deactivate-stale=true",
            "--ingest.exit-after=true",
            "--server.port=8081"
          ]
        }]
      }
    })

    retry_policy {
      # 월1회 저빈도라 실패를 공격적으로 재시도할 이유가 없다(다음 달 스케줄이 사실상 재시도) —
      # RunTask API 호출 자체가 일시적으로 실패한 경우(스로틀링 등)만 가볍게 2회 재시도.
      maximum_retry_attempts       = 2
      maximum_event_age_in_seconds = 3600
    }
  }
}
