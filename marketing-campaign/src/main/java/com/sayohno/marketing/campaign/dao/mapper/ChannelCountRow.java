package com.sayohno.marketing.campaign.dao.mapper;

import lombok.Data;

/**
 * 按渠道分组计数查询投影行（channelId 为 null 代表直达访问）。
 */
@Data
public class ChannelCountRow {
    private Long channelId;
    private Long cnt;
}
