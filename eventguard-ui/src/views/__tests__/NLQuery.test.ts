import { mount, flushPromises } from '@vue/test-utils'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import ElementPlus from 'element-plus'
import NLQuery from '../NLQuery.vue'

vi.mock('../../api/ai', () => ({
  AiApi: {
    query: vi.fn(),
  },
}))

import { AiApi } from '../../api/ai'

describe('NLQuery', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('输入问题并提交后调用 AiApi.query', async () => {
    ;(AiApi.query as any).mockResolvedValue({
      intent: 'event_lookup',
      data: { orderId: 'abc', status: 'PAID' },
      answer: '订单 abc 当前状态为 PAID。',
    })

    const wrapper = mount(NLQuery, {
      global: { plugins: [ElementPlus] },
    })

    await wrapper.find('input[data-testid="question-input"]').setValue('订单 abc 当前状态？')
    await wrapper.find('button[data-testid="submit-btn"]').trigger('click')
    await flushPromises()

    expect(AiApi.query).toHaveBeenCalledWith('订单 abc 当前状态？')
    expect(wrapper.text()).toContain('订单 abc 当前状态为 PAID')
    expect(wrapper.text()).toContain('event_lookup')
  })

  it('stats_aggregation 结果展示统计表格', async () => {
    ;(AiApi.query as any).mockResolvedValue({
      intent: 'stats_aggregation',
      data: [{ status: 'PAID', orderCount: 5, totalAmount: 495 }],
      answer: '昨天有 5 个 PAID 订单。',
    })

    const wrapper = mount(NLQuery, {
      global: { plugins: [ElementPlus] },
    })

    await wrapper.find('input[data-testid="question-input"]').setValue('昨天有多少 PAID 订单？')
    await wrapper.find('button[data-testid="submit-btn"]').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('5 个 PAID')
    expect(wrapper.text()).toContain('stats_aggregation')
  })

  it('查询失败时显示错误提示', async () => {
    ;(AiApi.query as any).mockRejectedValue(new Error('AI 服务不可用'))

    const wrapper = mount(NLQuery, {
      global: { plugins: [ElementPlus] },
    })

    await wrapper.find('input[data-testid="question-input"]').setValue('测试问题')
    await wrapper.find('button[data-testid="submit-btn"]').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('查询失败')
  })
})
