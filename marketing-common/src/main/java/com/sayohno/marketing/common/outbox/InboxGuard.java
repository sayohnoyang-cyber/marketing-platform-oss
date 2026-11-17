package com.sayohno.marketing.common.outbox;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Consumer;

@Service
public class InboxGuard {
    private final ConsumerInboxMapper inboxMapper;
    private final TransactionTemplate transactionTemplate;

    public InboxGuard(ConsumerInboxMapper inboxMapper, org.springframework.transaction.PlatformTransactionManager manager) {
        this.inboxMapper = inboxMapper;
        this.transactionTemplate = new TransactionTemplate(manager);
    }

    public void once(String consumerGroup, OutboxEvent event, Consumer<OutboxEvent> effect) {
        if (seen(consumerGroup, event.getEventId())) {
            return;
        }
        try {
            transactionTemplate.executeWithoutResult(status -> {
                ConsumerInbox inbox = new ConsumerInbox();
                inbox.setConsumerGroup(consumerGroup);
                inbox.setEventId(event.getEventId());
                inbox.setEventType(event.getEventType());
                inboxMapper.insert(inbox);
                effect.accept(event);
            });
        } catch (DataIntegrityViolationException exception) {
            if (!seen(consumerGroup, event.getEventId())) {
                throw exception;
            }
        }
    }

    private boolean seen(String consumerGroup, String eventId) {
        return inboxMapper.selectCount(Wrappers.<ConsumerInbox>lambdaQuery()
                .eq(ConsumerInbox::getConsumerGroup, consumerGroup)
                .eq(ConsumerInbox::getEventId, eventId)) > 0;
    }
}
