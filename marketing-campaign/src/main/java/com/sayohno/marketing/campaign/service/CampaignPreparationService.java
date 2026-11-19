package com.sayohno.marketing.campaign.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sayohno.marketing.campaign.ActivityStatuses;
import com.sayohno.marketing.campaign.dao.entity.CampaignActivity;
import com.sayohno.marketing.campaign.dao.entity.CampaignOffer;
import com.sayohno.marketing.campaign.dao.mapper.CampaignActivityMapper;
import com.sayohno.marketing.campaign.dao.mapper.CampaignOfferMapper;
import com.sayohno.marketing.campaign.dao.mapper.CampaignOperationMapper;
import com.sayohno.marketing.campaign.dao.entity.CampaignOperation;
import com.sayohno.marketing.common.config.MarketingProperties;
import com.sayohno.marketing.common.context.CurrentUser;
import com.sayohno.marketing.common.context.UserContext;
import com.sayohno.marketing.common.context.UserRole;
import com.sayohno.marketing.common.exception.BizException;
import com.sayohno.marketing.common.result.ErrorCode;
import com.sayohno.marketing.coupon.service.OfferInventoryService;
import com.sayohno.marketing.coupon.service.OfferPlanService;
import com.sayohno.marketing.shortlink.service.LinkDirectoryService;
import com.sayohno.marketing.shortlink.service.RedirectService;
import org.springframework.stereotype.Service;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Supplier;

@Service
public class CampaignPreparationService {
    private final CampaignService campaigns;
    private final CampaignActivityMapper activityMapper;
    private final CampaignOperationStore store;
    private final CampaignOperationMapper operations;
    private final CampaignConfigurationService configuration;
    private final CampaignOfferMapper coupons;
    private final OfferPlanService templates;
    private final OfferInventoryService allocations;
    private final LinkDirectoryService links;
    private final RedirectService redirects;
    private final CampaignPageCache pages;
    private final CapacityBaselineService capacity;
    private final MarketingProperties properties;
    private final Clock clock;
    private final CampaignMessageService messages;
    private final CampaignForecastService forecasts;
    private final CampaignRateLimitService rateLimits;
    private final CampaignFlowService flow;

    public CampaignPreparationService(CampaignService campaigns, CampaignActivityMapper activityMapper,
            CampaignOperationStore store, CampaignOperationMapper operations,
            CampaignConfigurationService configuration, CampaignOfferMapper coupons, OfferPlanService templates,
            OfferInventoryService allocations, LinkDirectoryService links, RedirectService redirects, CampaignPageCache pages,
            CapacityBaselineService capacity, MarketingProperties properties, CampaignMessageService messages,
            CampaignForecastService forecasts, CampaignRateLimitService rateLimits, CampaignFlowService flow, Clock clock) {
        this.campaigns = campaigns; this.activityMapper = activityMapper; this.store = store; this.operations = operations;
        this.configuration = configuration; this.coupons = coupons; this.templates = templates; this.allocations = allocations;
        this.links = links; this.redirects = redirects; this.pages = pages; this.capacity = capacity; this.properties = properties;
        this.messages = messages; this.forecasts = forecasts; this.rateLimits = rateLimits; this.flow = flow; this.clock = clock;
    }

    /**
     * 扫描即将开场（5 分钟内）且尚未完成当前配置预热的已发布活动，自动执行一次预热兜底（P2-7）。
     */
    public int autoPrepareUpcoming() {
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime windowEnd = now.plusMinutes(5);
        List<CampaignActivity> upcoming = activityMapper.selectList(Wrappers.<CampaignActivity>lambdaQuery()
                .eq(CampaignActivity::getStatus, ActivityStatuses.PUBLISHED)
                .gt(CampaignActivity::getStartTime, now)
                .le(CampaignActivity::getStartTime, windowEnd));
        int prepared = 0;
        for (CampaignActivity activity : upcoming) {
            try {
                Map<String, Object> outcome = runInternal(activity, "auto:" + activity.getId() + ":" + activity.getVersion());
                if (outcome != null) {
                    prepared++;
                }
            } catch (RuntimeException ignored) {
            }
        }
        return prepared;
    }

