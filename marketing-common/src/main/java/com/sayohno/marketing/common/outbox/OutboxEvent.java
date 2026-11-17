package com.sayohno.marketing.common.outbox;

import com.baomidou.mybatisplus.annotation.TableName;
import com.sayohno.marketing.common.mybatis.PersistentEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("infra_outbox_event")
public class OutboxEvent extends PersistentEntity {
    private String eventId;
    private String eventType;
    private String aggregateId;
    private String payload;
    private Integer status;
    private Integer retryCount;
    private LocalDateTime nextRetryAt;
    private String lastError;
}
