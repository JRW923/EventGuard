import { ref, onMounted, onUnmounted, type Ref } from 'vue'
import type { AnomalyAlert } from '../api/anomaly'

/**
 * 异常告警 WebSocket composable。
 *
 * 连接 /ws/anomalies，实时推送异常告警到 alerts 列表（保留最近 100 条）。
 */
export function useAnomalyWebSocket(url?: string): {
  alerts: Ref<AnomalyAlert[]>
  connected: Ref<boolean>
} {
  const alerts = ref<AnomalyAlert[]>([])
  const connected = ref(false)
  let ws: WebSocket | null = null
  let reconnectTimer: ReturnType<typeof setTimeout> | null = null
  let disposed = false

  // 默认连接同源 /ws/anomalies（vite proxy 转发到后端 8080）
  const wsUrl = url || `ws://${window.location.host}/ws/anomalies`

  function connect() {
    if (disposed) return
    try {
      ws = new WebSocket(wsUrl)
      ws.onopen = () => { connected.value = true }
      ws.onclose = () => {
        connected.value = false
        // ponytail: 仅简单重连（固定 3s 间隔），无指数退避；如后端长期不可用会持续重试
        scheduleReconnect()
      }
      ws.onerror = () => { connected.value = false }
      ws.onmessage = (ev) => {
        try {
          const alert: AnomalyAlert = JSON.parse(ev.data)
          alerts.value.unshift(alert)
          // 保留最近 100 条
          if (alerts.value.length > 100) {
            alerts.value = alerts.value.slice(0, 100)
          }
        } catch (e) {
          console.error('[WS] 解析告警失败', e)
        }
      }
    } catch (e) {
      console.error('[WS] 连接失败', e)
      scheduleReconnect()
    }
  }

  function scheduleReconnect() {
    if (disposed || reconnectTimer) return
    reconnectTimer = setTimeout(() => {
      reconnectTimer = null
      connect()
    }, 3000)
  }

  onMounted(() => {
    connect()
  })

  onUnmounted(() => {
    disposed = true
    if (reconnectTimer) {
      clearTimeout(reconnectTimer)
      reconnectTimer = null
    }
    if (ws) {
      // ponytail: 置空 onclose 防止 close 触发 scheduleReconnect 在卸载后新建定时器
      ws.onclose = null
      ws.close()
      ws = null
    }
  })

  return { alerts, connected }
}
