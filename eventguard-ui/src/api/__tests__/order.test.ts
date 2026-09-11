import { describe, it, expect, beforeEach, vi } from 'vitest'

// create() 依赖 crypto.randomUUID，stub 成递增序列以便断言 key 是否复用
let seq = 0
vi.stubGlobal('crypto', { randomUUID: () => `uuid-${++seq}` })

const { post } = vi.hoisted(() => ({ post: vi.fn() }))
vi.mock('@/api/http', () => ({ http: { post } }))

const { OrderApi } = await import('@/api/order')

/** 取第 i 次调用携带的 X-Command-Id。 */
function commandIdAt(i: number): string {
  return post.mock.calls[i][2].headers['X-Command-Id']
}

describe('OrderApi.create 幂等键', () => {
  beforeEach(() => {
    post.mockReset()
    post.mockResolvedValue({ data: { orderId: 'o-1' } })
  })

  it('同一 payload 并发重复提交复用同一个 commandId', async () => {
    // 模拟用户连点：两次请求都在首次成功回调之前发出
    const p1 = OrderApi.create({ userId: 'u1', totalAmount: 100 })
    const p2 = OrderApi.create({ userId: 'u1', totalAmount: 100 })
    await Promise.all([p1, p2])

    expect(post).toHaveBeenCalledTimes(2)
    expect(commandIdAt(0)).toBe(commandIdAt(1))
  })

  it('payload 变化后生成新的 commandId', async () => {
    await OrderApi.create({ userId: 'u1', totalAmount: 100 })
    await OrderApi.create({ userId: 'u1', totalAmount: 200 })

    // 复用旧 key 会被后端指纹校验拒绝（commandId 已用于不同参数），必须换新
    expect(commandIdAt(0)).not.toBe(commandIdAt(1))
  })

  it('成功后再次提交同一 payload 生成新 commandId（允许下新单）', async () => {
    await OrderApi.create({ userId: 'u1', totalAmount: 100 })
    await OrderApi.create({ userId: 'u1', totalAmount: 100 })

    expect(commandIdAt(0)).not.toBe(commandIdAt(1))
  })
})
