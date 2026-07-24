<template>
  <div>
    <el-card>
      <template #header>自然语言查询</template>

      <div style="display: flex; gap: 8px; margin-bottom: 16px">
        <el-input
          v-model="question"
          placeholder="例如：订单 #abc 当前状态是什么？/ 昨天有多少支付失败？/ 订单 #1234 经历了哪些状态变更？"
          data-testid="question-input"
          @keyup.enter="submit"
        />
        <el-button type="primary" data-testid="submit-btn" :loading="loading" @click="submit">
          查询
        </el-button>
      </div>

      <el-alert v-if="error" type="error" :title="error" :closable="false" style="margin-bottom: 16px" />

      <div v-if="result" v-loading="loading">
        <el-descriptions :column="1" border style="margin-bottom: 16px">
          <el-descriptions-item label="意图">{{ result.intent }}</el-descriptions-item>
          <el-descriptions-item label="回答">{{ result.answer }}</el-descriptions-item>
        </el-descriptions>

        <h4>原始数据</h4>
        <!-- event_lookup: 单订单信息 -->
        <el-descriptions
          v-if="result.intent === 'event_lookup' && result.data"
          :column="1"
          border
          size="small"
        >
          <el-descriptions-item
            v-for="(v, k) in result.data"
            :key="k"
            :label="String(k)"
          >{{ v }}</el-descriptions-item>
        </el-descriptions>

        <!-- stats_aggregation: 统计表格 -->
        <el-table
          v-else-if="result.intent === 'stats_aggregation' && Array.isArray(result.data)"
          :data="result.data"
          border
          size="small"
        >
          <el-table-column prop="status" label="状态" width="180" />
          <el-table-column prop="orderCount" label="订单数" width="120" />
          <el-table-column prop="totalAmount" label="总金额" />
        </el-table>

        <!-- trace_replay: 事件序列 -->
        <el-table
          v-else-if="result.intent === 'trace_replay' && Array.isArray(result.data)"
          :data="result.data"
          border
          size="small"
        >
          <el-table-column prop="version" label="版本" width="80" />
          <el-table-column prop="eventType" label="事件类型" width="220" />
          <el-table-column prop="createdAt" label="发生时间" />
        </el-table>

        <!-- 兜底：JSON 展示 -->
        <pre v-else style="background: #f5f5f5; padding: 12px">{{ JSON.stringify(result.data, null, 2) }}</pre>
      </div>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { AiApi, type QueryResult } from '../api/ai'

const question = ref('')
const loading = ref(false)
const error = ref('')
const result = ref<QueryResult | null>(null)

async function submit() {
  if (!question.value.trim()) return
  loading.value = true
  error.value = ''
  result.value = null
  try {
    result.value = await AiApi.query(question.value)
  } catch (e: any) {
    error.value = '查询失败：' + (e.message || '未知错误')
  } finally {
    loading.value = false
  }
}
</script>
