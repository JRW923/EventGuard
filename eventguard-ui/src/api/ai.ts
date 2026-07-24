import { http } from './http'

export interface QueryResult {
  intent: string  // event_lookup / stats_aggregation / trace_replay
  // ponytail: data 暂用 any + 前端 JSON 回退展示；待 /ai/query 的 intent schema 稳定后改为判别联合类型
  data: any
  answer: string
}

export const AiApi = {
  query(question: string): Promise<QueryResult> {
    return http.post<QueryResult>('/ai/query', { question }).then((r) => r.data)
  },
}
