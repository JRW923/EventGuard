<template>
  <!-- standalone 页面（欢迎页/体验指南）裸渲染，不套控制台壳 -->
  <router-view v-if="route.meta.standalone" />
  <el-container v-else style="min-height: 100vh">
    <el-header class="app-header">
      <div class="app-header-inner">
        <h1 class="app-brand">EventGuard 控制台</h1>
        <el-menu
          :default-active="$route.path"
          mode="horizontal"
          router
          class="app-menu"
        >
          <el-menu-item v-if="auth.hasPermission('order:read')" index="/orders">订单列表</el-menu-item>
          <el-menu-item v-if="auth.hasPermission('anomaly:view')" index="/anomalies">异常看板</el-menu-item>
          <el-menu-item v-if="auth.hasPermission('ai:query')" index="/nl-query">NL 查询</el-menu-item>
          <el-menu-item v-if="auth.hasPermission('compensation:execute')" index="/compensations">补偿执行</el-menu-item>
          <el-sub-menu
            v-if="auth.hasPermission('user:manage') || auth.hasPermission('role:manage')"
            index="/admin"
          >
            <template #title>系统管理</template>
            <el-menu-item v-if="auth.hasPermission('user:manage')" index="/admin/users">用户管理</el-menu-item>
            <el-menu-item v-if="auth.hasPermission('role:manage')" index="/admin/roles">角色管理</el-menu-item>
            <el-menu-item v-if="auth.hasPermission('user:manage')" index="/admin/audit-logs">审计日志</el-menu-item>
          </el-sub-menu>
        </el-menu>

        <el-dropdown v-if="auth.isAuthenticated" class="app-user" @command="onUserCommand">
          <span class="app-user-trigger">
            <span class="app-user-avatar">{{ avatarText }}</span>
            {{ auth.user?.displayName || auth.user?.username }}
          </span>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="profile">修改密码</el-dropdown-item>
              <el-dropdown-item command="logout" divided>退出登录</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </div>
    </el-header>
    <el-main class="app-main">
      <div class="app-content">
        <router-view />
      </div>
    </el-main>
    <el-footer class="app-footer" v-if="health">
      <span>EventGuard {{ health.version }}</span>
      <span class="app-footer-dot">·</span>
      <span :class="health.status === 'UP' ? 'status-ok' : 'status-down'">
        {{ health.status === 'UP' ? '后端正常' : '后端异常' }}
      </span>
      <span v-if="health.dependencies?.db" class="app-footer-dot">·</span>
      <span v-if="health.dependencies?.db" :class="health.dependencies.db === 'UP' ? 'status-ok' : 'status-down'">
        数据库{{ health.dependencies.db === 'UP' ? '正常' : '异常' }}
      </span>
    </el-footer>
    <!-- 边角返回欢迎页（不占主视觉，仅角落可回） -->
    <button class="app-home-corner" title="返回欢迎页" aria-label="返回欢迎页" @click="goWelcome">
      <svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
        <path d="M3 10.5 12 3l9 7.5" />
        <path d="M5 9.5V21h14V9.5" />
      </svg>
    </button>
  </el-container>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { auth } from '@/stores/auth'
import { AuthApi, HealthApi, type HealthInfo } from '@/api/auth'

const router = useRouter()
const route = useRoute()

const health = ref<HealthInfo | null>(null)

onMounted(async () => {
  // 落地页/指南页不拉健康状态（也无 footer 展示它）
  if (route.meta.standalone) return
  try {
    health.value = await HealthApi.get()
  } catch {
    health.value = { status: 'DOWN', version: 'unknown', dependencies: {} }
  }
})

const avatarText = computed(() => (auth.user?.displayName || auth.user?.username || '?').charAt(0).toUpperCase())

async function onUserCommand(command: string) {
  if (command === 'profile') {
    router.push('/profile')
  } else if (command === 'logout') {
    try {
      await ElMessageBox.confirm('确定退出登录？', '提示', { type: 'warning' })
    } catch {
      return
    }
    try {
      await AuthApi.logout()
    } catch {
      // 即使注销接口失败也继续登出
    }
    auth.logout()
    router.push('/login')
  }
}

// 控制台各页边角返回欢迎页（/ 落地页）
function goWelcome() {
  router.push('/')
}
</script>

<style>
/* 全局基础样式与美化 */
html,
body {
  margin: 0;
  padding: 0;
}
body {
  background: #f5f7fa;
  color: #303133;
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', 'PingFang SC', 'Microsoft YaHei', sans-serif;
}

.app-header {
  background: #409eff;
  color: #fff;
  display: flex;
  align-items: center;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.12);
  padding: 0;
}
.app-header-inner {
  width: 100%;
  max-width: 1200px;
  margin: 0 auto;
  display: flex;
  align-items: center;
  padding: 0 20px;
}

