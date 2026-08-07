import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    host: true,
    port: 3000,
    proxy: {
      '/orders': 'http://localhost:8080',
      '/anomalies': 'http://localhost:8000',
      '/ai': 'http://localhost:8000',
      '/compensations': 'http://localhost:8080',
      '/auth': 'http://localhost:8080',
      '/users': 'http://localhost:8080',
      '/roles': 'http://localhost:8080',
      '/audit-logs': 'http://localhost:8080',
      '/gateway': 'http://localhost:8080',
      '/health': 'http://localhost:8080',
      '/ws': {
        target: 'ws://localhost:8080',
        ws: true,
      },
    },
  },
  build: {
    // 分包后单块尺寸下降，调高告警阈值避免 element-plus 块误报
    chunkSizeWarningLimit: 1000,
    rollupOptions: {
      output: {
        // vendor 分包：vue/element-plus/echarts/axios 拆成独立块，
        // 文件名带内容哈希、nginx 已配 immutable，依赖版本不变就长期命中浏览器/边缘缓存，
        // 每次发版只有业务代码（index/Landing/...）重新下载
        manualChunks(id: string) {
          if (!id.includes('node_modules')) return
          if (id.includes('element-plus') || id.includes('@element-plus')) return 'element-plus'
          if (id.includes('echarts') || id.includes('vue-echarts') || id.includes('zrender')) return 'echarts'
          if (id.includes('vue-router') || id.includes('/vue/') || id.includes('@vue/')) return 'vue-vendor'
          if (id.includes('/axios/')) return 'axios'
          return 'vendor'
        },
      },
    },
  },
})
