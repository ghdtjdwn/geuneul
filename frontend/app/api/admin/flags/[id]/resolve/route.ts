import type { NextRequest } from "next/server";
import { proxyAuthedPost } from "@/lib/backend";

// POST /api/admin/flags/{id}/resolve → 백엔드 /admin/flags/{id}/resolve (신고 처리, C1). ADMIN 전용.
// body {status: RESOLVED|DISMISSED}. RESOLVED면 백엔드가 대상 콘텐츠를 숨긴다. 이미 처리건은 409.
export async function POST(request: NextRequest, { params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  return proxyAuthedPost(`/admin/flags/${encodeURIComponent(id)}/resolve`, request);
}
