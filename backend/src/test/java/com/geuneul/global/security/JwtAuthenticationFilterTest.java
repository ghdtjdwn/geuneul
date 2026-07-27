package com.geuneul.global.security;

import com.geuneul.domain.auth.JwtService;
import com.geuneul.domain.auth.Role;
import com.geuneul.domain.auth.User;
import com.geuneul.domain.auth.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JwtAuthenticationFilterTest {

    private final JwtService jwtService = mock(JwtService.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final JwtAuthenticationFilter filter = new JwtAuthenticationFilter(jwtService, userRepository);

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("USER 토큰도 DB token_version이 일치할 때만 인증된다")
    void userTokenChecksServerVersion() throws Exception {
        User user = mock(User.class);
        when(user.getTokenVersion()).thenReturn(0L);
        when(jwtService.parse("user-token")).thenReturn(new JwtService.AuthPrincipal(10L, Role.USER));
        when(userRepository.findById(10L)).thenReturn(Optional.of(user));

        filter.doFilter(request("user-token"), new MockHttpServletResponse(), new MockFilterChain());

        assertThat(SecurityContextHolder.getContext().getAuthentication().getAuthorities())
                .extracting("authority")
                .containsExactly("ROLE_USER");
        verify(userRepository).findById(10L);
    }

    @Test
    @DisplayName("ADMIN 토큰이어도 DB role이 USER면 USER 권한으로 강등한다")
    void adminTokenDemotedWhenDbRoleIsUser() throws Exception {
        User user = mock(User.class);
        when(user.getRole()).thenReturn(Role.USER);
        when(user.getTokenVersion()).thenReturn(0L);
        when(jwtService.parse("admin-token")).thenReturn(new JwtService.AuthPrincipal(10L, Role.ADMIN));
        when(userRepository.findById(10L)).thenReturn(Optional.of(user));

        filter.doFilter(request("admin-token"), new MockHttpServletResponse(), new MockFilterChain());

        assertThat(SecurityContextHolder.getContext().getAuthentication().getAuthorities())
                .extracting("authority")
                .containsExactly("ROLE_USER");
        JwtService.AuthPrincipal principal =
                (JwtService.AuthPrincipal) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        assertThat(principal.role()).isEqualTo(Role.USER);
    }

    @Test
    @DisplayName("ADMIN 토큰이고 DB role도 ADMIN이면 ADMIN 권한을 유지한다")
    void adminTokenKeptWhenDbRoleIsAdmin() throws Exception {
        User user = mock(User.class);
        when(user.getRole()).thenReturn(Role.ADMIN);
        when(user.getTokenVersion()).thenReturn(0L);
        when(jwtService.parse("admin-token")).thenReturn(new JwtService.AuthPrincipal(10L, Role.ADMIN));
        when(userRepository.findById(10L)).thenReturn(Optional.of(user));

        filter.doFilter(request("admin-token"), new MockHttpServletResponse(), new MockFilterChain());

        assertThat(SecurityContextHolder.getContext().getAuthentication().getAuthorities())
                .extracting("authority")
                .containsExactly("ROLE_ADMIN");
    }

    @Test
    @DisplayName("로그아웃으로 DB token_version이 증가하면 기존 JWT는 즉시 익명 처리된다")
    void revokedVersionIsRejected() throws Exception {
        User user = mock(User.class);
        when(user.getTokenVersion()).thenReturn(1L);
        when(jwtService.parse("old-token")).thenReturn(new JwtService.AuthPrincipal(10L, Role.USER, 0L));
        when(userRepository.findById(10L)).thenReturn(Optional.of(user));

        filter.doFilter(request("old-token"), new MockHttpServletResponse(), new MockFilterChain());

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    private static MockHttpServletRequest request(String token) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token);
        return request;
    }
}
