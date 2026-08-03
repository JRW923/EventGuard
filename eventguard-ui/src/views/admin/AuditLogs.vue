<template>
  <div>
    <el-card>
      <template #header>
        <div style="display: flex; align-items: center; gap: 12px">
          <span>审计日志</span>
          <el-input
            v-model="usernameFilter"
            placeholder="按用户名过滤"
            clearable
            style="width: 180px"
            size="small"
            @keyup.enter="reload"
            @clear="reload"
          />
          <el-button size="small" @click="reload">查询</el-button>
        </div>
      </template>

      <el-table :data="logs" v-loading="loading" border stripe style="width: 100%">
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column prop="username" label="用户名" width="120" />
        <el-table-column prop="action" label="动作" width="180">
          <template #default="{ row }">
            <el-tag effect="light" size="small">{{ actionLabel(row.action) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="detail" label="详情" show-overflow-tooltip />
        <el-table-column prop="ip" label="IP" width="140" />
        <el-table-column label="时间" width="180">
          <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
        </el-table-column>
      </el-table>

      <el-pagination
        v-model:current-page="currentPage"
        :page-size="pageSize"
        :total="total"
        layout="prev, pager, next, total"
        style="margin-top: 16px"
        @current-change="loadData"
      />
      <el-alert v-if="error" :title="error" type="error" :closable="false" style="margin-top: 12px" />
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { AuthApi, type AuditLogItem } from '../../api/auth'

const logs = ref<AuditLogItem[]>([])
const loading = ref(false)
const error = ref('')
const usernameFilter = ref('')
const currentPage = ref(1)
const pageSize = ref(50)
const total = ref(0)

// ponytail: 服务端未返回 total 计数，前端按「本次拉满一页」估算；真实分页计数列为升级项
function loadData() {
  loading.value = true
  error.value = ''
  AuthApi.listAuditLogs({ page: currentPage.value - 1, size: pageSize.value, username: usernameFilter.value })
    .then((rows) => {
      logs.value = rows
      total.value = rows.length < pageSize.value ? (currentPage.value - 1) * pageSize.value + rows.length : currentPage.value * pageSize.value + 1
    })
    .catch((e: any) => {
      error.value = '加载失败：' + (e.message || '未知错误')
      logs.value = []
    })
    .finally(() => (loading.value = false))
}

function reload() {
  currentPage.value = 1
  loadData()
}

function actionLabel(action: string): string {
  const map: Record<string, string> = {
    LOGIN_OK: '登录成功',
    LOGIN_FAILED: '登录失败',
    LOGOUT: '登出',
    PASSWORD_CHANGE: '修改密码',
    USER_CREATE: '创建用户',
    USER_UPDATE: '更新用户',
    USER_DELETE: '删除用户',
    USER_RESET_PASSWORD: '重置密码',
    ROLE_CREATE: '创建角色',
    ROLE_UPDATE: '更新角色',
    ROLE_DELETE: '删除角色',
  }
  return map[action] || action
}

function formatTime(iso?: string): string {
  if (!iso) return '-'
  const d = new Date(iso)
  return Number.isNaN(d.getTime()) ? iso : d.toLocaleString('zh-CN', { hour12: false })
}

onMounted(loadData)
</script>
