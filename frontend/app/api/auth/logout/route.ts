import { type NextRequest, NextResponse } from "next/server";
import { SESSION_COOKIE } from "@/lib/auth";

// POST /api/auth/logout — 백엔드 token_version을 먼저 증가시켜 JWT 사본까지 폐기한 뒤 쿠키를 지운다.
export async function POST(request: NextRequest) {
  const token = request.cookies.get(SESSION_COOKIE)?.value;
  if (token) {
    const base = (process.env.GEUNEUL_API_BASE ?? "").replace(/\/$/, "");
    if (!base) return NextResponse.json({ error: "config" }, { status: 500 });
    try {
      const upstream = await fetch(`${base}/auth/logout`, {
        method: "POST",
        headers: { authorization: `Bearer ${token}` },
        cache: "no-store",
        signal: AbortSignal.timeout(10_000),
      });
      // 401 means the token is already expired/revoked; local cookie cleanup is still correct.
      if (!upstream.ok && upstream.status !== 401) {
        return NextResponse.json({ error: "logout_failed" }, { status: upstream.status });
      }
    } catch {
      // Keep the cookie so the user can retry revocation instead of silently leaving a valid token copy.
      return NextResponse.json({ error: "upstream_unreachable" }, { status: 502 });
    }
  }
  const res = NextResponse.json({ ok: true });
  res.cookies.delete(SESSION_COOKIE);
  return res;
}
