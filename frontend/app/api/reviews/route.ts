import type { NextRequest } from "next/server";
import { proxyAuthedPost } from "@/lib/backend";

// POST /api/reviews → 백엔드 POST /reviews (영구 후기 작성/수정, 로그인 필요).
// 세션 쿠키(httpOnly JWT)를 Bearer로 전달 — 쿠키 없으면 401(백엔드까지 안 감).
export function POST(request: NextRequest) {
  return proxyAuthedPost("/reviews", request);
}
