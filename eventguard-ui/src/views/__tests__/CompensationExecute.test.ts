import { mount, flushPromises } from '@vue/test-utils'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import ElementPlus from 'element-plus'
import CompensationExecute from '../CompensationExecute.vue'

vi.mock('../../api/compensation', () => ({
  CompensationApi: {
    execute: vi.fn(),
  },
}))

import { CompensationApi } from '../../api/compensation'

describe('CompensationExecute', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('从路由 query 预填 actionType 与 aggregateId', async () => {
    const wrapper = mount(CompensationExecute, {
      global: {
        plugins: [ElementPlus],
      },
      props: {
        initialActionType: 'FREEZE_ORDER',
        initialAggregateId: 'agg-from-anomaly',
      },
    })

    await flushPromises()

    expect(wrapper.find('[data-testid="action-type"]').attributes('value') || (wrapper.find('[data-testid="action-type"]').element as any).value).toContain('FREEZE_ORDER')
  })

  it('点击执行按钮调用 CompensationApi.execute', async () => {
    ;(CompensationApi.execute as any).mockResolvedValue({ success: true, message: '补偿已执行' })

    const wrapper = mount(CompensationExecute, {
      global: { plugins: [ElementPlus] },
    })

    await wrapper.find('[data-testid="aggregate-id"]').setValue('11111111-1111-1111-1111-111111111111')
    await wrapper.find('button[data-testid="execute-btn"]').trigger('click')
    await flushPromises()

    expect(CompensationApi.execute).toHaveBeenCalled()
    expect(wrapper.text()).toContain('补偿已执行')
  })

  it('执行失败时显示错误', async () => {
    ;(CompensationApi.execute as any).mockRejectedValue(new Error('不在白名单'))

    const wrapper = mount(CompensationExecute, {
      global: { plugins: [ElementPlus] },
    })

    await wrapper.find('[data-testid="aggregate-id"]').setValue('11111111-1111-1111-1111-111111111111')
    await wrapper.find('button[data-testid="execute-btn"]').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('不在白名单')
  })
})
