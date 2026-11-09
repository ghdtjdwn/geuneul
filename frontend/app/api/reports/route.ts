import type { NextRequest } from "next/server";
import { proxyOptionalAuthedPost } from "@/lib/backend";

// POST /api/reports → 백엔드 /reports (익명 허용, 로그인 시 인증 전달). 클라이언트 IP를 XFF로 보존.
export function POST(request: NextRequest) {
  return proxyOptionalAuthedPost("/reports", request);
}
