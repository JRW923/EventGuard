import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { http, setUnauthorizedHandler } from '../http'

// 捕获请求配置的简易适配器；status >= 400 时按 axios 语义抛错（含 response.status 供拦截器判断）
function captureAdapter(capture: (config: any) => void, status = 200) {
  return async (config: any) => {
    capture(config)
    if (status >= 400) {
      const err: any = new Error(`Request failed with status code ${status}`)
      err.config = config
      err.response = { status, data: {}, headers: {}, statusText: 'OK' }
      throw err
    }
    return { data: {}, status, statusText: 'OK', headers: {}, config }
  }
}

describe('http client', () => {
  beforeEach(() => {
    localStorage.clear()
  })
  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('attaches Authorization Bearer token from localStorage', async () => {
    localStorage.setItem('eg_token', 'jwt-abc')
    let captured: any
    http.defaults.adapter = captureAdapter((c) => (captured = c)) as any
    await http.get('/orders')
    expect(captured.headers.Authorization).toBe('Bearer jwt-abc')
  })

  it('does not attach Authorization without token', async () => {
    let captured: any
    http.defaults.adapter = captureAdapter((c) => (captured = c)) as any
    await http.get('/orders')
    expect(captured.headers.Authorization).toBeUndefined()
  })

  it('on 401 clears token and calls unauthorized handler', async () => {
    localStorage.setItem('eg_token', 'expired-jwt')
    const handler = vi.fn()
    setUnauthorizedHandler(handler)
    http.defaults.adapter = captureAdapter(() => undefined, 401) as any

    await expect(http.get('/orders')).rejects.toBeTruthy()
    expect(handler).toHaveBeenCalled()
    expect(localStorage.getItem('eg_token')).toBeNull()
  })
})
