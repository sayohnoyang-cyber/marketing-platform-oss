package com.sayohno.marketing.coupon.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sayohno.marketing.common.exception.BizException;
import com.sayohno.marketing.common.result.ErrorCode;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class OfferPolicyRules {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private OfferPolicyRules() {
    }

    public static JsonNode parse(String json, String name) {
        try {
            JsonNode node = MAPPER.readTree(json);
            if (node == null || !node.isObject()) {
                throw new BizException(ErrorCode.VALIDATION, name + " 必须是 JSON 对象");
            }
            return node;
        } catch (BizException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BizException(ErrorCode.VALIDATION, name + " 不是合法 JSON");
        }
    }

    public static int maxClaimsPerUser(String claimPolicy) {
        JsonNode node = parse(claimPolicy, "领取规则");
        int limit = node.path("maxClaimsPerUser").asInt(0);
        if (limit < 1) {
            throw new BizException(ErrorCode.VALIDATION, "maxClaimsPerUser 必须大于 0");
        }
        return limit;
    }

    public static BigDecimal maximumDiscount(String benefitPolicy) {
        JsonNode node = parse(benefitPolicy, "核销规则");
        if (!node.hasNonNull("capAmount")) {
            throw new BizException(ErrorCode.VALIDATION, "必须写明 capAmount，不能使用估算优惠");
        }
        BigDecimal amount = money(node, "capAmount");
        if (amount.signum() <= 0) {
            throw new BizException(ErrorCode.VALIDATION, "单张最高优惠必须大于 0");
        }
        return amount;
    }

    public static BigDecimal money(JsonNode node, String field) {
        if (!node.hasNonNull(field)) {
            throw new BizException(ErrorCode.VALIDATION, field + " 不能为空");
        }
        try {
            return new BigDecimal(node.get(field).asText()).setScale(2, RoundingMode.HALF_UP);
        } catch (NumberFormatException | ArithmeticException exception) {
            throw new BizException(ErrorCode.VALIDATION, field + " 金额不合法");
        }
    }
}
