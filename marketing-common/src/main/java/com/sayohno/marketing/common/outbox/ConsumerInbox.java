package com.sayohno.marketing.common.outbox;

import com.baomidou.mybatisplus.annotation.TableName;
import com.sayohno.marketing.common.mybatis.CreateOnlyEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("infra_consumer_inbox")
public class ConsumerInbox extends CreateOnlyEntity {
    private String consumerGroup;
    private String eventId;
    private String eventType;
}
