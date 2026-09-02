resource "aws_ecr_repository" "backend" {
  name = var.project
  # 배포는 커밋 SHA 태그만 쓰므로(deploy.yml) 태그 덮어쓰기를 금지 — 태그 하이재킹/우발적 재푸시 방어.
  image_tag_mutability = "IMMUTABLE"
  image_scanning_configuration {
    scan_on_push = true
  }
  force_delete = true # 개발 환경 정리 편의. 장기 보존 환경에서는 false.
}

# 오래된 이미지 자동 정리(비용 절감) — 최근 10개만 보관.
resource "aws_ecr_lifecycle_policy" "backend" {
  repository = aws_ecr_repository.backend.name
  policy = jsonencode({
    rules = [{
      rulePriority = 1
      description  = "keep last 10 images"
      selection = {
        tagStatus   = "any"
        countType   = "imageCountMoreThan"
        countNumber = 10
      }
      action = { type = "expire" }
    }]
  })
}
