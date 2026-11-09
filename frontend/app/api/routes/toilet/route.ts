import type { NextRequest } from "next/server";
import { proxy } from "@/lib/backend";

// GET /api/routes/toilet?fromLat&fromLng&toLat&toLng → 백엔드 /routes/toilet (화장실 포함 경로, 공개)
export function GET(request: NextRequest) {
  return proxy("/routes/toilet", request.nextUrl.searchParams.toString(), request);
}
