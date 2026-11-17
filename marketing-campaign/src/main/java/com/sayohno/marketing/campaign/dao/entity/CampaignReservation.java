package com.sayohno.marketing.campaign.dao.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.sayohno.marketing.common.mybatis.PersistentEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("campaign_reservation")
public class CampaignReservation extends PersistentEntity {
    private Long activityId;
    private Long userId;
    private Long channelId;
    private Integer status;
    private LocalDateTime reservedAt;
    private LocalDateTime cancelledAt;
}
