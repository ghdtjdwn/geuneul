package com.geuneul.domain.auth;

import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * JWT 발급/검증 라운드트립 — jjwt 0.13(Boot 4/Jackson 환경)에서 서명·만료·주체 복원이 실제로 되는지 못 박는다.
 */
class JwtServiceTest {

    private static final String SECRET = "geuneul-test-secret-0123456789-abcdefgh"; // ≥32바이트 테스트 더미 gitleaks:allow
    private final Clock clock = Clock.fixed(Instant.parse("2026-07-09T00:00:00Z"), ZoneOffset.UTC);

    private User user(long id, Role role) {
        User u = mock(User.class);
        when(u.getId()).thenReturn(id);
        when(u.getRole()).thenReturn(role);
        when(u.getTokenVersion()).thenReturn(7L);
        return u;
    }

    @Test
    @DisplayName("발급→검증 라운드트립: userId·role 복원")
    void roundTrip() {
        JwtService jwt = new JwtService(SECRET, 168, clock);
        String token = jwt.issue(user(42L, Role.USER));

        JwtService.AuthPrincipal principal = jwt.parse(token);
        assertThat(principal.userId()).isEqualTo(42L);
        assertThat(principal.role()).isEqualTo(Role.USER);
        assertThat(principal.tokenVersion()).isEqualTo(7L);
    }

    @Test
    @DisplayName("만료된 토큰은 거부")
    void rejectsExpired() {
        JwtService issuer = new JwtService(SECRET, 1, clock); // 1시간 만료
        String token = issuer.issue(user(1L, Role.USER));

        Clock later = Clock.fixed(Instant.parse("2026-07-09T02:00:00Z"), ZoneOffset.UTC);
        JwtService verifier = new JwtService(SECRET, 1, later);
        assertThatThrownBy(() -> verifier.parse(token)).isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("다른 시크릿으로 서명된 토큰은 거부")
    void rejectsWrongSignature() {
        String token = new JwtService(SECRET, 168, clock).issue(user(1L, Role.USER));
        JwtService other = new JwtService("another-secret-0123456789-abcdef-xyz", 168, clock);
        assertThatThrownBy(() -> other.parse(token)).isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("시크릿이 없거나 짧으면 발급 시 명확한 예외")
    void failsOnMissingSecret() {
        JwtService noKey = new JwtService("", 168, clock);
        assertThatThrownBy(() -> noKey.issue(user(1L, Role.USER)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET");
    }
}
