package com.sayohno.marketing.coupon.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum ApplyScope {
    SKU("sku"),
    STOREWIDE("storewide");

    @EnumValue
    @JsonValue
    private final String code;

    ApplyScope(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    @JsonCreator
    public static ApplyScope fromCode(String raw) {
        if (raw == null) {
            return null;
        }
        for (ApplyScope value : values()) {
            if (value.code.equalsIgnoreCase(raw) || value.name().equalsIgnoreCase(raw)) {
                return value;
            }
        }
        throw new IllegalArgumentException("Unknown ApplyScope: " + raw);
    }
}
