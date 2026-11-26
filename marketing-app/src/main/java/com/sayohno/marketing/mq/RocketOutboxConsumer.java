package com.sayohno.marketing.mq;

import com.sayohno.marketing.common.config.MarketingProperties;
import com.sayohno.marketing.common.outbox.OutboxEvent;
import com.sayohno.marketing.common.outbox.OutboxHandler;
import com.sayohno.marketing.common.outbox.OutboxPublisher;
import jakarta.annotation.PreDestroy;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.common.message.MessageExt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * RocketMQ Outbox 消费者装配器：
 * 按领域消费组严格隔离路由（券主链路、活动转化归因、活动转化审计、短链访问统计各跑独立消费组），
 * 避免单个域的消费失败引发全组重试与跨组死信污染。
 */
@Component
@ConditionalOnProperty(prefix = "marketing.mq", name = "enabled", havingValue = "true")
public class RocketOutboxConsumer implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(RocketOutboxConsumer.class);
    private final MarketingProperties properties;
    private final List<OutboxHandler> handlers;
    private final OutboxPublisher publisher;
    private final List<DefaultMQPushConsumer> consumers = new ArrayList<>();

    public RocketOutboxConsumer(MarketingProperties properties, List<OutboxHandler> handlers, OutboxPublisher publisher) {
        this.properties = properties;
        this.handlers = handlers;
        this.publisher = publisher;
    }

    @Override
    public void run(org.springframework.boot.ApplicationArguments args) throws Exception {
        start("marketing-coupon-consumer", "coupon.claim || coupon.claim.async || coupon.redeem || coupon.refund", false);
        start("marketing-campaign-conversion-consumer", "coupon.claim || coupon.redeem || coupon.refund", false);
        start("marketing-stats-consumer", "shortlink.visit", false);
        start("marketing-campaign-consumer", "campaign.conversion", false);
        if (properties.getMq().isDlqSubscribeEnabled()) {
            // RocketMQ 默认将 %DLQ% 主题设为只写（perm=2），仅当运维显式开启读权限时才订阅死信回标。
            start("marketing-coupon-dlq", "%DLQ%marketing-coupon-consumer", true);
            start("marketing-campaign-conversion-dlq", "%DLQ%marketing-campaign-conversion-consumer", true);
            start("marketing-stats-dlq", "%DLQ%marketing-stats-consumer", true);
            start("marketing-campaign-dlq", "%DLQ%marketing-campaign-consumer", true);
        }
    }

    private void start(String group, String expression, boolean deadLetter) throws Exception {
        DefaultMQPushConsumer created = new DefaultMQPushConsumer(group);
        created.setNamesrvAddr(properties.getMq().getNameServer());
        created.setMaxReconsumeTimes(3);
        if (deadLetter) {
            created.subscribe(expression, "*");
            created.registerMessageListener((MessageListenerConcurrently) (messages, context) -> {
                for (MessageExt message : messages) {
                    publisher.markDead(message.getKeys(), "进入死信队列 " + expression);
                }
                return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
            });
        } else {
            created.subscribe(properties.getMq().getTopic(), expression);
            created.registerMessageListener((MessageListenerConcurrently) (messages, context) -> consume(group, messages));
        }
        created.start();
        consumers.add(created);
    }

    ConsumeConcurrentlyStatus consume(String consumerGroup, List<MessageExt> messages) {
        for (MessageExt message : messages) {
            OutboxEvent event = new OutboxEvent();
            event.setEventId(message.getKeys());
            event.setEventType(message.getTags());
            event.setPayload(new String(message.getBody(), StandardCharsets.UTF_8));
            // 仅路由至归属本消费组且支持该事件类型的处理器，严格隔离消费组职责边界。
            List<OutboxHandler> matched = handlers.stream()
                    .filter(handler -> consumerGroup.equals(handler.consumerGroup()) && handler.supports(event.getEventType()))
                    .toList();
            if (matched.isEmpty()) {
                return ConsumeConcurrentlyStatus.RECONSUME_LATER;
            }
            try {
                for (OutboxHandler handler : matched) {
                    handler.handle(event);
                }
            } catch (RuntimeException exception) {
                log.error("事件消费失败，等待 RocketMQ 重试。group={}, eventId={}", consumerGroup, event.getEventId(), exception);
                return ConsumeConcurrentlyStatus.RECONSUME_LATER;
            }
        }
        return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
    }

    @PreDestroy
    public void shutdown() {
        for (DefaultMQPushConsumer consumer : consumers) {
            consumer.shutdown();
        }
    }
}
