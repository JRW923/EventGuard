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
}
