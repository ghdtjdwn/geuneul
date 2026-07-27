package com.geuneul.domain.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

/**
 * JWT 발급/검증 (jjwt 0.13, HS256). 스테이트리스 세션 — 서버가 상태를 안 들고 서명으로만 신원 증명.
 *
 * - 시크릿(JWT_SECRET)은 **지연 검증**한다: 비어 있어도 컨텍스트는 뜨고(배포 안전성 — 값 주입 전 배포되어도
 *   앱이 안 죽음), 실제 발급/검증 시점에 없거나 너무 짧으면(HS256은 ≥256bit=32바이트) 명확히 실패한다.
 * - 페이로드: sub=userId, role, ver(token_version). 만료는 기본 7일(카카오/구글 재로그인으로 갱신).
 * - Clock 주입(TimeConfig)으로 발급/만료 시각을 테스트에서 결정적으로 제어.
 */
@Service
public class JwtService {

    private final String secret;
    private final Duration expiration;
    private final Clock clock;

    public JwtService(@Value("${jwt.secret:}") String secret,
                      @Value("${jwt.expiration-hours:168}") long expirationHours,
                      Clock clock) {
        this.secret = secret == null ? "" : secret;
        this.expiration = Duration.ofHours(expirationHours);
        this.clock = clock;
    }

    /** userId·role을 담은 서명 토큰. */
    public String issue(User user) {
        Instant now = Instant.now(clock);
        return Jwts.builder()
                .subject(String.valueOf(user.getId()))
                .claim("role", user.getRole().name())
                .claim("ver", user.getTokenVersion())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(expiration)))
                .signWith(key())
                .compact();
    }

    /** 서명·만료 검증 후 주체(userId, role) 반환. 유효하지 않으면 JwtException 계열 예외. */
    public AuthPrincipal parse(String token) {
        Jws<Claims> jws = Jwts.parser()
                .verifyWith(key())
                .clock(() -> Date.from(Instant.now(clock)))
                .build()
                .parseSignedClaims(token);
        Claims c = jws.getPayload();
        long userId = Long.parseLong(c.getSubject());
        Role role = Role.valueOf(c.get("role", String.class));
        Object rawVersion = c.get("ver");
        long tokenVersion = rawVersion instanceof Number number ? number.longValue() : 0L;
        return new AuthPrincipal(userId, role, tokenVersion);
    }

    private SecretKey key() {
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalStateException(
                    "JWT_SECRET이 없거나 너무 짧습니다(HS256은 ≥32바이트 필요). 규칙 D: .env/SSM으로만 주입.");
        }
        return Keys.hmacShaKeyFor(bytes);
    }

    /** JWT에서 복원한 인증 주체. */
    public record AuthPrincipal(long userId, Role role, long tokenVersion) {
        /** Existing tests and pre-migration tokens retain version zero compatibility. */
        public AuthPrincipal(long userId, Role role) {
            this(userId, role, 0L);
        }
    }
}
