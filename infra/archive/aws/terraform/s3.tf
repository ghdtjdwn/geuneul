# S3 버킷 — 제보/후기 사진(P2 presigned PUT 업로드, docs/SPEC.md §7·§9 POST /photos/presign).
# 파일은 백엔드를 거치지 않는다: 브라우저가 S3Presigner가 서명한 URL로 직접 PUT한다.
#
# 비공개 원칙: 퍼블릭 액세스 완전 차단 + ACL 비활성(BucketOwnerEnforced). MVP는 오브젝트 URL을 그대로
# report/review에 저장한다(§7 "MVP는 object URL 저장") — 실제 뷰잉을 열려면 presigned GET이나 CloudFront가
# 필요한데 그건 스코프 밖(HANDOFF 다음 조각으로 남김, 지금은 업로드 파이프라인만 완성).

resource "aws_s3_bucket" "photos" {
  bucket = "${var.project}-photos-${data.aws_caller_identity.current.account_id}" # 계정ID로 전역 유일성 확보
  tags   = { Name = "${var.project}-photos" }
}

resource "aws_s3_bucket_ownership_controls" "photos" {
  bucket = aws_s3_bucket.photos.id
  rule {
    object_ownership = "BucketOwnerEnforced" # ACL 비활성화 — 버킷 정책/IAM으로만 접근 통제(2020년대 AWS 권장 기본값)
  }
}

resource "aws_s3_bucket_public_access_block" "photos" {
  bucket                  = aws_s3_bucket.photos.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_versioning" "photos" {
  bucket = aws_s3_bucket.photos.id
  versioning_configuration {
    status = "Disabled" # UGC 원본 재현 필요성 낮음 — 비용 절감. 필요해지면 Enabled로 전환.
  }
}

# 브라우저가 presigned PUT으로 직접 업로드하려면 CORS가 있어야 한다(프리플라이트 OPTIONS + 실제 PUT).
# GET도 열어둔 이유: 이후 presigned GET(비공개 뷰잉)을 붙일 때 재적용을 피하기 위한 선제 허용.
resource "aws_s3_bucket_cors_configuration" "photos" {
  bucket = aws_s3_bucket.photos.id
  cors_rule {
    allowed_methods = ["PUT", "GET"]
    allowed_origins = ["https://geuneul.vercel.app", "http://localhost:3000"]
    allowed_headers = ["*"]
    expose_headers  = ["ETag"]
    max_age_seconds = 3000
  }
}

# presign만 발급되고 실제 PUT이 되지 않은 멀티파트 잔재가 쌓이는 것을 방지(단일 PUT이 기본이라 발생 빈도는
# 낮지만 비용 위생 차원). 오브젝트 자체엔 만료를 두지 않는다 — 제보/후기가 참조 중인 사진이 임의로
# 사라지면 깨진 링크가 된다.
resource "aws_s3_bucket_lifecycle_configuration" "photos" {
  bucket = aws_s3_bucket.photos.id
  rule {
    id     = "abort-incomplete-multipart"
    status = "Enabled"
    filter {} # 버킷 전체 대상(prefix/tag 조건 없음) — 최신 프로바이더는 filter나 prefix를 명시하지 않으면 경고.
    abort_incomplete_multipart_upload {
      days_after_initiation = 1
    }
  }
}
