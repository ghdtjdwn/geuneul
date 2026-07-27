// 클라이언트 fetch 계층. 브라우저는 항상 동일 오리진 /api/* 프록시만 호출한다(ALB 직접 호출 금지).
import type { MyComment, MyReaction, MyReview } from "@/types/activity";
import type { SurgeInfo } from "@/types/alert";
import type { AdminFlagList, Flag, FlagCreatePayload, FlagStatus } from "@/types/flag";
import type { Bookmark, BookmarkToggle } from "@/types/bookmark";
import type { ReactionState, ReactionTarget, ReactionType, ReviewComment } from "@/types/community";
import type { Following, FollowResult, UserProfile } from "@/types/follow";
import type { NotificationCenter, NotificationRule, NotificationRuleType } from "@/types/notification";
import type { MapBounds, Place, Report, ReportCreatePayload, Scenario } from "@/types/place";
import type { PopularTimesSlot } from "@/types/popular";
import type { RouteResult } from "@/types/route";
import type { PlaceSearchResult } from "@/types/search";
import type { PhotoPresignResult, PhotoPurpose } from "@/types/photo";
import type { Review, ReviewCreatePayload, ReviewListResponse } from "@/types/review";
import type { User } from "@/types/user";
import { boundsParam } from "./geo";

export class ApiError extends Error {
  constructor(
    public status: number,
    message: string,
  ) {
    super(message);
    this.name = "ApiError";
  }
}

// 응답 에러 → ApiError. 백엔드는 Spring 기본 JSON({timestamp,status,error,path,message}) 또는
// 프록시 에러({error,message}) — message를 우선 살리고 없으면 error, 그래도 없으면 statusText.
async function toApiError(res: Response): Promise<ApiError> {
  let msg = res.statusText;
  try {
    const body = await res.json();
    msg = body?.message ?? body?.error ?? msg;
  } catch {
    /* 바디가 JSON이 아님 */
  }
  return new ApiError(res.status, msg);
}

// 동일 오리진 프록시가 10s 상한을 갖지만, 프록시 라우트 자체가 멈추는 경우를 대비해 클라이언트도 상한을 둔다.
const CLIENT_TIMEOUT_MS = 12_000;

async function getJson<T>(url: string): Promise<T> {
  const res = await fetch(url, { signal: AbortSignal.timeout(CLIENT_TIMEOUT_MS) });
  if (!res.ok) throw await toApiError(res);
  return res.json() as Promise<T>;
}

// 홈 지도 마커 = 뷰포트 bounds 조회(전 카테고리, 클라에서 칩 필터).
export function fetchByBounds(bounds: MapBounds): Promise<Place[]> {
  const qs = new URLSearchParams({ bounds: boundsParam(bounds), limit: "100" });
  return getJson<Place[]>(`/api/places?${qs}`);
}

// 시트 리스트 = 현재 위치 반경 조회(distanceM 포함).
export function fetchByRadius(params: { lat: number; lng: number; radius: number }): Promise<Place[]> {
  const qs = new URLSearchParams({
    lat: String(params.lat),
    lng: String(params.lng),
    radius: String(params.radius),
    limit: "100",
  });
  return getJson<Place[]>(`/api/places?${qs}`);
}

// 단건 상세.
export function fetchPlace(id: number): Promise<Place> {
  return getJson<Place>(`/api/places/${id}`);
}

// 지정 장소 검색(N5) — 카카오 키워드(백엔드 BFF). coords가 있으면 그 좌표 기준 거리순(내 주변 우선).
export function searchPlaces(
  query: string,
  coords?: { lat: number; lng: number } | null,
): Promise<PlaceSearchResult[]> {
  const qs = new URLSearchParams({ query });
  if (coords) {
    qs.set("lat", String(coords.lat));
    qs.set("lng", String(coords.lng));
  }
  return getJson<PlaceSearchResult[]>(`/api/places/search?${qs}`);
}

// 급해요 = 시나리오별 nearest 팬아웃(서버 프록시가 병합·정렬).
export function fetchUrgent(params: { scenario: Scenario; lat: number; lng: number }): Promise<Place[]> {
  const qs = new URLSearchParams({
    scenario: params.scenario,
    lat: String(params.lat),
    lng: String(params.lng),
  });
  return getJson<Place[]>(`/api/urgent?${qs}`);
}

