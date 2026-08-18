import { http } from './http'

// 用户自己的 LLM 配置（存 Java 侧，API key AES 加密，对外只回掩码）。
export interface LlmConfig {
  provider: '' | 'openai' | 'anthropic'
  baseUrl: string
  model: string
  maxTokens: number
  temperature: number
  apiKeyMasked: string
  hasApiKey: boolean
}

export interface LlmConfigPayload {
  provider: '' | 'openai' | 'anthropic'
  baseUrl: string
  apiKey?: string
  model: string
  maxTokens: number
  temperature: number
}

export const LlmApi = {
  get(): Promise<LlmConfig> {
    return http.get<LlmConfig>('/users/me/llm-config').then((r) => r.data)
  },
  update(payload: LlmConfigPayload): Promise<LlmConfig> {
    return http.put<LlmConfig>('/users/me/llm-config', payload).then((r) => r.data)
  },
}
