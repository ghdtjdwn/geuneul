import type { NextRequest } from "next/server";
import { proxyAuthed } from "@/lib/backend";

// GET /api/me/reviews → 백엔드 /me/reviews (내 후기, 로그인 필요 — Bearer 전달)
export function GET(request: NextRequest) {
  return proxyAuthed("GET", "/me/reviews", request);
}
