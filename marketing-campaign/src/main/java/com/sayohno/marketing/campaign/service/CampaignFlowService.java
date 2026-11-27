package com.sayohno.marketing.campaign.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sayohno.marketing.campaign.ActivityPhase;
import com.sayohno.marketing.campaign.ActivityStatuses;
import com.sayohno.marketing.campaign.dao.entity.CampaignActivity;
import com.sayohno.marketing.campaign.dao.entity.CampaignChannel;
import com.sayohno.marketing.campaign.dao.entity.CampaignOffer;
import com.sayohno.marketing.campaign.dao.entity.CampaignOperation;
import com.sayohno.marketing.campaign.dao.entity.CampaignReservation;
import com.sayohno.marketing.campaign.dao.mapper.CampaignChannelMapper;
import com.sayohno.marketing.campaign.dao.mapper.CampaignOfferMapper;
import com.sayohno.marketing.campaign.dao.mapper.CampaignOperationMapper;
import com.sayohno.marketing.common.exception.BizException;
import com.sayohno.marketing.common.result.ErrorCode;
import com.sayohno.marketing.coupon.dao.entity.IssuedVoucher;
import com.sayohno.marketing.coupon.dao.entity.OfferInventory;
import com.sayohno.marketing.coupon.service.OfferInventoryService;
import com.sayohno.marketing.shortlink.service.LinkDirectoryService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 活动编排门面服务：
 * 保留既有对外方法签名以兼容控制器与集成测试，将渠道、入口访问、领券编排及报表分析委托至各专职领域服务。
 */
@Service
public class CampaignFlowService {
    public static final String CONVERSION_EVENT = CampaignClaimService.CONVERSION_EVENT;

    private final CampaignService campaignService;
    private final CampaignChannelService channelService;
    private final CampaignEntryService entryService;
    private final CampaignClaimService claimCoordinator;
    private final CampaignAnalyticsService analyticsService;
    private final CampaignOfferMapper couponMapper;
    private final CampaignChannelMapper channelMapper;
    private final CampaignOperationMapper operationMapper;
    private final OfferInventoryService allocationService;
    private final LinkDirectoryService linkDirectoryService;
    private final CampaignPageCache pageCache;
    private final Clock clock;

    public CampaignFlowService(CampaignService campaignService,
                               CampaignChannelService channelService,
                               CampaignEntryService entryService,
                               CampaignClaimService claimCoordinator,
                               CampaignAnalyticsService analyticsService,
                               CampaignOfferMapper couponMapper,
                               CampaignChannelMapper channelMapper,
                               CampaignOperationMapper operationMapper,
                               OfferInventoryService allocationService,
                               LinkDirectoryService linkDirectoryService,
                               CampaignPageCache pageCache,
                               Clock clock) {
        this.campaignService = campaignService;
        this.channelService = channelService;
        this.entryService = entryService;
        this.claimCoordinator = claimCoordinator;
        this.analyticsService = analyticsService;
        this.couponMapper = couponMapper;
        this.channelMapper = channelMapper;
        this.operationMapper = operationMapper;
        this.allocationService = allocationService;
        this.linkDirectoryService = linkDirectoryService;
        this.pageCache = pageCache;
        this.clock = clock;
    }

    @Transactional
    public CampaignChannel addChannel(long activityId, String name, String idempotencyKey) {
        return channelService.addChannel(activityId, name, idempotencyKey);
    }

    @Transactional
    public CampaignChannel addChannel(long activityId, String name, String idempotencyKey, String customCode) {
        return channelService.addChannel(activityId, name, idempotencyKey, customCode);
    }

    public CampaignChannel setChannelEnabled(long activityId, long channelId, boolean enabled) {
        return channelService.setChannelEnabled(activityId, channelId, enabled);
    }

    public List<CampaignChannel> channels(long activityId) {
        return channelService.channels(activityId);
    }

    public Map<String, Object> mainLink(long activityId) {
        return channelService.getMainLink(activityId).toMap();
    }

    @Transactional
    public Map<String, Object> openMain(long activityId) {
        return channelService.openMain(activityId).toMap();
    }

    public Map<String, Object> visit(long activityId, String channelCode, String visitorId) {
        return entryService.visit(activityId, channelCode, visitorId, true);
    }

    public Map<String, Object> visit(long activityId, String channelCode, String visitorId, boolean recordVisit) {
        return entryService.visit(activityId, channelCode, visitorId, recordVisit);
    }

