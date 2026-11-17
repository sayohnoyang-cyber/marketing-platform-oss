package com.sayohno.marketing.coupon.stock;

public record StockDecision(String reason, int claimSeq, int allocCount) {
    public static final String RESERVED = "reserved";
    public static final String SOLD_OUT = "sold_out";
    public static final String QUOTA_EXCEEDED = "quota_exceeded";

    public StockDecision(String reason, int claimSeq) {
        this(reason, claimSeq, claimSeq);
    }

    public boolean ok() {
        return RESERVED.equals(reason);
    }

    public boolean quotaExceeded() {
        return QUOTA_EXCEEDED.equals(reason);
    }
}
