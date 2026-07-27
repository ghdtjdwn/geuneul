package com.geuneul.domain.flag;

import com.geuneul.domain.auth.JwtService;
import com.geuneul.domain.auth.Role;
import com.geuneul.domain.auth.User;
import com.geuneul.domain.auth.UserRepository;
import com.geuneul.domain.flag.dto.FlagPendingItemResponse;
import com.geuneul.domain.flag.dto.FlagPendingListResponse;
import com.geuneul.domain.flag.dto.FlagResponse;
import com.geuneul.global.security.SecurityConfig;
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
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 관리자 검수 큐 컨트롤러 슬라이스 — hasRole("ADMIN") 분기(비로그인 401, USER 403, ADMIN 200)를
 * 실 SecurityConfig 필터체인으로 검증한다(FlagControllerTest·ReviewControllerTest와 동일한
 * @Import 3종 패턴, TS-015).
 */
@WebMvcTest(AdminFlagController.class)
@Import({SecurityConfig.class, ServletWebSecurityAutoConfiguration.class, SecurityFilterAutoConfiguration.class})
class AdminFlagControllerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    FlagService flagService;

    @MockitoBean
    JwtService jwtService;

    @MockitoBean
    UserRepository userRepository;

    private void stubToken(String token, Role role) {
        given(jwtService.parse(token)).willReturn(new JwtService.AuthPrincipal(10L, role));
        User user = mock(User.class);
        given(user.getRole()).willReturn(role);
        given(user.getTokenVersion()).willReturn(0L);
        given(userRepository.findById(10L)).willReturn(Optional.of(user));
    }

    private static FlagPendingListResponse samplePage() {
        FlagPendingItemResponse item = new FlagPendingItemResponse(
                1L, "REPORT", 1L, "FALSE_INFO", "가짜 같아요", "PENDING", OffsetDateTime.now(),
                10L, true, "[제보] placeId=5 · 시원해요");
        return new FlagPendingListResponse(List.of(item), 0, 20, 1, false);
    }

    @Test
    @DisplayName("비로그인은 401 — 서비스까지 내려가지 않는다")
    void pendingUnauthenticatedIs401() throws Exception {
        mvc.perform(get("/admin/flags/pending"))
                .andExpect(status().isUnauthorized());

        then(flagService).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("일반 USER는 403")
    void pendingUserForbidden() throws Exception {
        stubToken("user-token", Role.USER);

        mvc.perform(get("/admin/flags/pending").header("Authorization", "Bearer user-token"))
                .andExpect(status().isForbidden());

        then(flagService).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("ADMIN은 200 + 대기 큐 목록(대상 요약 포함)")
    void pendingAdminOk() throws Exception {
        stubToken("admin-token", Role.ADMIN);
        given(flagService.pending(anyInt(), anyInt())).willReturn(samplePage());

        mvc.perform(get("/admin/flags/pending").header("Authorization", "Bearer admin-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.flags[0].targetSummary").value("[제보] placeId=5 · 시원해요"))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    @DisplayName("size는 최대 50으로 클램프된다")
    void sizeIsClampedToMax() throws Exception {
        stubToken("admin-token", Role.ADMIN);
        given(flagService.pending(anyInt(), anyInt())).willReturn(
                new FlagPendingListResponse(List.of(), 0, 50, 0, false));

        mvc.perform(get("/admin/flags/pending").header("Authorization", "Bearer admin-token").param("size", "500"))
                .andExpect(status().isOk());

        then(flagService).should().pending(0, 50);
    }

    @Test
    @DisplayName("신고 처리(resolve)도 비로그인 401, USER 403, ADMIN 200")
    void resolveRoleGating() throws Exception {
        mvc.perform(post("/admin/flags/1/resolve").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"RESOLVED\"}"))
                .andExpect(status().isUnauthorized());

        stubToken("user-token", Role.USER);
        mvc.perform(post("/admin/flags/1/resolve").header("Authorization", "Bearer user-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"RESOLVED\"}"))
                .andExpect(status().isForbidden());

        stubToken("admin-token", Role.ADMIN);
        given(flagService.resolve(eq(1L), org.mockito.ArgumentMatchers.any())).willReturn(
                new FlagResponse(1L, "REPORT", 1L, "SPAM", null, "RESOLVED", OffsetDateTime.now(), OffsetDateTime.now()));

        mvc.perform(post("/admin/flags/1/resolve").header("Authorization", "Bearer admin-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"RESOLVED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"));
    }
}
