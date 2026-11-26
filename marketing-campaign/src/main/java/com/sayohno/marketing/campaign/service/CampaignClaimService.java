package com.sayohno.marketing.campaign.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sayohno.marketing.campaign.ActivityPhase;
import com.sayohno.marketing.campaign.dao.entity.CampaignActivity;
import com.sayohno.marketing.campaign.dao.entity.CampaignChannel;
import com.sayohno.marketing.campaign.dao.entity.CampaignClaimIntent;
import com.sayohno.marketing.campaign.dao.entity.CampaignConversion;
import com.sayohno.marketing.campaign.dao.entity.CampaignOffer;
import com.sayohno.marketing.campaign.dao.entity.CampaignVisit;
import com.sayohno.marketing.campaign.dao.mapper.CampaignChannelMapper;
import com.sayohno.marketing.campaign.dao.mapper.CampaignClaimIntentMapper;
import com.sayohno.marketing.campaign.dao.mapper.CampaignConversionMapper;
import com.sayohno.marketing.campaign.dao.mapper.CampaignOfferMapper;
import com.sayohno.marketing.campaign.web.dto.ClaimTicketView;
import com.sayohno.marketing.common.exception.BizException;
import com.sayohno.marketing.common.outbox.OutboxStore;
import com.sayohno.marketing.common.result.ErrorCode;
import com.sayohno.marketing.coupon.dao.entity.IssuedVoucher;
import com.sayohno.marketing.coupon.enums.IssuerScope;
import com.sayohno.marketing.coupon.service.VoucherClaimService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 活动领券编排、查单与转化归因服务：协调前置限流、凭证校验、同步/异步领券与渠道转化记账。
 */
@Service
public class CampaignClaimService {
    public static final String CONVERSION_EVENT = "campaign.conversion";

