package com.sayohno.marketing.campaign.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ActivityRequest {
    @NotBlank
    @jakarta.validation.constraints.Size(max = 128)
    private String name;
    @jakarta.validation.constraints.Pattern(regexp = "优惠券推广活动|会员专享活动|新客领取活动")
    private String activityType;
    @jakarta.validation.constraints.DecimalMin("0.00")
    @jakarta.validation.constraints.Digits(integer = 10, fraction = 2)
    private java.math.BigDecimal budgetAmount;
    private LocalDateTime warmupStartTime;
    @NotNull
    private LocalDateTime startTime;
    @NotNull
    private LocalDateTime endTime;
    private Integer version;
    @jakarta.validation.constraints.Pattern(regexp = "[A-Za-z0-9_-]{1,64}")
    private String idempotencyKey;
}
