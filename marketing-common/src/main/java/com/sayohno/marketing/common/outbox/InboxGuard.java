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

    /**
     * 在同一本地事务内完成消费去重登记与业务处理，适用于纯数据库副作用的幂等消费者。
     */
    public void once(String consumerGroup, OutboxEvent event, Consumer<OutboxEvent> effect) {
        if (isSeen(consumerGroup, event.getEventId())) {
            return;
        }
        try {
            transactionTemplate.executeWithoutResult(status -> {
                insertInbox(consumerGroup, event);
                effect.accept(event);
            });
        } catch (DataIntegrityViolationException exception) {
            if (!isSeen(consumerGroup, event.getEventId())) {
                throw exception;
            }
        }
    }

    /**
     * 无外层事务的幂等消费守卫：先查去重表，在无外层事务条件下执行业务编排（如内部包含“独立短事务 + Redis 释放”），
     * 成功后再独立落库去重记录。避免外层大事务包裹 Redis 操作或吞掉回滚标记导致状态撕裂。
     */
    public void onceWithoutOuterTx(String consumerGroup, OutboxEvent event, Consumer<OutboxEvent> effect) {
        if (isSeen(consumerGroup, event.getEventId())) {
            return;
        }
        effect.accept(event);
        markSeen(consumerGroup, event);
    }

    /**
     * 判断指定消费组是否已经消费过该事件。
     */
    public boolean isSeen(String consumerGroup, String eventId) {
        return inboxMapper.selectCount(Wrappers.<ConsumerInbox>lambdaQuery()
                .eq(ConsumerInbox::getConsumerGroup, consumerGroup)
                .eq(ConsumerInbox::getEventId, eventId)) > 0;
    }

    /**
     * 幂等登记事件已消费；并发重复插入触发唯一约束时，仅当记录确已存在时静默忽略。
     */
    public void markSeen(String consumerGroup, OutboxEvent event) {
        try {
            transactionTemplate.executeWithoutResult(status -> insertInbox(consumerGroup, event));
        } catch (DataIntegrityViolationException exception) {
            if (!isSeen(consumerGroup, event.getEventId())) {
                throw exception;
            }
        }
    }

    private void insertInbox(String consumerGroup, OutboxEvent event) {
        ConsumerInbox inbox = new ConsumerInbox();
        inbox.setConsumerGroup(consumerGroup);
        inbox.setEventId(event.getEventId());
        inbox.setEventType(event.getEventType());
        inboxMapper.insert(inbox);
    }
}
