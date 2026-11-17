package com.sayohno.marketing.campaign.dao.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.sayohno.marketing.common.mybatis.PersistentEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("campaign_visit")
public class CampaignVisit extends PersistentEntity {
    private String visitTokenHash;
    private Long activityId;
    private Long channelId;
    private String visitorId;
    private Long userId;
    private LocalDateTime visitedAt;
    private LocalDateTime expireAt;
    private Integer validFlag;
}
