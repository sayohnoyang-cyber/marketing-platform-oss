import http from './http'

export function register(payload) {
  return http.post('/identity/register', payload)
}

export function login(payload) {
  return http.post('/identity/login', payload)
}

export function logout() {
  return http.post('/identity/logout')
}

export function me() {
  return http.get('/identity/me')
}

export function changePassword(payload) {
  return http.post('/identity/password', payload)
}

export function listShops() {
  return http.get('/merchant/shops')
}

export function createShop(name) {
  return http.post('/merchant/shops', { name })
}

export function listMembers() {
  return http.get('/merchant/members')
}

export function addMember(payload) {
  return http.post('/merchant/members', payload)
}
