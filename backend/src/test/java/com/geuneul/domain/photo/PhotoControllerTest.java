package com.geuneul.domain.photo;

import com.geuneul.domain.auth.JwtService;
import com.geuneul.domain.auth.Role;
import com.geuneul.domain.photo.dto.PhotoPresignResponse;
import com.geuneul.domain.report.ProxyClientResolver;
import com.geuneul.global.security.SecurityConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 컨트롤러 검증·인증 전파·레이트리밋 분기 단위 테스트 (MockMvc, S3 실호출 없음 — PhotoService는 목).
 * SecurityConfig를 명시 Import해 permitAll 슬라이스에서도 Authorization 헤더가 실제로 파싱되게 한다
 * (ReviewControllerTest가 확립한 패턴 — Boot 4 @WebMvcTest는 시큐리티 오토컨피그를 기본으로 안 끌어옴).
 */
@WebMvcTest(PhotoController.class)
@Import({SecurityConfig.class, ServletWebSecurityAutoConfiguration.class, SecurityFilterAutoConfiguration.class,
        ProxyClientResolver.class})
class PhotoControllerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    PhotoService photoService;

    @MockitoBean
    PhotoPresignRateLimiter rateLimiter;

    @MockitoBean
    JwtService jwtService;

    @BeforeEach
    void allowByDefault() {
        given(rateLimiter.tryAcquire(anyString())).willReturn(true);
    }

    private static PhotoPresignResponse sample() {
        return new PhotoPresignResponse(
                "https://bucket.s3.ap-northeast-2.amazonaws.com/report/x.jpg?X-Amz-Signature=abc",
                "https://bucket.s3.ap-northeast-2.amazonaws.com/report/x.jpg",
                "report/x.jpg",
                OffsetDateTime.now().plusMinutes(2));
    }

    @Test
    @DisplayName("정상 presign 요청(미인증)은 200 + 발급 정보를 돌려준다")
    void presignReturnsOk() throws Exception {
        given(photoService.presign(any(), isNull(), any())).willReturn(sample());

        mvc.perform(post("/photos/presign").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contentType\":\"image/jpeg\",\"contentLength\":2000000,\"purpose\":\"report\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.key").value("report/x.jpg"))
                .andExpect(jsonPath("$.uploadUrl").exists())
                .andExpect(jsonPath("$.objectUrl").exists());
    }

    @Test
    @DisplayName("contentType 누락이면 400 — 서비스까지 내려가지 않는다")
    void missingContentTypeIs400() throws Exception {
        mvc.perform(post("/photos/presign").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contentLength\":100}"))
                .andExpect(status().isBadRequest());

        then(photoService).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("contentLength 누락이면 400")
    void missingContentLengthIs400() throws Exception {
        mvc.perform(post("/photos/presign").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contentType\":\"image/jpeg\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("레이트리밋 초과면 429 — 서비스까지 내려가지 않는다")
    void rateLimitedIs429() throws Exception {
        given(rateLimiter.tryAcquire(anyString())).willReturn(false);

        mvc.perform(post("/photos/presign").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contentType\":\"image/jpeg\",\"contentLength\":100}"))
                .andExpect(status().isTooManyRequests());

        then(photoService).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("Authorization 헤더 없는 요청은 authenticated=false로 서비스에 전달된다 (report 익명 허용)")
    void anonymousPassesFalseToService() throws Exception {
        given(photoService.presign(any(), isNull(), any())).willReturn(sample());

        mvc.perform(post("/photos/presign").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contentType\":\"image/jpeg\",\"contentLength\":100,\"purpose\":\"report\"}"))
                .andExpect(status().isOk());

        then(photoService).should().presign(any(), isNull(), any());
    }

    @Test
    @DisplayName("유효 Bearer 토큰이면 authenticated=true로 서비스에 전달된다 (review 로그인 경로)")
    void authenticatedPassesTrueToService() throws Exception {
        given(jwtService.parse("valid-token")).willReturn(new JwtService.AuthPrincipal(1L, Role.USER));
        given(photoService.presign(any(), any(), any())).willReturn(sample());

        mvc.perform(post("/photos/presign")
                        .header("Authorization", "Bearer valid-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contentType\":\"image/jpeg\",\"contentLength\":100,\"purpose\":\"review\"}"))
                .andExpect(status().isOk());

        then(photoService).should().presign(any(), any(), any());
    }

    @Test
    @DisplayName("서비스가 401을 던지면(review 미인증) 그대로 전파된다")
    void serviceUnauthorizedPropagates() throws Exception {
        given(photoService.presign(any(), isNull(), any()))
                .willThrow(new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.UNAUTHORIZED, "로그인이 필요해요."));

        mvc.perform(post("/photos/presign").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contentType\":\"image/jpeg\",\"contentLength\":100,\"purpose\":\"review\"}"))
                .andExpect(status().isUnauthorized());
    }
}
