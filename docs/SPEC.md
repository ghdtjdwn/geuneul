# 그늘 (Geuneul) — 프로젝트 스펙

> **프로젝트 마감 상태:** 이 문서는 착수·확장 과정의 제품 스펙과 결정 기준을 보존한다. W0–P5 로드맵은 완료 이력이며, 현재 기능·운영 상태는 [루트 README](../README.md)와 [아키텍처](./architecture.md)를 기준으로 한다.

> 폭염·장마·벌레·화장실·식수·냉방·콘센트까지, **오늘 밖에서 살아남기 위한 생활 생존 지도.**
> 태그라인: "지금 쉬어갈 그늘을 찾아드립니다."

커버리지: **공공데이터가 있는 곳 전부(전국 표준데이터 그대로 적재, 지도가 필터링)** · UGC 필드테스트 거점: 서울 동작구(상도·노량진)
성격: 공공데이터 + 유저 제보/후기 + AI 추천 · **핵심 = PostGIS 대용량 지리검색(반경/kNN) + 실시간 UGC 시공간 스코어링.**

> 이 문서는 프로젝트의 목표·범위·데이터·알고리즘·ERD·API 계약을 정의하는 단일 스펙이다. 코드와 ADR에서 "§n"으로 참조한다. 개별 의사결정 근거는 [`adr/`](./adr)에 남긴다.

---

## 0. 개발 원칙

1. **MVP 우선순위를 지킨다.** "MVP" 표시된 것부터 만들고, "2차/심화"는 임의로 당겨오지 않는다.
2. **범위를 임의로 늘리지 않는다.** 새 기능은 스펙/백로그로 합의된 것만 구현한다.
3. **공공데이터 ingestion은 재실행 가능(idempotent)하게** 만든다. 같은 소스를 두 번 넣어도 중복이 안 생겨야 한다.
4. **좌표/반경 검색은 PostGIS 인덱스(GiST)** 를 쓴다. 애플리케이션 레벨에서 전체 스캔하지 않는다.
5. **모든 주요 API에는 테스트를 작성**한다. (공간쿼리·인제스천은 Testcontainers 실 DB로 검증.)
6. 침수·위험 정보는 **공포를 조장하지 않게** 표현한다("위험!" 대신 "최근 침수 제보 있음, 우회 권장").
7. 제보·후기는 **허위·명예훼손 관리가 필수**다. 신고/검수(모더레이션) 큐를 처음부터 염두에 둔다.
8. AI(요약·제보 분류·상태 정규화)는 곁다리다 — **핵심은 지리공간**. 프로바이더는 교체 가능하게 설계한다.
9. **"간판 vs 살"을 구분한다.** 지리공간 + 실시간 UGC 스코어링이 핵심 차별점(간판). 로그인·후기·커뮤니티는 "진짜 유저가 쓰는 제품"으로 만들어주는 살이지 주인공이 아니다. 커뮤니티가 주인공이 되면 리뷰앱이 되어 차별점이 사라진다.

비밀(키·비번·토큰)은 코드·문서·로그에 절대 하드코딩하지 않는다 — `.env`/`.local`(gitignore)·SSM으로만. 커밋 전 gitleaks 스캔.

## 1. 한 줄 정의와 핵심 가치

지도에 **위치만 찍지 말고 "지금 상태"를 보여준다.** 기존 공공지도는 "여기 화장실 있음"까지만 알려주지만, 그늘은
"지금 시원한지, 앉을 수 있는지, 콘센트 있는지, 벌레 많은지, 침수됐는지"를 **최근 제보 기준으로** 보여준다.

핵심 차별점 = **survival_score(지금 갈만함 점수)** + **최근성(freshness) 기반 실시간 체감 정보**.

**UGC 2단 구조 (핵심 설계):**
- **제보(report)** = 휘발성 실시간 상태("지금 시원함/벌레 많음", `expires_at` 있음) → **survival_score의 freshness**를 굴린다. 한 탭으로 올리는 낮은 부담 입력.
- **후기(review)** = 영구 정성 평가 → **장소 평판·커뮤니티** 콘텐츠. 로그인 필요.

