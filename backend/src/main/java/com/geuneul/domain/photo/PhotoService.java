package com.geuneul.domain.photo;

import com.geuneul.domain.auth.JwtService;
import com.geuneul.domain.photo.dto.PhotoPresignRequest;
import com.geuneul.domain.photo.dto.PhotoPresignResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.UNAUTHORIZED;

/**
 * 제보/후기 사진 업로드 — S3 presigned PUT URL 발급(docs/SPEC.md §7·§9 POST /photos/presign).
 * 백엔드는 파일 바이트를 거치지 않는다. 브라우저는 이 서비스가 서명한 URL로 PUT하며, CORS를 제공하지
 * 않는 저장소에서는 자격증명 없는 고정 upstream Caddy gateway가 Host를 원래 endpoint로 복원해 전달한다.
 *
 * <p>제약(발급 시 계약하고 UGC 저장 직전에 S3 HEAD로 다시 검증한다):
 * <ul>
 *   <li><b>타입</b>: {@link #ALLOWED_CONTENT_TYPES} 화이트리스트만. 서명된 Content-Type 헤더라
 *       브라우저가 실제 PUT에서 다른 타입을 보내면 S3가 서명 불일치로 거부한다.</li>
 *   <li><b>크기</b>: {@link #MAX_UPLOAD_BYTES} 초과면 presign 자체를 거부. 서명에 Content-Length를
 *       실어(PutObjectRequest#contentLength) 실제 PUT이 다른 크기를 보내면 마찬가지로 거부된다.</li>
 *   <li><b>불변성</b>: {@code If-None-Match: *}를 서명해 이미 생성된 key의 덮어쓰기를 S3가 412로 거부한다.</li>
 * </ul>
 * purpose=REVIEW는 후기(§9 POST /reviews)와 동일하게 로그인을 요구한다(휴대폰 카메라로 즉시 찍는
 * 제보와 달리 후기는 이미 로그인 UX를 통과한 뒤라 마찰 비용이 없다 — WORKLOG 근거).
 */
@Service
public class PhotoService {

    /** MIME 타입 → 확장자. 휴대폰 카메라/일반 이미지 편집기가 흔히 만드는 3종으로 제한(HEIC 등은 클라 변환 후 업로드). */
    static final Map<String, String> ALLOWED_CONTENT_TYPES = Map.of(
            "image/jpeg", "jpg",
            "image/png", "png",
            "image/webp", "webp"
    );

    /** 8MB — 휴대폰 사진 1장 기준 여유치. 발급 계약과 업로드 완료 후 S3 HEAD에서 모두 확인한다. */
    static final long MAX_UPLOAD_BYTES = 8L * 1024 * 1024;

    /** 짧게: presign 발급 직후 바로 업로드하는 흐름이라 길게 열어둘 이유가 없다(탈취된 URL의 악용 창 최소화). */
    private static final Duration SIGNATURE_DURATION = Duration.ofMinutes(2);

    /**
     * 조회용 presigned GET 유효기간(N1). 응답 JSON에 실려 클라이언트가 즉시 &lt;img&gt;로 렌더하고,
     * React Query가 상세 화면을 몇 분 캐시하므로 그 창을 넉넉히 덮되 탈취 시 악용 창은 짧게 유지하는 절충값.
     * 커먼스 UGC 사진이라 민감도는 낮지만, 저장 URL을 그대로 공개하지 않는다는 원칙(비공개 버킷 s3.tf)은 지킨다.
     */
    private static final Duration VIEW_SIGNATURE_DURATION = Duration.ofHours(1);

    private final S3Presigner presigner;
    private final String bucket;
    private final String objectBaseUrl;
    private final String browserUploadProxyBaseUrl;
    private final Set<String> acceptedObjectBaseUrls;
    private final Clock clock;
    private final PhotoUploadService uploadService;

