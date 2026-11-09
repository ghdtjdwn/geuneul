import type { NextRequest } from "next/server";
import { proxyAuthed } from "@/lib/backend";

// GET /api/me/reactions → 백엔드 /me/reactions (내 유용해요, 로그인 필요 — Bearer 전달)
export function GET(request: NextRequest) {
  return proxyAuthed("GET", "/me/reactions", request);
}
