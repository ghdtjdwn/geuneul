# ADR-0005. 카공/카페 기능은 "여름 실내 시나리오"로 흡수한다 (리뷰앱·카페앱화 금지)

- 상태: **부분 채택** — 1탄(실시간 자리 여유/혼잡 제보 `SEAT_OK`/`CROWDED`) 라이브(PR #22). 나머지 흡수 범위는 방향 확인 대기 (2026-07-04)
- 관련: `reports.report_type`, `place_features`, `survival_score`(P3), `GET /recommendations`, SPEC.md §5·§9 원칙
- 근거 조사: 다중 에이전트 리서치(카공맵·유사앱 리뷰·GitHub `awesome-cafe`/`Buzzzzing-Server`·Workfrom·누클) — wf_d23510d2

## 문제(Context)

"카공맵(공부하기 좋은 카페 지도)의 기능을 전부 넣고 싶다"는 요구. 조사 결과 **카공(연중 카페 공부)의 실사용 조건 = 시원한 실내 + 앉을 곳 + 콘센트 + 눈치 안 봄 + 자리 여유** 인데, 이는 그늘 여름생존의 **"무더위쉼터·도서관·지하상가에서 오래 버티기"(air_conditioned + seating + outlet + no_eyes)** 와 정확히 포개진다. 즉 카공은 별개 경쟁 도메인이 아니라 그늘의 **부분집합**이다.

그러나 무분별 흡수는 두 리스크가 있다: (a) **정체성 희석** — "여름 생존 지도"가 "연중 카페앱"으로 번짐(계절성 상실), (b) **간판 상실** — 지리공간+실시간 UGC 스코어링 대신 별점·리뷰가 주인공이 되어 "그냥 리뷰앱"이 됨(SPEC.md §9 명시 경계).

## 결정(Decision)

카공 수요를 **간판을 강화하는 방향으로만** 흡수한다. 모든 후보 기능을 3분류로 판별한다.

### ① flagship 강화 (흡수 우선) — 지리공간 + 실시간 UGC 스코어링을 직접 키움
- **실시간 자리 여유/혼잡 제보** = `reports.report_type`에 `SEAT_OK`/`SEAT_FULL`(또는 `CROWDED`) 추가. **스키마 변경 없이 enum만.** 휘발성 제보(`expires_at`)로 흡수돼 `survival_score`의 `report_freshness_score`를 그대로 굴려 **"지금 앉을 수 있음"** 킬러 기능이 된다. → 조사에서 "자리없음·헛걸음"이 카공/카페 **최대 불만**으로 확인.
- **survival_score 구현(P3)** — 카공 도메인의 Workfrom "capacity score"가 단일 종합점수 수요를 재검증. 신규가 아니라 **미구현 간판의 우선 구현** 근거.
- **GPS 지오펜스 방문 인증** — 클라이언트 좌표와 `place.geom`을 `ST_DWithin`(예 100m) + 사진 EXIF 촬영시각 서버검증으로 `verified` 플래그 → `trust_score`/`confidence` 가중. PostGIS 재사용으로 허위 제보 억제(지리공간 + 신뢰도 동시 강화).
- **시간대별 혼잡 파생(popular-times)** — `reports.created_at`을 요일×시간 버킷 `GROUP BY` 집계 → 외부 API 없이 자체 popular-times. survival_score 시간축 확장. **P4 additive**(MVP로 당기지 않음).
- **실시간 제보 급증 알림** — 이미 로드맵 P4(Redis Streams/LISTEN·NOTIFY). `SEAT_FULL`/`CROWDED` 밀도 임계로 재사용.

### ② 살(로 흡수 가능) — 기존 스펙/스키마에 대응, comfort_score 정밀화
- **콘센트/와이파이/소음 등급화**: `place_features.value`가 이미 `VARCHAR(64)`라 boolean이 아닌 **개수·접근성·속도·등급**을 스키마 변경 없이 저장. `noise_level`/`lighting` feature_type만 추가.
- **목적별 추천 시나리오**: `GET /recommendations?scenario=`에 `focus`(조용·콘센트)/`longstay`(눈치 안 봄·영업 김) 추가 — 파라미터만, survival_score 재사용.
- **영업시간/24시간 필터**(`open_hours_json` + `open_now_score` 기존 설계), **사진 업로드**(`photo_url`/`presign` 기존 스펙), **북마크+메모**(`bookmarks` 테이블 신설 필요), **신고·모더레이션 큐**(기존 스펙), **정형 태그 리뷰**(자유서술보다 간판 친화적).

### ③ dilution (거부/보류) — 정체성 희석 또는 비목표 충돌
- **카공 브랜드/`CAFE` 카테고리 신설**: 연중 도메인 정면 유입 → 계절성 희석. 넣더라도 "냉방 실내 피난처"로 프레이밍하고 **제안-후-착수**.
- **aspect(항목별) 별점을 UI 주인공화**: 리뷰앱화. `place_features`가 이미 커버.
- **예약·결제(누클형), 마일리지·리워드, 소셜 팔로우, 가격대 필터, 좌석단위 콘센트 지도**: 카공맵/누클/리뷰앱의 정체성이며 그늘 비목표(유료화·소셜그래프)와 충돌 → 흡수 금지.

**정체성 방어 라벨링:** 좌석/혼잡 제보는 "쉼터·화장실 붐빔 / 자리 없음"의 **여름 언어**로 노출한다.

## 검토한 대안(Alternatives)

| 대안 | 기각 이유 |
|---|---|
| 별도 카공 앱/브랜드 | 스택 100% 겹침 + 정체성 분산 — 맛핀 흡수(ADR 이전) 교훈의 반복 |
| `CAFE` 카테고리 즉시 신설 | 연중 카페 도메인을 정면 유입해 "여름 생존" 계절성·제품 정체성 희석 |
| 흡수 안 함 | 실사용 최대 수요(자리 여유·콘센트 등급·최신성)를 놓침 |
| 외부 혼잡도 API 의존(Buzzzzing류) | 핵심 차별점은 "외부 API 없이 UGC 시공간 스코어링으로 실시간 체감을 자체 생성" — 의존 시 차별점 약화 |

## 결과(Consequences)

- 카공 수요가 **간판(PostGIS 시공간 UGC 스코어링) 강화**로 전환된다. 커버리지·최신성(idempotent ETL + soft-delete)과 실시간 UGC가 여전히 주인공.
- 흡수는 **데이터 축(place_features)과 실시간 상태(reports)** 두 갈래로만 — 둘 다 기존 스키마/알고리즘 재사용이라 저비용.
- 한계: 카공 UI를 원하면 결국 "카페 색채"(테이블 넓이·조도)가 스며들 수 있음 → dilution 목록을 게이트로 유지하고, 카페 카테고리 신설은 별도 ADR([ADR-0006](0006-study-space-coverage-expansion.md))로 승인받는다.

## 근거(References)

- 리서치 소스: cagongmap.com(SPA), threads 소개, [`awesome-cafe`](https://github.com/utilForever/awesome-cafe) 카공 메타 스키마, Buzzzzing-Server(Spring 혼잡도), Workfrom(capacity score), 누클(좌석 공유), 경향신문(카공 사절 갈등)
- 사용자 신호(top): 자리 여유("도착 전 좌석 확인" 재사용의향 80%)·콘센트 등급·눈치 안 봄·데이터 최신성·방문 인증 리뷰
- SPEC.md §5(survival_score)·§9(간판 vs 살)·비목표(유료화·소셜그래프 제외)