    /**
     * 供后台调度器在无 HTTP 会话时按活动所属工作空间执行预热，执行完毕后严格恢复原上下文。
     */
    public Map<String, Object> runInternal(CampaignActivity activity, String requestKey) {
        CurrentUser previous = UserContext.get();
        try {
            UserContext.set(CurrentUser.builder()
                    .userId(activity.getMerchantId())
                    .username("system-auto-prepare")
                    .merchantId(activity.getMerchantId())
                    .roles(Set.of(UserRole.OWNER, UserRole.USER))
                    .build());
            var existingRuns = store.list(activity.getId(), "PREPARE");
            if (!existingRuns.isEmpty() && !Boolean.TRUE.equals(existingRuns.get(0).get("stale"))
                    && Set.of("SUCCEEDED", "PARTIAL").contains(String.valueOf(existingRuns.get(0).get("status")))) {
                return existingRuns.get(0);
            }
            return run(activity.getId(), requestKey);
        } finally {
            if (previous == null) {
                UserContext.clear();
            } else {
                UserContext.set(previous);
            }
        }
    }

    /** Synchronous bounded application checks. Each task result is persisted independently. */
    public Map<String, Object> run(long activityId, String requestKey) {
        var activity = campaigns.requireOwned(activityId);
        String config = configuration.fingerprint(activityId);
        String hash = store.hash("prepare-v1", config);
        String executionToken = UUID.randomUUID().toString();
        var run = store.existing(activityId, "PREPARE", requestKey, hash);
        if (run != null) {
            if (!"RUNNING".equals(run.getStatus())) return store.present(run);
            LocalDateTime cutoff = LocalDateTime.now(clock).minusSeconds(30);
            if (run.getUpdateTime().isAfter(cutoff)) throw new BizException(ErrorCode.CONFLICT, "检查正在执行，请稍后读取结果");
            int claimed = operations.update(null, Wrappers.<CampaignOperation>lambdaUpdate().eq(CampaignOperation::getId, run.getId())
                    .eq(CampaignOperation::getStatus, "RUNNING").le(CampaignOperation::getUpdateTime, cutoff)
                    .set(CampaignOperation::getUpdateTime, LocalDateTime.now(clock)).set(CampaignOperation::getExecutionToken, executionToken));
            if (claimed != 1) throw new BizException(ErrorCode.CONFLICT, "检查已由另一请求接管");
        }
        List<Map<String, Object>> tasks = new ArrayList<>();
        Map<String, Object> plan = forecasts.plan(activityId);
        WarmDecision decision = evaluateWarmDecision(activityId, plan);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("tasks", tasks);
        data.put("startedAt", LocalDateTime.now(clock));
        data.put("warmLevel", decision.level());
        data.put("totalVisits", decision.totalVisits());
        data.put("predictedPeakQps", decision.peakQps());
        data.put("safeCapacity", decision.safeCapacity());
        data.put("rateLimitActive", decision.rateLimitActive());
        data.put("recommendations", decision.recommendations());
        if (run == null) {
            run = store.create(activityId, "PREPARE", requestKey, hash, config, "RUNNING", data);
            run.setExecutionToken(executionToken);
            operations.updateById(run);
        } else run.setExecutionToken(executionToken);
        var coupon = coupons.selectOne(Wrappers.<CampaignOffer>lambdaQuery().eq(CampaignOffer::getActivityId, activityId));
        task(run, data, tasks, "relations", "活动与券模板关系校验", () -> {
            var checks = campaigns.checks(activityId);
            var blocked = checks.stream().filter(row -> Boolean.TRUE.equals(row.get("blocking")) && !Boolean.TRUE.equals(row.get("passed"))).toList();
            return result(blocked.isEmpty() ? "SUCCEEDED" : "FAILED", blocked.isEmpty() ? "时间、券、投放、入口与预算符合发布条件" : "发布条件未通过，请先处理工作区中的阻断项");
        });
        task(run, data, tasks, "link", "短链映射与布隆检查", () -> {
            if (activity.getMainLinkId() == null) return result("FAILED", "还没有活动主链");
            return redirects.warmMapping(links.findOwned(activity.getMainLinkId()));
        });
        task(run, data, tasks, "offer", "优惠券展示规则缓存", () -> coupon == null ? result("FAILED", "尚未选券") : templates.warmOffer(coupon.getPlanId()));
        task(run, data, tasks, "stock", "库存缓存预热与一致性检查", () -> coupon == null || coupon.getInventoryId() == null ? result("FAILED", "尚未投放") : allocations.warmStock(coupon.getInventoryId()));
        task(run, data, tasks, "page", "活动热点读缓存准备", () -> flow.warmPageCache(activityId));
        task(run, data, tasks, "capacity", "开场与消息消费容量校验", () -> capacityCheck(activityId, decision));
        data.put("finishedAt", LocalDateTime.now(clock));
        long passed = tasks.stream().filter(row -> "SUCCEEDED".equals(row.get("status"))).count();
        data.put("passed", passed); data.put("total", tasks.size());
        boolean stale = !config.equals(configuration.fingerprint(activityId));
        String status = stale ? "STALE" : tasks.stream().anyMatch(row -> "FAILED".equals(row.get("status"))) ? "FAILED" : passed == tasks.size() ? "SUCCEEDED" : "PARTIAL";
        store.update(run, status, data);
        return store.present(run);
    }

