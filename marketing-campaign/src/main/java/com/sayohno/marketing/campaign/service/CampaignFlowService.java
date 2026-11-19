package com.sayohno.marketing.campaign.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sayohno.marketing.campaign.ActivityPhase;
import com.sayohno.marketing.campaign.ActivityStatuses;
import com.sayohno.marketing.campaign.dao.entity.CampaignActivity;
import com.sayohno.marketing.campaign.dao.entity.CampaignChannel;
import com.sayohno.marketing.campaign.dao.entity.CampaignClaimIntent;
import com.sayohno.marketing.campaign.dao.entity.CampaignConversion;
import com.sayohno.marketing.campaign.dao.entity.CampaignOffer;
import com.sayohno.marketing.campaign.dao.entity.CampaignReservation;
import com.sayohno.marketing.campaign.dao.entity.CampaignVisit;
import com.sayohno.marketing.campaign.dao.mapper.CampaignChannelMapper;
import com.sayohno.marketing.campaign.dao.mapper.CampaignClaimIntentMapper;
import com.sayohno.marketing.campaign.dao.mapper.CampaignConversionMapper;
import com.sayohno.marketing.campaign.dao.mapper.CampaignOfferMapper;
import com.sayohno.marketing.campaign.dao.mapper.CampaignReservationMapper;
import com.sayohno.marketing.campaign.dao.mapper.CampaignVisitMapper;
import com.sayohno.marketing.common.config.MarketingProperties;
import com.sayohno.marketing.common.exception.BizException;
import com.sayohno.marketing.common.hash.RequestHashes;
import com.sayohno.marketing.common.outbox.OutboxStore;
import com.sayohno.marketing.common.result.ErrorCode;
import com.sayohno.marketing.coupon.dao.entity.OfferInventory;
import com.sayohno.marketing.coupon.dao.entity.IssuedVoucher;
import com.sayohno.marketing.coupon.enums.InventoryStatus;
import com.sayohno.marketing.coupon.enums.IssuerScope;
import com.sayohno.marketing.coupon.service.OfferInventoryService;
import com.sayohno.marketing.coupon.service.VoucherClaimService;
import com.sayohno.marketing.coupon.service.OfferPlanService;
import com.sayohno.marketing.identity.service.MerchantAccessService;
import com.sayohno.marketing.shortlink.dao.entity.Link;
import com.sayohno.marketing.shortlink.dao.entity.LinkGroup;
import com.sayohno.marketing.shortlink.dao.mapper.LinkAccessLogMapper;
import com.sayohno.marketing.shortlink.service.LinkDirectoryService;
import com.sayohno.marketing.shortlink.service.PublicLinkAddress;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class CampaignFlowService {
    public static final String CONVERSION_EVENT = "campaign.conversion";
    private static final char[] ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz".toCharArray();
    private final CampaignService campaignService;
    private final CampaignChannelMapper channelMapper;
    private final CampaignVisitMapper visitMapper;
    private final CampaignReservationMapper reservationMapper;
    private final CampaignOfferMapper couponMapper;
    private final CampaignClaimIntentMapper intentMapper;
    private final CampaignConversionMapper conversionMapper;
    private final LinkDirectoryService linkDirectoryService;
    private final LinkAccessLogMapper linkAccessLogMapper;
    private final VoucherClaimService claimService;
    private final OfferInventoryService allocationService;
    private final MerchantAccessService merchantAccessService;
    private final MarketingProperties properties;
    private final OutboxStore outboxStore;
    private final Clock clock;
    private final OfferPlanService templateService;
    private final CampaignRateLimitService rateLimits;
    private final CampaignPageCache pageCache;
    private final TransactionTemplate transactionTemplate;

    public CampaignFlowService(CampaignService campaignService,
                               CampaignChannelMapper channelMapper,
                               CampaignVisitMapper visitMapper,
                               CampaignReservationMapper reservationMapper,
                               CampaignOfferMapper couponMapper,
                               CampaignClaimIntentMapper intentMapper,
                               CampaignConversionMapper conversionMapper,
                               LinkDirectoryService linkDirectoryService,
                               LinkAccessLogMapper linkAccessLogMapper,
                               VoucherClaimService claimService,
                               OfferInventoryService allocationService,
                               MerchantAccessService merchantAccessService,
                               MarketingProperties properties,
                               OutboxStore outboxStore,
                               Clock clock, OfferPlanService templateService, CampaignRateLimitService rateLimits, CampaignPageCache pageCache,
                               PlatformTransactionManager transactionManager) {
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.campaignService = campaignService;
        this.channelMapper = channelMapper;
        this.visitMapper = visitMapper;
        this.reservationMapper = reservationMapper;
        this.couponMapper = couponMapper;
        this.intentMapper = intentMapper;
        this.conversionMapper = conversionMapper;
        this.linkDirectoryService = linkDirectoryService;
        this.linkAccessLogMapper = linkAccessLogMapper;
        this.claimService = claimService;
        this.allocationService = allocationService;
        this.merchantAccessService = merchantAccessService;
        this.properties = properties;
        this.outboxStore = outboxStore;
        this.clock = clock;
        this.templateService = templateService;
        this.rateLimits = rateLimits;
        this.pageCache = pageCache;
    }

    @Transactional
    public CampaignChannel addChannel(long activityId, String name, String idempotencyKey) {
        return addChannel(activityId, name, idempotencyKey, null);
    }

    @Transactional
    public CampaignChannel addChannel(long activityId, String name, String idempotencyKey, String customCode) {
        CampaignActivity activity = campaignService.requireOwned(activityId);
        if (customCode != null && !customCode.matches("[A-Za-z0-9]{2,12}")) throw new BizException(ErrorCode.VALIDATION, "渠道标识须为2到12位字母或数字");
        if (name == null || name.isBlank() || idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new BizException(ErrorCode.VALIDATION, "渠道名称和幂等键不能为空");
        }
        CampaignChannel existing = channelMapper.selectOne(Wrappers.<CampaignChannel>lambdaQuery()
                .eq(CampaignChannel::getActivityId, activityId)
                .eq(CampaignChannel::getIdempotencyKey, idempotencyKey));
        if (existing != null) {
            if (!name.trim().equals(existing.getName()) || (customCode != null && !customCode.equals(existing.getChannelCode()))) {
                throw new BizException(ErrorCode.CONFLICT, "同一幂等键的渠道参数不一致");
            }
            if (existing.getLinkId() == null) {
                existing.setLinkId(ensureMain(activity).getId());
                channelMapper.updateById(existing);
            }
            return existing;
        }
        CampaignChannel channel = new CampaignChannel();
        channel.setActivityId(activityId);
        channel.setMerchantId(activity.getMerchantId());
        channel.setChannelCode(customCode == null ? randomCode(8) : customCode);
        channel.setName(name.trim());
        channel.setIdempotencyKey(idempotencyKey.trim());
        channel.setStatus(1);
        try { channelMapper.insert(channel); }
        catch (DataIntegrityViolationException exception) { throw new BizException(ErrorCode.CONFLICT, "渠道标识或创建请求已被占用，请检查后重试"); }
        channel.setLinkId(ensureMain(activity).getId());
        channelMapper.updateById(channel);
        pageCache.evict(activityId);
        return channel;
    }

    @Transactional
    public CampaignOffer place(long activityId, int quantity, int maxClaimsPerUser, String idempotencyKey, BigDecimal budget) {
        CampaignActivity activity = campaignService.requireOwned(activityId);
        if (phaseEnded(activity)) {
            throw new BizException(ErrorCode.CONFLICT, "活动已结束或已下线");
        }
        if (budget != null) {
            campaignService.saveBudget(activityId, budget);
        }
        CampaignOffer coupon = couponMapper.selectOne(Wrappers.<CampaignOffer>lambdaQuery()
                .eq(CampaignOffer::getActivityId, activityId));
        if (coupon == null) {
            throw new BizException(ErrorCode.VALIDATION, "先选择一张券");
        }
        boolean beforeStart = activity.getStatus() == ActivityStatuses.DRAFT
                || LocalDateTime.now(clock).isBefore(activity.getStartTime());
        if (coupon.getInventoryId() == null) {
            if (!beforeStart) {
                throw new BizException(ErrorCode.CONFLICT, "开场后不能新建投放");
            }
            OfferInventory allocation = allocationService.allocate(coupon.getPlanId(), quantity, maxClaimsPerUser, idempotencyKey);
            coupon.setInventoryId(allocation.getId());
            couponMapper.updateById(coupon);
            pageCache.evict(activityId);
            return coupon;
        }
        allocationService.adjust(coupon.getInventoryId(), quantity, maxClaimsPerUser, idempotencyKey, beforeStart, beforeStart);
        pageCache.evict(activityId);
        return couponMapper.selectById(coupon.getId());
    }

    public CampaignChannel setChannelEnabled(long activityId, long channelId, boolean enabled) {
        campaignService.requireOwned(activityId);
        CampaignChannel channel = channelMapper.selectById(channelId);
        if (channel == null || !channel.getActivityId().equals(activityId)) {
            throw new BizException(ErrorCode.NOT_FOUND, "渠道不存在");
        }
        channel.setStatus(enabled ? 1 : 0);
        channelMapper.updateById(channel);
        pageCache.evict(activityId);
        return channel;
    }

    public List<CampaignChannel> channels(long activityId) {
        campaignService.requireOwned(activityId);
        return channelMapper.selectList(Wrappers.<CampaignChannel>lambdaQuery()
                .eq(CampaignChannel::getActivityId, activityId)
                .orderByAsc(CampaignChannel::getId));
    }

    /**
     * 批量查询并组装活动列表与统计字段，将原先按活动数线性增长的 N+1 查询收敛为固定常数次批量查询。
     */
    public List<Map<String, Object>> listActivitiesSummary() {
        List<CampaignActivity> activities = campaignService.list();
        if (activities.isEmpty()) {
            return List.of();
        }
        List<Long> activityIds = activities.stream().map(CampaignActivity::getId).toList();
        Map<Long, List<CampaignChannel>> channelsByActivity = channelMapper.selectList(Wrappers.<CampaignChannel>lambdaQuery()
                        .in(CampaignChannel::getActivityId, activityIds))
                .stream().collect(java.util.stream.Collectors.groupingBy(CampaignChannel::getActivityId));
        Map<Long, Long> reservationCounts = toCountMap(reservationMapper.countByActivityBatch(activityIds, ActivityStatuses.RESERVED));
        Map<Long, Long> visitCounts = toCountMap(visitMapper.uniqueActivityVisitorsBatch(activityIds));
        Map<Long, Long> claimCounts = toCountMap(conversionMapper.countByTypeBatch(activityIds, "CLAIM"));

        List<Map<String, Object>> rows = campaignService.presentBatch(activities, reservationCounts);
        for (Map<String, Object> row : rows) {
            long id = ((Number) row.get("id")).longValue();
            List<CampaignChannel> channels = channelsByActivity.getOrDefault(id, List.of());
            long enabledChannels = channels.stream().filter(ch -> ch.getStatus() != null && ch.getStatus() == 1).count();
            boolean mainOn = Boolean.TRUE.equals(row.get("mainEnabled"));
            row.put("channelCount", channels.size());
            row.put("enabledChannels", enabledChannels);
            row.put("entryCount", mainOn ? 1 + enabledChannels : 0);
            row.put("visits", visitCounts.getOrDefault(id, 0L));
            row.put("claims", claimCounts.getOrDefault(id, 0L));
            row.put("reservations", reservationCounts.getOrDefault(id, 0L));
        }
        return rows;
    }

    private static Map<Long, Long> toCountMap(List<com.sayohno.marketing.campaign.dao.mapper.ActivityCountRow> rows) {
        Map<Long, Long> result = new LinkedHashMap<>();
        for (var row : rows) {
            if (row.getActivityId() != null && row.getCnt() != null) {
                result.put(row.getActivityId(), row.getCnt());
            }
        }
        return result;
    }

    public Map<String, Object> visit(long activityId, String channelCode, String visitorId) {
        return visit(activityId, channelCode, visitorId, true);
    }

    /**
     * 落地页访问与状态轮询：优先从 CampaignPageCache 读取活动、主链、渠道与券展示快照，
     * /status（recordVisit=false）在缓存命中时不穿透查询数据库。
     */
    public Map<String, Object> visit(long activityId, String channelCode, String visitorId, boolean recordVisit) {
        CampaignPageCache.PageSnapshot snapshot = pageCache.getOrLoad(activityId, () -> loadPageSnapshot(activityId));
        if (snapshot.mainLinkId() == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "活动还没有主链");
        }
        if (snapshot.mainLinkEnableStatus() == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "主链不存在");
        }
        return renderFromSnapshot(snapshot, channelCode, visitorId, recordVisit);
    }

    public Map<String, Object> warmPageCache(long activityId) {
        campaignService.requireOwned(activityId);
        return pageCache.warm(activityId, () -> loadPageSnapshot(activityId));
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
                activity.getId(), activity.getMerchantId(), activity.getName(),
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
        String blocked = snapshotBlock(snapshot, phase, channelCode);
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
            Integer liveRemaining = pageCache.readLiveRemaining(snapshot.inventoryId());
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
        // 草稿活动未发布，不通过公开接口暴露名称、券规则与投放库存等内部配置（P2-5）。
        if (phase == ActivityPhase.DRAFT) {
            return body;
        }
        body.put("name", snapshot.name());
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

    private static String snapshotBlock(CampaignPageCache.PageSnapshot snapshot, ActivityPhase phase, String channelCode) {
        if (channelCode != null && !channelCode.isBlank() && !channelCode.matches("[A-Za-z0-9]{1,32}")) {
            return "渠道参数不合法";
        }
        boolean mainOff = snapshot.mainLinkId() != null && (snapshot.mainEnabled() == null || snapshot.mainEnabled() != 1
                || snapshot.mainLinkEnableStatus() == null || snapshot.mainLinkEnableStatus() != 0);
        if (mainOff) return "主链已停用";
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

    public Map<String, Object> mainLink(long activityId) {
        CampaignActivity activity = campaignService.requireOwned(activityId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("enabled", activity.getMainEnabled() != null && activity.getMainEnabled() == 1);
        if (activity.getMainLinkId() == null) {
            body.put("shortUri", "");
            body.put("fullShortUrl", "");
            body.put("originUrl", "");
            return body;
        }
        Link link = linkDirectoryService.findOwned(activity.getMainLinkId());
        body.put("id", link.getId());
        body.put("shortUri", link.getShortUri());
        body.put("fullShortUrl", link.getFullShortUrl());
        body.put("originUrl", link.getOriginUrl());
        return body;
    }

    public Map<String, Object> entry(String shortUri, String channelCode, String visitorId) {
        return entry(shortUri, channelCode, visitorId, true);
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

    public Map<String, Object> personalState(long activityId, long userId) {
        CampaignActivity activity = campaignService.requireReadable(activityId);
        boolean reserved = reservationMapper.selectCount(Wrappers.<CampaignReservation>lambdaQuery()
                .eq(CampaignReservation::getActivityId, activityId)
                .eq(CampaignReservation::getUserId, userId)
                .eq(CampaignReservation::getStatus, ActivityStatuses.RESERVED)) > 0;
        CampaignOffer linked = couponMapper.selectOne(Wrappers.<CampaignOffer>lambdaQuery()
                .eq(CampaignOffer::getActivityId, activityId));
        // 按本场投放统计已发出的券；核销、退款不会恢复领取次数，也不受渠道切换影响。
        long claimed = linked == null || linked.getInventoryId() == null ? 0
                : claimService.receivedCount(userId, linked.getInventoryId());
        String eligibilityReason = eligibilityFailure(activity, userId);
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("personalReserved", reserved);
        state.put("personalClaimed", claimed);
        state.put("eligible", eligibilityReason == null);
        state.put("eligibilityReason", eligibilityReason == null ? "" : eligibilityReason);
        return state;
    }

    private String eligibilityFailure(CampaignActivity activity, long userId) {
        String type = activity.getActivityType();
        if ("新客领取活动".equals(type) && claimService.hasMerchantVoucher(userId, activity.getMerchantId())) {
            return "本活动仅限新用户参与";
        }
        if ("会员专享活动".equals(type) && !claimService.hasRedeemedMerchantVoucher(userId, activity.getMerchantId())) {
            return "本活动仅限已有核销记录的会员参与";
        }
        return null;
    }

    /**
     * 活动领券入口。
     * 不加外层事务：领券服务内部会先做 Redis 预扣，再用独立事务落库并提交，最后才释放预扣标记。
     * 若在此处包一层大事务，Redis 操作会早于数据库提交，后续步骤失败回滚时会造成 Redis 已扣、数据库未发券，
     * 且领取请求记录一并回滚，补偿任务无法找回。发券成功后的归因记录单独成事务，失败时用原请求号重试即可补齐。
     */
    public IssuedVoucher claim(long userId, long activityId, long templateId, String requestId, String credential) {
        PreparedClaim prepared = prepareClaim(userId, activityId, templateId, requestId, credential);
        IssuedVoucher coupon = claimService.claimAllocation(userId, templateId, prepared.inventoryId(), IssuerScope.MERCHANT, requestId);
        // 券已提交后再写归因；两步都按券编号幂等，重试同一请求号会补齐缺失的记录。
        persistClaimAttribution(prepared.intent(), activityId, prepared.visit(), userId, coupon.getId());
        return coupon;
    }

    /**
     * 可靠异步领券：先记录归因意向与 claim_ticket，Redis Lua 预扣成功后在同一事务内标记 RESERVED 并写入 Outbox，
     * 由 Outbox/MQ 消费者幂等落库发券；即使进程崩溃或重启，租约到期后也会由恢复任务兜底完成或补偿。
     */
    public Map<String, Object> claimAsync(long userId, long activityId, long templateId, String requestId, String credential) {
        PreparedClaim prepared = prepareClaim(userId, activityId, templateId, requestId, credential);
        VoucherClaimService.ClaimReceipt receipt = claimService.claimAllocationAsync(
                userId, templateId, prepared.inventoryId(), IssuerScope.MERCHANT, requestId);
        if ("SUCCESS".equals(receipt.status()) && receipt.voucherId() != null) {
            persistClaimAttribution(prepared.intent(), activityId, prepared.visit(), userId, receipt.voucherId());
        }
        return ticketView(receipt);
    }

    /**
     * 查询异步领券处理结果：直接读取数据库 claim_ticket，不依赖进程内状态，且不受活动暂停或结束阶段拦截。
     */
    public Map<String, Object> claimStatus(long userId, long activityId, long templateId, String requestId) {
        campaignService.requireReadable(activityId);
        CampaignOffer linked = couponMapper.selectOne(Wrappers.<CampaignOffer>lambdaQuery()
                .eq(CampaignOffer::getActivityId, activityId)
                .eq(CampaignOffer::getPlanId, templateId));
        if (linked == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "这张券不属于这场活动");
        }
        if (linked.getInventoryId() == null) {
            throw new BizException(ErrorCode.VALIDATION, "这场活动还没有投放");
        }
        VoucherClaimService.ClaimReceipt ticket = claimService.findClaimTicket(userId, templateId, linked.getInventoryId(), requestId);
        if ("SUCCESS".equals(ticket.status()) && ticket.voucherId() != null) {
            completeClaimAttribution(ticket.voucherId());
        }
        return ticketView(ticket);
    }

    /**
     * 异步消费 coupon.claim 事件或查单时，幂等补齐领取意向回填与活动转化归因。
     */
    public void completeClaimAttribution(long voucherId) {
        var req = claimService.findRequestByUserCouponId(voucherId);
        if (req == null || req.getInventoryId() == null || req.getInventoryId() <= 0) {
            return;
        }
        CampaignClaimIntent intent = intentMapper.selectOne(Wrappers.<CampaignClaimIntent>lambdaQuery()
                .eq(CampaignClaimIntent::getUserId, req.getUserId())
                .eq(CampaignClaimIntent::getPlanId, req.getPlanId())
                .eq(CampaignClaimIntent::getInventoryId, req.getInventoryId())
                .eq(CampaignClaimIntent::getRequestId, req.getRequestId()));
        if (intent == null) {
            return;
        }
        transactionTemplate.executeWithoutResult(status -> {
            if (intent.getVoucherId() == null) {
                intent.setVoucherId(voucherId);
                intentMapper.updateById(intent);
            }
            recordConversion("claim:" + voucherId, "CLAIM", intent.getActivityId(),
                    intent.getChannelId(), intent.getVisitId(), intent.getUserId(), voucherId, null, LocalDateTime.now(clock));
        });
        pageCache.evict(intent.getActivityId());
    }

    private PreparedClaim prepareClaim(long userId, long activityId, long templateId, String requestId, String credential) {
        CampaignVisit visit = requireVisit(activityId, credential, userId);
        // 限流放在活动、渠道、投放等查询和领取意向写入之前，超额请求不再打到数据库。
        rateLimits.admit(activityId, visit.getChannelId(), userId);
        CampaignActivity activity = campaignService.requireReadable(activityId);
        ActivityPhase phase = campaignService.phase(activity);
        if (!phase.claimOpen()) {
            throw new BizException(ErrorCode.CONFLICT, "活动未开场、已下线、已结束或已暂停，不能领取");
        }
        if (activity.getMainLinkId() != null && (activity.getMainEnabled() == null || activity.getMainEnabled() != 1)) {
            throw new BizException(ErrorCode.CONFLICT, "主链已停用");
        }
        if (visit.getChannelId() != null) {
            CampaignChannel channel = channelMapper.selectById(visit.getChannelId());
            if (channel == null || channel.getStatus() == null || channel.getStatus() != 1) {
                throw new BizException(ErrorCode.CONFLICT, "渠道已停用");
            }
        }
        CampaignOffer linked = couponMapper.selectOne(Wrappers.<CampaignOffer>lambdaQuery()
                .eq(CampaignOffer::getActivityId, activityId)
                .eq(CampaignOffer::getPlanId, templateId));
        if (linked == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "这张券不属于这场活动");
        }
        if (linked.getInventoryId() == null) {
            throw new BizException(ErrorCode.VALIDATION, "这场活动还没有投放");
        }
        String eligibilityReason = eligibilityFailure(activity, userId);
        if (eligibilityReason != null) {
            throw new BizException(ErrorCode.VALIDATION, eligibilityReason);
        }
        CampaignClaimIntent intent = rememberIntent(activityId, linked.getInventoryId(), visit, userId, templateId, requestId);
        return new PreparedClaim(visit, linked.getInventoryId(), intent);
    }

    private void persistClaimAttribution(CampaignClaimIntent intent, long activityId, CampaignVisit visit, long userId, long voucherId) {
        transactionTemplate.executeWithoutResult(status -> {
            if (intent.getVoucherId() == null) {
                intent.setVoucherId(voucherId);
                intentMapper.updateById(intent);
            }
            recordConversion("claim:" + voucherId, "CLAIM", activityId, visit.getChannelId(), visit.getId(),
                    userId, voucherId, null, LocalDateTime.now(clock));
        });
        pageCache.evict(activityId);
    }

    private static Map<String, Object> ticketView(VoucherClaimService.ClaimReceipt ticket) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", ticket.requestId());
        body.put("status", ticket.status());
        body.put("voucherId", ticket.voucherId());
        body.put("coupon", ticket.coupon());
        return body;
    }

    private record PreparedClaim(CampaignVisit visit, long inventoryId, CampaignClaimIntent intent) {
    }

    public List<Map<String, Object>> report(long activityId) {
        CampaignActivity activity = campaignService.requireOwned(activityId);
        List<CampaignChannel> channels = channelMapper.selectList(Wrappers.<CampaignChannel>lambdaQuery()
                .eq(CampaignChannel::getActivityId, activityId));
        Long mainLinkId = activity.getMainLinkId();
        List<Map<String, Object>> rows = new java.util.ArrayList<>(channels.stream()
                .map(channel -> channelStats(activityId, mainLinkId, channel.getId(), channel.getChannelCode(), channel.getName(), "CHANNEL")).toList());
        rows.add(channelStats(activityId, mainLinkId, null, null, "直接访问", "DIRECT"));
        return rows;
    }

    public long attributedClaims(long activityId) {
        campaignService.requireOwned(activityId);
        return countActivity(activityId, "CLAIM");
    }

    public long effectiveVisitors(long activityId) {
        campaignService.requireOwned(activityId);
        return visitMapper.uniqueActivityVisitors(activityId);
    }

    public long unreservedActiveVisitors(long activityId, List<Long> activeChannelIds) {
        campaignService.requireOwned(activityId);
        return visitMapper.unreservedActiveVisitors(activityId, activeChannelIds);
    }

    @Transactional
    public Map<String, Object> openMain(long activityId) {
        CampaignActivity activity = campaignService.requireOwned(activityId);
        ensureMain(activity);
        return mainLink(activityId);
    }

    @Transactional
    public CampaignOffer saveOffer(long activityId, Long templateId, int quantity, int maxClaimsPerUser,
                                    String idempotencyKey, BigDecimal budget, Integer version) {
        CampaignActivity activity = campaignService.requireOwned(activityId);
        if (version != null && !version.equals(activity.getVersion())) {
            throw new BizException(ErrorCode.CONFLICT, "活动已更新，请刷新后再保存");
        }
        if (phaseEnded(activity)) {
            throw new BizException(ErrorCode.CONFLICT, "活动已结束或已终止");
        }
        if (templateId == null) {
            throw new BizException(ErrorCode.VALIDATION, "先选择一张券");
        }
        campaignService.assertBudget(templateId, quantity, budget);
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
        CampaignActivity created = campaignService.create(name, source.getWarmupStartTime(), source.getStartTime(), source.getEndTime(), source.getActivityType(), source.getBudgetAmount());
        CampaignOffer coupon = couponMapper.selectOne(Wrappers.<CampaignOffer>lambdaQuery()
                .eq(CampaignOffer::getActivityId, activityId));
        if (coupon != null) {
            campaignService.attachCoupon(created.getId(), coupon.getPlanId());
        }
        for (CampaignChannel channel : channels(activityId)) {
            addChannel(created.getId(), channel.getName(), "copy-" + created.getId() + "-" + channel.getId());
        }
        openMain(created.getId());
        return created.getId();
    }

    public Map<String, Object> dashboard(long activityId) {
        return board(activityId, "dashboard");
    }

    public Map<String, Object> review(long activityId) {
        Map<String, Object> body = board(activityId, "review");
        body.put("redeemRateNote", "核销率的分子是核销笔数，分母是本场已领取张数。退款不恢复本场可领库存。");
        return body;
    }

    public void recordFromCouponEvent(String eventType, long voucherId, Long settlementId) {
        CampaignClaimIntent intent = intentMapper.selectOne(Wrappers.<CampaignClaimIntent>lambdaQuery()
                .eq(CampaignClaimIntent::getVoucherId, voucherId));
        if (intent == null) {
            return;
        }
        String type = "coupon.redeem".equals(eventType) ? "REDEEM" : "REFUND";
        recordConversion(type.toLowerCase() + ":" + (settlementId == null ? voucherId : settlementId),
                type, intent.getActivityId(), intent.getChannelId(), intent.getVisitId(),
                intent.getUserId(), voucherId, settlementId, LocalDateTime.now(clock));
    }

    private void recordConversion(String eventId, String type, long activityId, Long channelId, Long visitId,
                                  long userId, Long voucherId, Long settlementId, LocalDateTime occurredAt) {
        if (conversionMapper.selectCount(Wrappers.<CampaignConversion>lambdaQuery()
                .eq(CampaignConversion::getEventId, eventId)) > 0) {
            return;
        }
        CampaignConversion conversion = new CampaignConversion();
        conversion.setEventId(eventId);
        conversion.setActivityId(activityId);
        conversion.setChannelId(channelId);
        conversion.setVisitId(visitId);
        conversion.setUserId(userId);
        conversion.setVoucherId(voucherId);
        conversion.setSettlementId(settlementId);
        conversion.setEventType(type);
        conversion.setOccurredAt(occurredAt);
        try {
            conversionMapper.insert(conversion);
        } catch (DataIntegrityViolationException ignored) {
            return;
        }
        outboxStore.append("campaign:" + eventId, CONVERSION_EVENT, String.valueOf(activityId),
                "{\"eventType\":\"" + type + "\",\"voucherId\":" + voucherId + "}");
    }

    private Map<String, Object> board(long activityId, String kind) {
        CampaignActivity activity = campaignService.requireOwned(activityId);
        Map<String, Object> summary = campaignService.present(activity);
        List<Map<String, Object>> channels = report(activityId);
        long attributed = channels.stream().mapToLong(item -> ((Number) item.get("claims")).longValue()).sum();
        Object claimed = summary.get("claimed");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("kind", kind);
        body.put("generatedAt", LocalDateTime.now(clock).toString());
        body.put("timezone", "Asia/Shanghai");
        body.put("activity", summary);
        body.put("channels", channels);
        body.put("attributedClaims", attributed);
        body.put("reconciled", claimed instanceof Number number && number.longValue() == attributed);
        addRefundSummary(body, activityId, null, false);
        long clicks = activity.getMainLinkId() == null ? 0L : linkAccessLogMapper.uniqueLinkClicks(activity.getMainLinkId());
        long visits = visitMapper.uniqueActivityVisitors(activityId);
        long reservations = activeReservations(activityId);
        long redeems = countActivity(activityId, "REDEEM");
        long refunds = countActivity(activityId, "REFUND");
        body.put("clicks", clicks);
        body.put("visits", visits);
        body.put("funnel", Map.of(
                "clicks", clicks,
                "visits", visits,
                "reservations", reservations,
                "claims", attributed,
                "redeems", redeems,
                "refunds", refunds));
        body.put("visitDefinition", "短链点击为 302 跳转去重访客，落地页到达为有效入口去重访客；直接打开落地页不经过短链时，到达可能大于点击。各渠道独立去重，不直接相加作为活动总数。");
        if (claimed instanceof Number number && number.longValue() > 0) {
            body.put("redeemRate", BigDecimal.valueOf(redeems).divide(BigDecimal.valueOf(number.longValue()), 4, java.math.RoundingMode.HALF_UP));
        } else {
            body.put("redeemRate", null);
        }
        body.put("redeemRateNote", "分子是核销笔数，分母是本场已领取张数，截止时间为 generatedAt。");
        return body;
    }

    private Map<String, Object> channelStats(long activityId, Long mainLinkId, Long channelId, String channelCode, String name, String kind) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("channelId", channelId);
        row.put("name", name);
        row.put("kind", kind);
        row.put("enabled", channelId == null || channelMapper.selectById(channelId).getStatus() == 1);
        row.put("clicks", mainLinkId == null ? 0L : linkAccessLogMapper.uniqueClicks(mainLinkId, channelCode));
        row.put("visits", visitMapper.uniqueVisitors(activityId, channelId));
        row.put("claims", countMatched(activityId, channelId, "CLAIM"));
        row.put("redeems", countMatched(activityId, channelId, "REDEEM"));
        row.put("refunds", countMatched(activityId, channelId, "REFUND"));
        addRefundSummary(row, activityId, channelId, true);
        var reservations = Wrappers.<CampaignReservation>lambdaQuery()
                .eq(CampaignReservation::getActivityId, activityId)
                .eq(CampaignReservation::getStatus, ActivityStatuses.RESERVED);
        if (channelId == null) {
            reservations.isNull(CampaignReservation::getChannelId);
        } else {
            reservations.eq(CampaignReservation::getChannelId, channelId);
        }
        row.put("reservations", reservationMapper.selectCount(reservations));
        return row;
    }

    private void addRefundSummary(Map<String, Object> target, long activityId, Long channelId, boolean byChannel) {
        var refund = conversionMapper.refunds(activityId, channelId, byChannel);
        long missing = refund.getRefundCount() - refund.getAmountCount();
        target.put("refundAmount", missing == 0 ? refund.getKnownAmount() : null);
        target.put("knownRefundAmount", refund.getKnownAmount());
        target.put("unreportedRefunds", missing);
        target.put("refundAmountStatus", missing == 0 ? "COMPLETE" : "PARTIAL");
    }

    private long countActivity(long activityId, String type) {
        Long count = conversionMapper.selectCount(Wrappers.<CampaignConversion>lambdaQuery()
                .eq(CampaignConversion::getActivityId, activityId)
                .eq(CampaignConversion::getEventType, type));
        return count == null ? 0 : count;
    }

    private long countMatched(long activityId, Long channelId, String type) {
        var query = Wrappers.<CampaignConversion>lambdaQuery()
                .eq(CampaignConversion::getActivityId, activityId)
                .eq(CampaignConversion::getEventType, type);
        if (channelId == null) {
            query.isNull(CampaignConversion::getChannelId);
        } else {
            query.eq(CampaignConversion::getChannelId, channelId);
        }
        Long count = conversionMapper.selectCount(query);
        return count == null ? 0 : count;
    }

    private long countType(long activityId, long channelId, String type) {
        Long count = conversionMapper.selectCount(Wrappers.<CampaignConversion>lambdaQuery()
                .eq(CampaignConversion::getActivityId, activityId)
                .eq(CampaignConversion::getChannelId, channelId)
                .eq(CampaignConversion::getEventType, type));
        return count == null ? 0 : count;
    }

    private CampaignClaimIntent rememberIntent(long activityId, long inventoryId, CampaignVisit visit, long userId, long templateId, String requestId) {
        CampaignClaimIntent existing = intentMapper.selectOne(Wrappers.<CampaignClaimIntent>lambdaQuery()
                .eq(CampaignClaimIntent::getUserId, userId)
                .eq(CampaignClaimIntent::getPlanId, templateId)
                .eq(CampaignClaimIntent::getInventoryId, inventoryId)
                .eq(CampaignClaimIntent::getRequestId, requestId));
        if (existing != null) {
            if (!existing.getActivityId().equals(activityId) || !Objects.equals(existing.getChannelId(), visit.getChannelId())) {
                throw new BizException(ErrorCode.CONFLICT, "同一领取请求不能改到别的渠道");
            }
            return existing;
        }
        CampaignClaimIntent intent = new CampaignClaimIntent();
        intent.setActivityId(activityId);
        intent.setInventoryId(inventoryId);
        intent.setChannelId(visit.getChannelId());
        intent.setVisitId(visit.getId());
        intent.setUserId(userId);
        intent.setPlanId(templateId);
        intent.setRequestId(requestId);
        try {
            intentMapper.insert(intent);
        } catch (DataIntegrityViolationException exception) {
            // 无外层事务后，同一请求号并发提交可能撞唯一键，改为读取先写入的那条再校验渠道。
            CampaignClaimIntent raced = intentMapper.selectOne(Wrappers.<CampaignClaimIntent>lambdaQuery()
                    .eq(CampaignClaimIntent::getUserId, userId)
                    .eq(CampaignClaimIntent::getPlanId, templateId)
                    .eq(CampaignClaimIntent::getInventoryId, inventoryId)
                    .eq(CampaignClaimIntent::getRequestId, requestId));
            if (raced == null || !raced.getActivityId().equals(activityId)
                    || !Objects.equals(raced.getChannelId(), visit.getChannelId())) {
                throw new BizException(ErrorCode.CONFLICT, "同一领取请求不能改到别的渠道");
            }
            return raced;
        }
        return intent;
    }

    @Transactional
    public void identifyVisitor(long activityId, String credential, long userId) {
        requireVisit(activityId, credential, userId);
    }

    private CampaignVisit requireVisit(long activityId, String credential, long userId) {
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

    private Link ensureMain(CampaignActivity activity) {
        if (activity.getMainLinkId() != null) {
            return linkDirectoryService.findOwned(activity.getMainLinkId());
        }
        LinkGroup group = linkDirectoryService.listGroups().stream()
                .filter(item -> "活动渠道".equals(item.getName()))
                .findFirst()
                .orElseGet(() -> linkDirectoryService.createGroup("活动渠道"));
        String origin = PublicLinkAddress.base(properties.getShortlink().getPublicBaseUrl(), false, null, null)
                + "/a/" + activity.getId();
        Link link = linkDirectoryService.createLink(group.getGid(), origin, activity.getEndTime(),
                "act:" + activity.getId() + ":main", null, null);
        campaignService.bindMainLink(activity.getId(), link.getId());
        activity.setMainLinkId(link.getId());
        activity.setMainEnabled(1);
        return link;
    }

    private boolean phaseEnded(CampaignActivity activity) {
        ActivityPhase phase = campaignService.phase(activity);
        return phase == ActivityPhase.ENDED || phase == ActivityPhase.CANCELLED;
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
        if (phase == ActivityPhase.OFFLINE) {
            return "活动已下线";
        }
        if (phase == ActivityPhase.CANCELLED) {
            return "活动已终止";
        }
        if (phase == ActivityPhase.PAUSED) {
            return "活动已暂停";
        }
        if (phase == ActivityPhase.DRAFT) {
            return "活动还没发布";
        }
        return null;
    }

    private String action(CampaignActivity activity, String blocked) {
        if (blocked != null) {
            return "UNAVAILABLE";
        }
        ActivityPhase phase = campaignService.phase(activity);
        if (phase == ActivityPhase.ENDED) {
            return "ENDED";
        }
        if (phase == ActivityPhase.WARMUP) {
            return "RESERVE";
        }
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

    private static String randomCode(int size) {
        StringBuilder builder = new StringBuilder();
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = 0; i < size; i++) {
            builder.append(ALPHABET[random.nextInt(ALPHABET.length)]);
        }
        return builder.toString();
    }
}
