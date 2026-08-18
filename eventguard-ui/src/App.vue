<template>
  <router-view v-if="route.meta.standalone" />
  <el-container v-else class="app-shell">
    <div v-if="sidebarOpen" class="app-sidebar-backdrop" @click="sidebarOpen = false" />
    <el-aside class="app-sidebar" :class="{ 'app-sidebar--open': sidebarOpen }" width="248px">
      <button class="app-brand-lockup" type="button" title="返回项目落地页" @click="router.push('/')">
        <img src="/brand/logo-2.png" alt="EventGuard" class="app-brand-mark" />
        <div>
          <div class="app-brand">EventGuard</div>
          <div class="app-brand-caption">事件卫士 · 控制台</div>
        </div>
      </button>
      <div class="app-workspace-label">OPERATIONS CONSOLE</div>

      <el-menu :default-active="activeMenu" router class="app-menu" @select="sidebarOpen = false">
        <el-menu-item index="/">
          <span class="app-menu-icon app-menu-icon--home" aria-hidden="true" />
          <span>项目首页</span>
        </el-menu-item>
        <el-menu-item v-if="auth.hasPermission('order:read')" index="/orders">
          <span class="app-menu-icon">◈</span><span>订单中心</span>
        </el-menu-item>
        <el-menu-item v-if="auth.hasPermission('anomaly:view')" index="/anomalies">
          <span class="app-menu-icon">⌁</span><span>异常看板</span>
        </el-menu-item>
        <el-menu-item v-if="auth.hasPermission('ai:query')" index="/nl-query">
          <span class="app-menu-icon">✦</span><span>AI 查询</span>
        </el-menu-item>
        <el-menu-item v-if="auth.hasPermission('ai:query')" index="/ai-report">
          <span class="app-menu-icon">▤</span><span>运营周报</span>
        </el-menu-item>
        <el-menu-item v-if="auth.hasPermission('compensation:execute')" index="/compensations">
          <span class="app-menu-icon">↗</span><span>补偿执行</span>
        </el-menu-item>
        <el-menu-item v-if="auth.hasPermission('compensation:execute')" index="/approvals">
          <span class="app-menu-icon">✓</span><span>审批队列</span>
        </el-menu-item>
        <el-sub-menu v-if="auth.hasPermission('user:manage') || auth.hasPermission('role:manage')" index="/admin">
          <template #title><span class="app-menu-icon">⚙</span><span>系统管理</span></template>
          <el-menu-item v-if="auth.hasPermission('user:manage')" index="/admin/users">用户管理</el-menu-item>
          <el-menu-item v-if="auth.hasPermission('role:manage')" index="/admin/roles">角色权限</el-menu-item>
          <el-menu-item v-if="auth.hasPermission('user:manage')" index="/admin/audit-logs">审计日志</el-menu-item>
        </el-sub-menu>
      </el-menu>

      <div class="app-sidebar-footer">
        <span class="app-sidebar-footer-dot" :class="healthOnline ? 'is-online' : 'is-offline'" />
        <span>{{ healthOnline ? '服务运行正常' : '等待后端连接' }}</span>
      </div>
    </el-aside>

    <el-container class="app-body">
      <el-header class="app-topbar">
        <div class="app-topbar-left">
          <button class="app-mobile-toggle" type="button" aria-label="打开导航" @click="sidebarOpen = !sidebarOpen">☰</button>
          <div class="app-breadcrumb">
            <button class="app-breadcrumb-home" type="button" @click="router.push('/')">EventGuard</button>
            <span class="app-breadcrumb-separator">/</span>
            <strong>{{ route.meta.title || '工作台' }}</strong>
          </div>
        </div>
        <div class="app-topbar-right">
          <div class="app-runtime-status" :class="healthOnline ? 'is-online' : 'is-offline'">
            <span class="app-runtime-pulse" /> {{ healthOnline ? 'Live' : 'Offline' }}
          </div>
          <el-dropdown v-if="auth.isAuthenticated" class="app-user" @command="onUserCommand">
            <button class="app-user-trigger" type="button">
              <span class="app-user-avatar">{{ avatarText }}</span>
              <span class="app-user-name">{{ auth.user?.displayName || auth.user?.username }}</span>
              <span class="app-user-chevron" aria-hidden="true" />
            </button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="profile">个人中心</el-dropdown-item>
                <el-dropdown-item command="logout" divided>退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </el-header>
      <el-main class="app-main">
        <div class="app-content"><router-view /></div>
      </el-main>
      <el-footer v-if="health" class="app-footer">
        <span>EventGuard {{ health.version }}</span>
        <span class="app-footer-dot">·</span>
        <span :class="healthOnline ? 'status-ok' : 'status-down'">{{ healthOnline ? '后端正常' : '后端异常' }}</span>
        <template v-if="health.dependencies?.db">
          <span class="app-footer-dot">·</span>
          <span :class="health.dependencies.db === 'UP' ? 'status-ok' : 'status-down'">数据库 {{ health.dependencies.db === 'UP' ? '正常' : '异常' }}</span>
        </template>
      </el-footer>
    </el-container>
  </el-container>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus/es/components/message-box/index.mjs'
