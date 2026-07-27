// 서버 전용. 브라우저는 동일 오리진 /api/* 만 호출하고, 이 프록시가 ALB 로 대신 요청한다.
// → ALB 가 http(TLS 없음)이고 CORS 미설정이어도 mixed-content/CORS 를 동시에 회피.
// GEUNEUL_API_BASE 는 서버 전용 env(NEXT_PUBLIC 아님)라 ALB 호스트가 클라이언트 번들에 노출되지 않는다.
import type { NextRequest } from "next/server";
import { NextResponse } from "next/server";
import { SESSION_COOKIE } from "@/lib/auth";

const RAW_BASE = process.env.GEUNEUL_API_BASE ?? "";
const BASE = RAW_BASE.replace(/\/$/, "");

const TIMEOUT_MS = 10_000;

async function backendFetch(path: string, search: string, request?: NextRequest): Promise<Response> {
  const url = `${BASE}${path}${search ? `?${search}` : ""}`;
  // 레이트리밋 대상 GET(검색·경로)은 원 클라이언트 신원을 보존해야 유저별 버킷으로 잡힌다 —
  // 안 보내면 백엔드 ProxyClientResolver가 ALB 최우측 XFF(=Vercel egress IP)로 키잉해 전 유저가 버킷을 공유한다.
  const identity: Record<string, string> = {};
  if (request) {
    const xff = request.headers.get("x-forwarded-for") ?? "";
    // Vercel documents x-vercel-forwarded-for as the non-overridable client-IP header when an upstream proxy exists.
    const clientIp = request.headers.get("x-vercel-forwarded-for")
      ?? request.headers.get("x-real-ip")
      ?? xff.split(",")[0].trim();
    const proxySecret = process.env.GEUNEUL_PROXY_SECRET ?? "";
    if (!proxySecret) throw new Error("GEUNEUL_PROXY_SECRET is required for rate-limited proxy routes");
    if (xff) identity["x-forwarded-for"] = xff;
    if (clientIp) identity["x-client-ip"] = clientIp;
    if (proxySecret) identity["x-proxy-auth"] = proxySecret;
  }
  return fetch(url, {
    headers: { accept: "application/json", ...identity },
    signal: AbortSignal.timeout(TIMEOUT_MS),
    // 백엔드 Redis 가 캐시를 담당하므로 프록시는 항상 신선하게 통과(체감 상태의 freshness 우선).
    cache: "no-store",
  });
}

// 쓰기 프록시: JSON body를 그대로 중계하고, 원 클라이언트 IP(X-Forwarded-For)를 보존해
// 백엔드 레이트리밋(클라이언트별)이 프록시 IP로 뭉치지 않게 한다.
export async function proxyPost(path: string, request: Request): Promise<NextResponse> {
  if (!BASE) {
    return NextResponse.json(
      { error: "config", message: "GEUNEUL_API_BASE is not configured on the server." },
      { status: 500 },
    );
  }
  try {
    const body = await request.text();
    // Vercel이 외부 프록시 뒤에서도 덮어쓰는 전용 헤더를 우선하고, 직접 배포 환경용 헤더로 폴백한다.
    const xff = request.headers.get("x-forwarded-for") ?? "";
    const clientIp = request.headers.get("x-vercel-forwarded-for")
      ?? request.headers.get("x-real-ip")
      ?? xff.split(",")[0].trim();
    // BFF↔백엔드 공유 시크릿(서버 전용). 없으면 신뢰할 client identity를 증명할 수 없으므로 fail-safe 중단한다.
    const proxySecret = process.env.GEUNEUL_PROXY_SECRET ?? "";
    if (!proxySecret) {
      return NextResponse.json({ error: "config", message: "GEUNEUL_PROXY_SECRET is not configured." }, { status: 500 });
    }
    const res = await fetch(`${BASE}${path}`, {
      method: "POST",
      headers: {
        "content-type": "application/json",
        accept: "application/json",
        ...(xff ? { "x-forwarded-for": xff } : {}),
        ...(clientIp ? { "x-client-ip": clientIp } : {}),
        ...(proxySecret ? { "x-proxy-auth": proxySecret } : {}),
      },
      body,
      signal: AbortSignal.timeout(TIMEOUT_MS),
      cache: "no-store",
    });
    const resBody = await res.text();
    // 빈 바디는 null로(204/205/304 null-body status에 "" 주면 Response 생성자가 TypeError→502 오변환, 푸시 test 실패 오탐).
    return new NextResponse(resBody || null, {
      status: res.status,
      headers: { "content-type": res.headers.get("content-type") ?? "application/json; charset=utf-8" },
    });
  } catch (err) {
    const message = err instanceof Error ? err.message : String(err);
    return NextResponse.json({ error: "upstream_unreachable", message }, { status: 502 });
  }
}

