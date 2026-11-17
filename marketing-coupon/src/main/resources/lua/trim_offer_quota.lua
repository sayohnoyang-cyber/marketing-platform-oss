-- 原子校验并调减投放缓存库存，避免并发扣成负数。
-- KEYS[1] offer:inventory:{inventoryId}
-- ARGV[1] 调减数量 delta (> 0)
-- 返回：1=缓存不存在或调减成功，0=缓存库存不足
local remaining = redis.call('GET', KEYS[1])
if not remaining then
    return 1
end
local current = tonumber(remaining)
local delta = tonumber(ARGV[1])
if not current or current < delta then
    return 0
end
redis.call('DECRBY', KEYS[1], delta)
return 1
