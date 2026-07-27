package com.geuneul.domain.auth;

import com.geuneul.domain.auth.oauth.OAuthClient;
import com.geuneul.domain.auth.oauth.OAuthUserInfo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * 소셜 로그인 오케스트레이션: 제공자별 클라이언트로 code를 교환해 사용자 정보를 얻고,
 * (provider, provider_id)로 upsert한 뒤 JWT를 발급한다.
 */
@Service
public class AuthService {

    private final Map<AuthProvider, OAuthClient> clients = new EnumMap<>(AuthProvider.class);
    private final UserRepository userRepository;
    private final JwtService jwtService;

    public AuthService(List<OAuthClient> oauthClients, UserRepository userRepository, JwtService jwtService) {
        for (OAuthClient c : oauthClients) {
            clients.put(c.provider(), c);
        }
        this.userRepository = userRepository;
        this.jwtService = jwtService;
    }

    /** code 교환 → 유저 upsert → JWT. */
    @Transactional
    public AuthResult login(AuthProvider provider, String code, String redirectUri) {
        OAuthClient client = clients.get(provider);
        if (client == null) {
            throw new ResponseStatusException(NOT_FOUND, "지원하지 않는 제공자: " + provider);
        }
        OAuthUserInfo info = client.exchange(code, redirectUri);
        User user = upsert(provider, info);
        String token = jwtService.issue(user);
        return new AuthResult(token, user);
    }

    /** /me — JWT 주체(userId)로 사용자 조회. */
    @Transactional(readOnly = true)
    public User getById(long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "사용자를 찾을 수 없습니다"));
    }

    /** Revokes every JWT issued with the current token version, including stolen copies. */
    @Transactional
    public void logout(long userId) {
        User user = userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "사용자를 찾을 수 없습니다"));
        user.revokeSessions();
    }

    private User upsert(AuthProvider provider, OAuthUserInfo info) {
        String nickname = (info.nickname() == null || info.nickname().isBlank())
                ? defaultNickname(provider) : info.nickname();
        User user = userRepository.findByProviderAndProviderIdForUpdate(provider, info.providerId())
                .orElseGet(() -> {
                    userRepository.insertIfAbsent(provider.name(), info.providerId(), info.email(), nickname,
                            info.profileImage());
                    return userRepository.findByProviderAndProviderIdForUpdate(provider, info.providerId())
                            .orElseThrow(() -> new IllegalStateException("OAuth user upsert did not produce a row"));
                });
        // Both the insert winner and conflict loser refresh while holding the same row lock.
        user.refreshProfile(info.email(), nickname, info.profileImage());
        return user;
    }

    private static String defaultNickname(AuthProvider provider) {
        return provider == AuthProvider.KAKAO ? "카카오사용자" : "구글사용자";
    }

    /** 로그인 결과 — 발급 토큰 + 사용자. */
    public record AuthResult(String token, User user) {
    }
}
