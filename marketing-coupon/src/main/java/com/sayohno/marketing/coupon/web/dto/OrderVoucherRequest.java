package com.sayohno.marketing.coupon.web.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class OrderVoucherRequest {
    @NotBlank
    private String orderNo;
    @jakarta.validation.constraints.DecimalMin("0.01")
    @jakarta.validation.constraints.Digits(integer = 10, fraction = 2)
    private BigDecimal orderAmount;
    @jakarta.validation.constraints.DecimalMin("0.00")
    @jakarta.validation.constraints.Digits(integer = 10, fraction = 2)
    private BigDecimal refundAmount;
    private String goodsNumber;
}
