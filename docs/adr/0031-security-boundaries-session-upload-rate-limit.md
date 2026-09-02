# ADR-0031 — 업로드 claim·서버 세션 폐기·분산 레이트리밋

- 상태: 승인, 구현 완료·OCI production 적용 (2026-09-03 확인)
- 관련: ADR-0004(동일 오리진 BFF), V21 Flyway, Redis, 비공개 S3

## 문제

사진 필드는 `https://` 형식만 검사해 presign을 거치지 않은 외부 URL도 UGC에 저장할 수 있었다. JWT 로그아웃은
BFF 쿠키만 지워 탈취되거나 복사된 토큰을 만료 전까지 서버에서 폐기할 수 없었다. 레이트리밋은 ECS 인스턴스별
메모리에 있어 scale-out 시 한도가 인스턴스 수만큼 늘었고, BFF 공유 시크릿 누락 시 XFF 신뢰가 fail-open이었다.
제보를 숨기는 모더레이션도 제보에서 파생된 AI 요약과 popular-times 캐시를 함께 지우지 않았다.

## 결정

1. V21이 `photo_uploads` claim을 만든다. presign 시 S3 key·용도·예상 MIME/크기·30분 claim 만료와
   인증 user ID 또는 익명 client identity의 SHA-256을 기록한다. report/review 저장 전 같은 소유자·용도인지 확인하고,
   private S3 `HeadObject`로 실제 object의 타입과 크기가 일치할 때만 URL을 저장한다. claim row는 pessimistic lock으로
   한 번만 consume하고, 여러 review URL은 정렬된 순서로 잠근다. presigned PUT에는 `If-None-Match: *`를 함께 서명해
   consume 검증 뒤에도 같은 key를 덮어쓸 수 없다. 기존 저장 사진의 조회 계약과 같은 review에 이미 결합된 URL의
   유지 편집은 허용하되, 새 place/review에서 completed claim을 재사용할 수는 없다. review의 photo consume·upsert·
   trust score 갱신은 하나의 transaction이며 `(user_id, place_id)` advisory transaction lock이 첫 행의 no-row gap까지
   직렬화한다. 따라서 review flush가 실패하면 claim consume도 rollback된다. 후기에서 제거된 사진은
   같은 transaction에서 `completed_at`을 유지한 채 `detached_at`으로 reclaimable 전이한다. 최종 후기가
   참조하는 사진만 attached 상태로 남는다.
2. `users.token_version`을 0으로 추가하고 JWT `ver` claim과 매 요청 비교한다. 기존 JWT에 `ver`가 없으면 0으로
   해석해 마이그레이션 직후 로그인을 끊지 않는다. `POST /auth/logout`은 token version을 증가시키며, BFF는 이 호출이
   성공하거나 토큰이 이미 무효인 401일 때만 쿠키를 지운다. 재로그인의 profile refresh와 logout은 같은 user row의
   pessimistic write lock에서 직렬화해 stale entity update가 증가한 version을 되살릴 수 없게 한다. 첫 OAuth login은
   PostgreSQL `INSERT ... ON CONFLICT DO NOTHING`으로 no-row gap을 원자적으로 수렴시키고, conflict loser가 승자 행을
   lock/re-read한 뒤 profile을 갱신한다.
3. report row 생성·숨김은 `ReportDerivedCacheService`를 통해 DB의 place별 generation을 write transaction 안에서
   증가시킨다. `aiSummary`와 `popularTimes`는 `placeId:generation` key를 사용하고 loader 전·후 generation을 비교해,
   commit→`AFTER_COMMIT` eviction 뒤 끝난 stale reader도 예전 generation에 값을 공개할 수 없다. 변경을 감지한 reader는
   한 번 재조회하며, 지속 경쟁 시 캐시하지 않고 원본 값을 반환한다. rollback은 generation과 캐시를 둘 다 건드리지 않는다.
4. 세 레이트리밋(report, photo presign, 외부 API proxy)은 Redis Lua로 분·시간 counter를 원자적으로 공유한다.
   Redis 장애 시에는 기존 상한형 in-memory limiter로 폴백한다. Redis key에는 client identity 원문 대신 SHA-256만 둔다.
