package com.sayohno.marketing.coupon.dao.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.sayohno.marketing.common.mybatis.CreateOnlyEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("offer_inventory_command")
public class OfferInventoryCommand extends CreateOnlyEntity {
    private Long inventoryId;
    private String idempotencyKey;
    private Integer targetQuantity;
    private Integer maxClaimsPerUser;
}
