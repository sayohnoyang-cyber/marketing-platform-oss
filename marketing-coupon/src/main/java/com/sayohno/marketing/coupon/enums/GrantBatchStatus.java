package com.sayohno.marketing.coupon.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum GrantBatchStatus {
    QUEUED("queued"),
    RUNNING("running"),
    DONE("done"),
    PARTIAL("partial"),
    FAILED("failed");

    @EnumValue
    @JsonValue
    private final String code;

    GrantBatchStatus(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    @JsonCreator
    public static GrantBatchStatus fromCode(String raw) {
        if (raw == null) {
            return null;
        }
        for (GrantBatchStatus value : values()) {
            if (value.code.equalsIgnoreCase(raw) || value.name().equalsIgnoreCase(raw)) {
                return value;
            }
        }
        throw new IllegalArgumentException("Unknown GrantBatchStatus: " + raw);
    }
}
