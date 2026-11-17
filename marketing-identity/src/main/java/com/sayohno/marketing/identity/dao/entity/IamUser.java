package com.sayohno.marketing.identity.dao.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.sayohno.marketing.common.mybatis.PersistentEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("iam_user")
public class IamUser extends PersistentEntity {
    private String username;
    private String passwordHash;
    private Integer status;
}
