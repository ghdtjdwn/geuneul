import type { NextRequest } from "next/server";
import { proxy } from "@/lib/backend";

// GET /api/places/{id}/popular-times → 백엔드 /places/{id}/popular-times (요일×시간 혼잡 파생, 백엔드 Redis 1h 캐시)
export async function GET(_request: NextRequest, { params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  return proxy(`/places/${encodeURIComponent(id)}/popular-times`, "");
}
