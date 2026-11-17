package com.sayohno.marketing.campaign.service;

import com.sayohno.marketing.campaign.ActivityPhase;
import com.sayohno.marketing.campaign.dao.mapper.CampaignOverviewMapper;
import com.sayohno.marketing.campaign.web.dto.TrafficBucket;
import com.sayohno.marketing.common.exception.BizException;
import com.sayohno.marketing.common.result.ErrorCode;
import com.sayohno.marketing.identity.service.MerchantAccessService;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;

@Service
public class CampaignOverviewService {
    private final CampaignOverviewMapper mapper;
    private final MerchantAccessService access;
    private final CampaignService campaigns;
    private final CampaignOperationStore operations;
    private final CapacityBaselineService capacity;
    private final Clock clock;
    private final CampaignForecastService forecasts;

    public CampaignOverviewService(CampaignOverviewMapper mapper, MerchantAccessService access, CampaignService campaigns,
            CampaignOperationStore operations, CapacityBaselineService capacity, CampaignForecastService forecasts, Clock clock) {
        this.mapper = mapper; this.access = access; this.campaigns = campaigns;
        this.forecasts = forecasts; this.operations = operations; this.capacity = capacity; this.clock = clock;
    }

    public Map<String, Object> overview(String period, Long activityId) {
        if (!Set.of("today", "week").contains(period)) throw new BizException(ErrorCode.VALIDATION, "统计范围不合法");
        long merchantId = access.requireMerchant().getMerchantId();
        if (activityId != null) campaigns.requireOwned(activityId);
        LocalDateTime end = LocalDateTime.now(clock), today = end.toLocalDate().atStartOfDay();
        boolean hourly = "today".equals(period);
        int shift = hourly ? 1 : 7;
        LocalDateTime start = hourly ? today : today.minusDays(6);
        LocalDateTime previousStart = start.minusDays(shift), previousEnd = end.minusDays(shift);
        long visits = mapper.visitors(merchantId, start, end), previousVisits = mapper.visitors(merchantId, previousStart, previousEnd);
        long reserved = mapper.reservations(merchantId, start, end), previousReserved = mapper.reservations(merchantId, previousStart, previousEnd);
        long converted = mapper.convertedVisitors(merchantId, start, end), previousConverted = mapper.convertedVisitors(merchantId, previousStart, previousEnd);
        Double rate = percent(converted, visits), previousRate = percent(previousConverted, previousVisits);
        var activities = campaigns.list();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("period", period); result.put("generatedAt", end); result.put("from", start); result.put("to", end);
        result.put("reservations", reserved); result.put("reservationChange", change(reserved, previousReserved));
        result.put("visitors", visits); result.put("visitorChange", change(visits, previousVisits));
        result.put("conversionRate", rate); result.put("conversionChange", rate == null || previousRate == null ? null : round(rate - previousRate));
        result.put("activityCount", activities.size()); result.put("upcomingCount", activities.stream().filter(row -> campaigns.phase(row) == ActivityPhase.WARMUP).count());
        result.put("hasPreviousTraffic", previousVisits > 0);
        result.put("points", points(mapper.buckets(merchantId, start, end, hourly), mapper.buckets(merchantId, previousStart, previousEnd, hourly), start, end, shift, hourly));
        var sources = mapper.sources(merchantId, start, end);
        long sourceTotal = sources.stream().mapToLong(row -> ((Number) row.get("visits")).longValue()).sum();
        result.put("insight", sourceTotal == 0 ? "当前时间范围暂无访问。分享活动短链后，这里会显示真实的访问趋势与来源表现。"
                : sources.get(0).get("name") + "贡献了 " + Math.round(((Number) sources.get(0).get("visits")).doubleValue() / sourceTotal * 100)
                + "% 的来源去重访问；当前范围有 " + reserved + " 个有效预约。来源独立去重，跨来源访客可能重复。");
        result.put("opening", opening(activityId));
        result.put("definitions", Map.of("visitors", "统计范围内所有活动的浏览器访客去重数，表示平台可观测触达；不包含短信或广告送达量。",
                "reservations", "统计范围内提交且当前仍有效的预约；同一用户在不同活动分别计数。",
                "conversion", "同一统计范围内，到达活动并成功领取的去重访客数 ÷ 到达活动的去重访客数。",
                "trend", hourly ? "按小时分别去重；灰线为昨天相同已过去时段。" : "按天分别去重；灰线为前7天的相同已过去时段。"));
        return result;
    }