// 익명 허용 쓰기 프록시: 세션 쿠키나 Authorization 헤더가 있으면 백엔드에 전달하고, 없으면 익명 요청으로 통과한다.
// 제보처럼 "로그인 시 신뢰도/작성자 맥락은 살리되 비로그인 제출도 허용"하는 엔드포인트용.
export async function proxyOptionalAuthedPost(path: string, request: NextRequest): Promise<NextResponse> {
  if (!BASE) {
    return NextResponse.json(
      { error: "config", message: "GEUNEUL_API_BASE is not configured on the server." },
      { status: 500 },
    );
  }
  try {
    const body = await request.text();
    const xff = request.headers.get("x-forwarded-for") ?? "";
    const clientIp = request.headers.get("x-vercel-forwarded-for")
      ?? request.headers.get("x-real-ip")
      ?? xff.split(",")[0].trim();
    const proxySecret = process.env.GEUNEUL_PROXY_SECRET ?? "";
    if (!proxySecret) {
      return NextResponse.json({ error: "config", message: "GEUNEUL_PROXY_SECRET is not configured." }, { status: 500 });
    }
    const token = request.cookies.get(SESSION_COOKIE)?.value;
    const authorization = token ? `Bearer ${token}` : (request.headers.get("authorization") ?? "");
    const res = await fetch(`${BASE}${path}`, {
      method: "POST",
      headers: {
        "content-type": "application/json",
        accept: "application/json",
        ...(xff ? { "x-forwarded-for": xff } : {}),
        ...(clientIp ? { "x-client-ip": clientIp } : {}),
        ...(proxySecret ? { "x-proxy-auth": proxySecret } : {}),
        ...(authorization ? { authorization } : {}),
      },
      body,
      signal: AbortSignal.timeout(TIMEOUT_MS),
      cache: "no-store",
    });
    const resBody = await res.text();
    return new NextResponse(resBody || null, {
      status: res.status,
      headers: { "content-type": res.headers.get("content-type") ?? "application/json; charset=utf-8" },
    });
  } catch (err) {
    const message = err instanceof Error ? err.message : String(err);
    return NextResponse.json({ error: "upstream_unreachable", message }, { status: 502 });
  }
}

// 로그인 필요 쓰기 프록시(후기·댓글·리액션 등): 세션 쿠키(httpOnly JWT)를 Bearer로 백엔드에 전달한다.
// 쿠키가 없으면 백엔드까지 가지 않고 즉시 401(불필요한 왕복 방지) — /api/me와 동일 판정 기준.
// 백엔드가 돌려주는 401(토큰 만료 등)·400(검증 실패)은 그대로 통과시켜 클라이언트가 구분할 수 있게 한다.
// method로 POST/DELETE를 모두 지원한다(리액션 취소는 DELETE + body). GET body 없는 메서드는 body 생략.
export async function proxyAuthed(method: string, path: string, request: NextRequest): Promise<NextResponse> {
  if (!BASE) {
    return NextResponse.json(
      { error: "config", message: "GEUNEUL_API_BASE is not configured on the server." },
      { status: 500 },
    );
  }
  const token = request.cookies.get(SESSION_COOKIE)?.value;
  if (!token) {
    return NextResponse.json({ error: "unauthenticated", message: "로그인이 필요해요." }, { status: 401 });
  }
  try {
    const body = await request.text();
    const res = await fetch(`${BASE}${path}`, {
      method,
      headers: {
        "content-type": "application/json",
        accept: "application/json",
        authorization: `Bearer ${token}`,
      },
      body: body || undefined,
      signal: AbortSignal.timeout(TIMEOUT_MS),
      cache: "no-store",
    });
    const resBody = await res.text();
    // 빈 바디는 null로(204/205/304 null-body status에 "" 주면 Response 생성자가 TypeError→502 오변환, 푸시 test 실패 오탐).
    return new NextResponse(resBody || null, {
      status: res.status,
      headers: { "content-type": res.headers.get("content-type") ?? "application/json; charset=utf-8" },
    });
  } catch (err) {
    const message = err instanceof Error ? err.message : String(err);
    return NextResponse.json({ error: "upstream_unreachable", message }, { status: 502 });
  }
}

