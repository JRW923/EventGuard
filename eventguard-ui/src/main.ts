import { createApp } from 'vue'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import App from './App.vue'
import { router } from './router'
import { permission } from './directives/permission'
import { setUnauthorizedHandler } from './api/http'
import { auth } from './stores/auth'

const app = createApp(App)
app.use(router)
app.use(ElementPlus)
app.directive('permission', permission)
app.mount('#app')

// 401 全局处理：清登录态 → 跳登录页（保留当前地址便于登录后回跳）
setUnauthorizedHandler(() => {
  auth.logout()
  router.push({ path: '/login', query: { redirect: router.currentRoute.value.fullPath } })
})
