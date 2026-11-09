# CloudFront — ALB 앞단 무료 HTTPS(기본 도메인 dxxxx.cloudfront.net).
#
# 왜(ADR-0015): 공유용 신뢰 링크(Swagger·API 데모)에 HTTPS가 필요한데, ACM 공개 인증서는
# 소유·통제하는 도메인이 있어야 발급된다(*.elb.amazonaws.com엔 불가) — 이 계정엔 등록 도메인이
# 없다. 2026 현재 Freenom식 무료 도메인은 사실상 폐지됐다. 대안으로 CloudFront 배포를 ALB 앞에
# 두면 **AWS 관리 기본 인증서(*.cloudfront.net)** 로 도메인·ACM 신청 없이 즉시 신뢰 HTTPS를 얻는다
# (프리티어 1TB/월 + 고정비 0 → 이 트래픽엔 사실상 무료). 프론트 공개 앱(geuneul.vercel.app)은 이미
# Vercel HTTPS이고 BFF가 서버사이드로 ALB(http)에 붙으므로(ADR-0004) 이 배포에 영향받지 않는다 —
# CloudFront는 "직접 ALB 링크"에 HTTPS를 얹는 추가 진입점이다.
#
# 캐시: API는 동적이라 Managed-CachingDisabled(응답 캐시 안 함) + Managed-AllViewer(모든 헤더·쿠키·
# 쿼리스트링을 오리진으로 전달) — CloudFront를 CDN 캐시가 아니라 "관리형 HTTPS 프록시"로만 쓴다.

data "aws_cloudfront_cache_policy" "caching_disabled" {
  name = "Managed-CachingDisabled"
}

data "aws_cloudfront_origin_request_policy" "all_viewer" {
  name = "Managed-AllViewer"
}

resource "aws_cloudfront_distribution" "app" {
  enabled         = true
  comment         = "${var.project} ALB HTTPS (기본 도메인)"
  price_class     = "PriceClass_200" # 북미·유럽·아시아(서울 엣지 포함) — 국내 서비스에 충분, All보다 저렴
  http_version    = "http2and3"
  is_ipv6_enabled = true

  origin {
    domain_name = aws_lb.app.dns_name
    origin_id   = "alb"

    custom_origin_config {
      http_port              = 80
      https_port             = 443
      origin_protocol_policy = "http-only" # ALB 리스너는 80(HTTP)만 — CloudFront↔ALB 구간은 AWS 백본
      origin_ssl_protocols   = ["TLSv1.2"]
    }
  }

  default_cache_behavior {
    target_origin_id       = "alb"
    viewer_protocol_policy = "redirect-to-https"                                          # http로 와도 https로 리다이렉트
    allowed_methods        = ["GET", "HEAD", "OPTIONS", "PUT", "POST", "PATCH", "DELETE"] # 제보/후기 POST 등
    cached_methods         = ["GET", "HEAD"]

    cache_policy_id          = data.aws_cloudfront_cache_policy.caching_disabled.id
    origin_request_policy_id = data.aws_cloudfront_origin_request_policy.all_viewer.id
    compress                 = true
  }

  restrictions {
    geo_restriction {
      restriction_type = "none"
    }
  }

  viewer_certificate {
    cloudfront_default_certificate = true # *.cloudfront.net 기본 인증서 → 커스텀 도메인·ACM 불필요
  }
}
