import { http } from './http'

export interface AnalysisReport {
  anomaly_id: string
  root_cause: string
  evidence: string[]
  suggestions: { action: string; reason: string; risk: string }[]
}

export interface AgentTraceStep {
  step: number
  tool: string
  input: Record<string, any>
  output: any
}

export interface HealResult {
  report: AnalysisReport
  agent_trace: AgentTraceStep[]
  note?: string
}

export interface SimilarCase {
  similarity: number
  case_anomaly_id: string
  rule_id: string
  aggregate_id: string
  event_type?: string
  level: string
  detected_at: string
  description: string
  resolution: string
}

export interface AnomalyAlert {
  anomaly_id: string
  rule_id: string
  aggregate_id: string
  event_type?: string
  level: string
  source?: string
  priority?: string
  detected_at: string
  description: string
  details?: Record<string, any>
}

export const AnomalyApi = {
  getAnalysis(anomalyId: string): Promise<AnalysisReport> {
    return http.get<AnalysisReport>(`/anomalies/${anomalyId}/analysis`).then((r) => r.data)
  },
  // ReAct 深度分析（Item 6a）：agent 多轮工具调用收集证据 → 报告 + 分析过程 trace
  // agent 有多轮 LLM 调用，放宽单请求超时（默认 axios 10s 不够）
  healAnomaly(anomalyId: string): Promise<HealResult> {
    return http.post<HealResult>(`/ai/heal/${anomalyId}`, {}, { timeout: 30000 }).then((r) => r.data)
  },
  // 最近告警历史（server 侧环形缓冲，最新在前）：WS 重连后补拉断线期间错过的告警
  getRecentAlerts(): Promise<AnomalyAlert[]> {
    return http.get<AnomalyAlert[]>('/alerts/recent').then((r) => r.data)
  },
  // 相似案例检索（Item 8 · 轻量 RAG）
  similarCases(anomalyId: string, topK = 5): Promise<{ anomaly_id: string; cases: SimilarCase[]; message?: string }> {
    return http.get(`/ai/cases/${anomalyId}/similar`, { params: { top_k: topK } }).then((r) => r.data)
  },
}
