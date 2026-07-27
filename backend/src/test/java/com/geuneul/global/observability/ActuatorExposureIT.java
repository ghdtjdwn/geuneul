package com.geuneul.global.observability;

import com.geuneul.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 관측성(P4, ADR-0014) — 기본 프로퍼티(application.yml)에서 actuator 노출 경계를 못 박는다.
 *
 * <p><b>배경</b>: 이 IT 도입 전에는 {@code management.endpoints.web.exposure.include}가
 * {@code health,info,prometheus}로 하드코딩돼 있어 /actuator/prometheus가 ALB를 통해 인증 없이
 * 프로덕션에 공개돼 있었다(2026-07 실측 확인, WORKLOG/TROUBLESHOOTING 기록). ADR-0014 이후
 * 기본값은 {@code health,info}뿐이라 ECS(MANAGEMENT_EXPOSURE 미설정)에서는 prometheus가 항상 404다.
 *
 * <p>health는 ALB 헬스체크(`/actuator/health`)가 계속 의존하므로 반드시 공개 유지, prometheus/env 등
 * 민감할 수 있는 엔드포인트는 옵트인이라야 한다는 계약을 CI가 지속적으로 지킨다.
 */
@AutoConfigureMockMvc
class ActuatorExposureIT extends AbstractIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Test
    @DisplayName("/actuator/health는 기본 설정에서 인증 없이 공개(ALB 헬스체크 의존)")
    void healthIsPubliclyExposedByDefault() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("/actuator/prometheus는 기본 설정(MANAGEMENT_EXPOSURE 미설정)에서 노출되지 않는다(404) — 프로덕션 안전 기본값")
    void prometheusIsNotExposedByDefault() throws Exception {
        mvc.perform(get("/actuator/prometheus")).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("/actuator/env는 화이트리스트(health,info[,prometheus])에 아예 없어 항상 404 — 민감정보 방어")
    void envIsNeverInTheAllowlist() throws Exception {
        mvc.perform(get("/actuator/env")).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Swagger UI는 기본 설정(SPRINGDOC_ENABLED 미설정)에서 노출되지 않는다")
    void swaggerUiIsNotExposedByDefault() throws Exception {
        mvc.perform(get("/swagger-ui.html")).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("OpenAPI 문서는 기본 설정(SPRINGDOC_ENABLED 미설정)에서 노출되지 않는다")
    void openApiDocsAreNotExposedByDefault() throws Exception {
        mvc.perform(get("/v3/api-docs")).andExpect(status().isNotFound());
    }
}
