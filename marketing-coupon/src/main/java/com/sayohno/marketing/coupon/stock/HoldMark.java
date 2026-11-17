package com.sayohno.marketing.coupon.stock;

public record HoldMark(int templateCount, int allocCount) {
    public static HoldMark parse(String value) {
        int split = value.indexOf(':');
        if (split < 1 || split == value.length() - 1) {
            throw new IllegalArgumentException("预扣标记格式不正确");
        }
        return new HoldMark(Integer.parseInt(value.substring(0, split)), Integer.parseInt(value.substring(split + 1)));
    }
}
