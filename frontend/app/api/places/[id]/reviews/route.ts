import type { NextRequest } from "next/server";
import { proxy } from "@/lib/backend";

// GET /api/places/{id}/reviews → 백엔드 /places/{id}/reviews (공개, 최신순 페이지네이션).
// page/size 쿼리는 그대로 통과(미지정 시 백엔드 기본값 page=0·size=20).
export async function GET(request: NextRequest, { params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  return proxy(`/places/${encodeURIComponent(id)}/reviews`, request.nextUrl.searchParams.toString());
}
