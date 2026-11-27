package com.sayohno.marketing.identity.dao.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
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
    // 仅改密语句可以更新版本，普通用户资料写入不能覆盖它。
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Long sessionVersion;
    private Integer status;
}
