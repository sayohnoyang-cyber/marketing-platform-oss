package com.sayohno.marketing.campaign.dao.mapper;

import lombok.Data;

/**
 * 按渠道与转化类型分组的批量统计投影行。
 */
@Data
public class ChannelEventCountRow {
    private Long channelId;
    private String eventType;
    private Long cnt;
}
