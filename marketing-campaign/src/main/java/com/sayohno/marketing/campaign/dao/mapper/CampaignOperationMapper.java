package com.sayohno.marketing.campaign.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sayohno.marketing.campaign.dao.entity.CampaignOperation;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface CampaignOperationMapper extends BaseMapper<CampaignOperation> {
    @org.apache.ibatis.annotations.Select("SELECT id FROM campaign_activity WHERE id = #{id} FOR UPDATE")
    Long lockActivity(@org.apache.ibatis.annotations.Param("id") long id);
}
