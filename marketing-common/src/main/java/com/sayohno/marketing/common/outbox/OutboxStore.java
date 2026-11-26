package com.sayohno.marketing.common.outbox;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

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

    /**
     * 批量追加 Outbox 事件：单次 SQL 批量落库，减少逐条 INSERT 的网络 RTT 与事务开销。
     */
    public int appendBatch(List<OutboxBatchItem> items) {
        if (items == null || items.isEmpty()) {
            return 0;
        }
        LocalDateTime now = LocalDateTime.now();
        List<OutboxEvent> events = new ArrayList<>(items.size());
        for (OutboxBatchItem item : items) {
            OutboxEvent event = new OutboxEvent();
            event.setId(IdWorker.getId());
            event.setEventId(item.eventId());
            event.setEventType(item.eventType());
            event.setAggregateId(item.aggregateId());
            event.setPayload(item.payload() == null ? "{}" : item.payload());
            event.setStatus(OutboxStatus.PENDING);
            event.setRetryCount(0);
            event.setNextRetryAt(now);
            event.setCreateTime(now);
            event.setUpdateTime(now);
            events.add(event);
        }
        return mapper.insertBatch(events);
    }

    public record OutboxBatchItem(String eventId, String eventType, String aggregateId, String payload) {
    }
}