    private WarmDecision evaluateWarmDecision(long activityId, Map<String, Object> plan) {
        long totalVisits = plan.get("totalVisits") instanceof Number n ? n.longValue() : 0L;
        Long peakQps = plan.get("peakQps") instanceof Number n ? n.longValue() : null;
        Double safeCapacity = plan.get("safeCapacity") instanceof Number n ? n.doubleValue() : null;
        boolean rateLimitActive = "ACTIVE".equals(rateLimits.current(activityId).get("status"));
        String level = "STANDARD";
        List<String> recommendations = new ArrayList<>();
        boolean overCapacity = peakQps != null && safeCapacity != null && peakQps > safeCapacity;
        boolean highRatio = peakQps != null && safeCapacity != null && safeCapacity > 0 && peakQps >= safeCapacity * 0.6;
        if (overCapacity || totalVisits >= 1000) {
            level = "PEAK_PROTECT";
            if (!rateLimitActive) {
                recommendations.add("预测压力接近或超出安全容量，建议开场前应用活动/渠道限流策略，并优先使用异步领券入口");
            } else {
                recommendations.add("已开启限流保护，建议核对异步领券与 Outbox 消费积压");
            }
        } else if (highRatio || totalVisits >= 100) {
            level = "ELEVATED";
            recommendations.add("访问量或预约压力已起量，建议提前完成热点缓存预热并保持默认限流保护");
        } else {
            recommendations.add("当前流量处于常规水平，完成基础缓存与短链映射预热即可");
        }
        return new WarmDecision(level, totalVisits, peakQps, safeCapacity, rateLimitActive, recommendations);
    }

    private Map<String, Object> capacityCheck(long activityId, WarmDecision decision) {
        var observed = messages.status(activityId);
        if ("FAILED".equals(observed.get("status"))) return result("FAILED", "存在失败或超时消息，请检查消息处理");
        var forecastsList = store.list(activityId, "FORECAST");
        if (forecastsList.isEmpty() || Boolean.TRUE.equals(forecastsList.get(0).get("stale"))) {
            return result("UNVERIFIED", "预热级别 " + decision.level() + "（有效访问 " + decision.totalVisits() + "）；没有当前活动配置下的预测快照");
        }
        @SuppressWarnings("unchecked") Map<String, Object> data = (Map<String, Object>) forecastsList.get(0).get("data");
        Object id = data.get("baselineId");
        if (id == null) {
            return result("UNVERIFIED", "预热级别 " + decision.level() + "（有效访问 " + decision.totalVisits() + "）；预测没有关联真实压测报告");
        }
        var baseline = capacity.requireUsable(Long.parseLong(id.toString()));
        return result("UNVERIFIED", "预热级别 " + decision.level() + "（有效访问 " + decision.totalVisits()
                + "，限流" + (decision.rateLimitActive() ? "已启用" : "使用默认保护") + "）；已关联压测报告“"
                + baseline.getReportName() + "”，安全容量 " + capacity.safeQps(baseline)
                + " QPS；近5分钟处理 " + observed.get("completedInWindow") + " 条，待处理 " + observed.get("backlog") + " 条；最大消费容量待实测");
    }

    private record WarmDecision(String level, long totalVisits, Long peakQps, Double safeCapacity,
                                boolean rateLimitActive, List<String> recommendations) {
    }

    private void task(CampaignOperation run, Map<String, Object> data, List<Map<String, Object>> tasks, String code, String title, Supplier<Map<String, Object>> action) {
        Map<String, Object> task = new LinkedHashMap<>(); task.put("code", code); task.put("title", title); task.put("status", "RUNNING");
        task.put("startedAt", LocalDateTime.now(clock)); tasks.add(task); store.update(run, "RUNNING", data);
        try { task.putAll(action.get()); }
        catch (BizException exception) { task.putAll(result("FAILED", exception.getMessage())); }
        catch (RuntimeException exception) { task.putAll(result("FAILED", "执行失败，请检查基础服务后重试")); }
        task.put("finishedAt", LocalDateTime.now(clock)); store.update(run, "RUNNING", data);
    }

    private static Map<String, Object> result(String status, String detail) { return Map.of("status", status, "detail", detail); }
}
