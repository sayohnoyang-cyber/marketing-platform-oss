package com.sayohno.marketing.coupon.stock;

import com.sayohno.marketing.common.exception.BizException;
import com.sayohno.marketing.common.result.ErrorCode;
import com.sayohno.marketing.coupon.redis.OfferInventoryKeys;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@ConditionalOnProperty(prefix = "marketing.redis", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RedisOfferStockGateway implements OfferStockGateway {
    private final StringRedisTemplate redis;
    private final DefaultRedisScript<List> acquireScript;
    private final DefaultRedisScript<Long> compensateScript;
    private final DefaultRedisScript<List> allocationAcquireScript;
    private final DefaultRedisScript<Long> allocationCompensateScript;
    private final DefaultRedisScript<Long> allocationDecreaseScript;
    private final DefaultRedisScript<Long> stockIncreaseScript;

    public RedisOfferStockGateway(StringRedisTemplate redis) {
        this.redis = redis;
        this.acquireScript = new DefaultRedisScript<>();
        this.acquireScript.setLocation(new ClassPathResource("lua/reserve_offer_inventory.lua"));
        this.acquireScript.setResultType(List.class);
        this.compensateScript = new DefaultRedisScript<>();
        this.compensateScript.setLocation(new ClassPathResource("lua/release_offer_inventory.lua"));
        this.compensateScript.setResultType(Long.class);
        this.allocationAcquireScript = new DefaultRedisScript<>();
        this.allocationAcquireScript.setLocation(new ClassPathResource("lua/reserve_offer_quota.lua"));
        this.allocationAcquireScript.setResultType(List.class);
        this.allocationCompensateScript = new DefaultRedisScript<>();
        this.allocationCompensateScript.setLocation(new ClassPathResource("lua/release_offer_quota.lua"));
        this.allocationCompensateScript.setResultType(Long.class);
        this.allocationDecreaseScript = new DefaultRedisScript<>();
        this.allocationDecreaseScript.setLocation(new ClassPathResource("lua/trim_offer_quota.lua"));
        this.allocationDecreaseScript.setResultType(Long.class);
        this.stockIncreaseScript = new DefaultRedisScript<>();
        this.stockIncreaseScript.setLocation(new ClassPathResource("lua/increase_offer_stock.lua"));
        this.stockIncreaseScript.setResultType(Long.class);
    }

    @Override
    public StockDecision tryAcquire(long templateId, long userId, int limit, int dbStock, long ttlSeconds, long claimRequestId) {
        List<?> result = redis.execute(acquireScript,
                List.of(OfferInventoryKeys.planInventory(templateId),
                        OfferInventoryKeys.planQuota(userId, templateId),
                        OfferInventoryKeys.ticket(templateId, claimRequestId)),
                Integer.toString(limit), Long.toString(ttlSeconds), Integer.toString(dbStock));
        if (result == null || result.size() < 2) {
            throw new BizException(ErrorCode.SYSTEM, "库存脚本没有返回结果");
        }
        return new StockDecision(String.valueOf(result.get(0)), number(result.get(1)));
    }

    @Override
    public int compensate(long templateId, long userId, int claimSeq, long claimRequestId) {
        Long result = redis.execute(compensateScript,
                List.of(OfferInventoryKeys.planInventory(templateId),
                        OfferInventoryKeys.planQuota(userId, templateId),
                        OfferInventoryKeys.ticket(templateId, claimRequestId)),
                Integer.toString(claimSeq));
        if (result == null) {
            throw new BizException(ErrorCode.SYSTEM, "库存补偿脚本没有返回结果");
        }
        return result.intValue();
    }

    @Override
    public Integer readHold(long templateId, long claimRequestId) {
        String value = redis.opsForValue().get(OfferInventoryKeys.ticket(templateId, claimRequestId));
        return value == null ? null : Integer.valueOf(value);
    }

    @Override
    public void releaseHold(long templateId, long claimRequestId) {
        redis.delete(OfferInventoryKeys.ticket(templateId, claimRequestId));
    }

    @Override
    public void invalidatePlanStock(long templateId) {
        redis.delete(OfferInventoryKeys.planInventory(templateId));
    }

    @Override
    public StockDecision tryAcquireAllocation(long inventoryId, long templateId, long userId, int allocationLimit,
                                              int templateLimit, int dbRemaining, long ttlSeconds, long claimRequestId) {
        return tryAcquireAllocationByKey(inventoryId, templateId, userId, allocationLimit,
                templateLimit, dbRemaining, ttlSeconds, Long.toString(claimRequestId));
    }

    @Override
    public StockDecision tryAcquireAllocationByKey(long inventoryId, long templateId, long userId, int allocationLimit,
                                                   int templateLimit, int dbRemaining, long ttlSeconds, String claimKey) {
        List<?> result = redis.execute(allocationAcquireScript, List.of(
                OfferInventoryKeys.inventory(templateId, inventoryId),
                OfferInventoryKeys.inventoryQuota(templateId, userId, inventoryId),
                OfferInventoryKeys.planQuota(userId, templateId),
                OfferInventoryKeys.inventoryTicketByKey(templateId, inventoryId, claimKey)),
                Integer.toString(allocationLimit), Integer.toString(templateLimit),
                Long.toString(ttlSeconds), Integer.toString(dbRemaining));
        if (result == null || result.size() < 3) {
            throw new BizException(ErrorCode.SYSTEM, "投放库存脚本没有返回结果");
        }
        return new StockDecision(String.valueOf(result.get(0)), number(result.get(1)), number(result.get(2)));
    }

    @Override
    public int compensateAllocation(long inventoryId, long templateId, long userId, int templateCount, int allocCount, long claimRequestId) {
        return compensateAllocationByKey(inventoryId, templateId, userId, templateCount, allocCount, Long.toString(claimRequestId));
    }

    @Override
    public int compensateAllocationByKey(long inventoryId, long templateId, long userId, int templateCount, int allocCount, String claimKey) {
        Long result = redis.execute(allocationCompensateScript, List.of(
                OfferInventoryKeys.inventory(templateId, inventoryId),
                OfferInventoryKeys.inventoryQuota(templateId, userId, inventoryId),
                OfferInventoryKeys.planQuota(userId, templateId),
                OfferInventoryKeys.inventoryTicketByKey(templateId, inventoryId, claimKey)),
                Integer.toString(templateCount), Integer.toString(allocCount));
        if (result == null) {
            throw new BizException(ErrorCode.SYSTEM, "投放补偿脚本没有返回结果");
        }
        return result.intValue();
    }

    @Override
    public HoldMark readAllocationHold(long templateId, long inventoryId, long claimRequestId) {
        String value = redis.opsForValue().get(OfferInventoryKeys.inventoryTicket(templateId, inventoryId, claimRequestId));
        return value == null ? null : HoldMark.parse(value);
    }

    @Override
    public void releaseAllocationHold(long templateId, long inventoryId, long claimRequestId) {
        releaseAllocationHoldByKey(templateId, inventoryId, Long.toString(claimRequestId));
    }

    @Override
    public void releaseAllocationHoldByKey(long templateId, long inventoryId, String claimKey) {
        redis.delete(OfferInventoryKeys.inventoryTicketByKey(templateId, inventoryId, claimKey));
    }

    @Override
    public void increaseAllocationIfPresent(long templateId, long inventoryId, int delta) {
        if (delta <= 0) {
            return;
        }
        // 使用 Lua 在 Redis 单线程内原子校验存在并递增，防止键过期瞬间创建出无 TTL 的错账库存键。
        redis.execute(stockIncreaseScript, List.of(OfferInventoryKeys.inventory(templateId, inventoryId)), Integer.toString(delta));
    }

    @Override
    public void decreaseAllocationIfPresent(long templateId, long inventoryId, int delta) {
        if (delta <= 0) {
            return;
        }
        String key = OfferInventoryKeys.inventory(templateId, inventoryId);
        // 通过 Lua 脚本在 Redis 单线程内原子校验并扣减，防止并发下读-改-写导致缓存库存变为负数。
        Long result = redis.execute(allocationDecreaseScript, List.of(key), Integer.toString(delta));
        if (result == null) {
            throw new BizException(ErrorCode.SYSTEM, "投放库存调减脚本没有返回结果");
        }
        if (result == 0L) {
            throw new BizException(ErrorCode.CONFLICT, "投放缓存库存不足，不能减少");
        }
    }

    @Override
    public void deleteAllocation(long templateId, long inventoryId) {
        redis.delete(OfferInventoryKeys.inventory(templateId, inventoryId));
    }

    @Override
    public boolean hasAllocationStock(long templateId, long inventoryId) {
        return Boolean.TRUE.equals(redis.hasKey(OfferInventoryKeys.inventory(templateId, inventoryId)));
    }

    @Override
    public Integer readAllocationRemaining(long templateId, long inventoryId) {
        String raw = redis.opsForValue().get(OfferInventoryKeys.inventory(templateId, inventoryId));
        return raw == null ? null : Math.max(0, Integer.parseInt(raw));
    }

    private static int number(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        return Integer.parseInt(String.valueOf(value));
    }
}
