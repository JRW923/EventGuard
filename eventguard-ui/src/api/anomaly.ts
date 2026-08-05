import { http } from './http'

export interface AnalysisReport {
  anomaly_id: string
  root_cause: string
  evidence: string[]
  suggestions: { action: string; reason: string; risk: string }[]
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
  // 最近告警历史（server 侧环形缓冲，最新在前）：WS 重连后补拉断线期间错过的告警
  getRecentAlerts(): Promise<AnomalyAlert[]> {
    return http.get<AnomalyAlert[]>('/alerts/recent').then((r) => r.data)
  },
}
