-- 仅校验并递增单用户滑动窗口频控桶（在任何数据库查询之前执行，用于前置拦截恶意刷单流量）。
-- KEYS[1] per-user bucket
-- ARGV[1] perUserQps (> 0)
local userLimit = tonumber(ARGV[1] or '0')
if userLimit <= 0 then
    return 1
end

local t = redis.call('TIME')
local nowSec = tonumber(t[1])
local prevSec = nowSec - 1
local weight = 1.0 - (tonumber(t[2]) / 1000000.0)

local curSec = tonumber(redis.call('HGET', KEYS[1], 'cur_sec') or '0')
local curCnt = tonumber(redis.call('HGET', KEYS[1], 'cur_cnt') or '0')
local pSec = tonumber(redis.call('HGET', KEYS[1], 'prev_sec') or '0')
local pCnt = tonumber(redis.call('HGET', KEYS[1], 'prev_cnt') or '0')

local effCur = 0
local effPrev = 0
local eff = 0
if curSec == nowSec then
    effCur = curCnt
    effPrev = (pSec == prevSec) and pCnt or 0
    eff = effCur + effPrev * weight
elseif curSec == prevSec then
    effCur = 0
    effPrev = curCnt
    eff = effPrev * weight
end

if eff >= userLimit then
    return 0
end

redis.call('HSET', KEYS[1], 'cur_sec', nowSec, 'cur_cnt', effCur + 1, 'prev_sec', prevSec, 'prev_cnt', effPrev)
redis.call('EXPIRE', KEYS[1], 3)
return 1
