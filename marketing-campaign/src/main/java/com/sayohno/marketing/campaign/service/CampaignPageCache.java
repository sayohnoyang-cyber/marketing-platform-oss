package com.sayohno.marketing.campaign.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sayohno.marketing.campaign.dao.entity.CampaignActivity;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 活动落地页读缓存：缓存活动元信息、主链开关、渠道状态、券展示规则及投放配置。
 * 启用 Redis 时写入 Redis（TTL 5 分钟）；未启用 Redis 时使用短时本地缓存（TTL 30 秒），
 * 所有活动/渠道/投放变更均通过 evict(activityId) 主动失效。
 */
@Service
public class CampaignPageCache {
    private static final Duration REDIS_TTL = Duration.ofMinutes(5);
    private static final long LOCAL_TTL_MS = 30_000L;

    private final ObjectProvider<StringRedisTemplate> redis;
    private final ObjectMapper json;
    private final Map<Long, LocalEntry> localCache = new ConcurrentHashMap<>();
    /** 按活动 ID 维度做进程内 Singleflight 互斥，合并并发回源加载，防止缓存失效瞬间打穿数据库。 */
    private final Map<Long, Object> singleflightLocks = new ConcurrentHashMap<>();

    public CampaignPageCache(ObjectProvider<StringRedisTemplate> redis, ObjectMapper json) {
        this.redis = redis;
        this.json = json;
    }

    public PageSnapshot getOrLoad(long activityId, Supplier<PageSnapshot> loader) {
        PageSnapshot cached = read(activityId);
        if (cached != null) {
            return cached;
        }
        Object lock = singleflightLocks.computeIfAbsent(activityId, ignored -> new Object());
        try {
            synchronized (lock) {
                // 双重检查：并发线程排队进入后直接复用先行线程已回填的缓存结果。
                PageSnapshot doubleChecked = read(activityId);
                if (doubleChecked != null) {
                    return doubleChecked;
                }
                PageSnapshot loaded = loader.get();
                if (loaded != null) {
                    write(activityId, loaded);
                }
                return loaded;
            }
        } finally {
            singleflightLocks.remove(activityId, lock);
        }
    }

    public void evict(long activityId) {
        localCache.remove(activityId);
        var cache = redis.getIfAvailable();
        if (cache != null) {
            try {
                cache.delete(key(activityId));
            } catch (RuntimeException ignored) {
            }
        }
    }

    public Integer readLiveRemaining(long inventoryId) {
        var cache = redis.getIfAvailable();
        if (cache == null) {
            return null;
        }
        try {
            String raw = cache.opsForValue().get("offer:inventory:" + inventoryId);
            return raw == null ? null : Math.max(0, Integer.parseInt(raw));
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    public Map<String, Object> warm(long activityId, Supplier<PageSnapshot> loader) {
        PageSnapshot snapshot = loader.get();
        if (snapshot == null) {
            return Map.of("status", "FAILED", "detail", "活动不存在，无法预热读缓存");
        }
        var cache = redis.getIfAvailable();
        if (cache == null) {
            writeLocal(activityId, snapshot);
            return Map.of("status", "UNVERIFIED", "detail", "Redis未启用，已预热本机活动读缓存");
        }
        try {
            String serialized = json.writeValueAsString(snapshot);
            cache.opsForValue().set(key(activityId), serialized, REDIS_TTL);
            String readBack = cache.opsForValue().get(key(activityId));
            boolean matched = serialized.equals(readBack);
            return Map.of("status", matched ? "SUCCEEDED" : "FAILED",
                    "detail", matched ? "本场活动热点读缓存（含渠道与券展示规则）已写入并读回" : "活动读缓存不一致");
        } catch (Exception exception) {
            return Map.of("status", "FAILED", "detail", "活动读缓存无法验证");
        }
    }

    /** 保留兼容旧调用 */
    public Map<String, Object> view(CampaignActivity activity) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("activityId", activity.getId());
        data.put("name", activity.getName());
        data.put("startTime", activity.getStartTime().toString());
        data.put("endTime", activity.getEndTime().toString());
        return data;
    }

    private PageSnapshot read(long activityId) {
        var cache = redis.getIfAvailable();
        if (cache != null) {
            try {
                String raw = cache.opsForValue().get(key(activityId));
                if (raw != null && !raw.isBlank()) {
                    return json.readValue(raw, PageSnapshot.class);
                }
            } catch (Exception ignored) {
                try {
                    cache.delete(key(activityId));
                } catch (RuntimeException ignored2) {
                }
            }
            return null;
        }
        LocalEntry entry = localCache.get(activityId);
        if (entry != null && entry.loadedAtMs() + LOCAL_TTL_MS > System.currentTimeMillis()) {
            return entry.snapshot();
        }
        localCache.remove(activityId);
        return null;
    }

    private void write(long activityId, PageSnapshot snapshot) {
        var cache = redis.getIfAvailable();
        if (cache != null) {
            try {
                cache.opsForValue().set(key(activityId), json.writeValueAsString(snapshot), REDIS_TTL);
            } catch (Exception ignored) {
            }
            return;
        }
        writeLocal(activityId, snapshot);
    }

    private void writeLocal(long activityId, PageSnapshot snapshot) {
        if (localCache.size() > 10_000) {
            localCache.clear();
        }
        localCache.put(activityId, new LocalEntry(System.currentTimeMillis(), snapshot));
    }

    private String key(long activityId) {
        return "campaign:page:" + activityId;
    }

    private record LocalEntry(long loadedAtMs, PageSnapshot snapshot) {
    }

    public record ChannelSnapshot(long id, String code, String name, int status) {
    }

    public record PageSnapshot(
            long activityId,
            long merchantId,
            String name,
            String activityType,
            int status,
            int version,
            LocalDateTime warmupStartTime,
            LocalDateTime startTime,
            LocalDateTime endTime,
            Long mainLinkId,
            Integer mainEnabled,
            Integer mainLinkEnableStatus,
            Long planId,
            Long inventoryId,
            Integer quantity,
            Integer claimed,
            Integer maxClaimsPerUser,
            Map<String, Object> publicOffer,
            Map<String, ChannelSnapshot> channels
    ) {
    }
}
