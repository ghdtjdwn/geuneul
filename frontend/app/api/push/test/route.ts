import type { NextRequest } from "next/server";
import { proxyAuthed } from "@/lib/backend";

// POST /api/push/test → 백엔드 /push/test (내 기기로 테스트 배너 1회, 로그인 필요)
export function POST(request: NextRequest) {
  return proxyAuthed("POST", "/push/test", request);
}
