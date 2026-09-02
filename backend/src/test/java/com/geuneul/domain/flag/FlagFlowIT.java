package com.geuneul.domain.flag;

import com.geuneul.AbstractIntegrationTest;
import com.geuneul.domain.auth.AuthProvider;
import com.geuneul.domain.auth.JwtService;
import com.geuneul.domain.auth.Role;
import com.geuneul.domain.auth.User;
import com.geuneul.domain.auth.UserRepository;
import com.geuneul.domain.place.Place;
import com.geuneul.domain.place.PlaceCategory;
import com.geuneul.domain.place.PlaceRepository;
import com.geuneul.domain.report.Report;
import com.geuneul.domain.report.ReportRepository;
import com.geuneul.domain.report.ReportType;
import com.geuneul.domain.review.Review;
import com.geuneul.domain.review.ReviewRepository;
import com.geuneul.global.geo.GeoUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 모더레이션(신고+검수 큐) 플로우 IT — 실 PostGIS + Spring Security 필터체인에서 접수→중복409→
 * 관리자 큐 조회(403/200)→처리(resolve)를 엔드투엔드로 검증한다(ReviewFlowIT와 동일 패턴, TS-009/TS-015).
 * 로컬은 colima Docker API 협상 이슈로 skip될 수 있다 — CI(표준 Docker)가 최종 게이트.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = "jwt.secret=geuneul-flag-it-test-secret-0123456789ab") // gitleaks:allow (테스트 전용 더미)
class FlagFlowIT extends AbstractIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    FlagRepository flagRepository;

    @Autowired
    ReportRepository reportRepository;

    @Autowired
    ReviewRepository reviewRepository;

    @Autowired
    PlaceRepository placeRepository;

    @Autowired
    UserRepository userRepository;

    @Autowired
    JwtService jwtService;

    private Long reportId;
    private String userToken;
    private String adminToken;

    @BeforeEach
    void setUp() {
        flagRepository.deleteAll();
        reportRepository.deleteAll();
        reviewRepository.deleteAll();
        placeRepository.deleteAll();
        userRepository.deleteAll();

        Place place = placeRepository.save(Place.of(
                "상도1동 무더위쉼터", PlaceCategory.COOLING_SHELTER, "서울 동작구 성대로 100",
                GeoUtils.point(37.4986, 126.9531), "test", "flag-1"));

        Report report = reportRepository.save(Report.anonymous(
                place.getId(), ReportType.HOT, "에어컨이 꺼져있어요(허위)", true, null));
        reportId = report.getId();

        User reporter = userRepository.save(
                User.create(AuthProvider.KAKAO, "kakao-flag-user", "u@test.com", "신고자", null));
        userToken = jwtService.issue(reporter);

        User admin = userRepository.save(
                User.create(AuthProvider.GOOGLE, "google-flag-admin", "a@test.com", "관리자", null));
        setRole(admin, Role.ADMIN);
        admin = userRepository.save(admin); // ADMIN은 DB role이 진실 — 필터가 토큰 role을 DB로 재확인한다
        adminToken = jwtService.issue(admin);
    }

    // User.role의 정상 승격 경로는 현재 권한 모델 범위 밖이라 테스트 전용 리플렉션으로 ADMIN을
    // 부여한다(ReviewServiceTest의 setId 리플렉션 패턴과 동일 근거 — 엔티티 프로덕션 계약은 안 바꾼다).
    private static void setRole(User user, Role role) {
        try {
            Field f = User.class.getDeclaredField("role");
            f.setAccessible(true);
            f.set(user, role);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @DisplayName("신고 접수 → 같은 유저가 같은 대상을 다시 신고하면 409")
    void createThenDuplicateIsConflict() throws Exception {
        mvc.perform(post("/flags")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetType\":\"REPORT\",\"targetId\":" + reportId
                                + ",\"reason\":\"FALSE_INFO\",\"detail\":\"허위 같아요\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"));

        mvc.perform(post("/flags")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetType\":\"REPORT\",\"targetId\":" + reportId + ",\"reason\":\"SPAM\"}"))
                .andExpect(status().isConflict());

        assertThat(flagRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("없는 대상을 신고하면 404")
    void unknownTargetIs404() throws Exception {
        mvc.perform(post("/flags")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetType\":\"REPORT\",\"targetId\":999999,\"reason\":\"SPAM\"}"))
                .andExpect(status().isNotFound());

        assertThat(flagRepository.count()).isZero();
    }

    @Test
    @DisplayName("Authorization 헤더 없이 신고하면 401")
    void unauthenticatedPostIs401() throws Exception {
        mvc.perform(post("/flags").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetType\":\"REPORT\",\"targetId\":" + reportId + ",\"reason\":\"SPAM\"}"))
                .andExpect(status().isUnauthorized());

        assertThat(flagRepository.count()).isZero();
    }

    @Test
    @DisplayName("관리자 검수 큐: 비로그인 401, 일반 유저 403, 관리자는 200 + 대상 요약")
    void adminPendingQueueRoleGating() throws Exception {
        mvc.perform(post("/flags")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetType\":\"REPORT\",\"targetId\":" + reportId + ",\"reason\":\"FALSE_INFO\"}"))
                .andExpect(status().isCreated());

        mvc.perform(get("/admin/flags/pending"))
                .andExpect(status().isUnauthorized());

        mvc.perform(get("/admin/flags/pending").header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());

        mvc.perform(get("/admin/flags/pending").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.flags[0].status").value("PENDING"))
                .andExpect(jsonPath("$.flags[0].targetExists").value(true))
                .andExpect(jsonPath("$.flags[0].targetSummary").isNotEmpty());
    }

    @Test
    @DisplayName("관리자가 신고를 처리(resolve)하면 PENDING 큐에서 사라지고, 재처리는 409")
    void adminResolvesFlag() throws Exception {
        mvc.perform(post("/flags")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetType\":\"REPORT\",\"targetId\":" + reportId + ",\"reason\":\"SPAM\"}"))
                .andExpect(status().isCreated());
        long flagId = flagRepository.findAll().get(0).getId();

        mvc.perform(post("/admin/flags/" + flagId + "/resolve")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"RESOLVED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"))
                .andExpect(jsonPath("$.resolvedAt").isNotEmpty());

        mvc.perform(get("/admin/flags/pending").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));

        mvc.perform(post("/admin/flags/" + flagId + "/resolve")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DISMISSED\"}"))
                .andExpect(status().isConflict());
    }
}
