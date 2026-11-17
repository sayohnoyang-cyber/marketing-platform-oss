package com.sayohno.marketing.coupon.dao.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.sayohno.marketing.common.mybatis.PersistentEntity;
import com.sayohno.marketing.coupon.enums.RedemptionStatus;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("voucher_redemption")
public class VoucherRedemption extends PersistentEntity {
    private Long userId;
    private Long voucherId;
    private String orderNo;
    private BigDecimal discountAmount;
    private BigDecimal orderAmount;
    private BigDecimal refundAmount;
    private LocalDateTime refundedAt;
    private RedemptionStatus status;
}
