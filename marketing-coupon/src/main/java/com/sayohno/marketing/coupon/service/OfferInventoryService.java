package com.sayohno.marketing.coupon.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sayohno.marketing.common.exception.BizException;
import com.sayohno.marketing.common.result.ErrorCode;
import com.sayohno.marketing.coupon.dao.entity.ClaimTicket;
import com.sayohno.marketing.coupon.dao.entity.OfferInventory;
import com.sayohno.marketing.coupon.dao.entity.OfferInventoryCommand;
import com.sayohno.marketing.coupon.dao.entity.OfferPlan;
import com.sayohno.marketing.coupon.dao.mapper.ClaimTicketMapper;
import com.sayohno.marketing.coupon.dao.mapper.OfferInventoryCommandMapper;
import com.sayohno.marketing.coupon.dao.mapper.OfferInventoryMapper;
import com.sayohno.marketing.coupon.dao.mapper.OfferPlanMapper;
import com.sayohno.marketing.coupon.dao.mapper.IssuedVoucherMapper;
import com.sayohno.marketing.coupon.dao.entity.IssuedVoucher;
import com.sayohno.marketing.coupon.enums.ClaimTicketStatus;
import com.sayohno.marketing.coupon.enums.InventoryStatus;
import com.sayohno.marketing.coupon.stock.OfferStockGateway;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.LocalDateTime;

@Service
public class OfferInventoryService {
    private final OfferInventoryMapper allocationMapper;
    private final OfferInventoryCommandMapper commandMapper;
    private final OfferPlanMapper templateMapper;
    private final IssuedVoucherMapper userMapper;
    private final ClaimTicketMapper claimTicketMapper;
    private final OfferPlanService templateService;
    private final OfferStockGateway stockGateway;
    private final org.springframework.beans.factory.ObjectProvider<org.springframework.data.redis.core.StringRedisTemplate> redis;

    public OfferInventoryService(OfferInventoryMapper allocationMapper,
                                   OfferInventoryCommandMapper commandMapper,
                                   OfferPlanMapper templateMapper,
                                   IssuedVoucherMapper userMapper,
                                   ClaimTicketMapper claimTicketMapper,
                                   OfferPlanService templateService,
                                   OfferStockGateway stockGateway, org.springframework.beans.factory.ObjectProvider<org.springframework.data.redis.core.StringRedisTemplate> redis) {
        this.allocationMapper = allocationMapper;
        this.commandMapper = commandMapper;
        this.templateMapper = templateMapper;
        this.userMapper = userMapper;
        this.claimTicketMapper = claimTicketMapper;
        this.templateService = templateService;
        this.stockGateway = stockGateway;
        this.redis = redis;
    }

    /**
     * 计算可用于初始化 Redis 库存缓存的真实剩余：数据库剩余减去在途已预扣（RESERVED）票据数，
     * 防止 Redis 键丢失或预热时把在途未落库的预扣重新放回可用库存。
     */
    public int availableForCache(OfferInventory allocation) {
        int dbRemaining = Math.max(0, allocation.getQuantity() - allocation.getClaimedCount());
        Long reserved = claimTicketMapper.selectCount(Wrappers.<ClaimTicket>lambdaQuery()
                .eq(ClaimTicket::getInventoryId, allocation.getId())
                .eq(ClaimTicket::getStatus, ClaimTicketStatus.RESERVED));
        int inFlight = reserved == null ? 0 : reserved.intValue();
        return Math.max(0, dbRemaining - inFlight);
    }

