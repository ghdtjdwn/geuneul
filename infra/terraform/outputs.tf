output "alb_url" {
  description = "ALB origin URL (public health: /actuator/health; Swagger is production-disabled)"
  value       = "http://${aws_lb.app.dns_name}"
}

output "https_url" {
  description = "CloudFront 기본 도메인 HTTPS URL (public health: /actuator/health; Swagger is production-disabled)"
  value       = "https://${aws_cloudfront_distribution.app.domain_name}"
}

output "ecr_repository_url" {
  description = "이미지 push 대상"
  value       = aws_ecr_repository.backend.repository_url
}

output "ecs_cluster" {
  value = aws_ecs_cluster.main.name
}

output "ecs_service" {
  value = aws_ecs_service.app.name
}

output "rds_endpoint" {
  value = aws_db_instance.postgres.address
}

output "redis_endpoint" {
  description = "ECS 태스크데프 REDIS_HOST 로 주입할 ElastiCache 엔드포인트"
  value       = aws_elasticache_cluster.redis.cache_nodes[0].address
}

output "s3_photos_bucket" {
  description = "ECS 태스크데프 S3_BUCKET_NAME 으로 주입할 사진 버킷명 (P2 presign)"
  value       = aws_s3_bucket.photos.bucket
}

output "github_actions_role_arn" {
  description = "GitHub 레포 Secrets에 AWS_ROLE_ARN 으로 등록"
  value       = aws_iam_role.github_actions.arn
}

output "ingest_schedule_name" {
  description = "P3 공공데이터 무인 동기화 EventBridge Scheduler 이름 — 상태 확인: aws scheduler get-schedule --name <값> --group-name default"
  value       = aws_scheduler_schedule.public_data_sync.name
}

output "ingest_schedule_state" {
  description = "현재 ENABLED/DISABLED. 기본값은 ENABLED이며, 중지하려면 -var ingest_schedule_enabled=false로 적용한다."
  value       = aws_scheduler_schedule.public_data_sync.state
}

output "autoscaling_status" {
  description = "P4 ECS Service Auto Scaling 활성 여부 + min/max. var.autoscaling_enabled=false로 언제든 비활성화 가능."
  value       = var.autoscaling_enabled ? "ENABLED min=1 max=${var.autoscaling_max} (CPU target 60%)" : "DISABLED"
}
