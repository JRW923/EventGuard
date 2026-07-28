import { http } from './http'
import { EventItem } from '@/types/event'

export interface OrderListItem {
  orderId: string
  status: string
  totalAmount: number
  version: number
  updatedAt: string
}

export interface OrderListResponse {
  orders: OrderListItem[]
  total: number
  page: number
  size: number
}

export const OrderApi = {
  list(status: string | null, page: number, size: number): Promise<OrderListResponse> {
    const params: Record<string, number | string> = { page, size }
    if (status) params.status = status
    return http.get<OrderListResponse>('/orders', { params }).then((r) => r.data)
  },

  get(orderId: string): Promise<OrderListItem> {
    return http.get<OrderListItem>(`/orders/${orderId}`).then((r) => r.data)
  },

  create(payload: { userId: string; totalAmount: number }): Promise<{ orderId: string }> {
    return http.post<{ orderId: string }>('/orders', payload).then((r) => r.data)
  },

  getEvents(orderId: string, upToVersion?: number): Promise<EventItem[]> {
    const params: Record<string, number> = {}
    if (upToVersion != null) params.upToVersion = upToVersion
    return http.get<EventItem[]>(`/orders/${orderId}/events`, { params }).then((r) => r.data)
  },

  getStats(status: string | null, from: string | null, to: string | null): Promise<any[]> {
    const params: Record<string, string> = {}
    if (status) params.status = status
    if (from) params.from = from
    if (to) params.to = to
    return http.get<any[]>('/orders/stats', { params }).then((r) => r.data)
  },
}
