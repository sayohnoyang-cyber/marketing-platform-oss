package com.sayohno.marketing.campaign.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sayohno.marketing.campaign.dao.entity.CampaignConversion;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface CampaignConversionMapper extends BaseMapper<CampaignConversion> {
    @org.apache.ibatis.annotations.Select("""
            SELECT c.channel_id AS channel_id,
                   COUNT(*) AS refund_count,
                   COUNT(s.refund_amount) AS amount_count,
                   COALESCE(SUM(s.refund_amount), 0) AS known_amount
            FROM campaign_conversion c
            LEFT JOIN voucher_redemption s ON s.id = c.settlement_id AND s.voucher_id = c.voucher_id
            WHERE c.activity_id = #{activityId} AND c.event_type = 'REFUND'
            GROUP BY c.channel_id
            """)
    java.util.List<ChannelRefundSummaryRow> refundsByChannel(@org.apache.ibatis.annotations.Param("activityId") long activityId);

    @org.apache.ibatis.annotations.Select("""
            SELECT channel_id, event_type, COUNT(*) AS cnt
            FROM campaign_conversion
            WHERE activity_id = #{activityId}
            GROUP BY channel_id, event_type
            """)
    java.util.List<ChannelEventCountRow> countByChannelAndType(@org.apache.ibatis.annotations.Param("activityId") long activityId);

    @org.apache.ibatis.annotations.Select("""
        <script>
        SELECT COUNT(*) AS refund_count, COUNT(s.refund_amount) AS amount_count,
          COALESCE(SUM(s.refund_amount), 0) AS known_amount
        FROM campaign_conversion c LEFT JOIN voucher_redemption s ON s.id = c.settlement_id AND s.voucher_id = c.voucher_id
        WHERE c.activity_id = #{activityId} AND c.event_type = 'REFUND'
        <if test="byChannel">
          <choose><when test="channelId != null">AND c.channel_id = #{channelId}</when><otherwise>AND c.channel_id IS NULL</otherwise></choose>
        </if>
        </script>
        """)
    com.sayohno.marketing.campaign.web.dto.RefundSummary refunds(
        @org.apache.ibatis.annotations.Param("activityId") long activityId,
        @org.apache.ibatis.annotations.Param("channelId") Long channelId,
        @org.apache.ibatis.annotations.Param("byChannel") boolean byChannel);

    @org.apache.ibatis.annotations.Select("""
            <script>
            SELECT activity_id, COUNT(*) AS cnt
            FROM campaign_conversion
            WHERE activity_id IN
            <foreach item="id" collection="activityIds" open="(" separator="," close=")">#{id}</foreach>
            AND event_type = #{eventType}
            GROUP BY activity_id
            </script>
            """)
    java.util.List<ActivityCountRow> countByTypeBatch(
            @org.apache.ibatis.annotations.Param("activityIds") java.util.List<Long> activityIds,
            @org.apache.ibatis.annotations.Param("eventType") String eventType);

    /** 统计本场活动中当前仍处于已核销状态（未退款）的去重券数，作为核销率分子以避免退款重核导致超过 100%。 */
    @org.apache.ibatis.annotations.Select("""
            SELECT COUNT(DISTINCT c.voucher_id)
            FROM campaign_conversion c
            JOIN issued_voucher v ON v.id = c.voucher_id
            WHERE c.activity_id = #{activityId} AND c.event_type = 'REDEEM' AND v.status = 'redeemed'
            """)
    long countNetRedeemedVouchers(@org.apache.ibatis.annotations.Param("activityId") long activityId);
}
