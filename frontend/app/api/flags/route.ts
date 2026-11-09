import type { NextRequest } from "next/server";
import { proxyAuthedPost } from "@/lib/backend";

// POST /api/flags → 백엔드 /flags (신고 접수, C1). 로그인 필요 — reporterId는 백엔드가 JWT에서 취한다.
// 201(접수)·409(중복 신고)·404(대상 없음)·401(비로그인)을 그대로 통과시켜 클라이언트가 구분한다.
export function POST(request: NextRequest) {
  return proxyAuthedPost("/flags", request);
}
