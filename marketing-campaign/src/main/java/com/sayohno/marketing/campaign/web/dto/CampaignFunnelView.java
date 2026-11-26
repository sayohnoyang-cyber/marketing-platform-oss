package com.sayohno.marketing.campaign.web.dto;

import java.util.Map;

/**
 * 活动转化漏斗强类型统计视图。
 */
public record CampaignFunnelView(
        long clicks,
        long visits,
        long reservations,
        long claims,
        long redeems,
        long netRedeemed,
        long refunds
) {
    public Map<String, Object> toMap() {
        return Map.of(
                "clicks", clicks,
                "visits", visits,
                "reservations", reservations,
                "claims", claims,
                "redeems", redeems,
                "netRedeemed", netRedeemed,
                "refunds", refunds
        );
    }
}
