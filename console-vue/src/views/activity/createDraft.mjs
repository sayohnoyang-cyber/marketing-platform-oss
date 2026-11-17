import { requestId } from '../../lib/requestId.mjs'

export function draftKey(profile) {
  return profile?.userId && profile?.merchantId ? `marketing.activity-draft.${profile.userId}.${profile.merchantId}` : ''
}

export function readDraft(storage, key) {
  if (!key) return null
  try {
    const draft = JSON.parse(storage.getItem(key) || 'null')
    return draft?.schema === 1 && draft.form && typeof draft.form.name === 'string' ? draft : null
  } catch { return null }
}

export function writeDraft(storage, key, draft) {
  if (!key) return false
  try { storage.setItem(key, JSON.stringify({ ...draft, schema: 1 })); return true } catch { return false }
}

export function createPlan(form, entries) {
  return {
    payload: { name: form.name, activityType: form.type, budgetAmount: form.budget, startTime: `${form.startDate}:00`, endTime: `${form.endDate}:00`, idempotencyKey: requestId() },
    offer: form.planId ? { planId: form.planId, quantity: form.quantity, maxClaimsPerUser: form.maxClaimsPerUser, budgetAmount: form.budget, idempotencyKey: requestId() } : null,
    channels: entries.map(entry => ({ name: entry.name, channelCode: entry.code || null, idempotencyKey: requestId() })),
    activityId: '', mainDone: false, offerDone: false, channelDone: []
  }
}

export async function savePlan(plan, api, persist) {
  // 所有写入前先保存完整请求编号；响应丢失时仍使用原参数、原编号续存。
  persist()
  if (!plan.activityId) {
    const response = await api.createActivity(plan.payload)
    plan.activityId = String(response.data.id); persist()
  }
  const detail = (await api.getActivity(plan.activityId)).data
  if (detail.phase !== 'DRAFT') throw new Error('活动状态已变化，请进入已保存活动继续配置')
  if (detail.name !== plan.payload.name || String(detail.startTime).slice(0,19) !== plan.payload.startTime
      || String(detail.endTime).slice(0,19) !== plan.payload.endTime
      || (detail.activityType && detail.activityType !== plan.payload.activityType)
      || (detail.budgetAmount != null && Number(detail.budgetAmount) !== Number(plan.payload.budgetAmount))) {
    throw new Error('活动信息已被修改，请进入已保存活动确认')
  }
  if (!plan.mainDone) { await api.openMainLink(plan.activityId); plan.mainDone = true; persist() }
  if (plan.offer && !plan.offerDone) {
    const current = (await api.getActivity(plan.activityId)).data
    if ((current.planId && String(current.planId) !== String(plan.offer.planId))
        || (current.quantity != null && Number(current.quantity) !== 0 && Number(current.quantity) !== Number(plan.offer.quantity))) {
      throw new Error('已保存投放与草稿不一致，请进入活动确认')
    }
    await api.saveOffer(plan.activityId, { ...plan.offer, version: current.version })
    plan.offerDone = true; persist()
  }
  for (const entry of plan.channels) {
    if (plan.channelDone.includes(entry.idempotencyKey)) continue
    await api.addChannel(plan.activityId, entry)
    plan.channelDone.push(entry.idempotencyKey); persist()
  }
  return plan.activityId
}
