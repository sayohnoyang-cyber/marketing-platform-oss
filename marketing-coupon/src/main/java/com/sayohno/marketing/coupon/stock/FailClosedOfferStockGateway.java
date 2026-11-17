package com.sayohno.marketing.coupon.stock;

import com.sayohno.marketing.common.exception.BizException;
import com.sayohno.marketing.common.result.ErrorCode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "marketing.redis", name = "enabled", havingValue = "false")
public class FailClosedOfferStockGateway implements OfferStockGateway {
    @Override
    public StockDecision tryAcquire(long templateId, long userId, int limit, int dbStock, long ttlSeconds, long claimRequestId) {
        throw new BizException(ErrorCode.SYSTEM, "Redis 未启用，不能扣减库存");
    }

    @Override
    public int compensate(long templateId, long userId, int claimSeq, long claimRequestId) {
        throw new BizException(ErrorCode.SYSTEM, "Redis 未启用，不能回补库存");
    }

    @Override
    public Integer readHold(long claimRequestId) {
        throw new BizException(ErrorCode.SYSTEM, "Redis 未启用，不能读取预扣标记");
    }

    @Override
    public void releaseHold(long claimRequestId) {
    }

    @Override
    public void increaseIfPresent(long templateId, int delta) {
        throw new BizException(ErrorCode.SYSTEM, "Redis 未启用，不能调整库存缓存");
    }

    @Override
    public StockDecision tryAcquireAllocation(long inventoryId, long templateId, long userId, int allocationLimit,
                                              int templateLimit, int dbRemaining, long ttlSeconds, long claimRequestId) {
        throw new BizException(ErrorCode.SYSTEM, "Redis 未启用，不能扣减投放库存");
    }

    @Override
    public StockDecision tryAcquireAllocationByKey(long inventoryId, long templateId, long userId, int allocationLimit,
                                                   int templateLimit, int dbRemaining, long ttlSeconds, String claimKey) {
        throw new BizException(ErrorCode.SYSTEM, "Redis 未启用，不能扣减投放库存");
    }

    @Override
    public int compensateAllocation(long inventoryId, long templateId, long userId, int templateCount, int allocCount, long claimRequestId) {
        throw new BizException(ErrorCode.SYSTEM, "Redis 未启用，不能回补投放库存");
    }

    @Override
    public int compensateAllocationByKey(long inventoryId, long templateId, long userId, int templateCount, int allocCount, String claimKey) {
        throw new BizException(ErrorCode.SYSTEM, "Redis 未启用，不能回补投放库存");
    }

    @Override
    public HoldMark readAllocationHold(long inventoryId, long claimRequestId) {
        throw new BizException(ErrorCode.SYSTEM, "Redis 未启用，不能读取投放预扣");
    }

    @Override
    public void releaseAllocationHold(long inventoryId, long claimRequestId) {
    }

    @Override
    public void releaseAllocationHoldByKey(long inventoryId, String claimKey) {
    }

    @Override
    public void increaseAllocationIfPresent(long inventoryId, int delta) {
        throw new BizException(ErrorCode.SYSTEM, "Redis 未启用，不能调整投放库存缓存");
    }

    @Override
    public void decreaseAllocationIfPresent(long inventoryId, int delta) {
        throw new BizException(ErrorCode.SYSTEM, "Redis 未启用，不能调整投放库存缓存");
    }

    @Override
    public void deleteAllocation(long inventoryId) {
        throw new BizException(ErrorCode.SYSTEM, "Redis 未启用，不能删除投放库存缓存");
    }
}
