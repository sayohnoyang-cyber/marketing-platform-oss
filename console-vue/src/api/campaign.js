import http from './http'

export function listActivities() {
  return http.get('/merchant/activities')
}

export function getActivity(activityId) {
  return http.get(`/merchant/activities/${activityId}`)
}

export function createActivity(payload) {
  return http.post('/merchant/activities', payload)
}

export function updateActivity(activityId, payload) {
  return http.patch(`/merchant/activities/${activityId}`, payload)
}

export function deleteActivity(activityId) {
  return http.delete(`/merchant/activities/${activityId}`)
}

export function copyActivity(activityId) {
  return http.post(`/merchant/activities/${activityId}/copy`)
}

export function attachCoupon(activityId, planId) {
  return http.post(`/merchant/activities/${activityId}/offers`, { planId })
}

export function placeAllocation(activityId, payload) {
  return http.post(`/merchant/activities/${activityId}/allocation`, payload)
}

export function saveOffer(activityId, payload) {
  return http.put(`/merchant/activities/${activityId}/allocation`, payload)
}

export function mainLink(activityId) {
  return http.get(`/merchant/activities/${activityId}/main-link`)
}

export function openMainLink(activityId) {
  return http.post(`/merchant/activities/${activityId}/main-link`)
}

export function postponeActivity(activityId, payload) {
  return http.post(`/merchant/activities/${activityId}/postpone`, payload)
}

export function cancelActivity(activityId) {
  return http.post(`/merchant/activities/${activityId}/cancel`)
}

export function offlineActivity(activityId) {
  return http.post(`/merchant/activities/${activityId}/offline`)
}

export function terminateActivity(activityId) {
  return http.post(`/merchant/activities/${activityId}/terminate`)
}

export function activityChecks(activityId) {
  return http.get(`/merchant/activities/${activityId}/checks`)
}

export function runPreparation(activityId, requestKey) {
  return http.post(`/merchant/activities/${activityId}/prepare-runs`, { requestKey })
}

export function setMainEnabled(activityId, enabled) {
  return http.post(`/merchant/activities/${activityId}/main`, { enabled })
}

export function setChannelEnabled(activityId, channelId, enabled) {
  return http.post(`/merchant/activities/${activityId}/channels/${channelId}/status`, { enabled })
}

export function publishActivity(activityId) {
  return http.post(`/merchant/activities/${activityId}/publish`)
}

export function pauseActivity(activityId) {
  return http.post(`/merchant/activities/${activityId}/pause`)
}

export function addChannel(activityId, payload) {
  return http.post(`/merchant/activities/${activityId}/channels`, payload)
}

export function listChannels(activityId) {
  return http.get(`/merchant/activities/${activityId}/channels`)
}

export function reservationCount(activityId) {
  return http.get(`/merchant/activities/${activityId}/reservations/count`)
}

export function activityReport(activityId) {
  return http.get(`/merchant/activities/${activityId}/report`)
}

export function activityDashboard(activityId) {
  return http.get(`/merchant/activities/${activityId}/dashboard`)
}

export function activityReview(activityId) {
  return http.get(`/merchant/activities/${activityId}/review`)
}

export function listForecasts(id) { return http.get(`/merchant/activities/${id}/forecasts`) }
export function saveForecast(id, payload) { return http.post(`/merchant/activities/${id}/forecasts`, payload) }
export function listCapacityBaselines() { return http.get('/merchant/capacity-baselines') }
export function createCapacityBaseline(payload) { return http.post('/merchant/capacity-baselines', payload) }
export function listPreparationRuns(id) { return http.get(`/merchant/activities/${id}/prepare-runs`) }
export function applyRateLimit(id, payload) { return http.put(`/merchant/activities/${id}/rate-limit-policy`, payload) }
export function getRateLimit(id) { return http.get(`/merchant/activities/${id}/rate-limit-policy`) }
export function disableRateLimit(id) { return http.delete(`/merchant/activities/${id}/rate-limit-policy`) }

export function trafficOverview(period = 'today', activityId) {
  return http.get('/merchant/overview', { params: { period, ...(activityId ? { activityId } : {}) } })
}

export function forecastCalibration(id) { return http.get(`/merchant/activities/${id}/forecast-calibration`) }
export function activityMessageStatus(id) { return http.get(`/merchant/activities/${id}/message-status`) }

export function openingPlan(activityId) { return http.get(`/merchant/activities/${activityId}/opening-plan`) }
