package com.sayohno.marketing.campaign.dao.mapper;

import com.sayohno.marketing.campaign.web.dto.LinkListEntry;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Mapper
public interface CampaignLinkListMapper {
    long count(@Param("q") Map<String, Object> query);
    List<LinkListEntry> page(@Param("q") Map<String, Object> query, @Param("offset") long offset, @Param("size") long size);
    Map<String, Object> totals(@Param("q") Map<String, Object> query);
    List<Map<String, Object>> jumpSegments(@Param("merchantId") long merchantId);
    List<Map<String, Object>> entryVisits(@Param("merchantId") long merchantId, @Param("ids") List<Long> activityIds);
    List<Map<String, Object>> plainVisits(@Param("merchantId") long merchantId, @Param("ids") List<Long> linkIds);
    List<Map<String, Object>> options(@Param("merchantId") long merchantId, @Param("word") String word, @Param("offset") long offset, @Param("size") long size);
    com.sayohno.marketing.campaign.web.dto.ActivitySpotlight spotlight(@Param("merchantId") long merchantId, @Param("now") LocalDateTime now);
}
