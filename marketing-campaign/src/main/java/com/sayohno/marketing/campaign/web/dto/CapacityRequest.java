package com.sayohno.marketing.campaign.web.dto;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

public record CapacityRequest(
        @NotBlank @Size(max = 128) String deploymentId,
        @NotBlank @Size(max = 256) String reportName,
        @Min(1) @Max(1000000) int sustainedQps,
        @Min(1) int p95Millis,
        @Min(1) @Max(1000) int p99Millis,
        @NotNull @DecimalMin("0") @DecimalMax("0.01") BigDecimal errorRate,
        @Min(30) @Max(86400) int durationSeconds,
        @Min(1000) int sampleCount,
        @NotNull LocalDateTime measuredAt,
        @NotNull LocalDateTime expiresAt) { }
