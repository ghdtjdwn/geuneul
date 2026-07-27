package com.geuneul.domain.report;

import com.geuneul.domain.auth.JwtService;
import com.geuneul.domain.auth.Role;
import com.geuneul.domain.report.dto.ReportResponse;
import com.geuneul.global.security.SecurityConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 컨트롤러 검증·리밋·선택적 인증 분기 단위 테스트 (MockMvc, DB 불필요) —
 * 저장·만료 필터링의 실제 동작은 ReportFlowIT에서 실 PostGIS로 검증한다.
 * SecurityConfig를 Import해 POST /reports가 permitAll이면서도 토큰이 있으면 principal이
 * 채워지는 것(ReviewControllerTest와 동일한 Boot 4 @WebMvcTest 패턴)을 실제로 검증한다.
 */
@WebMvcTest(ReportController.class)
@Import({ProxyClientResolver.class, // 실 리졸버로 XFF→키 유도까지 검증(시크릿 미설정)
        SecurityConfig.class, ServletWebSecurityAutoConfiguration.class, SecurityFilterAutoConfiguration.class})
class ReportControllerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    ReportService reportService;

    @MockitoBean
    ReportRateLimiter rateLimiter;

    @MockitoBean
    JwtService jwtService;

    @BeforeEach
    void allowByDefault() {
        given(rateLimiter.tryAcquire(anyString())).willReturn(true);
    }

    private static ReportResponse sample() {
        return new ReportResponse(1L, 1L, "COOL", "시원해요", null, null, true, false,
                OffsetDateTime.now(), OffsetDateTime.now().plusHours(3));
    }

    @Test
    @DisplayName("정상 제보는 201 Created + 본문을 돌려준다 (비로그인 — principal null)")
    void createReturns201() throws Exception {
        given(reportService.create(any(), any(), any())).willReturn(sample());

        mvc.perform(post("/reports").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"placeId\":1,\"reportType\":\"COOL\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.reportType").value("COOL"))
                .andExpect(jsonPath("$.reportTypeLabel").value("시원해요"));

        then(reportService).should().create(isNull(), any(), any());
    }

    @Test
    @DisplayName("Authorization 헤더가 있으면 principal이 채워져 서비스로 전달된다 (선택적 인증, trust_score 가중 대상)")
    void createWithAuthAttachesPrincipal() throws Exception {
        given(jwtService.parse("valid-token")).willReturn(new JwtService.AuthPrincipal(10L, Role.USER));
        given(reportService.create(any(), any(), any())).willReturn(sample());

        mvc.perform(post("/reports")
                        .header("Authorization", "Bearer valid-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"placeId\":1,\"reportType\":\"COOL\"}"))
                .andExpect(status().isCreated());

        ArgumentCaptor<JwtService.AuthPrincipal> principal = ArgumentCaptor.forClass(JwtService.AuthPrincipal.class);
        then(reportService).should().create(principal.capture(), any(), any());
        assertThat(principal.getValue().userId()).isEqualTo(10L);
    }

    @Test
    @DisplayName("무효한 토큰이어도 POST /reports는 permitAll이라 401이 아니라 익명(principal null)으로 통과한다")
    void invalidTokenFallsBackToAnonymous() throws Exception {
        given(jwtService.parse("bad-token")).willThrow(new io.jsonwebtoken.security.SignatureException("bad"));
        given(reportService.create(any(), any(), any())).willReturn(sample());

        mvc.perform(post("/reports")
                        .header("Authorization", "Bearer bad-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"placeId\":1,\"reportType\":\"COOL\"}"))
                .andExpect(status().isCreated());

        then(reportService).should().create(isNull(), any(), any());
    }

    @Test
    @DisplayName("reportType 누락이면 400")
    void missingTypeIs400() throws Exception {
        mvc.perform(post("/reports").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"placeId\":1}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("정의되지 않은 reportType 값이면 400")
    void unknownTypeIs400() throws Exception {
        mvc.perform(post("/reports").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"placeId\":1,\"reportType\":\"LAVA\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("코멘트 120자 초과면 400")
    void tooLongCommentIs400() throws Exception {
        String longComment = "가".repeat(121);
        mvc.perform(post("/reports").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"placeId\":1,\"reportType\":\"COOL\",\"comment\":\"" + longComment + "\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("photoUrl이 https://로 시작하지 않으면 400 (POST /photos/presign 결과만 수용)")
    void nonHttpsPhotoUrlIs400() throws Exception {
        mvc.perform(post("/reports").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"placeId\":1,\"reportType\":\"COOL\",\"photoUrl\":\"javascript:alert(1)\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("photoUrl 미지정이면 정상 통과(선택 필드)")
    void missingPhotoUrlIsOptional() throws Exception {
        given(reportService.create(any(), any(), any())).willReturn(sample());

        mvc.perform(post("/reports").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"placeId\":1,\"reportType\":\"COOL\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("레이트리밋 초과면 429 — 서비스까지 내려가지 않는다")
    void rateLimitedIs429() throws Exception {
        given(rateLimiter.tryAcquire(anyString())).willReturn(false);

        mvc.perform(post("/reports").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"placeId\":1,\"reportType\":\"COOL\"}"))
                .andExpect(status().isTooManyRequests());

        then(reportService).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("시크릿 미설정 시 전달 XFF를 무시하고 TCP peer key로 fail-safe 처리한다")
    void missingSecretUsesPeerKey() throws Exception {
        given(reportService.create(any(), any(), any())).willReturn(sample());

        mvc.perform(post("/reports").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Forwarded-For", "203.0.113.7, 10.0.0.1")
                        .content("{\"placeId\":1,\"reportType\":\"COOL\"}"))
                .andExpect(status().isCreated());

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        then(rateLimiter).should().tryAcquire(key.capture());
        assertThat(key.getValue()).isEqualTo("x:127.0.0.1");
    }

    @Test
    @DisplayName("장소별 최근 제보 GET은 200 + 배열")
    void recentReportsOk() throws Exception {
        given(reportService.recentByPlace(anyLong())).willReturn(List.of(sample()));

        mvc.perform(get("/places/1/reports"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].reportTypeLabel").value("시원해요"));
    }
}
