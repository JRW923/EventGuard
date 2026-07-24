import axios from 'axios'

// 通过 Vite 环境变量配置后端地址，默认走 vite proxy（同源）
const baseURL = import.meta.env.VITE_API_BASE_URL || ''

export const http = axios.create({
  baseURL,
  timeout: 10000,
  headers: { 'Content-Type': 'application/json' },
})

const apiKey = import.meta.env.VITE_API_KEY
if (apiKey) {
  http.defaults.headers.common['X-API-Key'] = apiKey
}

http.interceptors.response.use(
  (resp) => resp,
  (error) => {
    console.error('[HTTP]', error.config?.url, error.message)
    return Promise.reject(error)
  }
)
