import { http } from './http'

export interface LlmSettings {
  provider: '' | 'openai' | 'anthropic'
  base_url: string
  model: string
  max_tokens: number
  temperature: number
  api_key_masked: string
  has_api_key: boolean
  using_defaults: boolean
}

export interface LlmSettingsPayload {
  provider: '' | 'openai' | 'anthropic'
  base_url: string
  api_key?: string
  model: string
  max_tokens: number
  temperature: number
}

export const LlmApi = {
  get(): Promise<LlmSettings> {
    return http.get<LlmSettings>('/ai/settings/llm').then((r) => r.data)
  },
  update(payload: LlmSettingsPayload): Promise<LlmSettings> {
    return http.put<LlmSettings>('/ai/settings/llm', payload).then((r) => r.data)
  },
  reset(): Promise<LlmSettings> {
    return http.post<LlmSettings>('/ai/settings/llm/reset').then((r) => r.data)
  },
}

