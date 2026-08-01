<template>
  <div>
    <el-card>
      <template #header>补偿执行</template>

      <el-form label-width="120px">
        <el-form-item label="动作类型">
          <select
            v-model="form.actionType"
            data-testid="action-type"
            class="eg-select"
            style="width: 320px"
          >
            <option v-for="a in actionTypes" :key="a.value" :value="a.value">
              {{ a.value }}（{{ a.risk }}）
            </option>
          </select>
        </el-form-item>

        <el-form-item label="聚合根 ID">
          <el-input
            v-model="form.aggregateId"
            data-testid="aggregate-id"
            placeholder="UUID"
            style="width: 320px"
          />
        </el-form-item>

        <el-form-item label="参数 JSON">
          <el-input
            v-model="paramsJson"
            type="textarea"
            :rows="4"
            placeholder='{"amount": 100}'
            style="width: 480px"
          />
        </el-form-item>

        <el-form-item>
          <el-button
            type="danger"
            data-testid="execute-btn"
            v-permission="'compensation:execute'"
            :loading="loading"
            @click="execute"
          >
            执行补偿
          </el-button>
        </el-form-item>
      </el-form>

      <el-alert
        v-if="result"
        :type="result.success ? 'success' : 'error'"
        :title="result.message"
        :closable="false"
        style="margin-top: 16px"
      />
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { CompensationApi, type CompensationResult } from '../api/compensation'

// 支持从异常看板跳转带入的初值（props 优先，其次 route.query）
const props = defineProps<{
  initialActionType?: string
  initialAggregateId?: string
}>()

const route = useRoute()

const actionTypes = [
  { value: 'REFUND', risk: 'MEDIUM' },
  { value: 'NOTIFY_DELAY', risk: 'LOW' },
  { value: 'MARK_OUT_OF_STOCK', risk: 'LOW' },
  { value: 'FREEZE_ORDER', risk: 'HIGH' },
  { value: 'BACKOFF_AND_STOP', risk: 'LOW' },
]

const form = ref({
  actionType: 'REFUND',
  aggregateId: '',
})
const paramsJson = ref('{}')
const loading = ref(false)
const result = ref<CompensationResult | null>(null)

onMounted(() => {
  if (props.initialActionType) form.value.actionType = props.initialActionType
  if (props.initialAggregateId) form.value.aggregateId = props.initialAggregateId
  // 路由 query 预填（异常看板跳转）；无 router 上下文（如单测）时 route 为 undefined，跳过
  if (route) {
    const q = route.query
    if (!props.initialActionType && q.actionType) form.value.actionType = q.actionType as string
    if (!props.initialAggregateId && q.aggregateId) form.value.aggregateId = (q.aggregateId as string) || ''
  }
})

async function execute() {
  loading.value = true
  result.value = null
  try {
    let params = {}
    try {
      params = JSON.parse(paramsJson.value || '{}')
    } catch {
      result.value = { success: false, message: '参数 JSON 格式错误' }
      loading.value = false
      return
    }
    result.value = await CompensationApi.execute({
      actionType: form.value.actionType,
      aggregateId: form.value.aggregateId,
      params,
    })
  } catch (e: any) {
    result.value = { success: false, message: '执行失败：' + (e.message || '未知错误') }
  } finally {
    loading.value = false
  }
}
</script>
