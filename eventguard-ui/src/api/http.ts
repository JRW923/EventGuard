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

// 把网络/后端错误转成用户可读的中文提示，不暴露 HTTP 状态码与内部异常文案。
// 后端业务消息（ResponseStatusException / FastAPI detail）是中文时直接透出；
// 其余（"Request failed with status code 500"、英文技术文案等）按状态码给通用提示。
export function friendlyError(error: any, fallback = '操作失败，请稍后重试'): string {
  // 超时（axios 的 ECONNABORTED 或后端消息里带 timeout）
  if (error?.code === 'ECONNABORTED' || /timeout|timed ?out/i.test(error?.message || '')) {
    return '请求超时，请稍后重试'
  }
  const resp = error?.response
  // 无响应：优先识别前端主动抛出的业务错误（如本地表单校验），否则视为网络中断
  if (!resp) {
    const msg = error?.message
    if (typeof msg === 'string' && msg.trim() && /[\u4e00-\u9fa5]/.test(msg)) {
      return msg.trim()
    }
    return '网络连接异常，请检查网络后重试'
  }
  const data = resp.data
  const raw = (data && typeof data === 'object' ? (data.message ?? data.detail) : undefined) as string | undefined
  if (typeof raw === 'string' && raw.trim() && /[\u4e00-\u9fa5]/.test(raw)) {
    return raw.trim()
  }
  switch (resp.status) {
    case 400: return '请求参数有误，请检查后重试'
    case 401: return '登录已过期，请重新登录'
    case 403: return '没有权限执行此操作'
    case 404: return '请求的内容不存在'
    case 409: return '操作冲突，请刷新后重试'
    case 429: return '操作过于频繁，请稍后再试'
    case 500:
    case 502:
    case 503:
    case 504: return '服务器繁忙，请稍后重试'
    default: return fallback
  }
}
