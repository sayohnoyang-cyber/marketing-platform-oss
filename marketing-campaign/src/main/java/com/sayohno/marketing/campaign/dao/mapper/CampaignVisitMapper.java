package com.sayohno.marketing.campaign.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sayohno.marketing.campaign.dao.entity.CampaignVisit;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

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
}