// 제보 기본 장소 = 현재 위치 최근접(카테고리 무관).
export function fetchNearestAny(params: { lat: number; lng: number; limit?: number }): Promise<Place[]> {
  const qs = new URLSearchParams({
    lat: String(params.lat),
    lng: String(params.lng),
    limit: String(params.limit ?? 1),
  });
  return getJson<Place[]>(`/api/places/nearest?${qs}`);
}

// 장소의 최근 유효 제보(미만료, 최신순 top20).
export function fetchPlaceReports(placeId: number): Promise<Report[]> {
  return getJson<Report[]>(`/api/places/${placeId}/reports`);
}

// 장소의 시간대별 혼잡 파생(요일×시간, 만료 포함 이력 채굴). 백엔드 Redis 1h 캐시.
export function fetchPopularTimes(placeId: number): Promise<PopularTimesSlot[]> {
  return getJson<PopularTimesSlot[]>(`/api/places/${placeId}/popular-times`);
}

// 경유지 경로 — 출발→경유지→도착. via=toilet(화장실, F3) | shade(쿨링쉼터/실내, C4). 경유지 선택은 백엔드 PostGIS(우회 최소).
export function fetchRoute(
  params: { fromLat: number; fromLng: number; toLat: number; toLng: number },
  via: "toilet" | "shade",
): Promise<RouteResult> {
  const qs = new URLSearchParams({
    fromLat: String(params.fromLat),
    fromLng: String(params.fromLng),
    toLat: String(params.toLat),
    toLng: String(params.toLng),
  });
  return getJson<RouteResult>(`/api/routes/${via}?${qs}`);
}

// 뷰포트 내 제보 급증 스냅샷(폴백/초기 상태). SSE(/api/alerts/stream)가 공백을 실시간으로 메운다.
export function fetchSurge(bounds: MapBounds): Promise<SurgeInfo[]> {
  const qs = new URLSearchParams({ bounds: boundsParam(bounds), limit: "50" });
  return getJson<SurgeInfo[]>(`/api/alerts/surge?${qs}`);
}

// 내 프로필 — 미로그인(401)이면 null. 그 외 오류는 throw(호출부에서 구분).
export async function fetchMe(): Promise<User | null> {
  const res = await fetch(`/api/me`, { signal: AbortSignal.timeout(CLIENT_TIMEOUT_MS) });
  if (res.status === 401) return null;
  if (!res.ok) throw await toApiError(res);
  return res.json() as Promise<User>;
}

// 내 관심 장소 목록(로그인 필요). 미로그인(401)이면 빈 배열로 처리(호출부 enabled 게이팅과 함께).
export function fetchMyBookmarks(): Promise<Bookmark[]> {
  return getJson<Bookmark[]>(`/api/me/bookmarks`);
}

// 내 글 관리(N6) — 내 후기/댓글/유용해요(로그인 필요, 최신순). 미로그인은 enabled 게이팅으로 회피.
export function fetchMyReviews(): Promise<MyReview[]> {
  return getJson<MyReview[]>(`/api/me/reviews`);
}

export function fetchMyComments(): Promise<MyComment[]> {
  return getJson<MyComment[]>(`/api/me/comments`);
}

export function fetchMyReactions(): Promise<MyReaction[]> {
  return getJson<MyReaction[]>(`/api/me/reactions`);
}

// 작성자 공개 프로필(N7) — 공개(누구나). 로그인 시 following 채워짐(BFF가 쿠키를 옵션 전달).
export function fetchUserProfile(id: number): Promise<UserProfile> {
  return getJson<UserProfile>(`/api/users/${id}`);
}

// 팔로우/언팔로우(로그인 필요, 멱등) — 응답 {following, followerCount}로 버튼·카운트 즉시 반영.
export async function followUser(id: number): Promise<FollowResult> {
  const res = await fetch(`/api/users/${id}/follow`, { method: "POST", signal: AbortSignal.timeout(CLIENT_TIMEOUT_MS) });
  if (!res.ok) throw await toApiError(res);
  return res.json() as Promise<FollowResult>;
}

export async function unfollowUser(id: number): Promise<FollowResult> {
  const res = await fetch(`/api/users/${id}/follow`, { method: "DELETE", signal: AbortSignal.timeout(CLIENT_TIMEOUT_MS) });
  if (!res.ok) throw await toApiError(res);
  return res.json() as Promise<FollowResult>;
}

// 내 팔로잉 목록("나만" 봄, 로그인 필요).
export function fetchMyFollowing(): Promise<Following[]> {
  return getJson<Following[]>(`/api/me/following`);
}

