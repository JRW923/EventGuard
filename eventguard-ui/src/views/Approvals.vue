<template>
  <div>
    <el-card>
      <template #header>
        <div style="display: flex; align-items: center; gap: 16px">
          <span>补偿审批</span>
          <el-tag type="warning" size="small">待审批 {{ approvals.length }}</el-tag>
          <el-button size="small" @click="load">刷新</el-button>
        </div>
      </template>

      <el-table :data="approvals" v-loading="loading" border stripe style="width: 100%">
        <el-table-column :resizable="false" prop="approvalId" label="审批单 ID" width="200" show-overflow-tooltip />
        <el-table-column :resizable="false" prop="actionType" label="动作" width="120" />
        <el-table-column :resizable="false" prop="aggregateId" label="订单 ID" width="200" show-overflow-tooltip />
        <el-table-column :resizable="false" label="参数" width="140">
          <template #default="{ row }">
            <pre class="params">{{ JSON.stringify(row.params ?? {}) }}</pre>
          </template>
        </el-table-column>
        <el-table-column :resizable="false" prop="requestedBy" label="发起方" width="90" />
        <el-table-column :resizable="false" prop="requestedAt" label="申请时间" width="170">
          <template #default="{ row }">{{ formatTime(row.requestedAt) }}</template>
        </el-table-column>
        <el-table-column :resizable="false" label="操作" width="130">
          <template #default="{ row }">
            <el-button
              size="small"
              type="primary"
              :loading="deciding[row.approvalId] === 'approve'"
              @click="decide(row.approvalId, true)"
            >
              批准
            </el-button>
            <el-button
              size="small"
              type="danger"
              :loading="deciding[row.approvalId] === 'reject'"
              @click="decide(row.approvalId, false)"
            >
              拒绝
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-empty v-if="approvals.length === 0 && !loading" description="暂无待审批项" />
      <el-alert v-if="error" type="error" :title="error" :closable="false" style="margin-top: 16px" />
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus/es/components/message/index.mjs'
import { CompensationApi, type ApprovalItem } from '../api/compensation'

const approvals = ref<ApprovalItem[]>([])
const loading = ref(false)
const error = ref('')
const deciding = ref<Record<string, 'approve' | 'reject' | ''>>({})

function formatTime(iso?: string): string {
  if (!iso) return '-'
  const d = new Date(iso)
  return Number.isNaN(d.getTime()) ? iso : d.toLocaleString('zh-CN', { hour12: false })
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    approvals.value = await CompensationApi.listApprovals()
  } catch (e: any) {
    error.value = '加载失败：' + (e.message || '未知错误')
    approvals.value = []
  } finally {
    loading.value = false
  }
}

async function decide(approvalId: string, approve: boolean) {
  deciding.value[approvalId] = approve ? 'approve' : 'reject'
  try {
    const status = await CompensationApi.decideApproval(approvalId, approve)
    ElMessage.success(`${approve ? '已批准' : '已拒绝'}（Saga: ${status}）`)
    await load()
  } catch (e: any) {
    ElMessage.error('操作失败：' + (e.message || '未知错误'))
  } finally {
    deciding.value[approvalId] = ''
  }
}

onMounted(load)
</script>

<style scoped>
.params {
  margin: 0;
  font-size: 12px;
  color: #606266;
}
</style>
