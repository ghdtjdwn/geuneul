import type { NextRequest } from "next/server";
import { proxy } from "@/lib/backend";

// GET /api/alerts/surge?bounds=w,s,e,n&limit=  → 백엔드 /alerts/surge (뷰포트 급증 스냅샷/폴백)
export function GET(request: NextRequest) {
  return proxy("/alerts/surge", request.nextUrl.searchParams.toString());
}
