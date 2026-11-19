package com.sayohno.marketing.shortlink.dao.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.sayohno.marketing.common.mybatis.CreateOnlyEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("link_access_log")
public class LinkAccessLog extends CreateOnlyEntity {
    private String eventId;
    private Long linkId;
    private String visitorId;
    private String channelCode;
    private LocalDateTime visitTime;
    private String device;
    private String browser;
}
