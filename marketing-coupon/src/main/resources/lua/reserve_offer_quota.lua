-- KEYS[1] offer:plan:{planId}:inventory:{inventoryId} (string counter)
-- KEYS[2] offer:plan:{planId}:inventory:{inventoryId}:quota:{userId}
-- KEYS[3] offer:plan:{planId}:quota:{userId}
-- KEYS[4] offer:plan:{planId}:inventory:{inventoryId}:ticket:{claimKey}
-- ARGV[1] 本场限领上限
-- ARGV[2] 方案总限领上限
-- ARGV[3] 过期秒数
-- ARGV[4] 缓存未初始化时的数据库兜底剩余
local existingTicket = redis.call('GET', KEYS[4])
if existingTicket then
    local planSeq, invSeq = string.match(existingTicket, '^(%d+):(%d+)$')
    return {'reserved', tonumber(planSeq), tonumber(invSeq)}
end
local remaining = redis.call('GET', KEYS[1])
if not remaining then
    -- 未提供数据库余量时通知调用方回源，避免将尚未初始化的库存写成零。
    if tonumber(ARGV[4]) < 0 then
        return {'needs_stock', 0, 0}
    end
    if tonumber(ARGV[3]) > 0 then
        redis.call('SET', KEYS[1], ARGV[4], 'EX', ARGV[3])
    else
        redis.call('SET', KEYS[1], ARGV[4])
    end
    remaining = ARGV[4]
end
remaining = tonumber(remaining)
if remaining == nil or remaining <= 0 then
    return {'sold_out', 0, 0}
end
local invLimit = tonumber(ARGV[1])
local planLimit = tonumber(ARGV[2])
local invClaimed = tonumber(redis.call('GET', KEYS[2]) or '0')
local planClaimed = tonumber(redis.call('GET', KEYS[3]) or '0')
if invClaimed >= invLimit or planClaimed >= planLimit then
    return {'quota_exceeded', planClaimed, invClaimed}
end
local planNext = redis.call('INCR', KEYS[3])
local invNext = redis.call('INCR', KEYS[2])
if planNext == 1 and tonumber(ARGV[3]) > 0 then
    redis.call('EXPIRE', KEYS[3], ARGV[3])
end
if invNext == 1 and tonumber(ARGV[3]) > 0 then
    redis.call('EXPIRE', KEYS[2], ARGV[3])
end
redis.call('DECR', KEYS[1])
local marker = planNext .. ':' .. invNext
if tonumber(ARGV[3]) > 0 then
    redis.call('SET', KEYS[4], marker, 'EX', ARGV[3])
else
    redis.call('SET', KEYS[4], marker)
end
return {'reserved', planNext, invNext}