→ "휘발성 상태 vs 영구 평판" 2단 + 신뢰도(trust) 가중이 이 서비스의 설계 포인트.

## 2. 타깃 사용자 & 시나리오

| 사용자 | 상황 | 앱이 해결할 일 |
|---|---|---|
| 대학생 | 수업 사이 1~2시간, 돈 쓰기 싫음 | 무료로 앉을 수 있고 시원한 곳 + 콘센트 여부 |
| 러너/산책러 | 한강·공원에서 물·화장실 필요 | 음수대·화장실·그늘·벌레 제보 |
| 커플/친구 | 더워서 실내로 피하고 싶음 | 눈치 안 보고 잠깐 쉴 실내 |
| 통학러 | 비 오고 길 미끄러움 | 침수·미끄럼·지하 이동 루트 제보 |
| 노트북 유저 | 카페 말고 잠깐 충전할 곳 | 콘센트/와이파이/자리 필터 |

## 3. 기능 범위 (MVP / 2차 / 심화)

| 분류 | 기능 | 단계 |
|---|---|---|
| 생존 인프라 | 무더위쉼터, 공중화장실, 음수대, 공원, 공공기관, 도서관, 지하상가 | **MVP** |
| 지리검색 | 반경 검색(ST_DWithin), 최근접(kNN `<->`), bounds 조회, 마커 클러스터 | **MVP** |
| 체감 편의 | 시원함, 앉을 수 있음, 콘센트, 와이파이, 눈치 안 보임, 공부 가능 | **MVP 일부** |
| 여름 리스크 | 벌레/러브버그/모기, 악취, 흡연냄새, 미끄럼, 침수, 공사먼지 | **MVP** |
| 실시간 제보 | 지금 시원함/더움/물 있음/화장실 깨끗함/벌레 많음/침수됨 (휘발성) | **MVP** |
| 로그인 | 카카오/구글 소셜 로그인(OAuth), JWT 세션 | **MVP** |
| 후기(리뷰) | 장소별 영구 후기(별점/코멘트/사진) | **MVP** |
| 추천 | 날씨·거리·운영시간·제보 신뢰도 반영 "지금 갈만함" 점수 | **MVP** |
| AI 요약 | "최근 제보 기준 시원하지만 화장실은 별로" 한 문장 요약 | **MVP** |
| 신뢰도 | 유저 trust_score, 제보/후기 가중, 스팸 억제 | **MVP 일부** |
| 커뮤니티 | 댓글, 사진 인증, "유용했어요", 허위 제보/후기 신고 | **2차** |
| 모더레이션 | 신고 큐, 장소 병합, 데이터 수동 보정 (관리자) | **2차** |
| 알림 | 내 주변 침수/벌레 급증, 관심 장소 상태 변화, 폭염 피난 추천 | **심화** |
| 루트 | 비 피하는 경로, 그늘 경로, 화장실 포함 경로 | **심화** |

**비목표(하지 않는다):** 유료화·광고 없음. **친구/그룹 소셜 그래프·스토리·팔로우 피드는 하지 않는다** — 그늘은 폐쇄 친구망이 아니라 **공개 커먼스**다.

**커버리지 원칙:** 공공데이터 레이어는 **전국을 그대로 적재**한다 — 인제스천은 배치 upsert라 범위 제한이 비용을 아끼지도 않고, 데이터가 많을수록 대용량 지리검색이 실전 소재가 된다. 지도는 어디서 열어도 동작한다. **"좁게 집중"은 UGC·검증에만 적용** — 제보 커뮤니티 시딩과 현장 필드테스트는 동작구(상도·노량진)에서 한다(콜드스타트 해법, 하이퍼로컬 런칭 방식). 서비스 범위를 특정 지역으로 제한하지 않는다.

## 4. 데이터 소스

