import type { NextRequest } from "next/server";
import { proxyAuthed } from "@/lib/backend";

// GET /api/me/bookmarks → 백엔드 /me/bookmarks (내 관심 장소 목록, 로그인 필요 — Bearer 전달)
export function GET(request: NextRequest) {
  return proxyAuthed("GET", "/me/bookmarks", request);
}
