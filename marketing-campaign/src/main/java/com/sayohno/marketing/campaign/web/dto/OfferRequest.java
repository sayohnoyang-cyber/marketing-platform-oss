package com.sayohno.marketing.campaign.web.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class OfferRequest {
    @NotNull(message = "请选择优惠券")
    private Long planId;
    @NotNull(message = "请填写投放数量")
    @Min(value = 1, message = "投放数量至少为 1")
    private Integer quantity;
    @NotNull(message = "请填写每人限领")
    @Min(value = 1, message = "每人限领至少为 1")
    private Integer maxClaimsPerUser;
    @NotBlank(message = "缺少重试标识")
    private String idempotencyKey;
    @NotNull(message = "请填写预算")
    private BigDecimal budgetAmount;
    private Integer version;
}
