import { describe, it, expect, beforeEach, vi } from 'vitest'

vi.mock('@/api/auth', () => ({
  AuthApi: {
    login: vi.fn(),
    me: vi.fn(),
  },
}))

import { AuthApi } from '@/api/auth'

// auth store 是模块级单例（导入时即读 localStorage），故用动态 import 保证每次拿到新状态
async function loadAuth() {
  return (await import('../auth')).auth
}

describe('auth store', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.resetModules()
  })

  it('login persists token and user', async () => {
    ;(AuthApi.login as any).mockResolvedValue({
      token: 't1',
      user: {
        id: 1,
        username: 'admin',
        displayName: '管理员',
        enabled: true,
        mustChangePassword: false,
        roles: ['ADMIN'],
        permissions: ['order:read'],
      },
    })
    const auth = await loadAuth()
    await auth.login('admin', 'pw')
    expect(auth.isAuthenticated).toBe(true)
    expect(auth.user?.username).toBe('admin')
    expect(auth.hasPermission('order:read')).toBe(true)
    expect(auth.hasPermission('order:write')).toBe(false)
    expect(auth.hasPermission()).toBe(true) // 无权限要求默认放行
    expect(localStorage.getItem('eg_token')).toBe('t1')
  })

  it('restores state from localStorage', async () => {
    localStorage.setItem('eg_token', 't2')
    localStorage.setItem('eg_user', JSON.stringify({ id: 2, username: 'viewer', permissions: ['order:read'] }))
    const auth = await loadAuth()
    expect(auth.isAuthenticated).toBe(true)
    expect(auth.user?.username).toBe('viewer')
    expect(auth.hasPermission('order:read')).toBe(true)
  })

  it('logout clears state', async () => {
    localStorage.setItem('eg_token', 't3')
    localStorage.setItem('eg_user', JSON.stringify({ id: 3, username: 'x' }))
    const auth = await loadAuth()
    auth.logout()
    expect(auth.isAuthenticated).toBe(false)
    expect(auth.user).toBeNull()
    expect(localStorage.getItem('eg_token')).toBeNull()
    expect(localStorage.getItem('eg_user')).toBeNull()
  })
})
