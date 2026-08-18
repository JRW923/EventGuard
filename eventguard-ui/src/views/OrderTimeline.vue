<template>
  <div>
    <el-card>
      <template #header>
        <div style="display: flex; align-items: center; gap: 16px">
          <span>订单事件时间线</span>
          <el-tag>{{ orderId }}</el-tag>
          <el-button size="small" @click="loadEvents">刷新</el-button>
          <el-button size="small" @click="$router.back()">返回</el-button>
        </div>
      </template>

      <div style="display: flex; align-items: center; gap: 12px; margin-bottom: 12px">
        <span>按版本回放：</span>
        <el-input-number
          v-model="upToVersion"
          :min="1"
          :max="fullMaxVersion || 100000"
          size="small"
          controls-position="right"
          placeholder="全部版本"
        />
        <el-button size="small" type="primary" @click="loadEvents">回放</el-button>
        <el-button size="small" @click="resetReplay">重置</el-button>
      </div>

      <EventTimeline :events="events" v-loading="loading" />

      <el-alert v-if="error" type="error" :title="error" :closable="false" style="margin-top: 16px" />
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import EventTimeline from '../components/EventTimeline.vue'
import { OrderApi } from '../api/order'
import { friendlyError } from '../api/http'
import { EventItem } from '@/types/event'

const route = useRoute()
const orderId = route.params.id as string

// 页面标签带订单号（简短展示前 8 位），便于多标签页区分
document.title = `订单时间线 · ${orderId.slice(0, 8)} · EventGuard`

const events = ref<EventItem[]>([])
const loading = ref(false)
const error = ref('')
// ponytail: 时间线"编辑器"最小可用形态=按版本回放（时间旅行）；完整"状态在版本N"重建为升级路径
const upToVersion = ref<number | undefined>(undefined)
const fullMaxVersion = ref<number>(0)

async function loadEvents() {
  loading.value = true
  error.value = ''
  try {
    events.value = await OrderApi.getEvents(orderId, upToVersion.value)
    if (upToVersion.value == null && events.value.length) {
      fullMaxVersion.value = Math.max(...events.value.map((e) => e.version))
    }
  } catch (e: any) {
    error.value = friendlyError(e, '加载失败')
    events.value = []
  } finally {
    loading.value = false
  }
}

function resetReplay() {
  upToVersion.value = undefined
  loadEvents()
}

onMounted(loadEvents)
</script>
