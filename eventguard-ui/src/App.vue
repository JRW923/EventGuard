<template>
  <el-container style="min-height: 100vh">
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
          </el-sub-menu>
        </el-menu>

        <el-dropdown class="app-user" @command="onUserCommand">
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
  </el-container>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { auth } from '@/stores/auth'
import { AuthApi } from '@/api/auth'

const router = useRouter()

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
  background: transparent !important;
}
.app-menu .el-menu-item,
.app-menu .el-sub-menu__title {
  height: 60px;
  line-height: 60px;
  border-bottom: 2px solid transparent;
  color: rgba(255, 255, 255, 0.85) !important;
}
.app-menu .el-menu-item:hover,
.app-menu .el-sub-menu__title:hover {
  background: rgba(255, 255, 255, 0.12) !important;
  color: #fff !important;
}
.app-menu .el-menu-item.is-active {
  border-bottom-color: #fff;
  font-weight: 600;
  color: #fff !important;
}
/* 系统管理子菜单激活态（进入用户/角色管理页时高亮「系统管理」） */
.app-menu .el-sub-menu__title.is-active {
  color: #fff !important;
  border-bottom-color: #fff;
  font-weight: 600;
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
