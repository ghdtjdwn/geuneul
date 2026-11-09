import type { NextRequest } from "next/server";
import { proxyOptionalAuth } from "@/lib/backend";

// GET /api/users/{id} → 백엔드 /users/{id} (작성자 공개 프로필). 공개지만 로그인 시 following 채우려 쿠키를 옵션 전달.
export async function GET(request: NextRequest, { params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  return proxyOptionalAuth("GET", `/users/${encodeURIComponent(id)}`, request);
}
