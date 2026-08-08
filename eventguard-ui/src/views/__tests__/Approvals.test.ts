import { mount, flushPromises } from '@vue/test-utils'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import ElementPlus from 'element-plus'
import Approvals from '../Approvals.vue'

vi.mock('../../api/compensation', () => ({
  CompensationApi: {
    listApprovals: vi.fn(),
    decideApproval: vi.fn(),
  },
}))

import { CompensationApi } from '../../api/compensation'

const approval = {
  approvalId: 'ap-1',
  sagaId: 's-1',
  actionType: 'REFUND',
  aggregateId: 'agg-1',
  params: { amount: 200 },
  status: 'PENDING',
  requestedBy: 'saga',
  requestedAt: '2026-08-08T10:00:00Z',
}

describe('Approvals', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('加载并渲染待审批列表', async () => {
    ;(CompensationApi.listApprovals as any).mockResolvedValue([approval])

    const wrapper = mount(Approvals, {
      global: { plugins: [ElementPlus] },
    })
    await flushPromises()

    expect(CompensationApi.listApprovals).toHaveBeenCalled()
    expect(wrapper.text()).toContain('ap-1')
    expect(wrapper.text()).toContain('REFUND')
    expect(wrapper.text()).toContain('saga')
  })

  it('点击批准调用 decideApproval(true) 并刷新', async () => {
    ;(CompensationApi.listApprovals as any).mockResolvedValue([approval])
    ;(CompensationApi.decideApproval as any).mockResolvedValue('COMPLETED')

    const wrapper = mount(Approvals, {
      global: { plugins: [ElementPlus] },
    })
    await flushPromises()

    await wrapper.findAll('button').find((b) => b.text() === '批准')!.trigger('click')
    await flushPromises()

    expect(CompensationApi.decideApproval).toHaveBeenCalledWith('ap-1', true)
  })

  it('空列表显示占位提示', async () => {
    ;(CompensationApi.listApprovals as any).mockResolvedValue([])

    const wrapper = mount(Approvals, {
      global: { plugins: [ElementPlus] },
    })
    await flushPromises()

    expect(wrapper.text()).toContain('暂无待审批项')
  })
})
