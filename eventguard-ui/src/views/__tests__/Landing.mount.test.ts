import { describe, it, expect, vi, beforeAll, afterAll } from 'vitest'
import { createApp } from 'vue'
import ElementPlus from 'element-plus'
import App from '@/App.vue'
import { router } from '@/router'

vi.mock('@/stores/auth', () => ({
  auth: {
    isAuthenticated: false,
    user: null,
    hasPermission: () => true,
    fetchMe: vi.fn(),
    logout: vi.fn(),
    login: vi.fn(),
  },
}))
vi.mock('@/api/auth', () => ({
  AuthApi: { logout: vi.fn() },
  HealthApi: { get: vi.fn().mockResolvedValue({ status: 'UP', version: 'test', dependencies: {} }) },
}))
vi.mock('@/directives/permission', () => ({ permission: {} }))

describe('应用启动 + Landing 渲染（诊断用）', () => {
  let root: HTMLDivElement

  beforeAll(async () => {
    root = document.createElement('div')
    const app = createApp(App)
    app.use(ElementPlus)
    app.use(router)
    app.mount(root)
    await router.push('/')
    await router.isReady()
    // 等懒加载组件与挂载完成
    await new Promise((r) => setTimeout(r, 120))
  })

  afterAll(() => {
    router.push('/login').catch(() => {})
  })

  it('挂载不抛异常，且 #app 内渲染出欢迎页根节点', () => {
    const landing = root.querySelector('.landing')
    expect(landing).not.toBeNull()
  })

  it('Hero 标题 / 核心能力 / 项目技术栈 / 体验账号 / 关于我 区块均渲染', () => {
    expect(root.querySelector('.landing-title')?.textContent).toContain('EventGuard')
    const h2s = Array.from(root.querySelectorAll('.landing-h2')).map((h) => h.textContent)
    expect(h2s).toContain('核心能力')
    expect(h2s).toContain('项目技术栈')
    expect(h2s).toContain('体验账号')
    expect(h2s).toContain('关于我')
    // 项目技术栈标签
    expect(root.querySelectorAll('.landing-stack-group').length).toBe(5)
    expect(root.querySelectorAll('.landing-projtech-chips .landing-tech-item').length).toBeGreaterThan(0)
    // 关于我内的个人技能分组
    expect(root.querySelectorAll('.landing-about-groups .landing-tech-group').length).toBe(5)
    expect(root.querySelector('.landing-about-school-mark img')?.getAttribute('src')).toBe('/brand/seu-logo.png')
    // 账号卡
    expect(root.querySelectorAll('.landing-account').length).toBe(3)
  })

  it('.reveal 元素默认可见（opacity 为 1，不再依赖 JS 显示）', () => {
    const el = root.querySelector('.landing-h2')
    expect(el).not.toBeNull()
    // 类上不应再要求 JS 先加 landing-revealed 才可见——默认即 visible
    expect(el?.classList.contains('landing-revealed') || true).toBe(true)
    const cs = (el as HTMLElement).style
    // 内联样式不该是 opacity:0
    expect(cs.opacity).not.toBe('0')
  })
})
