-- 原子追加已有库存缓存键：仅当键存在时递增，避免 hasKey + increment 竞态在键过期瞬间创建出无 TTL 的错误库存键。
-- KEYS[1] offer:plan:{planId}:inventory 或 offer:plan:{planId}:inventory:{inventoryId}
-- ARGV[1] 增加数量 delta (> 0)
-- 返回：1=缓存存在并已递增，0=缓存不存在未操作
if redis.call('EXISTS', KEYS[1]) == 1 then
    redis.call('INCRBY', KEYS[1], tonumber(ARGV[1]))
    return 1
end
return 0
