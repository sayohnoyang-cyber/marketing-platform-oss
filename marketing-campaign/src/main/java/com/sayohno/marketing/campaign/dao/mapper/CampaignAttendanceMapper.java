package com.sayohno.marketing.campaign.dao.mapper;

import com.sayohno.marketing.campaign.web.dto.AttendanceSample;
import org.apache.ibatis.annotations.*;
import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface CampaignAttendanceMapper {
    @Select("""
        SELECT r.channel_id, ch.name, COUNT(*) AS reservations,
          SUM(CASE WHEN EXISTS (
            SELECT 1 FROM campaign_visit v WHERE v.activity_id = r.activity_id
              AND v.valid_flag = 1 AND v.visitor_id IS NOT NULL AND v.visitor_id != ''
              AND v.visitor_id NOT IN ('landing', 'anonymous') AND v.visited_at >= #{start} AND v.visited_at < #{end}
              AND (v.user_id = r.user_id OR EXISTS (
                SELECT 1 FROM campaign_visit b WHERE b.activity_id = r.activity_id AND b.user_id = r.user_id
                  AND b.visitor_id = v.visitor_id AND b.valid_flag = 1 AND b.visited_at < #{start}
              ))
          ) OR EXISTS (
            SELECT 1 FROM campaign_conversion c WHERE c.activity_id = r.activity_id AND c.user_id = r.user_id
              AND c.event_type = 'CLAIM' AND c.occurred_at >= #{start} AND c.occurred_at < #{end}
          ) THEN 1 ELSE 0 END) AS arrivals
        FROM campaign_reservation r LEFT JOIN campaign_channel ch ON ch.id = r.channel_id AND ch.activity_id = r.activity_id
        WHERE r.activity_id = #{activityId} AND r.reserved_at < #{start}
          AND (r.status = 1 OR r.cancelled_at >= #{start})
        GROUP BY r.channel_id, ch.name
        """)
    List<AttendanceSample> opening(@Param("activityId") long activityId,
                                  @Param("start") LocalDateTime start, @Param("end") LocalDateTime end);
}
