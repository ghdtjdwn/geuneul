# ADR-0015. ALB 무료 HTTPS — CloudFront 기본 도메인(*.cloudfront.net), 커스텀 도메인·ACM 없이

- 상태: 승인 (2026-07-10) · apply 완료·라이브
- 관련: `infra/terraform/cloudfront.tf`(신규)·`outputs.tf`(https_url)·`alb.tf`(오리진), CloudFront 배포 `https://d2pedv974beobb.cloudfront.net`
- 선행: [ADR-0004](0004-frontend-same-origin-proxy.md)(프론트는 동일 오리진 BFF로 ALB(http)에 서버사이드 접근 — 이 배포와 무관), SPEC.md §7(Infra)·§10 P4(공유 링크 신뢰도)

## 문제(Context)

라이브 API(ALB, `http://geuneul-alb-...elb.amazonaws.com`)를 **HTTPS로 공유**하고 싶다 — Swagger UI·API 데모 링크는 http면 브라우저 경고가 뜨고 신뢰도가 떨어진다. 공개 앱(`geuneul.vercel.app`)은 이미 Vercel HTTPS이고 BFF(ADR-0004)가 서버사이드로 ALB에 붙으므로 **최종 사용자 경로는 이미 HTTPS**다 — 남은 건 "직접 ALB 링크"에 HTTPS를 얹는 것.

정석은 ALB에 443 리스너 + ACM 공개 인증서다. 그러나 **ACM 공개 인증서는 소유·통제하는 도메인이 있어야 발급된다**(`*.elb.amazonaws.com`엔 발급 불가, DNS/이메일 검증 필요). 이 계정엔 등록 도메인이 없고, 2026 현재 Freenom식 무료 도메인은 사실상 폐지됐다(§0-B② 웹 확인). 무료 서브도메인(is-a.dev 등)은 외부 레포 PR 머지 대기가 있어 즉시성이 없다.

## 결정(Decision)

**CloudFront 배포를 ALB 앞에 두고, CloudFront 기본 도메인(`*.cloudfront.net`)의 AWS 관리 인증서로 HTTPS를 제공한다.** 커스텀 도메인·ACM 신청이 전혀 없이 즉시 신뢰 HTTPS를 얻는다.

- **오리진**: ALB(`aws_lb.app.dns_name`), `origin_protocol_policy = http-only`(ALB 리스너는 80만 — CloudFront↔ALB 구간은 AWS 백본).
- **뷰어**: `viewer_protocol_policy = redirect-to-https`(http로 와도 https로 301), 기본 인증서(`cloudfront_default_certificate = true`).
- **캐시 없음**: API는 동적이라 `Managed-CachingDisabled`(응답 캐시 안 함) + `Managed-AllViewer`(모든 헤더·쿠키·쿼리스트링을 오리진에 전달) — CloudFront를 CDN 캐시가 아니라 **"관리형 HTTPS 프록시"** 로만 쓴다. 메서드는 POST/PUT/DELETE 포함 전체 허용(제보·후기 등).
- **price_class = PriceClass_200**: 아시아(서울 엣지) 포함, All보다 저렴. 국내 서비스에 충분.

## 근거(Rationale)

- **비용 0에 가깝다**: CloudFront 프리티어 1TB/월 아웃 + 1천만 요청/월 + 고정비 없음 → 이 트래픽엔 사실상 무료. ALB 443+ACM 경로도 인증서 자체는 무료지만 도메인 비용·관리가 붙는다.
- **즉시성**: 배포 생성 ~2~3분, 도메인 등록/DNS 전파/PR 대기 없이 공개 진입점을 마련할 수 있다.
- **간판 불변**: 지리검색·survival_score·UGC 로직에 손대지 않는다 — 순수 인프라 진입점 추가(additive). BFF·오토스케일링·관측성 등 기존 경로 무영향.
- **되돌리기 쉽다**: 나중에 진짜 도메인이 생기면 CloudFront에 CNAME + ACM(us-east-1) 인증서를 붙이거나, ALB 443 리스너로 전환하면 된다 — 이 결정이 일방통행이 아니다.

## 검토한 대안(Alternatives)

| 대안 | 기각/보류 이유 |
|---|---|
| **ALB 443 리스너 + ACM 공개 인증서** | 정석이지만 통제 도메인이 필수 — 없음. 무료 도메인은 2026 현재 마땅한 게 없다. 도메인 확보 시 언제든 전환 가능(보류). |
| **무료 서브도메인(is-a.dev/js.org) + ACM + ALB 443** | URL은 예쁘지만(`geuneul.is-a.dev`) 외부 공개 레포에 GitHub PR을 올려 머지를 기다려야 함(수시간~수일) — 즉시 공개할 수 없다. 도메인 확보 후 옵션으로 남김. |
| **CloudFront 그대로 두고 캐시 켜기** | API 응답을 캐시하면 실시간 제보·survival_score가 굳어 오답을 준다(간판 훼손). CachingDisabled로 프록시로만 쓴다. |
| **HTTP 유지(아무것도 안 함)** | 공개 앱은 이미 HTTPS라 급하진 않지만, 직접 ALB/Swagger 링크 공유 시 신뢰도 손해 — 무료·즉시 해결책(CloudFront)이 있어 방치할 이유가 없다. |

## 결과(Consequences)

- `https://d2pedv974beobb.cloudfront.net` 로 health·Swagger·전체 API가 신뢰 HTTPS로 열린다(TLS 검증 통과, http→301 리다이렉트 확인).
- 레이트리밋: CloudFront 경유 요청은 CloudFront IP를 달고 오리진에 도착 → `ProxyClientResolver`가 최우측 XFF로 키잉(BFF 경로가 아님). 데모 링크엔 무해. 만약 CloudFront를 주 진입점으로 승격하면 신뢰 프록시 경계를 재점검해야 한다(현재는 부가 진입점).
- 관리형 캐시/오리진요청 정책은 data 소스로 참조(하드코딩 ID 아님) — AWS가 관리하므로 정책 드리프트 없음.
