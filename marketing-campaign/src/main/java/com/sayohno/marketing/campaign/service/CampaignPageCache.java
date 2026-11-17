package com.sayohno.marketing.campaign.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sayohno.marketing.campaign.dao.entity.CampaignActivity;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class CampaignPageCache {
    private final ObjectProvider<StringRedisTemplate> redis;
    private final ObjectMapper json;
    public CampaignPageCache(ObjectProvider<StringRedisTemplate> redis, ObjectMapper json) { this.redis = redis; this.json = json; }

    public Map<String, Object> view(CampaignActivity activity) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("activityId", activity.getId()); data.put("name", activity.getName());
        data.put("startTime", activity.getStartTime().toString()); data.put("endTime", activity.getEndTime().toString());
        var cache = redis.getIfAvailable();
        if (cache != null) {
            try {
                String text = json.writeValueAsString(data);
                if (!text.equals(cache.opsForValue().get(key(activity)))) cache.opsForValue().set(key(activity), text, Duration.ofMinutes(5));
            } catch (Exception ignored) { /* Authoritative DB view remains available. */ }
        }
        return data;
    }

    public Map<String, Object> warm(CampaignActivity activity) {
        var cache = redis.getIfAvailable();
        if (cache == null) return Map.of("status", "UNVERIFIED", "detail", "Redis未启用，无法准备活动读缓存");
        var data = view(activity);
        try {
            boolean matched = json.writeValueAsString(data).equals(cache.opsForValue().get(key(activity)));
            return Map.of("status", matched ? "SUCCEEDED" : "FAILED", "detail", matched ? "本场活动版本的展示数据已写入并读回" : "活动读缓存不一致");
        } catch (Exception exception) { return Map.of("status", "FAILED", "detail", "活动读缓存无法验证"); }
    }

    private String key(CampaignActivity activity) { return "campaign:page:" + activity.getId() + ":" + activity.getVersion(); }
}
