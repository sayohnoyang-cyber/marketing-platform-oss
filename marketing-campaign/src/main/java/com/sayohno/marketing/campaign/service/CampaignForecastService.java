package com.sayohno.marketing.campaign.service;

import com.sayohno.marketing.campaign.web.dto.ForecastRequest;
import com.sayohno.marketing.common.exception.BizException;
import com.sayohno.marketing.common.result.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.*;

@Service
public class CampaignForecastService {
    private final CampaignService campaigns;
    private final CampaignFlowService flow;
    private final CampaignConfigurationService configuration;
    private final CampaignOperationStore store;
    private final CapacityBaselineService capacity;
    private final Clock clock;
    private final CampaignCalibrationService calibration;

    public CampaignForecastService(CampaignService campaigns, CampaignFlowService flow, CampaignConfigurationService configuration,
            CampaignOperationStore store, CapacityBaselineService capacity, CampaignCalibrationService calibration, Clock clock) {
        this.campaigns = campaigns; this.flow = flow; this.configuration = configuration; this.store = store; this.capacity = capacity; this.calibration = calibration; this.clock = clock;
    }

    @Transactional
    public Map<String, Object> save(long activityId, ForecastRequest request) {
        campaigns.requireOwned(activityId);
        String config = configuration.fingerprint(activityId);
        Map<String, Object> parameters = parameters(request);
        String hash = store.hash(parameters, config);
        var existing = store.existing(activityId, "FORECAST", request.requestKey(), hash);
        if (existing != null) return store.present(existing);
        Map<String, Object> data = calculate(activityId, request);
        return store.present(store.create(activityId, "FORECAST", request.requestKey(), hash, config, "SAVED", data));
    }

