package com.sayohno.marketing.coupon.web.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ReviseOfferPlanRequest {
    private String title;
    private LocalDateTime usableFrom;
    private LocalDateTime usableUntil;
    private String claimPolicy;
    private String benefitPolicy;
}
