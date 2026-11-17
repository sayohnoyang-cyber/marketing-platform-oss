export const phases = {
  DRAFT: '草稿',
  WARMUP: '预热中',
  LIVE: '进行中',
  SOLDOUT: '已领完',
  ENDED: '已结束',
  PAUSED: '已暂停',
  OFFLINE: '已下线',
  CANCELLED: '已终止'
}

export const entryStates = {
  DRAFT: '草稿',
  RESERVE: '可预约',
  CLAIMABLE: '可领取',
  SOLD_OUT: '已领完',
  ENDED: '已结束',
  UNAVAILABLE: '不可用'
}

export function phaseText(phase) {
  return phases[phase] || phase || ''
}

export function displayPhase(item) {
  if (item?.phase === 'LIVE' && item.quantity != null && Number(item.quantity) - Number(item.claimed || 0) <= 0) {
    return 'SOLDOUT'
  }
  return item?.phase
}

export function stepFor(item) {
  const phase = displayPhase(item)
  if (phase === 'DRAFT') return 'pick'
  if (phase === 'LIVE' || phase === 'SOLDOUT') return 'live'
  if (phase === 'ENDED' || phase === 'CANCELLED') return 'review'
  if (phase === 'OFFLINE') return 'prepare'
  return 'prepare'
}

export function activityPath(item, step) {
  if (!item?.id) return '/activities'
  return `/activities/${item.id}/${step || stepFor(item)}`
}

export function leftOf(item) {
  if (item?.quantity == null) return null
  return Math.max(0, Number(item.quantity) - Number(item.claimed || 0))
}

export function formatNumber(value) {
  if (value == null || value === '') return '—'
  return Number(value).toLocaleString('zh-CN')
}

export function dayRange(item) {
  return `${shortDay(item?.startTime)} — ${shortDay(item?.endTime)}`
}

export function clockLabel(value) {
  if (!value) return '未设置'
  return String(value).replace(/(\d{2}:\d{2}:\d{2})\.\d+/, '$1').replace('T', ' ').replaceAll('-', '.')
}

function shortDay(value) {
  if (!value) return '—'
  const date = new Date(String(value).replace(' ', 'T'))
  if (Number.isNaN(date.getTime())) return '—'
  return `${date.getMonth() + 1}.${String(date.getDate()).padStart(2, '0')}`
}

export function toLocalInput(value) {
  if (!value) return ''
  return String(value).replace(' ', 'T').slice(0, 16)
}

export function fromLocalInput(value) {
  if (!value) return ''
  return value.length === 16 ? `${value}:00` : value
}

export function moneyTimes(amount, quantity) {
  const text = String(amount ?? '')
  if (!/^\d+(\.\d{1,2})?$/.test(text)) return null
  const [whole, fraction = ''] = text.split('.')
  const cents = Number(whole) * 100 + Number((fraction + '00').slice(0, 2))
  const total = cents * Number(quantity || 0)
  return `${Math.floor(total / 100)}.${String(total % 100).padStart(2, '0')}`
}

export function ruleAmount(rule) {
  try {
    const parsed = typeof rule === 'string' ? JSON.parse(rule || '{}') : (rule || {})
    return parsed.capAmount || ''
  } catch {
    return ''
  }
}

export function couponRuleText(rule) {
  try {
    const value = typeof rule === 'string' ? JSON.parse(rule || '{}') : (rule || {})
    if (value.percentOff != null) return `${Number(((1 - Number(value.percentOff)) * 10).toFixed(2))} 折 · 最高优惠 ${value.capAmount || '—'} 元`
    if (Number(value.minSpend) > 0) return `满 ${value.minSpend} 减 ${value.capAmount || '—'}`
    if (value.capAmount) return `立减 ${value.capAmount} 元`
  } catch { /* Fall through to the empty rule label. */ }
  return '优惠券待设置'
}

export function couponFace(rule) {
  try {
    const value = typeof rule === 'string' ? JSON.parse(rule || '{}') : (rule || {})
    if (value.percentOff != null) return `${Number(((1 - Number(value.percentOff)) * 10).toFixed(2))} 折`
    if (value.capAmount) return `¥${value.capAmount}`
  } catch { /* Empty state. */ }
  return '—'
}
