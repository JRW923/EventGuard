<template>
  <el-container style="min-height: 100vh">
    <el-header style="background: #409eff; color: white; display: flex; align-items: center">
      <h1 style="margin: 0; font-size: 18px">EventGuard 控制台</h1>
      <el-menu
        :default-active="$route.path"
        mode="horizontal"
        background-color="#409eff"
        text-color="#fff"
        active-text-color="#fff"
        router
        style="margin-left: 40px; flex: 1"
      >
        <el-menu-item index="/orders">订单列表</el-menu-item>
        <el-menu-item index="/anomalies">异常看板</el-menu-item>
        <el-menu-item index="/nl-query">NL 查询</el-menu-item>
        <el-menu-item index="/compensations">补偿执行</el-menu-item>
      </el-menu>
    </el-header>
    <el-main>
      <router-view />
    </el-main>
  </el-container>
</template>

<script setup lang="ts">
</script>
