package com.sayohno.marketing.coupon.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum BenefitKind {
    AMOUNT_OFF("amount_off"),
    AMOUNT_OFF_OVER("amount_off_over"),
    PERCENT_OFF("percent_off");

    @EnumValue
    @JsonValue
    private final String code;

    BenefitKind(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    @JsonCreator
    public static BenefitKind fromCode(String raw) {
        if (raw == null) {
            return null;
        }
        for (BenefitKind value : values()) {
            if (value.code.equalsIgnoreCase(raw) || value.name().equalsIgnoreCase(raw)) {
                return value;
            }
        }
        throw new IllegalArgumentException("Unknown BenefitKind: " + raw);
    }
}
