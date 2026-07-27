# 아키텍처 의사결정 기록 (ADR)

주요 설계 결정을 "문제 → 결정 → 검토한 대안 → 결과 → 근거(2026 트렌드 확인)" 형식으로 남긴다.
각 문서는 그 결정이 왜 그렇게 내려졌는지를 코드/라이브 상태와 함께 설명한다.

| # | 결정 | 상태 |
|---|---|---|
| [0001](./0001-geometry-storage-geography-function-index.md) | 좌표는 `geometry(Point,4326)` 저장, 거리 연산은 `geography` 함수 인덱스(GiST) | 승인 |
| [0002](./0002-idempotent-ingestion-upsert.md) | 공공데이터 인제스천은 자연키 `ON CONFLICT` 배치 upsert(멱등) | 승인 |
| [0003](./0003-geocoding-pipeline.md) | 좌표 미제공 데이터는 geocode-before-insert + 저장 좌표 재사용 | 승인 |
| [0004](./0004-frontend-same-origin-proxy.md) | 프론트는 동일 오리진 서버 프록시(BFF)로만 백엔드 접근 | 승인 |
| [0005](./0005-cafe-features-as-summer-scenario.md) | 카공/카페 기능은 "여름 실내 시나리오"로 흡수(리뷰앱화 금지) | 부분 채택 |
| [0006](./0006-study-space-coverage-expansion.md) | 공부 가능 공간 데이터 커버리지 확장(survival 레이어) | 승인·구현 반영 |
| [0007](./0007-survival-score-sql-signals-java-compose.md) | `survival_score` — 시공간 신호는 SQL 뷰, 가중치 조립은 순수 함수 | 승인 |
| [0008](./0008-recommendations-scenario-weighted-ranking.md) | 추천(`/recommendations`) — survival_score에 시나리오 가중을 얹은 2단 랭킹 | 승인 |
| [0009](./0009-weather-comfort-additive-restore.md) | survival_score comfort — 기온(체감) 신호를 comfort_score에 additive 복원 | 승인 |
| [0010](./0010-ai-summary-openrouter-provider.md) | AI 한줄 요약 — 프로바이더 중립 OpenAI 호환 클라이언트(현재 Mistral, Anthropic 키 부재로 이탈), 상세 전용 additive | 승인 |
| [0011](./0011-scheduled-public-data-sync.md) | 공공데이터 주기 동기화 — EventBridge Scheduler(Universal Target) → ECS RunTask + Postgres advisory lock | 승인(라이브 · 월 1회 ENABLED) |
| [0012](./0012-k6-load-explain-index-tuning.md) | 간판 성능 실증 — k6 부하테스트 + EXPLAIN 인덱스 튜닝(V8 만료 제보 인덱스) | 승인 |
| [0013](./0013-ecs-service-autoscaling.md) | ECS Service Auto Scaling — CPU target tracking, min=1/max=3, 기본 ENABLED | 승인(apply 완료·라이브) |
| [0014](./0014-observability-otel-micrometer-grafana.md) | 관측성 — Micrometer/Prometheus(pull)+Boot 4 OTel 스타터(push 트레이싱)+로컬 Grafana/Tempo, prometheus는 프로덕션 기본 미노출 | 승인 |
| [0015](./0015-cloudfront-default-domain-https.md) | ALB 무료 HTTPS — CloudFront 기본 도메인(*.cloudfront.net), 커스텀 도메인·ACM 없이 | 승인(apply 완료·라이브) |
| [0016](./0016-realtime-report-surge-listen-notify-sse.md) | 실시간 제보 급증 알림 — Postgres LISTEN/NOTIFY(멀티 인스턴스 팬아웃) → SSE, Kafka/Redis Streams 배제 | 승인 |
| [0017](./0017-place-feature-comfort-signal.md) | 시설(place_features) → survival_score comfort 통합 — 별도 SQL 뷰 집계 + Java 단조 상승, 등급은 실시간 신호로만 게이트 | 승인 |
| [0018](./0018-notifications-in-app-center-surge-reuse.md) | 알림 — 인앱 센터 MVP + 급증 LISTEN/NOTIFY 재사용 평가, dedup_key로 멀티 인스턴스·cooldown 1회 발송, Web Push는 stretch | 승인 |
| [0019](./0019-routes-toilet-waypoint-external-directions.md) | 루트 — 화장실 포함 경로: 경유지 선택은 우리 PostGIS(detour 최소), 도로 폴리라인은 외부 directions API(전략 추상화, 직선 MVP·Kakao 후속) | 승인(MVP) |
| [0020](./0020-heat-escape-notification-on-demand-weather.md) | HEAT_ESCAPE 알림 — 온디맨드(pull) 날씨 평가, 새 스케줄러 없이 | 승인·라이브 |
| [0021](./0021-road-polyline-kakao-navi-key-reuse.md) | 화장실 경로 도로 폴리라인 — 카카오내비 길찾기, 기존 REST 키 재사용 | 승인·라이브 |
| [0022](./0022-web-push-zerodep-vapid-feature-gated.md) | Web Push 전송 — zerodep(BouncyCastle 없이) + VAPID, 기능 플래그 게이팅 | 승인·라이브 |
| [0023](./0023-commons-safe-follow.md) | 커먼스 세이프 팔로우 — 작성자 공개 프로필 + "수만 공개, 목록은 비공개" 팔로우 | 승인·라이브 |
| [0024](./0024-shade-rain-route-corridor-overlay.md) | 그늘/비 경로 — 경로 corridor 주변 그늘·실내 피난처 오버레이(F4) | 승인·라이브 |
| [0025](./0025-scale-prep-load-based-tuning.md) | 대규모 대비 — 부하 기반 튜닝(task_cpu 0.5vCPU · EXPLAIN 인덱스 · 오토스케일링/풀 점검) | 승인·라이브 |
| [0026](./0026-bookmark-status-change-notification.md) | 관심 장소 상태 변화 알림(C3) — 북마크 장소 단건 침수·미끄럼, V9 이벤트 재사용·제보시각 dedup 버킷·RETURNING 정확히 1회 푸시 | 승인 |
| [0027](./0027-shade-waypoint-route.md) | 그늘 경유 경로(C4) — 쿨링쉼터/실내 경유지 1곳(F3 화장실 경로와 대칭), 자체 가중 라우팅 없이 PostGIS corridor로 경유지 선택 | 승인 |
| [0028](./0028-alb-cloudfront-origin-lockdown.md) | ALB를 CloudFront origin-facing prefix list로 격리 — 인터넷 직접 HTTP 노출 제거, BFF도 CloudFront(HTTPS) 경유로 전환 | 승인·라이브 |
| [0029](./0029-rds-encryption-backup-snapshot-restore.md) | RDS 저장 암호화 + 자동 백업 — 스냅샷을 KMS 암호화 복사 후 복원(무손실), 프리티어 retention 1일 | 승인·라이브 |
| [0030](./0030-ingest-operational-ledger-deterministic-load.md) | 인제스천 운영 원장 + 결정적 공간 부하 입력 — retry/dead-letter/freshness와 seed 기반 JSON summary | 승인·운영 적용 완료(로컬 실 PostGIS·30만 부하 검증) |
| [0031](./0031-security-boundaries-session-upload-rate-limit.md) | 업로드 claim·서버 세션 폐기·Redis 분산 레이트리밋·운영 Swagger 기본 차단 | 승인·구현 완료(운영 미적용) |
