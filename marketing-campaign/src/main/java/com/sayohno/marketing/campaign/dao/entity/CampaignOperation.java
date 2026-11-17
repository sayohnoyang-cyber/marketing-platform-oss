package com.sayohno.marketing.campaign.dao.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.sayohno.marketing.common.mybatis.PersistentEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("campaign_operation")
public class CampaignOperation extends PersistentEntity {
    private Long merchantId;
    private Long activityId;
    private String kind;
    private String requestKey;
    private String requestHash;
    private String configurationHash;
    private String status;
    private String executionToken;
    private String payloadJson;
}
