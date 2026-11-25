import http from './http'

export function listTemplates(page = 1) {
  return http.get('/merchant/offer-plans', { params: { page, size: 20 } })
}

export async function listAllTemplates() {
  const records = []
  for (let page = 1; ; page++) {
    const response = await http.get('/merchant/offer-plans', { params: { page, size: 100 } })
    const batch = response.data?.records || []
    records.push(...batch)
    if (!batch.length || (response.data?.total != null && records.length >= Number(response.data.total)) || batch.length < 100) break
  }
  return { data: { records } }
}

export function createTemplate(payload) {
  return http.post('/merchant/offer-plans', payload)
}

export function terminateTemplate(id) {
  return http.post(`/merchant/offer-plans/${id}/terminate`)
}

export function increaseStock(id, count) {
  return http.post(`/merchant/offer-plans/${id}/stock`, { count })
}

export function reviseTemplate(id, payload) {
  return http.put(`/merchant/offer-plans/${id}`, payload)
}

export function listMyCoupons(page = 1) {
  return http.get('/user/vouchers', { params: { page, size: 20 } })
}

export function claimCoupon(planId, requestId) {
  return http.post('/user/vouchers/claims', { planId, requestId })
}

export function quoteCoupon(payload) {
  return http.post('/user/vouchers/quotes', payload)
}

export function lockCoupon(id, payload) {
  return http.post(`/user/vouchers/${id}/hold`, payload)
}

export function payCoupon(id, orderNo) {
  return http.post(`/user/vouchers/${id}/complete`, { orderNo })
}

export function refundCoupon(id, orderNo, refundAmount) {
  return http.post(`/user/vouchers/${id}/refund`, { orderNo, refundAmount })
}

export function listTasks() {
  return http.get('/merchant/grant-batches')
}

export function createTask(planId, file, runAt) {
  const body = new FormData()
  body.append('planId', planId)
  body.append('file', file)
  if (runAt) {
    body.append('runAt', runAt.length === 16 ? `${runAt}:00` : runAt)
  }
  return http.post('/merchant/grant-batches', body)
}

export function couponSettlements(id) { return http.get(`/user/vouchers/${id}/redemptions`) }
export function listMerchantRedemptions(params) { return http.get('/merchant/vouchers/redemptions', { params }) }
export function cancelCoupon(id, orderNo) { return http.post(`/user/vouchers/${id}/void`, { orderNo }) }

export function claimActivityCouponAsync(activityId, planId, requestId, credential) {
  return http.post(`/user/activities/${activityId}/claims/async`, { planId, requestId, credential })
}

export function getActivityClaimStatus(activityId, requestId, planId) {
  return http.get(`/user/activities/${activityId}/claims/${encodeURIComponent(requestId)}`, { params: { planId } })
}
