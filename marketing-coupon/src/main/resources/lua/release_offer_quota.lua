-- KEYS[1] offer:plan:{planId}:inventory:{inventoryId}
-- KEYS[2] offer:plan:{planId}:inventory:{inventoryId}:quota:{userId}
-- KEYS[3] offer:plan:{planId}:quota:{userId}
-- KEYS[4] offer:plan:{planId}:inventory:{inventoryId}:ticket:{claimKey}
-- ARGV[1] 方案序号
-- ARGV[2] 本场序号
local ticket = redis.call('GET', KEYS[4])
local expected = ARGV[1] .. ':' .. ARGV[2]
if not ticket or tostring(ticket) ~= tostring(expected) then
    return 0
end
-- 票据已保证单次预扣只回补一次；并发多张乱序失败时直接安全递减，避免限领额度永久泄漏。
local planClaimed = tonumber(redis.call('GET', KEYS[3]) or '0')
if planClaimed > 0 then
    redis.call('DECR', KEYS[3])
end
local invClaimed = tonumber(redis.call('GET', KEYS[2]) or '0')
if invClaimed > 0 then
    redis.call('DECR', KEYS[2])
end
-- 仅当库存键仍存在时回补，避免投放冻结删除后重新创建值为 1 的幽灵库存键。
if redis.call('EXISTS', KEYS[1]) == 1 then
    redis.call('INCR', KEYS[1])
end
redis.call('DEL', KEYS[4])
return 1