5. BFF는 [Vercel request header 계약](https://vercel.com/docs/headers/request-headers)이 외부 proxy에도 덮어쓰기
   방지를 보장하는 `x-vercel-forwarded-for`를 우선 client identity로 쓰고, `GEUNEUL_PROXY_SECRET`로 backend에
   증명한다. 이 secret 없이
   rate-limited route를 호출하면 500으로 중단한다. 백엔드는 시크릿 미설정 시
   전달 IP 헤더를 무시해 공유 TCP-peer bucket으로 축약한다. 가용성보다 위조 우회 방지를 우선한 fail-safe다.
6. Swagger/OpenAPI는 기본 비활성이고 로컬에서만 `SPRINGDOC_ENABLED=true`로 켠다.
7. 만료된 미사용 claim과 detached claim은 `FOR UPDATE SKIP LOCKED`로 회당 최대 50개를 lease한다.
   S3 `DeleteObject` 직전에 report/review의 현재 URL 참조를 DB에서 다시 확인하고, 참조 중이면 detach/lease를
   해제해 객체를 보호한다. 삭제 후 claim을 지우며,
   UUID lease token과 idempotent delete로 process/DB 실패를 재시도한다. lease는 15분 뒤 다른 ECS task가 회수한다.
   claim 만료·lease·consume 시각은 application clock가 아닌 PostgreSQL `CURRENT_TIMESTAMP`를 단일 기준으로 삼는다. cleanup
   token이 있는 미사용 행은 consume할 수 없고, completed 행은 detached일 때만 lease할 수 있는
   DB·entity invariant를 둔다.
   `geuneul.photo.cleanup.objects{outcome=claimed|deleted|failed}`와 run failure counter를 기록한다.
8. CodeQL·Dependabot과 private vulnerability reporting을 공급망/제보 경계로 둔다.
   [GHSA-mh99-v99m-4gvg](https://github.com/advisories/GHSA-mh99-v99m-4gvg)/CVE-2026-14257의 공식
   패치판은 `brace-expansion` 5.0.8이며 1.1.16은 패치되지 않았다. 전이 의존성 전체를 5.0.8로
   고정하고, callable CommonJS export를 기대하는 `minimatch@3.1.5`만 tracked pnpm patch로 `.expand`에
   연결한다. CI는 override·patch SHA-256·lockfile에 5.0.8 외 해석이 없음을 검증하고,
   deep chained brace PoC가 `maxLength`를 넘지 않는지 실행한 뒤 ignore 없이 `pnpm audit`한다.

## 검토한 대안

- URL host allowlist만 검사: 우리 버킷 URL을 문자열로 위조할 수 있고 PUT 완료도 증명하지 못한다.
- S3 bucket 공개 또는 서버 multipart upload: 현재 private presigned PUT 흐름의 비용·대역폭 이점을 잃는다.
- JWT denylist: 토큰마다 Redis 상태와 만료 정리가 필요하다. 사용자 단위 logout-all 요구에는 단조 증가 version이 작다.
- Redis 장애 시 모든 요청 허용/거부: 전자는 방어를 잃고 후자는 부가 계층 장애가 UGC 전체 장애가 된다. bounded local
  fallback이 현재 가용성 계약에 맞다.
- S3 lifecycle로 모든 `report/`·`review/` object 만료: 참조 중인 정상 UGC도 삭제한다. DB의 미사용 claim만 선택하는
  lease job이 참조 안전성과 재시도 가능성을 함께 보존한다.

## 결과와 운영 경계

- V21은 additive migration이지만 새 바이너리는 새 column/table(`users.token_version`, `photo_uploads`,
  `report_cache_generations`)을 요구한다. 배포 전 DB snapshot/PITR 상태를 확인하고
  기존 ECS rolling deploy가 Flyway V21을 한 번 적용하게 한다. 마이그레이션을 별도 수동 적용하거나 운영 DB에 직접
  실행하지 않는다.
- 롤백은 이전 task revision으로 가능하다. V21 column/table은 이전 코드가 무시하므로 즉시 drop하지 않는다. 데이터
  삭제는 별도 승인된 후속 migration으로만 한다.
- backend secret·V21 binary를 먼저 배포하고 기존 frontend 호환성과 logout endpoint를 검증한 뒤,
  같은 secret을 Vercel에 주입하고 새 BFF를 배포한다. 이 순서가 구 backend의 logout 404 창을 없앰다.
  이 구현 작업에서는 Vercel env, AWS, DB migration, deploy를 변경하지 않았다.
- cleanup은 `PHOTO_CLEANUP_ENABLED=false`로 중단할 수 있다. 실패 시 object/claim을 직접 삭제하지 말고 metric과 lease
  retry를 확인한다. `s3:DeleteObject` 권한은 photo bucket object ARN에만 있고 `ListBucket`은 부여하지 않는다.
- V21 이전 review에 저장된 사진 URL에는 `photo_uploads` claim이 없다. 기존 review에서 이 URL을 제거하거나 새 managed
  사진으로 교체하는 수정은 허용하지만, legacy object는 cleanup 관리 범위 밖이어서 자동 삭제할 수 없다. 별도 검증된
  backfill 또는 명시적 운영 정리 전까지 S3에 남을 수 있으며, 신규 첨부 URL에는 예외 없이 claim 검증을 적용한다.
