# EventGuard 前端

Vue 3 + TypeScript 管理台，负责订单时间线、异常告警、自然语言查询和审批操作。

- `src/views`、`src/components`：页面与可复用界面。
- `src/api`、`src/stores`：接口访问和前端状态。
- `src/*/*.test.ts`：组件与交互测试。
- `public`：开发期静态资源；`dist` 为本地构建产物，不提交。

本地运行：`npm ci && npm run dev`。生产镜像由根目录 Docker Compose 构建。
