import type { NextRequest } from "next/server";
import { proxyAuthed } from "@/lib/backend";

// GET /api/admin/flags?status= → 백엔드 /admin/flags?status= (검수 큐, C1). ADMIN 전용(백엔드 /admin/** =
// hasRole ADMIN → 비관리자 403·비로그인 401). proxyAuthed는 쿼리스트링을 포워딩하지 않으므로,
// nextUrl.searchParams를 path에 직접 결합해 status/page/size를 넘긴다(BACKLOG C1 주의).
export function GET(request: NextRequest) {
  const search = request.nextUrl.searchParams.toString();
  return proxyAuthed("GET", `/admin/flags${search ? `?${search}` : ""}`, request);
}
