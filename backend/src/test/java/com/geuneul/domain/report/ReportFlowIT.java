package com.geuneul.domain.report;

import com.geuneul.AbstractIntegrationTest;
import com.geuneul.domain.place.Place;
import com.geuneul.domain.place.PlaceCategory;
import com.geuneul.domain.place.PlaceRepository;
import com.geuneul.domain.photo.PhotoUploadService;
import com.geuneul.global.geo.GeoUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 제보 플로우 IT — 실 PostGIS에서 생성→조회→만료 제외를 엔드투엔드로 검증.
 * ⚠️ 레이트리미터가 실빈이므로 각 테스트는 X-Forwarded-For로 서로 다른 클라이언트 키를 쓴다
 * (같은 분 안에서 테스트끼리 분당 3회 창을 나눠 먹어 플래키해지는 것을 방지).
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = "geuneul.proxy-secret=test-proxy-secret")
class ReportFlowIT extends AbstractIntegrationTest {

    @MockitoBean
    PhotoUploadService photoUploadService;

    @Autowired
    MockMvc mvc;

    @Autowired
    ReportRepository reportRepository;

    @Autowired
    PlaceRepository placeRepository;

    private Long placeId;

    @BeforeEach
    void setUp() {
        reportRepository.deleteAll();
        placeRepository.deleteAll();
        Place saved = placeRepository.save(Place.of(
                "상도1동 무더위쉼터", PlaceCategory.COOLING_SHELTER, "서울 동작구 성대로 100",
                GeoUtils.point(37.4986, 126.9531), "test", "r-1"));
        placeId = saved.getId();
    }

    @Test
    @DisplayName("제보 생성 → 최근 제보 조회로 돌아온다 (TTL로 expires_at 자동 산정, photoUrl 왕복)")
    void createThenList() throws Exception {
        mvc.perform(post("/reports").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Forwarded-For", "198.51.100.1")
                        .content("{\"placeId\":" + placeId + ",\"reportType\":\"COOL\",\"comment\":\" 에어컨 좋아요 \","
                                + "\"photoUrl\":\"https://geuneul-photos.s3.ap-northeast-2.amazonaws.com/report/x.jpg\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.reportTypeLabel").value("시원해요"))
                .andExpect(jsonPath("$.comment").value("에어컨 좋아요"))  // 공백 정규화
                .andExpect(jsonPath("$.photoUrl").value("https://geuneul-photos.s3.ap-northeast-2.amazonaws.com/report/x.jpg"))
                .andExpect(jsonPath("$.expiresAt").exists());

        mvc.perform(get("/places/" + placeId + "/reports"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].reportType").value("COOL"))
                .andExpect(jsonPath("$[0].photoUrl").exists());

        assertThat(reportRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("만료된 제보는 최근 제보 조회에서 제외된다 (휘발성 규약)")
    void expiredReportsAreExcluded() throws Exception {
        reportRepository.save(Report.anonymous(placeId, ReportType.HOT, "만료된 제보", true,
                OffsetDateTime.now().minusHours(1)));
        reportRepository.save(Report.anonymous(placeId, ReportType.COOL, "유효한 제보", true,
                OffsetDateTime.now().plusHours(1)));

        mvc.perform(get("/places/" + placeId + "/reports"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].comment").value("유효한 제보"));
    }

    @Test
    @DisplayName("없는 장소로 제보하면 404 — 유령 장소에 제보가 쌓이지 않는다")
    void unknownPlaceIs404() throws Exception {
        mvc.perform(post("/reports").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Forwarded-For", "198.51.100.2")
                        .content("{\"placeId\":999999,\"reportType\":\"COOL\"}"))
                .andExpect(status().isNotFound());

        assertThat(reportRepository.count()).isZero();
    }

    @Test
    @DisplayName("같은 클라이언트의 연속 폭주는 429로 차단된다 (실빈 리미터 관통 검증)")
    void burstIsRateLimited() throws Exception {
        // 정확한 창 경계는 실시간이라 단정할 수 없으므로(분 경계에 걸릴 수 있음),
        // 분당 3회 창 2개에 걸쳐도 반드시 초과하는 7연속 요청으로 "최소 1회 429"를 단정한다.
        String body = "{\"placeId\":" + placeId + ",\"reportType\":\"BUG\"}";
        int tooMany = 0;
        for (int i = 0; i < 7; i++) {
            int status = mvc.perform(post("/reports").contentType(MediaType.APPLICATION_JSON)
                            .header("X-Forwarded-For", "198.51.100.3")
                            .content(body))
                    .andReturn().getResponse().getStatus();
            if (status == 429) tooMany++;
        }
        assertThat(tooMany).isGreaterThanOrEqualTo(1);
    }
}
