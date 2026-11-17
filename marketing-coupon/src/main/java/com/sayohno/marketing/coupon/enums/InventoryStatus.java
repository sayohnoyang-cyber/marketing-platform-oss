package com.sayohno.marketing.coupon.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum InventoryStatus {
    OPEN("open"),
    FROZEN("frozen");

    @EnumValue
    @JsonValue
    private final String code;

    InventoryStatus(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    @JsonCreator
    public static InventoryStatus fromCode(String raw) {
        if (raw == null) {
            return null;
        }
        for (InventoryStatus value : values()) {
            if (value.code.equalsIgnoreCase(raw) || value.name().equalsIgnoreCase(raw)) {
                return value;
            }
        }
        throw new IllegalArgumentException("Unknown InventoryStatus: " + raw);
    }
}
