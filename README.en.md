# Geuneul — Summer Survival Map

[한국어](./README.md)

> A map service for finding nearby shelters and essential amenities during heat waves or rain. It searches more than 150,000 public-data locations with PostGIS and shows their current condition through a report-aware `survival_score`.

<p>
  <a href="https://geuneul.vercel.app"><img src="https://img.shields.io/badge/Open_on_the_web-Geuneul-163c2f?logo=vercel&logoColor=white" alt="Open Geuneul on the web" /></a>
  <a href="https://geuneul.vercel.app/install"><img src="https://img.shields.io/badge/Install-PWA-17957e?logo=pwa&logoColor=white" alt="PWA installation guide" /></a>
  <a href="https://geuneul.vercel.app/geuneul.apk"><img src="https://img.shields.io/badge/Android_APK-Download-3DDC84?logo=android&logoColor=white" alt="Download Android APK" /></a>
</p>

[![API](https://img.shields.io/badge/API-live_health-17957e)](https://d2pedv974beobb.cloudfront.net/actuator/health)

[![Public Data](https://img.shields.io/badge/Public_data-150k%2B_POI-2f9e44)](#data-etl)
[![Radius p95](https://img.shields.io/badge/Radius_p95-1.35s_local_300k-17957e)](./docs/adr/0030-ingest-operational-ledger-deterministic-load.md)
[![Coverage](https://img.shields.io/badge/JaCoCo-87.22%25-17957e)](#technology)
[![ADR](https://img.shields.io/badge/ADR-32-informational)](./docs/adr/README.md)
[![License: MIT](https://img.shields.io/badge/License-MIT-lightgrey.svg)](./LICENSE)

[![CI](https://github.com/ghdtjdwn/geuneul/actions/workflows/ci.yml/badge.svg)](https://github.com/ghdtjdwn/geuneul/actions/workflows/ci.yml)
[![Frontend CI](https://github.com/ghdtjdwn/geuneul/actions/workflows/frontend-ci.yml/badge.svg)](https://github.com/ghdtjdwn/geuneul/actions/workflows/frontend-ci.yml)
[![Deploy (OCI ARM64)](https://github.com/ghdtjdwn/geuneul/actions/workflows/deploy.yml/badge.svg)](https://github.com/ghdtjdwn/geuneul/actions/workflows/deploy.yml)

> Production status (2026-09-03): the Vercel frontend/BFF remains in place, while the AWS backend and operating data have been migrated to OCI Ampere A1. The [AWS-to-OCI migration record](./docs/OCI-MIGRATION.md) documents the source and target specifications, PITR recovery, integrity checks, and cutover.

## Screenshots

### Map search and place discovery

<a href="https://geuneul.vercel.app"><img src="./docs/media/demo-desktop-3pane.png" alt="Desktop web: three-pane layout with search, nearby places, and map" /></a>

Search for nearby shelters, public toilets, and cafés while comparing the list and map markers.

### Shade-aware routes and place details

<a href="https://geuneul.vercel.app"><img src="./docs/media/demo-shade-route.png" alt="Desktop web: place detail and shade-aware route" /></a>

Check a place's condition, amenities, and recent reports, then find a route with a cooling-shelter or public-toilet waypoint.

### Mobile PWA

<p align="center">
  <a href="https://geuneul.vercel.app"><img src="https://github.com/user-attachments/assets/d437d24f-d1f0-4250-a3bf-0762ae36b612" alt="Mobile map: search and nearby-place bottom sheet" width="260" /></a>
</p>

On mobile, the map and bottom sheet support nearby discovery, while scenario recommendations help users choose a place quickly. The service works in a browser and can also be installed as a PWA or Android app. More screens, including scenario recommendations, are in the [architecture document](./docs/architecture.md#데모).

---

## Design highlights

- Spatial queries stay in the database. Radius search uses `ST_DWithin`, nearest-neighbor search uses kNN `<->`, and viewport search uses GiST indexes. Low-volume production k6 tuning reduced radius-search p95 from 2.68s to about 1.4s ([ADR-0012](./docs/adr/0012-k6-load-explain-index-tuning.md)). The current code was reverified at 1.35s p95 with no failed requests against a fingerprinted local 300,000-place snapshot; the environments differ, so no direct improvement ratio is claimed ([ADR-0030](./docs/adr/0030-ingest-operational-ledger-deterministic-load.md)).
- Idempotent ETL and geocoding use the `source + source_external_id` natural key, so a batch can be run again without duplicates. The service stores 60,297 cooling shelters, 52,334 public toilets, 3,551 libraries, and commercial café/study-space data. Missing WGS84 coordinates are completed with Kakao geocoding and cached to avoid repeated requests. The V20 run ledger tracks retry lineage and freshness without source payloads, while partial API responses and remote CSV digest mismatches fail before database mutation ([ADR-0030](./docs/adr/0030-ingest-operational-ledger-deterministic-load.md)).
- Real-time, geo-temporal UGC scoring combines expiring reports and durable reviews with trust weighting in `survival_score`. Report surges flow from PostgreSQL `LISTEN/NOTIFY` through multi-instance fan-out to SSE. Saved-place notifications use `INSERT … RETURNING` to send exactly once ([ADR-0016](./docs/adr/0016-realtime-report-surge-listen-notify-sse.md), [ADR-0026](./docs/adr/0026-bookmark-status-change-notification.md)).
- Server-validated security boundaries use conditional S3 writes and one-time claims before UGC can reference a photo. Bounded cleanup handles expired unused objects and photos detached from reviews only after rechecking current references. Login and logout serialize on the same user row so database token-version revocation cannot be overwritten, while Redis shares rate limits across ECS instances ([ADR-0031](./docs/adr/0031-security-boundaries-session-upload-rate-limit.md)).

Reviews and photo claims commit or roll back in one transaction. Claimless photos saved before V21 can be removed from existing reviews but remain outside automatic cleanup. PostgreSQL locks, cache generations, and database time provide the consistency boundaries for concurrent first OAuth login, late stale readers, and cleanup clock skew.
Report security issues through [GitHub private vulnerability reporting](./.github/SECURITY.md), not a public issue.

> Stack: Spring Boot 4 · Java 21 · PostgreSQL + PostGIS · Redis · AWS ECS Fargate (source) · OCI Ampere A1 (target) · Terraform · Next.js PWA

## Using the service

- Web: the same URL supports desktop and mobile browsers. Desktop uses a map-and-list layout; mobile centers on the map and bottom sheet.
- Installation: the application is a PWA using a service worker and Web App Manifest. Android supports browser installation and a [signed APK](https://geuneul.vercel.app/geuneul.apk); iOS supports Add to Home Screen.
- Implementation: the web and installed app share the same Next.js client and BFF. Only responsive UI, service worker behavior, and the TWA package vary by environment.

## Architecture

![Architecture diagram](./docs/media/architecture.svg)

Browser application APIs use only the same-origin `/api/*` BFF. Photo bytes are the sole exception: a short-lived signed PUT goes through a fixed Object Storage gateway, while spatial search and geo-temporal aggregation remain in GiST-backed SQL ([ADR-0004](./docs/adr/0004-frontend-same-origin-proxy.md), [ADR-0032](./docs/adr/0032-oci-arm64-self-hosted-migration.md)).

For runtime, ETL, CI/CD diagrams, and more screenshots, see [docs/architecture.md](./docs/architecture.md). The existing AWS deployment is documented in [DEPLOY.md](./DEPLOY.md); the OCI migration is in [docs/OCI-MIGRATION.md](./docs/OCI-MIGRATION.md).

## `survival_score`

`survival_score` expresses the current condition of a place as a score from 0 to 100 and one of three marker states: green (good), yellow (fair), or gray (insufficient information).

```
survival_score = 0.25·distance + 0.20·comfort + 0.20·freshness − 0.15·risk   (+ open_now when data is available)
freshness: 0–1h=1.0 | 1–3h=0.8 | today=0.6 | this week=0.3 | older=0.1
```

- The SQL view `place_report_signals` performs geo-temporal aggregation. A pure Java function assembles weights and marker state, keeping heavy aggregation in the database and policy tuning testable ([ADR-0007](./docs/adr/0007-survival-score-sql-signals-java-compose.md)).
- Reports are trust-weighted and expire by type. Reviews remain as durable reputation data and are scored separately.
- Missing components such as `open_now` are not fabricated; weights are renormalized until the source data becomes available.
- Scenario recommendations reuse the same function with scenario-specific weights ([ADR-0008](./docs/adr/0008-recommendations-scenario-weighted-ranking.md)).

## Data and ETL

Public standard datasets are loaded idempotently: repeating the same source performs an upsert on `source + source_external_id`, and records absent from a new snapshot are soft-deactivated. Since public-toilet data stopped providing WGS84 coordinates after February 2025, the ingestion flow converts addresses to coordinates with the Kakao Local API and saves results for idempotency and rate-limit safety. See [the ETL flow](./docs/architecture.md#데이터--etl).

| Data | Refresh policy |
|---|---|
| Libraries | Automatic. EventBridge runs a full API ingestion and soft-delete sync on the 2nd of each month at 04:00 KST. |
| Weather | Automatic on demand. Data is refreshed after the 30-minute Redis TTL expires. |
| User reports | Immediate on submission; surges are delivered by SSE. |
| Cooling shelters and public toilets | Manual. Publish a new CSV snapshot, then run the ECS ingestion task. |
| Cafés and study cafés | Manual after confirming source availability and collection scope. |

## Quick start

```bash
# 1) Infrastructure — PostGIS + Redis
docker compose up -d

# 2) Backend — API documentation is enabled explicitly for local development only
cd backend && SPRINGDOC_ENABLED=true ./gradlew bootRun

# 3) Frontend — http://localhost:3000
# Without a Kakao JavaScript key, the map is a placeholder but API data still works.
cd frontend && pnpm install && pnpm dev
```

Public-data ingestion is idempotent and safe to rerun:

```bash
cd backend
./gradlew bootRun --args='--ingest.source=cooling_shelter --ingest.file=/path/to/cooling-shelters.csv --ingest.charset=MS949'
```

## API examples

```bash
# Radius search — nearest first, distance in meters, and survival badge
GET /places?lat=37.4963&lng=126.9575&radius=1000

# Viewport markers — cooling shelters
GET /places?bounds=126.93,37.49,126.97,37.52&category=COOLING_SHELTER

# Nearest-neighbor search — works anywhere in Korea (example: Busan)
GET /places/nearest?lat=35.2133&lng=129.0157&category=COOLING_SHELTER&limit=3

# Scenario recommendation — rest30|restroom|rain|focus|longstay
GET /recommendations?lat=37.4963&lng=126.9575&scenario=restroom

# Expiring report. Sending lat/lng verifies a visit when within 100 m of the place.
POST /reports {"placeId":1,"reportType":"COOL","comment":"Strong air conditioning","lat":37.4963,"lng":126.9575}
GET  /places/1/reports

# Shade or restroom waypoint route
GET /routes/shade?fromLat=37.50&fromLng=126.95&toLat=37.51&toLng=126.96

# Report-surge snapshot and SSE stream
GET /alerts/surge?bounds=126.93,37.49,126.97,37.52
GET /alerts/stream
```

## Technology

| Area | Stack |
|---|---|
| Backend | Spring Boot 4 · Java 21 · PostgreSQL + PostGIS (Hibernate Spatial + JTS) · Flyway · Redis |
| Frontend | Next.js 16.2.12 App Router · TypeScript · Tailwind v4 · TanStack Query · Kakao Maps · Serwist PWA ([frontend README](./frontend/README.md)) |
| Infrastructure | Vercel frontend/BFF · OCI Ampere A1 ARM64 rootless Compose · OCI Object Storage · Terraform · retired AWS ECS/RDS source |
| Quality and operations | Testcontainers 2 with real PostGIS · JaCoCo 70% gate · DB/request-seeded k6 JSON summaries · gitleaks · CodeQL · Dependabot · local-only Swagger |

## Documentation

- Product scope, ERD, and API contract: [docs/SPEC.md](./docs/SPEC.md)
- Complete feature, implementation, and stack guide: [docs/FEATURES.md](./docs/FEATURES.md) (Korean)
- Architecture and screenshots: [docs/architecture.md](./docs/architecture.md)
- Technical decision records: [docs/adr/](./docs/adr) ([index](./docs/adr/README.md), 0001–0032)
- AWS deployment: [DEPLOY.md](./DEPLOY.md)
- AWS-to-OCI source/target specifications and data-preserving migration record: [docs/OCI-MIGRATION.md](./docs/OCI-MIGRATION.md) · [ADR-0032](./docs/adr/0032-oci-arm64-self-hosted-migration.md)
- Design and API reference: [docs/design-brief.md](./docs/design-brief.md)

## Delivered capabilities

- Geospatial search: radius, nearest-neighbor, and viewport queries backed by PostGIS and GiST.
- Public-data ingestion, idempotent upserts, soft-deactivation, and Kakao geocoding for missing coordinates.
- Weather-aware scoring, scenario recommendations, and provider-neutral AI summaries with graceful degradation.
- Reports, reviews, photos, social login, trust weighting, moderation, bookmarks, following, notifications, SSE, and Web Push.
- Shade and restroom waypoint routes, road polylines, popular-times heatmaps, and responsive desktop/mobile map experiences.
- PWA installation, Android TWA/APK distribution, iOS Add to Home Screen, CI checks, OIDC deployment, infrastructure as code, autoscaling, and observability.

## License

[MIT](./LICENSE) © 2026 ghdtjdwn
