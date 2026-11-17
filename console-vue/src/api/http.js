import axios from 'axios'
import store from '@/store'
import router from '@/router'
import { safeReturn } from '@/views/login/returnTo'
import { normalizePublicLinks } from '@/lib/publicLink.mjs'

const http = axios.create({
  baseURL: '/api',
  timeout: 15000
})

http.interceptors.request.use((config) => {
  const token = store.state.token
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

http.interceptors.response.use(
  (response) => {
    const body = response.data
    if (body && body.code && body.code !== '0') {
      return Promise.reject(body)
    }
    return normalizePublicLinks(body, import.meta.env.VITE_PUBLIC_SHORTLINK_ORIGIN || '')
  },
  (error) => {
    const status = error?.response?.status
    const body = error?.response?.data
    if (status === 401) {
      const back = safeReturn(router.currentRoute.value.fullPath)
      store.commit('clearSession')
      if (router.currentRoute.value.path !== '/login') {
        router.push({ path: '/login', query: back ? { expired: '1', return: back } : { expired: '1' } })
      }
    }
    return Promise.reject(body || { message: '网络异常，请稍后重试' })
  }
)

export default http
