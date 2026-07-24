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

export const CompensationApi = {
  execute(req: CompensationRequest): Promise<CompensationResult> {
    return http.post<CompensationResult>('/compensations', req).then((r) => r.data)
  },
}
