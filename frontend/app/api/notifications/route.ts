import type { NextRequest } from "next/server";
import { proxyAuthed } from "@/lib/backend";

// GET /api/notifications → 백엔드 /notifications (알림 센터: 발송 이력 + 안읽음 수, 로그인 필요)
export function GET(request: NextRequest) {
  return proxyAuthed("GET", "/notifications", request);
}
