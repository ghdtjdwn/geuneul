import type { NextRequest } from "next/server";
import { proxyPhotoPresign } from "@/lib/backend";

// POST /api/photos/presign → 백엔드 /photos/presign. purpose=report는 익명 허용, purpose=review는
// 세션 쿠키가 있으면 Bearer로 함께 전달(둘 다 proxyPhotoPresign 안에서 처리 — lib/backend.ts 참고).
export function POST(request: NextRequest) {
  return proxyPhotoPresign(request);
}
