package com.sayohno.marketing.campaign.dao.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.sayohno.marketing.common.mybatis.CreateOnlyEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("campaign_conversion")
public class CampaignConversion extends CreateOnlyEntity {
    private String eventId;
    private Long activityId;
    private Long channelId;
    private Long visitId;
    private Long userId;
    private Long voucherId;
    private Long settlementId;
    private String eventType;
    private LocalDateTime occurredAt;
}
