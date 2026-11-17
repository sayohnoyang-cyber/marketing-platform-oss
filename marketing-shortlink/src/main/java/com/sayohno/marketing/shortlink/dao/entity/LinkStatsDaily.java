package com.sayohno.marketing.shortlink.dao.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.sayohno.marketing.common.mybatis.PersistentEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("link_stats_daily")
public class LinkStatsDaily extends PersistentEntity {
    private Long linkId;
    private LocalDate statDate;
    private Long pv;
    private Long uv;
    private Long uip;
}
