package com.sayohno.marketing.coupon.web.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class QuoteVoucherRequest {
    @NotNull
    private Long voucherId;
    @NotNull
    private BigDecimal orderAmount;
    private String goodsNumber;
}
