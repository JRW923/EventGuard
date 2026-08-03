import { http } from './http'

export interface UserInfo {
  id: number
  username: string
  displayName: string
  enabled: boolean
  mustChangePassword: boolean
  roles: string[]
  permissions: string[]
}

export interface LoginResponse {
  token: string
  user: UserInfo
}

export interface PermissionItem {
  id: number
  code: string
  description: string
}

export interface RoleItem {
  id: number
  code: string
  name: string
  description: string
  permissions: string[]
}

export const AuthApi = {
  login(username: string, password: string): Promise<LoginResponse> {
    return http.post<LoginResponse>('/auth/login', { username, password }).then((r) => r.data)
  },

  me(): Promise<UserInfo> {
    return http.get<UserInfo>('/auth/me').then((r) => r.data)
  },

  logout(): Promise<void> {
    return http.post('/auth/logout').then(() => undefined)
  },

  logoutAll(): Promise<void> {
    return http.post('/auth/logout-all').then(() => undefined)
  },

  changePassword(oldPassword: string, newPassword: string): Promise<void> {
    return http.post('/auth/password', { oldPassword, newPassword }).then(() => undefined)
  },

  // —— 用户管理（user:manage）——
  listUsers(): Promise<UserInfo[]> {
    return http.get<UserInfo[]>('/users').then((r) => r.data)
  },

  createUser(payload: {
    username: string
    password: string
    displayName?: string
    enabled?: boolean
    roleIds: number[]
  }): Promise<UserInfo> {
    return http.post<UserInfo>('/users', payload).then((r) => r.data)
  },

  updateUser(id: number, payload: { displayName?: string; enabled?: boolean; roleIds: number[] }): Promise<UserInfo> {
    return http.put<UserInfo>(`/users/${id}`, payload).then((r) => r.data)
  },

  resetPassword(id: number, newPassword: string): Promise<void> {
    return http.post(`/users/${id}/reset-password`, { newPassword }).then(() => undefined)
  },

  deleteUser(id: number): Promise<void> {
    return http.delete(`/users/${id}`).then(() => undefined)
  },

  // —— 角色/权限管理（role:manage）——
  listRoles(): Promise<RoleItem[]> {
    return http.get<RoleItem[]>('/roles').then((r) => r.data)
  },

  createRole(payload: { code: string; name: string; description?: string; permissions: string[] }): Promise<RoleItem> {
    return http.post<RoleItem>('/roles', payload).then((r) => r.data)
  },

  updateRole(id: number, payload: { name: string; description?: string; permissions: string[] }): Promise<RoleItem> {
    return http.put<RoleItem>(`/roles/${id}`, payload).then((r) => r.data)
  },

  deleteRole(id: number): Promise<void> {
    return http.delete(`/roles/${id}`).then(() => undefined)
  },

  listPermissions(): Promise<PermissionItem[]> {
    return http.get<PermissionItem[]>('/roles/permissions').then((r) => r.data)
  },

  // —— 审计日志（user:manage）——
  listAuditLogs(params: { page?: number; size?: number; username?: string }): Promise<AuditLogItem[]> {
    return http
      .get<AuditLogItem[]>('/audit-logs', { params: { page: params.page ?? 0, size: params.size ?? 50, username: params.username || undefined } })
      .then((r) => r.data)
  },
}

export interface AuditLogItem {
  id: number
  username: string
  action: string
  detail: string
  ip: string
  createdAt: string
}
