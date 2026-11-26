package com.sayohno.marketing.common.outbox;

import com.sayohno.marketing.common.config.MarketingProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Component
public class OutboxPublisher {
    private final OutboxEventMapper mapper;
    private final OutboxTransport transport;
    private final MarketingProperties properties;
    private final Counter deliveredCounter;
    private final Counter failedCounter;

    public OutboxPublisher(OutboxEventMapper mapper, OutboxTransport transport, MarketingProperties properties) {
        this(mapper, transport, properties, null);
    }

    @Autowired
    public OutboxPublisher(OutboxEventMapper mapper,
                           OutboxTransport transport,
                           MarketingProperties properties,
                           ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this.mapper = mapper;
        this.transport = transport;
        this.properties = properties;
        MeterRegistry registry = meterRegistryProvider == null ? null : meterRegistryProvider.getIfAvailable();
        this.deliveredCounter = registry == null ? null : Counter.builder("marketing.outbox.delivered")
                .description("Outbox 投递成功消息数")
                .register(registry);
        this.failedCounter = registry == null ? null : Counter.builder("marketing.outbox.failed")
                .description("Outbox 投递失败进入重试或死信数")
                .register(registry);
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
        if (due.isEmpty()) {
            return new BatchOutcome(0, 0);
        }
        List<Long> sentIds = new ArrayList<>(due.size());
        for (OutboxEvent event : due) {
            if (mapper.claim(event.getId(), now, stale) != 1) {
                continue;
            }
            try {
                transport.dispatch(event);
                sentIds.add(event.getId());
            } catch (RuntimeException exception) {
                if (failedCounter != null) {
                    failedCounter.increment();
                }
                String message = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
                if (message.length() > 500) {
                    message = message.substring(0, 500);
                }
                mapper.markRetry(event.getId(), LocalDateTime.now(),
                        LocalDateTime.now().plus(properties.getOutbox().getRetryDelay()),
                        message, properties.getOutbox().getMaxRetries());
            }
        }
        if (!sentIds.isEmpty()) {
            LocalDateTime sentAt = LocalDateTime.now();
            try {
                mapper.markSentBatch(sentIds, sentAt);
            } catch (RuntimeException ignored) {
                // 批量更新异常时降级逐条确认，确保已投递状态不丢失。
                for (Long sentId : sentIds) {
                    mapper.markSent(sentId, sentAt);
                }
            }
            if (deliveredCounter != null) {
                deliveredCounter.increment(sentIds.size());
            }
        }
        return new BatchOutcome(due.size(), sentIds.size());
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
