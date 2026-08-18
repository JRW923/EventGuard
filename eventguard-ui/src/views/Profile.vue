<template>
  <div class="profile-page">
    <LlmSettings />
    <el-card class="profile-card">
      <template #header>
        <span>修改密码</span>
      </template>
      <el-form label-width="90px" @keyup.enter="onSubmit">
        <el-form-item label="原密码">
          <el-input v-model="oldPwd" type="password" show-password data-testid="old-pwd" />
        </el-form-item>
        <el-form-item label="新密码">
          <el-input v-model="newPwd" type="password" show-password placeholder="至少 8 位" data-testid="new-pwd" />
        </el-form-item>
        <el-form-item label="确认密码">
          <el-input v-model="confirmPwd" type="password" show-password data-testid="confirm-pwd" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="loading" data-testid="submit-btn" @click="onSubmit">确认修改</el-button>
        </el-form-item>
      </el-form>
      <el-alert v-if="error" :title="error" type="error" :closable="false" />
    </el-card>

    <el-card class="profile-card">
      <template #header>
        <span>安全</span>
      </template>
      <div style="display: flex; align-items: center; justify-content: space-between">
        <div>
          <div style="font-weight: 500">退出所有设备</div>
          <div style="color: #909399; font-size: 13px; margin-top: 4px">使本账号在其他设备登录的会话全部失效，需重新登录</div>
        </div>
        <el-button type="danger" plain :loading="logoutAllLoading" data-testid="logout-all-btn" @click="onLogoutAll">
          退出所有设备
        </el-button>
      </div>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus/es/components/message/index.mjs'
import { ElMessageBox } from 'element-plus/es/components/message-box/index.mjs'
import { AuthApi } from '@/api/auth'
import { auth } from '@/stores/auth'
import { friendlyError } from '@/api/http'
import LlmSettings from '@/views/admin/LlmSettings.vue'

const router = useRouter()
const oldPwd = ref('')
const newPwd = ref('')
const confirmPwd = ref('')
const loading = ref(false)
const error = ref('')
const logoutAllLoading = ref(false)

async function onSubmit() {
  if (newPwd.value.length < 8) {
    error.value = '新密码至少 8 位'
    return
  }
  if (newPwd.value !== confirmPwd.value) {
    error.value = '两次输入的密码不一致'
    return
  }
  loading.value = true
  error.value = ''
  try {
    await AuthApi.changePassword(oldPwd.value, newPwd.value)
    ElMessage.success('密码已修改，请重新登录')
    auth.logout()
    router.push('/login')
  } catch (e: any) {
    error.value = friendlyError(e, '修改失败')
  } finally {
    loading.value = false
  }
}

async function onLogoutAll() {
  try {
    await ElMessageBox.confirm('将注销本账号在所有设备的登录状态，确定继续？', '退出所有设备', {
      type: 'warning', confirmButtonText: '确定', cancelButtonText: '取消',
    })
  } catch {
    return
  }
  logoutAllLoading.value = true
  try {
    await AuthApi.logoutAll()
    ElMessage.success('已退出所有设备，请重新登录')
    auth.logout()
    router.push('/login')
  } catch (e: any) {
    ElMessage.error(friendlyError(e, '操作失败'))
  } finally {
    logoutAllLoading.value = false
  }
}
</script>

<style scoped>
/* 与 LlmSettings 的 .llm-page 同宽居中，三个模块左缘对齐 */
.profile-page {
  max-width: 980px;
  margin: 0 auto;
}
.profile-card {
  margin-top: 16px;
}
</style>