    private List<Map<String, Object>> points(List<TrafficBucket> current, List<TrafficBucket> previous,
            LocalDateTime start, LocalDateTime end, int shift, boolean hourly) {
        Map<String, Long> currentMap = new HashMap<>(), previousMap = new HashMap<>();
        for (var row : current) currentMap.put(row.getStatDate() + ":" + row.getBucketHour(), row.getVisitors());
        for (var row : previous) previousMap.put(row.getStatDate() + ":" + row.getBucketHour(), row.getVisitors());
        List<Map<String, Object>> points = new ArrayList<>();
        for (int index = 0; index < (hourly ? 24 : 7); index++) {
            LocalDateTime bucket = hourly ? start.plusHours(index) : start.plusDays(index);
            String key = bucket.toLocalDate() + ":" + (hourly ? bucket.getHour() : 0);
            String previousKey = bucket.minusDays(shift).toLocalDate() + ":" + (hourly ? bucket.getHour() : 0);
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("label", hourly ? String.format("%02d:00", index) : (bucket.getMonthValue() + "." + String.format("%02d", bucket.getDayOfMonth())));
            boolean future = bucket.isAfter(end);
            point.put("current", future ? null : currentMap.getOrDefault(key, 0L));
            point.put("previous", future ? null : previousMap.getOrDefault(previousKey, 0L));
            points.add(point);
        }
        return points;
    }

    private Map<String, Object> opening(Long id) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("activityId", id); result.put("forecastStatus", "MISSING"); result.put("qps", null); result.put("safeCapacity", null);
        result.put("tasks", List.of()); result.put("ready", false); result.put("stale", false);
        if (id == null) return result;
        var activity = campaigns.requireOwned(id);
        result.put("name", activity.getName());
        var forecasts = operations.list(id, "FORECAST");
        if (!forecasts.isEmpty()) {
            var snapshot = forecasts.get(0);
            @SuppressWarnings("unchecked") var data = (Map<String, Object>) snapshot.get("data");
            boolean stale = Boolean.TRUE.equals(snapshot.get("stale"));
            result.put("forecastStatus", stale ? "STALE" : "AVAILABLE"); result.put("forecastAt", snapshot.get("createdAt"));
            result.put("scenario", data.get("scenario"));
            if (!stale) {
                @SuppressWarnings("unchecked") var scenarios = (List<Map<String, Object>>) data.get("scenarios");
                scenarios.stream().filter(row -> row.get("key").equals(data.get("scenario"))).findFirst()
                        .ifPresent(row -> result.put("qps", Double.parseDouble(row.get("qps").toString())));
                if (data.get("baselineId") != null) {
                    try { result.put("safeCapacity", capacity.safeQps(capacity.requireUsable(Long.valueOf(data.get("baselineId").toString())))); }
                    catch (BizException exception) { result.put("capacityNote", exception.getMessage()); }
                }
            }
        }
        var preparations = operations.list(id, "PREPARE");
        if (!preparations.isEmpty()) {
            var run = preparations.get(0);
            @SuppressWarnings("unchecked") var data = (Map<String, Object>) run.get("data");
            boolean stale = Boolean.TRUE.equals(run.get("stale"));
            result.put("tasks", data.getOrDefault("tasks", List.of())); result.put("stale", stale);
            result.put("ready", !stale && "SUCCEEDED".equals(run.get("status")));
        }
        var plan = this.forecasts.plan(id);
        result.put("plan", plan); result.put("qps", plan.get("peakQps")); result.put("safeCapacity", plan.get("safeCapacity"));
        return result;
    }

    private static Double percent(long n, long d) { return d == 0 ? null : round(100.0 * n / d); }
    private static Double change(long current, long previous) { return previous == 0 ? null : round((current - previous) * 100.0 / previous); }
    private static double round(double value) { return Math.round(value * 100) / 100.0; }
}
