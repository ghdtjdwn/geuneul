import type { NextRequest } from "next/server";
import { proxy, proxyAuthedPost } from "@/lib/backend";

// GET /api/reviews/{id}/comments → 백엔드 (공개, 오래된 순)
export async function GET(_request: NextRequest, { params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  return proxy(`/reviews/${encodeURIComponent(id)}/comments`, "");
}

// POST /api/reviews/{id}/comments → 백엔드 (댓글 작성, 로그인 필요)
export async function POST(request: NextRequest, { params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  return proxyAuthedPost(`/reviews/${encodeURIComponent(id)}/comments`, request);
}
