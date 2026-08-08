import { describe, it, expect, vi, beforeEach } from 'vitest'

// 用 vi.hoisted 提供可变的登录态，mock auth store 以驱动守卫行为
const state = vi.hoisted(() => ({ authenticated: false }))

vi.mock('@/stores/auth', () => ({
  auth: {
    get isAuthenticated() {
      return state.authenticated
    },
    user: null,
    hasPermission: () => true,
    fetchMe: vi.fn(),
  },
}))

import { router } from '@/router'

describe('欢迎页 / 体验指南路由（登录前落地页）', () => {
  beforeEach(async () => {
    state.authenticated = false
    // 回到欢迎页，避免跨用例残留路由
    await router.push('/').catch(() => {})
  })

  it('“/” 是公开的欢迎页而非重定向', () => {
    const route = router.getRoutes().find((r) => r.path === '/')
    expect(route).toBeDefined()
    expect(route?.redirect).toBeUndefined()
    expect(route?.meta.public).toBe(true)
    expect(route?.meta.standalone).toBe(true)
  })

  it('“/guide” 是公开的体验指南页', () => {
    const route = router.getRoutes().find((r) => r.path === '/guide')
    expect(route).toBeDefined()
    expect(route?.meta.public).toBe(true)
    expect(route?.meta.standalone).toBe(true)
  })

  it('未登录访问“/”进入欢迎页', async () => {
    state.authenticated = false
    await router.push('/')
    expect(router.currentRoute.value.path).toBe('/')
  })

  it('已登录仍可访问介绍页', async () => {
    state.authenticated = true
    await router.push('/guide') // 先离开“/”，避免重复导航不触发守卫
    await router.push('/')
    expect(router.currentRoute.value.path).toBe('/')
  })

  it('已登录访问“/login”重定向到“/orders”', async () => {
    state.authenticated = true
    await router.push('/login')
    expect(router.currentRoute.value.path).toBe('/orders')
  })

  it('未登录访问受保护页面“/orders”重定向到登录页并携带 redirect', async () => {
    state.authenticated = false
    await router.push('/orders')
    expect(router.currentRoute.value.path).toBe('/login')
    expect(router.currentRoute.value.query.redirect).toBe('/orders')
  })
})
