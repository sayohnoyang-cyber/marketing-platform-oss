package com.sayohno.marketing.shortlink.dao.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.sayohno.marketing.common.mybatis.PersistentEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("link_create_request")
public class LinkCreateRequest extends PersistentEntity {
    private Long merchantId;
    private String idempotencyKey;
    private String requestHash;
    private Long linkId;
}
