package com.sayohno.marketing.campaign.web.dto;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 单渠道归因与转化统计强类型视图。
 */
public record ChannelStatsView(
        Long channelId,
        String name,
        String kind,
        boolean enabled,
        long clicks,
        long visits,
        long claims,
        long redeems,
        long refunds,
        BigDecimal refundAmount,
        BigDecimal knownRefundAmount,
        long unreportedRefunds,
        String refundAmountStatus,
        long reservations
) {
    public Map<String, Object> toMap() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("channelId", channelId);
        row.put("name", name);
        row.put("kind", kind);
        row.put("enabled", enabled);
        row.put("clicks", clicks);
        row.put("visits", visits);
        row.put("claims", claims);
        row.put("redeems", redeems);
        row.put("refunds", refunds);
        row.put("refundAmount", refundAmount);
        row.put("knownRefundAmount", knownRefundAmount);
        row.put("unreportedRefunds", unreportedRefunds);
        row.put("refundAmountStatus", refundAmountStatus);
        row.put("reservations", reservations);
        return row;
    }
}
