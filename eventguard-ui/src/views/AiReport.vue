<template>
  <div>
    <el-card>
      <template #header>
        <div style="display: flex; align-items: center; gap: 16px">
          <span>运营周报</span>
          <el-select v-model="days" size="small" style="width: 120px">
            <el-option :value="7" label="近 7 天" />
            <el-option :value="14" label="近 14 天" />
            <el-option :value="30" label="近 30 天" />
          </el-select>
          <el-button type="primary" size="small" :loading="loading" @click="generate">
            生成周报
          </el-button>
          <el-button size="small" @click="generate">刷新</el-button>
        </div>
      </template>

      <el-alert v-if="error" type="error" :title="error" :closable="false" style="margin-bottom: 16px" />

      <template v-if="report">
        <el-descriptions :column="2" border size="small" style="margin-bottom: 16px">
          <el-descriptions-item label="周期">最近 {{ report.period.days }} 天</el-descriptions-item>
          <el-descriptions-item label="异常总数">{{ report.total_anomalies }}</el-descriptions-item>
          <el-descriptions-item label="统计起点">{{ formatTime(report.period.from) }}</el-descriptions-item>
          <el-descriptions-item label="统计终点">{{ formatTime(report.period.to) }}</el-descriptions-item>
        </el-descriptions>

        <h3>异常分布（按规则）</h3>
        <el-table :data="report.by_rule" border stripe size="small" style="margin-bottom: 16px">
          <el-table-column :resizable="false" prop="rule_id" label="规则 ID" min-width="220" />
          <el-table-column :resizable="false" prop="count" label="命中次数" width="120" sortable />
        </el-table>

        <h3>订单状态统计</h3>
        <el-table :data="report.order_stats" border stripe size="small" style="margin-bottom: 16px">
          <el-table-column :resizable="false" prop="status" label="状态" min-width="180" />
          <el-table-column :resizable="false" prop="orderCount" label="订单数" width="120" />
          <el-table-column :resizable="false" prop="totalAmount" label="总金额" width="140" />
        </el-table>

        <h3>AI 症状分析</h3>
        <ul>
          <li v-for="(s, i) in report.symptoms" :key="i">{{ s }}</li>
        </ul>

        <h3>处理建议</h3>
        <ul>
          <li v-for="(r, i) in report.recommendations" :key="i">{{ r }}</li>
        </ul>

        <h3>重点订单（异常集中）</h3>
        <el-table v-if="report.top_orders.length" :data="report.top_orders" border stripe size="small">
          <el-table-column :resizable="false" prop="aggregate_id" label="订单 ID" min-width="280" show-overflow-tooltip />
          <el-table-column :resizable="false" prop="count" label="异常次数" width="120" />
          <el-table-column :resizable="false" label="操作" width="140">
            <template #default="{ row }">
              <el-button size="small" @click="loadStory(row.aggregate_id)">查看故事</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-empty v-else description="近期无异常订单" :image-size="60" />
      </template>

      <el-empty v-else-if="!loading" description="选择周期后生成周报" />

      <el-dialog v-model="storyVisible" title="订单故事线" width="50%">
        <div v-if="story" v-loading="storyLoading">
          <p style="line-height: 1.8">{{ story.story }}</p>
          <div class="story-events">
            {{ (story.event_types || []).join(' → ') }}
          </div>
        </div>
      </el-dialog>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { AiApi, type WeeklyReport, type OrderStory } from '../api/ai'
import { friendlyError } from '../api/http'

const days = ref(7)
const loading = ref(false)
const error = ref('')
const report = ref<WeeklyReport | null>(null)
const storyVisible = ref(false)
const storyLoading = ref(false)
const story = ref<OrderStory | null>(null)

function formatTime(iso: string): string {
  const d = new Date(iso)
  return Number.isNaN(d.getTime()) ? iso : d.toLocaleString('zh-CN', { hour12: false })
}

async function generate() {
  loading.value = true
  error.value = ''
  try {
    report.value = await AiApi.weeklyReport(days.value)
  } catch (e: any) {
    error.value = friendlyError(e, '生成周报失败')
    report.value = null
  } finally {
    loading.value = false
  }
}

async function loadStory(aggregateId: string) {
  storyVisible.value = true
  storyLoading.value = true
  story.value = null
  try {
    story.value = await AiApi.orderStory(aggregateId)
  } catch (e: any) {
    story.value = { aggregate_id: aggregateId, story: '加载失败', event_types: [] }
  } finally {
    storyLoading.value = false
  }
}
</script>

<style scoped>
.story-events {
  margin-top: 12px;
  font-size: 12px;
  color: #909399;
  word-break: break-all;
}
</style>
