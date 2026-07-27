package com.geuneul.domain.photo;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/** Bounded cleanup configuration; validation prevents an accidental unbounded production sweep. */
@Validated
@ConfigurationProperties("geuneul.photo-cleanup")
public record PhotoCleanupProperties(
        boolean enabled,
        @Min(1) @Max(100) int batchSize,
        @NotNull Duration lease
) {
    public PhotoCleanupProperties {
        if (lease != null && (lease.isZero() || lease.isNegative())) {
            throw new IllegalArgumentException("geuneul.photo-cleanup.lease must be positive");
        }
    }
}
