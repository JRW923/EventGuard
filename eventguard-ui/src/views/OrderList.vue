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
            style="padding: 4px 8px"
          >
            <option :value="null">全部状态</option>
            <option v-for="s in statuses" :key="s" :value="s">{{ s }}</option>
          </select>
          <el-button size="small" @click="loadData">刷新</el-button>
        </div>
      </template>

      <el-table :data="orders" v-loading="loading" border style="width: 100%">
        <el-table-column prop="orderId" label="订单 ID" width="320" />
        <el-table-column prop="status" label="状态" width="160" />
        <el-table-column prop="totalAmount" label="金额" width="120" />
        <el-table-column prop="version" label="版本" width="80" />
        <el-table-column prop="updatedAt" label="更新时间" />
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
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
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

onMounted(loadData)
</script>
