package com.sayohno.marketing.coupon.dao.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.sayohno.marketing.common.mybatis.PersistentEntity;
import com.sayohno.marketing.coupon.enums.InventoryStatus;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("offer_inventory")
public class OfferInventory extends PersistentEntity {
    private Long activityId;
    private Long planId;
    private Long merchantId;
    private Integer quantity;
    private Integer claimedCount;
    private Integer maxClaimsPerUser;
    private InventoryStatus status;
    private String idempotencyKey;
}
