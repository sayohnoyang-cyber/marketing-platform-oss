export function refreshDelay(entry) {
  if (['ENDED', 'CANCELLED'].includes(entry?.phase)) return null
  // 以服务端时间计算边界，手机时间不准确也能及时切换；普通轮询错峰执行。
  const now = Date.parse(entry?.serverTime)
  const boundary = Date.parse(entry?.phase === 'WARMUP' ? entry.startTime : entry?.endTime)
  const normal = 15000 + Math.floor(Math.random() * 3000)
  return Number.isFinite(boundary - now) && boundary > now ? Math.min(normal, Math.max(500, boundary - now + 150)) : normal
}

export function personalState(entry) {
  const claimed = Math.max(0, Number(entry?.personalClaimed || 0))
  return { reserved: entry?.personalReserved === true, claimed, remaining: Math.max(0, Number(entry?.maxClaimsPerUser || 1) - claimed) }
}