    public Map<String, Object> warmPageCache(long activityId) {
        return entryService.warmPageCache(activityId);
    }

    public Map<String, Object> entry(String shortUri, String channelCode, String visitorId) {
        return entryService.entry(shortUri, channelCode, visitorId, true);
    }

    public Map<String, Object> entry(String shortUri, String channelCode, String visitorId, boolean recordVisit) {
        return entryService.entry(shortUri, channelCode, visitorId, recordVisit);
    }

    @Transactional
    public CampaignReservation reserve(long userId, long activityId, String credential) {
        return entryService.reserve(userId, activityId, credential);
    }

    @Transactional
    public CampaignReservation cancelReservation(long userId, long activityId) {
        return entryService.cancelReservation(userId, activityId);
    }

    public long activeReservations(long activityId) {
        return entryService.activeReservations(activityId);
    }

    public Map<String, Object> personalState(long activityId, long userId) {
        return entryService.getPersonalState(activityId, userId).toMap();
    }

    @Transactional
    public void identifyVisitor(long activityId, String credential, long userId) {
        entryService.identifyVisitor(activityId, credential, userId);
    }

    public IssuedVoucher claim(long userId, long activityId, long templateId, String requestId, String credential) {
        return claimCoordinator.claim(userId, activityId, templateId, requestId, credential);
    }

    public Map<String, Object> claimAsync(long userId, long activityId, long templateId, String requestId, String credential) {
        return claimCoordinator.claimAsync(userId, activityId, templateId, requestId, credential).toMap();
    }

    public Map<String, Object> claimStatus(long userId, long activityId, long templateId, String requestId) {
        return claimCoordinator.claimStatus(userId, activityId, templateId, requestId).toMap();
    }

    public void completeClaimAttribution(long voucherId) {
        claimCoordinator.completeClaimAttribution(voucherId);
    }

    public void recordFromCouponEvent(String eventType, long voucherId, Long settlementId) {
        claimCoordinator.recordFromCouponEvent(eventType, voucherId, settlementId);
    }

    public List<Map<String, Object>> listActivitiesSummary() {
        return analyticsService.listActivitiesSummary();
    }

    public List<Map<String, Object>> report(long activityId) {
        return analyticsService.report(activityId);
    }

    public long attributedClaims(long activityId) {
        return analyticsService.attributedClaims(activityId);
    }

    public long effectiveVisitors(long activityId) {
        return analyticsService.effectiveVisitors(activityId);
    }

    public long unreservedActiveVisitors(long activityId, List<Long> activeChannelIds) {
        return analyticsService.unreservedActiveVisitors(activityId, activeChannelIds);
    }

    public Map<String, Object> dashboard(long activityId) {
        return analyticsService.dashboard(activityId);
    }

    public Map<String, Object> review(long activityId) {
        return analyticsService.review(activityId);
    }

    @Transactional
    public CampaignOffer place(long activityId, int quantity, int maxClaimsPerUser, String idempotencyKey, BigDecimal budget) {
        CampaignActivity activity = campaignService.lockOwned(activityId);
        if (phaseEnded(activity)) {
            throw new BizException(ErrorCode.CONFLICT, "活动已结束或已下线");
        }
        CampaignOffer coupon = couponMapper.selectOne(Wrappers.<CampaignOffer>lambdaQuery()
                .eq(CampaignOffer::getActivityId, activityId));
        if (coupon == null) {
            throw new BizException(ErrorCode.VALIDATION, "先选择一张券");
        }
        boolean beforeStart = activity.getStatus() == ActivityStatuses.DRAFT
                || LocalDateTime.now(clock).isBefore(activity.getStartTime());
        OfferInventory allocation;
        if (coupon.getInventoryId() == null) {
            if (!beforeStart) {
                throw new BizException(ErrorCode.CONFLICT, "开场后不能新建投放");
            }
            allocation = allocationService.allocate(activityId, coupon.getPlanId(), quantity, maxClaimsPerUser, idempotencyKey);
            coupon.setInventoryId(allocation.getId());
            couponMapper.updateById(coupon);
        } else {
            allocation = allocationService.adjust(coupon.getInventoryId(), quantity, maxClaimsPerUser, idempotencyKey, beforeStart, beforeStart);
        }
        BigDecimal effectiveBudget = budget != null ? budget : activity.getBudgetAmount();
        if (effectiveBudget != null || activity.getStatus() != ActivityStatuses.DRAFT) {
            campaignService.assertBudget(coupon.getPlanId(), allocation.getQuantity(), effectiveBudget);
        }
        if (budget != null) {
            campaignService.saveBudget(activityId, budget);
        }
        pageCache.evict(activityId);
        return coupon;
    }

