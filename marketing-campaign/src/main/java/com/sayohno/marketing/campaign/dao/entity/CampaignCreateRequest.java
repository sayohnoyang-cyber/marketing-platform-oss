package com.sayohno.marketing.campaign.dao.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.sayohno.marketing.common.mybatis.PersistentEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("campaign_create_request")
public class CampaignCreateRequest extends PersistentEntity {
    private Long merchantId;
    private String idempotencyKey;
    private String requestHash;
    private Long activityId;
}
