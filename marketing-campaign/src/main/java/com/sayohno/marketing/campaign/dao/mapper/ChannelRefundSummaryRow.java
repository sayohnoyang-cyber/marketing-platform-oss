package com.sayohno.marketing.campaign.dao.mapper;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 按渠道分组的退款汇总投影行（channelId 为 null 代表直达访问）。
 */
@Data
public class ChannelRefundSummaryRow {
    private Long channelId;
    private long refundCount;
    private long amountCount;
    private BigDecimal knownAmount;
}
