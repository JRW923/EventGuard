<template>
  <div>
    <el-card>
      <template #header>自然语言查询 · 支持多轮追问</template>

      <!-- 对话线程 -->
      <div ref="threadRef" class="chat-thread">
        <div v-if="messages.length === 0" class="chat-empty">
          输入问题开始查询，例如「最近 7 天有多少订单」；缺订单号时我会反问，下一轮带上订单号即可。
        </div>

        <div v-for="(msg, i) in messages" :key="i" class="chat-row">
          <div v-if="msg.role === 'user'" class="chat-user">
            <div class="bubble user-bubble">{{ msg.text }}</div>
          </div>

          <div v-else class="chat-assistant">
            <div
              class="bubble assistant-bubble"
              v-loading="loading && i === messages.length - 1"
              element-loading-text="正在分析…（大模型响应慢时自动降级为规则摘要）"
            >
              <el-alert v-if="msg.error" type="error" :title="msg.error" :closable="false" />
              <template v-else>
                <div class="chat-answer">{{ msg.answer }}</div>
                <div class="chat-intent">
                  意图：{{ msg.intent }}<span v-if="msg.needs_input"> · 等待补充参数</span>
                </div>

                <!-- event_lookup: 单订单信息 -->
                <el-descriptions
                  v-if="msg.intent === 'event_lookup' && msg.data"
                  :column="1"
                  border
                  size="small"
                >
                  <el-descriptions-item
                    v-for="(v, k) in msg.data"
                    :key="k"
                    :label="String(k)"
                  >{{ v }}</el-descriptions-item>
                </el-descriptions>

                <!-- stats_aggregation: 统计表格 -->
                <el-table
                  v-else-if="msg.intent === 'stats_aggregation' && Array.isArray(msg.data)"
                  :data="msg.data"
                  border
                  stripe
                  size="small"
                >
                  <el-table-column :resizable="false" prop="status" label="状态" width="180" />
                  <el-table-column :resizable="false" prop="orderCount" label="订单数" width="120" />
                  <el-table-column :resizable="false" prop="totalAmount" label="总金额" width="140" />
                </el-table>

                <!-- trace_replay: 事件序列 -->
                <el-table
                  v-else-if="msg.intent === 'trace_replay' && Array.isArray(msg.data)"
                  :data="msg.data"
                  border
                  stripe
                  size="small"
                >
                  <el-table-column :resizable="false" prop="version" label="版本" width="80" />
                  <el-table-column :resizable="false" prop="eventType" label="事件类型" width="220" />
                  <el-table-column :resizable="false" prop="createdAt" label="发生时间" width="180" />
                </el-table>

                <!-- 兜底：JSON 展示 -->
                <pre v-else-if="msg.data" class="json-fallback">{{ JSON.stringify(msg.data, null, 2) }}</pre>
              </template>
            </div>
          </div>
        </div>
      </div>

      <!-- 输入区 -->
      <div style="display: flex; gap: 8px; margin-top: 16px">
        <el-input
          v-model="question"
          placeholder="例如：订单 #abc 当前状态是什么？/ 昨天有多少支付失败？/ 查看一下刚才那笔订单的轨迹"
          data-testid="question-input"
          @keyup.enter="submit"
        />
        <el-button type="primary" data-testid="submit-btn" :loading="loading" @click="submit">
          查询
        </el-button>
      </div>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { nextTick, ref } from 'vue'
import { AiApi, type QueryResult } from '../api/ai'
import { friendlyError } from '../api/http'

interface ChatMessage {
  role: 'user' | 'assistant'
  text?: string
  answer?: string
  intent?: string
  data?: any
  error?: string
  needs_input?: boolean
}

const messages = ref<ChatMessage[]>([])
const question = ref('')
const loading = ref(false)
// 多轮会话 id：首轮 undefined（后端新建），后续携带
const conversationId = ref<string | undefined>(undefined)
const threadRef = ref<HTMLElement | null>(null)

async function submit() {
  if (!question.value.trim()) return
  const q = question.value.trim()
  question.value = ''
  messages.value.push({ role: 'user', text: q })
  loading.value = true
  try {
    // 首轮不带会话 id，保证单轮语义与压测一致；后续携带以续聊
    const result: QueryResult = conversationId.value
      ? await AiApi.query(q, conversationId.value)
      : await AiApi.query(q)
    conversationId.value = result.conversation_id || undefined
    messages.value.push({
      role: 'assistant',
      answer: result.answer,
      intent: result.intent,
      data: result.data,
      needs_input: result.needs_input,
    })
  } catch (e: any) {
    // axios 超时（10s）时给出可操作提示；后端对慢 LLM 已做 8s 上界自动降级，正常不会走到这
    const isTimeout = e?.code === 'ECONNABORTED' || /timeout|timed ?out/i.test(e?.message || '')
    messages.value.push({
      role: 'assistant',
      error: isTimeout
        ? '查询超时（等待超过 10 秒已中止）。后端会自动降级为规则摘要，请重试。'
        : friendlyError(e, '查询失败'),
    })
  } finally {
    loading.value = false
    await nextTick()
    if (threadRef.value) {
      threadRef.value.scrollTop = threadRef.value.scrollHeight
    }
  }
}
</script>

<style scoped>
.chat-thread {
  max-height: 520px;
  overflow-y: auto;
  padding-right: 4px;
}
.chat-empty {
  color: #909399;
  font-size: 13px;
  padding: 24px 0;
  text-align: center;
}
.chat-row {
  margin-bottom: 12px;
}
.chat-user {
  display: flex;
  justify-content: flex-end;
}
.chat-assistant {
  display: flex;
  justify-content: flex-start;
}
.bubble {
  max-width: 82%;
  border-radius: 8px;
  padding: 10px 12px;
  line-height: 1.6;
}
.user-bubble {
  background: #409eff;
  color: #fff;
}
.assistant-bubble {
  background: #f4f4f5;
  width: 82%;
}
.chat-answer {
  font-size: 14px;
}
.chat-intent {
  margin-top: 8px;
  font-size: 12px;
  color: #909399;
}
.json-fallback {
  background: #f5f5f5;
  padding: 12px;
  margin: 8px 0 0;
  font-size: 12px;
}
</style>