    /**
     * 预热库存缓存：若键不存在则用 SET NX 写入扣除在途预扣后的可用剩余并读回验证；
     * 若键已存在则与数据库剩余及在途预扣比对，避免覆盖正在运行中的实时库存。
     */
    public java.util.Map<String, Object> warmStock(long inventoryId) {
        var allocation = requireOwned(inventoryId);
        var template = templateService.requireOwned(allocation.getPlanId());
        var cache = redis.getIfAvailable();
        if (cache == null) return java.util.Map.of("status", "UNVERIFIED", "detail", "Redis未启用，无法预热库存缓存");
        String key = com.sayohno.marketing.coupon.redis.OfferInventoryKeys.inventory(inventoryId);
        int dbRemaining = Math.max(0, allocation.getQuantity() - allocation.getClaimedCount());
        int available = availableForCache(allocation);
        long ttl = Math.max(60, Duration.between(LocalDateTime.now(), template.getUsableUntil()).getSeconds());
        Boolean initialized = cache.opsForValue().setIfAbsent(key, Integer.toString(available), Duration.ofSeconds(ttl));
        String raw = cache.opsForValue().get(key);
        if (raw == null) {
            return java.util.Map.of("status", "FAILED", "detail", "库存缓存写入后未能读回");
        }
        int cached = Integer.parseInt(raw);
        if (Boolean.TRUE.equals(initialized)) {
            boolean matched = cached == available;
            return java.util.Map.of("status", matched ? "SUCCEEDED" : "FAILED",
                    "detail", matched ? "库存缓存已预热并读回，可用剩余=" + available : "库存缓存初始化后读回不一致");
        }
        String status = (cached == available || cached == dbRemaining) ? "SUCCEEDED"
                : (cached < dbRemaining && cached >= 0) ? "UNVERIFIED" : "FAILED";
        return java.util.Map.of("status", status,
                "detail", "数据库剩余=" + dbRemaining + "，扣在途后=" + available + "，缓存剩余=" + cached
                        + ("UNVERIFIED".equals(status) ? "；可能存在在途预扣，需对账复核" : ""));
    }

    /** 发布活动时静默预热库存缓存，Redis 未启用或抖动时不阻断发布流程。 */
    public void warmStockQuietly(long inventoryId) {
        try {
            var cache = redis.getIfAvailable();
            if (cache == null) return;
            var allocation = allocationMapper.selectById(inventoryId);
            if (allocation == null || allocation.getStatus() != InventoryStatus.OPEN) return;
            var template = templateMapper.selectById(allocation.getPlanId());
            if (template == null || template.getUsableUntil() == null) return;
            int available = availableForCache(allocation);
            long ttl = Math.max(60, Duration.between(LocalDateTime.now(), template.getUsableUntil()).getSeconds());
            cache.opsForValue().setIfAbsent(
                    com.sayohno.marketing.coupon.redis.OfferInventoryKeys.inventory(inventoryId),
                    Integer.toString(available), Duration.ofSeconds(ttl));
        } catch (RuntimeException ignored) {
        }
    }

    /** Observe only; never initialize or replace the live stock hash. */
    public java.util.Map<String, Object> inspectStock(long inventoryId) {
        var allocation = requireOwned(inventoryId);
        var cache = redis.getIfAvailable();
        if (cache == null) return java.util.Map.of("status", "UNVERIFIED", "detail", "Redis未启用，无法核对库存缓存");
        String raw = cache.opsForValue().get(com.sayohno.marketing.coupon.redis.OfferInventoryKeys.inventory(inventoryId));
        if (raw == null) return java.util.Map.of("status", "SKIPPED", "detail", "库存缓存尚未初始化，将由真实领取路径初始化；本次未写库存");
        int remaining = allocation.getQuantity() - allocation.getClaimedCount();
        int cached = Integer.parseInt(raw);
        String status = cached == remaining ? "SUCCEEDED" : cached < remaining && cached >= 0 ? "UNVERIFIED" : "FAILED";
        return java.util.Map.of("status", status, "detail", "数据库剩余=" + remaining + "，缓存剩余=" + cached + (cached < remaining ? "；可能存在在途预扣，需对账复核" : ""));
    }

    @Transactional
    public OfferInventory allocate(long templateId, int quantity, int maxClaimsPerUser, String idempotencyKey) {
        OfferPlan template = requireTemplate(templateId, quantity, maxClaimsPerUser, idempotencyKey);
        OfferInventory existing = allocationMapper.selectOne(Wrappers.<OfferInventory>lambdaQuery()
                .eq(OfferInventory::getMerchantId, template.getMerchantId())
                .eq(OfferInventory::getIdempotencyKey, idempotencyKey.trim()));
        if (existing != null) {
            if (!existing.getPlanId().equals(templateId) || existing.getQuantity() != quantity
                    || existing.getMaxClaimsPerUser() != maxClaimsPerUser) {
                throw new BizException(ErrorCode.CONFLICT, "同一幂等键的投放参数不一致");
            }
            return existing;
        }
        if (templateMapper.decrementStock(templateId, quantity, LocalDateTime.now()) != 1) {
            throw new BizException(ErrorCode.VALIDATION, "可投放数量不足");
        }
        OfferInventory created = new OfferInventory();
        created.setPlanId(templateId);
        created.setMerchantId(template.getMerchantId());
        created.setQuantity(quantity);
        created.setClaimedCount(0);
        created.setMaxClaimsPerUser(maxClaimsPerUser);
        created.setStatus(InventoryStatus.OPEN);
        created.setIdempotencyKey(idempotencyKey.trim());
        try {
            allocationMapper.insert(created);
        } catch (DataIntegrityViolationException exception) {
            throw new BizException(ErrorCode.CONFLICT, "投放请求冲突，请用原幂等键重试");
        }
        return created;
    }

