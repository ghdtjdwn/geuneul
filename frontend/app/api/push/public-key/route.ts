import { proxy } from "@/lib/backend";

// GET /api/push/public-key → 백엔드 /push/public-key (VAPID 공개키 + 활성 여부, 공개)
export function GET() {
  return proxy("/push/public-key", "");
}
