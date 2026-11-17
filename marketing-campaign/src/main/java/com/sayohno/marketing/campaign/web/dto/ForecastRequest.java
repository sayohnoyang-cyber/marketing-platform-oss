package com.sayohno.marketing.campaign.web.dto;

import jakarta.validation.constraints.*;
import java.util.Map;

public record ForecastRequest(
        @NotBlank @Size(max = 64) String requestKey,
        @NotNull Map<String, Double> arrivalRates,
        @Min(0) @Max(200) double growthPercent,
        @DecimalMin("1") @DecimalMax("5") double attempts,
        @NotBlank @Pattern(regexp = "low|normal|high") String scenario,
        @Min(0) @Max(1000000) double unreservedQps,
        Long baselineId) { }