초기 데이터는 공공데이터로 확보하고, **체감 정보는 유저 제보/후기로** 쌓는다.

| 데이터 | 목적 | 수집 방식 | 주의점 |
|---|---|---|---|
| 무더위쉼터 | 시원한 공공 피난 장소(기본 레이어) | 공공데이터 API/CSV | 운영시간 최신성 검증 |
| 공중화장실 | 러닝·데이트·통학 필수 | 전국공중화장실표준데이터(2026-07 실측 59,768건) + 주소 지오코딩 | 2025-02 이후 WGS84 좌표 전면 미제공 → **주소 지오코딩 보완**(카카오 로컬 API) |
| 음수대/식수 | 러닝·폭염 핵심 | 서울시 공원음수대 데이터, 추후 지자체 확장 | 운영 여부는 제보로 보완 |
| 날씨 | 폭염·강수·습도 기반 점수 | 기상청 초단기실황 | **Redis TTL 캐싱 필수**(rate limit) |
| 침수/안전 | 장마철 위험 회피 | 서울/지자체 공간정보 + 유저 제보 | 표현 주의(공포 조장 금지) |
| 체감 제보/후기 | 시원함/벌레/냄새/콘센트/자리/평판 | 앱 내 제보·후기 | 허위·명예훼손 관리(모더레이션) |

## 5. survival_score 알고리즘

```
survival_score =
    0.25 * distance_score        # 가까울수록 높음
  + 0.20 * open_now_score        # 지금 열려 있으면 높음
  + 0.20 * comfort_score         # 시원함/앉을 곳/콘센트/물/화장실
  + 0.20 * report_freshness_score# 최근 제보일수록 높음 (신뢰도 가중)
  - 0.15 * risk_score            # 벌레/냄새/침수/혼잡

report_freshness_score:
  0~1시간 = 1.0 | 1~3시간 = 0.8 | 오늘 = 0.6 | 이번 주 = 0.3 | 1주 이상 = 0.1
```

- 마커 색은 카테고리가 아니라 **survival_score 구간**으로 칠한다(초록=지금 좋음 / 노랑=보통 / 회색=정보 부족).
- 제보는 **trust_score로 가중**한다(신뢰도 낮은 유저·스팸 제보의 영향 축소).
- **후기(review)는 survival_score와 분리**된 "장소 평판(reputation)"으로 관리한다(휘발성 상태 ≠ 영구 평판). 시공간 랭킹은 DB(PostGIS/SQL) 레이어에서 계산한다.
- 결측 성분(open_now 등)은 지어내지 않고 **재정규화로 제외**한다 — 데이터가 붙으면 가중치 복원만으로 확장(ADR-0007·0009).

## 6. MVP 화면

| 화면 | 필수 요소 |
|---|---|
| 홈 지도 | 현재 위치, 카테고리/피처 필터, 마커 클러스터, survival_score 색상 |
| 장소 상세 | 주소, 운영시간, 시설, 최근 제보, **후기 목록**, 사진, AI 요약 |
| 제보하기 | 상태 선택, 사진 선택, 한 줄 코멘트, 익명 여부(로그인 시 신뢰도 반영) |
| 후기 작성 | 별점, 코멘트, 사진 (로그인 필요) |
| 로그인/프로필 | 카카오/구글 로그인, 내 제보·후기·신뢰도 |
| 추천 탭 | "지금 30분 버틸 곳" / "화장실 급할 때" / "비 피할 곳" |
| 관리자(2차) | 허위 제보/후기 신고 큐, 장소 병합, 데이터 수동 보정 |

위험 정보 문구는 권유형·중립 톤("최근 침수 제보가 있어요 · 우회를 권장해요")을 쓴다.

## 7. 기술 스택

