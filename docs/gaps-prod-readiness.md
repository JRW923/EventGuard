# EventGuard 生产就绪缺口清单（与业务无关的基建项）

> 面向「真正投入使用」：以下均为**业务无关**的工程化/运维/体验缺口。
> 优先级：**P0** 上线必须（缺失会造成事故或直接无法对外）；**P1** 上线后很快需要；**P2** 增强项。
> 状态随实施推进在「实施记录」更新。

## P0 · 上线必须

| # | 缺口 | 现状 | 后果 | 建议方案 |
|---|------|------|------|----------|
| 1 | **数据备份 / 恢复** | 无任何 pg_dump / 卷快照；`domain_events` 是事件溯源事实源 | 数据丢失 = 全部订单历史不可重建（事件溯源唯一优势也是最大风险） | `scripts/backup-db.sh`（pg_dump 全库 + 保留 N 天）+ crontab；文档写清恢复步骤 |
| 2 | **统一监控告警** | actuator 只暴露 `health/info/metrics`；异常检测只推 WebSocket | 服务挂了没人知道；告警只在有人盯着看板时可见 | 接入 Prometheus + Grafana（actuator `prometheus` 端点已具备基础），加关键告警规则 |
| 3 | **错误追踪** | 无 Sentry / 集中日志 | 生产报错靠 `docker logs` 翻，无法回溯请求链路 | 集中日志（Loki/ELK）或 Sentry；至少结构化日志 + 请求 ID |
| 4 | **HTTPS + 证书** | nginx 无 `ssl_certificate`；仅靠 Cloudflare 隧道兜底 | 直连 8080/80 为明文 | 隧道已有；如走直连需 certbot 或反向代理统一终止 TLS |
| 5 | **前端 404 页** | 路由无 catch-all，访问未知地址白屏；有 403 无 404 | 用户访问失效链接体验差、无法排障 | 加 catch-all 路由 + 404 视图 |
| 6 | **密码找回** | 仅「改自己密码」，无「忘记密码」；管理员可重置他人密码 | 用户忘记密码只能找管理员 | 登录页加「忘记密码」引导 → 联系管理员重置（`POST /users/{id}/reset-password` 已有） |
| 7 | **通用请求限流** | 仅登录防爆破（`LoginAttemptGuard`），其余接口无速率限制 | 接口可被脚本刷爆（查询/补偿/创建） | 新增 per-IP 简单限流 Filter（非登录接口，可配置阈值） |
| 8 | **审计日志可视化** | 有 `auth_audit_log` 表与写入，但无读取页面 | 管理员看不到谁做了什么 | 后端 `GET /audit-logs`（admin 权限）+ 前端审计日志页 |

## P1 · 上线后很快需要

| # | 缺口 | 现状 | 后果 | 建议方案 |
|---|------|------|------|----------|
| 9 | **安全响应头 + gzip** | nginx 无 `X-Frame-Options`/`CSP`/`HSTS`，无 gzip | 点击劫持/XSS 面大；前端 bundle 未压缩 | nginx `add_header` 三件套 + `gzip on` |
| 10 | **DB 连接池/超时参数** | 依赖 Spring 默认值 | 高并发连接耗尽风险无兜底 | `application.yml` 显式 HikariCP `maximum-pool-size` / `connection-timeout` |
| 11 | **数据保留策略** | `domain_events` / `order_view` / 日志无限增长 | 长期运行磁盘大头是事件表 | 归档/TTL 设计文档 + 可选清理脚本（保留期可配） |
| 12 | **优雅停机/滚动发布** | 无 preStop 优雅下线 | 重启打断在途命令/连接 | compose `stop_grace_period` + Spring `server.shutdown=graceful` |
| 13 | **错误追踪接入** | 无 | 见 P0-3 | 接入 Sentry（server 端 `sentry-spring-boot` / AI 端 `sentry-sdk`）或 Loki 日志聚合 |
| 14 | **国际化 i18n** | 全中文硬编码 | 无法面向多语言 | 前端 `vue-i18n`，文案抽离（工作量大，列为增强） |

## P2 · 增强项

| # | 缺口 | 说明 |
|---|------|------|
| 15 | PWA / 移动端适配 | 当前桌面布局，无响应式断点；可加 PWA manifest + 基本断点 |
| 16 | 令牌管理 | JWT 12h 固定，无「记住我 / 登出所有设备」 |
| 17 | CORS 策略 | 当前靠同源 + nginx 反代规避；未显式配置（开放 API 给第三方会踩坑） |
| 18 | 版本/健康页 | 前端无「当前版本 + 后端连通性」指示 |
| 19 | 配置中心/密钥轮换 | `.env` 手动管理，无密钥轮换流程 |

## 实施记录

- [x] P0-1 备份脚本（`scripts/backup-db.sh`：docker exec pg_dump custom 格式，保留 14 天，已验证 pg_restore --list 可读）
- [ ] P0-2 Prometheus + Grafana
- [ ] P0-3 错误追踪
- [ ] P0-4 HTTPS/证书
- [ ] P0-5 前端 404 页
- [ ] P0-6 密码找回引导
- [ ] P0-7 通用限流
- [ ] P0-8 审计日志页
- [ ] P1-9 安全头 + gzip
- [ ] P1-10 连接池参数
- [ ] P1-11 数据保留策略
- [ ] P1-12 优雅停机
- [ ] P1-13 错误追踪接入
- [ ] P1-14 i18n
