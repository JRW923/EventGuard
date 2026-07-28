import { createRouter, createWebHashHistory, type RouteRecordRaw } from 'vue-router'

const routes: RouteRecordRaw[] = [
  { path: '/', redirect: '/orders' },
  { path: '/orders', name: 'OrderList', component: () => import('../views/OrderList.vue') },
  { path: '/anomalies', name: 'AnomalyDashboard', component: () => import('../views/AnomalyDashboard.vue') },
  { path: '/nl-query', name: 'NLQuery', component: () => import('../views/NLQuery.vue') },
  { path: '/orders/:id/timeline', name: 'OrderTimeline', component: () => import('../views/OrderTimeline.vue') },
  { path: '/compensations', name: 'CompensationExecute', component: () => import('../views/CompensationExecute.vue') },
]

export const router = createRouter({
  // ponytail: 用 hash 模式，URL 形如 /#/orders，避免 SPA 路由(/orders 等)与后端 API 路径同名
  // 导致 nginx 把浏览器直接访问/刷新转发到后端而 401；hash 部分不发给服务器，刷新/深链均正常
  history: createWebHashHistory(),
  routes,
})
