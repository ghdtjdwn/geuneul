import type { NextRequest } from "next/server";
import { proxyAuthed } from "@/lib/backend";

// POST /api/reactions → 백엔드 (리액션 추가, 로그인 필요, 멱등)
export function POST(request: NextRequest) {
  return proxyAuthed("POST", "/reactions", request);
}

// DELETE /api/reactions → 백엔드 (리액션 취소, 로그인 필요, body로 대상 전달)
export function DELETE(request: NextRequest) {
  return proxyAuthed("DELETE", "/reactions", request);
}