    private Map<String, Object> parameters(ForecastRequest request) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("arrivalRates", new TreeMap<>(request.arrivalRates()));
        result.put("growthPercent", request.growthPercent()); result.put("attempts", request.attempts());
        result.put("scenario", request.scenario()); result.put("unreservedQps", request.unreservedQps()); result.put("baselineId", request.baselineId());
        return result;
    }

    private Map<String, Object> calculate(long activityId, ForecastRequest request) {
        var baseline = request.baselineId() == null ? null : capacity.requireUsable(request.baselineId());
        List<Map<String, Object>> reports = flow.report(activityId);
        Set<String> allowed = new HashSet<>();
        reports.forEach(row -> allowed.add("DIRECT".equals(row.get("kind")) ? "direct" : String.valueOf(row.get("channelId"))));
        if (!allowed.equals(request.arrivalRates().keySet())) throw new BizException(ErrorCode.CONFLICT, "渠道已变化，请重新读取预约数据");
        Set<Long> enabled = new HashSet<>();
        flow.channels(activityId).stream().filter(row -> row.getStatus() == 1).forEach(row -> enabled.add(row.getId()));
        boolean mainEnabled = Integer.valueOf(1).equals(campaigns.requireOwned(activityId).getMainEnabled());
        List<Map<String, Object>> rows = new ArrayList<>();
        double arrivals = 0, reservations = 0;
        long currentReservations = 0, eligibleReservations = 0;
        long channelVisitsSum = 0;
        for (var report : reports) {
            String key = "DIRECT".equals(report.get("kind")) ? "direct" : String.valueOf(report.get("channelId"));
            Double rate = request.arrivalRates().get(key);
            if (rate == null || !Double.isFinite(rate) || rate < 0 || rate > 100) throw new BizException(ErrorCode.VALIDATION, "到场率须在0到100之间");
            boolean active = mainEnabled
                    && ("direct".equals(key) || enabled.contains(((Number) report.get("channelId")).longValue()));
            long visits = report.get("visits") instanceof Number n ? n.longValue() : 0L;
            if (active) channelVisitsSum += visits;
            double count = ((Number) report.get("reservations")).longValue() * (1 + request.growthPercent() / 100);
            double expected = active ? count * rate / 100 : 0;
            currentReservations += ((Number) report.get("reservations")).longValue();
            if (active) eligibleReservations += ((Number) report.get("reservations")).longValue();
            reservations += count; arrivals += expected;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("key", key); row.put("name", report.get("name")); row.put("reservations", report.get("reservations"));
            row.put("visits", visits);
            row.put("rate", rate); row.put("enabled", active); row.put("expected", expected); rows.add(row);
        }
        // 全场有效访客按活动维度去重，避免同一访客跨渠道重复累加；用于估算尚未预约的潜在开场流量。
        long totalVisits = Math.max(flow.effectiveVisitors(activityId), channelVisitsSum > 0 ? 1L : 0L);
        long activeVisits = channelVisitsSum;
        long unreservedVisitors = Math.max(0L, activeVisits - eligibleReservations);
        long visitSuggestedUnreservedQps = unreservedVisitors == 0 ? 0L
                : Math.max(1L, Math.round(unreservedVisitors * 0.2 * request.attempts() / 10.0));
        Double visitToReservationRate = totalVisits > 0
                ? Math.round(1000.0 * currentReservations / totalVisits) / 10.0
                : null;
        double average = arrivals * request.attempts() / 10;
        List<Map<String, Object>> scenarios = new ArrayList<>();
        String[] keys = {"low", "normal", "high"}, names = {"保守场景", "常规场景", "集中场景"};
        double[] factors = {1, 1.8, 2.8};
        for (int index = 0; index < keys.length; index++) scenarios.add(Map.of("key", keys[index], "label", names[index],
                "multiplier", factors[index], "qps", Math.round(average * factors[index] + request.unreservedQps())));
        if (!Double.isFinite(average)) throw new BizException(ErrorCode.VALIDATION, "预测参数超出可计算范围");
        Map<String, Object> data = new LinkedHashMap<>(parameters(request));
        data.put("currentReservations", currentReservations); data.put("eligibleReservations", eligibleReservations);
        data.put("totalVisits", totalVisits);
        data.put("activeVisits", activeVisits);
        data.put("visitToReservationRate", visitToReservationRate);
        data.put("visitSuggestedUnreservedQps", visitSuggestedUnreservedQps);
        data.put("rows", rows); data.put("reservations", reservations); data.put("arrivals", arrivals); data.put("average", average);
        data.put("scenarios", scenarios); data.put("windowSeconds", 10); data.put("modelVersion", "assumptions-v1");
        data.put("generatedAt", LocalDateTime.now(clock)); data.put("safeCapacity", baseline == null ? null : capacity.safeQps(baseline));
        data.put("capacityExpiresAt", baseline == null ? null : baseline.getExpiresAt());
        data.put("historyReference", calibration.recommend(activityId));
        data.put("assumptions", "到场率和集中系数为运营假设；未预约流量以额外QPS单独输入；不以券量截断需求。");
        return data;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> plan(long activityId) {
        var activity = campaigns.requireOwned(activityId);
        var history = store.list(activityId, "FORECAST");
        Map<String, Object> snapshot = history.isEmpty() ? null : history.get(0);
        Map<String, Object> saved = snapshot == null ? Map.of() : (Map<String, Object>) snapshot.get("data");
        var reference = calibration.recommend(activityId);
        List<Map<String, Object>> referenceRows = (List<Map<String, Object>>) reference.getOrDefault("rows", List.of());
        Map<String, Double> rates = new LinkedHashMap<>();
        Map<String, Object> savedRates = (Map<String, Object>) saved.getOrDefault("arrivalRates", Map.of());
        for (var row : flow.report(activityId)) {
            String key = "DIRECT".equals(row.get("kind")) ? "direct" : String.valueOf(row.get("channelId"));
            double rate = referenceRows.stream().filter(r -> key.equals(String.valueOf(r.get("key")))).findFirst()
                    .map(r -> ((Number) r.get("rate")).doubleValue()).orElse(40d);
            rates.put(key, savedRates.containsKey(key) ? ((Number) savedRates.get(key)).doubleValue() : rate);
        }
        Long baselineId = saved.get("baselineId") == null ? null : Long.valueOf(saved.get("baselineId").toString());
        boolean baselineExpired = false;
        if (baselineId != null) {
            try { capacity.requireUsable(baselineId); } catch (BizException exception) { baselineId = null; baselineExpired = true; }
        }
        var request = new ForecastRequest("preview", rates, number(saved, "growthPercent", 0), number(saved, "attempts", 2),
                String.valueOf(saved.getOrDefault("scenario", "normal")), number(saved, "unreservedQps", 0), baselineId);
        Map<String, Object> data = calculate(activityId, request);
        List<Map<String, Object>> scenarios = (List<Map<String, Object>>) data.get("scenarios");
        long peak = scenarios.stream().filter(r -> request.scenario().equals(r.get("key"))).mapToLong(r -> ((Number) r.get("qps")).longValue()).findFirst().orElse(0);
        long eligible = ((Number) data.get("eligibleReservations")).longValue();
        long totalVisits = ((Number) data.getOrDefault("totalVisits", 0L)).longValue();
        long activeVisits = ((Number) data.getOrDefault("activeVisits", 0L)).longValue();
        long visitSuggestedUnreservedQps = ((Number) data.getOrDefault("visitSuggestedUnreservedQps", 0L)).longValue();
        // 当尚无预约但有效入口已有短链/活动访问量且未手动输入未预约QPS时，用访问量推算的潜在开场压力参与容量判断。
        if (eligible == 0 && request.unreservedQps() <= 0 && visitSuggestedUnreservedQps > 0) {
            peak = visitSuggestedUnreservedQps;
        }
        boolean sampled = eligible > 0 || request.unreservedQps() > 0 || activeVisits > 0;
        List<Map<String, Object>> oldRows = (List<Map<String, Object>>) saved.getOrDefault("rows", List.of());
        List<Map<String, Object>> currentRows = (List<Map<String, Object>>) data.get("rows");
        boolean reservationsChanged = snapshot != null && (oldRows.size() != currentRows.size() || currentRows.stream().anyMatch(row -> oldRows.stream()
                .noneMatch(old -> row.get("key").equals(old.get("key")) && number(old, "reservations", -1) == number(row, "reservations", -2))));
        boolean refresh = snapshot == null || Boolean.TRUE.equals(snapshot.get("stale")) || reservationsChanged || baselineExpired;
        Double safe = data.get("safeCapacity") == null ? null : ((Number) data.get("safeCapacity")).doubleValue();
        String status = !sampled ? (((Number) data.get("currentReservations")).longValue() > 0 ? "NO_ACTIVE_ENTRY" : "NO_SAMPLE") : safe == null ? "UNVERIFIED" : peak > safe ? "OVER_CAPACITY" : "WITHIN_CAPACITY";
        var preparations = store.list(activityId, "PREPARE");
        // 预热任务中库存检查在开场前为只读观察（SKIPPED）、容量检查为待实测（UNVERIFIED），无失败项且未过期的 PARTIAL/SUCCEEDED 均视为已完成当前配置预热。
        boolean prepared = !preparations.isEmpty() && !Boolean.TRUE.equals(preparations.get(0).get("stale"))
                && Set.of("SUCCEEDED", "PARTIAL").contains(String.valueOf(preparations.get(0).get("status")));
        String action = !sampled ? "COLLECT_RESERVATIONS" : refresh ? "SAVE_FORECAST" : "OVER_CAPACITY".equals(status) ? "REVIEW_CAPACITY" : prepared ? "MONITOR_OPENING" : "RUN_PREPARATION";
        String phase = campaigns.phase(activity).name();
        if (Set.of("ENDED", "CANCELLED", "SOLDOUT").contains(phase)) action = "REVIEW_ACTIVITY";
        else if ("LIVE".equals(phase)) action = "MONITOR_OPENING";
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("phase", phase);
        result.put("activityId", activityId); result.put("activityName", activity.getName());
        result.put("reservationCount", data.get("currentReservations")); result.put("eligibleReservations", eligible);
        result.put("totalVisits", totalVisits);
        result.put("visitToReservationRate", data.get("visitToReservationRate"));
        result.put("visitSuggestedUnreservedQps", visitSuggestedUnreservedQps);
        result.put("expectedArrivals", Math.round(((Number) data.get("arrivals")).doubleValue()));
        result.put("peakQps", sampled ? peak : null); result.put("safeCapacity", safe); result.put("capacityStatus", status);
        result.put("forecastNeedsRefresh", refresh); result.put("reservationsChanged", reservationsChanged);
        result.put("source", snapshot != null ? "SAVED" : referenceRows.stream().anyMatch(r -> Boolean.TRUE.equals(r.get("usable"))) ? "HISTORY" : "DEFAULT");
        result.put("nextAction", action); result.put("prepared", prepared); result.put("windowSeconds", 10);
        result.put("forecastAt", snapshot == null ? null : snapshot.get("createdAt")); result.put("generatedAt", LocalDateTime.now(clock));
        result.put("definition", "基于有效预约预估开场10秒内的领券请求压力，不是实时QPS；预热不等于扩容，未预约流量单独估算。");
        return result;
    }

    private static double number(Map<String, Object> data, String key, double fallback) {
        Object raw = data.get(key);
        if (raw == null) return fallback;
        try { double value = Double.parseDouble(raw.toString()); return Double.isFinite(value) ? value : fallback; }
        catch (NumberFormatException exception) { return fallback; }
    }

}
