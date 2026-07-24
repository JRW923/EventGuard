import { describe, it, expect } from 'vitest'
import { http } from '../http'

describe('http client', () => {
  it('attaches X-API-Key header from env', () => {
    expect(http.defaults.headers.common['X-API-Key']).toBe(import.meta.env.VITE_API_KEY)
  })
})
