package com.geuneul.domain.auth;

import com.geuneul.AbstractIntegrationTest;
import com.geuneul.domain.auth.oauth.OAuthClient;
import com.geuneul.domain.auth.oauth.OAuthUserInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuthSessionConcurrencyIT extends AbstractIntegrationTest {

    @Autowired UserRepository users;
    @Autowired PlatformTransactionManager transactionManager;

    @Test
    @DisplayName("동시 첫 OAuth 로그인은 ON CONFLICT 후 row lock으로 하나의 user를 공유한다")
    void concurrentFirstLoginConvergesOnOneUser() throws Exception {
        users.deleteAll();
        CyclicBarrier exchanged = new CyclicBarrier(2);
        OAuthClient oauth = new OAuthClient() {
            @Override public AuthProvider provider() { return AuthProvider.KAKAO; }
            @Override public OAuthUserInfo exchange(String code, String redirectUri) {
                try {
                    exchanged.await(5, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
                return new OAuthUserInfo("atomic-first-user", code + "@example.com", "nick-" + code, null);
            }
        };
        JwtService jwt = new JwtService("0123456789abcdef0123456789abcdef", 1, Clock.systemUTC());
        AuthService service = new AuthService(List.of(oauth), users, jwt);
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);

        AuthService.AuthResult first;
        AuthService.AuthResult second;
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> transactions.execute(status ->
                    service.login(AuthProvider.KAKAO, "a", "redirect")));
            var b = executor.submit(() -> transactions.execute(status ->
                    service.login(AuthProvider.KAKAO, "b", "redirect")));
            first = a.get(10, TimeUnit.SECONDS);
            second = b.get(10, TimeUnit.SECONDS);
        }

        assertThat(users.findAll()).singleElement().satisfies(persisted -> {
            assertThat(persisted.getId()).isEqualTo(first.user().getId()).isEqualTo(second.user().getId());
            assertThat(persisted.getTokenVersion()).isZero();
            assertThat(persisted.getNickname()).isIn("nick-a", "nick-b");
        });
        assertThat(jwt.parse(first.token()).userId()).isEqualTo(jwt.parse(second.token()).userId());
        assertThat(jwt.parse(first.token()).tokenVersion()).isZero();
        assertThat(jwt.parse(second.token()).tokenVersion()).isZero();
    }

    @Test
    @DisplayName("동시 login profile refresh와 logout은 user row에서 직렬화돼 revocation이 되살아나지 않는다")
    void loginAndLogoutAreLinearizedOnUserRow() throws Exception {
        users.deleteAll();
        User existing = users.save(User.create(AuthProvider.KAKAO, "concurrent-user", null, "before", null));
        OAuthClient oauth = new OAuthClient() {
            @Override public AuthProvider provider() { return AuthProvider.KAKAO; }
            @Override public OAuthUserInfo exchange(String code, String redirectUri) {
                return new OAuthUserInfo("concurrent-user", "new@example.com", "after", null);
            }
        };
        CountDownLatch issueEntered = new CountDownLatch(1);
        CountDownLatch releaseLogin = new CountDownLatch(1);
        JwtService jwt = mock(JwtService.class);
        when(jwt.issue(any(User.class))).thenAnswer(invocation -> {
            issueEntered.countDown();
            if (!releaseLogin.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test timeout");
            return "token";
        });
        AuthService service = new AuthService(List.of(oauth), users, jwt);
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var login = executor.submit(() -> transactions.execute(status ->
                    service.login(AuthProvider.KAKAO, "code", "redirect")));
            assertThat(issueEntered.await(5, TimeUnit.SECONDS)).isTrue();

            CountDownLatch logoutStarted = new CountDownLatch(1);
            var logout = executor.submit(() -> transactions.executeWithoutResult(status -> {
                logoutStarted.countDown();
                service.logout(existing.getId());
            }));
            assertThat(logoutStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> logout.get(200, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);

            releaseLogin.countDown();
            assertThat(login.get(5, TimeUnit.SECONDS).user().getNickname()).isEqualTo("after");
            logout.get(5, TimeUnit.SECONDS);
        } finally {
            releaseLogin.countDown();
        }

        User persisted = users.findById(existing.getId()).orElseThrow();
        assertThat(persisted.getNickname()).isEqualTo("after");
        assertThat(persisted.getTokenVersion()).isEqualTo(1L);
    }
}
