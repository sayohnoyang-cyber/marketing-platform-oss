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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private static final Logger log = LoggerFactory.getLogger(VoucherClaimService.class);
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
        reserveOwned(request.getId(), template, token, true);
        return new ClaimReceipt(trimmedRequestId, "RESERVED", null, null);
    }

    /**
     * 消费异步领券事件，完成数据库落库与释放 Redis hold。
     * 严禁在外层事务中调用：必须保证 commit() 独立事务已提交后再释放 Redis 预扣标记；
     * 终态业务失败（VALIDATION/NOT_FOUND）立即回补 Redis 库存并标记 FAILED，
     * 瞬时基础设施异常（DB 连接超时、死锁等）直接向上抛出交由 Outbox/MQ 重试。
     */
    public void completeAsyncClaim(long requestRowId, String token) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("异步领券落库不能在外层事务中调用");
        }
        ClaimTicket current = requestMapper.selectById(requestRowId);
        if (current == null || current.getStatus() == ClaimTicketStatus.SUCCEEDED || current.getStatus() == ClaimTicketStatus.FAILED) {
            return;
        }
        if (current.getInventoryId() == null || current.getInventoryId() <= 0) {
            return;
        }
        if (token == null || !token.equals(current.getReservationToken())) {
            // 已接管的票据由新持有者继续处理，旧消息无需再次竞争租约。
            return;
        }
        try {
            OfferPlan template = loadOpenTemplate(current.getPlanId());
            IssuerScope source = current.getIssuerScope() == null ? IssuerScope.MERCHANT : current.getIssuerScope();
            commitAndRelease(current, template, source, token);
        } catch (BizException exception) {
            if (!terminalFailure(exception)) {
                throw exception;
            }
            failOwned(requestRowId, token);
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
        return finishOwned(template, request.getId(), source, token);
    }

    public int recoverStuck(Duration olderThan) {
        List<ClaimTicket> pendingRefunds = requestMapper.selectList(Wrappers.<ClaimTicket>lambdaQuery()
                .select(ClaimTicket::getId)
                .eq(ClaimTicket::getStatus, ClaimTicketStatus.FAILED)
                .eq(ClaimTicket::getRedisApplied, 1)
                .orderByAsc(ClaimTicket::getId).last("LIMIT 200"));
        for (ClaimTicket row : pendingRefunds) {
            clearFailedReservation(row.getId());
        }
        LocalDateTime deadline = LocalDateTime.now().minus(olderThan);
        // 限制单轮扫描恢复条数，避免积压过长时一次性拉取全部超时票据撑爆内存或长期占用调度线程。
        List<ClaimTicket> stuck = requestMapper.selectList(Wrappers.<ClaimTicket>lambdaQuery()
                .in(ClaimTicket::getStatus, ClaimTicketStatus.PENDING, ClaimTicketStatus.RESERVED)
                .isNotNull(ClaimTicket::getReservationToken)
                .le(ClaimTicket::getLeaseUntil, deadline)
                .orderByAsc(ClaimTicket::getId)
                .last("LIMIT 200"));
        int recovered = 0;
        for (ClaimTicket request : stuck) {
            String token = UUID.randomUUID().toString().replace("-", "");
            if (!lease(request.getId(), token)) {
                continue;
            }
            try {
                OfferPlan template = loadOpenTemplate(request.getPlanId());
                finishOwned(template, request.getId(),
                        request.getIssuerScope() == null ? IssuerScope.MERCHANT : request.getIssuerScope(), token);
                recovered++;
            } catch (BizException exception) {
                if (terminalFailure(exception)) {
                    failOwned(request.getId(), token);
                }
            }
        }
        return recovered;
    }

    public long receivedCount(long userId, long inventoryId) {
        return userMapper.selectCount(Wrappers.<IssuedVoucher>lambdaQuery()
                .eq(IssuedVoucher::getUserId, userId)
                .eq(IssuedVoucher::getInventoryId, inventoryId));
    }

    public boolean hasMerchantVoucher(long userId, long merchantId) {
        return userMapper.countByUserAndMerchant(userId, merchantId) > 0;
    }

    public boolean hasRedeemedMerchantVoucher(long userId, long merchantId) {
        return userMapper.countRedeemedByUserAndMerchant(userId, merchantId) > 0;
    }

    public Page<IssuedVoucher> myCoupons(long userId, long page, long size) {
        long safeSize = Math.min(Math.max(size, 1), 100);
        return userMapper.selectPage(new Page<>(Math.max(page, 1), safeSize),
                Wrappers.<IssuedVoucher>lambdaQuery()
                        .eq(IssuedVoucher::getUserId, userId)
                        .orderByDesc(IssuedVoucher::getId));
    }

    private IssuedVoucher finishOwned(OfferPlan template, long requestRowId, IssuerScope source, String token) {
        ClaimTicket ticket = reserveOwned(requestRowId, template, token, false);
        try {
            return commitAndRelease(ticket, template, source, token);
        } catch (BizException exception) {
            if (terminalFailure(exception)) {
                failOwned(requestRowId, token);
            }
            throw exception;
        }
    }

    private static boolean terminalFailure(BizException exception) {
        return exception.getErrorCode() == ErrorCode.VALIDATION || exception.getErrorCode() == ErrorCode.NOT_FOUND;
    }

    private ClaimTicket reserveOwned(long id, OfferPlan template, String token, boolean async) {
        try {
            return transactionTemplate.execute(status -> {
                ClaimTicket ticket = requestMapper.lockById(id);
                requireOwner(ticket, token);
                if (ticket.getStatus() == ClaimTicketStatus.PENDING) {
                    StockDecision decision;
                    if (ticket.getInventoryId() != null && ticket.getInventoryId() > 0) {
                        OfferInventory allocation = allocationMapper.selectById(ticket.getInventoryId());
                        if (allocation == null || allocation.getStatus() != InventoryStatus.OPEN) {
                            throw new BizException(ErrorCode.VALIDATION, "投放不可用");
                        }
                        decision = reserveAllocationStock(template, allocation, ticket, ticket.getUserId());
                    } else {
                        int limit = OfferPolicyRules.maxClaimsPerUser(template.getClaimPolicy());
                        decision = stockGateway.tryAcquire(template.getId(), ticket.getUserId(), limit,
                                template.getInventoryQty(), reservationTtl(template), id);
                    }
                    if (!decision.ok()) {
                        throw stockError(decision);
                    }
                    Integer allocationCount = ticket.getInventoryId() != null && ticket.getInventoryId() > 0
                            ? decision.allocCount() : null;
                    if (requestMapper.markReserved(id, token, decision.claimSeq(), allocationCount, LocalDateTime.now()) != 1) {
                        throw lostLease();
                    }
                }
                if (async) {
                    outboxStore.append("claim-async:" + id + ":" + token, ASYNC_CLAIM_EVENT, String.valueOf(id),
                            "{\"requestRowId\":" + id + ",\"token\":\"" + token + "\"}");
                }
                return ticket;
            });
        } catch (BizException exception) {
            if (terminalFailure(exception)) {
                failOwned(id, token);
            }
            throw exception;
        }
    }

    private IssuedVoucher commitAndRelease(ClaimTicket ticket, OfferPlan template, IssuerScope source, String token) {
        long id = ticket.getId();
        IssuedVoucher coupon = commit(id, ticket.getUserId(), template, source, token);
        releaseCommittedHold(id, () -> {
            if (ticket.getInventoryId() != null && ticket.getInventoryId() > 0) {
                stockGateway.releaseAllocationHold(template.getId(), ticket.getInventoryId(), id);
            } else {
                stockGateway.releaseHold(template.getId(), id);
            }
        });
        return coupon;
    }

    private void releaseCommittedHold(long ticketId, Runnable release) {
        try {
            release.run();
        } catch (RuntimeException exception) {
            // 数据库已确认发券。残留标记带有 TTL，清理失败不能触发库存补偿。
            log.warn("领券已成功，预扣标记等待过期。ticketId={}", ticketId, exception);
        }
    }

    private long reservationTtl(OfferPlan template) {
        return Math.max(60, Duration.between(LocalDateTime.now(), template.getUsableUntil()).getSeconds());
    }

    private StockDecision reserveAllocationStock(OfferPlan template, OfferInventory allocation, ClaimTicket current, long userId) {
        long inventoryId = allocation.getId();
        int templateLimit = OfferPolicyRules.maxClaimsPerUser(template.getClaimPolicy());
        long ttl = reservationTtl(template);
        StockDecision decision = stockGateway.tryAcquireAllocation(inventoryId, template.getId(), userId,
                allocation.getMaxClaimsPerUser(), templateLimit, -1, ttl, current.getId());
        if (!StockDecision.NEEDS_STOCK.equals(decision.reason())) {
            return decision;
        }
        Long inFlight = requestMapper.selectCount(Wrappers.<ClaimTicket>lambdaQuery()
                .eq(ClaimTicket::getInventoryId, inventoryId)
                .eq(ClaimTicket::getStatus, ClaimTicketStatus.RESERVED));
        int remaining = Math.max(0, allocation.getQuantity() - allocation.getClaimedCount() - inFlight.intValue());
        return stockGateway.tryAcquireAllocation(inventoryId, template.getId(), userId,
                allocation.getMaxClaimsPerUser(), templateLimit, remaining, ttl, current.getId());
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

    private void failOwned(long id, String token) {
        // 先持久化失败，再退还预扣。进程中断后，同一请求重试会先清理残留标记。
        if (requestMapper.markFailed(id, token, LocalDateTime.now()) == 1) {
            clearFailedReservation(id);
        }
    }

    private void clearFailedReservation(long id) {
        transactionTemplate.executeWithoutResult(status -> {
            ClaimTicket row = requestMapper.lockById(id);
            if (row != null && row.getStatus() == ClaimTicketStatus.FAILED && Integer.valueOf(1).equals(row.getRedisApplied())) {
                releaseFailedReservation(row);
                requestMapper.update(null, Wrappers.<ClaimTicket>lambdaUpdate()
                        .eq(ClaimTicket::getId, id).set(ClaimTicket::getRedisApplied, 0));
            }
        });
    }

    private void releaseFailedReservation(ClaimTicket row) {
        long plan = row.getPlanId();
        long id = row.getId();
        if (row.getInventoryId() != null && row.getInventoryId() > 0) {
            long inventory = row.getInventoryId();
            HoldMark hold = stockGateway.readAllocationHold(plan, inventory, id);
            if (hold != null) {
                stockGateway.compensateAllocation(inventory, plan, row.getUserId(), hold.templateCount(), hold.allocCount(), id);
            }
        } else {
            Integer hold = stockGateway.readHold(plan, id);
            if (hold != null) {
                stockGateway.compensate(plan, row.getUserId(), hold, id);
            }
        }
    }

    private void requireOwner(ClaimTicket row, String token) {
        if (row == null || !token.equals(row.getReservationToken())
                || (row.getStatus() != ClaimTicketStatus.PENDING && row.getStatus() != ClaimTicketStatus.RESERVED)) {
            throw lostLease();
        }
    }

    private BizException lostLease() {
        return new BizException(ErrorCode.CONFLICT, "领取票据已由其他执行者处理");
    }

    private boolean lease(long id, String token) {
        LocalDateTime now = LocalDateTime.now();
        return requestMapper.acquireLease(id, token, now.plus(properties.getOffer().getClaimRecoverAfter()), now) == 1;
    }

    private IssuedVoucher commit(long requestId, long userId, OfferPlan template, IssuerScope source, String token) {
        return transactionTemplate.execute(status -> {
            ClaimTicket current = requestMapper.lockById(requestId);
            if (current != null && current.getStatus() == ClaimTicketStatus.SUCCEEDED) {
                return userMapper.selectById(current.getVoucherId());
            }
            requireOwner(current, token);
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
            // 在数据库事务内基于已有最大序号递增分配 claim_seq，避免 Redis 乱序回补限领计数后触发唯一键冲突。
            Integer latestSeq = userMapper.lockLatestClaimSeq(userId, template.getId());
            int dbClaimSeq = (latestSeq == null ? 0 : latestSeq) + 1;
            IssuedVoucher coupon = new IssuedVoucher();
            coupon.setUserId(userId);
            coupon.setPlanId(template.getId());
            coupon.setInventoryId(inventoryId);
            coupon.setClaimSeq(dbClaimSeq);
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
            if (requestMapper.succeed(requestId, token, coupon.getId(), dbClaimSeq, now) != 1) {
                throw lostLease();
            }
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
            long id = existing.getId();
            transactionTemplate.executeWithoutResult(status -> {
                ClaimTicket row = requestMapper.lockById(id);
                if (row.getStatus() == ClaimTicketStatus.FAILED) {
                    releaseFailedReservation(row);
                    requestMapper.reopenFailed(id, LocalDateTime.now());
                }
            });
            return requestMapper.selectById(id);
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
