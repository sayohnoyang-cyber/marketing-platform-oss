-- KEYS[1] offer:plan:{planId}:inventory
-- KEYS[2] offer:plan:{planId}:quota:{userId}
-- KEYS[3] offer:ticket:{ticketId}
-- ARGV[1] 本次预扣序号，仅当票据匹配时回补库存
local ticket = redis.call('GET', KEYS[3])
local expected = ARGV[1]
if not ticket or tostring(ticket) ~= tostring(expected) then
    return 0
end
local claimed = tonumber(redis.call('GET', KEYS[2]) or '0')
if claimed > 0 then
    redis.call('DECR', KEYS[2])
end
if redis.call('EXISTS', KEYS[1]) == 1 then
    redis.call('INCR', KEYS[1])
end
redis.call('DEL', KEYS[3])
return 1
