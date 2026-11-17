package com.sayohno.marketing.coupon.dao.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.sayohno.marketing.common.mybatis.PersistentEntity;
import com.sayohno.marketing.coupon.enums.ClaimTicketStatus;
import com.sayohno.marketing.coupon.enums.IssuerScope;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("claim_ticket")
public class ClaimTicket extends PersistentEntity {
    private Long userId;
    private Long planId;
    private Long inventoryId;
    private String requestId;
    private String requestHash;
    private IssuerScope issuerScope;
    private ClaimTicketStatus status;
    private Integer claimSeq;
    private Integer allocReceiveCount;
    private Long voucherId;
    private Integer redisApplied;
    private String reservationToken;
    private LocalDateTime leaseUntil;
}
