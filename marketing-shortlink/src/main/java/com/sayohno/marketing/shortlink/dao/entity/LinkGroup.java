package com.sayohno.marketing.shortlink.dao.entity;

import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.sayohno.marketing.common.mybatis.PersistentEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("link_group")
public class LinkGroup extends PersistentEntity {
    private String gid;
    private Long merchantId;
    private String name;
    private Integer sortOrder;
    private Integer status;
    @TableLogic
    private Integer delFlag;
}
