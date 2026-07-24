export interface EventItem {
  eventId: string
  aggregateId: string
  eventType: string
  version: number
  createdAt: string
  payload: Record<string, any>
}
