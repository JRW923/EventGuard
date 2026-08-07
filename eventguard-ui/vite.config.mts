import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import Components from 'unplugin-vue-components/vite'
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers'

// element-plus 2.14 里部分子组件没有独立入口（<el-option>/<el-table-column> 等被父组件重导出），
// 这里映射到实际导出它们的父组件深路径，保证按需引用时只拉对应模块、不整包拖入 barrel。
const SUB_COMPONENT_PARENT: Record<string, string> = {
  checkboxgroup: 'checkbox',
  descriptionsitem: 'descriptions',
  dropdownitem: 'dropdown',
  dropdownmenu: 'dropdown',
  footer: 'container',
  formitem: 'form',
  header: 'container',
  main: 'container',
  menuitem: 'menu',
  option: 'select',
  submenu: 'menu',
  tablecolumn: 'table',
  loadingdirective: 'loading',
  popoverdirective: 'popover',
  infinitescroll: 'infinite-scroll',
}

// 包一层 ElementPlusResolver：默认它从 element-plus/es（barrel 根）具名导入，
// 而 2.14 的 barrel 顶层 import 了全部组件、Rollup 摇不掉，导致全量打包。
// 这里把 from 改成对应组件的深路径（element-plus/es/components/<name>/index.mjs），
// 样式（sideEffects）保持原样，仍由 resolver 按组件注入。
function deepElementPlusResolver(options: Record<string, unknown> = {}) {
  const [comp, dir] = ElementPlusResolver(options) as [
    { resolve: (name: string) => Promise<{ name: string; from: string } | undefined> },
    { resolve: (name: string) => Promise<{ name: string; from: string } | undefined> },
  ]
  const kebab = (s: string) => s.replace(/([a-z0-9])([A-Z])/g, '$1-$2').toLowerCase()
  const deepFrom = (name: string) => {
    const k = kebab(name.replace(/^El/, ''))
    const parent = SUB_COMPONENT_PARENT[k.replace(/-/g, '')]
    return `element-plus/es/components/${parent ?? k}/index.mjs`
  }
  const wrap = (resolver: typeof comp, type: 'component' | 'directive') => ({
    type,
    resolve: async (name: string) => {
      const r = await resolver.resolve(name)
      if (!r) return
      return { ...r, from: deepFrom(r.name ?? name) }
    },
  })
  return [wrap(comp, 'component'), wrap(dir, 'directive')]
}

export default defineConfig({
  plugins: [
    vue(),
    // Element Plus 按需引入：模板里的 <el-*> 组件和 v-loading 指令按需打包，
    // 各自样式随组件自动注入，首屏不再整包下载 element-plus 全量 JS/CSS。
    // dts 生成到 src/ 下，纳入 tsconfig 使 vue-tsc 能解析模板组件类型。
    Components({
      dts: 'src/components.d.ts',
      resolvers: [deepElementPlusResolver({ directives: true })],
    }),
  ],
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
