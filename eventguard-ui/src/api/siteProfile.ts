import { http } from './http'
import type { ProfileContent } from '@/config/profileDefaults'
import { defaultProfile } from '@/config/profileDefaults'

/**
 * 个人主页内容：GET 公开（匿名可读）；PUT 需 user:manage。
 * 服务端未配置或请求失败时回落 defaultProfile，主页永远可渲染。
 */
export async function fetchProfile(): Promise<ProfileContent> {
  try {
    const { data } = await http.get<{ content: ProfileContent | null }>('/site-profile')
    if (data?.content && data.content.name) {
      // 浅合并默认值：编辑时只存了部分字段也能渲染（新字段上线不被旧数据遮蔽）
      return { ...defaultProfile, ...data.content }
    }
  } catch {
    // 匿名网络异常等：回落默认
  }
  return defaultProfile
}

export async function saveProfile(content: ProfileContent): Promise<void> {
  await http.put('/site-profile', content)
}

export { defaultProfile }
