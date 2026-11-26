package com.sayohno.marketing.campaign.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sayohno.marketing.campaign.dao.entity.CampaignVisit;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface CampaignVisitMapper extends BaseMapper<CampaignVisit> {
    @Select("""
            <script>
            SELECT COUNT(DISTINCT visitor_id) FROM campaign_visit
            WHERE activity_id = #{activityId} AND valid_flag = 1 AND visitor_id IS NOT NULL AND visitor_id != '' AND visitor_id NOT IN ('landing', 'anonymous')
            <choose><when test="channelId != null">AND channel_id = #{channelId}</when>
            <otherwise>AND channel_id IS NULL</otherwise></choose>
            </script>
            """)
    long uniqueVisitors(@Param("activityId") long activityId, @Param("channelId") Long channelId);

    @Select("""
            SELECT COUNT(DISTINCT visitor_id) FROM campaign_visit
            WHERE activity_id = #{activityId} AND valid_flag = 1 AND visitor_id IS NOT NULL AND visitor_id != '' AND visitor_id NOT IN ('landing', 'anonymous')
            """)
    long uniqueActivityVisitors(@Param("activityId") long activityId);

    @Select("""
            SELECT channel_id, COUNT(DISTINCT visitor_id) AS cnt
            FROM campaign_visit
            WHERE activity_id = #{activityId} AND valid_flag = 1
              AND visitor_id IS NOT NULL AND visitor_id != '' AND visitor_id NOT IN ('landing', 'anonymous')
            GROUP BY channel_id
            """)
    List<ChannelCountRow> uniqueVisitorsByChannel(@Param("activityId") long activityId);

    @Select("""
            <script>
            SELECT activity_id, COUNT(DISTINCT visitor_id) AS cnt
            FROM campaign_visit
            WHERE activity_id IN
            <foreach item="id" collection="activityIds" open="(" separator="," close=")">#{id}</foreach>
            AND valid_flag = 1 AND visitor_id IS NOT NULL AND visitor_id != '' AND visitor_id NOT IN ('landing', 'anonymous')
            GROUP BY activity_id
            </script>
            """)
    List<ActivityCountRow> uniqueActivityVisitorsBatch(@Param("activityIds") List<Long> activityIds);

    @Select("""
            <script>
            SELECT COUNT(DISTINCT v.visitor_id) FROM campaign_visit v
            WHERE v.activity_id = #{activityId}
              AND v.valid_flag = 1
              AND v.visitor_id IS NOT NULL AND v.visitor_id != '' AND v.visitor_id NOT IN ('landing', 'anonymous')
              AND (
                v.channel_id IS NULL
                <if test="activeChannelIds != null and !activeChannelIds.isEmpty()">
                  OR v.channel_id IN
                  <foreach item="cid" collection="activeChannelIds" open="(" separator="," close=")">#{cid}</foreach>
                </if>
              )
              AND (
                v.user_id IS NULL OR NOT EXISTS (
                  SELECT 1 FROM campaign_reservation r
                  WHERE r.activity_id = #{activityId} AND r.user_id = v.user_id AND r.status = 1
                )
              )
            </script>
            """)
    long unreservedActiveVisitors(@Param("activityId") long activityId, @Param("activeChannelIds") List<Long> activeChannelIds);

    @Select("""
            SELECT id FROM campaign_visit
            WHERE visited_at < #{cutoff}
              AND NOT EXISTS (SELECT 1 FROM campaign_claim_intent i WHERE i.visit_id = campaign_visit.id)
              AND NOT EXISTS (SELECT 1 FROM campaign_conversion c WHERE c.visit_id = campaign_visit.id)
            ORDER BY id
            LIMIT #{limit}
            """)
    List<Long> findExpiredUnreferencedIds(@Param("cutoff") LocalDateTime cutoff, @Param("limit") int limit);

    @Delete("""
            DELETE FROM campaign_visit
            WHERE visited_at < #{cutoff}
              AND NOT EXISTS (SELECT 1 FROM campaign_claim_intent i WHERE i.visit_id = campaign_visit.id)
              AND NOT EXISTS (SELECT 1 FROM campaign_conversion c WHERE c.visit_id = campaign_visit.id)
            """)
    int deleteExpiredUnreferenced(@Param("cutoff") LocalDateTime cutoff);
}
