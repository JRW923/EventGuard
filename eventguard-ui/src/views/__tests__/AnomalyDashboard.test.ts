import { mount, flushPromises } from '@vue/test-utils'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { ref, nextTick } from 'vue'
import ElementPlus from 'element-plus'
import AnomalyDashboard from '../AnomalyDashboard.vue'

// mock anomaly API
vi.mock('../../api/anomaly', () => ({
  AnomalyApi: {
    getAnalysis: vi.fn(),
  },
}))

// mock WebSocket composable（用真实 ref，与组件模板自动解包行为一致）
vi.mock('../../composables/useAnomalyWebSocket', () => ({
  useAnomalyWebSocket: vi.fn(() => ({
    alerts: ref([]),
    connected: ref(false),
  })),
}))

import { AnomalyApi } from '../../api/anomaly'
import { useAnomalyWebSocket } from '../../composables/useAnomalyWebSocket'

describe('AnomalyDashboard', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('挂载时初始化 WebSocket 连接', () => {
    mount(AnomalyDashboard, {
      global: { plugins: [ElementPlus] },
    })
    expect(useAnomalyWebSocket).toHaveBeenCalled()
  })

  it('WebSocket alerts 变化时渲染告警列表', async () => {
    ;(useAnomalyWebSocket as any).mockReturnValue({
      alerts: ref([
        {
          anomaly_id: 'a-1',
          rule_id: 'R001',
          aggregate_id: '11111111-1111-1111-1111-111111111111',
          level: 'ERROR',
          description: '金额偏离',
          detected_at: '2026-07-21T10:00:00Z',
        },
      ]),
      connected: ref(true),
    })

    const wrapper = mount(AnomalyDashboard, {
      global: { plugins: [ElementPlus] },
    })

    // el-table 行渲染为异步，需等待 tick
    await flushPromises()
    await nextTick()

    expect(wrapper.text()).toContain('a-1')
    expect(wrapper.text()).toContain('金额偏离')
    expect(wrapper.text()).toContain('ERROR')
  })

  it('点击异常项调用 getAnalysis 并显示根因报告', async () => {
    ;(useAnomalyWebSocket as any).mockReturnValue({
      alerts: ref([
        { anomaly_id: 'a-1', rule_id: 'R001', aggregate_id: 'agg-1', level: 'ERROR', description: '金额偏离', detected_at: '2026-07-21T10:00:00Z' },
      ]),
      connected: ref(true),
    })
    ;(AnomalyApi.getAnalysis as any).mockResolvedValue({
      anomaly_id: 'a-1',
      root_cause: '订单金额偏离用户历史均值 3σ',
      evidence: ['均值 100，本次 500'],
      suggestions: [{ action: 'FREEZE_ORDER', reason: '冻结订单', risk: 'LOW' }],
    })

    const wrapper = mount(AnomalyDashboard, {
      global: { plugins: [ElementPlus] },
    })

    // 等待告警行渲染
    await flushPromises()
    await nextTick()

    // 点击第一条异常
    await wrapper.find('[data-testid="anomaly-item-a-1"]').trigger('click')
    await flushPromises()

    expect(AnomalyApi.getAnalysis).toHaveBeenCalledWith('a-1')
    expect(wrapper.text()).toContain('订单金额偏离用户历史均值')
    expect(wrapper.text()).toContain('FREEZE_ORDER')
  })
})
