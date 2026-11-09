import type { NextRequest } from "next/server";
import { proxyAuthed } from "@/lib/backend";

// POST/DELETE /api/users/{id}/follow → 백엔드 /users/{id}/follow (팔로우/언팔로우, 로그인 필요 — Bearer 전달)
export async function POST(request: NextRequest, { params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  return proxyAuthed("POST", `/users/${encodeURIComponent(id)}/follow`, request);
}

export async function DELETE(request: NextRequest, { params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  return proxyAuthed("DELETE", `/users/${encodeURIComponent(id)}/follow`, request);
}
