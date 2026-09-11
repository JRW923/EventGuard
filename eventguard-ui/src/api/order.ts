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

/**
 * 幂等键按「用户意图」粒度复用，而不是按 HTTP 请求：同一份 payload 重复提交
 * （用户连点、超时后手动重试）复用同一个 X-Command-Id，后端 command_log 命中
 * 首次结果，不会创建出多笔订单。
 *
 * ponytail: 只缓存最近一份意图，且与 payload 指纹绑定——换 payload 就换 key。
 * 否则「改了金额再提交」会复用旧 commandId，后端指纹校验会抛
 * 「commandId 已用于不同的订单、命令类型或参数」。
 */
let pendingIntent: { commandId: string; fingerprint: string } | null = null

function commandIdFor(payload: { userId: string; totalAmount: number }): string {
  const fingerprint = `${payload.userId}|${payload.totalAmount}`
  if (pendingIntent?.fingerprint === fingerprint) return pendingIntent.commandId
  const commandId = crypto.randomUUID()
  pendingIntent = { commandId, fingerprint }
  return commandId
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
    return http.post<{ orderId: string }>('/orders', payload, {
      headers: { 'X-Command-Id': commandIdFor(payload) },
    }).then((r) => {
      // 仅在成功后清空：失败时保留 key，重试同一份 payload 仍走幂等路径
      pendingIntent = null
      return r.data
    })
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
