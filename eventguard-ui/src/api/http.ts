import axios from 'axios'
import { clearAuth, getToken } from './token'

// 通过 Vite 环境变量配置后端地址，默认走 vite proxy（同源）
const baseURL = import.meta.env.VITE_API_BASE_URL || ''

export const http = axios.create({
  baseURL,
  timeout: 10000,
  headers: { 'Content-Type': 'application/json' },
})

// 请求拦截器：附上登录 JWT（Bearer），替代原静态 X-API-Key
http.interceptors.request.use((config) => {
  const token = getToken()
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

// 401 → 清登录态并交由 app 层跳登录页；403 → 权限不足提示
let onUnauthorized: (() => void) | null = null
export function setUnauthorizedHandler(fn: () => void) {
  onUnauthorized = fn
}

http.interceptors.response.use(
  (resp) => resp,
  (error) => {
    const status = error.response?.status
    if (status === 401) {
      clearAuth()
      if (onUnauthorized) onUnauthorized()
    } else if (status === 403) {
      console.error('[HTTP] 权限不足', error.config?.url)
    } else {
      console.error('[HTTP]', error.config?.url, error.message)
    }
    return Promise.reject(error)
  }
)
