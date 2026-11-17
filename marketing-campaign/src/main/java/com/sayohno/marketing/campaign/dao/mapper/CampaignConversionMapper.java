package com.sayohno.marketing.campaign.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sayohno.marketing.campaign.dao.entity.CampaignConversion;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface CampaignConversionMapper extends BaseMapper<CampaignConversion> {
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
}