/* P2-15 移动端适配：窄屏时头部换行、菜单横向滚动，避免挤压 */
@media (max-width: 768px) {
  .app-header-inner {
    flex-wrap: wrap;
    padding: 0 10px;
    gap: 4px;
  }
  .app-menu {
    margin-left: 0;
    order: 3;
    width: 100%;
    overflow-x: auto;
  }
  .app-menu .el-menu-item,
  .app-menu .el-sub-menu__title {
    height: 44px;
    line-height: 44px;
    margin: 0 2px;
  }
  .app-user {
    margin-left: auto;
  }
  .app-content {
    padding: 12px;
  }
}
.app-brand {
  margin: 0;
  font-size: 18px;
  font-weight: 600;
  white-space: nowrap;
}
.app-menu {
  margin-left: 32px;
  flex: 1;
  border-bottom: none;
  /* ponytail: 强制与头部同色，而非依赖透明——Element Plus 默认 --el-menu-bg-color 为白，
     若透明失效会出现白底白字看不清标签 */
  background: #409eff !important;
}
.app-menu .el-menu-item,
.app-menu .el-sub-menu__title {
  height: 60px;
  line-height: 60px;
  margin: 0 4px;
  border-radius: 6px;
  color: #fff !important;
  font-weight: 500;
}
.app-menu .el-menu-item:hover,
.app-menu .el-sub-menu__title:hover {
  background: rgba(255, 255, 255, 0.18) !important;
  color: #fff !important;
}
/* 激活态反色药丸：白底蓝字，当前页标签最清晰 */
.app-menu .el-menu-item.is-active,
.app-menu .el-sub-menu__title.is-active {
  background: #fff !important;
  color: #409eff !important;
  font-weight: 600;
  border-radius: 6px;
  border-bottom: none !important;
}
/* 内联渲染（非 teleport）时子菜单下拉面板 */
.app-menu .el-sub-menu .el-menu {
  background: #fff;
}
.app-menu .el-sub-menu .el-menu-item {
  height: 44px;
  line-height: 44px;
  color: #303133 !important;
}
.app-menu .el-sub-menu .el-menu-item.is-active {
  color: #409eff !important;
}
/* 系统管理下拉（popper 默认 teleport 到 body，须用全局选择器覆盖主菜单传下来的白字/透明底） */
.el-menu--popup {
  background: #fff;
}
.el-menu--popup .el-menu-item {
  color: #303133;
}
.el-menu--popup .el-menu-item:hover {
  background: #ecf5ff;
}
.el-menu--popup .el-menu-item.is-active {
  color: #409eff;
  font-weight: 600;
}

.app-user {
  margin-left: 16px;
}
.app-user-trigger {
  display: flex;
  align-items: center;
  gap: 6px;
  color: #fff;
  cursor: pointer;
  font-size: 14px;
}
.app-user-avatar {
  width: 26px;
  height: 26px;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.25);
  color: #fff;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-weight: 600;
}

.app-main {
  padding: 0;
  background: #f5f7fa;
}
.app-content {
  max-width: 1200px;
  margin: 0 auto;
  padding: 20px;
}
.app-footer {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  color: #909399;
  font-size: 12px;
  background: #fff;
  border-top: 1px solid #e4e7ed;
}
.app-footer-dot {
  color: #dcdfe6;
}
.status-ok {
  color: #67c23a;
}
.status-down {
  color: #f56c6c;
}

/* 边角返回欢迎页：低调小按钮，不占主视觉 */
.app-home-corner {
  position: fixed;
  right: 14px;
  bottom: 14px;
  z-index: 30;
  width: 34px;
  height: 34px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  border: 1px solid rgba(64, 158, 255, 0.35);
  background: rgba(255, 255, 255, 0.88);
  color: #409eff;
  cursor: pointer;
  opacity: 0.4;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.08);
  transition: opacity 0.2s ease, transform 0.2s ease, box-shadow 0.2s ease;
}
.app-home-corner:hover {
  opacity: 1;
  transform: translateY(-2px);
  box-shadow: 0 6px 16px rgba(64, 158, 255, 0.25);
}

/* 统一原生 select 视觉（状态筛选 / 动作类型），与 Element Plus 控件一致 */
.eg-select {
  height: 32px;
  padding: 0 10px;
  border: 1px solid #dcdfe6;
  border-radius: 4px;
  background: #fff;
  color: #606266;
  font-size: 14px;
  box-sizing: border-box;
  outline: none;
  cursor: pointer;
}
.eg-select:focus,
.eg-select:hover {
  border-color: #409eff;
}

/* ponytail: 禁用表格列宽手动拖拽——用户不应手动调整表格宽度 */
.el-table__column-resize-handle {
  display: none !important;
}
</style>
