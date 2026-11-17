package com.sayohno.marketing.common.outbox;

public final class OutboxStatus {
    public static final int PENDING = 0;
    public static final int SENDING = 1;
    public static final int SENT = 2;
    public static final int DEAD = 3;

    private OutboxStatus() {
    }
}
