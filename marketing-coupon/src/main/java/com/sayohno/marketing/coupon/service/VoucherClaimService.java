package com.sayohno.marketing.coupon.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.JsonNode;
import com.sayohno.marketing.common.config.MarketingProperties;
import com.sayohno.marketing.common.exception.BizException;
import com.sayohno.marketing.common.hash.RequestHashes;
import com.sayohno.marketing.common.outbox.OutboxStore;
import com.sayohno.marketing.common.result.ErrorCode;
import com.sayohno.marketing.coupon.dao.entity.OfferInventory;
import com.sayohno.marketing.coupon.dao.entity.ClaimTicket;
import com.sayohno.marketing.coupon.dao.entity.OfferPlan;
import com.sayohno.marketing.coupon.dao.entity.IssuedVoucher;
import com.sayohno.marketing.coupon.dao.mapper.OfferInventoryMapper;
import com.sayohno.marketing.coupon.dao.mapper.ClaimTicketMapper;
import com.sayohno.marketing.coupon.dao.mapper.OfferPlanMapper;
import com.sayohno.marketing.coupon.dao.mapper.IssuedVoucherMapper;
import com.sayohno.marketing.coupon.enums.ClaimTicketStatus;
import com.sayohno.marketing.coupon.enums.InventoryStatus;
import com.sayohno.marketing.coupon.enums.IssuerScope;
import com.sayohno.marketing.coupon.enums.OfferPlanStatus;
import com.sayohno.marketing.coupon.enums.VoucherStatus;
import com.sayohno.marketing.coupon.stock.HoldMark;
import com.sayohno.marketing.coupon.stock.OfferStockGateway;
import com.sayohno.marketing.coupon.stock.StockDecision;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class VoucherClaimService {
    public static final String CLAIM_EVENT = "coupon.claim";
    public static final String ASYNC_CLAIM_EVENT = "coupon.claim.async";
    private final OfferPlanMapper templateMapper;
    private final IssuedVoucherMapper userMapper;
    private final ClaimTicketMapper requestMapper;
    private final OfferInventoryMapper allocationMapper;
    private final OfferStockGateway stockGateway;
    private final OutboxStore outboxStore;
    private final MarketingProperties properties;
    private final TransactionTemplate transactionTemplate;

    public VoucherClaimService(OfferPlanMapper templateMapper,
                              IssuedVoucherMapper userMapper,
                              ClaimTicketMapper requestMapper,
                              OfferInventoryMapper allocationMapper,
                              OfferStockGateway stockGateway,
                              OutboxStore outboxStore,
                              MarketingProperties properties,
                              PlatformTransactionManager transactionManager) {
        this.templateMapper = templateMapper;
        this.userMapper = userMapper;
        this.requestMapper = requestMapper;
        this.allocationMapper = allocationMapper;
        this.stockGateway = stockGateway;
        this.outboxStore = outboxStore;
        this.properties = properties;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public IssuedVoucher claim(long userId, long templateId, IssuerScope source, String requestId) {
        return claim(userId, templateId, 0L, source, requestId);
    }

    public IssuedVoucher claimAllocation(long userId, long templateId, long inventoryId, IssuerScope source, String requestId) {
        if (inventoryId <= 0) {
            throw new BizException(ErrorCode.VALIDATION, "投放标识不能为空");
        }
        return claim(userId, templateId, inventoryId, source, requestId);
    }

    /**
     * 异步秒杀领券：仅执行 Redis Lua 原子预扣并写入异步落库事件，立即返回受理凭证。
     * 实际扣减数据库库存与发券由 Outbox/MQ 消费者幂等完成；若落库失败则自动补偿 Redis 库存。
     */
    public ClaimReceipt claimAllocationAsync(long userId, long templateId, long inventoryId, IssuerScope source, String requestId) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("领券不能在外层事务中调用");
        }
        if (inventoryId <= 0) {
            throw new BizException(ErrorCode.VALIDATION, "投放标识不能为空");
        }
        String trimmedRequestId = validateRequestId(requestId);
        String hash = requestHash(userId, templateId, inventoryId, source);
        ClaimTicket existing = find(userId, templateId, inventoryId, trimmedRequestId);
        if (existing != null && !hash.equals(existing.getRequestHash())) {
            throw new BizException(ErrorCode.CONFLICT, "同一幂等键的参数不一致");
        }
        if (existing != null && existing.getStatus() == ClaimTicketStatus.SUCCEEDED) {
            IssuedVoucher coupon = userMapper.selectById(existing.getVoucherId());
            return new ClaimReceipt(trimmedRequestId, "SUCCESS", existing.getVoucherId(), coupon);
        }
        OfferPlan template = loadOpenTemplate(templateId);
        ClaimTicket request = begin(userId, templateId, inventoryId, trimmedRequestId, hash, source);
        if (request.getStatus() == ClaimTicketStatus.SUCCEEDED) {
            IssuedVoucher coupon = userMapper.selectById(request.getVoucherId());
            return new ClaimReceipt(trimmedRequestId, "SUCCESS", request.getVoucherId(), coupon);
        }
        String token = UUID.randomUUID().toString().replace("-", "");
        if (!lease(request.getId(), token)) {
            ClaimTicket latest = requestMapper.selectById(request.getId());
            if (latest != null && latest.getStatus() == ClaimTicketStatus.SUCCEEDED) {
                IssuedVoucher coupon = userMapper.selectById(latest.getVoucherId());
                return new ClaimReceipt(trimmedRequestId, "SUCCESS", latest.getVoucherId(), coupon);
            }
            if (latest != null && latest.getStatus() == ClaimTicketStatus.RESERVED) {
                return new ClaimReceipt(trimmedRequestId, "RESERVED", null, null);
            }
            throw new BizException(ErrorCode.CONFLICT, "同一请求正在处理，请稍后用原请求号重试");
        }
        ClaimTicket current = requestMapper.selectById(request.getId());
        OfferInventory allocation = requireOpenAllocation(inventoryId, current.getId(), token);
        int[] counts = reserveAllocationStock(template, allocation, current, userId, token, false);
        // 在同一小事务中锁定 RESERVED 状态并写入异步落库事件，保证预扣后不会丢失发券任务。
        transactionTemplate.executeWithoutResult(status -> {
            if (requestMapper.markReserved(current.getId(), token, counts[0], counts[1], LocalDateTime.now()) != 1) {
                stockGateway.compensateAllocation(inventoryId, template.getId(), userId, counts[0], counts[1], current.getId());
                throw new BizException(ErrorCode.CONFLICT, "领取预扣状态已被其他执行者接管");
            }
            outboxStore.append("claim-async:" + current.getId() + ":" + token, ASYNC_CLAIM_EVENT,
                    String.valueOf(current.getId()),
                    "{\"requestRowId\":" + current.getId() + ",\"token\":\"" + token + "\"}");
        });
        return new ClaimReceipt(trimmedRequestId, "RESERVED", null, null);
    }

    /**
     * 消费异步领券事件，完成数据库落库与释放 Redis hold；失败时补偿 Redis 库存并标记 FAILED。
     */
    public void completeAsyncClaim(long requestRowId, String token) {
        ClaimTicket current = requestMapper.selectById(requestRowId);
        if (current == null || current.getStatus() == ClaimTicketStatus.SUCCEEDED || current.getStatus() == ClaimTicketStatus.FAILED) {
            return;
        }
        if (current.getInventoryId() == null || current.getInventoryId() <= 0) {
            return;
        }
        // 若消息延迟超过租约期，尝试重新获取租约以免与 recoverStuck 并发竞争。
        String activeToken = token;
        if (activeToken == null || !activeToken.equals(current.getReservationToken())) {
            activeToken = UUID.randomUUID().toString().replace("-", "");
            if (!lease(requestRowId, activeToken)) {
                return;
            }
            current = requestMapper.selectById(requestRowId);
            if (current == null || current.getStatus() == ClaimTicketStatus.SUCCEEDED || current.getStatus() == ClaimTicketStatus.FAILED) {
                return;
            }
        }
        OfferPlan template = templateMapper.selectById(current.getPlanId());
        if (template == null) {
            compensateAllocationAndFail(requestRowId, current.getInventoryId(), current.getPlanId(),
                    current.getUserId(), current.getClaimSeq(), current.getAllocReceiveCount(), activeToken);
            return;
        }
        Integer templateCount = current.getClaimSeq();
        Integer allocCount = current.getAllocReceiveCount();
        if (templateCount == null || allocCount == null) {
            HoldMark hold = stockGateway.readAllocationHold(current.getInventoryId(), requestRowId);
            if (hold == null) {
                requestMapper.markFailed(requestRowId, activeToken, LocalDateTime.now());
                return;
            }
            templateCount = hold.templateCount();
            allocCount = hold.allocCount();
        }
        try {
            IssuerScope source = current.getIssuerScope() == null ? IssuerScope.MERCHANT : current.getIssuerScope();
            commit(requestRowId, current.getUserId(), template, source, templateCount);
            stockGateway.releaseAllocationHold(current.getInventoryId(), requestRowId);
        } catch (RuntimeException exception) {
            compensateAllocationAndFail(requestRowId, current.getInventoryId(), template.getId(),
                    current.getUserId(), templateCount, allocCount, activeToken);
        }
    }

    public ClaimReceipt findClaimTicket(long userId, long templateId, long inventoryId, String requestId) {
        String trimmed = validateRequestId(requestId);
        ClaimTicket row = find(userId, templateId, inventoryId, trimmed);
        if (row == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "领取请求不存在");
        }
        String status = switch (row.getStatus()) {
            case SUCCEEDED -> "SUCCESS";
            case FAILED -> "FAILED";
            case RESERVED -> "RESERVED";
            case PENDING -> "PENDING";
        };
        IssuedVoucher coupon = row.getStatus() == ClaimTicketStatus.SUCCEEDED && row.getVoucherId() != null
                ? userMapper.selectById(row.getVoucherId())
                : null;
        return new ClaimReceipt(trimmed, status, row.getVoucherId(), coupon);
    }

    /**
     * 纯内存+Redis 预扣接口，热路径零 MySQL 读写；预扣成功后由异步工作线程落库。
     */
    public StockDecision reserveAllocationByKey(long userId, long templateId, long inventoryId,
                                              int allocationLimit, int templateLimit, int remaining,
                                              LocalDateTime usableUntil, String claimKey) {
        long ttl = Math.max(60, Duration.between(LocalDateTime.now(), usableUntil).getSeconds());
        StockDecision decision = stockGateway.tryAcquireAllocationByKey(inventoryId, templateId, userId,
                allocationLimit, templateLimit, remaining, ttl, claimKey);
        if (!decision.ok()) {
            throw stockError(decision);
        }
        return decision;
    }

    public void compensateAllocationByKey(long inventoryId, long templateId, long userId,
                                          int templateCount, int allocCount, String claimKey) {
        stockGateway.compensateAllocationByKey(inventoryId, templateId, userId, templateCount, allocCount, claimKey);
    }

    /**
     * 后台消费线程单事务落库异步领券结果，失败时回滚 Redis 库存并记录 FAILED。
     */
    public IssuedVoucher persistAsyncClaim(long userId, long templateId, long inventoryId, IssuerScope source,
                                        String requestId, String claimKey, int templateCount, int allocCount) {
        String trimmedRequestId = validateRequestId(requestId);
        String hash = requestHash(userId, templateId, inventoryId, source);
        ClaimTicket existing = find(userId, templateId, inventoryId, trimmedRequestId);
        if (existing != null && existing.getStatus() == ClaimTicketStatus.SUCCEEDED && existing.getVoucherId() != null) {
            stockGateway.releaseAllocationHoldByKey(inventoryId, claimKey);
            return userMapper.selectById(existing.getVoucherId());
        }
        OfferPlan template = loadOpenTemplate(templateId);
        ClaimTicket request = begin(userId, templateId, inventoryId, trimmedRequestId, hash, source);
        if (request.getStatus() == ClaimTicketStatus.SUCCEEDED && request.getVoucherId() != null) {
            stockGateway.releaseAllocationHoldByKey(inventoryId, claimKey);
            return userMapper.selectById(request.getVoucherId());
        }
        String token = UUID.randomUUID().toString().replace("-", "");
        if (!lease(request.getId(), token)) {
            ClaimTicket latest = requestMapper.selectById(request.getId());
            if (latest != null && latest.getStatus() == ClaimTicketStatus.SUCCEEDED && latest.getVoucherId() != null) {
                stockGateway.releaseAllocationHoldByKey(inventoryId, claimKey);
                return userMapper.selectById(latest.getVoucherId());
            }
            throw new BizException(ErrorCode.CONFLICT, "同一异步请求正在落库");
        }
        try {
            IssuedVoucher coupon = commit(request.getId(), userId, template, source, templateCount);
            stockGateway.releaseAllocationHoldByKey(inventoryId, claimKey);
            return coupon;
        } catch (RuntimeException exception) {
            stockGateway.compensateAllocationByKey(inventoryId, templateId, userId, templateCount, allocCount, claimKey);
            requestMapper.markFailed(request.getId(), token, LocalDateTime.now());
            throw exception;
        }
    }

    public AsyncTemplateMeta findOpenTemplateMeta(long templateId) {
        OfferPlan template = loadOpenTemplate(templateId);
        return new AsyncTemplateMeta(OfferPolicyRules.maxClaimsPerUser(template.getClaimPolicy()), template.getUsableUntil());
    }

    public record AsyncTemplateMeta(int templateLimit, LocalDateTime usableUntil) {
    }

    public ClaimTicket findRequestByUserCouponId(long voucherId) {
        return requestMapper.selectOne(Wrappers.<ClaimTicket>lambdaQuery()
                .eq(ClaimTicket::getVoucherId, voucherId));
    }

    public record ClaimReceipt(String requestId, String status, Long voucherId, IssuedVoucher coupon) {
    }

    private IssuedVoucher claim(long userId, long templateId, long inventoryId, IssuerScope source, String requestId) {
        // 领券依赖“Redis 预扣 → 独立事务提交 → 释放预扣”的顺序；被外层事务包住会让提交延后到 Redis 操作之后，
        // 一旦外层回滚就出现 Redis 与数据库库存不一致，因此直接拒绝在已有事务中调用。
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("领券不能在外层事务中调用");
        }
        String trimmedRequestId = validateRequestId(requestId);
        String hash = requestHash(userId, templateId, inventoryId, source);
        ClaimTicket existing = find(userId, templateId, inventoryId, trimmedRequestId);
        if (existing != null && !hash.equals(existing.getRequestHash())) {
            throw new BizException(ErrorCode.CONFLICT, "同一幂等键的参数不一致");
        }
        if (existing != null && existing.getStatus() == ClaimTicketStatus.SUCCEEDED) {
            return userMapper.selectById(existing.getVoucherId());
        }
        OfferPlan template = loadOpenTemplate(templateId);
        ClaimTicket request = begin(userId, templateId, inventoryId, trimmedRequestId, hash, source);
        if (request.getStatus() == ClaimTicketStatus.SUCCEEDED) {
            return userMapper.selectById(request.getVoucherId());
        }
        String token = UUID.randomUUID().toString().replace("-", "");
        if (!lease(request.getId(), token)) {
            ClaimTicket latest = requestMapper.selectById(request.getId());
            if (latest != null && latest.getStatus() == ClaimTicketStatus.SUCCEEDED) {
                return userMapper.selectById(latest.getVoucherId());
            }
            throw new BizException(ErrorCode.CONFLICT, "同一请求正在处理，请稍后用原请求号重试");
        }
        return finishOwned(template, request.getId(), userId, source, token);
    }

    public int recoverStuck(Duration olderThan) {
        LocalDateTime deadline = LocalDateTime.now().minus(olderThan);
        List<ClaimTicket> stuck = requestMapper.selectList(Wrappers.<ClaimTicket>lambdaQuery()
                .in(ClaimTicket::getStatus, ClaimTicketStatus.PENDING, ClaimTicketStatus.RESERVED)
                .isNotNull(ClaimTicket::getReservationToken)
                .le(ClaimTicket::getLeaseUntil, deadline));
        int recovered = 0;
        for (ClaimTicket request : stuck) {
            String token = UUID.randomUUID().toString().replace("-", "");
            if (!lease(request.getId(), token)) {
                continue;
            }
            OfferPlan template = templateMapper.selectById(request.getPlanId());
            if (template == null) {
                compensateAndFail(request.getId(), request.getPlanId(), request.getUserId(), request.getClaimSeq(), token);
                continue;
            }
            try {
                finishOwned(template, request.getId(), request.getUserId(),
                        request.getIssuerScope() == null ? IssuerScope.MERCHANT : request.getIssuerScope(), token);
                recovered++;
            } catch (RuntimeException exception) {
                compensateAndFail(request.getId(), request.getPlanId(), request.getUserId(), request.getClaimSeq(), token);
            }
        }
        return recovered;
    }

    public long receivedCount(long userId, long inventoryId) {
        return userMapper.selectCount(Wrappers.<IssuedVoucher>lambdaQuery()
                .eq(IssuedVoucher::getUserId, userId)
                .eq(IssuedVoucher::getInventoryId, inventoryId));
    }

    public Page<IssuedVoucher> myCoupons(long userId, long page, long size) {
        long safeSize = Math.min(Math.max(size, 1), 100);
        return userMapper.selectPage(new Page<>(Math.max(page, 1), safeSize),
                Wrappers.<IssuedVoucher>lambdaQuery()
                        .eq(IssuedVoucher::getUserId, userId)
                        .orderByDesc(IssuedVoucher::getId));
    }

    private IssuedVoucher finishOwned(OfferPlan template, long requestRowId, long userId, IssuerScope source, String token) {
        ClaimTicket current = requestMapper.selectById(requestRowId);
        if (current.getInventoryId() != null && current.getInventoryId() > 0) {
            return finishAllocation(template, current, userId, source, token);
        }
        Integer hold = stockGateway.readHold(requestRowId);
        int claimSeq;
        if (current.getStatus() == ClaimTicketStatus.RESERVED && current.getClaimSeq() != null) {
            claimSeq = current.getClaimSeq();
        } else if (hold != null) {
            claimSeq = hold;
            if (requestMapper.markReserved(requestRowId, token, claimSeq, null, LocalDateTime.now()) != 1) {
                throw new BizException(ErrorCode.CONFLICT, "领取预扣状态已被其他执行者接管");
            }
        } else {
            int limit = OfferPolicyRules.maxClaimsPerUser(template.getClaimPolicy());
            long ttl = Math.max(60, Duration.between(LocalDateTime.now(), template.getUsableUntil()).getSeconds());
            StockDecision decision = stockGateway.tryAcquire(template.getId(), userId, limit, template.getInventoryQty(), ttl, requestRowId);
            if (!decision.ok()) {
                requestMapper.markFailed(requestRowId, token, LocalDateTime.now());
                throw stockError(decision);
            }
            claimSeq = decision.claimSeq();
            if (requestMapper.markReserved(requestRowId, token, claimSeq, null, LocalDateTime.now()) != 1) {
                stockGateway.compensate(template.getId(), userId, claimSeq, requestRowId);
                throw new BizException(ErrorCode.CONFLICT, "领取预扣状态已被其他执行者接管");
            }
        }
        try {
            IssuedVoucher coupon = commit(requestRowId, userId, template, source, claimSeq);
            stockGateway.releaseHold(requestRowId);
            return coupon;
        } catch (RuntimeException exception) {
            compensateAndFail(requestRowId, template.getId(), userId, claimSeq, token);
            if (exception instanceof BizException biz) {
                throw biz;
            }
            throw exception;
        }
    }

    private IssuedVoucher finishAllocation(OfferPlan template, ClaimTicket current, long userId, IssuerScope source, String token) {
        long inventoryId = current.getInventoryId();
        long requestRowId = current.getId();
        OfferInventory allocation = requireOpenAllocation(inventoryId, requestRowId, token);
        int[] counts = reserveAllocationStock(template, allocation, current, userId, token, true);
        int templateCount = counts[0];
        int allocCount = counts[1];
        try {
            IssuedVoucher coupon = commit(requestRowId, userId, template, source, templateCount);
            stockGateway.releaseAllocationHold(inventoryId, requestRowId);
            return coupon;
        } catch (RuntimeException exception) {
            compensateAllocationAndFail(requestRowId, inventoryId, template.getId(), userId, templateCount, allocCount, token);
            if (exception instanceof BizException biz) {
                throw biz;
            }
            throw exception;
        }
    }

    private OfferInventory requireOpenAllocation(long inventoryId, long requestRowId, String token) {
        OfferInventory allocation = allocationMapper.selectById(inventoryId);
        if (allocation == null || allocation.getStatus() != InventoryStatus.OPEN) {
            requestMapper.markFailed(requestRowId, token, LocalDateTime.now());
            throw new BizException(ErrorCode.VALIDATION, "投放不可用");
        }
        return allocation;
    }

    private int[] reserveAllocationStock(OfferPlan template, OfferInventory allocation, ClaimTicket current,
                                         long userId, String token, boolean markImmediately) {
        long inventoryId = allocation.getId();
        long requestRowId = current.getId();
        HoldMark hold = stockGateway.readAllocationHold(inventoryId, requestRowId);
        if (current.getStatus() == ClaimTicketStatus.RESERVED && current.getClaimSeq() != null && current.getAllocReceiveCount() != null) {
            return new int[]{current.getClaimSeq(), current.getAllocReceiveCount()};
        }
        if (hold != null) {
            if (markImmediately && requestMapper.markReserved(requestRowId, token, hold.templateCount(), hold.allocCount(), LocalDateTime.now()) != 1) {
                throw new BizException(ErrorCode.CONFLICT, "领取预扣状态已被其他执行者接管");
            }
            return new int[]{hold.templateCount(), hold.allocCount()};
        }
        int templateLimit = OfferPolicyRules.maxClaimsPerUser(template.getClaimPolicy());
        int remaining = allocation.getQuantity() - allocation.getClaimedCount();
        long ttl = Math.max(60, Duration.between(LocalDateTime.now(), template.getUsableUntil()).getSeconds());
        StockDecision decision = stockGateway.tryAcquireAllocation(inventoryId, template.getId(), userId,
                allocation.getMaxClaimsPerUser(), templateLimit, remaining, ttl, requestRowId);
        if (!decision.ok()) {
            requestMapper.markFailed(requestRowId, token, LocalDateTime.now());
            throw stockError(decision);
        }
        if (markImmediately && requestMapper.markReserved(requestRowId, token, decision.claimSeq(), decision.allocCount(), LocalDateTime.now()) != 1) {
            stockGateway.compensateAllocation(inventoryId, template.getId(), userId, decision.claimSeq(), decision.allocCount(), requestRowId);
            throw new BizException(ErrorCode.CONFLICT, "领取预扣状态已被其他执行者接管");
        }
        return new int[]{decision.claimSeq(), decision.allocCount()};
    }

    private static String validateRequestId(String requestId) {
        if (requestId == null || requestId.isBlank() || requestId.length() > 64) {
            throw new BizException(ErrorCode.VALIDATION, "requestId 长度必须在 1 到 64 之间");
        }
        return requestId.trim();
    }

    private static String requestHash(long userId, long templateId, long inventoryId, IssuerScope source) {
        String scope = source == null ? "" : source.getCode();
        return RequestHashes.sha256(inventoryId == 0
                ? userId + "|" + templateId + "|" + scope
                : userId + "|" + templateId + "|" + inventoryId + "|" + scope);
    }

    private void compensateAndFail(long requestRowId, long templateId, long userId, Integer claimSeq, String token) {
        ClaimTicket row = requestMapper.selectById(requestRowId);
        if (row != null && row.getInventoryId() != null && row.getInventoryId() > 0) {
            compensateAllocationAndFail(requestRowId, row.getInventoryId(), templateId, userId,
                    claimSeq, row.getAllocReceiveCount(), token);
            return;
        }
        if (claimSeq != null) {
            int compensated = stockGateway.compensate(templateId, userId, claimSeq, requestRowId);
            if (compensated == 0 && stockGateway.readHold(requestRowId) != null) {
                return;
            }
        }
        requestMapper.markFailed(requestRowId, token, LocalDateTime.now());
    }

    private void compensateAllocationAndFail(long requestRowId, long inventoryId, long templateId, long userId,
                                             Integer templateCount, Integer allocCount, String token) {
        if (templateCount != null && allocCount != null) {
            int compensated = stockGateway.compensateAllocation(inventoryId, templateId, userId, templateCount, allocCount, requestRowId);
            if (compensated == 0 && stockGateway.readAllocationHold(inventoryId, requestRowId) != null) {
                return;
            }
        }
        requestMapper.markFailed(requestRowId, token, LocalDateTime.now());
    }

    private boolean lease(long id, String token) {
        LocalDateTime now = LocalDateTime.now();
        return requestMapper.acquireLease(id, token, now.plus(properties.getOffer().getClaimRecoverAfter()), now) == 1;
    }

    private IssuedVoucher commit(long requestId, long userId, OfferPlan template, IssuerScope source, int claimSeq) {
        return transactionTemplate.execute(status -> {
            ClaimTicket current = requestMapper.selectById(requestId);
            if (current != null && current.getStatus() == ClaimTicketStatus.SUCCEEDED) {
                return userMapper.selectById(current.getVoucherId());
            }
            long inventoryId = current.getInventoryId() == null ? 0L : current.getInventoryId();
            int limit = OfferPolicyRules.maxClaimsPerUser(template.getClaimPolicy());
            Long owned = userMapper.selectCount(Wrappers.<IssuedVoucher>lambdaQuery()
                    .eq(IssuedVoucher::getUserId, userId)
                    .eq(IssuedVoucher::getPlanId, template.getId()));
            if (owned != null && owned >= limit) {
                throw new BizException(ErrorCode.VALIDATION, "已达到领取上限");
            }
            if (inventoryId == 0) {
                if (templateMapper.decrementStock(template.getId(), 1, LocalDateTime.now()) != 1) {
                    throw new BizException(ErrorCode.VALIDATION, "库存不足或优惠券已结束");
                }
            } else {
                OfferInventory allocation = allocationMapper.selectById(inventoryId);
                Long allocOwned = userMapper.selectCount(Wrappers.<IssuedVoucher>lambdaQuery()
                        .eq(IssuedVoucher::getUserId, userId)
                        .eq(IssuedVoucher::getInventoryId, inventoryId));
                if (allocation == null || allocOwned != null && allocOwned >= allocation.getMaxClaimsPerUser()) {
                    throw new BizException(ErrorCode.VALIDATION, "已达到本场领取上限");
                }
                if (allocationMapper.claimOne(inventoryId, LocalDateTime.now()) != 1) {
                    throw new BizException(ErrorCode.VALIDATION, "投放库存不足");
                }
            }
            LocalDateTime now = LocalDateTime.now();
            IssuedVoucher coupon = new IssuedVoucher();
            coupon.setUserId(userId);
            coupon.setPlanId(template.getId());
            coupon.setInventoryId(inventoryId);
            coupon.setClaimSeq(claimSeq);
            coupon.setIssuerScope(source);
            coupon.setGrantedAt(now);
            coupon.setUsableFrom(now);
            coupon.setUsableUntil(validEnd(template, now));
            coupon.setStatus(VoucherStatus.AVAILABLE);
            try {
                userMapper.insert(coupon);
            } catch (DataIntegrityViolationException exception) {
                throw new BizException(ErrorCode.CONFLICT, "领取记录冲突，请使用原请求重试");
            }
            current.setStatus(ClaimTicketStatus.SUCCEEDED);
            current.setVoucherId(coupon.getId());
            current.setClaimSeq(claimSeq);
            current.setRedisApplied(1);
            current.setReservationToken(null);
            current.setLeaseUntil(null);
            requestMapper.updateById(current);
            outboxStore.append("claim:" + current.getId(), CLAIM_EVENT, String.valueOf(coupon.getId()),
                    "{\"voucherId\":" + coupon.getId() + ",\"templateId\":" + template.getId() + "}");
            return coupon;
        });
    }

    private ClaimTicket begin(long userId, long templateId, long inventoryId, String requestId, String hash, IssuerScope source) {
        ClaimTicket existing = find(userId, templateId, inventoryId, requestId);
        if (existing == null) {
            ClaimTicket created = new ClaimTicket();
            created.setUserId(userId);
            created.setPlanId(templateId);
            created.setInventoryId(inventoryId);
            created.setRequestId(requestId);
            created.setRequestHash(hash);
            created.setIssuerScope(source);
            created.setStatus(ClaimTicketStatus.PENDING);
            created.setRedisApplied(0);
            try {
                requestMapper.insert(created);
                return created;
            } catch (DataIntegrityViolationException exception) {
                existing = find(userId, templateId, inventoryId, requestId);
            }
        }
        if (existing == null) {
            throw new BizException(ErrorCode.CONFLICT, "领取请求冲突");
        }
        if (!hash.equals(existing.getRequestHash())) {
            throw new BizException(ErrorCode.CONFLICT, "同一幂等键的参数不一致");
        }
        if (existing.getStatus() == ClaimTicketStatus.FAILED) {
            boolean held = inventoryId == 0
                    ? stockGateway.readHold(existing.getId()) != null
                    : stockGateway.readAllocationHold(inventoryId, existing.getId()) != null;
            if (!held) {
                existing.setStatus(ClaimTicketStatus.PENDING);
                existing.setRedisApplied(0);
                existing.setReservationToken(null);
                existing.setLeaseUntil(null);
                requestMapper.updateById(existing);
            }
        }
        return existing;
    }

    private OfferPlan loadOpenTemplate(long templateId) {
        OfferPlan template = templateMapper.selectById(templateId);
        LocalDateTime now = LocalDateTime.now();
        if (template == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "优惠券模板不存在");
        }
        if (template.getStatus() != OfferPlanStatus.OPEN) {
            throw new BizException(ErrorCode.VALIDATION, "优惠券已结束");
        }
        if (now.isBefore(template.getUsableFrom()) || now.isAfter(template.getUsableUntil())) {
            throw new BizException(ErrorCode.VALIDATION, "不在优惠券领取时间");
        }
        return template;
    }

    private LocalDateTime validEnd(OfferPlan template, LocalDateTime now) {
        JsonNode rule = OfferPolicyRules.parse(template.getBenefitPolicy(), "核销规则");
        int hours = rule.path("validityPeriodHours").asInt(0);
        LocalDateTime end = hours > 0 ? now.plusHours(hours) : template.getUsableUntil();
        return end.isAfter(template.getUsableUntil()) ? template.getUsableUntil() : end;
    }

    private ClaimTicket find(long userId, long templateId, long inventoryId, String requestId) {
        return requestMapper.selectOne(Wrappers.<ClaimTicket>lambdaQuery()
                .eq(ClaimTicket::getUserId, userId)
                .eq(ClaimTicket::getPlanId, templateId)
                .eq(ClaimTicket::getInventoryId, inventoryId)
                .eq(ClaimTicket::getRequestId, requestId));
    }

    private BizException stockError(StockDecision decision) {
        if (decision.quotaExceeded()) {
            return new BizException(ErrorCode.VALIDATION, "已达到领取上限");
        }
        return new BizException(ErrorCode.VALIDATION, "库存不足");
    }
}