    @Autowired
    public PhotoService(S3Presigner presigner,
                        @Value("${aws.s3.bucket:}") String bucket,
                        @Value("${aws.s3.region:ap-northeast-2}") String region,
                        @Value("${aws.s3.endpoint:}") String endpoint,
                        @Value("${aws.s3.public-base-url:}") String publicBaseUrl,
                        @Value("${aws.s3.legacy-base-urls:}") String legacyBaseUrls,
                        @Value("${aws.s3.browser-upload-proxy-base-url:}") String browserUploadProxyBaseUrl,
                        Clock clock, PhotoUploadService uploadService) {
        this.presigner = presigner;
        this.bucket = bucket;
        this.objectBaseUrl = resolveObjectBaseUrl(bucket, region, endpoint, publicBaseUrl);
        this.browserUploadProxyBaseUrl = normalizeBrowserUploadProxyBaseUrl(browserUploadProxyBaseUrl);
        this.acceptedObjectBaseUrls = acceptedBaseUrls(this.objectBaseUrl, legacyBaseUrls);
        this.clock = clock;
        this.uploadService = uploadService;
    }

    /** 기존 단위 테스트와 임베딩 호출의 AWS 기본 동작을 보존한다. Spring은 위 생성자를 사용한다. */
    public PhotoService(S3Presigner presigner, String bucket, String region,
                        Clock clock, PhotoUploadService uploadService) {
        this(presigner, bucket, region, "", "", "", "", clock, uploadService);
    }

    /** purpose=REVIEW는 인증 주체가 필수이며, 발급 결과는 서버 upload claim에 함께 기록한다. */
    public PhotoPresignResponse presign(PhotoPresignRequest request, JwtService.AuthPrincipal principal,
                                        String clientKey) {
        String extension = ALLOWED_CONTENT_TYPES.get(request.contentType());
        if (extension == null) {
            throw new ResponseStatusException(BAD_REQUEST,
                    "지원하지 않는 이미지 타입이에요. image/jpeg, image/png, image/webp만 업로드할 수 있어요.");
        }
        if (request.contentLength() > MAX_UPLOAD_BYTES) {
            throw new ResponseStatusException(BAD_REQUEST,
                    "사진이 너무 커요. 최대 " + (MAX_UPLOAD_BYTES / 1024 / 1024) + "MB까지 업로드할 수 있어요.");
        }

        PhotoPurpose purpose = PhotoPurpose.fromValue(request.purpose());
        if (purpose == PhotoPurpose.REVIEW && principal == null) {
            throw new ResponseStatusException(UNAUTHORIZED, "후기 사진은 로그인 후 업로드할 수 있어요.");
        }
        if (!StringUtils.hasText(bucket)) {
            throw new IllegalStateException(
                    "S3_BUCKET_NAME이 설정되지 않았습니다. 규칙 D: env/SSM으로만 주입.");
        }

        String key = purpose.prefix() + "/" + UUID.randomUUID() + "." + extension;

        PutObjectRequest objectRequest = PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .contentType(request.contentType())
                .contentLength(request.contentLength())
                // Signed conditional create: a still-valid URL cannot overwrite the object after claim validation.
                .ifNoneMatch("*")
                .build();

        PutObjectPresignRequest presignRequest = PutObjectPresignRequest.builder()
                .signatureDuration(SIGNATURE_DURATION)
                .putObjectRequest(objectRequest)
                .build();

        PresignedPutObjectRequest presigned = presigner.presignPutObject(presignRequest);

        // 버킷은 비공개(퍼블릭 액세스 블록) — 오브젝트 URL을 그대로 저장한다(스키마 기존 photo_url/photos_json 재사용).
        // 실제 뷰잉은 읽기 시점에 presignGet()으로 임시 GET 서명을 발급한다(N1, 버킷 퍼블릭 전환 없이 403 해소).
        String objectUrl = objectUrlFor(key);
        OffsetDateTime expiresAt = OffsetDateTime.now(clock).plus(SIGNATURE_DURATION);
        uploadService.register(key, objectUrl, purpose, principal, clientKey,
                request.contentType(), request.contentLength());

        return new PhotoPresignResponse(browserUploadUrl(presigned), objectUrl, key, expiresAt);
    }

