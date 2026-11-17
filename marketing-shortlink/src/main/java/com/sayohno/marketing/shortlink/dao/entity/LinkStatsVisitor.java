package com.sayohno.marketing.shortlink.dao.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.sayohno.marketing.common.mybatis.CreateOnlyEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("link_stats_visitor")
public class LinkStatsVisitor extends CreateOnlyEntity {
    private Long linkId;
    private LocalDate statDate;
    private String visitorId;
}
