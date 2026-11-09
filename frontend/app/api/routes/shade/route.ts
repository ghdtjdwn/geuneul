import type { NextRequest } from "next/server";
import { proxy } from "@/lib/backend";

// GET /api/routes/shade?fromLat&fromLng&toLat&toLng → 백엔드 /routes/shade (그늘 경유 경로, 공개, C4). /routes/toilet 미러.
export function GET(request: NextRequest) {
  return proxy("/routes/shade", request.nextUrl.searchParams.toString(), request);
}