    @Transactional
    public OfferInventory adjust(long inventoryId, int targetQuantity, int maxClaimsPerUser, String idempotencyKey,
                                   boolean allowReduce, boolean allowLimitChange) {
        if (targetQuantity < 1 || maxClaimsPerUser < 1 || idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new BizException(ErrorCode.VALIDATION, "投放数量、限领和幂等键不合法");
        }
        OfferInventory allocation = requireOwned(inventoryId);
        if (allocation.getStatus() != InventoryStatus.OPEN) {
            throw new BizException(ErrorCode.CONFLICT, "投放已冻结");
        }
        OfferPlan template = templateService.requireOwned(allocation.getPlanId());
        if (maxClaimsPerUser > OfferPolicyRules.maxClaimsPerUser(template.getClaimPolicy())) {
            throw new BizException(ErrorCode.VALIDATION, "本场限领不能高于模板总限领");
        }
        OfferInventoryCommand command = commandMapper.selectOne(Wrappers.<OfferInventoryCommand>lambdaQuery()
                .eq(OfferInventoryCommand::getInventoryId, inventoryId)
                .eq(OfferInventoryCommand::getIdempotencyKey, idempotencyKey.trim()));
        if (command != null) {
            if (command.getTargetQuantity() != targetQuantity || command.getMaxClaimsPerUser() != maxClaimsPerUser) {
                throw new BizException(ErrorCode.CONFLICT, "同一幂等键的投放参数不一致");
            }
            // 同参幂等键命中历史命令时直接返回当前投放，防止乱序重试把后续已追加的库存误减回旧值。
            return allocation;
        }
        OfferInventoryCommand created = new OfferInventoryCommand();
        created.setInventoryId(inventoryId);
        created.setIdempotencyKey(idempotencyKey.trim());
        created.setTargetQuantity(targetQuantity);
        created.setMaxClaimsPerUser(maxClaimsPerUser);
        try {
            commandMapper.insert(created);
        } catch (DataIntegrityViolationException exception) {
            throw new BizException(ErrorCode.CONFLICT, "投放请求冲突，请用原幂等键重试");
        }
        allocation = allocationMapper.selectById(inventoryId);
        if (maxClaimsPerUser != allocation.getMaxClaimsPerUser()) {
            if (!allowLimitChange) {
                throw new BizException(ErrorCode.CONFLICT, "开场后不能修改每人限领");
            }
            allocation.setMaxClaimsPerUser(maxClaimsPerUser);
            allocationMapper.updateById(allocation);
        }
        int delta = targetQuantity - allocation.getQuantity();
        if (delta > 0) {
            if (templateMapper.decrementStock(allocation.getPlanId(), delta, LocalDateTime.now()) != 1) {
                throw new BizException(ErrorCode.VALIDATION, "可投放数量不足");
            }
            if (allocationMapper.addQuantity(inventoryId, delta, allocation.getQuantity(), LocalDateTime.now()) != 1) {
                throw new BizException(ErrorCode.CONFLICT, "投放数量已变化，请重试");
            }
            afterCommit(() -> stockGateway.increaseAllocationIfPresent(inventoryId, delta));
        } else if (delta < 0) {
            if (!allowReduce) {
                throw new BizException(ErrorCode.CONFLICT, "开场后不能减少投放");
            }
            int reduce = -delta;
            if (allocationMapper.reduceQuantity(inventoryId, reduce, allocation.getQuantity(), LocalDateTime.now()) != 1) {
                throw new BizException(ErrorCode.VALIDATION, "减少后的投放不能少于已领取数量");
            }
            templateMapper.returnUnallocated(allocation.getPlanId(), reduce, LocalDateTime.now());
            afterCommit(() -> stockGateway.decreaseAllocationIfPresent(inventoryId, reduce));
        }
        return allocationMapper.selectById(inventoryId);
    }

