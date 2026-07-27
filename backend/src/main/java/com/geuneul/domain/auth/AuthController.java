package com.geuneul.domain.auth;

import com.geuneul.domain.auth.dto.AuthResponse;
import com.geuneul.domain.auth.dto.LoginRequest;
import com.geuneul.domain.auth.dto.UserResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * 인증 API (docs/SPEC.md §9). 소셜 로그인은 프론트 BFF가 받은 인가 코드를 서버에서 교환한다.
 * /me는 JWT 필요(SecurityConfig에서 인증 요구), 나머지 /auth/**는 공개.
 */
@Tag(name = "Auth", description = "소셜 로그인(카카오/구글) + JWT 세션")
@RestController
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @Operation(summary = "카카오 로그인", description = "인가 코드(code)+redirectUri를 서버에서 교환 → JWT 발급")
    @PostMapping("/auth/kakao")
    public AuthResponse kakao(@Valid @RequestBody LoginRequest request) {
        return AuthResponse.of(authService.login(AuthProvider.KAKAO, request.code(), request.redirectUri()));
    }

    @Operation(summary = "구글 로그인", description = "인가 코드(code)+redirectUri를 서버에서 교환 → JWT 발급")
    @PostMapping("/auth/google")
    public AuthResponse google(@Valid @RequestBody LoginRequest request) {
        return AuthResponse.of(authService.login(AuthProvider.GOOGLE, request.code(), request.redirectUri()));
    }

    @Operation(summary = "내 프로필", description = "Authorization: Bearer {JWT} 필요. 신뢰도·역할 포함.")
    @GetMapping("/me")
    public UserResponse me(@AuthenticationPrincipal JwtService.AuthPrincipal principal) {
        return UserResponse.of(authService.getById(principal.userId()));
    }

    @Operation(summary = "로그아웃", description = "현재 사용자의 token_version을 올려 기존 JWT를 서버에서 즉시 폐기한다.")
    @PostMapping("/auth/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@AuthenticationPrincipal JwtService.AuthPrincipal principal) {
        authService.logout(principal.userId());
    }
}
