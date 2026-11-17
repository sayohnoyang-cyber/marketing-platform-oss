package com.sayohno.marketing.campaign.dao.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.sayohno.marketing.common.mybatis.PersistentEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("campaign_claim_intent")
public class CampaignClaimIntent extends PersistentEntity {
    private Long activityId;
    private Long inventoryId;
    private Long channelId;
    private Long visitId;
    private Long userId;
    private Long planId;
    private String requestId;
    private Long voucherId;
}
