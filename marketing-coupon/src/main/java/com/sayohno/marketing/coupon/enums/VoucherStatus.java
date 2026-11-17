package com.sayohno.marketing.coupon.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum VoucherStatus {
    AVAILABLE("available"),
    HELD("held"),
    REDEEMED("redeemed"),
    EXPIRED("expired"),
    VOIDED("voided");

    @EnumValue
    @JsonValue
    private final String code;

    VoucherStatus(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    @JsonCreator
    public static VoucherStatus fromCode(String raw) {
        if (raw == null) {
            return null;
        }
        for (VoucherStatus value : values()) {
            if (value.code.equalsIgnoreCase(raw) || value.name().equalsIgnoreCase(raw)) {
                return value;
            }
        }
        throw new IllegalArgumentException("Unknown VoucherStatus: " + raw);
    }
}
