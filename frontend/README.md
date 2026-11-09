# 그늘 프론트엔드

> **Live:** https://geuneul.vercel.app
> 루트 README에는 제품·백엔드·인프라 전체 개요를, 이 문서에는 Next.js 클라이언트의 실행과 구조만 남긴다.

Next.js 16 App Router 기반 PWA입니다. 브라우저는 같은 오리진의 `/api/*` Route Handler만 호출하고, 서버 측 BFF가 CloudFront를 거쳐 Spring Boot API에 연결합니다. 백엔드 주소와 인증 비밀은 브라우저 번들에 포함하지 않습니다.

## 제공 기능

- Kakao 지도, 현재 위치, 장소·지역 검색, 카테고리 필터, 모바일 바텀시트와 데스크톱 3분할 지도
- PostGIS 기반 주변 장소·시나리오 추천, 장소 상세, 화장실·그늘 경유 경로
- 제보·후기·사진 업로드·반응·신고·관리자 검수
- 카카오·구글 로그인, 프로필·팔로우·북마크·알림 센터
- SSE 급증 알림, 선택형 Web Push, 오프라인 셸·설치 화면·TWA APK 배포

## 구조

```text
app/
  (shell)/       지도, 급해요, 제보, 마이페이지와 오버레이 슬롯
  api/           동일 오리진 BFF(Route Handlers)
  install/       PWA·APK 설치 안내
  sw.ts          Serwist 서비스 워커
components/      map · place · urgent · notification · mypage · shell · ui
lib/             API 클라이언트, React Query, 위치·선택 상태, 지도·PWA·push 유틸리티
types/           API 경계 타입
```

지도 SDK는 브라우저 전용이므로 `MapCanvas`에서 SSR을 끕니다. 위치 권한은 이미 허용된 경우에만 자동 갱신하고, 그 외에는 최근 위치를 복원한 뒤 사용자가 현재 위치 버튼을 눌렀을 때 요청합니다.

## 환경 변수

`.env.local`에만 저장하며 커밋하지 않습니다.

```bash
# 서버 전용: CloudFront HTTPS 오리진
GEUNEUL_API_BASE=https://<cloudfront-domain>

# 브라우저 공개값: Kakao JavaScript SDK 도메인 허용목록으로 보호
NEXT_PUBLIC_KAKAO_MAP_JS_KEY=<kakao-javascript-key>

# 서버 전용: BFF → 백엔드 신뢰 헤더(선택)
GEUNEUL_PROXY_SECRET=<shared-secret>
```

Kakao 콘솔의 **JavaScript SDK 도메인**에는 `http://localhost:3000`과 `https://geuneul.vercel.app`을 등록해야 합니다. 키가 없으면 지도만 안내 화면으로 대체되고, 빌드 자체는 정상 동작합니다.

## 개발 및 검증

```bash
pnpm install
pnpm dev          # http://localhost:3000, webpack
pnpm typecheck
pnpm lint
pnpm build         # Serwist 서비스 워커까지 포함한 프로덕션 빌드
```

Serwist가 webpack 플러그인을 사용하므로 개발·빌드는 `--webpack`으로 고정돼 있습니다.

## 배포

`main` push는 Vercel Production 배포를 자동으로 시작합니다. GitHub Actions의 Frontend CI는 `typecheck → lint → build`를 별도로 검증합니다. Vercel에는 위 세 환경 변수를 Production 값으로 등록합니다.

인증, API 계약, AWS 배포와 성능 근거는 각각 [루트 README](../README.md), [아키텍처](../docs/architecture.md), [배포 문서](../DEPLOY.md)를 기준으로 합니다.
