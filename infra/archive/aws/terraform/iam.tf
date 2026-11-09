# --- ECS 태스크 실행 롤 (ECR pull, CloudWatch 로그, SSM 비밀 읽기) ---
resource "aws_iam_role" "ecs_task_execution" {
  name = "${var.project}-ecs-exec"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "ecs-tasks.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })
}

resource "aws_iam_role_policy_attachment" "ecs_exec_managed" {
  role       = aws_iam_role.ecs_task_execution.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

# task def의 secrets(valueFrom SSM)를 실행 롤이 읽을 수 있게.
resource "aws_iam_role_policy" "ecs_exec_ssm" {
  name = "${var.project}-exec-ssm"
  role = aws_iam_role.ecs_task_execution.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect   = "Allow"
      Action   = ["ssm:GetParameters"]
      Resource = "arn:aws:ssm:${var.aws_region}:${data.aws_caller_identity.current.account_id}:parameter/${var.project}/*"
    }]
  })
}

# --- 태스크 롤 (앱이 쓰는 AWS 권한 — 최소 권한 원칙) ---
resource "aws_iam_role" "ecs_task" {
  name = "${var.project}-ecs-task"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "ecs-tasks.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })
}

# PhotoService(S3Presigner)가 이 롤로 PUT/GET을 서명한다 — 정적 액세스키 없이 태스크 롤만으로 presign
# (규칙 D와 정합: 자격증명이 코드/env에 남지 않는다). cleanup은 만료된 claim의 정확한 key만 DeleteObject하며
# ListBucket 없이 동일 버킷 object ARN에만 최소 권한을 둔다.
resource "aws_iam_role_policy" "ecs_task_s3_photos" {
  name = "${var.project}-task-s3-photos"
  role = aws_iam_role.ecs_task.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect   = "Allow"
      Action   = ["s3:PutObject", "s3:GetObject", "s3:DeleteObject"]
      Resource = "${aws_s3_bucket.photos.arn}/*"
    }]
  })
}

# --- GitHub Actions OIDC (장기 액세스키 없이 배포 = 2026 베스트프랙티스) ---
resource "aws_iam_openid_connect_provider" "github" {
  url             = "https://token.actions.githubusercontent.com"
  client_id_list  = ["sts.amazonaws.com"]
  thumbprint_list = ["6938fd4d98bab03faadb97b34396831e3780aea1"]
}

resource "aws_iam_role" "github_actions" {
  name = "${var.project}-gha-deploy"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Federated = aws_iam_openid_connect_provider.github.arn }
      Action    = "sts:AssumeRoleWithWebIdentity"
      # sub를 main 브랜치로 한정 — 다른 브랜치/태그/포크 PR의 워크플로우가 이 롤을 assume하지 못한다.
      # (deploy.yml은 push:main + workflow_dispatch(main)에서만 돌므로 운영 영향 없음.)
      Condition = {
        StringEquals = { "token.actions.githubusercontent.com:aud" = "sts.amazonaws.com" }
        StringLike   = { "token.actions.githubusercontent.com:sub" = "repo:${var.github_repo}:ref:refs/heads/main" }
      }
    }]
  })
}

resource "aws_iam_role_policy" "github_actions" {
  name = "${var.project}-gha-policy"
  role = aws_iam_role.github_actions.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid      = "ECRAuth"
        Effect   = "Allow"
        Action   = ["ecr:GetAuthorizationToken"]
        Resource = "*"
      },
      {
        Sid    = "ECRPushPull"
        Effect = "Allow"
        Action = [
          "ecr:BatchCheckLayerAvailability", "ecr:GetDownloadUrlForLayer", "ecr:BatchGetImage",
          "ecr:PutImage", "ecr:InitiateLayerUpload", "ecr:UploadLayerPart", "ecr:CompleteLayerUpload"
        ]
        Resource = aws_ecr_repository.backend.arn
      },
      {
        # 서비스 갱신은 이 클러스터의 geuneul 서비스로 한정.
        Sid      = "ECSService"
        Effect   = "Allow"
        Action   = ["ecs:DescribeServices", "ecs:UpdateService"]
        Resource = aws_ecs_service.app.id
      },
      {
        # 태스크 정의 API는 리소스 수준 제한을 지원하지 않아 "*" 필수(AWS 제약).
        Sid      = "ECSTaskDef"
        Effect   = "Allow"
        Action   = ["ecs:DescribeTaskDefinition", "ecs:RegisterTaskDefinition"]
        Resource = "*"
      },
      {
        Sid      = "PassRole"
        Effect   = "Allow"
        Action   = ["iam:PassRole"]
        Resource = [aws_iam_role.ecs_task_execution.arn, aws_iam_role.ecs_task.arn]
        # ECS 태스크 실행 외 용도로 롤을 넘기지 못하게 고정.
        Condition = { StringEquals = { "iam:PassedToService" = "ecs-tasks.amazonaws.com" } }
      }
    ]
  })
}
