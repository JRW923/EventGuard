# EventGuard 文档索引

按用途分六类。找不到时先看这一页。

## [架构设计/](架构设计/)

系统怎么设计的、为什么这么设计。

| 文档 | 内容 |
|---|---|
| [eventguard-design.md](架构设计/eventguard-design.md) | 完整设计文档：事件模型、CQRS、检测分层、AI 能力 |
| [architecture.svg](架构设计/architecture.svg) | 架构拓扑图 |
| [architecture-review-2026-08.md](架构设计/architecture-review-2026-08.md) | 2026-08 架构评审记录与改进项 |
| [eventguard-plan.md](架构设计/eventguard-plan.md) | M1–M5 里程碑开发计划 |

## [部署运维/](部署运维/)

怎么部署到线上、还差什么才算生产就绪。

| 文档 | 内容 |
|---|---|
| [deploy-cloudflare-tunnel.md](部署运维/deploy-cloudflare-tunnel.md) | Cloudflare Tunnel 免备案 HTTPS（推荐方案） |
| [deploy-linux-baota.md](部署运维/deploy-linux-baota.md) | 腾讯云轻量 + 宝塔面板部署 |
| [gaps-prod-readiness.md](部署运维/gaps-prod-readiness.md) | 生产就绪缺口清单（P0/P1 已落地，P2 待排期） |
| [deployment-notes-2026-08-09.md](部署运维/deployment-notes-2026-08-09.md) | 上线部署记录：服务器端偏差、alertmanager 回退、R002/R003 修复 |

## [使用指南/](使用指南/)

怎么把它跑起来、怎么演示。

| 文档 | 内容 |
|---|---|
| [local-development.md](使用指南/local-development.md) | IDEA + FastAPI + Vite 本地开发启动顺序 |
| [demo-script.md](使用指南/demo-script.md) | 逐场景 Demo 走查脚本 |

## [面试材料/](面试材料/)

求职时怎么讲这个项目。

| 文档 | 内容 |
|---|---|
| [面试文档.md](面试材料/面试文档.md) | 简历素材、讲解话术、面试题本、差异化亮点、演练建议 |
| [量化口径.md](面试材料/量化口径.md) | 哪些数字有实测依据、哪些还没跑出来（**写简历前必读**） |

## [验证报告/](验证报告/)

跑过什么、结果如何。

| 文档 | 内容 |
|---|---|
| [verification-log.md](验证报告/verification-log.md) | 各里程碑验证记录与实测结果 |

## [版本记录/](版本记录/)

| 文档 | 内容 |
|---|---|
| [release-notes-v1.0.0.md](版本记录/release-notes-v1.0.0.md) | v1.0.0 |
| [release-notes-v1.1.0.md](版本记录/release-notes-v1.1.0.md) | v1.1.0 |

## [开发过程/](开发过程/)

历史归档，记录当时的实现路径。里面的文件路径是写作时的状态，未随目录调整更新。

| 目录 | 内容 |
|---|---|
| [迭代计划/](开发过程/迭代计划/) | M1–M5 各阶段的执行计划与已知上限记录 |
