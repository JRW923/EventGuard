import { http } from './http'

export interface CompensationRequest {
  actionType: string
  aggregateId: string
  params?: Record<string, any>
}

export interface CompensationResult {
  success: boolean
  message: string
}

export interface SagaStep {
  actionType: string
  params?: Record<string, any>
}

export interface ApprovalItem {
  approvalId: string
  sagaId: string
  actionType: string
  aggregateId: string
  params?: Record<string, any>
  status: string // PENDING / APPROVED / REJECTED
  requestedBy: string
  requestedAt?: string
}

export const CompensationApi = {
  execute(req: CompensationRequest): Promise<CompensationResult> {
    return http.post<CompensationResult>('/compensations', req).then((r) => r.data)
  },
  // AI 建议的补偿步骤走 Saga（Item 6b）：高风险步自动落审批单
  startSaga(aggregateId: string, steps: SagaStep[]): Promise<{ status: string }> {
    return http.post<{ status: string }>('/compensations/saga', { aggregateId, steps }).then((r) => r.data)
  },
  // 审批清单（PENDING）
  listApprovals(): Promise<ApprovalItem[]> {
    return http.get<ApprovalItem[]>('/approvals').then((r) => r.data)
  },
  // 审批/拒绝
  decideApproval(approvalId: string, approve: boolean): Promise<string> {
    return http
      .post(`/approvals/${approvalId}/${approve ? 'approve' : 'reject'}`, { decidedBy: 'operator' })
      .then((r) => r.data)
  },
}
