package com.sayohno.marketing.common.outbox;

import com.sayohno.marketing.common.config.MarketingProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
public class OutboxPublisher {
    private final OutboxEventMapper mapper;
    private final OutboxTransport transport;
    private final MarketingProperties properties;

    public OutboxPublisher(OutboxEventMapper mapper, OutboxTransport transport, MarketingProperties properties) {
        this.mapper = mapper;
        this.transport = transport;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${OUTBOX_PUBLISH_DELAY:200}")
    public void scheduled() {
        if (properties.getOutbox().isPublishEnabled()) {
            publishCycle();
        }
    }

    /**
     * 单轮连续批量投递：每批拉取 batchSize 条，若本批满载则继续拉取，直到队列见底或达到 maxPerCycle 上限。
     */
    public int publishCycle() {
        int batchSize = Math.max(1, properties.getOutbox().getBatchSize());
        int maxPerCycle = Math.max(batchSize, properties.getOutbox().getMaxPerCycle());
        int totalDelivered = 0;
        int totalProcessed = 0;
        while (totalProcessed < maxPerCycle) {
            int limit = Math.min(batchSize, maxPerCycle - totalProcessed);
            BatchOutcome outcome = publishBatch(limit);
            totalDelivered += outcome.delivered();
            totalProcessed += outcome.fetched();
            if (outcome.fetched() < limit) {
                break;
            }
        }
        return totalDelivered;
    }

    public int publishDue(int limit) {
        return publishBatch(limit).delivered();
    }

    private BatchOutcome publishBatch(int limit) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime stale = now.minus(properties.getOutbox().getSendingLease());
        var due = mapper.findDue(now, stale, limit);
        int delivered = 0;
        for (OutboxEvent event : due) {
            if (mapper.claim(event.getId(), now, stale) != 1) {
                continue;
            }
            try {
                transport.dispatch(event);
                mapper.markSent(event.getId(), LocalDateTime.now());
                delivered++;
            } catch (RuntimeException exception) {
                String message = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
                if (message.length() > 500) {
                    message = message.substring(0, 500);
                }
                mapper.markRetry(event.getId(), LocalDateTime.now(),
                        LocalDateTime.now().plus(properties.getOutbox().getRetryDelay()),
                        message, properties.getOutbox().getMaxRetries());
            }
        }
        return new BatchOutcome(due.size(), delivered);
    }

    private record BatchOutcome(int fetched, int delivered) {
    }

    public boolean replay(String eventId) {
        return mapper.reopen(eventId, LocalDateTime.now()) == 1;
    }

    public void markDead(String eventId, String error) {
        String message = error == null ? "死信" : error;
        if (message.length() > 500) {
            message = message.substring(0, 500);
        }
        mapper.markDead(eventId, message, LocalDateTime.now());
    }
}
