package com.sayohno.marketing.coupon.dao.entity;

import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.sayohno.marketing.common.mybatis.PersistentEntity;
import com.sayohno.marketing.coupon.enums.IssuerScope;
import com.sayohno.marketing.coupon.enums.VoucherStatus;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("issued_voucher")
public class IssuedVoucher extends PersistentEntity {
    private Long userId;
    private Long planId;
    private Long inventoryId;
    private Integer claimSeq;
    private IssuerScope issuerScope;
    private LocalDateTime grantedAt;
    private LocalDateTime usableFrom;
    private LocalDateTime usableUntil;
    private VoucherStatus status;
    @TableLogic
    private Integer delFlag;
}