- **Frontend**: Next.js + TypeScript, PWA 우선(모바일 웹). 지도 UX·공유 링크·SEO에 유리.
- **Backend**: **Spring Boot 4 + Java 21**.
- **DB**: PostgreSQL + **PostGIS**. 공간 매핑은 **Hibernate Spatial + JTS**, 컬럼 `geometry(Point,4326)`(WGS84). 반경검색 `ST_DWithin`(GiST 인덱스), 최근접 `<->`+`ORDER BY`(KNN). 스키마 마이그레이션은 **Flyway**.
- **Geocoding**: **카카오 로컬 API**(주소→좌표, 지번/도로명) — 공중화장실 WGS84 결측 보완. 결과 좌표는 저장(멱등·rate limit 회피).
- **Cache**: Redis (날씨 초단기실황 TTL 캐시, rate limit, 조회 캐시)
- **Realtime/Event**: 제보 급증 알림 등은 **Redis Streams / Postgres LISTEN·NOTIFY**로. (Kafka 등 과설계 금지 — 필요 입증 후에만.)
- **Storage**: private S3 (제보/후기 사진, `If-None-Match:*` presigned PUT + 일회성 upload claim/HeadObject 검증 + 만료 미사용 object bounded cleanup)
- **Auth**: **카카오/구글 소셜 로그인(OAuth2) + user row 직렬화·token_version으로 서버 폐기 가능한 JWT 세션**
- **Map**: Kakao Maps (국내 POI/UX)
- **AI**: OpenAI 호환 프로바이더 중립 클라이언트(장소 요약) — 곁다리, 교체는 config만
- **Infra (AWS)**: **ECS Fargate**(관리형 컨테이너) + **RDS PostgreSQL(PostGIS)** + **Terraform(IaC)** + **GitHub Actions(OIDC로 키 없이 배포)** + **ECR** + ALB + CloudFront. 프론트는 Vercel. Docker Compose는 로컬 개발용.
  - NAT 게이트웨이 없이 Fargate는 퍼블릭 서브넷(SG로 잠금)에 두어 비용 절감. RDS·Redis는 프라이빗 서브넷.
- **Test/Ops**: Testcontainers(PG16+PostGIS/Redis), JaCoCo, k6(seed 고정 + JSON summary 부하테스트), OpenTelemetry/Grafana(인제스천 원장 freshness 포함), gitleaks.

## 8. ERD

```
users(id, provider, provider_id, email, nickname, profile_image, trust_score, role, token_version, created_at)
  # provider: KAKAO | GOOGLE ; role: USER | ADMIN

places(id, name, category, address, geom(Point,4326), source, source_external_id,
       external_map_url, open_hours_json, geocoded, created_at, updated_at)
  # source_external_id + source = 멱등 upsert 자연키 ; geocoded = 지오코딩 보완 여부

place_features(id, place_id, feature_type, value, source, confidence)
  # feature_type: air_conditioned, outlet, wifi, restroom, water, seating, no_eyes

reports(id, user_id, place_id, report_type, status_value, comment,
        photo_url, confidence, is_anonymous, created_at, expires_at)
  # report_type: SEAT_OK, CROWDED, COOL, HOT, BUG, ODOR, SMOKE, FLOOD, SLIPPERY, WATER_OK, RESTROOM_CLEAN
  # 휘발성 — expires_at 지나면 freshness/score에서 제외

reviews(id, user_id, place_id, rating, comment, photos_json, created_at, updated_at)
  # 영구 평판 — 로그인 필요, survival_score와 분리

review_comments(id, review_id, user_id, comment, created_at)          # 2차: 커뮤니티
reactions(id, target_type, target_id, user_id, type)                  # 2차: 유용했어요 등
reports_flags/reviews_flags(id, target_id, reporter_id, reason, status, created_at)  # 신고/검수 큐
trust_scores(id, target_type, target_id, score, factors_json, calculated_at)
bookmarks(id, user_id, place_id, memo, created_at)
notifications(id, user_id, type, condition_json, is_active, created_at)   # 심화

ingest_runs(id, source, input_fingerprint, trigger_type, retry_of, status, started_at, finished_at,
            expected_records, total_records, upserted_records, skipped_records,
            geocoded_records, geocode_failed_records, backfilled_records, duration_ms, complete, error_code)
  # one-off 실행 원장. retry_of는 같은 source+입력 digest 계보, error_code는 클래스명만(메시지/원본 미저장)
ingest_dead_letters(id, run_id, source, category, record_count, retryable, created_at, resolved_at)
  # 원본 payload가 아닌 실패 유형별 집계. 성공 retry가 조상 chain을 해결 처리
```