    @Transactional
    public OfferInventory freeze(long inventoryId) {
        // 先对投放行加 FOR UPDATE 排他锁，确保读取 claimedCount 与冻结之间不会插入新的领券提交，守恒退回未领库存。
        OfferInventory allocation = allocationMapper.lockById(inventoryId);
        if (allocation == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "投放不存在");
        }
        if (allocation.getStatus() == InventoryStatus.FROZEN) {
            return allocation;
        }
        int unclaimed = Math.max(0, allocation.getQuantity() - allocation.getClaimedCount());
        if (allocationMapper.freeze(inventoryId, LocalDateTime.now()) != 1) {
            return allocationMapper.selectById(inventoryId);
        }
        if (unclaimed > 0) {
            templateMapper.returnUnallocated(allocation.getPlanId(), unclaimed, LocalDateTime.now());
        }
        afterCommit(() -> stockGateway.deleteAllocation(inventoryId));
        return allocationMapper.selectById(inventoryId);
    }

    private static void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }

    public OfferInventory find(long inventoryId) {
        OfferInventory allocation = allocationMapper.selectById(inventoryId);
        if (allocation == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "投放不存在");
        }
        return allocation;
    }

    public java.util.Map<Long, OfferInventory> findBatch(java.util.Collection<Long> allocationIds) {
        if (allocationIds == null || allocationIds.isEmpty()) {
            return java.util.Map.of();
        }
        return allocationMapper.selectBatchIds(allocationIds).stream()
                .collect(java.util.stream.Collectors.toMap(OfferInventory::getId, a -> a, (a, b) -> a));
    }

    public OfferInventory requireOwned(long inventoryId) {
        OfferInventory allocation = allocationMapper.selectById(inventoryId);
        if (allocation == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "投放不存在");
        }
        templateService.requireOwned(allocation.getPlanId());
        return allocation;
    }

    public int unallocated(long templateId) {
        return templateService.requireOwned(templateId).getInventoryQty();
    }

    public int allocatedQuantity(long templateId) {
        templateService.requireOwned(templateId);
        return allocationMapper.selectList(Wrappers.<OfferInventory>lambdaQuery()
                        .eq(OfferInventory::getPlanId, templateId)).stream()
                .mapToInt(OfferInventory::getQuantity)
                .sum();
    }

    public void fillUsage(OfferPlan template) {
        java.util.List<OfferInventory> rows = allocationMapper.selectList(Wrappers.<OfferInventory>lambdaQuery()
                .eq(OfferInventory::getPlanId, template.getId()));
        int openRemaining = rows.stream()
                .filter(row -> row.getStatus() == InventoryStatus.OPEN)
                .mapToInt(row -> Math.max(0, row.getQuantity() - row.getClaimedCount()))
                .sum();
        int issued = rows.stream().mapToInt(OfferInventory::getClaimedCount).sum() + directClaimed(template.getId());
        template.setOpenRemaining(openRemaining);
        template.setIssuedCount(issued);
    }

    public int directClaimed(long templateId) {
        templateService.requireOwned(templateId);
        Long count = userMapper.selectCount(Wrappers.<IssuedVoucher>lambdaQuery()
                .eq(IssuedVoucher::getPlanId, templateId)
                .eq(IssuedVoucher::getInventoryId, 0L));
        return count == null ? 0 : count.intValue();
    }

    private OfferPlan requireTemplate(long templateId, int quantity, int maxClaimsPerUser, String idempotencyKey) {
        if (quantity < 1 || maxClaimsPerUser < 1 || idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new BizException(ErrorCode.VALIDATION, "投放数量、限领和幂等键不合法");
        }
        OfferPlan template = templateService.requireOwned(templateId);
        if (maxClaimsPerUser > OfferPolicyRules.maxClaimsPerUser(template.getClaimPolicy())) {
            throw new BizException(ErrorCode.VALIDATION, "本场限领不能高于模板总限领");
        }
        return template;
    }
}
