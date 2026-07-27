package com.geuneul.domain.report;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * XFF 신뢰경계 해석 단위 테스트 (코드리뷰 확정 버그의 회귀 방지).
 * 순수 오버로드 resolve(proxyAuth, clientIp, xff, remoteAddr)를 직접 검증한다.
 */
class ProxyClientResolverTest {

    private static ProxyClientResolver withSecret(String secret) {
        return new ProxyClientResolver(secret);
    }

    @Nested
    @DisplayName("시크릿 미설정 — 전달 헤더를 신뢰하지 않는 fail-safe")
    class NoSecret {
        private final ProxyClientResolver r = withSecret("");

        @Test
        @DisplayName("XFF를 무시하고 TCP 피어를 키로 쓴다")
        void ignoresXff() {
            assertThat(r.resolve(null, null, "203.0.113.7, 10.0.0.1", "10.9.9.9")).isEqualTo("x:10.9.9.9");
        }

        @Test
        @DisplayName("시크릿 미설정이면 X-Client-Ip/X-Proxy-Auth가 있어도 신뢰하지 않는다")
        void ignoresClientIpWithoutConfiguredSecret() {
            assertThat(r.resolve("anything", "1.2.3.4", "203.0.113.7", "10.9.9.9")).isEqualTo("x:10.9.9.9");
        }

        @Test
        @DisplayName("XFF 없으면 remoteAddr")
        void fallbackRemoteAddr() {
            assertThat(r.resolve(null, null, null, "10.9.9.9")).isEqualTo("x:10.9.9.9");
        }
    }

    @Nested
    @DisplayName("시크릿 설정(BFF-only 운영) — 위조 우회 차단")
    class WithSecret {
        private final ProxyClientResolver r = withSecret("s3cr3t");

        @Test
        @DisplayName("BFF가 시크릿으로 증명하면 X-Client-Ip를 신뢰(c: 네임스페이스)")
        void trustsClientIpWhenAuthenticated() {
            assertThat(r.resolve("s3cr3t", "198.51.100.9", "spoof, 76.76.21.1", "10.9.9.9"))
                    .isEqualTo("c:198.51.100.9");
        }

        @Test
        @DisplayName("시크릿 불일치(직접 ALB 타격)면 X-Client-Ip 무시, ALB append 최우측 XFF 사용")
        void ignoresSpoofedClientIpAndUsesRightmost() {
            // 공격자가 X-Client-Ip와 XFF 최좌측을 위조해도 최우측(ALB가 붙인 실 IP)이 키가 된다.
            assertThat(r.resolve("wrong", "1.2.3.4", "9.9.9.1, 9.9.9.2, 203.0.113.50", "10.9.9.9"))
                    .isEqualTo("x:203.0.113.50");
        }

        @Test
        @DisplayName("최좌측 회전 공격 무력화: 최우측이 고정이면 키도 고정")
        void rotatingLeftmostDoesNotCreateNewKeys() {
            String k1 = r.resolve(null, null, "1.1.1.1, 203.0.113.50", "10.9.9.9");
            String k2 = r.resolve(null, null, "2.2.2.2, 203.0.113.50", "10.9.9.9");
            assertThat(k1).isEqualTo("x:203.0.113.50");
            assertThat(k1).isEqualTo(k2); // 회전해도 같은 키 → 우회 불가
        }

        @Test
        @DisplayName("증명 없고 XFF도 없으면 remoteAddr")
        void fallbackRemoteAddr() {
            assertThat(r.resolve(null, null, null, "10.9.9.9")).isEqualTo("x:10.9.9.9");
        }
    }
}
