package com.geuneul.domain.flag.dto;

import com.geuneul.domain.flag.FlagReason;
import com.geuneul.domain.flag.FlagTargetType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 신고 접수 요청. reporterId는 바디로 받지 않고 JWT(AuthPrincipal)에서 취해 신원 위조를 막는다.
 * ReviewCreateRequest와 동일한 docs/SPEC.md 신원 계약을 따른다.
 */
@Schema(description = "신고 접수 요청 — 로그인 필요")
public record FlagCreateRequest(
        @Schema(description = "신고 대상 종류", example = "REPORT")
        @NotNull(message = "targetType은 필수입니다")
        FlagTargetType targetType,

        @Schema(description = "신고 대상 ID(report.id 또는 review.id)", example = "1")
        @NotNull(message = "targetId는 필수입니다")
        Long targetId,

        @Schema(description = "신고 사유", example = "FALSE_INFO")
        @NotNull(message = "reason은 필수입니다")
        FlagReason reason,

        @Schema(description = "자유 텍스트(선택, 최대 500자)", example = "가짜 제보로 보여요")
        @Size(max = 500, message = "detail은 500자 이하여야 합니다")
        String detail
) {
}
