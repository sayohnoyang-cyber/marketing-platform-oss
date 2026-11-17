package com.sayohno.marketing.campaign.dao.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.sayohno.marketing.common.mybatis.PersistentEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("campaign_channel")
public class CampaignChannel extends PersistentEntity {
    private Long activityId;
    private Long merchantId;
    private String channelCode;
    private String name;
    private Long linkId;
    private String idempotencyKey;
    private Integer status;
}
