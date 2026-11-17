package com.sayohno.marketing.common.outbox;

import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
public class OutboxStore {
    private final OutboxEventMapper mapper;

    public OutboxStore(OutboxEventMapper mapper) {
        this.mapper = mapper;
    }

    public void append(String eventId, String eventType, String aggregateId, String payload) {
        OutboxEvent event = new OutboxEvent();
        event.setEventId(eventId);
        event.setEventType(eventType);
        event.setAggregateId(aggregateId);
        event.setPayload(payload == null ? "{}" : payload);
        event.setStatus(OutboxStatus.PENDING);
        event.setRetryCount(0);
        event.setNextRetryAt(LocalDateTime.now());
        mapper.insert(event);
    }
}
