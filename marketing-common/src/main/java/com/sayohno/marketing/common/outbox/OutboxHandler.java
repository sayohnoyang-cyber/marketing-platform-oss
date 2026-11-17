package com.sayohno.marketing.common.outbox;

public interface OutboxHandler {
    boolean supports(String eventType);

    void handle(OutboxEvent event);
}
