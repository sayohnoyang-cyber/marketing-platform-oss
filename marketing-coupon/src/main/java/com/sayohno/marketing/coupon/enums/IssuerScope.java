package com.sayohno.marketing.coupon.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum IssuerScope {
    MERCHANT("merchant"),
    PLATFORM("platform");

    @EnumValue
    @JsonValue
    private final String code;

    IssuerScope(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    @JsonCreator
    public static IssuerScope fromCode(String raw) {
        if (raw == null) {
            return null;
        }
        for (IssuerScope value : values()) {
            if (value.code.equalsIgnoreCase(raw) || value.name().equalsIgnoreCase(raw)) {
                return value;
            }
        }
        throw new IllegalArgumentException("Unknown IssuerScope: " + raw);
    }
}
