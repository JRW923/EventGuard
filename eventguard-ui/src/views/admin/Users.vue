<template>
  <div>
    <el-card>
      <template #header>
        <div style="display: flex; align-items: center; gap: 12px">
          <span>用户管理</span>
          <el-button size="small" type="primary" data-testid="create-btn" @click="openCreate">新建用户</el-button>
          <el-button size="small" @click="loadData">刷新</el-button>
        </div>
      </template>

      <el-table :data="users" v-loading="loading" border stripe>
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column prop="username" label="用户名" width="160" />
        <el-table-column prop="displayName" label="显示名" width="140" />
        <el-table-column label="角色">
          <template #default="{ row }">
            <el-tag v-for="r in row.roles" :key="r" size="small" style="margin-right: 4px">{{ r }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.enabled ? 'success' : 'info'">{{ row.enabled ? '启用' : '禁用' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="220">
          <template #default="{ row }">
            <el-button size="small" @click="openEdit(row)">编辑</el-button>
            <el-button size="small" type="warning" @click="openReset(row)">重置密码</el-button>
            <el-button size="small" type="danger" :disabled="row.username === auth.user?.username" @click="onDelete(row)">
              删除
            </el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-alert v-if="error" :title="error" type="error" :closable="false" style="margin-top: 12px" />
    </el-card>

    <!-- 新建/编辑用户 -->
    <el-dialog v-model="dialogVisible" :title="editing ? '编辑用户' : '新建用户'" width="460px">
      <el-form label-width="90px">
        <el-form-item label="用户名" v-if="!editing">
          <el-input v-model="form.username" data-testid="username" />
        </el-form-item>
        <el-form-item label="初始密码" v-if="!editing">
          <el-input v-model="form.password" type="password" show-password placeholder="至少 8 位" data-testid="password" />
        </el-form-item>
        <el-form-item label="显示名">
          <el-input v-model="form.displayName" />
        </el-form-item>
        <el-form-item label="角色">
          <el-select v-model="form.roleIds" multiple style="width: 100%" placeholder="选择角色">
            <el-option v-for="r in roles" :key="r.id" :label="`${r.name} (${r.code})`" :value="r.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="form.enabled" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" data-testid="save-btn" @click="onSave">保存</el-button>
      </template>
    </el-dialog>

    <!-- 重置密码 -->
    <el-dialog v-model="resetVisible" title="重置密码" width="380px">
      <el-form label-width="90px">
        <el-form-item label="新密码">
          <el-input v-model="resetPwd" type="password" show-password placeholder="至少 8 位" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="resetVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="onReset">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { AuthApi, type UserInfo, type RoleItem } from '@/api/auth'
import { auth } from '@/stores/auth'

const users = ref<UserInfo[]>([])
const roles = ref<RoleItem[]>([])
const loading = ref(false)
const saving = ref(false)
const error = ref('')

const dialogVisible = ref(false)
const editing = ref<UserInfo | null>(null)
const form = ref<{ username: string; password: string; displayName: string; roleIds: number[]; enabled: boolean }>({
  username: '',
  password: '',
  displayName: '',
  roleIds: [],
  enabled: true,
})

const resetVisible = ref(false)
const resetPwd = ref('')
const resetTarget = ref<UserInfo | null>(null)

async function loadData() {
  loading.value = true
  error.value = ''
  try {
    const [u, r] = await Promise.all([AuthApi.listUsers(), AuthApi.listRoles()])
    users.value = u
    roles.value = r
  } catch (e: any) {
    error.value = e.response?.data?.message || '加载失败'
  } finally {
    loading.value = false
  }
}

function openCreate() {
  editing.value = null
  form.value = { username: '', password: '', displayName: '', roleIds: [], enabled: true }
  dialogVisible.value = true
}

function openEdit(row: UserInfo) {
  editing.value = row
  form.value = {
    username: row.username,
    password: '',
    displayName: row.displayName,
    roleIds: roles.value.filter((r) => row.roles.includes(r.code)).map((r) => r.id),
    enabled: row.enabled,
  }
  dialogVisible.value = true
}

async function onSave() {
  saving.value = true
  try {
    if (editing.value) {
      await AuthApi.updateUser(editing.value.id, {
        displayName: form.value.displayName,
        enabled: form.value.enabled,
        roleIds: form.value.roleIds,
      })
    } else {
      if (!form.value.username || form.value.password.length < 8) {
        throw new Error('用户名必填，密码至少 8 位')
      }
      await AuthApi.createUser({
        username: form.value.username,
        password: form.value.password,
        displayName: form.value.displayName,
        enabled: form.value.enabled,
        roleIds: form.value.roleIds,
      })
    }
    ElMessage.success('保存成功')
    dialogVisible.value = false
    await loadData()
  } catch (e: any) {
    ElMessage.error(e.response?.data?.message || e.message || '保存失败')
  } finally {
    saving.value = false
  }
}

function openReset(row: UserInfo) {
  resetTarget.value = row
  resetPwd.value = ''
  resetVisible.value = true
}

async function onReset() {
  if (!resetTarget.value) return
  if (resetPwd.value.length < 8) {
    ElMessage.warning('密码至少 8 位')
    return
  }
  saving.value = true
  try {
    await AuthApi.resetPassword(resetTarget.value.id, resetPwd.value)
    ElMessage.success('已重置，该用户下次登录需改密')
    resetVisible.value = false
  } catch (e: any) {
    ElMessage.error(e.response?.data?.message || '重置失败')
  } finally {
    saving.value = false
  }
}

async function onDelete(row: UserInfo) {
  await ElMessageBox.confirm(`确定删除用户「${row.username}」？`, '提示', { type: 'warning' })
  await AuthApi.deleteUser(row.id)
  ElMessage.success('已删除')
  await loadData()
}

onMounted(loadData)
</script>