// 후기 작성 등 로그인 POST — proxyAuthed의 얇은 래퍼(기존 호출부 호환).
export function proxyAuthedPost(path: string, request: NextRequest): Promise<NextResponse> {
  return proxyAuthed("POST", path, request);
}

// 공개지만 로그인 시 개인화되는 GET(예: 작성자 프로필의 following 상태, N7). 세션 쿠키가 있으면 Bearer로 실어
// 보내되, 없어도 401을 내지 않고 그대로 진행한다(비로그인도 프로필은 봄 — proxyAuthed와 proxy의 중간).
export async function proxyOptionalAuth(method: string, path: string, request: NextRequest): Promise<NextResponse> {
  if (!BASE) {
    return NextResponse.json(
      { error: "config", message: "GEUNEUL_API_BASE is not configured on the server." },
      { status: 500 },
    );
  }
  const token = request.cookies.get(SESSION_COOKIE)?.value;
  try {
    const res = await fetch(`${BASE}${path}`, {
      method,
      headers: {
        accept: "application/json",
        ...(token ? { authorization: `Bearer ${token}` } : {}),
      },
      signal: AbortSignal.timeout(TIMEOUT_MS),
      cache: "no-store",
    });
    const resBody = await res.text();
    // 빈 바디는 null로(204/205/304 null-body status에 "" 주면 Response 생성자가 TypeError→502 오변환, 푸시 test 실패 오탐).
    return new NextResponse(resBody || null, {
      status: res.status,
      headers: { "content-type": res.headers.get("content-type") ?? "application/json; charset=utf-8" },
    });
  } catch (err) {
    const message = err instanceof Error ? err.message : String(err);
    return NextResponse.json({ error: "upstream_unreachable", message }, { status: 502 });
  }
}

// 사진 presign 전용 프록시: purpose=report는 익명 허용(proxyPost처럼 XFF/클라이언트IP를 보존해 백엔드
// 레이트리밋이 유저별로 동작하게 함), purpose=review는 세션 쿠키가 있으면 Bearer로 함께 실어 보낸다
// (proxyAuthedPost와 달리 쿠키가 없다고 즉시 401을 내지 않는다 — report 용도의 익명 요청까지 막아버리므로.
// 없으면 그대로 진행하고, 백엔드가 review 용도에서 미인증이면 401을 낸다).
export async function proxyPhotoPresign(request: NextRequest): Promise<NextResponse> {
  if (!BASE) {
    return NextResponse.json(
      { error: "config", message: "GEUNEUL_API_BASE is not configured on the server." },
      { status: 500 },
    );
  }
  try {
    const body = await request.text();
    const xff = request.headers.get("x-forwarded-for") ?? "";
    const clientIp = request.headers.get("x-vercel-forwarded-for")
      ?? request.headers.get("x-real-ip")
      ?? xff.split(",")[0].trim();
    const proxySecret = process.env.GEUNEUL_PROXY_SECRET ?? "";
    if (!proxySecret) {
      return NextResponse.json({ error: "config", message: "GEUNEUL_PROXY_SECRET is not configured." }, { status: 500 });
    }
    const token = request.cookies.get(SESSION_COOKIE)?.value;
    const res = await fetch(`${BASE}/photos/presign`, {
      method: "POST",
      headers: {
        "content-type": "application/json",
        accept: "application/json",
        ...(xff ? { "x-forwarded-for": xff } : {}),
        ...(clientIp ? { "x-client-ip": clientIp } : {}),
        ...(proxySecret ? { "x-proxy-auth": proxySecret } : {}),
        ...(token ? { authorization: `Bearer ${token}` } : {}),
      },
      body,
      signal: AbortSignal.timeout(TIMEOUT_MS),
      cache: "no-store",
    });
    const resBody = await res.text();
    // 빈 바디는 null로(204/205/304 null-body status에 "" 주면 Response 생성자가 TypeError→502 오변환, 푸시 test 실패 오탐).
    return new NextResponse(resBody || null, {
      status: res.status,
      headers: { "content-type": res.headers.get("content-type") ?? "application/json; charset=utf-8" },
    });
  } catch (err) {
    const message = err instanceof Error ? err.message : String(err);
    return NextResponse.json({ error: "upstream_unreachable", message }, { status: 502 });
  }
}

