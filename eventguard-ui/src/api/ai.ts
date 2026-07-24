import { http } from './http'

export interface QueryResult {
  intent: string  // event_lookup / stats_aggregation / trace_replay
  data: any
  answer: string
}

export const AiApi = {
  query(question: string): Promise<QueryResult> {
    return http.post<QueryResult>('/ai/query', { question }).then((r) => r.data)
  },
}
