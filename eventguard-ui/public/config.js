// 本地开发兜底（vite dev 无 nginx 注入）。生产由容器启动时 envsubst 用 EG_API_KEY 覆盖本文件。
// 此处填入与后端一致的开发密钥，否则 dev server 调用接口会因 401 失败。
window.__EG_API_KEY__ = "root";
