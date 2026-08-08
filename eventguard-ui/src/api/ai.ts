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

export const AiApi = {
  query(question: string, conversationId?: string): Promise<QueryResult> {
    const payload: Record<string, unknown> = { question }
    if (conversationId) payload.conversation_id = conversationId
    return http.post<QueryResult>('/ai/query', payload).then((r) => r.data)
  },
}
