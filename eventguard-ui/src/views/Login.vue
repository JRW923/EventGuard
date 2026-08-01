<template>
  <div class="login-page">
    <el-card class="login-card">
      <div class="login-brand">
        <img src="/brand/logo-2.png" alt="EventGuard" width="44" height="44" />
        <h2>EventGuard 控制台</h2>
        <p class="login-sub">事件溯源 · 智能异常检测 · 自然语言查询</p>
      </div>

      <el-form label-position="top" @keyup.enter="onLogin">
        <el-form-item>
          <el-input v-model="username" placeholder="用户名" data-testid="username" size="large" />
        </el-form-item>
        <el-form-item>
          <el-input
            v-model="password"
            type="password"
            placeholder="密码"
            show-password
            data-testid="password"
            size="large"
          />
        </el-form-item>
        <el-button
          type="primary"
          size="large"
          style="width: 100%"
          :loading="loading"
          data-testid="login-btn"
          @click="onLogin"
        >
          登 录
        </el-button>
      </el-form>

      <el-alert v-if="error" :title="error" type="error" :closable="false" style="margin-top: 16px" />
    </el-card>

    <!-- 首次登录强制改密 -->
    <el-dialog
      v-model="mustChange"
      title="首次登录，请修改密码"
      :close-on-click-modal="false"
      :close-on-press-escape="false"
      :show-close="false"
      width="420px"
    >
      <el-form label-width="80px">
        <el-form-item label="新密码">
          <el-input v-model="newPwd" type="password" show-password placeholder="至少 8 位" data-testid="new-pwd" />
        </el-form-item>
        <el-form-item label="确认密码">
          <el-input v-model="confirmPwd" type="password" show-password placeholder="再次输入新密码" data-testid="confirm-pwd" />
        </el-form-item>
      </el-form>
      <p v-if="changeError" style="color: #f56c6c; font-size: 13px">{{ changeError }}</p>
      <template #footer>
        <el-button type="primary" :loading="changing" data-testid="change-btn" @click="onChangePwd">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { AuthApi } from '@/api/auth'
import { auth } from '@/stores/auth'

const route = useRoute()
const router = useRouter()

const username = ref('')
const password = ref('')
const loading = ref(false)
const error = ref('')

const mustChange = ref(false)
const newPwd = ref('')
const confirmPwd = ref('')
const changing = ref(false)
const changeError = ref('')

async function onLogin() {
  if (!username.value || !password.value) {
    error.value = '请输入用户名和密码'
    return
  }
  loading.value = true
  error.value = ''
  try {
    const user = await auth.login(username.value.trim(), password.value)
    if (user.mustChangePassword) {
      mustChange.value = true
    } else {
      finishLogin()
    }
  } catch (e: any) {
    error.value = e.response?.data?.message || e.response?.statusText || '登录失败'
  } finally {
    loading.value = false
  }
}

async function onChangePwd() {
  if (newPwd.value.length < 8) {
    changeError.value = '新密码至少 8 位'
    return
  }
  if (newPwd.value !== confirmPwd.value) {
    changeError.value = '两次输入的密码不一致'
    return
  }
  changing.value = true
  changeError.value = ''
  try {
    await AuthApi.changePassword(password.value, newPwd.value)
    await auth.fetchMe() // 刷新 mustChangePassword=false
    ElMessage.success('密码已修改，请重新登录')
    auth.logout()
    mustChange.value = false
    password.value = ''
    newPwd.value = ''
    confirmPwd.value = ''
  } catch (e: any) {
    changeError.value = e.response?.data?.message || '修改失败'
  } finally {
    changing.value = false
  }
}

function finishLogin() {
  const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/'
  router.push(redirect)
}
</script>

<style scoped>
.login-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #409eff 0%, #1c6fc4 100%);
}
.login-card {
  width: 380px;
  padding: 8px 12px;
}
.login-brand {
  text-align: center;
  margin-bottom: 20px;
}
.login-brand h2 {
  margin: 10px 0 4px;
  color: #303133;
}
.login-sub {
  color: #909399;
  font-size: 13px;
  margin: 0;
}
</style>
