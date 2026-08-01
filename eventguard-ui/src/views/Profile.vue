<template>
  <div>
    <el-card style="max-width: 480px">
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
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { AuthApi } from '@/api/auth'
import { auth } from '@/stores/auth'

const router = useRouter()
const oldPwd = ref('')
const newPwd = ref('')
const confirmPwd = ref('')
const loading = ref(false)
const error = ref('')

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
    error.value = e.response?.data?.message || '修改失败'
  } finally {
    loading.value = false
  }
}
</script>
