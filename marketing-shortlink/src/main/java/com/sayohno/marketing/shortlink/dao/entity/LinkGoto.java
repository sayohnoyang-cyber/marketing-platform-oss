package com.sayohno.marketing.shortlink.dao.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.sayohno.marketing.common.mybatis.PersistentEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("link_goto")
public class LinkGoto extends PersistentEntity {
    private String fullShortUrl;
    private String gid;
}
