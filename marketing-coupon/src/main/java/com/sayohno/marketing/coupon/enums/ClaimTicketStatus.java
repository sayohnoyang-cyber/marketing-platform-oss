package com.sayohno.marketing.coupon.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum ClaimTicketStatus {
    PENDING("pending"),
    RESERVED("reserved"),
    SUCCEEDED("succeeded"),
    FAILED("failed");

    @EnumValue
    @JsonValue
    private final String code;

    ClaimTicketStatus(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    @JsonCreator
    public static ClaimTicketStatus fromCode(String raw) {
        if (raw == null) {
            return null;
        }
        for (ClaimTicketStatus value : values()) {
            if (value.code.equalsIgnoreCase(raw) || value.name().equalsIgnoreCase(raw)) {
                return value;
            }
        }
        throw new IllegalArgumentException("Unknown ClaimTicketStatus: " + raw);
    }
}
