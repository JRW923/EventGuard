import { http } from './http'

export interface QueryResult {
  intent: string // event_lookup / stats_aggregation / trace_replay
  // ponytail: data 暂用 any + 前端 JSON 回退展示；待 /ai/query 的 intent schema 稳定后改为判别联合类型
  data: any
  answer: string
  // 多轮对话（Item 1）：会话 id 供续聊携带；needs_input=true 表示 answer 是缺参反问
  conversation_id?: string
  needs_input?: boolean
}

export interface OrderPrediction {
  outcome: string // CLOSED / CANCELLED / REFUNDED / STUCK
  confidence: number
  risk: 'LOW' | 'MEDIUM' | 'HIGH'
}

export interface WeeklyReport {
  period: { days: number; from: string; to: string }
  total_anomalies: number
  by_rule: { rule_id: string; count: number }[]
  order_stats: { status: string; orderCount: number; totalAmount: number }[]
  symptoms: string[]
  recommendations: string[]
  top_orders: { aggregate_id: string; count: number }[]
  generated_at?: string // 后端落库时附加，历史列表用它区分同一周期的多份报告
}

export interface OrderStory {
  aggregate_id: string
  story: string
  event_types: string[]
}

export const AiApi = {
  query(question: string, conversationId?: string): Promise<QueryResult> {
    const payload: Record<string, unknown> = { question }
    if (conversationId) payload.conversation_id = conversationId
    return http.post<QueryResult>('/ai/query', payload).then((r) => r.data)
  },
  // 订单终局预测（Item 5）：加载订单事件序列 → 预测终局状态 + 置信度 + 风险
  predict(orderId: string): Promise<{ aggregate_id: string; current_status?: string; prediction: OrderPrediction | null; message?: string }> {
    return http.get(`/ai/predict/${orderId}`).then((r) => r.data)
  },
  // 高风险在途订单 watchlist（Item 5）
  watchlist(limit = 10): Promise<{ items: Array<{ orderId: string; status?: string; outcome: string; confidence: number; risk: string }>; message?: string }> {
    return http.get('/ai/predictions/watchlist', { params: { limit } }).then((r) => r.data)
  },
  // 运营周报（Item 7）：近期异常聚合 + LLM 症状/建议。
  // 后端有同周期短时缓存：缓存窗口内重复请求直接返回落库结果，不再调 LLM
  weeklyReport(days = 7): Promise<WeeklyReport> {
    return http.post<WeeklyReport>('/ai/report/weekly', { days }).then((r) => r.data)
  },
  // 运营周报历史：最近生成的多份报告（新在前），前端可切换查看
  weeklyReportHistory(limit = 20): Promise<{ items: WeeklyReport[] }> {
    return http.get<{ items: WeeklyReport[] }>('/ai/report/weekly/history', { params: { limit } }).then((r) => r.data)
  },
  // 订单事件故事线（Item 7）
  orderStory(aggregateId: string): Promise<OrderStory> {
    return http.get<OrderStory>(`/ai/orders/${aggregateId}/story`).then((r) => r.data)
  },
}