import { auth } from '@/stores/auth'
import { AuthApi, HealthApi, type HealthInfo } from '@/api/auth'

const router = useRouter()
const route = useRoute()
const health = ref<HealthInfo | null>(null)
const sidebarOpen = ref(false)

const healthOnline = computed(() => health.value?.status === 'UP' || health.value?.status === 'ok')
const activeMenu = computed(() => {
  if (route.path.startsWith('/orders/')) return '/orders'
  if (route.path.startsWith('/admin/')) return route.path
  return route.path
})
const avatarText = computed(() => (auth.user?.displayName || auth.user?.username || '?').charAt(0).toUpperCase())

async function loadHealth() {
  try {
    health.value = await HealthApi.get()
  } catch {
    health.value = { status: 'DOWN', version: 'unknown', dependencies: {} }
  }
}

onMounted(() => {
  if (!route.meta.standalone) loadHealth()
})

watch(() => route.path, () => {
  sidebarOpen.value = false
  if (!route.meta.standalone && !health.value) loadHealth()
})

async function onUserCommand(command: string) {
  if (command === 'profile') return router.push('/profile')
  try {
    await ElMessageBox.confirm('确定退出当前账号吗？', '退出登录', {
      type: 'warning',
      confirmButtonText: '确定',
      cancelButtonText: '取消',
    })
  } catch {
    return
  }
  try {
    await AuthApi.logout()
  } catch {
    // 服务不可用时仍清理浏览器会话，避免用户被旧 token 卡住。
  }
  auth.logout()
  router.push('/login')
}
</script>

<style>
:root {
  --eg-ink: #172033;
  --eg-muted: #6f7b91;
  --eg-line: #e6eaf1;
  --eg-surface: #ffffff;
  --eg-canvas: #f5f7fb;
  --eg-brand: #3451e8;
  --eg-teal: #0f9f9a;
  --eg-warm: #f08a5d;
}

html, body, #app { min-height: 100%; margin: 0; }
body {
  background: var(--eg-canvas);
  color: var(--eg-ink);
  font-family: Inter, -apple-system, BlinkMacSystemFont, 'Segoe UI', 'PingFang SC', 'Microsoft YaHei', sans-serif;
  -webkit-font-smoothing: antialiased;
}
button, input, textarea, select { font: inherit; }

