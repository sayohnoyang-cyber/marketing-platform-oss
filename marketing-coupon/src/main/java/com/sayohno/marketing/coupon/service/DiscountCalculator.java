package com.sayohno.marketing.coupon.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.sayohno.marketing.common.exception.BizException;
import com.sayohno.marketing.common.result.ErrorCode;
import com.sayohno.marketing.coupon.dao.entity.OfferPlan;
import com.sayohno.marketing.coupon.enums.ApplyScope;
import com.sayohno.marketing.coupon.enums.BenefitKind;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class DiscountCalculator {
    private DiscountCalculator() {
    }

    public static BigDecimal discount(OfferPlan template, BigDecimal orderAmount, String goodsNumber) {
        if (orderAmount == null) {
            throw new BizException(ErrorCode.VALIDATION, "订单金额不能为空");
        }
        JsonNode rule = OfferPolicyRules.parse(template.getBenefitPolicy(), "核销规则");
        if (template.getApplyScope() == ApplyScope.SKU
                && (goodsNumber == null || !goodsNumber.equals(template.getSkuCode()))) {
            throw new BizException(ErrorCode.VALIDATION, "商品与优惠券不匹配");
        }
        BigDecimal terms = rule.hasNonNull("minSpend") ? OfferPolicyRules.money(rule, "minSpend") : BigDecimal.ZERO;
        if (orderAmount.compareTo(terms) < 0) {
            throw new BizException(ErrorCode.VALIDATION, "订单金额未满足使用条件");
        }
        BigDecimal maximum = OfferPolicyRules.money(rule, "capAmount");
        if (template.getBenefitKind() == null) {
            throw new BizException(ErrorCode.VALIDATION, "无效的优惠券类型");
        }
        BigDecimal discount = switch (template.getBenefitKind()) {
            case AMOUNT_OFF, AMOUNT_OFF_OVER -> maximum;
            case PERCENT_OFF -> {
                if (!rule.hasNonNull("percentOff")) {
                    throw new BizException(ErrorCode.VALIDATION, "percentOff 不能为空");
                }
                BigDecimal rate = new BigDecimal(rule.get("percentOff").asText());
                BigDecimal value = orderAmount.multiply(rate).setScale(2, RoundingMode.HALF_UP);
                yield value.min(maximum);
            }
        };
        if (discount.compareTo(BigDecimal.ZERO) < 0 || discount.compareTo(orderAmount) > 0) {
            throw new BizException(ErrorCode.VALIDATION, "优惠金额超出订单金额");
        }
        return discount.setScale(2, RoundingMode.HALF_UP);
    }
}
