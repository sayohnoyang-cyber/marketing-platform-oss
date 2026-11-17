package com.sayohno.marketing.coupon.service;

import com.sayohno.marketing.common.exception.BizException;
import com.sayohno.marketing.coupon.dao.entity.OfferPlan;
import com.sayohno.marketing.coupon.enums.ApplyScope;
import com.sayohno.marketing.coupon.enums.BenefitKind;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DiscountCalculatorTest {
    @Test
    void goodsCouponUsesTheSameAmountRules() {
        OfferPlan goods = template(BenefitKind.AMOUNT_OFF_OVER, "{\"minSpend\":\"10.00\",\"capAmount\":\"5.00\"}");
        goods.setApplyScope(ApplyScope.SKU);
        goods.setSkuCode("sku-1");
        assertThrows(BizException.class, () -> DiscountCalculator.discount(goods, new BigDecimal("1.00"), "sku-1"));
        assertThrows(BizException.class, () -> DiscountCalculator.discount(goods, new BigDecimal("9.00"), "sku-1"));
        assertEquals(new BigDecimal("5.00"), DiscountCalculator.discount(goods, new BigDecimal("30.00"), "sku-1"));

        OfferPlan rate = template(BenefitKind.PERCENT_OFF, "{\"minSpend\":\"0.00\",\"capAmount\":\"20.00\",\"percentOff\":\"0.50\"}");
        rate.setApplyScope(ApplyScope.SKU);
        rate.setSkuCode("sku-1");
        assertEquals(new BigDecimal("6.00"), DiscountCalculator.discount(rate, new BigDecimal("12.00"), "sku-1"));
        assertThrows(BizException.class, () -> DiscountCalculator.discount(rate, new BigDecimal("1.00"), "other"));
    }

    @Test
    void fullReductionAndDiscountCap() {
        OfferPlan full = template(BenefitKind.AMOUNT_OFF_OVER, "{\"minSpend\":\"100.00\",\"capAmount\":\"15.00\"}");
        assertEquals(new BigDecimal("15.00"), DiscountCalculator.discount(full, new BigDecimal("120.00"), null));
        assertThrows(BizException.class, () -> DiscountCalculator.discount(full, new BigDecimal("90.00"), null));

        OfferPlan rate = template(BenefitKind.PERCENT_OFF, "{\"minSpend\":\"0.00\",\"capAmount\":\"20.00\",\"percentOff\":\"0.10\"}");
        assertEquals(new BigDecimal("12.00"), DiscountCalculator.discount(rate, new BigDecimal("120.00"), null));
    }

    private static OfferPlan template(BenefitKind type, String rule) {
        OfferPlan template = new OfferPlan();
        template.setBenefitKind(type);
        template.setApplyScope(ApplyScope.STOREWIDE);
        template.setBenefitPolicy(rule);
        return template;
    }
}
