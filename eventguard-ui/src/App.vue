<template>
  <el-container style="min-height: 100vh">
    <el-header class="app-header">
      <div class="app-header-inner">
        <h1 class="app-brand">EventGuard 控制台</h1>
        <el-menu
          :default-active="$route.path"
          mode="horizontal"
          background-color="transparent"
          text-color="rgba(255,255,255,0.85)"
          active-text-color="#fff"
          router
          class="app-menu"
        >
          <el-menu-item index="/orders">订单列表</el-menu-item>
          <el-menu-item index="/anomalies">异常看板</el-menu-item>
          <el-menu-item index="/nl-query">NL 查询</el-menu-item>
          <el-menu-item index="/compensations">补偿执行</el-menu-item>
        </el-menu>
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
.app-menu .el-menu-item {
  height: 60px;
  line-height: 60px;
  border-bottom: 2px solid transparent;
}
.app-menu .el-menu-item.is-active {
  border-bottom-color: #fff;
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
