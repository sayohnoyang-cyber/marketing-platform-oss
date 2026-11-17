package com.sayohno.marketing.identity.dao.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.sayohno.marketing.common.mybatis.PersistentEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("iam_merchant_member")
public class IamMerchantMember extends PersistentEntity {
    private Long merchantId;
    private Long userId;
    private String role;
    private Integer status;
}
