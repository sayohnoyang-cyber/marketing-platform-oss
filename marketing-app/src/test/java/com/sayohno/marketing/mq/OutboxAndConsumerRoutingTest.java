package com.sayohno.marketing.mq;

import com.sayohno.marketing.common.config.MarketingProperties;
import com.sayohno.marketing.common.outbox.OutboxEvent;
import com.sayohno.marketing.common.outbox.OutboxEventMapper;
import com.sayohno.marketing.common.outbox.OutboxHandler;
import com.sayohno.marketing.common.outbox.OutboxPublisher;
import com.sayohno.marketing.common.outbox.OutboxTransport;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.common.message.MessageExt;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证 Commit 10 的 Outbox 批量状态回写（含逐条兜底降级）与 RocketMQ 消费组严格隔离路由。
 */
class OutboxAndConsumerRoutingTest {

    @Test
    void publishCycleBatchesSentStatusUpdates() {
        OutboxEventMapper mapper = mock(OutboxEventMapper.class);
        OutboxTransport transport = mock(OutboxTransport.class);
        MarketingProperties properties = new MarketingProperties();
        properties.getOutbox().setBatchSize(10);
        properties.getOutbox().setMaxPerCycle(20);

        List<OutboxEvent> batch = List.of(event(1L, "e-1"), event(2L, "e-2"), event(3L, "e-3"));
        when(mapper.findDue(any(), any(), anyInt())).thenReturn(batch);
        when(mapper.claim(anyLong(), any(), any())).thenReturn(1);
        when(mapper.markSentBatch(any(), any())).thenReturn(3);

        OutboxPublisher publisher = new OutboxPublisher(mapper, transport, properties);
        int delivered = publisher.publishCycle();

        assertEquals(3, delivered);
        verify(transport, times(3)).dispatch(any());
        verify(mapper, times(1)).markSentBatch(argThat(ids -> ids.equals(List.of(1L, 2L, 3L))), any(LocalDateTime.class));
        verify(mapper, never()).markSent(anyLong(), any());

        // 验证当 markSentBatch 异常时自动降级为逐条 markSent，避免已投递消息重复发送。
        OutboxEventMapper fallbackMapper = mock(OutboxEventMapper.class);
        when(fallbackMapper.findDue(any(), any(), anyInt())).thenReturn(List.of(event(10L, "e-10"), event(11L, "e-11")));
        when(fallbackMapper.claim(anyLong(), any(), any())).thenReturn(1);
        doThrow(new RuntimeException("batch update failed")).when(fallbackMapper).markSentBatch(any(), any());

        OutboxPublisher fallbackPublisher = new OutboxPublisher(fallbackMapper, transport, properties);
        int fallbackDelivered = fallbackPublisher.publishCycle();
        assertEquals(2, fallbackDelivered);
        verify(fallbackMapper, times(1)).markSent(eq(10L), any());
        verify(fallbackMapper, times(1)).markSent(eq(11L), any());
    }

    @Test
    void consumeRoutesStrictlyByConsumerGroup() {
        AtomicInteger couponHits = new AtomicInteger();
        AtomicInteger conversionHits = new AtomicInteger();
        OutboxHandler couponHandler = new OutboxHandler() {
            @Override
            public boolean supports(String eventType) {
                return "coupon.claim".equals(eventType);
            }

            @Override
            public String consumerGroup() {
                return "marketing-coupon-consumer";
            }

            @Override
            public void handle(OutboxEvent event) {
                couponHits.incrementAndGet();
            }
        };
        OutboxHandler conversionHandler = new OutboxHandler() {
            @Override
            public boolean supports(String eventType) {
                return "coupon.claim".equals(eventType);
            }

            @Override
            public String consumerGroup() {
                return "marketing-campaign-conversion-consumer";
            }

            @Override
            public void handle(OutboxEvent event) {
                conversionHits.incrementAndGet();
            }
        };

        RocketOutboxConsumer consumer = new RocketOutboxConsumer(
                new MarketingProperties(),
                List.of(couponHandler, conversionHandler),
                mock(OutboxPublisher.class));

        MessageExt message = new MessageExt();
        message.setKeys("evt-1");
        message.setTags("coupon.claim");
        message.setBody("{}".getBytes(StandardCharsets.UTF_8));

        assertEquals(ConsumeConcurrentlyStatus.CONSUME_SUCCESS,
                consumer.consume("marketing-coupon-consumer", List.of(message)));
        assertEquals(1, couponHits.get());
        assertEquals(0, conversionHits.get());

        assertEquals(ConsumeConcurrentlyStatus.CONSUME_SUCCESS,
                consumer.consume("marketing-campaign-conversion-consumer", List.of(message)));
        assertEquals(1, couponHits.get());
        assertEquals(1, conversionHits.get());

        assertEquals(ConsumeConcurrentlyStatus.RECONSUME_LATER,
                consumer.consume("marketing-unknown-consumer", List.of(message)));
        assertEquals(1, couponHits.get());
        assertEquals(1, conversionHits.get());
    }

    private static OutboxEvent event(long id, String eventId) {
        OutboxEvent event = new OutboxEvent();
        event.setId(id);
        event.setEventId(eventId);
        event.setEventType("coupon.claim");
        event.setPayload("{}");
        return event;
    }
}
