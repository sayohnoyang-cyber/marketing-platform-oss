-- Atomic sliding-window rate limiter for per-user, activity-total, and source budgets using Redis TIME.
-- KEYS[1] activity total bucket
-- KEYS[2] source bucket
-- KEYS[3] per-user bucket
-- ARGV[1] totalQps
-- ARGV[2] sourceQps
-- ARGV[3] perUserQps (0 disables per-user check)
local t = redis.call('TIME')
local nowSec = tonumber(t[1])
local prevSec = nowSec - 1
local weight = 1.0 - (tonumber(t[2]) / 1000000.0)

local function readBucket(key)
    local curSec = tonumber(redis.call('HGET', key, 'cur_sec') or '0')
    local curCnt = tonumber(redis.call('HGET', key, 'cur_cnt') or '0')
    local pSec = tonumber(redis.call('HGET', key, 'prev_sec') or '0')
    local pCnt = tonumber(redis.call('HGET', key, 'prev_cnt') or '0')
    if curSec == nowSec then
        local effectivePrev = (pSec == prevSec) and pCnt or 0
        return curCnt, effectivePrev, curCnt + effectivePrev * weight
    elseif curSec == prevSec then
        return 0, curCnt, curCnt * weight
    else
        return 0, 0, 0
    end
end

local function incrBucket(key, curCnt, prevCnt)
    redis.call('HSET', key, 'cur_sec', nowSec, 'cur_cnt', curCnt + 1, 'prev_sec', prevSec, 'prev_cnt', prevCnt)
    redis.call('EXPIRE', key, 3)
end

local userLimit = tonumber(ARGV[3] or '0')
local uCur, uPrev, uEff = 0, 0, 0
if userLimit > 0 and KEYS[3] and KEYS[3] ~= '' then
    uCur, uPrev, uEff = readBucket(KEYS[3])
    -- 先检查单用户桶，避免单个恶意脚本打满活动总额度与来源额度
    if uEff >= userLimit then
        return 0
    end
end

local totalLimit = tonumber(ARGV[1])
local sourceLimit = tonumber(ARGV[2])
local tCur, tPrev, tEff = readBucket(KEYS[1])
local sCur, sPrev, sEff = readBucket(KEYS[2])
if tEff >= totalLimit or sEff >= sourceLimit then
    return 0
end

if userLimit > 0 and KEYS[3] and KEYS[3] ~= '' then
    incrBucket(KEYS[3], uCur, uPrev)
end
incrBucket(KEYS[1], tCur, tPrev)
incrBucket(KEYS[2], sCur, sPrev)
return 1
