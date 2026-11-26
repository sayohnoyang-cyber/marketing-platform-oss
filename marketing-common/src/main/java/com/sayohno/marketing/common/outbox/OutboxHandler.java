package com.sayohno.marketing.common.outbox;

/**
 * Outbox 事件处理器契约：每个处理器显式声明所属消费组，确保 MQ 模式下各领域消费组严格隔离、互不拖垮。
 */
public interface OutboxHandler {
    boolean supports(String eventType);

    /**
     * 所属消费组名称；同一事件类型可被不同消费组独立消费。
     */
    default String consumerGroup() {
        return getClass().getSimpleName();
    }

    void handle(OutboxEvent event);
}
