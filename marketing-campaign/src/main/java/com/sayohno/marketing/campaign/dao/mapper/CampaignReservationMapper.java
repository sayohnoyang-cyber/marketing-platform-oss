package com.sayohno.marketing.campaign.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sayohno.marketing.campaign.dao.entity.CampaignReservation;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface CampaignReservationMapper extends BaseMapper<CampaignReservation> {
    @Select("""
            SELECT channel_id, COUNT(*) AS cnt
            FROM campaign_reservation
            WHERE activity_id = #{activityId} AND status = #{status}
            GROUP BY channel_id
            """)
    List<ChannelCountRow> countByChannel(@Param("activityId") long activityId, @Param("status") int status);

    @Select("""
            <script>
            SELECT activity_id, COUNT(*) AS cnt
            FROM campaign_reservation
            WHERE activity_id IN
            <foreach item="id" collection="activityIds" open="(" separator="," close=")">#{id}</foreach>
            AND status = #{status}
            GROUP BY activity_id
            </script>
            """)
    List<ActivityCountRow> countByActivityBatch(@Param("activityIds") List<Long> activityIds, @Param("status") int status);
}
