package com.sayohno.marketing.coupon.dao.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.sayohno.marketing.common.mybatis.CreateOnlyEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("grant_batch_error")
public class GrantBatchError extends CreateOnlyEntity {
    private Long batchId;
    private Integer rowNo;
    private Long userId;
    private String reason;
}