// 관심 장소 저장(로그인 필요, 멱등 upsert).
export async function addBookmark(placeId: number, memo?: string): Promise<BookmarkToggle> {
  const res = await fetch(`/api/bookmarks`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ placeId, memo }),
    signal: AbortSignal.timeout(CLIENT_TIMEOUT_MS),
  });
  if (!res.ok) throw await toApiError(res);
  return res.json() as Promise<BookmarkToggle>;
}

// 관심 장소 해제(로그인 필요, 멱등).
export async function removeBookmark(placeId: number): Promise<BookmarkToggle> {
  const res = await fetch(`/api/bookmarks/${placeId}`, {
    method: "DELETE",
    signal: AbortSignal.timeout(CLIENT_TIMEOUT_MS),
  });
  if (!res.ok) throw await toApiError(res);
  return res.json() as Promise<BookmarkToggle>;
}

// 알림 센터(발송 이력 + 안읽음 수, 로그인 필요).
export function fetchNotifications(): Promise<NotificationCenter> {
  return getJson<NotificationCenter>(`/api/notifications`);
}

// 알림 전체 읽음.
export async function markNotificationsRead(): Promise<void> {
  const res = await fetch(`/api/notifications/read`, { method: "POST", signal: AbortSignal.timeout(CLIENT_TIMEOUT_MS) });
  if (!res.ok) throw await toApiError(res);
}

// 내 알림 규칙 목록.
export function fetchNotificationRules(): Promise<NotificationRule[]> {
  return getJson<NotificationRule[]>(`/api/notifications/rules`);
}

// 알림 규칙 생성 — SURGE_NEARBY는 lat/lng/radiusM 필요, BOOKMARK_SURGE는 불필요.
export async function createNotificationRule(payload: {
  type: NotificationRuleType;
  lat?: number;
  lng?: number;
  radiusM?: number;
}): Promise<NotificationRule> {
  const res = await fetch(`/api/notifications/rules`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify(payload),
    signal: AbortSignal.timeout(CLIENT_TIMEOUT_MS),
  });
  if (!res.ok) throw await toApiError(res);
  return res.json() as Promise<NotificationRule>;
}

// 알림 규칙 삭제.
export async function deleteNotificationRule(id: number): Promise<void> {
  const res = await fetch(`/api/notifications/rules/${id}`, {
    method: "DELETE",
    signal: AbortSignal.timeout(CLIENT_TIMEOUT_MS),
  });
  if (!res.ok) throw await toApiError(res);
}

// 로그아웃 — 서버 token_version 폐기 성공 후 세션 쿠키 삭제.
export async function logout(): Promise<void> {
  const res = await fetch(`/api/auth/logout`, { method: "POST", signal: AbortSignal.timeout(CLIENT_TIMEOUT_MS) });
  if (!res.ok) throw await toApiError(res);
}

// 신고 접수(C1) — 로그인 필요. 201=접수, 409=이미 신고한 항목, 404=대상 없음, 401=비로그인.
// 호출부(FlagButton)가 ApiError.status로 안내 문구를 분기한다(§6 중립 톤).
export async function createFlag(payload: FlagCreatePayload): Promise<Flag> {
  const res = await fetch(`/api/flags`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify(payload),
    signal: AbortSignal.timeout(CLIENT_TIMEOUT_MS),
  });
  if (!res.ok) throw await toApiError(res);
  return res.json() as Promise<Flag>;
}

// 관리자 검수 큐(C1) — ADMIN 전용. 비관리자(403)·비로그인(401)은 ApiError로 던져 호출부가 우아하게 처리한다
// (admin 화면은 useMe role로 선게이팅하지만, 방어적으로 403/401도 구분해 안내). status 기본 PENDING.
export function fetchAdminFlags(status: FlagStatus = "PENDING", page = 0): Promise<AdminFlagList> {
  const qs = new URLSearchParams({ status, page: String(page), size: "20" });
  return getJson<AdminFlagList>(`/api/admin/flags?${qs}`);
}

// 신고 처리(C1) — ADMIN 전용. RESOLVED면 대상 콘텐츠 숨김, DISMISSED면 무처리 종결. 이미 처리건은 409.
export async function resolveFlag(id: number, status: "RESOLVED" | "DISMISSED"): Promise<Flag> {
  const res = await fetch(`/api/admin/flags/${id}/resolve`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ status }),
    signal: AbortSignal.timeout(CLIENT_TIMEOUT_MS),
  });
  if (!res.ok) throw await toApiError(res);
  return res.json() as Promise<Flag>;
}

