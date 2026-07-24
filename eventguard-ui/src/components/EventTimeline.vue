<template>
  <div>
    <div v-if="events.length === 0" data-testid="timeline-empty">
      <el-empty description="暂无事件" />
    </div>
    <v-chart
      v-else
      class="chart"
      :option="chartOption"
      autoresize
      style="height: 400px"
    />

    <el-table :data="sortedEvents" border size="small" style="margin-top: 16px">
      <el-table-column prop="version" label="版本" width="80" />
      <el-table-column prop="eventType" label="事件类型" width="220" />
      <el-table-column prop="createdAt" label="发生时间" width="220" />
      <el-table-column label="Payload">
        <template #default="scope">
          <pre v-if="scope && scope.row" style="margin: 0; font-size: 12px">{{ JSON.stringify(scope.row.payload, null, 2) }}</pre>
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import VChart from 'vue-echarts'
import { use } from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import { LineChart } from 'echarts/charts'
import { GridComponent, TooltipComponent, LegendComponent } from 'echarts/components'

use([CanvasRenderer, LineChart, GridComponent, TooltipComponent, LegendComponent])

interface EventItem {
  eventId: string
  aggregateId: string
  eventType: string
  version: number
  createdAt: string
  payload: Record<string, any>
}

const props = defineProps<{ events: EventItem[] }>()

// ponytail: 排序按 version 升序，假设 version 即事件回放顺序；若后端乱序返回需强一致可改为 createdAt 兜底
const sortedEvents = computed(() =>
  [...props.events].sort((a, b) => a.version - b.version)
)

const chartOption = computed(() => {
  const sorted = sortedEvents.value
  return {
    title: {
      text: '事件时间线',
      left: 'center',
    },
    tooltip: {
      trigger: 'axis',
      formatter: (params: any) => {
        const idx = params[0].dataIndex
        const ev = sorted[idx]
        return `${ev.eventType}<br/>版本：${ev.version}<br/>时间：${ev.createdAt}<br/>payload：${JSON.stringify(ev.payload)}`
      },
    },
    xAxis: {
      type: 'category',
      data: sorted.map((e) => e.eventType),
      axisLabel: { rotate: 30 },
    },
    yAxis: {
      type: 'value',
      name: '版本号',
    },
    series: [
      {
        name: '事件版本',
        type: 'line',
        data: sorted.map((e) => e.version),
        symbolSize: 12,
        lineStyle: { width: 3 },
      },
    ],
  }
})
</script>

<style scoped>
.chart {
  width: 100%;
}
</style>
