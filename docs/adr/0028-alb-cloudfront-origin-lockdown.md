# ADR-0028 — ALB를 CloudFront origin-facing으로 격리 (인터넷 직접 HTTP 노출 제거)

- 상태: AWS 운영 이력. 당시 apply·라이브 검증 뒤 2026-09-03 OCI Caddy edge로 대체하고 ALB·CloudFront를 삭제했다.
- 날짜: 2026-07-12
- 관련: ADR-0004(동일 오리진 BFF), ADR-0015(CloudFront 무료 HTTPS)

> **후속 상태(2026-07-12):** 아래 "남겨 둔 보안 트레이드오프"의 RDS 저장 암호화·백업·삭제보호는 [ADR-0029](./0029-rds-encryption-backup-snapshot-restore.md)에서 스냅샷 복원 방식으로 해결됐다. 해당 절은 이 결정 당시의 제약 기록이다.

## 문제(Context)

무료 HTTPS를 위해 CloudFront 기본 도메인을 앞에 뒀지만(ADR-0015), ALB 보안 그룹은 여전히 `80/tcp`를
`0.0.0.0/0`으로 열어 두고 있었다. 그래서 두 가지 노출이 남았다.

1. **ALB 직접 타격** — CloudFront를 우회해 ALB DNS로 직접 HTTP 요청이 가능했다. 백엔드에 레이트리밋·입력
   검증이 있어 즉각적 위험은 제한적이지만, HTTPS 강제(CloudFront의 `redirect-to-https`)를 우회하는 평문 경로가 남는다.
2. **BFF→ALB 평문 구간** — 프론트 BFF(Vercel)가 ALB를 직접(http) 호출하면 로그인 JWT·제보가 평문으로 오간다.

간판(공간검색)·실시간성과 무관한 "표면 정리"지만, 진입점을 하나로 좁히는 건 방어 깊이에 값한다.

## 결정(Decision)

1. **BFF를 CloudFront(HTTPS) 경유로 전환** — Vercel `GEUNEUL_API_BASE`를 ALB 직접 URL에서
   CloudFront 도메인(`https://d2pedv974beobb.cloudfront.net`)으로 바꾸고 재배포. 브라우저·BFF 모두 CloudFront만 탄다.
2. **ALB SG ingress를 CloudFront origin-facing으로 제한** — AWS 관리형 prefix list
   `com.amazonaws.global.cloudfront.origin-facing`(pl-22a6434b, ap-northeast-2)로 `80/tcp`를 좁혀
   인터넷 직접 타격을 차단한다. `data.aws_ec2_managed_prefix_list`로 참조해 대역이 자동 갱신되게 한다.

전환이 안전한 근거(전부 실측):
- CloudFront 캐시 정책 = **Managed-CachingDisabled** → 제보·후기·상태·survival_score 응답이 캐시되지 않아 **실시간성 무영향**.
- 허용 메서드 = GET/HEAD/OPTIONS/PUT/POST/PATCH/DELETE, origin request policy = AllViewer → **POST(제보·로그인)·쿼리스트링·Authorization 헤더 전달** 정상.
- SG `description`은 immutable이라 문자열을 바꾸면 SG가 replace되고 ALB가 잠깐 끊긴다 → **ingress 규칙만 in-place** 변경(무중단).

## 검토한 대안

| 대안 | 기각 사유 |
|---|---|
| ALB에 443 리스너 + ACM 인증서 | ACM은 검증할 커스텀 도메인이 필요한데 없음. CloudFront 기본 도메인이 이미 무료 HTTPS를 담당(ADR-0015) |
| SG description까지 정확히 변경 | SG description은 immutable → 변경 시 SG replace → ALB가 참조 중이라 다운타임 위험. 문자열은 유지하고 코드 주석·ADR로 실제 규칙 설명 |
| BFF는 ALB 직접 유지 + ALB만 잠금 | BFF가 CloudFront prefix list에 없어 즉시 차단됨(라이브 다운). BFF 전환이 선행 필수 |
| 그대로 두고 문서로만 근거 | 실제 노출(평문 우회 경로)이 남아 "완화책 서술"에 그침. 실변경이 실제 방어 |

## 결과(Consequences)

- **ALB 직접 HTTP → 차단**(연결 timeout, `000`). **CloudFront HTTPS·앱 BFF → 200 유지**. 무중단 전환.
- 진입점이 CloudFront(HTTPS) 하나로 수렴 → HTTPS 강제 우회 불가, BFF↔백엔드도 HTTPS.
- 트레이드오프: BFF→CloudFront→ALB로 홉이 하나 늘어 수십 ms 레이턴시 추가(캐시 안 하므로 매 요청). 간판 응답(공간검색 p95 ~1.4s)에 비해 무시 가능.

## 남겨 둔 보안 트레이드오프 (의도적, 실서비스 전환 시 재검토)

프로젝트·데모 규모에서 비용·리스크 대비 이득이 낮아 **의도적으로 남긴** 항목을 정직하게 기록한다.

- **RDS 저장 암호화** — 라이브 인스턴스는 미암호화(이전 생성분, `storage_encrypted`는 replace를 유발해
  `ignore_changes`로 고정). **신규 배포는 암호화되게 코드에 반영**돼 있고, 라이브 DB 암호화는 스냅샷→암호화본
  복원(다운타임)이라 데이터가 공공+재현가능한 현재 이득이 낮다.
- **RDS 백업/PITR·삭제보호** — 프리티어가 retention을 제약하고 데이터가 재적재로 복구 가능해 off. 유료 전환 시 켠다.

두 항목 모두 "무지성으로 다 켜기"보다 **제약과 트레이드오프를 알고 판단한 선택**이며, `rds.tf`에 근거가 있다.
