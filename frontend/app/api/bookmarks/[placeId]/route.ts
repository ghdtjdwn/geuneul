import type { NextRequest } from "next/server";
import { proxyAuthed } from "@/lib/backend";

// DELETE /api/bookmarks/{placeId} → 백엔드 /bookmarks/{placeId} (관심 장소 해제, 로그인 필요)
export async function DELETE(request: NextRequest, { params }: { params: Promise<{ placeId: string }> }) {
  const { placeId } = await params;
  return proxyAuthed("DELETE", `/bookmarks/${encodeURIComponent(placeId)}`, request);
}