// 휘발성 제보 생성(익명 허용). 429 = 레이트리밋 — 메시지로 안내.
export async function createReport(payload: ReportCreatePayload): Promise<Report> {
  const res = await fetch(`/api/reports`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify(payload),
    signal: AbortSignal.timeout(CLIENT_TIMEOUT_MS),
  });
  if (!res.ok) throw await toApiError(res);
  return res.json() as Promise<Report>;
}

// 장소의 영구 후기 목록(공개, 최신순 페이지네이션). survival_score(휘발성 제보)와 분리된 평판.
export function fetchPlaceReviews(placeId: number, page = 0): Promise<ReviewListResponse> {
  const qs = new URLSearchParams({ page: String(page), size: "20" });
  return getJson<ReviewListResponse>(`/api/places/${placeId}/reviews?${qs}`);
}

// 후기 작성/수정(로그인 필요, 장소당 1건 upsert). 401 = 미로그인 — 호출부가 안내 메시지로 처리.
export async function createReview(payload: ReviewCreatePayload): Promise<Review> {
  const res = await fetch(`/api/reviews`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify(payload),
    signal: AbortSignal.timeout(CLIENT_TIMEOUT_MS),
  });
  if (!res.ok) throw await toApiError(res);
  return res.json() as Promise<Review>;
}

// 후기 댓글 목록(공개, 오래된 순).
export function fetchReviewComments(reviewId: number): Promise<ReviewComment[]> {
  return getJson<ReviewComment[]>(`/api/reviews/${reviewId}/comments`);
}

// 후기 댓글 작성(로그인 필요). 401 = 미로그인 — 호출부가 안내.
export async function createReviewComment(reviewId: number, comment: string): Promise<ReviewComment> {
  const res = await fetch(`/api/reviews/${reviewId}/comments`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ comment }),
    signal: AbortSignal.timeout(CLIENT_TIMEOUT_MS),
  });
  if (!res.ok) throw await toApiError(res);
  return res.json() as Promise<ReviewComment>;
}

// 리액션 토글(유용해요). add=true면 POST(추가·멱등), false면 DELETE(취소). 응답은 {reacted,count}.
export async function toggleReaction(params: {
  targetType: ReactionTarget;
  targetId: number;
  type?: ReactionType;
  add: boolean;
}): Promise<ReactionState> {
  const res = await fetch(`/api/reactions`, {
    method: params.add ? "POST" : "DELETE",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ targetType: params.targetType, targetId: params.targetId, type: params.type ?? "HELPFUL" }),
    signal: AbortSignal.timeout(CLIENT_TIMEOUT_MS),
  });
  if (!res.ok) throw await toApiError(res);
  return res.json() as Promise<ReactionState>;
}

// 사진 업로드 presign 발급 — S3로 직접 PUT할 URL을 받는다. purpose=review는 로그인 필요(401은 호출부가 처리).
export async function presignPhoto(params: {
  contentType: string;
  contentLength: number;
  purpose: PhotoPurpose;
}): Promise<PhotoPresignResult> {
  const res = await fetch(`/api/photos/presign`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify(params),
    signal: AbortSignal.timeout(CLIENT_TIMEOUT_MS),
  });
  if (!res.ok) throw await toApiError(res);
  return res.json() as Promise<PhotoPresignResult>;
}

// presign으로 받은 URL에 파일을 직접 PUT — 서명 시 실은 Content-Type/Content-Length와 정확히 일치해야
// S3가 받아준다(PhotoService 주석 참고). 이 요청은 동일 오리진 프록시를 거치지 않고 S3로 바로 나간다
// (presigned URL 자체가 인가 수단이라 BFF를 거칠 이유가 없다 — 오히려 대용량 바이너리가 Vercel 함수를 왕복하지 않아 유리).
export async function uploadPhotoToS3(presigned: PhotoPresignResult, file: File): Promise<void> {
  const res = await fetch(presigned.uploadUrl, {
    method: "PUT",
    // Must match PhotoService's signed conditional create header; an existing key returns S3 412 instead of overwrite.
    headers: { "content-type": file.type, "if-none-match": "*" },
    body: file,
  });
  if (!res.ok) throw new Error(`사진 업로드에 실패했어요 (${res.status})`);
}
