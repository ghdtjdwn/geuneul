package com.geuneul.domain.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/**
 * 소셜 로그인 사용자 (ERD §8, 테이블은 V2에서 생성됨 — 이 엔티티는 매핑만; ddl-auto=validate).
 *
 * - 자연키는 (provider, provider_id) — 재로그인 시 이 쌍으로 조회해 upsert(중복 계정 방지).
 * - trust_score(0~100, double): 제보 신뢰도 가중에 쓰인다(V4 place_report_signals 뷰가
 *   0.7 + 0.3*min(trust/100,1)로 로그인 제보를 가산). 신규 유저는 0(익명과 동일 기저)에서 시작해
 *   좋은 제보로 쌓는다 — 산출식은 {@link TrustScore}, 재계산 오케스트레이션은
 *   {@link TrustScoreService}(제보/후기 작성 시 온디맨드 호출, P2 trust_score 계산).
 * - email은 nullable — 카카오는 이메일 동의(비즈 인증)가 없으면 안 오므로 provider_id로만 식별한다.
 */
@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private AuthProvider provider;

    @Column(name = "provider_id", nullable = false, length = 128)
    private String providerId;

    @Column(length = 255)
    private String email;

    @Column(nullable = false, length = 64)
    private String nickname;

    @Column(name = "profile_image", length = 512)
    private String profileImage;

    @Column(name = "trust_score", nullable = false)
    private double trustScore;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Role role = Role.USER;

    /** Incremented on logout so every previously issued JWT becomes invalid across all server instances. */
    @Column(name = "token_version", nullable = false)
    private long tokenVersion;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected User() {
    }

    /** 최초 로그인 시 신규 사용자 생성. trust_score/role/created_at은 DB 기본값·기저에서 시작. */
    public static User create(AuthProvider provider, String providerId, String email,
                              String nickname, String profileImage) {
        User u = new User();
        u.provider = provider;
        u.providerId = providerId;
        u.email = email;
        u.nickname = nickname;
        u.profileImage = profileImage;
        u.trustScore = 0;
        u.role = Role.USER;
        u.tokenVersion = 0;
        return u;
    }

    /**
     * trust_score 재계산 결과 반영. {@link com.geuneul.domain.auth.TrustScoreService}만 호출한다 —
     * 클라이언트 요청으로 직접 지정 불가(신원 위조 방지, ReviewService.create의 userId 패턴과 동일 원칙).
     */
    public void updateTrustScore(double trustScore) {
        this.trustScore = trustScore;
    }

    /** 재로그인 시 프로필 최신화(닉네임/이미지/이메일이 바뀌었을 수 있음). trust/role은 건드리지 않는다. */
    public void refreshProfile(String email, String nickname, String profileImage) {
        if (email != null) {
            this.email = email;
        }
        if (nickname != null && !nickname.isBlank()) {
            this.nickname = nickname;
        }
        if (profileImage != null) {
            this.profileImage = profileImage;
        }
    }

    public Long getId() {
        return id;
    }

    public AuthProvider getProvider() {
        return provider;
    }

    public String getProviderId() {
        return providerId;
    }

    public String getEmail() {
        return email;
    }

    public String getNickname() {
        return nickname;
    }

    public String getProfileImage() {
        return profileImage;
    }

    public double getTrustScore() {
        return trustScore;
    }

    public Role getRole() {
        return role;
    }

    public long getTokenVersion() {
        return tokenVersion;
    }

    public void revokeSessions() {
        tokenVersion = Math.addExact(tokenVersion, 1);
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
