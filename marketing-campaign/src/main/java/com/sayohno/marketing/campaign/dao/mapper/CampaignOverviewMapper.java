package com.sayohno.marketing.campaign.dao.mapper;

import com.sayohno.marketing.campaign.web.dto.TrafficBucket;
import org.apache.ibatis.annotations.*;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Mapper
public interface CampaignOverviewMapper {
    String VALID_VISIT = " v.valid_flag = 1 AND v.visitor_id IS NOT NULL AND v.visitor_id != '' AND v.visitor_id NOT IN ('landing','anonymous') ";

    @Select("""
            SELECT COUNT(DISTINCT v.visitor_id) FROM campaign_visit v
            JOIN campaign_activity a ON a.id = v.activity_id
            WHERE a.merchant_id = #{merchantId} AND v.visited_at >= #{start} AND v.visited_at < #{end} AND
            """ + VALID_VISIT)
    long visitors(@Param("merchantId") long merchantId, @Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    @Select("""
            SELECT COUNT(*) FROM campaign_reservation r JOIN campaign_activity a ON a.id = r.activity_id
            WHERE a.merchant_id = #{merchantId} AND r.status = 1 AND r.reserved_at >= #{start} AND r.reserved_at < #{end}
            """)
    long reservations(@Param("merchantId") long merchantId, @Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    @Select("""
            SELECT COUNT(DISTINCT v.visitor_id) FROM campaign_conversion c
            JOIN campaign_activity a ON a.id = c.activity_id
            JOIN campaign_visit v ON v.id = c.visit_id AND v.activity_id = c.activity_id
            WHERE a.merchant_id = #{merchantId} AND c.event_type = 'CLAIM'
            AND c.occurred_at >= #{start} AND c.occurred_at < #{end}
            AND v.visited_at >= #{start} AND v.visited_at < #{end} AND
            """ + VALID_VISIT)
    long convertedVisitors(@Param("merchantId") long merchantId, @Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    @Select("""
            <script>
            SELECT CAST(v.visited_at AS DATE) AS stat_date,
            <choose><when test="hourly">HOUR(v.visited_at)</when><otherwise>0</otherwise></choose> AS bucket_hour,
            COUNT(DISTINCT v.visitor_id) AS visitors
            FROM campaign_visit v JOIN campaign_activity a ON a.id = v.activity_id
            WHERE a.merchant_id = #{merchantId} AND v.visited_at >= #{start} AND v.visited_at &lt; #{end}
            AND v.valid_flag = 1 AND v.visitor_id IS NOT NULL AND v.visitor_id != '' AND v.visitor_id NOT IN ('landing','anonymous')
            GROUP BY CAST(v.visited_at AS DATE)
            <if test="hourly">, HOUR(v.visited_at)</if>
            ORDER BY stat_date, bucket_hour
            </script>
            """)
    List<TrafficBucket> buckets(@Param("merchantId") long merchantId, @Param("start") LocalDateTime start,
                               @Param("end") LocalDateTime end, @Param("hourly") boolean hourly);

    @Select("""
            SELECT COALESCE(ch.name, '直接访问') AS name, COUNT(DISTINCT v.visitor_id) AS visits
            FROM campaign_visit v JOIN campaign_activity a ON a.id = v.activity_id
            LEFT JOIN campaign_channel ch ON ch.id = v.channel_id AND ch.activity_id = a.id
            WHERE a.merchant_id = #{merchantId} AND v.visited_at >= #{start} AND v.visited_at < #{end} AND
            """ + VALID_VISIT + " GROUP BY COALESCE(ch.name, '直接访问') ORDER BY visits DESC")
    List<Map<String, Object>> sources(@Param("merchantId") long merchantId, @Param("start") LocalDateTime start, @Param("end") LocalDateTime end);
}
