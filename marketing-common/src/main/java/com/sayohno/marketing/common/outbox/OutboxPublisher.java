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

    @Scheduled(fixedDelayString = "${OUTBOX_PUBLISH_DELAY:2000}")
    public void scheduled() {
        if (properties.getOutbox().isPublishEnabled()) {
            publishDue(50);
        }
    }

    public int publishDue(int limit) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime stale = now.minus(properties.getOutbox().getSendingLease());
        int delivered = 0;
        for (OutboxEvent event : mapper.findDue(now, stale, limit)) {
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
        return delivered;
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
