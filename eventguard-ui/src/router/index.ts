import { createRouter, createWebHashHistory, type RouteRecordRaw } from 'vue-router'
import { auth } from '@/stores/auth'

const routes: RouteRecordRaw[] = [
  { path: '/', redirect: '/orders' },
  { path: '/login', name: 'Login', component: () => import('../views/Login.vue'), meta: { title: '登录', public: true } },
  { path: '/403', name: 'Forbidden', component: () => import('../views/Forbidden.vue'), meta: { title: '无权限', public: true } },
  {
    path: '/orders',
    name: 'OrderList',
    component: () => import('../views/OrderList.vue'),
    meta: { title: '订单列表', permission: 'order:read' },
  },
  {
    path: '/anomalies',
    name: 'AnomalyDashboard',
    component: () => import('../views/AnomalyDashboard.vue'),
    meta: { title: '异常看板', permission: 'anomaly:view' },
  },
  {
    path: '/nl-query',
    name: 'NLQuery',
    component: () => import('../views/NLQuery.vue'),
    meta: { title: 'NL 查询', permission: 'ai:query' },
  },
  {
    path: '/orders/:id/timeline',
    name: 'OrderTimeline',
    component: () => import('../views/OrderTimeline.vue'),
    meta: { title: '订单时间线', permission: 'order:read' },
  },
  {
    path: '/compensations',
    name: 'CompensationExecute',
    component: () => import('../views/CompensationExecute.vue'),
    meta: { title: '补偿执行', permission: 'compensation:execute' },
  },
  { path: '/profile', name: 'Profile', component: () => import('../views/Profile.vue'), meta: { title: '个人中心' } },
  {
    path: '/admin/users',
    name: 'Users',
    component: () => import('../views/admin/Users.vue'),
    meta: { title: '用户管理', permission: 'user:manage' },
  },
  {
    path: '/admin/roles',
    name: 'Roles',
    component: () => import('../views/admin/Roles.vue'),
    meta: { title: '角色管理', permission: 'role:manage' },
  },
  {
    path: '/admin/audit-logs',
    name: 'AuditLogs',
    component: () => import('../views/admin/AuditLogs.vue'),
    meta: { title: '审计日志', permission: 'user:manage' },
  },
  // catch-all：未知地址 → 404 页（须放在最后）
  { path: '/:pathMatch(.*)*', name: 'NotFound', component: () => import('../views/NotFound.vue'), meta: { title: '页面不存在', public: true } },
]

export const router = createRouter({
  // ponytail: 用 hash 模式，URL 形如 /#/orders，避免 SPA 路由(/orders 等)与后端 API 路径同名
  // 导致 nginx 把浏览器直接访问/刷新转发到后端而 401；hash 部分不发给服务器，刷新/深链均正常
  history: createWebHashHistory(),
  routes,
})

// 全局前置守卫：未登录跳登录页；登录后按路由 meta.permission 校验权限
router.beforeEach(async (to) => {
  // 页面标签：有 meta.title 显示「页面 · EventGuard」，否则仅站点名（避免「EventGuard · EventGuard」）
  const pageTitle = to.meta.title as string | undefined
  document.title = pageTitle ? `${pageTitle} · EventGuard` : 'EventGuard'

  if (to.meta.public) {
    // 已登录用户访问登录页 → 直接进首页
    if (to.path === '/login' && auth.isAuthenticated) return { path: '/' }
    return true
  }

  if (!auth.isAuthenticated) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }

  // 页面刷新后 user 为空时，用现有 token 拉取最新信息（失败会走 401 拦截器登出）
  if (!auth.user) {
    try {
      await auth.fetchMe()
    } catch {
      return false
    }
  }

  const permission = to.meta.permission as string | undefined
  if (permission && !auth.hasPermission(permission)) {
    return { path: '/403' }
  }
  return true
})
