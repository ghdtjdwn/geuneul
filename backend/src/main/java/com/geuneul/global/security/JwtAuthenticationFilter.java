package com.geuneul.global.security;

import com.geuneul.domain.auth.Role;
import com.geuneul.domain.auth.JwtService;
import com.geuneul.domain.auth.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * Authorization: Bearer {JWT} 를 검증해 SecurityContext에 인증을 심는다(스테이트리스).
 * 토큰이 없거나 무효면 아무것도 심지 않고 통과 — 보호 경로는 SecurityConfig가 401로 거른다.
 * (인증 실패로 여기서 예외를 던지지 않는다: 공개 경로는 토큰 없이도 지나가야 하므로.)
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER = "Bearer ";

    private final JwtService jwtService;
    private final UserRepository userRepository;

    public JwtAuthenticationFilter(JwtService jwtService, UserRepository userRepository) {
        this.jwtService = jwtService;
        this.userRepository = userRepository;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith(BEARER)
                && SecurityContextHolder.getContext().getAuthentication() == null) {
            try {
                JwtService.AuthPrincipal principal = jwtService.parse(header.substring(BEARER.length()));
                principal = reconcileServerState(principal).orElseThrow();
                var authorities = List.of(new SimpleGrantedAuthority("ROLE_" + principal.role().name()));
                var authentication = new UsernamePasswordAuthenticationToken(principal, null, authorities);
                SecurityContextHolder.getContext().setAuthentication(authentication);
            } catch (Exception e) {
                // 무효 토큰 — 익명으로 진행(보호 경로면 이후 401). 공개 경로는 그대로 통과.
                SecurityContextHolder.clearContext();
            }
        }
        filterChain.doFilter(request, response);
    }

    private Optional<JwtService.AuthPrincipal> reconcileServerState(JwtService.AuthPrincipal principal) {
        if (userRepository == null) return Optional.of(principal);
        return userRepository.findById(principal.userId())
                .filter(user -> user.getTokenVersion() == principal.tokenVersion())
                .map(user -> {
                    Role role = principal.role() == Role.ADMIN && user.getRole() != Role.ADMIN
                            ? Role.USER : principal.role();
                    return new JwtService.AuthPrincipal(principal.userId(), role, principal.tokenVersion());
                });
    }
}
