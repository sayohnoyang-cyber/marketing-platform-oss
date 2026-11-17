package com.sayohno.marketing.common.outbox;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@ConditionalOnProperty(prefix = "marketing.mq", name = "enabled", havingValue = "false", matchIfMissing = true)
public class LocalOutboxTransport implements OutboxTransport {
    private final List<OutboxHandler> handlers;

    public LocalOutboxTransport(List<OutboxHandler> handlers) {
        this.handlers = handlers;
    }

    @Override
    public void dispatch(OutboxEvent event) {
        List<OutboxHandler> matched = handlers.stream()
                .filter(handler -> handler.supports(event.getEventType()))
                .toList();
        if (matched.isEmpty()) {
            throw new IllegalStateException("没有消费者: " + event.getEventType());
        }
        for (OutboxHandler handler : matched) {
            handler.handle(event);
        }
    }
}
