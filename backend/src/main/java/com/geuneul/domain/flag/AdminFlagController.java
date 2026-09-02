package com.geuneul.domain.flag;

import com.geuneul.domain.flag.dto.FlagPendingListResponse;
import com.geuneul.domain.flag.dto.FlagResolveRequest;
import com.geuneul.domain.flag.dto.FlagResponse;
import com.geuneul.global.web.ApiRequests;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자 검수 큐 API (docs/SPEC.md §0-7, §9 GET /admin/flags/pending). ADMIN 전용 —
 * SecurityConfig의 {@code /admin/**}가 {@code hasRole("ADMIN")}으로 보호한다(비로그인 401, 비관리자 403).
 * resolve는 최소 처리 엔드포인트만 제공한다(과설계 방지 — 장소 병합·
 * 데이터 수동 보정 등 나머지 관리자 기능은 2차 스코프).
 */
@Tag(name = "Admin", description = "신고 검수 큐 — ADMIN 전용")
@RestController
public class AdminFlagController {

    static final int MAX_PAGE_SIZE = 50;

    private final FlagService flagService;

    public AdminFlagController(FlagService flagService) {
        this.flagService = flagService;
    }

    @Operation(summary = "대기중 신고 목록 (ADMIN 전용)",
            description = "PENDING 상태 신고를 오래된 순으로, 대상 요약과 함께 페이지네이션 반환.")
    @GetMapping("/admin/flags/pending")
    public FlagPendingListResponse pending(@RequestParam(defaultValue = "0") int page,
                                           @RequestParam(defaultValue = "20") int size) {
        return flagService.pending(Math.max(page, 0), ApiRequests.clampLimit(size, MAX_PAGE_SIZE));
    }

    @Operation(summary = "신고 목록 상태별 조회 (ADMIN 전용)",
            description = "status(PENDING|RESOLVED|DISMISSED)별 목록 — 처리 이력 확인용. 기본 PENDING.")
    @GetMapping("/admin/flags")
    public FlagPendingListResponse byStatus(
            @RequestParam(defaultValue = "PENDING") FlagStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return flagService.byStatus(status, Math.max(page, 0), ApiRequests.clampLimit(size, MAX_PAGE_SIZE));
    }

    @Operation(summary = "신고 처리 (ADMIN 전용)",
            description = "PENDING 신고를 RESOLVED/DISMISSED로 전이. RESOLVED면 대상 콘텐츠를 숨긴다. 이미 처리된 신고는 409.")
    @PostMapping("/admin/flags/{id}/resolve")
    public FlagResponse resolve(@PathVariable long id, @Valid @RequestBody FlagResolveRequest request) {
        return flagService.resolve(id, request);
    }
}
