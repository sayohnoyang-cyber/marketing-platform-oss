package com.sayohno.marketing.campaign.dao.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.sayohno.marketing.common.mybatis.PersistentEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("campaign_offer")
public class CampaignOffer extends PersistentEntity {
    private Long activityId;
    private Long planId;
    private String storeCode;
    private Long inventoryId;
    private Integer sortOrder;
}
