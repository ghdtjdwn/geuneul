# ADR-0027. 그늘 경유 경로 — 쿨링쉼터/실내 경유지 1곳(F3 화장실 경로와 대칭, C4)

- 상태: 승인(구현 반영, 2026-07-11)
- 관련: ADR-0019(경로 MVP·경유지 detour-min), ADR-0021(도로 폴리라인 Kakao navi 키 재사용), ADR-0024(그늘/비 corridor 오버레이 N8),
  `RouteService.shadeRoute`/`viaRoute`, `PlaceRepository.findBestWaypointByCategories`, `RouteController` GET /routes/shade,
  `RouteResponse.RouteStop.category`·`RouteWaypointView.getCategory`, SPEC.md §0-2(자체 라우팅 지양)·§3/§10(루트 3종)
- 후속: BACKLOG C4

## 문제(Context)

§3/§10은 루트 3종(비/그늘/화장실)을 그린다. F3(화장실 경로)와 N8(그늘/비 corridor 오버레이=길 근처 피할 곳)은 완료됐지만,
**'그늘 경로'가 '경로'로는 없었다** — N8은 기존 경로에 피난처를 표시만 하는 peripheral 오버레이일 뿐, "실제로 쉼터를 통과하는 길"이
아니었다. F3의 "화장실 들러 가기"에 대칭으로 "그늘 경유(쉼터에서 쉬어가기)"가 비어 있었다.

## 결정(Decision)

**F3 화장실 경로 인프라를 100% 재사용해, 현재위치→도착 사이 우회 최소 쿨링쉼터/실내 1곳을 경유지로 끼운 '그늘 경유 경로'를 대칭으로 채운다.
자체 가중 라우팅은 하지 않는다(§0-2) — 경유지 1곳만 PostGIS corridor로 고르고 폴리라인은 기존 DirectionsProvider가 그린다.**

- `PlaceRepository.findBestToiletWaypoint`의 고정 `category='TOILET'`를 `category = ANY(string_to_array(:categories,','))`로
  일반화한 `findBestWaypointByCategories` 추가(N8 findShadeAlongCorridor와 동일한 인젝션 안전 CSV 패턴). `RouteWaypointView`에
  `getCategory()` 추가(미니맵 아이콘 구분용, p.category 프로젝션).
- `RouteService`: `toiletRoute`를 공통 `viaRoute(from,to,categoriesCsv)`로 추출 → `toiletRoute`=TOILET 위임,
  `shadeRoute`=SHADE_CATEGORIES(COOLING_SHELTER·LIBRARY·UNDERGROUND) 신설. corridor 선택·폴리라인·shadeSpots 오버레이 공통.
- `RouteController` GET /routes/shade(/routes/toilet 미러·공개·requireKorea). `RouteResponse.RouteStop.category`(경유지만) 노출.
- 프론트: `fetchRoute(params, via)` 일반화, `/api/routes/shade` 1줄 미러, PlaceDetailOverlay "그늘 경유 경로" 버튼(setRoute 재사용),
  RouteMiniMapLive 경유지 마커를 하드코딩 'toilet'→waypoint.category 아이콘, types/route.ts waypoint category.

## 근거(Why)

- **왜 경유지 1곳(F3 대칭)인가 — 자체 가중 라우팅 금지(§0-2)**: 우리 간판은 지리검색이지 라우팅 엔진이 아니다. "우회 최소 경유지"를
  PostGIS corridor 쿼리(간판)로 고르고 폴리라인은 기존 DirectionsProvider가 그린다 → F3와 완전 대칭. 제품 요구사항의 "그늘 많은 경로
  재랭크 토글"안은 대안경로 다중 추출 + Kakao 대안경로 카운팅(라우팅 creep·TS-026 재검증)이라 **범위 제외**.
- **왜 SHADE_CATEGORIES(쉼터·도서관·지하상가)인가**: 셋 다 실내/지붕 피난처라 폭염·비를 동시에 해결(N8과 동일 집합 재사용).
  경유지도 이 중 우회 최소 1곳을 골라 "가는 길에 쉼터에서 쉬어가기"를 실현한다.
- **왜 N8 오버레이를 유지하며 경로를 추가하나**: N8("길 근처 피할 곳")과 C4("실제로 통과하는 길")는 다른 정보다. 경유지 경로 +
  주변 피난처 오버레이를 함께 실어 "그늘 경유(+피할 곳 N)"로 한 경험에 통합한다. UX 카피로 중복 인상을 분리한다.
- **왜 waypoint category를 DTO에 노출하나**: additive지만 안 하면 경유 쉼터가 미니맵에서 화장실 아이콘으로 뜬다(F3 하드코딩).
  프론트가 아이콘을 구분하려면 경유지 카테고리가 필요하다.
- **왜 마이그레이션·외부 API 0인가**: V18 유지·V3 GIST 재사용(공간쿼리), Kakao directions 재사용(ADR-0021, TS-026 이미 충족).

## 결과(Consequences)

- 마이그레이션·외부 API 0. `findBestToiletWaypoint`는 `findBestWaypointByCategories`로 대체(toiletRoute도 이를 CSV="TOILET"로
  호출) — 단일 쿼리가 category를 프로젝션해 RouteWaypointView.getCategory 계약을 채운다.
- 경유 쉼터가 corridor 안에 없으면 기존 graceful 폴백(직선/도로 + 오버레이만). GET /routes/shade는 /routes/toilet과 동일 스키마.
- 확장점: viaRoute(categoriesCsv)가 일반화됐으므로 향후 다른 경유 시나리오(예: 음수대 경유)도 카테고리 CSV만으로 추가 가능.
