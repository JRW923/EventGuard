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
}
