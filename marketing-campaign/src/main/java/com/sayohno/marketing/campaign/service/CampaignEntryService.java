package com.sayohno.marketing.campaign.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sayohno.marketing.campaign.ActivityPhase;
import com.sayohno.marketing.campaign.ActivityStatuses;
import com.sayohno.marketing.campaign.dao.entity.CampaignActivity;
import com.sayohno.marketing.campaign.dao.entity.CampaignChannel;
import com.sayohno.marketing.campaign.dao.entity.CampaignOffer;
import com.sayohno.marketing.campaign.dao.entity.CampaignReservation;
import com.sayohno.marketing.campaign.dao.entity.CampaignVisit;
import com.sayohno.marketing.campaign.dao.mapper.CampaignChannelMapper;
import com.sayohno.marketing.campaign.dao.mapper.CampaignOfferMapper;
import com.sayohno.marketing.campaign.dao.mapper.CampaignReservationMapper;
import com.sayohno.marketing.campaign.dao.mapper.CampaignVisitMapper;
import com.sayohno.marketing.campaign.web.dto.PersonalStateView;
import com.sayohno.marketing.common.exception.BizException;
import com.sayohno.marketing.common.hash.RequestHashes;
import com.sayohno.marketing.common.result.ErrorCode;
import com.sayohno.marketing.coupon.dao.entity.OfferInventory;
import com.sayohno.marketing.coupon.service.OfferInventoryService;
import com.sayohno.marketing.coupon.service.OfferPlanService;
import com.sayohno.marketing.coupon.service.VoucherClaimService;
import com.sayohno.marketing.shortlink.dao.entity.Link;
import com.sayohno.marketing.shortlink.service.LinkDirectoryService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 活动落地页访问、短链入口、预约、人群资格与访客凭证校验服务。
 */
@Service
public class CampaignEntryService {
    private final CampaignService campaignService;
    private final CampaignChannelMapper channelMapper;
    private final CampaignOfferMapper couponMapper;
    private final CampaignVisitMapper visitMapper;
    private final CampaignReservationMapper reservationMapper;
    private final LinkDirectoryService linkDirectoryService;
    private final OfferPlanService templateService;
    private final OfferInventoryService allocationService;
    private final VoucherClaimService claimService;
    private final CampaignPageCache pageCache;
    private final Clock clock;

    public CampaignEntryService(CampaignService campaignService,
                                CampaignChannelMapper channelMapper,
                                CampaignOfferMapper couponMapper,
                                CampaignVisitMapper visitMapper,
                                CampaignReservationMapper reservationMapper,
                                LinkDirectoryService linkDirectoryService,
                                OfferPlanService templateService,
                                OfferInventoryService allocationService,
                                VoucherClaimService claimService,
                                CampaignPageCache pageCache,
                                Clock clock) {
        this.campaignService = campaignService;
        this.channelMapper = channelMapper;
        this.couponMapper = couponMapper;
        this.visitMapper = visitMapper;
        this.reservationMapper = reservationMapper;
        this.linkDirectoryService = linkDirectoryService;
        this.templateService = templateService;
        this.allocationService = allocationService;
        this.claimService = claimService;
        this.pageCache = pageCache;
        this.clock = clock;
    }

    public Map<String, Object> visit(long activityId, String channelCode, String visitorId, boolean recordVisit) {
        CampaignPageCache.PageSnapshot snapshot = pageCache.getOrLoad(activityId, () -> loadPageSnapshot(activityId));
        return renderFromSnapshot(snapshot, channelCode, visitorId, recordVisit);
    }

    public Map<String, Object> warmPageCache(long activityId) {
        campaignService.requireOwned(activityId);
        return pageCache.warm(activityId, () -> loadPageSnapshot(activityId));
    }

