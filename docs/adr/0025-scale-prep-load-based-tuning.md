# ADR-0025. 대규모 대비 — 부하 기반 튜닝(task_cpu 0.5vCPU · EXPLAIN 인덱스 · 오토스케일링/풀 점검)

- 상태: 승인(구현 반영·프로덕션 적용, 2026-07-11)
- 관련: ADR-0012(P4 부하테스트·EXPLAIN)·ADR-0013(오토스케일링)·TS-005(느린 부팅), `Flyway V18`,
  `perf/k6/n9-results.md`·`perf/explain/n9-me-activity-tuning.txt`, `infra/archive/aws/terraform`(task_cpu), SPEC.md §7·§10(P4)
- 후속: BACKLOG N9(구 F6 승격)

## 문제(Context)

"트래픽이 붙으면" 미루던 대규모 대비를 **선제적으로** 만든다(2026-07-11 결정 — 대규모 대비는 부하가 실제로 붙기 전에 측정 근거와 함께 갖춰야 의미가 있다). k6 재부하로 병목을 실측하고, 데이터에 근거해 튜닝한다.

## 결정(Decision)

### 1) k6 재부하 → 병목 실측(gentle, read-only)

`perf/k6/spatial_load.js`(반경/kNN/bounds/추천)를 프로덕션에 gentle하게(PEAK_VUS 4, 70s, read-only GET — "고부하
금지" 준수) 걸어 p95를 측정. **반경(스코어드 조인) p95 2.68s**가 튀는 게 병목으로 드러남(나머지는 수백 ms).

### 2) task_cpu 0.25 → 0.5 vCPU 승격(구 F6, TS-005 근본)

라이브 태스크데프를 `describe → .cpu="512" → register → update-service`로 0.5 vCPU rev로 교체(memory 1024 유지,
유효 조합). deploy.yml이 describe 기반이라 이후 이미지 배포에도 cpu=512 보존. terraform `task_cpu` 기본값도 512로.

- **결과(before→after)**: 반경 p95 **2.68s→1.39s(−48%)**, kNN 238→98ms, bounds 395→210ms, **처리량 ×2.1**,
  부팅 **93s→70s**(TS-005 완화). → 병목은 GiST 인덱스가 아니라 **CPU**였음을 부하로 실증(ADR-0012의 "0.25vCPU
  포화가 빠르다" 가정 확인).

### 3) EXPLAIN 재튜닝 → Flyway V18 인덱스

로컬 실 PostGIS에 V1~V17 적용 + 합성 시드 후 신규 조회를 EXPLAIN. **N6 "내 글 관리"의 유저필터 조회가 seq scan**
이었음: `/me/comments`(review_comments.user_id)·`/me/reactions`(reactions.user_id)에 인덱스 부재. V18로
`idx_review_comments_user(user_id, created_at DESC)`·`idx_reactions_user(user_id, target_type, created_at DESC)`
추가 → **Seq Scan → Bitmap Index Scan**(증빙 `perf/explain/n9-me-activity-tuning.txt`). `/me/reviews`(V6)·
`/me/following`(V17)·반경/kNN/bounds/corridor(V3 GIST geography)는 이미 인덱스 경로라 무변경.

### 4) 오토스케일링·커넥션풀 점검(변경 없음 — 근거 있는 유지)

- **오토스케일링**: min1/max3 + CPU 60% target 유지. task_cpu가 용량 레버였고 60%는 AWS 권장 중앙값(ADR-0013).
  vCPU 2배로 태스크당 용량이 배가돼 같은 트래픽에 스케일아웃이 늦춰짐(비용 유리). 임계를 흔들 이유가 없음.
- **HikariCP 풀**: 태스크당 기본 10. max3 × 10 = 30 < RDS db.t3.micro `max_connections`(~87) → 여유 충분.
  부하에서 병목은 커넥션이 아니라 CPU였으므로 풀 확대 불필요.

## 근거(Why)

- **왜 task_cpu가 첫 레버인가** — 부하 데이터가 "인덱스는 이미 서빙 중, CPU가 포화"라고 말했다. 인덱스를 더 만들거나
  풀을 키우는 대신 **측정이 가리키는 곳**(CPU)을 고쳤다. "추측 말고 측정 후 튜닝"(§10 P4).
- **왜 인덱스는 N6 두 개만인가** — EXPLAIN이 seq scan을 딱 두 쿼리에서만 잡았다. 나머지는 이미 인덱스 경로 →
  불필요한 인덱스는 쓰기 비용만 늘린다. 측정이 범위를 정했다.
- **왜 오토스케일링·풀은 유지인가** — 모든 노브를 돌리는 게 튜닝이 아니다. 데이터가 병목으로 안 가리키면 두는 게
  옳다(불필요한 변경은 회귀 위험·비용). 유지도 근거 있는 결정이다.

## 결과(Consequences)

- 프로덕션이 **반경 p95 반토막 + 처리량 2배 + 부팅 25% 단축**으로 대규모 트래픽에 더 견딤(선제 확보). 비용은
  Fargate compute ~2배(0.25→0.5 vCPU, ~$12→$24/월대)지만 $200 크레딧 내에서 감내 가능하고 실사용 심화 근거를 얻음.
- V18 인덱스로 마이 활동 조회가 대용량에서도 인덱스 경로 유지. before/after 증빙(k6·EXPLAIN)을 문서화해 "부하 근거 튜닝"의 재현 가능한 기록 확보.
