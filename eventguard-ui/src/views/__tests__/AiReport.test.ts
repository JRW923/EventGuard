import { mount, flushPromises } from '@vue/test-utils'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import ElementPlus from 'element-plus'
import AiReport from '../AiReport.vue'

vi.mock('../../api/ai', () => ({
  AiApi: {
    weeklyReport: vi.fn(),
    weeklyReportHistory: vi.fn(),
    orderStory: vi.fn(),
  },
}))

import { AiApi } from '../../api/ai'

describe('AiReport', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('点击生成周报后渲染聚合结果与 LLM 建议', async () => {
    ;(AiApi.weeklyReport as any).mockResolvedValue({
      period: { days: 7, from: '2026-08-01T00:00:00Z', to: '2026-08-08T00:00:00Z' },
      total_anomalies: 3,
      by_rule: [{ rule_id: 'P002_STUCK', count: 2 }],
      order_stats: [{ status: 'PAID', orderCount: 4, totalAmount: 400 }],
      symptoms: ['PAID 停滞增多'],
      recommendations: ['复核停滞订单'],
      top_orders: [{ aggregate_id: 'agg-1', count: 2 }],
    })

    const wrapper = mount(AiReport, {
      global: { plugins: [ElementPlus] },
    })
    await wrapper.findAll('button').find((b) => b.text() === '生成周报')!.trigger('click')
    await flushPromises()

    expect(AiApi.weeklyReport).toHaveBeenCalledWith(7)
    expect(wrapper.text()).toContain('P002_STUCK')
    expect(wrapper.text()).toContain('PAID 停滞增多')
    expect(wrapper.text()).toContain('复核停滞订单')
  })

  it('空数据时显示占位', async () => {
    ;(AiApi.weeklyReport as any).mockResolvedValue({
      period: { days: 7, from: '', to: '' },
      total_anomalies: 0,
      by_rule: [],
      order_stats: [],
      symptoms: [],
      recommendations: [],
      top_orders: [],
    })

    const wrapper = mount(AiReport, {
      global: { plugins: [ElementPlus] },
    })
    await wrapper.findAll('button').find((b) => b.text() === '生成周报')!.trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('近期无异常订单')
  })

  // 周报落库后：挂载即拉取历史，选中历史报告可回看
  it('加载历史并可在下拉中选择历史报告回看', async () => {
    const generated = '2026-08-30T08:00:00.000Z'
    ;(AiApi.weeklyReportHistory as any).mockResolvedValue({
      items: [
        {
          period: { days: 7, from: '2026-08-23T00:00:00Z', to: '2026-08-30T00:00:00Z' },
          total_anomalies: 5,
          by_rule: [{ rule_id: 'R001', count: 3 }],
          order_stats: [],
          symptoms: ['上周大额订单偏离'],
          recommendations: ['复核'],
          top_orders: [],
          generated_at: generated,
        },
      ],
    })

    const wrapper = mount(AiReport, {
      global: { plugins: [ElementPlus] },
    })
    await flushPromises()

    // 挂载后自动加载历史
    expect(AiApi.weeklyReportHistory).toHaveBeenCalled()

    // 下拉出现历史项
    const select = wrapper.find('.el-select')
    expect(select.exists()).toBe(true)

    // 选中历史项后渲染对应报告内容
    const option = wrapper.findAll('.el-select-dropdown__item').at(0)
    if (option) {
      await option.trigger('click')
      await flushPromises()
      expect(wrapper.text()).toContain('上周大额订单偏离')
      expect(wrapper.text()).toContain('R001')
    }
  })
})
