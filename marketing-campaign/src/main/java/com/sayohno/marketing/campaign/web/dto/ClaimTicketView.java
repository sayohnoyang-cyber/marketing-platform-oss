package com.sayohno.marketing.campaign.web.dto;

import com.sayohno.marketing.coupon.dao.entity.IssuedVoucher;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 异步领券受理与查单结果强类型视图。
 */
public record ClaimTicketView(
        String requestId,
        String status,
        Long voucherId,
        IssuedVoucher coupon
) {
    public Map<String, Object> toMap() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("status", status);
        body.put("voucherId", voucherId);
        body.put("coupon", coupon);
        return body;
    }
}
