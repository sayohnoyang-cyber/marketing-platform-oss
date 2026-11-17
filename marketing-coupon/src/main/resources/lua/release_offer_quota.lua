-- KEYS[1] offer:inventory:{inventoryId}
-- KEYS[2] offer:inventory:{inventoryId}:quota:{userId}
-- KEYS[3] offer:plan:{planId}:quota:{userId}
-- KEYS[4] offer:ticket:inv:{inventoryId}:{claimKey}
-- ARGV[1] 方案序号
-- ARGV[2] 本场序号
local ticket = redis.call('GET', KEYS[4])
local expected = ARGV[1] .. ':' .. ARGV[2]
if not ticket or tostring(ticket) ~= tostring(expected) then
    return 0
end
local planClaimed = tonumber(redis.call('GET', KEYS[3]) or '0')
if planClaimed == tonumber(ARGV[1]) then
    redis.call('DECR', KEYS[3])
end
local invClaimed = tonumber(redis.call('GET', KEYS[2]) or '0')
if invClaimed == tonumber(ARGV[2]) then
    redis.call('DECR', KEYS[2])
end
redis.call('INCR', KEYS[1])
redis.call('DEL', KEYS[4])
return 1