## 9. API

```
# 인증
POST /auth/kakao , POST /auth/google          # 소셜 로그인 → JWT
GET  /me                                        # 내 프로필/신뢰도

# 지도/장소 (PostGIS)
GET  /places?lat=&lng=&radius=&category=&feature=   # 반경 마커 조회
GET  /places?bounds=                                # bounds 조회
GET  /places/nearest?lat=&lng=&type=                # kNN 최근접
GET  /places/{id}                                   # 상세 + 최근 제보 + 후기 + AI 요약

# UGC
POST /reports                                       # 장소 상태 제보(휘발성)
POST /reviews                                        # 장소 후기(영구, 로그인)
GET  /places/{id}/reviews
POST /reviews/{id}/comments                          # 2차
POST /photos/presign                                 # 소유자·용도 claim 기록 + 사진 업로드 URL 발급
POST /flags                                          # 허위 제보/후기 신고

# 추천/관리
GET  /recommendations?lat=&lng=&scenario=            # scenario: rest30 | restroom | rain
GET  /admin/flags/pending                            # 관리자 검수 큐
POST /notifications/rules                             # (심화) 관심 알림 설정
```

## 10. 로드맵 (완주)

| Phase | 목표 | 산출물 |
|---|---|---|
| **W0 · 세팅** | 레포·기술 결정·인프라 연결 | Docker Compose(PostGIS+Redis), Flyway 스키마, Kakao Maps SDK, CI 스켈레톤 |
| **P1 · 지리 코어** | 지도 엔진 + 공공데이터 | place/report/user 엔티티, **공공데이터 idempotent ingestion + 지오코딩**, 반경/kNN/bounds API, Swagger, Testcontainers |
| **P2 · UGC + 인증** | 로그인·제보·후기 | 카카오/구글 OAuth+JWT, 제보(휘발)+후기(영구) 2단, 사진 presign, 신뢰도, 신고/검수 큐 |
| **P3 · 스코어·추천·AI** | survival_score + 요약 | 시공간 랭킹(SQL), 추천 시나리오, 날씨 API+Redis TTL 캐시, AI 한줄 요약, **공공데이터 주기 동기화**(EventBridge→ECS RunTask, 멱등 upsert 재실행 + 스냅샷 이탈 행 soft-delete + 실행 원장/retry 계보) |
| **P4 · 심화** | 성능·실시간·관측 | **seed 고정 k6 부하테스트 + JSON summary + EXPLAIN 인덱스 튜닝**, 실시간 이벤트(제보 급증 알림), 캐시 전략, 관측성(OTel/Grafana), **ADR 문서** |
| **P5 · 실사용** | 실서비스 | 동작구 UGC 필드테스트·피드백, PWA 무료 설치 배포(/install) |

## 11. 기술 하이라이트

**핵심:** PostGIS 반경/kNN 대용량 지리검색 · 공공데이터 idempotent ETL + 주소 지오코딩 · 2단 UGC(제보/후기) 시공간 스코어링.
**심화:** 부하테스트(k6)·EXPLAIN 인덱스 튜닝 · 실시간 이벤트(LISTEN/NOTIFY→SSE) · 캐시 전략 · 신뢰도/모더레이션 · ADR 기반 의사결정 기록.
**제품:** 소셜 로그인(OAuth)·후기 커뮤니티·PWA 지도 프론트.

> ⚠️ 법률 자문 아님. 침수/안전 표현, 후기 명예훼손, 위치 데이터는 서비스 확장 전 표현·정책 검토 권장.
