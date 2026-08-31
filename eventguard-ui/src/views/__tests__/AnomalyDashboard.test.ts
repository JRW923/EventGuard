import { mount, flushPromises } from '@vue/test-utils'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { ref, nextTick } from 'vue'
import ElementPlus from 'element-plus'
import AnomalyDashboard from '../AnomalyDashboard.vue'

// mock anomaly API
vi.mock('../../api/anomaly', () => ({
  AnomalyApi: {
    getAnalysis: vi.fn(),
    similarCases: vi.fn(),
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
    // 根因对话框只保留统一 Saga 提交入口，单条执行降级到补偿页能力
    expect(wrapper.text()).not.toContain('执行 FREEZE_ORDER')
    expect(wrapper.text()).toContain('发起补偿审批')
  })

  // 回归：此前失败只 console.error，对话框正文依赖 currentReport 而其为 null，
  // 用户看到的是一个空对话框，没有任何错误提示
  it('getAnalysis 失败时对话框给出失败原因与重试入口，而非空白', async () => {
    ;(useAnomalyWebSocket as any).mockReturnValue({
      alerts: ref([
        { anomaly_id: 'a-1', rule_id: 'R001', aggregate_id: 'agg-1', level: 'ERROR', description: '金额偏离', detected_at: '2026-07-21T10:00:00Z' },
      ]),
      connected: ref(true),
    })
    ;(AnomalyApi.getAnalysis as any).mockRejectedValue({ response: { status: 409 } })

    const wrapper = mount(AnomalyDashboard, {
      global: { plugins: [ElementPlus] },
      attachTo: document.body,
    })

    await flushPromises()
    await nextTick()
    await wrapper.find('[data-testid="anomaly-item-a-1"]').trigger('click')
    await flushPromises()

    const text = document.body.textContent ?? ''
    expect(text).toContain('分析失败')
    expect(text).toContain('操作冲突，请刷新后重试')

    // 重试按钮重新发起请求，成功后回到报告视图
    const retry = wrapper.findAll('button').find((b) => b.text() === '重试')
    expect(retry).toBeTruthy()
    ;(AnomalyApi.getAnalysis as any).mockResolvedValue({
      anomaly_id: 'a-1',
      root_cause: '订单金额偏离用户历史均值 3σ',
      evidence: ['均值 100，本次 500'],
      suggestions: [{ action: 'FREEZE_ORDER', reason: '冻结订单', risk: 'LOW' }],
    })
    await retry!.trigger('click')
    await flushPromises()

    expect(AnomalyApi.getAnalysis).toHaveBeenCalledTimes(2)
    expect(document.body.textContent ?? '').toContain('订单金额偏离用户历史均值')
    wrapper.unmount()
  })

  // 相似案例后端独立于根因分析，不应被报告容器的 v-if 连带隐藏
  it('根因分析失败时，相似案例仍可独立加载并展示', async () => {
    ;(useAnomalyWebSocket as any).mockReturnValue({
      alerts: ref([
        { anomaly_id: 'a-1', rule_id: 'R001', aggregate_id: 'agg-1', level: 'ERROR', description: '金额偏离', detected_at: '2026-07-21T10:00:00Z' },
      ]),
      connected: ref(true),
    })
    ;(AnomalyApi.getAnalysis as any).mockRejectedValue({ response: { status: 409 } })
    ;(AnomalyApi.similarCases as any).mockResolvedValue({
      anomaly_id: 'a-1',
      cases: [
        {
          similarity: 0.82,
          case_anomaly_id: 'old-1',
          rule_id: 'R001',
          aggregate_id: 'agg-9',
          level: 'ERROR',
          detected_at: '2026-07-01T10:00:00Z',
          description: '历史金额偏离',
          resolution: 'FREEZE_ORDER',
        },
      ],
    })

    const wrapper = mount(AnomalyDashboard, {
      global: { plugins: [ElementPlus] },
      attachTo: document.body,
    })

    await flushPromises()
    await nextTick()
    await wrapper.find('[data-testid="anomaly-item-a-1"]').trigger('click')
    await flushPromises()

    // 报告区是失败态
    expect(document.body.textContent ?? '').toContain('分析失败')

    // 案例区在报告容器外，依然可用
    const loadBtn = wrapper.findAll('button').find((b) => b.text() === '加载相似案例')
    expect(loadBtn).toBeTruthy()
    await loadBtn!.trigger('click')
    await flushPromises()
    await nextTick()

    expect(AnomalyApi.similarCases).toHaveBeenCalledWith('a-1')
    const text = document.body.textContent ?? ''
    expect(text).toContain('历史金额偏离')
    expect(text).toContain('82%')
    expect(text).toContain('FREEZE_ORDER')
    wrapper.unmount()
  })
})
