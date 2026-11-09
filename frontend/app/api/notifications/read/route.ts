import type { NextRequest } from "next/server";
import { proxyAuthed } from "@/lib/backend";

// POST /api/notifications/read → 백엔드 /notifications/read (전체 읽음, 로그인 필요)
export function POST(request: NextRequest) {
  return proxyAuthed("POST", "/notifications/read", request);
}
