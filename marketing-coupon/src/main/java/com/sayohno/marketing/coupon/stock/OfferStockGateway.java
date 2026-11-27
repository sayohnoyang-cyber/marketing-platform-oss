package com.sayohno.marketing.coupon.stock;

public interface OfferStockGateway {
    StockDecision tryAcquire(long templateId, long userId, int limit, int dbStock, long ttlSeconds, long claimRequestId);

    /**
     * @return 1 已补偿并删除预扣标记；0 预扣标记已不存在，不能再次加库存
     */
    int compensate(long templateId, long userId, int claimSeq, long claimRequestId);

    Integer readHold(long templateId, long claimRequestId);

    void releaseHold(long templateId, long claimRequestId);

    void invalidatePlanStock(long templateId);

    StockDecision tryAcquireAllocation(long inventoryId, long templateId, long userId, int allocationLimit,
                                       int templateLimit, int dbRemaining, long ttlSeconds, long claimRequestId);

    StockDecision tryAcquireAllocationByKey(long inventoryId, long templateId, long userId, int allocationLimit,
                                            int templateLimit, int dbRemaining, long ttlSeconds, String claimKey);

    int compensateAllocation(long inventoryId, long templateId, long userId, int templateCount, int allocCount, long claimRequestId);

    int compensateAllocationByKey(long inventoryId, long templateId, long userId, int templateCount, int allocCount, String claimKey);

    HoldMark readAllocationHold(long templateId, long inventoryId, long claimRequestId);

    void releaseAllocationHold(long templateId, long inventoryId, long claimRequestId);

    void releaseAllocationHoldByKey(long templateId, long inventoryId, String claimKey);

    void increaseAllocationIfPresent(long templateId, long inventoryId, int delta);

    void decreaseAllocationIfPresent(long templateId, long inventoryId, int delta);

    void deleteAllocation(long templateId, long inventoryId);

    /**
     * 判断投放库存缓存键是否已初始化；缓存已预热时可跳过昂贵的在途票据 COUNT(*) 兜底查询。
     */
    default boolean hasAllocationStock(long templateId, long inventoryId) {
        return false;
    }

    /**
     * 读取投放缓存中的实时剩余库存；未启用缓存或尚未初始化时返回 null，避免外部模块硬编码拼接库存 Redis Key。
     */
    default Integer readAllocationRemaining(long templateId, long inventoryId) {
        return null;
    }
}
