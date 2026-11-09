import { proxyStream } from "@/lib/backend";

// GET /api/alerts/stream  → 백엔드 /alerts/stream (SSE 실시간 급증 푸시, text/event-stream 패스스루)
// 동적 라우트(캐시 금지) — SSE는 매 연결이 실시간 스트림이다.
export const dynamic = "force-dynamic";

export function GET() {
  return proxyStream("/alerts/stream", "");
}
