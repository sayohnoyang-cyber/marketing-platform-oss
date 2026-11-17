package com.sayohno.marketing.campaign.dao.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.sayohno.marketing.common.mybatis.PersistentEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("campaign_activity")
public class CampaignActivity extends PersistentEntity {
    private Long merchantId;
    private String name;
    private String activityType;
    private LocalDateTime warmupStartTime;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Integer status;
    private Integer version;
    private Long mainLinkId;
    private Integer mainEnabled;
    private BigDecimal budgetAmount;
}
