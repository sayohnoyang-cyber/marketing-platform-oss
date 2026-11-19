package com.sayohno.marketing.campaign.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sayohno.marketing.campaign.ActivityPhase;
import com.sayohno.marketing.campaign.ActivityStatuses;
import com.sayohno.marketing.campaign.dao.entity.CampaignActivity;
import com.sayohno.marketing.campaign.dao.entity.CampaignOffer;
import com.sayohno.marketing.campaign.dao.entity.CampaignReservation;
import com.sayohno.marketing.campaign.dao.mapper.CampaignActivityMapper;
import com.sayohno.marketing.campaign.dao.mapper.CampaignOfferMapper;
import com.sayohno.marketing.campaign.dao.mapper.CampaignReservationMapper;
import com.sayohno.marketing.common.campaign.DirectClaimGuard;
import com.sayohno.marketing.common.exception.BizException;
import com.sayohno.marketing.common.result.ErrorCode;
import com.sayohno.marketing.coupon.dao.entity.OfferInventory;
import com.sayohno.marketing.coupon.dao.entity.OfferPlan;
import com.sayohno.marketing.coupon.enums.InventoryStatus;
import com.sayohno.marketing.coupon.enums.OfferPlanStatus;
import com.sayohno.marketing.coupon.service.OfferInventoryService;
import com.sayohno.marketing.coupon.service.OfferPolicyRules;
import com.sayohno.marketing.coupon.service.OfferPlanService;
import com.sayohno.marketing.identity.service.MerchantAccessService;
import com.sayohno.marketing.shortlink.dao.entity.Link;
import com.sayohno.marketing.shortlink.service.LinkDirectoryService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class CampaignService implements DirectClaimGuard, com.sayohno.marketing.common.campaign.ActivityLinkGuard {
    @Override public void assertLinkable(long activityId) { requireOwned(activityId); }
    private final CampaignActivityMapper activityMapper;
    private final CampaignOfferMapper couponMapper;
    private final OfferPlanService templateService;
    private final OfferInventoryService allocationService;
    private final CampaignPublishService publishService;
    private final LinkDirectoryService linkDirectoryService;
    private final MerchantAccessService merchantAccessService;
    private final CampaignReservationMapper reservationMapper;
    private final CampaignPageCache pageCache;
    private final Clock clock;

    public CampaignService(CampaignActivityMapper activityMapper,
                           CampaignOfferMapper couponMapper,
                           OfferPlanService templateService,
                           OfferInventoryService allocationService,
                           CampaignPublishService publishService,
                           LinkDirectoryService linkDirectoryService,
                           MerchantAccessService merchantAccessService,
                           CampaignReservationMapper reservationMapper,
                           CampaignPageCache pageCache,
                           Clock clock) {
        this.activityMapper = activityMapper;
        this.couponMapper = couponMapper;
        this.templateService = templateService;
        this.allocationService = allocationService;
        this.publishService = publishService;
        this.linkDirectoryService = linkDirectoryService;
        this.merchantAccessService = merchantAccessService;
        this.reservationMapper = reservationMapper;
        this.pageCache = pageCache;
        this.clock = clock;
    }

    @Transactional
    public CampaignActivity create(String name, LocalDateTime warmupStart, LocalDateTime start, LocalDateTime end) {
        return create(name, warmupStart, start, end, null, null);
    }

    @Transactional
    public CampaignActivity create(String name, LocalDateTime warmupStart, LocalDateTime start, LocalDateTime end,
                                   String activityType, BigDecimal budgetAmount) {
        var merchant = merchantAccessService.requireMerchant();
        validateDetails(name, activityType, budgetAmount);
        if (warmupStart == null && start != null) {
            warmupStart = start.minusSeconds(1);
        }
        validateTimes(warmupStart, start, end);
        CampaignActivity activity = new CampaignActivity();
        activity.setMerchantId(merchant.getMerchantId());
        activity.setName(name.trim());
        activity.setActivityType(activityType == null ? "优惠券推广活动" : activityType);
        activity.setBudgetAmount(budgetAmount);
        activity.setWarmupStartTime(warmupStart);
        activity.setStartTime(start);
        activity.setEndTime(end);
        activity.setStatus(ActivityStatuses.DRAFT);
        activity.setVersion(1);
        activity.setMainEnabled(0);
        activityMapper.insert(activity);
        return activity;
    }

    public List<CampaignActivity> list() {
        var merchant = merchantAccessService.requireMerchant();
        return activityMapper.selectList(Wrappers.<CampaignActivity>lambdaQuery()
                .eq(CampaignActivity::getMerchantId, merchant.getMerchantId())
                .orderByDesc(CampaignActivity::getId));
    }

    public CampaignActivity requireOwned(long activityId) {
        var merchant = merchantAccessService.requireMerchant();
        CampaignActivity activity = activityMapper.selectById(activityId);
        if (activity == null || !merchant.getMerchantId().equals(activity.getMerchantId())) {
            throw new BizException(ErrorCode.NOT_FOUND, "活动不存在");
        }
        return activity;
    }

    public ActivityPhase phase(CampaignActivity activity) {
        return ActivityPhase.of(activity.getStatus(), activity.getWarmupStartTime(),
                activity.getStartTime(), activity.getEndTime(), LocalDateTime.now(clock));
    }

    @Transactional
    public CampaignActivity publish(long activityId) {
        CampaignActivity activity = requireOwned(activityId);
        if (activity.getStatus() != ActivityStatuses.DRAFT
                && activity.getStatus() != ActivityStatuses.PAUSED
                && activity.getStatus() != ActivityStatuses.OFFLINE) {
            throw new BizException(ErrorCode.CONFLICT, "当前状态不能发布");
        }
        if (!LocalDateTime.now(clock).isBefore(activity.getEndTime())) {
            throw new BizException(ErrorCode.CONFLICT, "活动已结束，不能发布");
        }
        List<Map<String, Object>> failed = checks(activityId).stream()
                .filter(item -> Boolean.TRUE.equals(item.get("blocking")) && !Boolean.TRUE.equals(item.get("passed")))
                .toList();
        if (!failed.isEmpty()) {
            throw new BizException(ErrorCode.VALIDATION, "发布检查未通过：" + failed.get(0).get("reason"));
        }
        CampaignActivity published = changeStatus(activity, ActivityStatuses.PUBLISHED);
        for (CampaignOffer row : couponMapper.selectList(Wrappers.<CampaignOffer>lambdaQuery()
                .eq(CampaignOffer::getActivityId, activityId))) {
            if (row.getInventoryId() != null) {
                allocationService.warmStockQuietly(row.getInventoryId());
            }
        }
        return published;
    }

    @Transactional
    public CampaignActivity pause(long activityId) {
        CampaignActivity activity = requireOwned(activityId);
        if (activity.getStatus() != ActivityStatuses.PUBLISHED) {
            throw new BizException(ErrorCode.CONFLICT, "只有已发布的活动可以暂停");
        }
        return changeStatus(activity, ActivityStatuses.PAUSED);
    }

    @Transactional
    public CampaignActivity offline(long activityId) {
        CampaignActivity activity = requireOwned(activityId);
        if (activity.getStatus() == ActivityStatuses.OFFLINE) {
            return activity;
        }
        if (activity.getStatus() != ActivityStatuses.PUBLISHED && activity.getStatus() != ActivityStatuses.PAUSED) {
            throw new BizException(ErrorCode.CONFLICT, "只有已发布的活动可以下线");
        }
        if (!LocalDateTime.now(clock).isBefore(activity.getEndTime())) {
            throw new BizException(ErrorCode.CONFLICT, "活动已结束");
        }
        return changeStatus(activity, ActivityStatuses.OFFLINE);
    }

    @Transactional
    public CampaignActivity cancel(long activityId) {
        CampaignActivity activity = requireOwned(activityId);
        if (activity.getStatus() == ActivityStatuses.CANCELLED) {
            return activity;
        }
        CampaignActivity updated = changeStatus(activity, ActivityStatuses.CANCELLED);
        freezeCoupons(activityId);
        return updated;
    }

    @Transactional
    public CampaignActivity postpone(long activityId, LocalDateTime warmupStart, LocalDateTime start, LocalDateTime end) {
        CampaignActivity activity = requireOwned(activityId);
        return update(activityId, activity.getName(), warmupStart, start, end, null);
    }

    @Transactional
    public CampaignActivity update(long activityId, String name, LocalDateTime warmupStart, LocalDateTime start,
                                   LocalDateTime end, Integer version) {
        return update(activityId, name, warmupStart, start, end, version, null, null);
    }

    @Transactional
    public CampaignActivity update(long activityId, String name, LocalDateTime warmupStart, LocalDateTime start,
                                   LocalDateTime end, Integer version, String activityType, BigDecimal budgetAmount) {
        CampaignActivity activity = requireOwned(activityId);
        validateDetails(name, activityType, budgetAmount);
        if (budgetAmount != null) {
            for (CampaignOffer linked : couponMapper.selectList(Wrappers.<CampaignOffer>lambdaQuery()
                    .eq(CampaignOffer::getActivityId, activityId))) {
                if (linked.getInventoryId() != null) {
                    assertBudget(linked.getPlanId(), allocationService.find(linked.getInventoryId()).getQuantity(), budgetAmount);
                }
            }
        }
        if (version != null && !version.equals(activity.getVersion())) {
            throw new BizException(ErrorCode.CONFLICT, "活动已更新，请刷新后再保存");
        }
        if (activity.getStatus() == ActivityStatuses.CANCELLED) {
            throw new BizException(ErrorCode.CONFLICT, "已终止的活动不能修改");
        }
        if (name == null || name.isBlank()) {
            throw new BizException(ErrorCode.VALIDATION, "请填写活动名称");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        if (activity.getStatus() != ActivityStatuses.DRAFT && !now.isBefore(activity.getEndTime())) {
            throw new BizException(ErrorCode.CONFLICT, "活动已结束，不能通过改时间重新开启");
        }
        boolean started = activity.getStatus() != ActivityStatuses.DRAFT && !now.isBefore(activity.getStartTime());
        LocalDateTime nextStart = start;
        LocalDateTime nextWarmup = warmupStart != null ? warmupStart : activity.getWarmupStartTime();
        if (started) {
            if (start != null && !start.equals(activity.getStartTime())) {
                throw new BizException(ErrorCode.CONFLICT, "开场后不能修改开始时间");
            }
            nextStart = activity.getStartTime();
            nextWarmup = activity.getWarmupStartTime();
            if (end == null || end.isBefore(activity.getEndTime())) {
                throw new BizException(ErrorCode.CONFLICT, "开场后只能延长结束时间");
            }
        }
        if (nextStart != null && (nextWarmup == null || !nextStart.isAfter(nextWarmup))) {
            nextWarmup = nextStart.minusSeconds(1);
        }
        validateTimes(nextWarmup, nextStart, end);
        assertCouponPeriodCovered(activityId, nextStart, end);
        activity.setName(name.trim());
        if (activityType != null) activity.setActivityType(activityType);
        if (budgetAmount != null) activity.setBudgetAmount(budgetAmount);
        activity.setWarmupStartTime(nextWarmup);
        activity.setStartTime(nextStart);
        activity.setEndTime(end);
        activity.setVersion(activity.getVersion() + 1);
        if (activityMapper.update(null, Wrappers.<CampaignActivity>lambdaUpdate()
                .set(CampaignActivity::getName, activity.getName())
                .set(CampaignActivity::getActivityType, activity.getActivityType())
                .set(CampaignActivity::getBudgetAmount, activity.getBudgetAmount())
                .set(CampaignActivity::getWarmupStartTime, nextWarmup)
                .set(CampaignActivity::getStartTime, nextStart)
                .set(CampaignActivity::getEndTime, end)
                .set(CampaignActivity::getVersion, activity.getVersion())
                .eq(CampaignActivity::getId, activityId)
                .eq(CampaignActivity::getVersion, version == null ? activity.getVersion() - 1 : version)) != 1) {
            throw new BizException(ErrorCode.CONFLICT, "活动已更新，请刷新后再保存");
        }
        // 活动改期或延期时同步更新主短链有效期并清理跳转缓存，避免活动未结束而短链提前过期。
        if (activity.getMainLinkId() != null) {
            linkDirectoryService.updateLink(activity.getMainLinkId(), null, end, null);
        }
        pageCache.evict(activityId);
        return activity;
    }

    @Transactional
    public CampaignOffer attachCoupon(long activityId, long templateId) {
        CampaignActivity activity = requireOwned(activityId);
        if (phase(activity) == ActivityPhase.LIVE) {
            throw new BizException(ErrorCode.CONFLICT, "活动进行中不能更换券");
        }
        OfferPlan template = templateService.requireOwned(templateId);
        if (template.getStatus() != OfferPlanStatus.OPEN) {
            throw new BizException(ErrorCode.VALIDATION, "只能关联生效中的优惠券");
        }
        // 优惠券可领开始时间须不晚于活动开场时间，防止活动开场后用户领券被模板生效时间拦截。
        if (template.getUsableFrom().isAfter(activity.getStartTime())) {
            throw new BizException(ErrorCode.VALIDATION, "优惠券生效时间不能晚于活动开场时间");
        }
        if (template.getUsableUntil().isBefore(activity.getEndTime())) {
            throw new BizException(ErrorCode.VALIDATION, "优惠券有效期必须覆盖活动结束时间");
        }
        List<CampaignOffer> linked = coupons(activityId);
        CampaignOffer existing = linked.stream().filter(row -> row.getPlanId().equals(templateId)).findFirst().orElse(null);
        if (existing != null) {
            return existing;
        }
        if (!linked.isEmpty()) {
            throw new BizException(ErrorCode.CONFLICT, "第一版每场活动只选一张券");
        }
        CampaignOffer row = new CampaignOffer();
        row.setActivityId(activityId);
        row.setPlanId(templateId);
        row.setStoreCode(template.getStoreCode());
        row.setSortOrder(0);
        couponMapper.insert(row);
        pageCache.evict(activityId);
        return row;
    }

    public CampaignActivity bindMainLink(long activityId, long linkId) {
        CampaignActivity activity = requireOwned(activityId);
        if (activity.getMainLinkId() != null) {
            return activity;
        }
        activity.setMainLinkId(linkId);
        activity.setMainEnabled(1);
        activityMapper.updateById(activity);
        pageCache.evict(activityId);
        return activity;
    }

    public CampaignActivity setMainEnabled(long activityId, boolean enabled) {
        CampaignActivity activity = requireOwned(activityId);
        if (activity.getMainLinkId() == null) {
            throw new BizException(ErrorCode.VALIDATION, "活动还没有主链");
        }
        activity.setMainEnabled(enabled ? 1 : 0);
        activityMapper.updateById(activity);
        linkDirectoryService.updateLink(activity.getMainLinkId(), null, activity.getEndTime(), enabled ? 0 : 1);
        pageCache.evict(activityId);
        return activity;
    }

    public CampaignActivity findByMainLink(long linkId) {
        return activityMapper.selectOne(Wrappers.<CampaignActivity>lambdaQuery()
                .eq(CampaignActivity::getMainLinkId, linkId));
    }

    public void bumpVersion(long activityId, Integer expected) {
        CampaignActivity activity = requireOwned(activityId);
        int current = activity.getVersion();
        if (expected != null && expected != current) {
            throw new BizException(ErrorCode.CONFLICT, "活动已更新，请刷新后再保存");
        }
        int updated = activityMapper.update(null, Wrappers.<CampaignActivity>lambdaUpdate()
                .set(CampaignActivity::getVersion, current + 1)
                .eq(CampaignActivity::getId, activityId)
                .eq(CampaignActivity::getVersion, current));
        if (updated != 1) {
            throw new BizException(ErrorCode.CONFLICT, "活动已更新，请刷新后再保存");
        }
        pageCache.evict(activityId);
    }

    public void saveBudget(long activityId, java.math.BigDecimal budget) {
        if (budget == null || budget.signum() < 0) {
            throw new BizException(ErrorCode.VALIDATION, "预算不能为负");
        }
        CampaignActivity activity = requireOwned(activityId);
        activity.setBudgetAmount(budget);
        activityMapper.updateById(activity);
    }

    public Map<String, Object> present(CampaignActivity activity) {
        Link link = null;
        if (activity.getMainLinkId() != null) {
            try {
                link = linkDirectoryService.findOwned(activity.getMainLinkId());
            } catch (BizException ignored) {
                link = null;
            }
        }
        List<CampaignOffer> linked = couponMapper.selectList(Wrappers.<CampaignOffer>lambdaQuery()
                .eq(CampaignOffer::getActivityId, activity.getId()));
        OfferPlan template = linked.isEmpty() ? null : templateService.requireOwned(linked.get(0).getPlanId());
        OfferInventory allocation = !linked.isEmpty() && linked.get(0).getInventoryId() != null
                ? allocationService.find(linked.get(0).getInventoryId())
                : null;
        List<Map<String, Object>> checks = checks(activity.getId());
        return buildPresentation(activity, link, linked, template, allocation, checks);
    }

    /**
     * 批量装配活动列表视图，避免按活动逐条触发短链、券模板、投放及预约统计查询。
     */
    public List<Map<String, Object>> presentBatch(List<CampaignActivity> activities, Map<Long, Long> reservationCounts) {
        if (activities.isEmpty()) {
            return List.of();
        }
        List<Long> activityIds = activities.stream().map(CampaignActivity::getId).toList();
        List<Long> linkIds = activities.stream().map(CampaignActivity::getMainLinkId).filter(java.util.Objects::nonNull).distinct().toList();
        Map<Long, Link> links = linkDirectoryService.findOwnedBatch(linkIds);
        Map<Long, List<CampaignOffer>> couponsByActivity = couponMapper.selectList(Wrappers.<CampaignOffer>lambdaQuery()
                        .in(CampaignOffer::getActivityId, activityIds))
                .stream().collect(java.util.stream.Collectors.groupingBy(CampaignOffer::getActivityId));
        List<Long> templateIds = couponsByActivity.values().stream()
                .flatMap(List::stream).map(CampaignOffer::getPlanId).filter(java.util.Objects::nonNull).distinct().toList();
        List<Long> allocationIds = couponsByActivity.values().stream()
                .flatMap(List::stream).map(CampaignOffer::getInventoryId).filter(java.util.Objects::nonNull).distinct().toList();
        Map<Long, OfferPlan> templates = templateService.findOwnedBatch(templateIds);
        Map<Long, OfferInventory> allocations = allocationService.findBatch(allocationIds);

        List<Map<String, Object>> rows = new ArrayList<>(activities.size());
        for (CampaignActivity activity : activities) {
            Link link = activity.getMainLinkId() == null ? null : links.get(activity.getMainLinkId());
            List<CampaignOffer> linked = couponsByActivity.getOrDefault(activity.getId(), List.of());
            OfferPlan template = linked.isEmpty() ? null : templates.get(linked.get(0).getPlanId());
            OfferInventory allocation = !linked.isEmpty() && linked.get(0).getInventoryId() != null
                    ? allocations.get(linked.get(0).getInventoryId())
                    : null;
            List<Map<String, Object>> checks = new ArrayList<>(publishService.checks(activity, linked, template, allocation, link));
            long reserved = reservationCounts.getOrDefault(activity.getId(), 0L);
            if (reserved == 0L) {
                checks.add(risk("reservations", "暂无预约", "forecast"));
            }
            checks.add(risk("capacity", "容量尚未验证", "forecast"));
            rows.add(buildPresentation(activity, link, linked, template, allocation, checks));
        }
        return rows;
    }

    private Map<String, Object> buildPresentation(CampaignActivity activity, Link link, List<CampaignOffer> linked,
                                                  OfferPlan template, OfferInventory allocation,
                                                  List<Map<String, Object>> checks) {
        Map<String, Object> row = new LinkedHashMap<>();
        ActivityPhase phase = phase(activity);
        row.put("id", activity.getId());
        row.put("name", activity.getName());
        row.put("activityType", activity.getActivityType());
        row.put("status", activity.getStatus());
        row.put("publishStatus", publishStatus(activity.getStatus()));
        row.put("timePhase", timePhase(activity));
        row.put("phase", phase.name());
        row.put("version", activity.getVersion());
        row.put("warmupStartTime", activity.getWarmupStartTime());
        row.put("startTime", activity.getStartTime());
        row.put("endTime", activity.getEndTime());
        row.put("budgetAmount", activity.getBudgetAmount());
        row.put("mainEnabled", activity.getMainEnabled() != null && activity.getMainEnabled() == 1);
        row.put("shortUri", link == null ? "" : link.getShortUri());
        row.put("fullShortUrl", link == null ? "" : link.getFullShortUrl());
        long passed = checks.stream().filter(item -> Boolean.TRUE.equals(item.get("blocking")) && Boolean.TRUE.equals(item.get("passed"))).count();
        long blocking = checks.stream().filter(item -> Boolean.TRUE.equals(item.get("blocking"))).count();
        row.put("checksPassed", passed);
        row.put("checksTotal", blocking);
        row.put("pending", checks.stream()
                .filter(item -> Boolean.TRUE.equals(item.get("blocking")) && !Boolean.TRUE.equals(item.get("passed")))
                .toList());
        row.put("risks", checks.stream().filter(item -> "risk".equals(item.get("level"))).toList());
        row.put("couponName", "");
        row.put("planId", null);
        row.put("quantity", null);
        row.put("claimed", null);
        row.put("maxClaimsPerUser", null);
        row.put("entryState", entryState(activity, null, null));
        if (template != null) {
            row.put("couponName", template.getTitle());
            row.put("planId", template.getId());
            row.put("couponRule", template.getBenefitPolicy());
            row.put("couponValidEndTime", template.getUsableUntil());
            if (allocation != null) {
                row.put("quantity", allocation.getQuantity());
                row.put("claimed", allocation.getClaimedCount());
                row.put("maxClaimsPerUser", allocation.getMaxClaimsPerUser());
                row.put("entryState", entryState(activity, allocation.getQuantity(), allocation.getClaimedCount()));
            }
        }
        row.put("allowedActions", allowedActions(activity, phase));
        return row;
    }

    public List<Map<String, Object>> checks(long activityId) {
        CampaignActivity activity = requireOwned(activityId);
        List<Map<String, Object>> items = new ArrayList<>(publishService.checks(activity));
        Long reserved = reservationMapper.selectCount(Wrappers.<CampaignReservation>lambdaQuery()
                .eq(CampaignReservation::getActivityId, activityId)
                .eq(CampaignReservation::getStatus, ActivityStatuses.RESERVED));
        if (reserved == null || reserved == 0) {
            items.add(risk("reservations", "暂无预约", "forecast"));
        }
        items.add(risk("capacity", "容量尚未验证", "forecast"));
        return items;
    }

    private void validateDetails(String name, String activityType, BigDecimal budget) {
        if (name == null || name.isBlank() || name.trim().length() > 128) {
            throw new BizException(ErrorCode.VALIDATION, "活动名称需为1–128个字");
        }
        if (activityType != null && !java.util.Set.of("优惠券推广活动", "会员专享活动", "新客领取活动").contains(activityType)) {
            throw new BizException(ErrorCode.VALIDATION, "请选择有效的活动类型");
        }
        if (budget != null && (budget.signum() < 0 || budget.scale() > 2 || budget.compareTo(new BigDecimal("9999999999.99")) > 0)) {
            throw new BizException(ErrorCode.VALIDATION, "预算需为非负金额，最多两位小数");
        }
    }

    public void assertBudget(long templateId, int quantity, BigDecimal budget) {
        if (budget == null || budget.signum() < 0) {
            throw new BizException(ErrorCode.VALIDATION, "预算不能为负");
        }
        OfferPlan template = templateService.requireOwned(templateId);
        BigDecimal max = OfferPolicyRules.maximumDiscount(template.getBenefitPolicy()).multiply(BigDecimal.valueOf(quantity));
        if (budget.compareTo(max) < 0) {
            throw new BizException(ErrorCode.VALIDATION, "最大让利 " + max.toPlainString() + " 超过预算 " + budget.toPlainString());
        }
    }

    public int recycleDue() {
        LocalDateTime now = LocalDateTime.now(clock);
        List<CampaignActivity> rows = activityMapper.findDueForRecycle(now);
        int frozen = 0;
        for (CampaignActivity activity : rows) {
            freezeCoupons(activity.getId());
            frozen++;
        }
        return frozen;
    }

    public void freezeCoupons(long activityId) {
        for (CampaignOffer row : couponMapper.selectList(Wrappers.<CampaignOffer>lambdaQuery()
                .eq(CampaignOffer::getActivityId, activityId))) {
            if (row.getInventoryId() != null) {
                allocationService.freeze(row.getInventoryId());
            }
        }
    }

    public List<CampaignOffer> coupons(long activityId) {
        requireOwned(activityId);
        return couponMapper.selectList(Wrappers.<CampaignOffer>lambdaQuery()
                .eq(CampaignOffer::getActivityId, activityId)
                .orderByAsc(CampaignOffer::getSortOrder));
    }

    @Override
    public void assertDirectClaimAllowed(long templateId) {
        LocalDateTime now = LocalDateTime.now(clock);
        List<CampaignOffer> links = couponMapper.selectList(Wrappers.<CampaignOffer>lambdaQuery()
                .eq(CampaignOffer::getPlanId, templateId));
        for (CampaignOffer link : links) {
            CampaignActivity activity = activityMapper.selectById(link.getActivityId());
            if (activity != null && activity.getStatus() != null
                    && activity.getStatus() != ActivityStatuses.DRAFT
                    && activity.getStatus() != ActivityStatuses.CANCELLED
                    && now.isBefore(activity.getEndTime())) {
                throw new BizException(ErrorCode.CONFLICT, "这张券属于活动，请从活动入口领取");
            }
        }
    }

    @Override
    public void assertTemplateRevisionAllowed(long templateId, LocalDateTime validStart, LocalDateTime validEnd, int maxClaimsPerUser) {
        LocalDateTime now = LocalDateTime.now(clock);
        List<CampaignOffer> links = couponMapper.selectList(Wrappers.<CampaignOffer>lambdaQuery()
                .eq(CampaignOffer::getPlanId, templateId));
        for (CampaignOffer link : links) {
            CampaignActivity activity = activityMapper.selectById(link.getActivityId());
            if (activity == null || activity.getStatus() == null || activity.getStatus() == ActivityStatuses.CANCELLED
                    || !now.isBefore(activity.getEndTime())) {
                continue;
            }
            if (validStart != null && validStart.isAfter(activity.getStartTime())) {
                throw new BizException(ErrorCode.VALIDATION, "券生效时间不能晚于已关联活动的开场时间");
            }
            if (validEnd != null && validEnd.isBefore(activity.getEndTime())) {
                throw new BizException(ErrorCode.VALIDATION, "券有效期必须覆盖已关联活动的结束时间");
            }
            if (link.getInventoryId() != null) {
                OfferInventory allocation = allocationService.find(link.getInventoryId());
                if (allocation.getStatus() == InventoryStatus.OPEN && maxClaimsPerUser < allocation.getMaxClaimsPerUser()) {
                    throw new BizException(ErrorCode.VALIDATION, "模板每人限领不能低于已关联活动的单场限领");
                }
            }
        }
    }

    @Override
    public void assertTemplateTerminationAllowed(long templateId) {
        LocalDateTime now = LocalDateTime.now(clock);
        List<CampaignOffer> links = couponMapper.selectList(Wrappers.<CampaignOffer>lambdaQuery()
                .eq(CampaignOffer::getPlanId, templateId));
        for (CampaignOffer link : links) {
            CampaignActivity activity = activityMapper.selectById(link.getActivityId());
            if (activity != null && activity.getStatus() != null
                    && activity.getStatus() != ActivityStatuses.DRAFT
                    && activity.getStatus() != ActivityStatuses.CANCELLED
                    && now.isBefore(activity.getEndTime())) {
                throw new BizException(ErrorCode.CONFLICT, "该优惠券已关联未结束的活动，请先终止活动后再停用券模板");
            }
        }
    }

    public CampaignActivity requireReadable(long activityId) {
        CampaignActivity activity = activityMapper.selectById(activityId);
        if (activity == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "活动不存在");
        }
        return activity;
    }

    private CampaignActivity changeStatus(CampaignActivity activity, int status) {
        int nextVersion = activity.getVersion() + 1;
        int updated = activityMapper.update(null, Wrappers.<CampaignActivity>lambdaUpdate()
                .set(CampaignActivity::getStatus, status)
                .set(CampaignActivity::getVersion, nextVersion)
                .eq(CampaignActivity::getId, activity.getId())
                .eq(CampaignActivity::getVersion, activity.getVersion()));
        if (updated != 1) {
            throw new BizException(ErrorCode.CONFLICT, "活动已更新，请刷新后再保存");
        }
        activity.setStatus(status);
        activity.setVersion(nextVersion);
        pageCache.evict(activity.getId());
        return activity;
    }

    private void assertCouponPeriodCovered(long activityId, LocalDateTime start, LocalDateTime end) {
        List<CampaignOffer> linked = couponMapper.selectList(Wrappers.<CampaignOffer>lambdaQuery()
                .eq(CampaignOffer::getActivityId, activityId));
        if (linked.isEmpty()) {
            return;
        }
        OfferPlan template = templateService.requireOwned(linked.get(0).getPlanId());
        if (start != null && template.getUsableFrom().isAfter(start)) {
            throw new BizException(ErrorCode.VALIDATION, "开始时间不能早于券的生效开始时间");
        }
        if (template.getUsableUntil().isBefore(end)) {
            throw new BizException(ErrorCode.VALIDATION, "结束时间不能晚于券的使用有效期");
        }
    }

    private static Map<String, Object> risk(String code, String reason, String fix) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("code", code);
        row.put("passed", true);
        row.put("blocking", false);
        row.put("level", "risk");
        row.put("reason", reason);
        row.put("fix", fix);
        return row;
    }

    private String entryState(CampaignActivity activity, Integer quantity, Integer claimed) {
        ActivityPhase phase = phase(activity);
        if (phase == ActivityPhase.DRAFT) {
            return "DRAFT";
        }
        if (phase == ActivityPhase.OFFLINE || phase == ActivityPhase.PAUSED || phase == ActivityPhase.CANCELLED) {
            return "UNAVAILABLE";
        }
        if (phase == ActivityPhase.ENDED) {
            return "ENDED";
        }
        if (phase == ActivityPhase.WARMUP) {
            return "RESERVE";
        }
        if (phase == ActivityPhase.LIVE) {
            if (quantity != null && claimed != null && claimed >= quantity) {
                return "SOLD_OUT";
            }
            return "CLAIMABLE";
        }
        return "UNAVAILABLE";
    }

    private List<String> allowedActions(CampaignActivity activity, ActivityPhase phase) {
        List<String> actions = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now(clock);
        boolean beforeEnd = now.isBefore(activity.getEndTime());
        if (beforeEnd && (activity.getStatus() == ActivityStatuses.DRAFT
                || activity.getStatus() == ActivityStatuses.OFFLINE
                || activity.getStatus() == ActivityStatuses.PAUSED)) {
            actions.add(activity.getStatus() == ActivityStatuses.DRAFT ? "publish" : "republish");
        }
        if (beforeEnd && (activity.getStatus() == ActivityStatuses.PUBLISHED || activity.getStatus() == ActivityStatuses.PAUSED)) {
            actions.add("offline");
        }
        if (activity.getStatus() != ActivityStatuses.CANCELLED && phase != ActivityPhase.ENDED) {
            actions.add("terminate");
            actions.add("edit");
        }
        actions.add("copy");
        return actions;
    }

    private static String publishStatus(Integer status) {
        if (status == null) {
            return "DRAFT";
        }
        return switch (status) {
            case ActivityStatuses.PUBLISHED -> "PUBLISHED";
            case ActivityStatuses.OFFLINE -> "OFFLINE";
            case ActivityStatuses.PAUSED -> "PAUSED";
            case ActivityStatuses.CANCELLED -> "TERMINATED";
            default -> "DRAFT";
        };
    }

    private String timePhase(CampaignActivity activity) {
        LocalDateTime now = LocalDateTime.now(clock);
        if (now.isBefore(activity.getStartTime())) {
            return "BEFORE";
        }
        if (now.isBefore(activity.getEndTime())) {
            return "CLAIMING";
        }
        return "ENDED";
    }

    private void validateTimes(LocalDateTime warmupStart, LocalDateTime start, LocalDateTime end) {
        if (warmupStart == null || start == null || end == null
                || !start.isAfter(warmupStart) || !end.isAfter(start)) {
            throw new BizException(ErrorCode.VALIDATION, "预热、开始和结束时间必须依次排开");
        }
    }

    public static Clock shanghaiClock() {
        return Clock.system(ZoneId.of("Asia/Shanghai"));
    }
}
