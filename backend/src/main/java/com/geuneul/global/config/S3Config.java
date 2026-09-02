package com.geuneul.global.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;

/**
 * S3 호환 저장소 클라이언트(PhotoService 전용, docs/SPEC.md §7). endpoint가 비어 있으면 AWS S3,
 * 지정되면 OCI Object Storage의 S3 compatibility endpoint처럼 SigV4 호환 저장소를 사용한다.
 * 자격증명은 SDK 기본 체인으로 해석해 코드나 설정 파일에 값을 두지 않는다.
 * presign 자체는 로컬 서명 연산이라 네트워크 호출이 없다 — 자격증명 미설정이어도 빈은 뜨고,
 * 실제 presign() 호출 시점에야 실패한다(부팅 안전성, JwtService와 동일 패턴).
 */
@Configuration
public class S3Config {

    @Bean
    public S3Client s3Client(@Value("${aws.s3.region:ap-northeast-2}") String region,
                             @Value("${aws.s3.endpoint:}") String endpoint,
                             @Value("${aws.s3.path-style:false}") boolean pathStyle) {
        var builder = S3Client.builder()
                .region(Region.of(region))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(pathStyle)
                        .build());
        if (StringUtils.hasText(endpoint)) {
            builder.endpointOverride(URI.create(endpoint));
        }
        return builder.build();
    }

    @Bean
    public S3Presigner s3Presigner(@Value("${aws.s3.region:ap-northeast-2}") String region,
                                   @Value("${aws.s3.endpoint:}") String endpoint,
                                   @Value("${aws.s3.path-style:false}") boolean pathStyle) {
        var builder = S3Presigner.builder()
                .region(Region.of(region))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(pathStyle)
                        .build());
        if (StringUtils.hasText(endpoint)) {
            builder.endpointOverride(URI.create(endpoint));
        }
        return builder.build();
    }
}
