<template>
  <div>
    <el-card>
      <template #header>
        <div style="display: flex; align-items: center; gap: 16px">
          <span>异常看板</span>
          <el-tag :type="connected ? 'success' : 'danger'" size="small">
            {{ connected ? 'WebSocket 已连接' : 'WebSocket 未连接' }}
          </el-tag>
          <el-switch v-model="aggregateMode" active-text="聚合模式" inactive-text="明细模式" size="small" />
        </div>
      </template>

      <!-- 明细模式：逐条告警 -->
      <el-table
        v-if="!aggregateMode"
        :data="alerts"
        border
        stripe
        style="width: 100%"
        max-height="400"
      >
        <el-table-column prop="anomaly_id" label="异常 ID" width="180" show-overflow-tooltip />
        <el-table-column prop="rule_id" label="规则 ID" width="100" />
        <el-table-column prop="level" label="级别" width="80">
          <template #default="{ row }">
            <el-tag :type="levelType(row.level)" size="small" style="white-space: nowrap">{{ row.level }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="description" label="描述" show-overflow-tooltip />
        <el-table-column prop="detected_at" label="检测时间" width="200">
          <template #default="{ row }">{{ formatTime(row.detected_at) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="140">
          <template #default="{ row }">
            <el-button
              size="small"
              :data-testid="`anomaly-item-${row.anomaly_id}`"
              @click="showAnalysis(row.anomaly_id)"
            >
              查看根因
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <!-- 聚合模式：按 (规则, 订单) 聚类，展示重复度与时间跨度（Item 2） -->
      <el-table
        v-else
        :data="clusters"
        border
        stripe
        style="width: 100%"
        max-height="400"
      >
        <el-table-column prop="rule_id" label="规则 ID" width="120" />
        <el-table-column prop="aggregate_id" label="订单 ID" show-overflow-tooltip />
        <el-table-column prop="level" label="级别" width="80">
          <template #default="{ row }">
            <el-tag :type="levelType(row.level)" size="small" style="white-space: nowrap">{{ row.level }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="count" label="命中次数" width="100" sortable />
        <el-table-column prop="first" label="首次" width="180">
          <template #default="{ row }">{{ formatTime(row.first) }}</template>
        </el-table-column>
        <el-table-column prop="last" label="最近" width="180">
          <template #default="{ row }">{{ formatTime(row.last) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="140">
          <template #default="{ row }">
            <el-button
              size="small"
              :data-testid="`cluster-item-${row.representative.anomaly_id}`"
              @click="showAnalysis(row.representative.anomaly_id)"
            >
              查看根因
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-empty v-if="alerts.length === 0" description="暂无异常告警" />
    </el-card>

    <el-dialog v-model="dialogVisible" title="根因分析报告" width="60%">
      <div v-if="currentReport" v-loading="analysisLoading">
        <h3>根因</h3>
        <p>{{ currentReport.root_cause }}</p>

        <h3>证据</h3>
        <ul>
          <li v-for="(ev, idx) in currentReport.evidence" :key="idx">{{ ev }}</li>
        </ul>

        <h3>建议动作</h3>
        <el-table :data="currentReport.suggestions" border size="small">
          <el-table-column prop="action" label="动作" width="180" />
          <el-table-column prop="reason" label="原因" />
          <el-table-column prop="risk" label="风险" width="100" />
        </el-table>

        <div style="margin-top: 16px; text-align: right">
          <el-button
            v-for="s in currentReport.suggestions"
            :key="s.action"
            type="primary"
            plain
            v-permission="'compensation:execute'"
            style="margin-left: 8px"
            @click="goCompensate(s.action)"
          >
            执行 {{ s.action }}
          </el-button>
        </div>
      </div>
      <div v-else v-loading="analysisLoading" />
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAnomalyWebSocket } from '../composables/useAnomalyWebSocket'
import { AnomalyApi, type AnalysisReport, type AnomalyAlert } from '../api/anomaly'

const router = useRouter()
const { alerts, connected } = useAnomalyWebSocket()

const dialogVisible = ref(false)
const analysisLoading = ref(false)
const currentReport = ref<AnalysisReport | null>(null)
const currentAggregateId = ref('')
const aggregateMode = ref(false)

interface Cluster {
  rule_id: string
  aggregate_id: string
  level: string
  count: number
  first: string
  last: string
  representative: AnomalyAlert
}

// 按 (规则, 订单) 聚类：展示同一异常被重复检出的次数与时间跨度（配合后端去重门控）
const clusters = computed<Cluster[]>(() => {
  const map = new Map<string, Cluster>()
  for (const a of alerts.value) {
    const key = `${a.rule_id}|${a.aggregate_id}`
    const c = map.get(key)
    if (c) {
      c.count++
      if (a.detected_at && a.detected_at < c.first) c.first = a.detected_at
      if (a.detected_at && a.detected_at > c.last) c.last = a.detected_at
    } else {
      map.set(key, {
        rule_id: a.rule_id,
        aggregate_id: a.aggregate_id,
        level: a.level,
        count: 1,
        first: a.detected_at,
        last: a.detected_at,
        representative: a,
      })
    }
  }
  return [...map.values()].sort((x, y) => y.count - x.count)
})

function levelType(level: string): 'danger' | 'warning' | 'info' {
  if (level === 'ERROR') return 'danger'
  if (level === 'WARN') return 'warning'
  return 'info'
}

function formatTime(iso?: string): string {
  if (!iso) return '-'
  const d = new Date(iso)
  return Number.isNaN(d.getTime()) ? iso : d.toLocaleString('zh-CN', { hour12: false })
}

async function showAnalysis(anomalyId: string) {
  dialogVisible.value = true
  analysisLoading.value = true
  currentReport.value = null
  // ponytail: 补偿按订单聚合根执行，须用 anomaly 的 aggregate_id 而非 anomaly_id
  const alert = alerts.value.find((a) => a.anomaly_id === anomalyId)
  currentAggregateId.value = alert?.aggregate_id ?? ''
  try {
    currentReport.value = await AnomalyApi.getAnalysis(anomalyId)
  } catch (e: any) {
    // ponytail: 根因分析失败时仅记录，对话框空白降级；如需可在此展示错误提示
    console.error('加载根因报告失败', e)
  } finally {
    analysisLoading.value = false
  }
}

function goCompensate(action: string) {
  router.push({ path: '/compensations', query: { actionType: action, aggregateId: currentAggregateId.value } })
}
</script>