    /**
     * 저장된 비공개 S3 오브젝트 URL을 조회 시점 presigned GET URL로 변환한다(N1 — 사진 표시 버그의 공유 수정).
     * 버킷이 완전 비공개(s3.tf 퍼블릭 차단·정책 없음)라 raw 오브젝트 URL은 {@code <img>}에서 403 → 읽기 경로에서
     * 짧게 서명된 임시 GET URL을 발급해 내려준다. presign은 로컬 서명 연산이라 네트워크 호출이 없다(저비용).
     *
     * <p>우리 버킷 오브젝트가 아니거나(레거시·외부 URL) 버킷이 미설정(단위/IT 환경)이면 <b>원본을 그대로</b>
     * 돌려준다 — 손대지 않아 무해하다(테스트가 저장한 임의 URL·빈 버킷에서 계약이 안 깨진다).
     */
    public String presignGet(String storedUrl) {
        if (!StringUtils.hasText(storedUrl) || !StringUtils.hasText(bucket)) {
            return storedUrl;
        }
        Optional<String> key = objectKeyFrom(storedUrl);
        if (key.isEmpty()) {
            return storedUrl;
        }
        GetObjectRequest getRequest = GetObjectRequest.builder()
                .bucket(bucket)
                .key(key.get())
                .build();
        GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                .signatureDuration(VIEW_SIGNATURE_DURATION)
                .getObjectRequest(getRequest)
                .build();
        return presigner.presignGetObject(presignRequest).url().toExternalForm();
    }

    /** 목록/후기 사진 배열용 — null·빈 리스트 안전, 각 원소를 presignGet으로 변환. */
    public List<String> presignGet(List<String> storedUrls) {
        if (storedUrls == null || storedUrls.isEmpty()) {
            return List.of();
        }
        return storedUrls.stream().map(this::presignGet).toList();
    }

    private String objectUrlFor(String key) {
        return objectBaseUrl + "/" + key;
    }

    private String browserUploadUrl(PresignedPutObjectRequest presigned) {
        String directUrl = presigned.url().toExternalForm();
        if (!StringUtils.hasText(browserUploadProxyBaseUrl)) {
            return directUrl;
        }
        java.net.URI direct = java.net.URI.create(directUrl);
        String query = StringUtils.hasText(direct.getRawQuery()) ? "?" + direct.getRawQuery() : "";
        return browserUploadProxyBaseUrl + direct.getRawPath() + query;
    }

    private Optional<String> objectKeyFrom(String storedUrl) {
        return acceptedObjectBaseUrls.stream()
                .map(base -> base + "/")
                .filter(storedUrl::startsWith)
                .map(prefix -> storedUrl.substring(prefix.length()))
                .filter(StringUtils::hasText)
                .findFirst();
    }

    private static String resolveObjectBaseUrl(String bucket, String region,
                                               String endpoint, String publicBaseUrl) {
        if (StringUtils.hasText(publicBaseUrl)) {
            return trimTrailingSlash(publicBaseUrl);
        }
        if (StringUtils.hasText(endpoint) && StringUtils.hasText(bucket)) {
            return trimTrailingSlash(endpoint) + "/" + bucket;
        }
        return "https://%s.s3.%s.amazonaws.com".formatted(bucket, region);
    }

    private static Set<String> acceptedBaseUrls(String currentBaseUrl, String legacyBaseUrls) {
        LinkedHashSet<String> bases = new LinkedHashSet<>();
        bases.add(currentBaseUrl);
        if (StringUtils.hasText(legacyBaseUrls)) {
            Arrays.stream(legacyBaseUrls.split(","))
                    .map(String::trim)
                    .filter(StringUtils::hasText)
                    .map(PhotoService::trimTrailingSlash)
                    .forEach(bases::add);
        }
        return Collections.unmodifiableSet(bases);
    }

    private static String trimTrailingSlash(String value) {
        String normalized = value.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private static String normalizeBrowserUploadProxyBaseUrl(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        String normalized = trimTrailingSlash(value);
        java.net.URI uri = java.net.URI.create(normalized);
        if (!"https".equalsIgnoreCase(uri.getScheme()) || !StringUtils.hasText(uri.getHost())
                || uri.getRawQuery() != null || uri.getRawFragment() != null || uri.getUserInfo() != null) {
            throw new IllegalArgumentException(
                    "S3_BROWSER_UPLOAD_PROXY_BASE_URL must be an HTTPS origin/path without query, fragment, or user info");
        }
        return normalized;
    }
}
