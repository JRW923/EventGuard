import { mount } from '@vue/test-utils'
import { describe, it, expect, vi } from 'vitest'
import EventTimeline from '../EventTimeline.vue'
import { EventItem } from '@/types/event'

// mock vue-echarts 避免实际渲染 canvas
vi.mock('vue-echarts', () => ({
  default: {
    name: 'VChart',
    props: ['option', 'autoresize'],
    render: () => null,
  },
}))

describe('EventTimeline', () => {
  it('传入空事件列表时不渲染图表', () => {
    const wrapper = mount(EventTimeline, {
      props: { events: [] },
    })
    expect(wrapper.find('[data-testid="timeline-empty"]').exists()).toBe(true)
  })

  it('empty events renders only el-empty, no table', async () => {
    const wrapper = mount(EventTimeline, { props: { events: [] } })
    expect(wrapper.find('[data-testid="timeline-empty"]').exists()).toBe(true)
    // ponytail: jsdom 下 Element Plus 不会给 <el-table> 加 .el-table 类（类在 layout 后注入），
    // 故用标签选择器检测真实渲染节点
    expect(wrapper.find('el-table').exists()).toBe(false)
  })

  it('传入事件列表时构建 ECharts option', () => {
    const events = [
      { eventId: 'e1', aggregateId: 'a1', eventType: 'OrderCreatedEvent', version: 1, createdAt: '2026-07-21T10:00:00Z', payload: { amount: 99 } },
      { eventId: 'e2', aggregateId: 'a1', eventType: 'PaymentCompletedEvent', version: 2, createdAt: '2026-07-21T10:05:00Z', payload: {} },
    ]

    const wrapper = mount(EventTimeline, {
      props: { events },
    })

    // 验证构建了 option（通过组件暴露的 computeOption 或检查 props 传递）
    const chart = wrapper.findComponent({ name: 'VChart' })
    expect(chart.exists()).toBe(true)
    const option = chart.props('option')
    expect(option).toBeTruthy()
    expect(option.series).toBeDefined()
    expect(option.xAxis.data).toContain('OrderCreatedEvent')
    expect(option.xAxis.data).toContain('PaymentCompletedEvent')
  })

  it('事件节点按 version 排序', () => {
    const events = [
      { eventId: 'e2', aggregateId: 'a1', eventType: 'PaymentCompletedEvent', version: 2, createdAt: '2026-07-21T10:05:00Z', payload: {} },
      { eventId: 'e1', aggregateId: 'a1', eventType: 'OrderCreatedEvent', version: 1, createdAt: '2026-07-21T10:00:00Z', payload: {} },
    ]

    const wrapper = mount(EventTimeline, {
      props: { events },
    })

    const chart = wrapper.findComponent({ name: 'VChart' })
    const option = chart.props('option')
    expect(option.xAxis.data[0]).toBe('OrderCreatedEvent')
    expect(option.xAxis.data[1]).toBe('PaymentCompletedEvent')
  })
})
