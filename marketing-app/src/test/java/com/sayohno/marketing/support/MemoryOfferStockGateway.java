package com.sayohno.marketing.support;

import com.sayohno.marketing.coupon.stock.OfferStockGateway;
import com.sayohno.marketing.coupon.stock.HoldMark;
import com.sayohno.marketing.coupon.stock.StockDecision;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 单进程测试用的库存闸门，语义对齐领券 Lua。生产配置不会注册它。
 */
public class MemoryOfferStockGateway implements OfferStockGateway {
    private final Map<Long, State> states = new ConcurrentHashMap<>();
    private final Map<Long, Integer> holds = new ConcurrentHashMap<>();
    private final Map<Long, Map<Long, Integer>> templateUsers = new ConcurrentHashMap<>();
    private final Map<Long, State> allocations = new ConcurrentHashMap<>();
    private final Map<String, HoldMark> allocationHolds = new ConcurrentHashMap<>();

    @Override
    public synchronized StockDecision tryAcquire(long templateId, long userId, int limit, int dbStock, long ttlSeconds, long claimRequestId) {
        Integer held = holds.get(claimRequestId);
        if (held != null) {
            return new StockDecision(StockDecision.RESERVED, held);
        }
        State state = states.computeIfAbsent(templateId, ignored -> new State(dbStock));
        if (state.stock <= 0) {
            return new StockDecision(StockDecision.SOLD_OUT, 0);
        }
        int current = templateCount(templateId, userId);
        if (current >= limit) {
            return new StockDecision(StockDecision.QUOTA_EXCEEDED, current);
        }
        int next = current + 1;
        templateUsers(templateId).put(userId, next);
        state.stock--;
        holds.put(claimRequestId, next);
        return new StockDecision(StockDecision.RESERVED, next);
    }

    @Override
    public synchronized int compensate(long templateId, long userId, int claimSeq, long claimRequestId) {
        Integer held = holds.get(claimRequestId);
        if (held == null || held != claimSeq) {
            return 0;
        }
        State state = states.get(templateId);
        if (state != null) {
            Map<Long, Integer> counts = templateUsers.get(templateId);
            if (counts != null && counts.getOrDefault(userId, 0) == claimSeq) {
                counts.put(userId, claimSeq - 1);
            }
            state.stock++;
        }
        holds.remove(claimRequestId);
        return 1;
    }

    @Override
    public synchronized Integer readHold(long claimRequestId) {
        return holds.get(claimRequestId);
    }

    @Override
    public synchronized void releaseHold(long claimRequestId) {
        holds.remove(claimRequestId);
    }

    @Override
    public synchronized void increaseIfPresent(long templateId, int delta) {
        State state = states.get(templateId);
        if (state != null) {
            state.stock += delta;
        }
    }

    @Override
    public synchronized StockDecision tryAcquireAllocation(long inventoryId, long templateId, long userId, int allocationLimit,
                                                           int templateLimit, int dbRemaining, long ttlSeconds, long claimRequestId) {
        return tryAcquireAllocationByKey(inventoryId, templateId, userId, allocationLimit, templateLimit, dbRemaining,
                ttlSeconds, Long.toString(claimRequestId));
    }

    @Override
    public synchronized StockDecision tryAcquireAllocationByKey(long inventoryId, long templateId, long userId, int allocationLimit,
                                                                int templateLimit, int dbRemaining, long ttlSeconds, String claimKey) {
        String key = inventoryId + ":" + claimKey;
        HoldMark held = allocationHolds.get(key);
        if (held != null) {
            return new StockDecision(StockDecision.RESERVED, held.templateCount(), held.allocCount());
        }
        State state = allocations.computeIfAbsent(inventoryId, ignored -> new State(dbRemaining));
        int templateCurrent = templateCount(templateId, userId);
        int allocCurrent = state.counts.getOrDefault(userId, 0);
        if (allocCurrent >= allocationLimit || templateCurrent >= templateLimit) {
            return new StockDecision(StockDecision.QUOTA_EXCEEDED, templateCurrent, allocCurrent);
        }
        if (state.stock <= 0) {
            return new StockDecision(StockDecision.SOLD_OUT, 0, 0);
        }
        int templateNext = templateCurrent + 1;
        int allocNext = allocCurrent + 1;
        templateUsers(templateId).put(userId, templateNext);
        state.counts.put(userId, allocNext);
        state.stock--;
        allocationHolds.put(key, new HoldMark(templateNext, allocNext));
        return new StockDecision(StockDecision.RESERVED, templateNext, allocNext);
    }

    @Override
    public synchronized int compensateAllocation(long inventoryId, long templateId, long userId, int templateCount, int allocCount, long claimRequestId) {
        return compensateAllocationByKey(inventoryId, templateId, userId, templateCount, allocCount, Long.toString(claimRequestId));
    }

    @Override
    public synchronized int compensateAllocationByKey(long inventoryId, long templateId, long userId, int templateCount, int allocCount, String claimKey) {
        String key = inventoryId + ":" + claimKey;
        HoldMark held = allocationHolds.get(key);
        if (held == null || held.templateCount() != templateCount || held.allocCount() != allocCount) {
            return 0;
        }
        Map<Long, Integer> template = templateUsers.get(templateId);
        if (template != null && template.getOrDefault(userId, 0) == templateCount) {
            template.put(userId, templateCount - 1);
        }
        State state = allocations.get(inventoryId);
        if (state != null) {
            if (state.counts.getOrDefault(userId, 0) == allocCount) {
                state.counts.put(userId, allocCount - 1);
            }
            state.stock++;
        }
        allocationHolds.remove(key);
        return 1;
    }

    @Override
    public synchronized HoldMark readAllocationHold(long inventoryId, long claimRequestId) {
        return allocationHolds.get(holdKey(inventoryId, claimRequestId));
    }

    @Override
    public synchronized void releaseAllocationHold(long inventoryId, long claimRequestId) {
        releaseAllocationHoldByKey(inventoryId, Long.toString(claimRequestId));
    }

    @Override
    public synchronized void releaseAllocationHoldByKey(long inventoryId, String claimKey) {
        allocationHolds.remove(inventoryId + ":" + claimKey);
    }

    @Override
    public synchronized void increaseAllocationIfPresent(long inventoryId, int delta) {
        State state = allocations.get(inventoryId);
        if (state != null) {
            state.stock += delta;
        }
    }

    @Override
    public synchronized void decreaseAllocationIfPresent(long inventoryId, int delta) {
        State state = allocations.get(inventoryId);
        if (state == null) {
            return;
        }
        if (state.stock < delta) {
            throw new IllegalStateException("投放缓存库存不足");
        }
        state.stock -= delta;
    }

    @Override
    public synchronized void deleteAllocation(long inventoryId) {
        allocations.remove(inventoryId);
    }

    private int templateCount(long templateId, long userId) {
        return templateUsers.getOrDefault(templateId, Map.of()).getOrDefault(userId, 0);
    }

    private Map<Long, Integer> templateUsers(long templateId) {
        return templateUsers.computeIfAbsent(templateId, ignored -> new ConcurrentHashMap<>());
    }

    private static String holdKey(long inventoryId, long claimRequestId) {
        return inventoryId + ":" + claimRequestId;
    }

    private static final class State {
        private int stock;
        private final Map<Long, Integer> counts = new ConcurrentHashMap<>();

        private State(int stock) {
            this.stock = stock;
        }
    }
}
