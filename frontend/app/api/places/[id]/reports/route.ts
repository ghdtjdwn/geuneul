import type { NextRequest } from "next/server";
import { proxy } from "@/lib/backend";

// GET /api/places/{id}/reports → 백엔드 /places/{id}/reports (유효 제보 최신순)
export async function GET(_request: NextRequest, { params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  return proxy(`/places/${encodeURIComponent(id)}/reports`, "");
}
