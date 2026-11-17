package com.sayohno.marketing.shortlink.dao.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.sayohno.marketing.common.mybatis.PersistentEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("link")
public class Link extends PersistentEntity {
    private Long merchantId;
    private Long activityId;
    private String gid;
    private String name;
    private String domain;
    private String shortUri;
    private String fullShortUrl;
    private String originUrl;
    private LocalDateTime validDate;
    private Integer enableStatus;
    private Long delTime;
    private Integer delFlag;
}