    @Transactional
    public CampaignOffer saveOffer(long activityId, Long templateId, int quantity, int maxClaimsPerUser,
                                    String idempotencyKey, BigDecimal budget, Integer version) {
        CampaignActivity activity = campaignService.lockOwned(activityId);
        if (version != null && !version.equals(activity.getVersion())) {
            throw new BizException(ErrorCode.CONFLICT, "活动已更新，请刷新后再保存");
        }
        if (phaseEnded(activity)) {
            throw new BizException(ErrorCode.CONFLICT, "活动已结束或已终止");
        }
        if (templateId == null) {
            throw new BizException(ErrorCode.VALIDATION, "先选择一张券");
        }
        CampaignOffer coupon = couponMapper.selectOne(Wrappers.<CampaignOffer>lambdaQuery()
                .eq(CampaignOffer::getActivityId, activityId));
        if (coupon != null && !coupon.getPlanId().equals(templateId)) {
            if (activity.getStatus() != ActivityStatuses.DRAFT) {
                throw new BizException(ErrorCode.CONFLICT, "发布后不能更换券模板");
            }
            if (coupon.getInventoryId() != null) {
                allocationService.freeze(coupon.getInventoryId());
            }
            couponMapper.deleteById(coupon.getId());
            coupon = null;
        }
        if (coupon == null) {
            campaignService.attachCoupon(activityId, templateId);
        }
        CampaignOffer placed = place(activityId, quantity, maxClaimsPerUser, idempotencyKey, budget);
        campaignService.bumpVersion(activityId, version);
        return placed;
    }

    @Transactional
    public long copy(long activityId) {
        CampaignActivity source = campaignService.requireOwned(activityId);
        String name = source.getName().endsWith("副本") ? source.getName() : source.getName() + " 副本";
        if (name.length() > 128) {
            name = name.substring(0, 128);
        }
        CampaignActivity created = campaignService.create(name, source.getWarmupStartTime(), source.getStartTime(),
                source.getEndTime(), source.getActivityType(), source.getBudgetAmount());
        CampaignOffer coupon = couponMapper.selectOne(Wrappers.<CampaignOffer>lambdaQuery()
                .eq(CampaignOffer::getActivityId, activityId));
        if (coupon != null) {
            campaignService.attachCoupon(created.getId(), coupon.getPlanId());
        }
        for (CampaignChannel channel : channelService.channels(activityId)) {
            channelService.addChannel(created.getId(), channel.getName(), "copy-" + created.getId() + "-" + channel.getId());
        }
        channelService.openMain(created.getId());
        return created.getId();
    }

    @Transactional
    public void deleteDraft(long activityId) {
        // 与发布共用活动行锁，确认仍是草稿后才开始冻结库存和删除关联数据。
        CampaignActivity activity = campaignService.lockOwned(activityId);
        if (activity.getStatus() == null || activity.getStatus() != ActivityStatuses.DRAFT) {
            throw new BizException(ErrorCode.CONFLICT, "只有草稿状态的活动可以删除");
        }
        List<CampaignOffer> offers = couponMapper.selectList(Wrappers.<CampaignOffer>lambdaQuery()
                .eq(CampaignOffer::getActivityId, activityId));
        for (CampaignOffer offer : offers) {
            if (offer.getInventoryId() != null) {
                allocationService.freeze(offer.getInventoryId());
            }
        }
        couponMapper.delete(Wrappers.<CampaignOffer>lambdaQuery().eq(CampaignOffer::getActivityId, activityId));
        channelMapper.delete(Wrappers.<CampaignChannel>lambdaQuery().eq(CampaignChannel::getActivityId, activityId));
        if (operationMapper != null) {
            operationMapper.delete(Wrappers.<CampaignOperation>lambdaQuery()
                    .eq(CampaignOperation::getActivityId, activityId));
        }
        if (activity.getMainLinkId() != null) {
            linkDirectoryService.updateManagedLink(activity.getMainLinkId(), null, null, 1);
        }
        campaignService.deleteRecordAndEvict(activityId);
    }

    private boolean phaseEnded(CampaignActivity activity) {
        ActivityPhase phase = campaignService.phase(activity);
        return phase == ActivityPhase.ENDED || phase == ActivityPhase.CANCELLED;
    }
}