    public Map<String, Object> entry(String shortUri, String channelCode, String visitorId, boolean recordVisit) {
        Link link = linkDirectoryService.findByShortUri(shortUri);
        if (link == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "短链不存在");
        }
        CampaignActivity activity = campaignService.findByMainLink(link.getId());
        CampaignChannel channel = null;
        if (activity == null) {
            channel = channelMapper.selectOne(Wrappers.<CampaignChannel>lambdaQuery()
                    .eq(CampaignChannel::getLinkId, link.getId())
                    .last("LIMIT 1"));
            if (channel == null) {
                throw new BizException(ErrorCode.NOT_FOUND, "这条短链没有对应的活动");
            }
            activity = campaignService.requireReadable(channel.getActivityId());
        }
        String blocked = entryBlock(activity, link, channelCode);
        Long channelId = null;
        String source = "DIRECT";
        if (blocked == null && channelCode != null && !channelCode.isBlank()) {
            channel = channelMapper.selectOne(Wrappers.<CampaignChannel>lambdaQuery()
                    .eq(CampaignChannel::getActivityId, activity.getId())
                    .eq(CampaignChannel::getChannelCode, channelCode));
            if (channel == null) {
                blocked = "渠道不存在";
            } else if (channel.getStatus() == null || channel.getStatus() != 1) {
                blocked = "渠道已停用";
            } else {
                channelId = channel.getId();
                source = channel.getName();
            }
        } else if (blocked == null && channel != null && activity.getMainLinkId() == null) {
            if (channel.getStatus() == null || channel.getStatus() != 1) {
                blocked = "渠道已停用";
            } else {
                channelId = channel.getId();
                source = channel.getName();
            }
        }
        ActivityPhase phase = campaignService.phase(activity);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("activityId", activity.getId());
        body.put("phase", phase.name());
        body.put("serverTime", LocalDateTime.now(clock).toString());
        body.put("source", blocked == null ? source : "UNAVAILABLE");
        body.put("action", action(activity, blocked));
        body.put("reason", blocked == null ? "" : blocked);
        if (phase == ActivityPhase.DRAFT) {
            return body;
        }
        body.put("name", activity.getName());
        body.put("startTime", activity.getStartTime().toString());
        body.put("endTime", activity.getEndTime().toString());
        CampaignOffer linked = couponMapper.selectOne(Wrappers.<CampaignOffer>lambdaQuery()
                .eq(CampaignOffer::getActivityId, activity.getId()));
        if (linked != null) {
            body.put("planId", linked.getPlanId());
            body.putAll(templateService.publicOffer(linked.getPlanId(), activity.getMerchantId()));
            if (linked.getInventoryId() != null) {
                OfferInventory allocation = allocationService.find(linked.getInventoryId());
                body.put("quantity", allocation.getQuantity());
                body.put("claimed", allocation.getClaimedCount());
                body.put("maxClaimsPerUser", allocation.getMaxClaimsPerUser());
            }
        }
        if (recordVisit && blocked == null && !"ENDED".equals(body.get("action"))) {
            String raw = UUID.randomUUID().toString().replace("-", "");
            CampaignVisit visit = new CampaignVisit();
            visit.setVisitTokenHash(RequestHashes.sha256(raw));
            visit.setActivityId(activity.getId());
            visit.setChannelId(channelId);
            visit.setVisitorId(visitorId);
            visit.setVisitedAt(LocalDateTime.now(clock));
            visit.setExpireAt(LocalDateTime.now(clock).plusMinutes(30));
            visit.setValidFlag(1);
            visitMapper.insert(visit);
            body.put("credential", raw);
            body.put("credentialExpireAt", visit.getExpireAt().toString());
        }
        return body;
    }

    @Transactional
    public CampaignReservation reserve(long userId, long activityId, String credential) {
        CampaignVisit visit = requireVisit(activityId, credential, userId);
        CampaignActivity activity = campaignService.requireReadable(activityId);
        if (!campaignService.phase(activity).reservationOpen()) {
            throw new BizException(ErrorCode.CONFLICT, "当前不在预约时间");
        }
        // 预约阶段前置校验活动人群资格，避免不符合条件的用户白预约并污染开场峰值预测。
        String eligibilityReason = eligibilityFailure(activity, userId);
        if (eligibilityReason != null) {
            throw new BizException(ErrorCode.VALIDATION, eligibilityReason);
        }
        CampaignReservation existing = reservationMapper.selectOne(Wrappers.<CampaignReservation>lambdaQuery()
                .eq(CampaignReservation::getActivityId, activityId)
                .eq(CampaignReservation::getUserId, userId));
        LocalDateTime now = LocalDateTime.now(clock);
        if (existing == null) {
            CampaignReservation created = new CampaignReservation();
            created.setActivityId(activityId);
            created.setUserId(userId);
            created.setChannelId(visit.getChannelId());
            created.setStatus(ActivityStatuses.RESERVED);
            created.setReservedAt(now);
            reservationMapper.insert(created);
            return created;
        }
        existing.setStatus(ActivityStatuses.RESERVED);
        existing.setChannelId(visit.getChannelId());
        existing.setReservedAt(now);
        existing.setCancelledAt(null);
        reservationMapper.updateById(existing);
        return existing;
    }

    @Transactional
    public CampaignReservation cancelReservation(long userId, long activityId) {
        CampaignReservation existing = reservationMapper.selectOne(Wrappers.<CampaignReservation>lambdaQuery()
                .eq(CampaignReservation::getActivityId, activityId)
                .eq(CampaignReservation::getUserId, userId)
                .eq(CampaignReservation::getStatus, ActivityStatuses.RESERVED));
        if (existing == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "没有有效预约");
        }
        existing.setStatus(ActivityStatuses.RESERVATION_CANCELLED);
        existing.setCancelledAt(LocalDateTime.now(clock));
        reservationMapper.updateById(existing);
        return existing;
    }

    public long activeReservations(long activityId) {
        campaignService.requireOwned(activityId);
        Long count = reservationMapper.selectCount(Wrappers.<CampaignReservation>lambdaQuery()
                .eq(CampaignReservation::getActivityId, activityId)
                .eq(CampaignReservation::getStatus, ActivityStatuses.RESERVED));
        return count == null ? 0 : count;
    }

    public PersonalStateView getPersonalState(long activityId, long userId) {
        CampaignActivity activity = campaignService.requireReadable(activityId);
        boolean reserved = reservationMapper.selectCount(Wrappers.<CampaignReservation>lambdaQuery()
                .eq(CampaignReservation::getActivityId, activityId)
                .eq(CampaignReservation::getUserId, userId)
                .eq(CampaignReservation::getStatus, ActivityStatuses.RESERVED)) > 0;
        CampaignOffer linked = couponMapper.selectOne(Wrappers.<CampaignOffer>lambdaQuery()
                .eq(CampaignOffer::getActivityId, activityId));
        long claimed = linked == null || linked.getInventoryId() == null ? 0
                : claimService.receivedCount(userId, linked.getInventoryId());
        String eligibilityReason = eligibilityFailure(activity, userId);
        return new PersonalStateView(reserved, claimed, eligibilityReason == null, eligibilityReason == null ? "" : eligibilityReason);
    }

    public String eligibilityFailure(CampaignActivity activity, long userId) {
        String type = activity.getActivityType();
        if ("新客领取活动".equals(type) && claimService.hasMerchantVoucher(userId, activity.getMerchantId())) {
            return "本活动仅限新用户参与";
        }
        if ("会员专享活动".equals(type) && !claimService.hasRedeemedMerchantVoucher(userId, activity.getMerchantId())) {
            return "本活动仅限已有核销记录的会员参与";
        }
        return null;
    }

    @Transactional
    public void identifyVisitor(long activityId, String credential, long userId) {
        requireVisit(activityId, credential, userId);
    }

    public CampaignVisit requireVisit(long activityId, String credential, long userId) {
        if (credential == null || credential.isBlank()) {
            throw new BizException(ErrorCode.VALIDATION, "缺少访问凭证");
        }
        CampaignVisit visit = visitMapper.selectOne(Wrappers.<CampaignVisit>lambdaQuery()
                .eq(CampaignVisit::getVisitTokenHash, RequestHashes.sha256(credential)));
        if (visit == null || visit.getValidFlag() == null || visit.getValidFlag() != 1) {
            throw new BizException(ErrorCode.CONFLICT, "访问凭证无效");
        }
        if (!visit.getActivityId().equals(activityId)) {
            throw new BizException(ErrorCode.CONFLICT, "访问凭证不属于这场活动");
        }
        if (!LocalDateTime.now(clock).isBefore(visit.getExpireAt())) {
            throw new BizException(ErrorCode.CONFLICT, "访问凭证已过期");
        }
        if (visit.getUserId() == null) {
            visit.setUserId(userId);
            visitMapper.updateById(visit);
        } else if (!visit.getUserId().equals(userId)) {
            throw new BizException(ErrorCode.CONFLICT, "访问凭证已绑定其他用户");
        }
        return visit;
    }

    private CampaignPageCache.PageSnapshot loadPageSnapshot(long activityId) {
        CampaignActivity activity = campaignService.requireReadable(activityId);
        Integer linkEnableStatus = null;
        if (activity.getMainLinkId() != null) {
            Link link = linkDirectoryService.findExisting(activity.getMainLinkId());
            if (link != null) {
                linkEnableStatus = link.getEnableStatus() == null ? 0 : link.getEnableStatus();
            }
        }
        CampaignOffer linked = couponMapper.selectOne(Wrappers.<CampaignOffer>lambdaQuery()
                .eq(CampaignOffer::getActivityId, activityId));
        Long planId = null;
        Long inventoryId = null;
        Integer quantity = null;
        Integer claimed = null;
        Integer maxClaimsPerUser = null;
        Map<String, Object> publicOffer = Map.of();
        if (linked != null) {
            planId = linked.getPlanId();
            publicOffer = templateService.publicOffer(linked.getPlanId(), activity.getMerchantId());
            if (linked.getInventoryId() != null) {
                inventoryId = linked.getInventoryId();
                OfferInventory allocation = allocationService.find(inventoryId);
                quantity = allocation.getQuantity();
                claimed = allocation.getClaimedCount();
                maxClaimsPerUser = allocation.getMaxClaimsPerUser();
            }
        }
        Map<String, CampaignPageCache.ChannelSnapshot> channelMap = new LinkedHashMap<>();
        for (CampaignChannel ch : channelMapper.selectList(Wrappers.<CampaignChannel>lambdaQuery()
                .eq(CampaignChannel::getActivityId, activityId))) {
            channelMap.put(ch.getChannelCode(), new CampaignPageCache.ChannelSnapshot(
                    ch.getId(), ch.getChannelCode(), ch.getName(), ch.getStatus() == null ? 0 : ch.getStatus()));
        }
        return new CampaignPageCache.PageSnapshot(
                activity.getId(), activity.getMerchantId(), activity.getName(), activity.getActivityType(),
                activity.getStatus(), activity.getVersion(),
                activity.getWarmupStartTime(), activity.getStartTime(), activity.getEndTime(),
                activity.getMainLinkId(), activity.getMainEnabled(), linkEnableStatus,
                planId, inventoryId, quantity, claimed, maxClaimsPerUser,
                publicOffer, channelMap);
    }

    private Map<String, Object> renderFromSnapshot(CampaignPageCache.PageSnapshot snapshot, String channelCode,
                                                   String visitorId, boolean recordVisit) {
        LocalDateTime now = LocalDateTime.now(clock);
        ActivityPhase phase = ActivityPhase.of(snapshot.status(), snapshot.warmupStartTime(),
                snapshot.startTime(), snapshot.endTime(), now);
        String blocked = snapshotBlock(phase, channelCode);
        Long channelId = null;
        String source = "DIRECT";
        if (blocked == null && channelCode != null && !channelCode.isBlank()) {
            CampaignPageCache.ChannelSnapshot ch = snapshot.channels() == null ? null : snapshot.channels().get(channelCode);
            if (ch == null) {
                blocked = "渠道不存在";
            } else if (ch.status() != 1) {
                blocked = "渠道已停用";
            } else {
                channelId = ch.id();
                source = ch.name();
            }
        }
        Integer quantity = snapshot.quantity();
        Integer claimed = snapshot.claimed();
        if (snapshot.inventoryId() != null && quantity != null) {
            Integer liveRemaining = pageCache.readLiveRemaining(snapshot.planId(), snapshot.inventoryId());
            if (liveRemaining != null) {
                claimed = Math.max(0, quantity - liveRemaining);
            }
        }
        String actionValue = snapshotAction(phase, blocked, snapshot.inventoryId(), quantity, claimed);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("activityId", snapshot.activityId());
        body.put("phase", phase.name());
        body.put("serverTime", now.toString());
        body.put("source", blocked == null ? source : "UNAVAILABLE");
        body.put("action", actionValue);
        body.put("reason", blocked == null ? "" : blocked);
        if (phase == ActivityPhase.DRAFT) {
            return body;
        }
        body.put("name", snapshot.name());
        body.put("activityType", snapshot.activityType() == null ? "优惠券推广活动" : snapshot.activityType());
        body.put("startTime", snapshot.startTime().toString());
        body.put("endTime", snapshot.endTime().toString());
        if (snapshot.planId() != null) {
            body.put("planId", snapshot.planId());
            if (snapshot.publicOffer() != null) {
                body.putAll(snapshot.publicOffer());
            }
            if (snapshot.inventoryId() != null) {
                body.put("quantity", quantity);
                body.put("claimed", claimed);
                body.put("maxClaimsPerUser", snapshot.maxClaimsPerUser());
            }
        }
        if (recordVisit && blocked == null && !"ENDED".equals(actionValue)) {
            String raw = UUID.randomUUID().toString().replace("-", "");
            CampaignVisit visit = new CampaignVisit();
            visit.setVisitTokenHash(RequestHashes.sha256(raw));
            visit.setActivityId(snapshot.activityId());
            visit.setChannelId(channelId);
            visit.setVisitorId(visitorId);
            visit.setVisitedAt(now);
            visit.setExpireAt(now.plusMinutes(30));
            visit.setValidFlag(1);
            visitMapper.insert(visit);
            body.put("credential", raw);
            body.put("credentialExpireAt", visit.getExpireAt().toString());
        }
        return body;
    }

    private static String snapshotBlock(ActivityPhase phase, String channelCode) {
        if (channelCode != null && !channelCode.isBlank() && !channelCode.matches("[A-Za-z0-9]{1,32}")) {
            return "渠道参数不合法";
        }
        if (phase == ActivityPhase.OFFLINE) return "活动已下线";
        if (phase == ActivityPhase.CANCELLED) return "活动已终止";
        if (phase == ActivityPhase.PAUSED) return "活动已暂停";
        if (phase == ActivityPhase.DRAFT) return "活动还没发布";
        return null;
    }

    private static String snapshotAction(ActivityPhase phase, String blocked, Long inventoryId, Integer quantity, Integer claimed) {
        if (blocked != null) return "UNAVAILABLE";
        if (phase == ActivityPhase.ENDED) return "ENDED";
        if (phase == ActivityPhase.WARMUP) return "RESERVE";
        if (phase == ActivityPhase.LIVE) {
            if (inventoryId == null || quantity == null || claimed == null) return "SOLD_OUT";
            return claimed < quantity ? "CLAIM" : "SOLD_OUT";
        }
        return "UNAVAILABLE";
    }

    private String entryBlock(CampaignActivity activity, Link link, String channelCode) {
        if (channelCode != null && !channelCode.isBlank() && !channelCode.matches("[A-Za-z0-9]{1,32}")) {
            return "渠道参数不合法";
        }
        boolean mainOff = activity.getMainLinkId() != null && (activity.getMainEnabled() == null || activity.getMainEnabled() != 1
                || link.getEnableStatus() == null || link.getEnableStatus() != 0);
        if (mainOff) {
            return "主链已停用";
        }
        ActivityPhase phase = campaignService.phase(activity);
        if (phase == ActivityPhase.OFFLINE) return "活动已下线";
        if (phase == ActivityPhase.CANCELLED) return "活动已终止";
        if (phase == ActivityPhase.PAUSED) return "活动已暂停";
        if (phase == ActivityPhase.DRAFT) return "活动还没发布";
        return null;
    }

    private String action(CampaignActivity activity, String blocked) {
        if (blocked != null) return "UNAVAILABLE";
        ActivityPhase phase = campaignService.phase(activity);
        if (phase == ActivityPhase.ENDED) return "ENDED";
        if (phase == ActivityPhase.WARMUP) return "RESERVE";
        if (phase == ActivityPhase.LIVE) {
            CampaignOffer coupon = couponMapper.selectOne(Wrappers.<CampaignOffer>lambdaQuery()
                    .eq(CampaignOffer::getActivityId, activity.getId()));
            if (coupon == null || coupon.getInventoryId() == null) {
                return "SOLD_OUT";
            }
            OfferInventory allocation = allocationService.find(coupon.getInventoryId());
            return allocation.getClaimedCount() < allocation.getQuantity() ? "CLAIM" : "SOLD_OUT";
        }
        return "UNAVAILABLE";
    }
}