// path 예: "/places", "/places/nearest", "/places/12"
export async function proxy(path: string, search: string, request?: NextRequest): Promise<NextResponse> {
  if (!BASE) {
    return NextResponse.json(
      { error: "config", message: "GEUNEUL_API_BASE is not configured on the server." },
      { status: 500 },
    );
  }
  try {
    const res = await backendFetch(path, search, request);
    const body = await res.text();
    return new NextResponse(body, {
      status: res.status,
      headers: { "content-type": res.headers.get("content-type") ?? "application/json; charset=utf-8" },
    });
  } catch (err) {
    const message = err instanceof Error ? err.message : String(err);
    return NextResponse.json({ error: "upstream_unreachable", message }, { status: 502 });
  }
}

// SSE(text/event-stream) 스트리밍 프록시 — 응답 바디를 버퍼링하지 않고 그대로 흘려보낸다(A4, ADR-0004).
// proxy()는 res.text()로 전량 버퍼링해 SSE에 못 쓰므로 별도 경로가 필요하다. Vercel 함수 최대 실행시간에
// 걸려 연결이 끊겨도 브라우저 EventSource가 자동 재연결하고, 스냅샷(GET /alerts/surge)이 공백을 메운다.
// cache 무효화 헤더를 강제해 프록시/CDN 버퍼링을 끈다(SSE는 즉시 전달이 생명).
export async function proxyStream(path: string, search: string): Promise<Response> {
  if (!BASE) {
    return NextResponse.json(
      { error: "config", message: "GEUNEUL_API_BASE is not configured on the server." },
      { status: 500 },
    );
  }
  try {
    const url = `${BASE}${path}${search ? `?${search}` : ""}`;
    const upstream = await fetch(url, {
      headers: { accept: "text/event-stream" },
      cache: "no-store",
      // SSE는 장시간 열려 있어 요청 타임아웃을 걸지 않는다(끊김은 EventSource 재연결이 처리).
    });
    if (!upstream.ok || !upstream.body) {
      return NextResponse.json({ error: "upstream", status: upstream.status }, { status: 502 });
    }
    return new Response(upstream.body, {
      status: 200,
      headers: {
        "content-type": "text/event-stream; charset=utf-8",
        "cache-control": "no-cache, no-transform",
        connection: "keep-alive",
      },
    });
  } catch (err) {
    const message = err instanceof Error ? err.message : String(err);
    return NextResponse.json({ error: "upstream_unreachable", message }, { status: 502 });
  }
}

// ALB에서 JSON을 받아 파싱하는 서버측 헬퍼(BASE 미설정/4xx·5xx는 throw).
// 급해요의 카테고리별 nearest 팬아웃·병합은 이 헬퍼를 여러 번 호출하는 app/api/urgent/route.ts에 있다.
export async function backendJson<T>(path: string, search: string): Promise<T> {
  if (!BASE) throw new Error("GEUNEUL_API_BASE not configured");
  const res = await backendFetch(path, search);
  if (!res.ok) throw new Error(`upstream ${res.status}`);
  return (await res.json()) as T;
}
