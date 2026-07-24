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

const route = useRoute()
const orderId = route.params.id as string

const events = ref<any[]>([])
const loading = ref(false)
const error = ref('')

async function loadEvents() {
  loading.value = true
  error.value = ''
  try {
    events.value = await OrderApi.getEvents(orderId)
  } catch (e: any) {
    error.value = '加载失败：' + (e.message || '未知错误')
    events.value = []
  } finally {
    loading.value = false
  }
}

onMounted(loadEvents)
</script>
