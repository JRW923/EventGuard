import { mount, flushPromises } from '@vue/test-utils'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import ElementPlus from 'element-plus'
import OrderList from '../OrderList.vue'

// mock order API
vi.mock('../../api/order', () => ({
  OrderApi: {
    list: vi.fn(),
  },
}))

import { OrderApi } from '../../api/order'

describe('OrderList', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('挂载时调用 API 加载订单列表', async () => {
    ;(OrderApi.list as any).mockResolvedValue({
      orders: [
        { orderId: '11111111-1111-1111-1111-111111111111', status: 'PAID', totalAmount: 99, version: 2, updatedAt: '2026-07-21T10:00:00Z' },
      ],
      total: 1,
      page: 0,
      size: 20,
    })

    const wrapper = mount(OrderList, {
      global: { plugins: [ElementPlus] },
    })

    await flushPromises()

    expect(OrderApi.list).toHaveBeenCalledWith(null, 0, 20)
    expect(wrapper.text()).toContain('11111111-1111-1111-1111-111111111111')
    expect(wrapper.text()).toContain('PAID')
  })

  it('状态筛选变化时重新查询', async () => {
    ;(OrderApi.list as any).mockResolvedValue({ orders: [], total: 0, page: 0, size: 20 })

    const wrapper = mount(OrderList, {
      global: { plugins: [ElementPlus] },
    })
    await flushPromises()

    // 模拟状态筛选变化
    await wrapper.find('select[data-testid="status-filter"]').setValue('PAID')
    await flushPromises()

    expect(OrderApi.list).toHaveBeenLastCalledWith('PAID', 0, 20)
  })

  it('API 失败时显示错误提示', async () => {
    ;(OrderApi.list as any).mockRejectedValue(new Error('Network Error'))

    const wrapper = mount(OrderList, {
      global: { plugins: [ElementPlus] },
    })
    await flushPromises()

    expect(wrapper.text()).toContain('网络连接异常')
  })
})