    private final CampaignService campaignService;
    private final CampaignEntryService entryService;
    private final CampaignChannelMapper channelMapper;
    private final CampaignOfferMapper couponMapper;
    private final CampaignClaimIntentMapper intentMapper;
    private final CampaignConversionMapper conversionMapper;
    private final VoucherClaimService claimService;
    private final CampaignRateLimitService rateLimits;
    private final OutboxStore outboxStore;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public CampaignClaimService(CampaignService campaignService,
                                CampaignEntryService entryService,
                                CampaignChannelMapper channelMapper,
                                CampaignOfferMapper couponMapper,
                                CampaignClaimIntentMapper intentMapper,
                                CampaignConversionMapper conversionMapper,
                                VoucherClaimService claimService,
                                CampaignRateLimitService rateLimits,
                                OutboxStore outboxStore,
                                PlatformTransactionManager transactionManager,
                                Clock clock) {
        this.campaignService = campaignService;
        this.entryService = entryService;
        this.channelMapper = channelMapper;
        this.couponMapper = couponMapper;
        this.intentMapper = intentMapper;
        this.conversionMapper = conversionMapper;
        this.claimService = claimService;
        this.rateLimits = rateLimits;
        this.outboxStore = outboxStore;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /**
     * 活动同步领券入口（不加外层事务，避免 Redis 预扣与数据库提交边界错位）。
     */
    public IssuedVoucher claim(long userId, long activityId, long templateId, String requestId, String credential) {
        PreparedClaim prepared = prepareClaim(userId, activityId, templateId, requestId, credential);
        IssuedVoucher coupon = claimService.claimAllocation(userId, templateId, prepared.inventoryId(), IssuerScope.MERCHANT, requestId);
        persistClaimAttribution(prepared.intent(), activityId, prepared.visit(), userId, coupon.getId());
        return coupon;
    }

    /**
     * 可靠异步领券入口：完成限流与归因意向记录后交由 VoucherClaimService 执行 Redis 预扣并落库 Outbox。
     */
    public ClaimTicketView claimAsync(long userId, long activityId, long templateId, String requestId, String credential) {
        PreparedClaim prepared = prepareClaim(userId, activityId, templateId, requestId, credential);
        VoucherClaimService.ClaimReceipt receipt = claimService.claimAllocationAsync(
                userId, templateId, prepared.inventoryId(), IssuerScope.MERCHANT, requestId);
        if ("SUCCESS".equals(receipt.status()) && receipt.voucherId() != null) {
            persistClaimAttribution(prepared.intent(), activityId, prepared.visit(), userId, receipt.voucherId());
        }
        return toView(receipt);
    }

    /**
     * 查询异步领券处理结果：直接读取数据库 claim_ticket，不受活动暂停或结束阶段拦截。
     */
    public ClaimTicketView claimStatus(long userId, long activityId, long templateId, String requestId) {
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
        return toView(ticket);
    }

    /**
     * 异步消费 coupon.claim 事件或查单时，幂等补齐领取意向回填与活动转化归因。
     */
    public void completeClaimAttribution(long voucherId) {
        CampaignClaimIntent intent = findClaimIntent(voucherId);
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
    }

    private CampaignClaimIntent findClaimIntent(long voucherId) {
        var req = claimService.findRequestByUserCouponId(voucherId);
        if (req == null || req.getInventoryId() == null || req.getInventoryId() <= 0) {
            return null;
        }
        return intentMapper.selectOne(Wrappers.<CampaignClaimIntent>lambdaQuery()
                .eq(CampaignClaimIntent::getUserId, req.getUserId())
                .eq(CampaignClaimIntent::getPlanId, req.getPlanId())
                .eq(CampaignClaimIntent::getInventoryId, req.getInventoryId())
                .eq(CampaignClaimIntent::getRequestId, req.getRequestId()));
    }

    public void recordFromCouponEvent(String eventType, long voucherId, Long settlementId) {
        CampaignClaimIntent intent = intentMapper.selectOne(Wrappers.<CampaignClaimIntent>lambdaQuery()
                .eq(CampaignClaimIntent::getVoucherId, voucherId));
        if (intent == null) {
            // 核销、退款事件可能先于领取归因到达，此时按原领取请求定位活动。
            intent = findClaimIntent(voucherId);
            if (intent == null) {
                return;
            }
        }
        String type = "coupon.redeem".equals(eventType) ? "REDEEM" : "REFUND";
        recordConversion(type.toLowerCase() + ":" + (settlementId == null ? voucherId : settlementId),
                type, intent.getActivityId(), intent.getChannelId(), intent.getVisitId(),
                intent.getUserId(), voucherId, settlementId, LocalDateTime.now(clock));
    }

    private PreparedClaim prepareClaim(long userId, long activityId, long templateId, String requestId, String credential) {
        // 第一层限流放在任何数据库读写之前，先以单用户频控拦截恶意脚本，避免压垮 MySQL。
        rateLimits.admitUser(activityId, userId);
        CampaignVisit visit = entryService.requireVisit(activityId, credential, userId);
        // 拿到来源渠道后再校验活动总额度与来源额度（不重复计数单用户桶）。
        rateLimits.admitActivityAndSource(activityId, visit.getChannelId());
        CampaignActivity activity = campaignService.requireReadable(activityId);
        ActivityPhase phase = campaignService.phase(activity);
        if (!phase.claimOpen()) {
            throw new BizException(ErrorCode.CONFLICT, "活动未开场、已下线、已结束或已暂停，不能领取");
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
        String eligibilityReason = entryService.eligibilityFailure(activity, userId);
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

    private static ClaimTicketView toView(VoucherClaimService.ClaimReceipt ticket) {
        return new ClaimTicketView(ticket.requestId(), ticket.status(), ticket.voucherId(), ticket.coupon());
    }

    private record PreparedClaim(CampaignVisit visit, long inventoryId, CampaignClaimIntent intent) {
    }
}
