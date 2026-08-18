<template>
  <div>
    <el-card>
      <template #header>
        <div style="display: flex; align-items: center; gap: 12px">
          <span>角色管理</span>
          <el-button size="small" type="primary" data-testid="create-btn" @click="openCreate">新建角色</el-button>
          <el-button size="small" @click="loadData">刷新</el-button>
        </div>
      </template>

      <el-table :data="roles" v-loading="loading" border stripe>
        <el-table-column :resizable="false" prop="id" label="ID" width="70" />
        <el-table-column :resizable="false" prop="code" label="编码" width="140" />
        <el-table-column :resizable="false" prop="name" label="名称" width="120" />
        <el-table-column :resizable="false" prop="description" label="描述" min-width="180" />
        <el-table-column :resizable="false" label="权限" width="260">
          <template #default="{ row }">
            <el-tag v-for="p in row.permissions" :key="p" size="small" style="margin-right: 4px" effect="plain">
              {{ p }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column :resizable="false" label="操作" width="150">
          <template #default="{ row }">
            <el-button size="small" @click="openEdit(row as RoleItem)">编辑</el-button>
            <el-button size="small" type="danger" :disabled="row.code === 'ADMIN'" @click="onDelete(row as RoleItem)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-alert v-if="error" :title="error" type="error" :closable="false" style="margin-top: 12px" />
    </el-card>

    <!-- 新建/编辑角色 -->
    <el-dialog v-model="dialogVisible" :title="editing ? '编辑角色' : '新建角色'" width="520px">
      <el-form label-width="80px">
        <el-form-item label="编码" v-if="!editing">
          <el-input v-model="form.code" placeholder="如 AUDITOR（大写）" data-testid="code" />
        </el-form-item>
        <el-form-item label="名称">
          <el-input v-model="form.name" data-testid="name" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="form.description" />
        </el-form-item>
        <el-form-item label="权限">
          <el-checkbox-group v-model="form.permissions">
            <el-checkbox v-for="p in permissions" :key="p.code" :value="p.code" style="display: block">
              {{ p.description }}（{{ p.code }}）
            </el-checkbox>
          </el-checkbox-group>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" data-testid="save-btn" @click="onSave">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus/es/components/message/index.mjs'
import { ElMessageBox } from 'element-plus/es/components/message-box/index.mjs'
import { AuthApi, type RoleItem, type PermissionItem } from '@/api/auth'

const roles = ref<RoleItem[]>([])
const permissions = ref<PermissionItem[]>([])
const loading = ref(false)
const saving = ref(false)
const error = ref('')

const dialogVisible = ref(false)
const editing = ref<RoleItem | null>(null)
const form = ref<{ code: string; name: string; description: string; permissions: string[] }>({
  code: '',
  name: '',
  description: '',
  permissions: [],
})

async function loadData() {
  loading.value = true
  error.value = ''
  try {
    const [r, p] = await Promise.all([AuthApi.listRoles(), AuthApi.listPermissions()])
    roles.value = r
    permissions.value = p
  } catch (e: any) {
    error.value = e.response?.data?.message || '加载失败'
  } finally {
    loading.value = false
  }
}

function openCreate() {
  editing.value = null
  form.value = { code: '', name: '', description: '', permissions: [] }
  dialogVisible.value = true
}

function openEdit(row: RoleItem) {
  editing.value = row
  form.value = { code: row.code, name: row.name, description: row.description, permissions: [...row.permissions] }
  dialogVisible.value = true
}

async function onSave() {
  saving.value = true
  try {
    if (editing.value) {
      await AuthApi.updateRole(editing.value.id, {
        name: form.value.name,
        description: form.value.description,
        permissions: form.value.permissions,
      })
    } else {
      if (!form.value.code || !form.value.name) {
        throw new Error('编码与名称必填')
      }
      await AuthApi.createRole({
        code: form.value.code,
        name: form.value.name,
        description: form.value.description,
        permissions: form.value.permissions,
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

async function onDelete(row: RoleItem) {
  await ElMessageBox.confirm(`确定删除角色「${row.name}」？删除后该角色下用户将失去对应权限。`, '提示', {
    type: 'warning',
    confirmButtonText: '确定',
    cancelButtonText: '取消',
  })
  await AuthApi.deleteRole(row.id)
  ElMessage.success('已删除')
  await loadData()
}

onMounted(loadData)
</script>
