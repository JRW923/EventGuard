import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import ElementPlus from 'element-plus'

const pushMock = vi.fn()

vi.mock('vue-router', () => ({
  useRouter: () => ({ push: pushMock }),
  useRoute: () => ({ query: {} }),
}))

vi.mock('@/stores/auth', () => ({
  auth: {
    login: vi.fn(),
    fetchMe: vi.fn(),
    logout: vi.fn(),
  },
}))

vi.mock('@/api/auth', () => ({
  AuthApi: {
    login: vi.fn(),
    me: vi.fn(),
    changePassword: vi.fn(),
  },
}))

import { auth } from '@/stores/auth'
import { AuthApi } from '@/api/auth'
import Login from '../Login.vue'

function mountLogin() {
  return mount(Login, { global: { plugins: [ElementPlus] } })
}

describe('Login.vue', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('登录成功后跳转首页', async () => {
    ;(auth.login as any).mockResolvedValue({
      username: 'admin',
      mustChangePassword: false,
      permissions: ['order:read'],
    })
    const wrapper = mountLogin()
    await wrapper.find('[data-testid="username"]').setValue('admin')
    await wrapper.find('[data-testid="password"]').setValue('admin123456')
    await wrapper.find('[data-testid="login-btn"]').trigger('click')
    await flushPromises()

    expect(auth.login).toHaveBeenCalledWith('admin', 'admin123456')
    expect(pushMock).toHaveBeenCalledWith('/orders')
  })

  it('mustChangePassword 用户登录后弹出改密框，改密成功登出重登', async () => {
    ;(auth.login as any).mockResolvedValue({
      username: 'admin',
      mustChangePassword: true,
      permissions: ['order:read'],
    })
    ;(AuthApi.changePassword as any).mockResolvedValue(undefined)

    const wrapper = mountLogin()
    await wrapper.find('[data-testid="username"]').setValue('admin')
    await wrapper.find('[data-testid="password"]').setValue('admin123456')
    await wrapper.find('[data-testid="login-btn"]').trigger('click')
    await flushPromises()

    // 不应跳转，出现强制改密框
    expect(pushMock).not.toHaveBeenCalled()

    await wrapper.find('[data-testid="new-pwd"]').setValue('new123456')
    await wrapper.find('[data-testid="confirm-pwd"]').setValue('new123456')
    await wrapper.find('[data-testid="change-btn"]').trigger('click')
    await flushPromises()

    expect(AuthApi.changePassword).toHaveBeenCalledWith('admin123456', 'new123456')
    expect(auth.fetchMe).toHaveBeenCalled()
    expect(auth.logout).toHaveBeenCalled()
  })
})
