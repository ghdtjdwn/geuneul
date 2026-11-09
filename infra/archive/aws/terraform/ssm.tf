# DB 비밀번호를 SSM Parameter Store(SecureString, 무료)에 저장 → ECS task def에서 secrets로 주입.
# 값은 var.db_password(터미널/tfvars에서만). 레포엔 안 들어감.
resource "aws_ssm_parameter" "db_password" {
  name  = "/${var.project}/db_password"
  type  = "SecureString"
  value = var.db_password
}

# BFF↔백엔드 공유 시크릿 (익명 제보 레이트리밋 XFF 신뢰경계, TS-008). 값은 var.proxy_secret(tfvars)로만.
# 실행 롤 SSM 정책이 /${var.project}/* 와일드카드라 별도 IAM 변경 불필요.
resource "aws_ssm_parameter" "proxy_secret" {
  name  = "/${var.project}/proxy_secret"
  type  = "SecureString"
  value = var.proxy_secret
}

# 기상청 serviceKey (P3 날씨 캐시). 값은 var.kma_service_key(tfvars)로만. 실행 롤 SSM 정책은 와일드카드.
resource "aws_ssm_parameter" "kma_service_key" {
  name  = "/${var.project}/kma_service_key"
  type  = "SecureString"
  value = var.kma_service_key
}

# 소셜 로그인(P2) 자격증명 + JWT 서명키. 전부 /${var.project}/* 와일드카드 SSM 정책 → IAM 무변경.
resource "aws_ssm_parameter" "kakao_rest_api_key" {
  name  = "/${var.project}/kakao_rest_api_key"
  type  = "SecureString"
  value = var.kakao_rest_api_key
}

resource "aws_ssm_parameter" "kakao_client_secret" {
  name  = "/${var.project}/kakao_client_secret"
  type  = "SecureString"
  value = var.kakao_client_secret
}

resource "aws_ssm_parameter" "google_client_id" {
  name  = "/${var.project}/google_client_id"
  type  = "SecureString"
  value = var.google_client_id
}

resource "aws_ssm_parameter" "google_client_secret" {
  name  = "/${var.project}/google_client_secret"
  type  = "SecureString"
  value = var.google_client_secret
}

resource "aws_ssm_parameter" "jwt_secret" {
  name  = "/${var.project}/jwt_secret"
  type  = "SecureString"
  value = var.jwt_secret
}

# data.go.kr 오픈API serviceKey (P3 무인 동기화). 값은 var.datago_service_key(tfvars)로만.
# ECS task def(ecs.tf)의 secrets로 주입되어 DataGoKrPublicLibraryClient가 사람 개입 없이 다운로드까지
# 자족 실행한다 — 이전엔 prod-ingest.sh처럼 실행할 때마다 셸 환경변수로 수동 주입해야 했다(무인 스케줄 불가 지점).
# 실행 롤 SSM 정책은 /${var.project}/* 와일드카드라 별도 IAM 변경 불필요.
resource "aws_ssm_parameter" "datago_service_key" {
  name  = "/${var.project}/datago_service_key"
  type  = "SecureString"
  value = var.datago_service_key
}

# AI 요약 프로바이더 API 키 (P3 AI 한줄 요약, 곁다리 — ADR-0010). 프로바이더 중립 OpenAI 호환 클라이언트
# (현재 Mistral). Anthropic 키가 없어 이탈(WORKLOG 기록). 값은 var.ai_summary_api_key(tfvars)로만.
# 실행 롤 SSM 정책은 와일드카드라 무변경. 미설정이어도 앱은 정상 기동 — ChatCompletionClient가 호출
# 시점에 지연검증해 AI 요약만 null로 폴백한다.
resource "aws_ssm_parameter" "ai_summary_api_key" {
  name  = "/${var.project}/ai_summary_api_key"
  type  = "SecureString"
  value = var.ai_summary_api_key
}
