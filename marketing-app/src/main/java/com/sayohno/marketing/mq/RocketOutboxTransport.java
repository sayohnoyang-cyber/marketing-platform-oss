package com.sayohno.marketing.mq;

import com.sayohno.marketing.common.config.MarketingProperties;
import com.sayohno.marketing.common.outbox.OutboxEvent;
import com.sayohno.marketing.common.outbox.OutboxTransport;
import jakarta.annotation.PreDestroy;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.common.message.Message;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Component
@ConditionalOnProperty(prefix = "marketing.mq", name = "enabled", havingValue = "true")
public class RocketOutboxTransport implements OutboxTransport {
    private final MarketingProperties properties;
    private volatile DefaultMQProducer producer;

    public RocketOutboxTransport(MarketingProperties properties) {
        this.properties = properties;
    }

    @Override
    public void dispatch(OutboxEvent event) {
        try {
            Message message = new Message(properties.getMq().getTopic(), event.getEventType(), event.getEventId(),
                    event.getPayload().getBytes(StandardCharsets.UTF_8));
            SendResult result = producer().send(message);
            if (result == null || result.getSendStatus() != SendStatus.SEND_OK) {
                throw new IllegalStateException("RocketMQ 发送状态异常: " + (result == null ? "空结果" : result.getSendStatus()));
            }
        } catch (Exception exception) {
            throw new IllegalStateException("RocketMQ 发送失败: " + exception.getMessage(), exception);
        }
    }

    private DefaultMQProducer producer() throws Exception {
        if (producer == null) {
            synchronized (this) {
                if (producer == null) {
                    DefaultMQProducer created = new DefaultMQProducer("marketing-outbox");
                    created.setNamesrvAddr(properties.getMq().getNameServer());
                    created.start();
                    producer = created;
                }
            }
        }
        return producer;
    }

    @PreDestroy
    public void shutdown() {
        if (producer != null) {
            producer.shutdown();
        }
    }
}
