import type { NextRequest } from "next/server";
import { proxyAuthed } from "@/lib/backend";

// GET /api/me/comments → 백엔드 /me/comments (내 댓글, 로그인 필요 — Bearer 전달)
export function GET(request: NextRequest) {
  return proxyAuthed("GET", "/me/comments", request);
}
