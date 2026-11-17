package com.sayohno.marketing.common.outbox;

public interface OutboxTransport {
    void dispatch(OutboxEvent event);
}
