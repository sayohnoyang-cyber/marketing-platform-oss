package com.sayohno.marketing.campaign.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sayohno.marketing.campaign.dao.entity.CampaignActivity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface CampaignActivityMapper extends BaseMapper<CampaignActivity> {
    @Select("SELECT * FROM campaign_activity WHERE id = #{id} AND merchant_id = #{merchantId} FOR UPDATE")
    CampaignActivity lockOwned(@Param("id") long id, @Param("merchantId") long merchantId);

    @Select("""
            SELECT DISTINCT a.* FROM campaign_activity a
            JOIN campaign_offer co ON co.activity_id = a.id
            JOIN offer_inventory oi ON oi.id = co.inventory_id
            WHERE oi.status = 'open'
              AND a.status IN (1, 4, 5, 6)
              AND (a.status = 6 OR a.end_time <= #{now})
            """)
    List<CampaignActivity> findDueForRecycle(@Param("now") LocalDateTime now);
}
