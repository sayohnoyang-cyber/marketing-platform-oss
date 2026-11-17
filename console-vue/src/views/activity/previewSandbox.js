// This module has no API dependency: all preview writes stay in memory.
export function createSandbox(activity) {
  return { id: String(activity.id), version: activity.version, quantity: Number(activity.quantity || 0), claimed: Number(activity.claimed || 0), serial: 1, people: {}, sources: {} }
}

export function previewPerson(sandbox) {
  return sandbox.people[sandbox.serial] ||= { reserved: false, claims: 0, visits: {} }
}

export function previewSource(sandbox, source) {
  return sandbox.sources[source] ||= { visits: 0, reservations: 0, claims: 0 }
}

export function visitPreview(sandbox, source, available) {
  if (!available) return
  const person = previewPerson(sandbox)
  if (!person.visits[source]) { person.visits[source] = true; previewSource(sandbox, source).visits++ }
}

export function actPreview(sandbox, { phase, source, limit, blocked }) {
  if (blocked) return { ok: false, message: blocked }
  const person = previewPerson(sandbox)
  const stats = previewSource(sandbox, source)
  if (phase === 'before') {
    if (person.reserved) return { ok: false, message: '这位用户已预约，切换渠道不会重复计数' }
    person.reserved = true; stats.reservations++
    return { ok: true, message: '模拟预约成功，已计入当前来源' }
  }
  if (phase !== 'live') return { ok: false, message: '当前不可领取' }
  if (person.claims >= limit) return { ok: false, message: '已达到本场领取上限' }
  if (sandbox.claimed >= sandbox.quantity) return { ok: false, message: '本场优惠券已领完' }
  person.claims++; sandbox.claimed++; stats.claims++
  return { ok: true, message: '模拟领取成功，预览剩余减少 1 张' }
}
