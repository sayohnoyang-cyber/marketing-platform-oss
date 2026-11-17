-- Global and source budgets are checked and charged atomically using Redis time.
local now = redis.call('TIME')[1]
local total = 0
local source = 0
if redis.call('HGET', KEYS[1], 'second') == now then total = tonumber(redis.call('HGET', KEYS[1], 'count') or '0') end
if redis.call('HGET', KEYS[2], 'second') == now then source = tonumber(redis.call('HGET', KEYS[2], 'count') or '0') end
if total >= tonumber(ARGV[1]) or source >= tonumber(ARGV[2]) then return 0 end
redis.call('HSET', KEYS[1], 'second', now, 'count', total + 1)
redis.call('HSET', KEYS[2], 'second', now, 'count', source + 1)
redis.call('EXPIRE', KEYS[1], 2)
redis.call('EXPIRE', KEYS[2], 2)
return 1
