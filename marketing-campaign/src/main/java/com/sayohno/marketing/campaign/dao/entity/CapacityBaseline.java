package com.sayohno.marketing.campaign.dao.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.sayohno.marketing.common.mybatis.PersistentEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("campaign_capacity_baseline")
public class CapacityBaseline extends PersistentEntity {
    private Long merchantId;
    private String deploymentId;
    private String reportName;
    private String endpoint;
    private Integer sustainedQps;
    private Integer p95Millis;
    private Integer p99Millis;
    private java.math.BigDecimal errorRate;
    private Integer durationSeconds;
    private Integer sampleCount;
    private java.time.LocalDateTime measuredAt;
    private java.time.LocalDateTime expiresAt;
}
