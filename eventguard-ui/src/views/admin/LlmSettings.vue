<template>
  <div class="llm-page">
    <div class="llm-heading">
      <div>
        <div class="llm-eyebrow">MY AI PLATFORM</div>
        <h1>我的 LLM 配置</h1>
        <p>配置你自己的模型接入。所有 AI 功能（NL 查询、根因分析、周报等）将使用你在此填写的 API。</p>
      </div>
      <el-tag v-if="settings?.has_api_key" type="success" effect="light">已配置 API key</el-tag>
      <el-tag v-else type="warning" effect="light">尚未配置 API key</el-tag>
    </div>

    <el-alert
      title="你的 API key 是私有的"
      description="API key 仅加密存储在你本人账号下，任何其他用户（包括管理员）都无法查看或使用；留空表示保留现有 key。"
      type="info"
      :closable="false"
      show-icon
      class="llm-alert"
    />

    <el-card class="llm-card" v-loading="loading">
      <template #header>
        <div class="llm-card-header"><span>连接参数</span><span class="llm-card-caption">OpenAI-compatible / Anthropic-compatible</span></div>
      </template>
      <el-form :model="form" label-position="top" class="llm-form">
        <div class="llm-grid llm-grid--two">
          <el-form-item label="Provider">
            <el-select v-model="form.provider" style="width: 100%" data-testid="llm-provider">
              <el-option label="自动探测" value="" />
              <el-option label="OpenAI Compatible" value="openai" />
              <el-option label="Anthropic Compatible" value="anthropic" />
            </el-select>
          </el-form-item>
          <el-form-item label="Model" required>
            <el-input v-model="form.model" placeholder="例如 qwen2.5:7b" data-testid="llm-model" />
          </el-form-item>
        </div>
        <el-form-item label="Base URL" required>
          <el-input v-model="form.base_url" placeholder="https://api.example.com/v1" data-testid="llm-base-url">
            <template #prepend>URL</template>
          </el-input>
          <div class="llm-help">支持 OpenAI-compatible 的 /v1/chat/completions，以及 Anthropic 的 /v1/messages。</div>
        </el-form-item>
        <el-form-item label="API key">
          <el-input v-model="form.api_key" type="password" show-password autocomplete="new-password" :placeholder="settings?.has_api_key ? `当前已设置：${settings.api_key_masked}，留空保持不变` : '请输入 API key'" data-testid="llm-api-key" />
        </el-form-item>
        <div class="llm-grid llm-grid--two">
          <el-form-item label="Max tokens">
            <el-input-number v-model="form.max_tokens" :min="128" :max="32768" :step="128" controls-position="right" style="width: 100%" />
          </el-form-item>
          <el-form-item label="Temperature">
            <el-input-number v-model="form.temperature" :min="0" :max="2" :step="0.1" :precision="1" controls-position="right" style="width: 100%" />
          </el-form-item>
        </div>
      </el-form>

      <el-alert v-if="error" :title="error" type="error" :closable="false" class="llm-error" />
      <div class="llm-actions">
        <el-button type="primary" :loading="saving" data-testid="llm-save" @click="save">保存</el-button>
      </div>
    </el-card>

    <div class="llm-footnote">
      <span class="llm-footnote-dot" /> 未配置时，AI 功能将提示你「先在个人中心配置你的 LLM API」。
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus/es/components/message/index.mjs'
import { LlmApi, type LlmConfig, type LlmConfigPayload } from '@/api/llm'

const loading = ref(false)
const saving = ref(false)
const error = ref('')
const settings = ref<LlmConfig | null>(null)
const form = reactive<LlmConfigPayload>({
  provider: '',
  base_url: '',
  api_key: '',
  model: '',
  max_tokens: 2048,
  temperature: 0.3,
})

async function load() {
  loading.value = true
  error.value = ''
  try {
    const data = await LlmApi.get()
    settings.value = data
    form.provider = data.provider
    form.base_url = data.base_url
    form.model = data.model
    form.max_tokens = data.max_tokens
    form.temperature = data.temperature
    form.api_key = ''
  } catch (e: any) {
    error.value = e.response?.data?.detail || e.response?.data?.message || '无法读取 LLM 配置。'
  } finally {
    loading.value = false
  }
}

async function save() {
  if (!form.base_url || !/^https?:\/\//i.test(form.base_url)) {
    ElMessage.warning('Base URL 必须以 http:// 或 https:// 开头')
    return
  }
  if (!form.model.trim()) {
    ElMessage.warning('请填写 Model')
    return
  }
  saving.value = true
  error.value = ''
  try {
    settings.value = await LlmApi.update({ ...form, api_key: form.api_key?.trim() || undefined })
    form.api_key = ''
    ElMessage.success('配置已保存，后续 AI 请求将使用你的新设置')
  } catch (e: any) {
    error.value = e.response?.data?.detail || e.response?.data?.message || e.message || '保存失败'
  } finally {
    saving.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.llm-page { max-width: 980px; margin: 0 auto; }
.llm-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 20px; margin-bottom: 22px; }
.llm-eyebrow { color: #4963e5; font-size: 11px; font-weight: 800; letter-spacing: 1.5px; }
.llm-heading h1 { margin: 6px 0 8px; color: #172033; font-size: 28px; line-height: 1.2; }
.llm-heading p { margin: 0; color: #77849a; font-size: 14px; }
.llm-alert { margin-bottom: 16px; border: 0; border-radius: 10px; }
.llm-card { border-radius: 14px; }
.llm-card-header { display: flex; align-items: center; justify-content: space-between; gap: 16px; }
.llm-card-caption { color: #9aa5b8; font-size: 12px; font-weight: 400; }
.llm-form { max-width: 760px; }
.llm-grid { display: grid; gap: 18px; }
.llm-grid--two { grid-template-columns: repeat(2, minmax(0, 1fr)); }
.llm-help { margin-top: 7px; color: #8b97aa; font-size: 12px; line-height: 1.5; }
.llm-error { margin-top: 8px; }
.llm-actions { display: flex; gap: 10px; margin-top: 18px; padding-top: 18px; border-top: 1px solid #edf0f5; }
.llm-footnote { display: flex; align-items: center; gap: 8px; margin-top: 16px; color: #8b97aa; font-size: 12px; }
.llm-footnote-dot { width: 7px; height: 7px; border-radius: 50%; background: #26b795; box-shadow: 0 0 0 4px rgba(38,183,149,.12); }
@media (max-width: 640px) {
  .llm-heading { display: block; }
  .llm-heading .el-tag { margin-top: 14px; }
  .llm-grid--two { grid-template-columns: 1fr; gap: 0; }
  .llm-card-caption { display: none; }
}
</style>
