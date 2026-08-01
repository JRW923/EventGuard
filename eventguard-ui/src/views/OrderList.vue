<template>
  <div>
    <el-card>
      <template #header>
        <div style="display: flex; align-items: center; gap: 16px">
          <span>订单列表</span>
          <select
            v-model="statusFilter"
            data-testid="status-filter"
            @change="onFilterChange"
            class="eg-select"
          >
            <option :value="null">全部状态</option>
            <option v-for="s in statuses" :key="s" :value="s">{{ s }}</option>
          </select>
          <el-button size="small" type="primary" v-permission="'order:create'" @click="openCreate">新建订单</el-button>
          <el-button size="small" @click="loadData">刷新</el-button>
        </div>
      </template>

      <el-table :data="orders" v-loading="loading" border stripe style="width: 100%">
        <el-table-column prop="orderId" label="订单 ID" width="320" show-overflow-tooltip />
        <el-table-column label="状态" width="160">
          <template #default="{ row }">
            <el-tag :type="statusType(row.status)" effect="light" style="white-space: nowrap">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="金额" width="120">
          <template #default="{ row }">¥{{ Number(row.totalAmount).toFixed(2) }}</template>
        </el-table-column>
        <el-table-column prop="version" label="版本" width="80" />
        <el-table-column label="更新时间">
          <template #default="{ row }">{{ formatTime(row.updatedAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="160">
          <template #default="{ row }">
            <el-button size="small" @click="goTimeline(row.orderId)">事件时间线</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        v-model:current-page="currentPage"
        :page-size="pageSize"
        :total="total"
        layout="prev, pager, next, total"
        style="margin-top: 16px"
        @current-change="onPageChange"
      />

      <el-alert v-if="error" type="error" :title="error" :closable="false" style="margin-top: 16px" />

      <el-dialog v-model="createVisible" title="新建订单" width="420px">
        <el-form :model="form" label-width="90px">
          <el-form-item label="用户 ID">
            <el-input v-model="form.userId" placeholder="如 u-demo" />
          </el-form-item>
          <el-form-item label="金额">
            <el-input-number v-model="form.totalAmount" :min="0" :step="1" />
          </el-form-item>
        </el-form>
        <template #footer>
          <el-button @click="createVisible = false">取消</el-button>
          <el-button type="primary" :loading="creating" @click="submitCreate">确定</el-button>
        </template>
      </el-dialog>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { OrderApi, type OrderListItem } from '../api/order'

const router = useRouter()

const orders = ref<OrderListItem[]>([])
const loading = ref(false)
const error = ref('')
const statusFilter = ref<string | null>(null)
const currentPage = ref(1)
const pageSize = ref(20)
const total = ref(0)

const statuses = [
  'PENDING_PAYMENT', 'PAYMENT_FAILED', 'PAID', 'CONFIRMED',
  'SHIPPED', 'DELIVERED', 'CLOSED', 'CANCELLED', 'REFUNDED',
]

// ponytail: 状态用彩色标签单行展示（白底浅色，避免长状态名换行）
function statusType(status: string): 'success' | 'info' | 'warning' | 'danger' | 'primary' {
  switch (status) {
    case 'PAID':
    case 'DELIVERED':
      return 'success'
    case 'PENDING_PAYMENT':
    case 'REFUNDED':
      return 'warning'
    case 'PAYMENT_FAILED':
      return 'danger'
    case 'CONFIRMED':
    case 'SHIPPED':
      return 'primary'
    default:
      return 'info'
  }
}

function formatTime(iso?: string): string {
  if (!iso) return '-'
  const d = new Date(iso)
  return Number.isNaN(d.getTime()) ? iso : d.toLocaleString('zh-CN', { hour12: false })
}

async function loadData() {
  loading.value = true
  error.value = ''
  try {
    const resp = await OrderApi.list(statusFilter.value, currentPage.value - 1, pageSize.value)
    orders.value = resp.orders
    total.value = resp.total
  } catch (e: any) {
    error.value = '加载失败：' + (e.message || '未知错误')
    orders.value = []
  } finally {
    loading.value = false
  }
}

function onFilterChange() {
  currentPage.value = 1
  loadData()
}

function onPageChange(page: number) {
  currentPage.value = page
  loadData()
}

function goTimeline(orderId: string) {
  router.push(`/orders/${orderId}/timeline`)
}

const createVisible = ref(false)
const creating = ref(false)
const form = ref<{ userId: string; totalAmount: number }>({ userId: '', totalAmount: 199 })

function openCreate() {
  form.value = { userId: '', totalAmount: 199 }
  createVisible.value = true
}

async function submitCreate() {
  if (!form.value.userId) {
    ElMessage.warning('请填写用户 ID')
    return
  }
  creating.value = true
  try {
    const { orderId } = await OrderApi.create({ userId: form.value.userId, totalAmount: Number(form.value.totalAmount) })
    ElMessage.success('创建成功')
    createVisible.value = false
    // ponytail: 读模型经 Debezium→Kafka 投影有秒级延迟，轮询直到订单出现在读模型再刷新列表，
    // 否则 loadData 跑在投影完成前，列表看不到新订单（即之前“需手动刷新”的根因）。
    await waitForOrder(orderId)
    await loadData()
  } catch (e: any) {
    ElMessage.error('创建失败：' + (e.message || '未知错误'))
  } finally {
    creating.value = false
  }
}

// 轮询订单读模型，直到投影完成（GET /orders/{id} 返回 200）或超时后继续
async function waitForOrder(orderId: string, attempts = 10, interval = 400): Promise<void> {
  for (let i = 0; i < attempts; i++) {
    try {
      await OrderApi.get(orderId)
      return
    } catch {
      // 尚未投影（404）或瞬时错误，稍后重试
    }
    await new Promise((r) => setTimeout(r, interval))
  }
}

onMounted(loadData)
</script>
