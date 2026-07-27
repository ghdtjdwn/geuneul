package com.geuneul.domain.photo;

import com.geuneul.domain.auth.JwtService;
import com.geuneul.domain.photo.dto.PhotoPresignRequest;
import com.geuneul.domain.photo.dto.PhotoPresignResponse;
import com.geuneul.domain.report.ProxyClientResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import static org.springframework.http.HttpStatus.TOO_MANY_REQUESTS;

/**
 * 사진 업로드 presign API (docs/SPEC.md §9 POST /photos/presign). 백엔드는 파일을 절대 거치지 않는다 —
 * 브라우저가 응답의 uploadUrl로 S3에 직접 PUT한다. 엔드포인트 자체는 permitAll(SecurityConfig)이다:
 * purpose=report는 익명 허용(제보 자체가 그렇다, §1), purpose=review는 PhotoService가 principal
 * 유무로 401을 낸다(POST /reviews와 동일 정책 — 컨트롤러 레벨에서 강제하면 report 익명 경로가 막힌다).
 *
 * <p>클라이언트 신원 해석은 ReportController와 동일하게 {@link ProxyClientResolver}를 재사용한다
 * (TS-008: XFF 최좌측 위조 우회를 막는 BFF 신뢰경계 로직 — 두 번째로 새로 만들 이유가 없다).
 */
@Tag(name = "Photos", description = "제보/후기 사진 업로드 — S3 presigned PUT URL 발급")
@RestController
public class PhotoController {

    private final PhotoService photoService;
    private final PhotoPresignRateLimiter rateLimiter;
    private final ProxyClientResolver clientResolver;

    public PhotoController(PhotoService photoService, PhotoPresignRateLimiter rateLimiter,
                           ProxyClientResolver clientResolver) {
        this.photoService = photoService;
        this.rateLimiter = rateLimiter;
        this.clientResolver = clientResolver;
    }

    @Operation(summary = "사진 업로드 presign 발급",
            description = "브라우저가 반환된 uploadUrl로 파일을 직접 PUT한다(서버는 파일을 거치지 않음). "
                    + "purpose=review는 로그인 필요(401), report는 익명 허용(남용 방어 레이트리밋 적용). "
                    + "최대 8MB, image/jpeg·png·webp만 허용 — presigned 서명에 타입·크기가 실려 위반 시 S3가 거부한다.")
    @PostMapping("/photos/presign")
    public PhotoPresignResponse presign(@Valid @RequestBody PhotoPresignRequest request,
                                        @AuthenticationPrincipal JwtService.AuthPrincipal principal,
                                        HttpServletRequest http) {
        String clientKey = clientResolver.resolve(http);
        if (!rateLimiter.tryAcquire(clientKey)) {
            throw new ResponseStatusException(TOO_MANY_REQUESTS, "사진 업로드 요청이 너무 잦아요. 잠시 후 다시 시도해 주세요.");
        }
        return photoService.presign(request, principal, clientKey);
    }
}
