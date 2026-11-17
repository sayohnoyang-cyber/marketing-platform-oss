package com.sayohno.marketing.coupon.stock;

public interface OfferStockGateway {
    StockDecision tryAcquire(long templateId, long userId, int limit, int dbStock, long ttlSeconds, long claimRequestId);

    /**
     * @return 1 已补偿并删除预扣标记；0 预扣标记已不存在，不能再次加库存
     */
    int compensate(long templateId, long userId, int claimSeq, long claimRequestId);

    Integer readHold(long claimRequestId);

    void releaseHold(long claimRequestId);

    void increaseIfPresent(long templateId, int delta);

    StockDecision tryAcquireAllocation(long inventoryId, long templateId, long userId, int allocationLimit,
                                       int templateLimit, int dbRemaining, long ttlSeconds, long claimRequestId);

    StockDecision tryAcquireAllocationByKey(long inventoryId, long templateId, long userId, int allocationLimit,
                                            int templateLimit, int dbRemaining, long ttlSeconds, String claimKey);

    int compensateAllocation(long inventoryId, long templateId, long userId, int templateCount, int allocCount, long claimRequestId);

    int compensateAllocationByKey(long inventoryId, long templateId, long userId, int templateCount, int allocCount, String claimKey);

    HoldMark readAllocationHold(long inventoryId, long claimRequestId);

    void releaseAllocationHold(long inventoryId, long claimRequestId);

    void releaseAllocationHoldByKey(long inventoryId, String claimKey);

    void increaseAllocationIfPresent(long inventoryId, int delta);

    void decreaseAllocationIfPresent(long inventoryId, int delta);

    void deleteAllocation(long inventoryId);
}
