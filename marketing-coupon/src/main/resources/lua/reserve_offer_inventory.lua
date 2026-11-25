-- KEYS[1] offer:plan:{planId}:inventory (string counter)
-- KEYS[2] offer:plan:{planId}:quota:{userId}
-- KEYS[3] offer:plan:{planId}:ticket:{ticketId}
-- ARGV[1] 每人限领上限
-- ARGV[2] 过期秒数
-- ARGV[3] 缓存未初始化时的数据库兜底库存
local existingTicket = redis.call('GET', KEYS[3])
if existingTicket then
    return {'reserved', tonumber(existingTicket)}
end
local remaining = redis.call('GET', KEYS[1])
if not remaining then
    if tonumber(ARGV[2]) > 0 then
        redis.call('SET', KEYS[1], ARGV[3], 'EX', ARGV[2])
    else
        redis.call('SET', KEYS[1], ARGV[3])
    end
    remaining = ARGV[3]
end
remaining = tonumber(remaining)
if remaining == nil or remaining <= 0 then
    return {'sold_out', 0}
end
local limit = tonumber(ARGV[1])
local claimed = tonumber(redis.call('GET', KEYS[2]) or '0')
if claimed >= limit then
    return {'quota_exceeded', claimed}
end
local nextClaimed = redis.call('INCR', KEYS[2])
if nextClaimed == 1 and tonumber(ARGV[2]) > 0 then
    redis.call('EXPIRE', KEYS[2], ARGV[2])
end
redis.call('DECR', KEYS[1])
if tonumber(ARGV[2]) > 0 then
    redis.call('SET', KEYS[3], nextClaimed, 'EX', ARGV[2])
else
    redis.call('SET', KEYS[3], nextClaimed)
end
return {'reserved', nextClaimed}
