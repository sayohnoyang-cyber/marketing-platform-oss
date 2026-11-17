package com.sayohno.marketing.coupon.dao.entity;

import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.sayohno.marketing.common.mybatis.PersistentEntity;
import com.sayohno.marketing.coupon.enums.GrantBatchStatus;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("grant_batch")
public class GrantBatch extends PersistentEntity {
    private Long merchantId;
    private Long planId;
    private String batchCode;
    private String filePath;
    private LocalDateTime runAt;
    private GrantBatchStatus status;
    private Integer progress;
    private Integer totalCount;
    private Integer successCount;
    private Integer failCount;
    private String ownerToken;
    private LocalDateTime leaseUntil;
    private LocalDateTime completionTime;
    @TableLogic
    private Integer delFlag;
}
