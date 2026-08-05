import { ref, onMounted, onUnmounted, type Ref } from 'vue'
import type { AnomalyAlert } from '../api/anomaly'
import { AnomalyApi } from '../api/anomaly'
import { getToken } from '../api/token'

/**
 * 异常告警 WebSocket composable。
 *
 * 连接 /ws/anomalies，实时推送异常告警到 alerts 列表（保留最近 100 条）。
 * 断线重连后调用 /alerts/recent 补拉断线期间错过的告警（按 anomaly_id 去重）。
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
  // 已见过的告警 id：WS 实时推送与 /alerts/recent 补拉共用同一去重集合，
  // 避免「重连瞬间同一条告警既走 WS 又走补拉」被重复插入列表。
  const seen = new Set<string>()

  // 默认连接同源 /ws/anomalies（vite proxy 转发到后端 8080）
  // ponytail: 按页面协议推导 ws/wss，否则 https 部署会因 mixed-content 拒绝连接
  const wsProto = window.location.protocol === 'https:' ? 'wss' : 'ws'
  // 登录 JWT 经查询参数 token 传递（浏览器 WS 无法带自定义头），后端握手拦截器校验
  const token = getToken()
  const wsUrl = url || `${wsProto}://${window.location.host}/ws/anomalies${token ? `?token=${token}` : ''}`

  function pushAlert(alert: AnomalyAlert) {
    if (!alert?.anomaly_id || seen.has(alert.anomaly_id)) return
    seen.add(alert.anomaly_id)
    alerts.value.unshift(alert)
    // 保留最近 100 条
    if (alerts.value.length > 100) {
      alerts.value = alerts.value.slice(0, 100)
    }
  }

  // 连接(含重连)后补拉 server 侧最近告警；已见过的去重，未见过的按 detected_at 归位
  async function backfill() {
    try {
      const recent = await AnomalyApi.getRecentAlerts()
      const unseen = (recent || []).filter((a) => a?.anomaly_id && !seen.has(a.anomaly_id))
      if (!unseen.length) return
      const merged = [...unseen, ...alerts.value]
      merged.sort((a, b) => String(b.detected_at || '').localeCompare(String(a.detected_at || '')))
      alerts.value = merged.slice(0, 100)
      unseen.forEach((a) => a.anomaly_id && seen.add(a.anomaly_id))
    } catch (e) {
      // 补拉失败不阻断 WS 实时推送（历史接口只是增强）
      console.warn('[WS] 补拉最近告警失败', e)
    }
  }

  function connect() {
    if (disposed) return
    try {
      ws = new WebSocket(wsUrl)
      ws.onopen = () => {
        connected.value = true
        backfill()
      }
      ws.onclose = () => {
        connected.value = false
        // ponytail: 仅简单重连（固定 3s 间隔），无指数退避；如后端长期不可用会持续重试
        scheduleReconnect()
      }
      ws.onerror = () => { connected.value = false }
      ws.onmessage = (ev) => {
        try {
          pushAlert(JSON.parse(ev.data))
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
