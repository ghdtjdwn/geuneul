import type { NextRequest } from "next/server";
import { proxy } from "@/lib/backend";

// GET /api/places/search?query&lat&lng&size → 백엔드 /places/search (카카오 키워드, 키는 백엔드에만).
export function GET(request: NextRequest) {
  return proxy("/places/search", request.nextUrl.searchParams.toString(), request);
}
