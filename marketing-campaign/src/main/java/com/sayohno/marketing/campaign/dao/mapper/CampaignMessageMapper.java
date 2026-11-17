package com.sayohno.marketing.campaign.dao.mapper;

import com.sayohno.marketing.campaign.web.dto.MessageSample;
import org.apache.ibatis.annotations.*;
import java.time.LocalDateTime;

@Mapper
public interface CampaignMessageMapper {
    String RECEIVED = """
        EXISTS (SELECT 1 FROM infra_consumer_inbox i WHERE i.event_id = e.event_id AND i.consumer_group =
          CASE WHEN e.event_type = 'shortlink.visit' THEN 'shortlink-stats'
               WHEN e.event_type = 'campaign.conversion' THEN 'campaign-report' ELSE 'coupon-event' END)
        AND (e.event_type NOT IN ('coupon.redeem','coupon.refund') OR
          EXISTS (SELECT 1 FROM infra_consumer_inbox i WHERE i.event_id = e.event_id AND i.consumer_group = 'campaign-conversion'))
        """;
    String SCOPE = """
        ((e.event_type = 'campaign.conversion' AND e.aggregate_id = CONCAT('', #{activityId}))
         OR (e.event_type IN ('coupon.claim','coupon.redeem','coupon.refund') AND EXISTS (
           SELECT 1 FROM campaign_claim_intent ci WHERE ci.activity_id = #{activityId}
             AND e.aggregate_id = CONCAT('', ci.voucher_id)))
         OR (e.event_type = 'shortlink.visit' AND EXISTS (
           SELECT 1 FROM campaign_activity a WHERE a.id = #{activityId} AND e.aggregate_id = CONCAT('', a.main_link_id))))
        """;

    @Select("""
        SELECT COUNT(*) AS total,
          COALESCE(SUM(CASE WHEN s.status = 0 THEN 1 ELSE 0 END),0) AS pending,
          COALESCE(SUM(CASE WHEN s.status = 1 THEN 1 ELSE 0 END),0) AS sending,
          COALESCE(SUM(CASE WHEN s.status = 3 THEN 1 ELSE 0 END),0) AS dead,
          COALESCE(SUM(CASE WHEN s.status IN (0,1) AND s.retry_count > 0 THEN 1 ELSE 0 END),0) AS retrying,
          COALESCE(SUM(CASE WHEN s.status = 2 AND s.received = 0 THEN 1 ELSE 0 END),0) AS awaiting_receipt,
          COALESCE(SUM(CASE WHEN s.received = 1 AND s.consumed_at >= #{start} THEN 1 ELSE 0 END),0) AS completed,
          COALESCE(SUM(CASE WHEN s.status = 1 AND s.update_time < #{stale} THEN 1 ELSE 0 END),0) AS stalled,
          MIN(CASE WHEN s.status IN (0,1) OR (s.status = 2 AND s.received = 0) THEN s.create_time ELSE NULL END) AS oldest_pending,
          MAX(CASE WHEN s.received = 1 THEN s.consumed_at ELSE NULL END) AS last_consumed_at
        FROM (SELECT e.*, CASE WHEN
        """ + RECEIVED + """
          THEN 1 ELSE 0 END AS received,
          (SELECT MAX(i.create_time) FROM infra_consumer_inbox i WHERE i.event_id = e.event_id
            AND i.consumer_group IN ('shortlink-stats','campaign-report','coupon-event','campaign-conversion')) AS consumed_at
          FROM infra_outbox_event e WHERE
        """ + SCOPE + ") s")
    MessageSample sample(@Param("activityId") long activityId, @Param("start") LocalDateTime start, @Param("stale") LocalDateTime stale);
}
