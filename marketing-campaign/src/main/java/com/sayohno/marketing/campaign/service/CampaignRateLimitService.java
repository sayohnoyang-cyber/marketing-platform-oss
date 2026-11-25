package com.sayohno.marketing.campaign.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sayohno.marketing.campaign.dao.entity.CampaignOperation;
import com.sayohno.marketing.campaign.dao.mapper.CampaignOperationMapper;
import com.sayohno.marketing.campaign.web.dto.ApplyLimitRequest;
import com.sayohno.marketing.common.config.MarketingProperties;
import com.sayohno.marketing.common.exception.BizException;
import com.sayohno.marketing.common.result.ErrorCode;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class CampaignRateLimitService {
    private final CampaignOperationMapper mapper;
    private final CampaignOperationStore store;
    private final CampaignConfigurationService configuration;
    private final CapacityBaselineService capacity;
    private final ObjectProvider<StringRedisTemplate> redis;
    private final MarketingProperties properties;
    private final CampaignService campaigns;
    private final Clock clock;
    private final DefaultRedisScript<Long> script;
    private final DefaultRedisScript<Long> userScript;
    private final Map<Long, Decision> decisions = new ConcurrentHashMap<>();

    public CampaignRateLimitService(CampaignOperationMapper mapper, CampaignOperationStore store, CampaignConfigurationService configuration,
            CapacityBaselineService capacity, ObjectProvider<StringRedisTemplate> redis, MarketingProperties properties, CampaignService campaigns, Clock clock) {
        this.mapper = mapper; this.store = store; this.configuration = configuration; this.capacity = capacity;
        this.redis = redis; this.properties = properties; this.campaigns = campaigns; this.clock = clock;
        this.script = new DefaultRedisScript<>(); script.setLocation(new ClassPathResource("lua/activity_rate_limit.lua")); script.setResultType(Long.class);
        this.userScript = new DefaultRedisScript<>(); userScript.setLocation(new ClassPathResource("lua/activity_user_rate_limit.lua")); userScript.setResultType(Long.class);
    }

    @Transactional
    public Map<String, Object> apply(long activityId, ApplyLimitRequest request) {
        campaigns.requireOwned(activityId);
        var template = redis.getIfAvailable();
        if (template == null) throw new BizException(ErrorCode.CONFLICT, "Redis未启用，不能应用分布式限流");
        try (var connection = template.getConnectionFactory().getConnection()) { connection.ping(); }
        catch (RuntimeException exception) { throw new BizException(ErrorCode.CONFLICT, "Redis不可用，限流策略未应用"); }
        mapper.lockActivity(activityId);
        String config = configuration.fingerprint(activityId);
        String hash = store.hash(request.forecastId(), config);
        var old = store.existing(activityId, "LIMIT", request.requestKey(), hash);
        if (old != null) return store.present(old);
        var forecast = store.require(activityId, request.forecastId(), "FORECAST");
        if (!config.equals(forecast.getConfigurationHash())) throw new BizException(ErrorCode.CONFLICT, "预测配置已过期，请重新保存预测");
        Map<String, Object> data = store.decode(forecast);
        Object baselineId = data.get("baselineId");
        var baseline = capacity.requireUsable(baselineId == null ? null : Long.parseLong(baselineId.toString()));
        @SuppressWarnings("unchecked") List<Map<String, Object>> scenarios = (List<Map<String, Object>>) data.get("scenarios");
        var selected = scenarios.stream().filter(row -> row.get("key").equals(data.get("scenario"))).findFirst().orElseThrow();
        // Long values use string serialization globally, including persisted JSON snapshots.
        double demand = Double.parseDouble(selected.get("qps").toString());
        if (demand <= 0) throw new BizException(ErrorCode.VALIDATION, "预测需求为零，不能生成入口限流建议");
        int total = Math.max(1, (int) Math.min(demand, capacity.safeQps(baseline)));
        @SuppressWarnings("unchecked") List<Map<String, Object>> rows = (List<Map<String, Object>>) data.get("rows");
        double allArrivals = ((Number) data.get("arrivals")).doubleValue();
        Map<String, Integer> sources = new LinkedHashMap<>();
        for (var row : rows) {
            double share = allArrivals > 0 ? ((Number) row.get("expected")).doubleValue() / allArrivals : 1.0 / Math.max(1, rows.size());
            sources.put(String.valueOf(row.get("key")), Math.max(1, (int) Math.floor(total * share)));
        }
        Map<String, Object> policy = new LinkedHashMap<>();
        policy.put("forecastId", forecast.getId()); policy.put("baselineId", baseline.getId()); policy.put("totalQps", total);
        policy.put("sources", sources); policy.put("expiresAt", baseline.getExpiresAt()); policy.put("deploymentId", baseline.getDeploymentId());
        policy.put("scope", "仅活动领券请求；活动总额度和来源额度同时生效，切换渠道不能绕过活动总额度");
        deactivate(activityId);
        return store.present(store.create(activityId, "LIMIT", request.requestKey(), hash, config, "ACTIVE", policy));
    }

    public Map<String, Object> current(long activityId) {
        campaigns.requireOwned(activityId);
        var row = active(activityId);
        if (row == null) return Map.of("status", "INACTIVE");
        return store.present(row);
    }

    @Transactional
    public void disable(long activityId) { campaigns.requireOwned(activityId); deactivate(activityId); }

    private void deactivate(long activityId) {
        mapper.update(null, Wrappers.<CampaignOperation>lambdaUpdate().eq(CampaignOperation::getActivityId, activityId)
                .eq(CampaignOperation::getKind, "LIMIT").eq(CampaignOperation::getStatus, "ACTIVE").set(CampaignOperation::getStatus, "INACTIVE"));
        decisions.remove(activityId);
    }

    private CampaignOperation active(long activityId) {
        return mapper.selectOne(Wrappers.<CampaignOperation>lambdaQuery().eq(CampaignOperation::getActivityId, activityId)
                .eq(CampaignOperation::getKind, "LIMIT").eq(CampaignOperation::getStatus, "ACTIVE").orderByDesc(CampaignOperation::getId).last("LIMIT 1"));
    }

    public void admit(long activityId, Long channelId) {
        admit(activityId, channelId, null);
    }

    /**
     * 前置单用户频控：在查询访问凭证等任何数据库操作之前执行，优先拦住刷子流量，避免打穿 MySQL。
     */
    public void admitUser(long activityId, Long userId) {
        int perUserQps = userId == null ? 0 : Math.max(0, properties.getCapacity().getPerUserClaimQps());
        if (perUserQps <= 0) {
            return;
        }
        var template = redis.getIfAvailable();
        if (template == null) {
            return;
        }
        String userKey = "campaign:rate:{" + activityId + "}:user:" + userId;
        Long admitted;
        try {
            admitted = template.execute(userScript, List.of(userKey), String.valueOf(perUserQps));
        } catch (RuntimeException exception) {
            throw new BizException(ErrorCode.CONFLICT, "限流服务暂不可用，请稍后重试");
        }
        if (!Long.valueOf(1).equals(admitted)) {
            throw new BizException(ErrorCode.RATE_LIMITED, "当前入口访问较多，请稍后重试");
        }
    }

    /**
     * 活动总额度与来源额度限流（不含单用户频控，供前置 admitUser 之后调用，避免单用户桶被重复计数）。
     */
    public void admitActivityAndSource(long activityId, Long channelId) {
        admitInternal(activityId, channelId, null, false);
    }

    /**
     * 领券入口限流，应在任何数据库写入之前调用。
     * 先检查单用户频控，再原子检查活动总额度与来源额度；策略失效时回落到默认保护额度。
     */
    public void admit(long activityId, Long channelId, Long userId) {
        admitInternal(activityId, channelId, userId, true);
    }

    private void admitInternal(long activityId, Long channelId, Long userId, boolean checkPerUser) {
        Decision decision = decision(activityId);
        if (decision.totalQps() <= 0) return;
        var template = redis.getIfAvailable();
        if (template == null) {
            // 未启用 Redis 的部署无法做分布式计数；默认保护放行，交给库存脚本兜底，显式策略则拒绝以免误以为已限流。
            if (decision.fallback()) return;
            throw new BizException(ErrorCode.CONFLICT, "限流服务暂不可用");
        }
        String source = channelId == null ? "direct" : channelId.toString();
        String prefix = "campaign:rate:{" + activityId + "}:" + (decision.fallback() ? "default" : decision.policyId());
        int sourceQps = decision.fallback() ? decision.totalQps() : decision.sources().getOrDefault(source, 1);
        int perUserQps = (!checkPerUser || userId == null) ? 0 : Math.max(0, properties.getCapacity().getPerUserClaimQps());
        String userKey = "campaign:rate:{" + activityId + "}:user:" + (userId == null ? "anon" : userId);
        Long admitted;
        try {
            admitted = template.execute(script, List.of(prefix + ":total", prefix + ":" + source, userKey),
                    String.valueOf(decision.totalQps()), String.valueOf(sourceQps), String.valueOf(perUserQps));
        } catch (RuntimeException exception) { throw new BizException(ErrorCode.CONFLICT, "限流服务暂不可用，请稍后重试"); }
        if (!Long.valueOf(1).equals(admitted)) throw new BizException(ErrorCode.RATE_LIMITED, "当前入口访问较多，请稍后重试");
    }

    private Decision decision(long activityId) {
        long now = System.currentTimeMillis();
        Decision cached = decisions.get(activityId);
        if (cached != null && cached.loadedAt() + properties.getCapacity().getPolicyCacheTtl().toMillis() > now) {
            return cached;
        }
        Decision loaded = load(activityId, now);
        decisions.put(activityId, loaded);
        return loaded;
    }

    private Decision load(long activityId, long now) {
        Decision fallback = new Decision(now, 0L, properties.getCapacity().getDefaultClaimQps(), Map.of(), true);
        var row = active(activityId);
        if (row == null) return fallback;
        Map<String, Object> policy = store.decode(row);
        if (!row.getConfigurationHash().equals(configuration.fingerprint(activityId))
                || !properties.getCapacity().getDeploymentId().equals(policy.get("deploymentId"))
                || !LocalDateTime.parse(String.valueOf(policy.get("expiresAt"))).isAfter(LocalDateTime.now(clock))) {
            return fallback;
        }
        @SuppressWarnings("unchecked") Map<String, Object> rawSources = (Map<String, Object>) policy.get("sources");
        Map<String, Integer> sources = new HashMap<>();
        rawSources.forEach((key, value) -> sources.put(key, Integer.parseInt(String.valueOf(value))));
        return new Decision(now, row.getId(), Integer.parseInt(String.valueOf(policy.get("totalQps"))), sources, false);
    }

    /** 本机缓存的限流判定；fallback 表示使用默认保护额度。 */
    private record Decision(long loadedAt, long policyId, int totalQps, Map<String, Integer> sources, boolean fallback) {
    }
}
