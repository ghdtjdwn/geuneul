import type { NextRequest } from "next/server";
import { proxy } from "@/lib/backend";

// GET /api/places/{id} → 백엔드 /places/{id}
export async function GET(_request: NextRequest, { params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  return proxy(`/places/${encodeURIComponent(id)}`, "");
}
