package com.geuneul.domain.auth;

import com.geuneul.domain.auth.oauth.OAuthClient;
import com.geuneul.domain.auth.oauth.OAuthUserInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 로그인 오케스트레이션 — (provider, provider_id) upsert 규칙과 미지원 제공자 처리를 검증한다.
 */
class AuthServiceTest {

    private OAuthClient kakao;
    private UserRepository userRepository;
    private JwtService jwtService;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        kakao = mock(OAuthClient.class);
        when(kakao.provider()).thenReturn(AuthProvider.KAKAO);
        userRepository = mock(UserRepository.class);
        jwtService = mock(JwtService.class);
        when(jwtService.issue(any())).thenReturn("jwt-token");
        authService = new AuthService(List.of(kakao), userRepository, jwtService);
    }

    @Test
    @DisplayName("신규 사용자: 조회 empty → save 후 토큰 발급")
    void createsNewUser() {
        when(kakao.exchange("code", "uri"))
                .thenReturn(new OAuthUserInfo("kid-1", null, "그늘러", "http://img"));
        when(userRepository.findByProviderAndProviderIdForUpdate(AuthProvider.KAKAO, "kid-1"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(User.create(AuthProvider.KAKAO, "kid-1", null, "그늘러", "http://img")));
        when(userRepository.insertIfAbsent("KAKAO", "kid-1", null, "그늘러", "http://img")).thenReturn(1);

        AuthService.AuthResult result = authService.login(AuthProvider.KAKAO, "code", "uri");

        assertThat(result.token()).isEqualTo("jwt-token");
        assertThat(result.user().getProviderId()).isEqualTo("kid-1");
        verify(userRepository).insertIfAbsent("KAKAO", "kid-1", null, "그늘러", "http://img");
    }

    @Test
    @DisplayName("기존 사용자: 프로필 갱신, save 미호출(dirty checking)")
    void refreshesExistingUser() {
        User existing = User.create(AuthProvider.KAKAO, "kid-1", null, "옛닉", null);
        when(kakao.exchange("code", "uri"))
                .thenReturn(new OAuthUserInfo("kid-1", "new@x.com", "새닉", "http://img2"));
        when(userRepository.findByProviderAndProviderIdForUpdate(AuthProvider.KAKAO, "kid-1"))
                .thenReturn(Optional.of(existing));

        AuthService.AuthResult result = authService.login(AuthProvider.KAKAO, "code", "uri");

        assertThat(result.user().getNickname()).isEqualTo("새닉");
        assertThat(result.user().getEmail()).isEqualTo("new@x.com");
        verify(userRepository, never()).insertIfAbsent(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("닉네임이 비면 제공자 기본 닉네임")
    void fallsBackNickname() {
        when(kakao.exchange("code", "uri"))
                .thenReturn(new OAuthUserInfo("kid-2", null, "  ", null));
        when(userRepository.findByProviderAndProviderIdForUpdate(AuthProvider.KAKAO, "kid-2"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(User.create(AuthProvider.KAKAO, "kid-2", null, "카카오사용자", null)));

        AuthService.AuthResult result = authService.login(AuthProvider.KAKAO, "code", "uri");
        assertThat(result.user().getNickname()).isEqualTo("카카오사용자");
    }

    @Test
    @DisplayName("등록되지 않은 제공자는 404")
    void unsupportedProvider() {
        assertThatThrownBy(() -> authService.login(AuthProvider.GOOGLE, "code", "uri"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
    }

    @Test
    @DisplayName("로그아웃은 사용자 token_version을 증가시켜 기존 토큰을 폐기한다")
    void logoutRevokesSessions() {
        User user = User.create(AuthProvider.KAKAO, "kid", null, "그늘러", null);
        when(userRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(user));

        authService.logout(10L);

        assertThat(user.getTokenVersion()).isEqualTo(1L);
    }
}
