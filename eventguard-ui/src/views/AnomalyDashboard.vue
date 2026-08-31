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
        <el-table-column :resizable="false" prop="anomaly_id" label="异常 ID" width="180" show-overflow-tooltip />
        <el-table-column :resizable="false" prop="rule_id" label="规则 ID" width="100" />
        <el-table-column :resizable="false" prop="level" label="级别" width="80">
          <template #default="{ row }">
            <el-tag :type="levelType(row.level)" size="small" style="white-space: nowrap">{{ row.level }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column :resizable="false" prop="description" label="描述" min-width="240" show-overflow-tooltip />
        <el-table-column :resizable="false" prop="detected_at" label="检测时间" width="200">
          <template #default="{ row }">{{ formatTime(row.detected_at) }}</template>
        </el-table-column>
        <el-table-column :resizable="false" label="操作" width="140">
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
        <el-table-column :resizable="false" prop="rule_id" label="规则 ID" width="120" />
        <el-table-column :resizable="false" prop="aggregate_id" label="订单 ID" min-width="200" show-overflow-tooltip />
        <el-table-column :resizable="false" prop="level" label="级别" width="80">
          <template #default="{ row }">
            <el-tag :type="levelType(row.level)" size="small" style="white-space: nowrap">{{ row.level }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column :resizable="false" prop="count" label="命中次数" width="100" sortable label-class-name="hit-count-column" />
        <el-table-column :resizable="false" prop="first" label="首次" width="180">
          <template #default="{ row }">{{ formatTime(row.first) }}</template>
        </el-table-column>
        <el-table-column :resizable="false" prop="last" label="最近" width="180">
          <template #default="{ row }">{{ formatTime(row.last) }}</template>
        </el-table-column>
        <el-table-column :resizable="false" label="操作" width="140">
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

    <el-dialog v-model="dialogVisible" title="根因分析报告" width="62%">
      <div style="margin-bottom: 12px; display: flex; gap: 8px">
        <el-button type="primary" plain :loading="healing" @click="runDeepAnalysis">
          深度分析（Agent）
        </el-button>
        <el-button
          type="success"
          plain
          v-permission="'compensation:execute'"
          :disabled="!currentReport || currentReport.suggestions.length === 0"
          @click="startSagaCompensation"
        >
          发起补偿审批
        </el-button>
        <span v-if="healNote" style="font-size: 12px; color: #909399; align-self: center">{{ healNote }}</span>
      </div>

      <div v-if="currentReport" v-loading="analysisLoading || healing">
        <!-- Agent 分析过程（Item 6a） -->
        <template v-if="agentTrace.length">
          <h3>AI 分析过程</h3>
          <el-collapse>
            <el-collapse-item
              v-for="(t, i) in agentTrace"
              :key="i"
              :title="`第 ${t.step} 步 · 工具 ${t.tool}`"
            >
              <div class="trace-input">输入：{{ JSON.stringify(t.input) }}</div>
              <pre class="trace-output">{{ typeof t.output === 'string' ? t.output : JSON.stringify(t.output, null, 2) }}</pre>
            </el-collapse-item>
          </el-collapse>
        </template>

        <h3>根因</h3>
        <p>{{ currentReport.root_cause }}</p>

        <h3>证据</h3>
        <ul>
          <li v-for="(ev, idx) in currentReport.evidence" :key="idx">{{ ev }}</li>
        </ul>

        <h3>建议动作</h3>
        <el-table :data="currentReport.suggestions" border size="small">
          <el-table-column :resizable="false" prop="action" label="动作" width="180" />
          <el-table-column :resizable="false" prop="reason" label="原因" min-width="280" />
          <el-table-column :resizable="false" prop="risk" label="风险" width="100" />
        </el-table>

      </div>
      <!-- 分析失败：给出原因与重试入口，避免只剩一个空白对话框 -->
      <el-result v-else-if="analysisError" icon="error" title="分析失败" :sub-title="analysisError">
        <template #extra>
          <el-button type="primary" size="small" :loading="analysisLoading" @click="retryAnalysis">
            重试
          </el-button>
        </template>
      </el-result>
      <!-- 首次分析时正文为空，用 loading 文案说明会等待较久（LLM 实测 10~16s） -->
      <div
        v-else
        v-loading="analysisLoading || healing"
        element-loading-text="正在分析根因…（大模型响应较慢，最长等待约 45 秒）"
        style="min-height: 120px"
      />

      <!-- 相似案例（Item 8 · 轻量 RAG）：后端独立于根因分析，故放在报告容器外——
           否则分析失败（如 LLM 未配置）时案例区会被连带隐藏，尽管它本可正常工作 -->
      <div class="similar-block">
        <div class="similar-head">
          <h3>相似案例</h3>
          <el-button
            type="warning"
            plain
            size="small"
            :loading="similarLoading"
            @click="loadSimilarCases"
          >
            {{ similarLoaded ? '重新加载' : '加载相似案例' }}
          </el-button>
        </div>
        <el-alert v-if="similarError" type="error" :closable="false" :title="similarError" />
        <el-table v-else-if="similarCases.length" :data="similarCases" border size="small">
          <el-table-column :resizable="false" prop="similarity" label="相似度" width="90">
            <template #default="{ row }">{{ Math.round(row.similarity * 100) }}%</template>
          </el-table-column>
          <el-table-column :resizable="false" prop="rule_id" label="规则" width="150" />
          <el-table-column :resizable="false" prop="aggregate_id" label="订单 ID" width="200" show-overflow-tooltip />
          <el-table-column :resizable="false" prop="description" label="描述" min-width="240" show-overflow-tooltip />
          <el-table-column :resizable="false" prop="resolution" label="处置" width="90" />
        </el-table>
        <el-empty v-else-if="similarLoaded" description="暂无相似案例" :image-size="60" />
        <span v-else class="similar-hint">参考历史异常的上次处置方式，与根因分析相互独立</span>
      </div>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus/es/components/message/index.mjs'
import { useAnomalyWebSocket } from '../composables/useAnomalyWebSocket'
import { AnomalyApi, type AnalysisReport, type AnomalyAlert, type AgentTraceStep, type SimilarCase } from '../api/anomaly'
import { CompensationApi } from '../api/compensation'
import { friendlyError } from '../api/http'

const { alerts, connected } = useAnomalyWebSocket()

const dialogVisible = ref(false)
const analysisLoading = ref(false)
const currentReport = ref<AnalysisReport | null>(null)
// 分析失败的原因。对话框正文依赖 currentReport，失败时若不记录并展示，
// 用户只会看到一个空白弹窗（此前 409 读己写冲突就是这种表现）
const analysisError = ref('')
const currentAggregateId = ref('')
const currentAnomalyId = ref('')
const aggregateMode = ref(false)
// Item 6a/6b：Agent 深度分析 + 补偿审批
const healing = ref(false)
const agentTrace = ref<AgentTraceStep[]>([])
const healNote = ref('')
// Item 8：相似案例。后端独立于根因分析，故单独维护加载/错误/空状态，
// 不跟随 currentReport 一起隐藏
const similarCases = ref<SimilarCase[]>([])
const similarLoading = ref(false)
const similarError = ref('')
const similarLoaded = ref(false)

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
  analysisError.value = ''
  agentTrace.value = []
  healNote.value = ''
  // 换一条异常时清空案例区，避免看到上一条的案例
  similarCases.value = []
  similarError.value = ''
  similarLoaded.value = false
  // ponytail: 补偿按订单聚合根执行，须用 anomaly 的 aggregate_id 而非 anomaly_id
  const alert = alerts.value.find((a) => a.anomaly_id === anomalyId)
  currentAggregateId.value = alert?.aggregate_id ?? ''
  currentAnomalyId.value = anomalyId
  try {
    currentReport.value = await AnomalyApi.getAnalysis(anomalyId)
  } catch (e: any) {
    console.error('加载根因报告失败', e)
    analysisError.value = friendlyError(e, '加载根因报告失败')
    ElMessage.error(analysisError.value)
  } finally {
    analysisLoading.value = false
  }
}

function retryAnalysis() {
  if (currentAnomalyId.value) showAnalysis(currentAnomalyId.value)
}

// Item 6a：ReAct 深度分析 —— agent 多轮工具调用收集证据，展示分析过程 + 报告
async function runDeepAnalysis() {
  if (!currentAnomalyId.value) return
  healing.value = true
  agentTrace.value = []
  healNote.value = ''
  try {
    const r = await AnomalyApi.healAnomaly(currentAnomalyId.value)
    currentReport.value = r.report
    agentTrace.value = r.agent_trace
    healNote.value = r.note ?? ''
    analysisError.value = ''
  } catch (e: any) {
    console.error('深度分析失败', e)
    // 也可能发生在还没有报告时（首次进入即点深度分析），记录错误以免只剩空白对话框
    analysisError.value = friendlyError(e, '深度分析失败')
    ElMessage.error(analysisError.value)
  } finally {
    healing.value = false
  }
}

// Item 6b：把 AI 建议动作作为步骤发起补偿 Saga（高风险步自动落审批单）
async function startSagaCompensation() {
  if (!currentReport.value || !currentAggregateId.value) return
  // 携带 AI 建议中的退款金额（Python 侧从事件序列取实付金额）：服务端按 amount 判定「>100 元需审批」
  const steps = currentReport.value.suggestions.map((s) => ({
    actionType: s.action,
    params: s.amount != null ? { amount: s.amount } : {},
  }))
  try {
    const r = await CompensationApi.startSaga(currentAggregateId.value, steps)
    const msg =
      r.status === 'AWAITING_APPROVAL'
        ? '补偿已提交，高风险步骤已进入审批队列'
        : `补偿已执行（Saga: ${r.status}）`
    ElMessage.success(msg)
  } catch (e: any) {
    ElMessage.error(friendlyError(e, '发起补偿失败'))
  }
}

// Item 8：相似案例检索（轻量 RAG）
async function loadSimilarCases() {
  if (!currentAnomalyId.value) return
  similarLoading.value = true
  similarCases.value = []
  similarError.value = ''
  try {
    const r = await AnomalyApi.similarCases(currentAnomalyId.value)
    similarCases.value = r.cases
    similarLoaded.value = true
  } catch (e: any) {
    console.error('相似案例加载失败', e)
    similarError.value = friendlyError(e, '相似案例加载失败')
    ElMessage.error(similarError.value)
  } finally {
    similarLoading.value = false
  }
}

</script>

<style scoped>
.trace-input {
  font-size: 12px;
  color: #909399;
  margin-bottom: 4px;
}
.trace-output {
  margin: 0;
  padding: 8px;
  background: #f5f5f5;
  font-size: 12px;
  max-height: 160px;
  overflow: auto;
}
:deep(.hit-count-column .cell) {
  display: inline-flex;
  align-items: center;
  white-space: nowrap;
}
:deep(.hit-count-column .caret-wrapper) {
  flex: 0 0 24px;
}
/* 相似案例独立于报告区，用分隔线与上方内容区分 */
.similar-block {
  margin-top: 16px;
  padding-top: 12px;
  border-top: 1px solid #ebeef5;
}
.similar-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}
.similar-head h3 {
  margin: 0;
}
.similar-hint {
  font-size: 12px;
  color: #909399;
}
</style>
