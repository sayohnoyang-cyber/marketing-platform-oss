package com.sayohno.marketing.campaign.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sayohno.marketing.campaign.dao.entity.CampaignCreateRequest;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface CampaignCreateRequestMapper extends BaseMapper<CampaignCreateRequest> {
    @Select("SELECT * FROM campaign_create_request WHERE merchant_id = #{merchantId} AND idempotency_key = #{key} FOR UPDATE")
    CampaignCreateRequest lockByKey(@Param("merchantId") long merchantId, @Param("key") String key);
}
