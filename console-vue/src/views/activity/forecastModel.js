export const scenarios = [
  { key: 'low', label: '保守场景', multiplier: 1 },
  { key: 'normal', label: '常规场景', multiplier: 1.8 },
  { key: 'high', label: '集中场景', multiplier: 2.8 }
]

export function estimateForecast(channels, growth, attempts, windowSeconds = 10, unreservedQps = 0) {
  if (!Number.isFinite(unreservedQps) || unreservedQps < 0) throw new Error('未预约流量参数不合法')
  if (!Number.isFinite(windowSeconds) || windowSeconds <= 0 || !Number.isFinite(growth) || growth < 0 || !Number.isFinite(attempts) || attempts < 0) throw new Error('预测参数不合法')
  const factor = 1 + growth / 100
  let reservations = 0, arrivals = 0
  const rows = channels.map(channel => {
    const count = Number(channel.reservations)
    const rate = Number(channel.rate)
    if (!Number.isFinite(count) || count < 0 || !Number.isFinite(rate) || rate < 0 || rate > 100) throw new Error('渠道预测参数不合法')
    const expected = channel.enabled === false ? 0 : count * factor * rate / 100
    reservations += count * factor; arrivals += expected
    return { ...channel, expected }
  })
  const average = arrivals * attempts / windowSeconds
  return { reservations, arrivals, average, rows: rows.map(row => ({ ...row, share: arrivals ? row.expected / arrivals : 0 })), scenarios: scenarios.map(item => ({ ...item, qps: Math.round(average * item.multiplier + unreservedQps) })) }
}
