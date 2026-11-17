package com.sayohno.marketing.coupon.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum RedemptionStatus {
    HELD("held"),
    VOIDED("voided"),
    COMPLETED("completed"),
    REFUNDED("refunded");

    @EnumValue
    @JsonValue
    private final String code;

    RedemptionStatus(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    @JsonCreator
    public static RedemptionStatus fromCode(String raw) {
        if (raw == null) {
            return null;
        }
        for (RedemptionStatus value : values()) {
            if (value.code.equalsIgnoreCase(raw) || value.name().equalsIgnoreCase(raw)) {
                return value;
            }
        }
        throw new IllegalArgumentException("Unknown RedemptionStatus: " + raw);
    }
}
