import { createApp } from 'vue'
import App from './App.vue'
import { router } from './router'
import { permission } from './directives/permission'
import { setUnauthorizedHandler } from './api/http'
import { auth } from './stores/auth'

// 编程式 API（ElMessage/ElMessageBox）不走模板解析，样式需手动引入；
// 模板组件与 v-loading 由 unplugin-vue-components 按需自动注入样式
import 'element-plus/es/components/message/style/css'
import 'element-plus/es/components/message-box/style/css'

const app = createApp(App)
app.use(router)
app.directive('permission', permission)

// 401 全局处理：清登录态 → 跳登录页（保留当前地址便于登录后回跳）
setUnauthorizedHandler(() => {
  auth.logout()
  router.push({ path: '/login', query: { redirect: router.currentRoute.value.fullPath } })
})

// 等初始导航解析完成再挂载：否则首帧渲染时 route.meta 为空，会先画出带蓝色顶栏的
// 控制台壳，再切到落地页，造成「打开欢迎页闪一下蓝色」的闪烁。
router.isReady().then(() => {
  app.mount('#app')
})
