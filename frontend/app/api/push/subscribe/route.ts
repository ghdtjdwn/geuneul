import type { NextRequest } from "next/server";
import { proxyAuthed } from "@/lib/backend";

// POST /api/push/subscribe → 백엔드 /push/subscribe (Web Push 구독 등록, 로그인 필요)
export function POST(request: NextRequest) {
  return proxyAuthed("POST", "/push/subscribe", request);
}
