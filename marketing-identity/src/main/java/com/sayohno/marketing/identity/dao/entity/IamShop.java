package com.sayohno.marketing.identity.dao.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.sayohno.marketing.common.mybatis.PersistentEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("iam_shop")
public class IamShop extends PersistentEntity {
    private Long merchantId;
    private String storeCode;
    private String name;
    private Integer status;
}
