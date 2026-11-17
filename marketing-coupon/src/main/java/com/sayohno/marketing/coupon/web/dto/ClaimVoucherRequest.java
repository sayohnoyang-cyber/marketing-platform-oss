package com.sayohno.marketing.coupon.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class ClaimVoucherRequest {
    @NotNull
    private Long planId;
    @NotBlank
    private String requestId;
}
