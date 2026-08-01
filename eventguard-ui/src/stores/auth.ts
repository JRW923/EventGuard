import { reactive } from 'vue'
import { AuthApi, type LoginResponse, type UserInfo } from '@/api/auth'
import { getStoredUser, getToken, setStoredUser, setToken } from '@/api/token'

// 轻量登录态单例（无 pinia）：token + 当前用户持久化到 localStorage。
// 鉴权断言统一走 hasPermission()，供路由守卫 / v-permission 指令 / 菜单过滤使用。
interface AuthState {
  token: string | null
  user: UserInfo | null
}

const state = reactive<AuthState>({
  token: getToken(),
  user: getStoredUser<UserInfo>(),
})

function persist() {
  setToken(state.token)
  setStoredUser(state.user)
}

export const auth = {
  get token() {
    return state.token
  },
  get user() {
    return state.user
  },
  get isAuthenticated() {
    return !!state.token
  },

  hasPermission(code?: string): boolean {
    if (!code) return true
    return state.user?.permissions?.includes(code) ?? false
  },

  async login(username: string, password: string): Promise<UserInfo> {
    const resp: LoginResponse = await AuthApi.login(username, password)
    state.token = resp.token
    state.user = resp.user
    persist()
    return resp.user
  },

  async fetchMe(): Promise<UserInfo> {
    const user = await AuthApi.me()
    state.user = user
    persist()
    return user
  },

  logout() {
    state.token = null
    state.user = null
    persist()
  },
}
