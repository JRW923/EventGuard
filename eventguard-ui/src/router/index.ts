import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'

const routes: RouteRecordRaw[] = [
  { path: '/', redirect: '/orders' },
  { path: '/orders', name: 'OrderList', component: () => import('../views/OrderList.vue') },
  { path: '/anomalies', name: 'AnomalyDashboard', component: () => import('../views/AnomalyDashboard.vue') },
  { path: '/nl-query', name: 'NLQuery', component: () => import('../views/NLQuery.vue') },
  { path: '/orders/:id/timeline', name: 'OrderTimeline', component: () => import('../views/OrderTimeline.vue') },
  { path: '/compensations', name: 'CompensationExecute', component: () => import('../views/CompensationExecute.vue') },
]

export const router = createRouter({
  history: createWebHistory(),
  routes,
})
