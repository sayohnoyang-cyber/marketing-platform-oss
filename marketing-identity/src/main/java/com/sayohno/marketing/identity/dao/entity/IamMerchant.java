package com.sayohno.marketing.identity.dao.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.sayohno.marketing.common.mybatis.PersistentEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("iam_merchant")
public class IamMerchant extends PersistentEntity {
    private String name;
    private Long ownerUserId;
    private Integer status;
}
