import http from './http'

export function listGroups() {
  return http.get('/merchant/link-groups')
}

export function createGroup(name) {
  return http.post('/merchant/link-groups', { name })
}

export function listLinks(gid, recycle = false) {
  return http.get('/merchant/links', { params: { gid, recycle } })
}

export function createLink(payload) {
  return http.post('/merchant/links', payload)
}

export function updateLink(id, payload) {
  return http.put(`/merchant/links/${id}`, payload)
}

export function recycleLink(id) {
  return http.post(`/merchant/links/${id}/recycle`)
}

export function restoreLink(id) {
  return http.post(`/merchant/links/${id}/restore`)
}

export function linkStatistics() { return http.get('/merchant/links/stats') }

export function listLinkDirectory(params) { return http.get('/merchant/link-directory', { params }) }
export function activityOptions(params) { return http.get('/merchant/link-directory/activities', { params }) }
export function activitySpotlight() { return http.get('/merchant/link-directory/spotlight') }
