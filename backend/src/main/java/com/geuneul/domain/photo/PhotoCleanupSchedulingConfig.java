package com.geuneul.domain.photo;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@EnableConfigurationProperties(PhotoCleanupProperties.class)
class PhotoCleanupSchedulingConfig {
}