.app-shell { min-height: 100vh; background: var(--eg-canvas); }
.app-sidebar {
  position: relative; z-index: 30; display: flex; flex-direction: column;
  min-height: 100vh; padding: 28px 16px 18px; box-sizing: border-box;
  background: #111a2e; color: #e8edff; border-right: 1px solid rgba(255,255,255,.06);
}
.app-brand-lockup { display: flex; align-items: center; gap: 12px; width: 100%; padding: 0 10px; border: 0; background: transparent; color: inherit; text-align: left; cursor: pointer; }
.app-brand-lockup:hover .app-brand { color: #dce3ff; }
.app-brand-mark { width: 38px; height: 38px; border-radius: 11px; box-shadow: 0 8px 24px rgba(82,111,255,.32); }
.app-brand { font-size: 17px; font-weight: 750; letter-spacing: 0; color: #fff; }
.app-brand-caption { margin-top: 3px; color: #94a2c0; font-size: 11px; }
.app-workspace-label { margin: 34px 10px 10px; color: #657395; font-size: 10px; font-weight: 700; letter-spacing: 0; }
.app-menu { border: 0; background: transparent; }
.app-menu .el-menu-item, .app-menu .el-sub-menu__title { height: 46px; line-height: 46px; margin: 4px 0; border-radius: 10px; color: #aeb9d4; font-size: 14px; }
.app-menu .el-menu-item:hover, .app-menu .el-sub-menu__title:hover { background: rgba(255,255,255,.07); color: #fff; }
.app-menu .el-menu-item.is-active { background: linear-gradient(100deg, rgba(80,105,239,.95), rgba(67,83,201,.88)); color: #fff; box-shadow: 0 8px 18px rgba(36,55,148,.28); }
.app-menu .el-menu { background: rgba(0,0,0,.12); border-radius: 10px; }
.app-menu .el-menu-item.is-active::before { content: ''; width: 3px; height: 18px; margin-right: 9px; border-radius: 3px; background: #fff; }
.app-menu-icon { width: 23px; margin-right: 8px; color: #91a2d1; font-size: 16px; text-align: center; }
.app-menu-icon--home { position: relative; height: 18px; }
.app-menu-icon--home::before { content: ''; position: absolute; left: 6px; top: 6px; width: 10px; height: 9px; border: 1.5px solid currentColor; border-top: 0; border-radius: 1px; }
.app-menu-icon--home::after { content: ''; position: absolute; left: 7px; top: 2px; width: 8px; height: 8px; border-left: 1.5px solid currentColor; border-top: 1.5px solid currentColor; transform: rotate(45deg); }
.app-menu .is-active .app-menu-icon { color: #fff; }
.app-sidebar-footer { display: flex; align-items: center; gap: 8px; margin-top: auto; padding: 13px 11px 0; color: #8e9bb8; font-size: 12px; border-top: 1px solid rgba(255,255,255,.08); }
.app-sidebar-footer-dot, .app-runtime-pulse { display: inline-block; width: 7px; height: 7px; border-radius: 50%; background: #c4cad7; }
.is-online .app-sidebar-footer-dot, .app-sidebar-footer-dot.is-online, .is-online .app-runtime-pulse { background: #26c6a0; box-shadow: 0 0 0 4px rgba(38,198,160,.13); }
.app-sidebar-footer-dot.is-offline, .is-offline .app-runtime-pulse { background: #ee8b73; }
.app-body { min-width: 0; }
.app-topbar { height: 72px; display: flex; align-items: center; justify-content: space-between; padding: 0 34px; background: rgba(255,255,255,.84); border-bottom: 1px solid var(--eg-line); backdrop-filter: blur(12px); }
.app-topbar-left, .app-topbar-right, .app-breadcrumb, .app-user-trigger { display: flex; align-items: center; }
.app-breadcrumb { gap: 10px; font-size: 14px; }
.app-breadcrumb-muted { color: #9aa5b8; }
.app-breadcrumb-home { padding: 3px 0; border: 0; background: transparent; color: #8995aa; cursor: pointer; }
.app-breadcrumb-home:hover { color: var(--eg-brand); }
.app-breadcrumb-separator { color: #c6ccd7; }
.app-breadcrumb strong { color: var(--eg-ink); font-weight: 650; }
.app-topbar-right { gap: 20px; }
.app-runtime-status { display: flex; align-items: center; gap: 8px; color: #7b879b; font-size: 12px; }
.app-runtime-status.is-online { color: #258f7c; }
.app-user-trigger { gap: 8px; padding: 4px; border: 0; background: transparent; color: var(--eg-ink); cursor: pointer; }
.app-user-avatar { display: inline-flex; align-items: center; justify-content: center; width: 32px; height: 32px; border-radius: 10px; color: #fff; background: linear-gradient(145deg, #4c63e8, #2e3db2); font-weight: 700; }
.app-user-name { max-width: 130px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 13px; font-weight: 600; }
.app-user-chevron { width: 7px; height: 7px; margin: -3px 4px 0 2px; border-right: 1.5px solid #8390a4; border-bottom: 1.5px solid #8390a4; transform: rotate(45deg); transition: transform .18s ease, border-color .18s ease; }
.app-user-trigger:hover .app-user-chevron { border-color: var(--eg-brand); }
.app-user-trigger:focus-visible { outline: 2px solid rgba(52,81,232,.3); outline-offset: 3px; border-radius: 8px; }
.app-mobile-toggle { display: none; margin-right: 12px; border: 0; background: transparent; color: var(--eg-ink); font-size: 20px; cursor: pointer; }
.app-main { padding: 0; background: var(--eg-canvas); }
.app-content { max-width: 1440px; min-height: calc(100vh - 124px); margin: 0 auto; padding: 30px 34px 40px; box-sizing: border-box; }
.app-content > div > .el-card, .app-content > .el-card { border: 1px solid var(--eg-line); border-radius: 14px; box-shadow: 0 9px 30px rgba(22,38,74,.06); }
.app-content .el-card__header { padding: 20px 24px; border-bottom: 1px solid #edf0f5; color: var(--eg-ink); font-weight: 700; }
.app-content .el-card__body { padding: 24px; }
.app-content .el-table { --el-table-border-color: #edf0f5; --el-table-header-bg-color: #f8f9fc; --el-table-row-hover-bg-color: #f5f7ff; }
.app-content .el-button--primary { --el-button-bg-color: var(--eg-brand); --el-button-border-color: var(--eg-brand); }
.app-content .el-tag { border-radius: 6px; }
.app-footer { height: 34px; display: flex; align-items: center; justify-content: center; gap: 7px; color: #9aa5b8; font-size: 11px; border-top: 1px solid var(--eg-line); background: #fff; }
.app-footer-dot { color: #cbd2dc; }
.status-ok { color: #1e9a80; }
.status-down { color: #e17369; }
.app-sidebar-backdrop { display: none; }

@media (max-width: 900px) {
  .app-sidebar { position: fixed; inset: 0 auto 0 0; width: 248px !important; transform: translateX(-105%); transition: transform .22s ease; box-shadow: 14px 0 35px rgba(10,19,43,.2); }
  .app-sidebar--open { transform: translateX(0); }
  .app-sidebar-backdrop { display: block; position: fixed; inset: 0; z-index: 20; background: rgba(12,20,39,.42); }
  .app-mobile-toggle { display: inline-block; }
  .app-topbar { padding: 0 18px; }
  .app-content { padding: 22px 16px 30px; }
  .app-user-name { display: none; }
}
@media (max-width: 520px) {
  .app-runtime-status { display: none; }
  .app-topbar { height: 62px; }
  .app-content { min-height: calc(100vh - 96px); }
  .app-content .el-card__body, .app-content .el-card__header { padding: 18px; }
}

.eg-select { height: 34px; padding: 0 10px; border: 1px solid #dfe4ee; border-radius: 7px; background: #fff; color: #546177; outline: none; }
.eg-select:hover, .eg-select:focus { border-color: var(--eg-brand); }
</style>
