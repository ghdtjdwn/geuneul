package com.geuneul.domain.report;

import com.geuneul.domain.auth.JwtService;
import com.geuneul.domain.report.dto.PopularTimesSlot;
import com.geuneul.domain.report.dto.ReportCreateRequest;
import com.geuneul.domain.report.dto.ReportResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.springframework.http.HttpStatus.TOO_MANY_REQUESTS;

/**
 * 휘발성 제보 API (docs/SPEC.md §9). 익명 제보 허용 + 장소별 최근 제보 조회.
 * POST /reports는 permitAll(SecurityConfig)이라 로그인은 선택이다 — Authorization 헤더가 있으면
 * {@code principal}이 채워져 trust_score 가중 대상이 되고(P2), 없으면 기존과 동일하게 완전 익명이다.
 * 사진은 presign claim의 소유자·용도와 S3 업로드 완료를 저장 전에 검증한다. 신고 큐는 후속.
 */
@Tag(name = "Reports", description = "휘발성 상태 제보 — 익명 허용, 로그인 시 신뢰도 가중, expires_at 지나면 제외")
@RestController
public class ReportController {

    private final ReportService reportService;
    private final ReportRateLimiter rateLimiter;
    private final ProxyClientResolver clientResolver;

    public ReportController(ReportService reportService, ReportRateLimiter rateLimiter,
                            ProxyClientResolver clientResolver) {
        this.reportService = reportService;
        this.rateLimiter = rateLimiter;
        this.clientResolver = clientResolver;
    }

    @Operation(summary = "제보 생성 (익명 허용, 로그인 시 신뢰도 가중)",
            description = "지금 상태를 제보한다. 타입별 TTL로 expires_at이 자동 산정된다. "
                    + "Authorization: Bearer {JWT}가 있으면 trust_score 가중 대상이 된다(선택). "
                    + "남용 방어: 클라이언트당 분당 3회·시간당 10회(초과 시 429).")
    @PostMapping("/reports")
    @ResponseStatus(HttpStatus.CREATED)
    public ReportResponse create(@AuthenticationPrincipal JwtService.AuthPrincipal principal,
                                 @Valid @RequestBody ReportCreateRequest request, HttpServletRequest http) {
        String clientKey = clientResolver.resolve(http);
        if (!rateLimiter.tryAcquire(clientKey)) {
            throw new ResponseStatusException(TOO_MANY_REQUESTS, "제보가 너무 잦아요. 잠시 후 다시 시도해 주세요.");
        }
        return reportService.create(principal, request, clientKey);
    }

    @Operation(summary = "장소의 최근 제보", description = "유효(미만료) 제보 최신순 최대 20개.")
    @GetMapping("/places/{placeId}/reports")
    public List<ReportResponse> recent(@PathVariable long placeId) {
        return reportService.recentByPlace(placeId);
    }

    @Operation(summary = "장소의 시간대별 혼잡 파생 (자체 popular-times)",
            description = "제보 이력을 KST 요일(0=일~6=토)×시간(0~23)으로 집계한 혼잡 패턴. 외부 API 없이 UGC로 유도. "
                    + "만료 제보도 포함(과거 이력 채굴). 제보가 있는 슬롯만 반환한다.")
    @GetMapping("/places/{placeId}/popular-times")
    public List<PopularTimesSlot> popularTimes(@PathVariable long placeId) {
        return reportService.popularTimes(placeId);
    }
}
