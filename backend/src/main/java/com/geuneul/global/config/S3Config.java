package com.geuneul.global.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * S3 presigned URL 발급기(PhotoService 전용, docs/SPEC.md §7). 자격증명은 지정하지 않는다 —
 * SDK의 기본 자격증명 체인(DefaultCredentialsProvider)이 ECS에서는 태스크 롤(iam.tf ecs_task_s3_photos),
 * 로컬에서는 ~/.aws를 암묵적으로 사용한다(정적 액세스키를 코드/설정에 두지 않음, 규칙 D).
 * presign 자체는 로컬 서명 연산이라 네트워크 호출이 없다 — 자격증명 미설정이어도 빈은 뜨고,
 * 실제 presign() 호출 시점에야 실패한다(부팅 안전성, JwtService와 동일 패턴).
 */
@Configuration
public class S3Config {

    @Bean
    public S3Client s3Client(@Value("${aws.s3.region:ap-northeast-2}") String region) {
        return S3Client.builder().region(Region.of(region)).build();
    }

    @Bean
    public S3Presigner s3Presigner(@Value("${aws.s3.region:ap-northeast-2}") String region) {
        return S3Presigner.builder()
                .region(Region.of(region))
                .build();
    }
}
