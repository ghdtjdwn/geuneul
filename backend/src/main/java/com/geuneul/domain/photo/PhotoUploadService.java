package com.geuneul.domain.photo;

import com.geuneul.domain.auth.JwtService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.core.exception.SdkClientException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE;

/** Records upload ownership and verifies the private S3 object before UGC can reference it. */
@Service
@Transactional(readOnly = true)
public class PhotoUploadService {

    private static final Duration CLAIM_DURATION = Duration.ofMinutes(30);

    private final PhotoUploadRepository repository;
    private final S3Client s3;
    private final String bucket;

    public PhotoUploadService(PhotoUploadRepository repository, S3Client s3,
                              @org.springframework.beans.factory.annotation.Value("${aws.s3.bucket:}") String bucket) {
        this.repository = repository;
        this.s3 = s3;
        this.bucket = bucket;
    }

    @Transactional
    public void register(String key, String objectUrl, PhotoPurpose purpose,
                         JwtService.AuthPrincipal principal, String clientKey,
                         String contentType, long contentLength) {
        Long userId = principal == null ? null : principal.userId();
        String clientHash = userId == null ? hashClient(clientKey) : null;
        OffsetDateTime now = databaseNow();
        repository.save(PhotoUpload.create(key, objectUrl, purpose, userId, clientHash,
                contentType, contentLength, now.plus(CLAIM_DURATION)));
    }

    @Transactional
    public void validateReport(String objectUrl, JwtService.AuthPrincipal principal, String clientKey) {
        if (objectUrl == null || objectUrl.isBlank()) return;
        validate(objectUrl, PhotoPurpose.REPORT, principal == null ? null : principal.userId(),
                principal == null ? hashClient(clientKey) : null);
    }

    @Transactional
    public void validateReview(List<String> objectUrls, long userId) {
        if (objectUrls == null || objectUrls.isEmpty()) return;
        List<String> ordered = objectUrls.stream().filter(Objects::nonNull).sorted().toList();
        if (ordered.size() != objectUrls.size() || ordered.stream().distinct().count() != ordered.size()) {
            throw invalid("사진 URL은 중복 없이 제출해야 합니다");
        }
        // Every transaction locks overlapping review claims in the same order, preventing list-order deadlocks.
        ordered.forEach(url -> validate(url, PhotoPurpose.REVIEW, userId, null));
    }

    /** Marks photos removed from a review as reclaimable in the caller's review transaction. */
    @Transactional
    public void detachReview(List<String> objectUrls, long userId) {
        if (objectUrls == null || objectUrls.isEmpty()) return;
        OffsetDateTime now = databaseNow();
        objectUrls.stream().sorted().forEach(url -> {
            PhotoUpload upload = repository.findByObjectUrlForUpdate(url).orElse(null);
            // V21 이전 review 사진에는 claim 행이 없다. 기존 review JSON에서 제거된 URL에 한해서만
            // unmanaged legacy object로 취급하며, 새로 첨부하는 URL은 validateReview가 계속 거부한다.
            if (upload == null) return;
            if (upload.getPurpose() != PhotoPurpose.REVIEW || !Objects.equals(upload.getOwnerUserId(), userId)) {
                throw invalid("사진 업로드 소유자 또는 용도가 일치하지 않습니다");
            }
            upload.detach(now);
        });
    }

    private void validate(String objectUrl, PhotoPurpose purpose, Long userId, String clientHash) {
        PhotoUpload upload = repository.findByObjectUrlForUpdate(objectUrl)
                .orElseThrow(() -> invalid("발급 기록이 없는 사진 URL입니다"));
        if (upload.getCompletedAt() != null) throw invalid("이미 사용한 사진 업로드입니다");
        if (upload.getCleanupToken() != null) {
            throw new ResponseStatusException(CONFLICT, "만료된 사진 정리가 진행 중입니다");
        }
        if (upload.getPurpose() != purpose) throw invalid("사진 용도가 제출 대상과 일치하지 않습니다");
        if (!ownerMatches(upload, userId, clientHash)) throw invalid("사진 업로드 소유자가 일치하지 않습니다");
        OffsetDateTime now = databaseNow();
        if (!upload.getExpiresAt().isAfter(now)) throw invalid("사진 첨부 유효시간이 지났습니다");

        HeadObjectResponse head;
        try {
            head = s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(upload.getObjectKey()).build());
        } catch (S3Exception e) {
            if (e.statusCode() == 404) throw invalid("사진 업로드가 완료되지 않았습니다");
            throw new ResponseStatusException(SERVICE_UNAVAILABLE, "사진 저장소 확인에 실패했습니다");
        } catch (SdkClientException e) {
            throw new ResponseStatusException(SERVICE_UNAVAILABLE, "사진 저장소 확인에 실패했습니다");
        }
        if (head.contentLength() == null || head.contentLength() != upload.getContentLength()
                || head.contentType() == null || !head.contentType().equalsIgnoreCase(upload.getContentType())) {
            throw invalid("업로드된 사진의 타입 또는 크기가 발급 조건과 일치하지 않습니다");
        }
        upload.consume(now);
    }

    private static boolean ownerMatches(PhotoUpload upload, Long userId, String clientHash) {
        return userId != null
                ? userId.equals(upload.getOwnerUserId())
                : clientHash != null && clientHash.equals(upload.getOwnerClientHash());
    }

    private OffsetDateTime databaseNow() {
        return repository.databaseNowInstant().atOffset(ZoneOffset.UTC);
    }

    static String hashClient(String clientKey) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((clientKey == null ? "unknown" : clientKey).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static ResponseStatusException invalid(String message) {
        return new ResponseStatusException(BAD_REQUEST, message);
    }
}
