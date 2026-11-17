package com.sayohno.marketing.coupon.dao.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.sayohno.marketing.common.mybatis.CreateOnlyEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("offer_plan_audit")
public class OfferPlanAudit extends CreateOnlyEntity {
    private Long planId;
    private Long operatorId;
    private String action;
    private String payload;
}
