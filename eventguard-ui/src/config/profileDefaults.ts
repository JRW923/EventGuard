/**
 * 个人主页默认内容：与简历同步的结构化数据。
 * 管理员在控制台「主页内容」编辑后存入 site_profile 表，前端优先用库里的版本；
 * 库里没有（首次部署/被清空）时回落到这份默认值，保证主页永远可渲染。
 */
export interface ProfileContent {
  name: string
  title: string
  phone: string
  email: string
  site: string
  github: string
  qq: string
  photo: string
  resumeUrl: string
  education: {
    school: string
    major: string
    degree: string
    period: string
    highlight: string
  }[]
  internships: {
    company: string
    dept: string
    role: string
    period: string
    background: string
    bullets: string[]
  }[]
  skills: {
    title: string
    items: string[]
  }[]
  awards: string[]
  projects: {
    id: 'eventguard' | 'synapse'
    name: string
    tagline: string
    period: string
    desc: string
    bullets: string[]
    stack: string[]
    links: { label: string; url: string }[]
    online?: boolean
  }[]
}

export const defaultProfile: ProfileContent = {
  name: '吴佳睿',
  title: '后端开发 · AI 应用开发 · Agent 开发',
  phone: '19913823585',
  email: '19913823585@163.com',
  site: 'www.jrwdev.site',
  github: 'https://github.com/JRW923',
  qq: '471464213',
  photo: '/brand/profile-photo.jpeg',
  resumeUrl: '/resume.pdf',
  education: [
    {
      school: '东南大学（985）',
      major: '交通运输工程',
      degree: '硕士',
      period: '2024.09 - 2027.06',
      highlight: '专业排名前10%（16/282）· 2025年度研究生国家奖学金',
    },
    {
      school: '东南大学（985）',
      major: '交通工程',
      degree: '本科',
      period: '2020.09 - 2024.06',
      highlight: '学院排名前10%（9/93）· 2021年度本科生国家奖学金',
    },
  ],
  internships: [
    {
      company: '华为技术有限公司',
      dept: '终端云服务部',
      role: 'AI 应用开发',
      period: '2026.05 - 2026.08',
      background: '参与面向华为开发者技术支持场景知识生产与管理平台的功能开发，实现工单到知识自动化转换与全生命周期管理。',
      bullets: [
        '重构工单转知识为 Agent 驱动：将 Python 硬编排单阶段调用重构为 LLM 两阶段 + Python 中间处理的 ReAct Agent 架构，判定与生成分离，降低约 60% token 消耗，每日工单处理耗时缩减约 50%',
        '优化知识质量评估流水线：基于协程并行将工具检测、语义评估、代码规则等异构检查并行化，每阶段独立降级隔离故障，单条知识评估耗时从 18-45s 缩短至 10-25s',
        '设计并实现 Skill 自演化系统：基于审核意见驱动的 Map-Reduce 流程（LLM 定位缺陷生成结构化意见、版本管理、数据看板），单月知识审核通过率提高 15%',
        '实现 LLM 多实例池化机制：多模型轮询分摊流量 + 撞限额自动切换，约 8% 调用触发降级切换且最终成功',
      ],
    },
  ],
  skills: [
    {
      title: 'Java 与后端开发',
      items: [
        '熟悉 Java 与面向对象编程，理解常用集合底层实现、多线程与 JVM 核心机制',
        '理解 IoC、AOP 设计思想，能使用 Spring Boot、MyBatis 开发后端服务',
      ],
    },
    {
      title: '数据库与中间件',
      items: [
        '熟悉 MySQL 表结构与索引设计，了解存储引擎、事务机制及 SQL 优化',
        '熟悉 Redis 数据结构与缓存应用，了解分布式锁、主从复制等机制',
        '能够使用 Kafka 实现异步处理、系统解耦与消息传递',
      ],
    },
    {
      title: 'AI 应用开发',
      items: [
        '理解 LLM 核心概念，熟悉 Prompt Engineering、RAG、向量数据库及检索优化',
        '能使用 LangGraph 等框架进行 Workflow 编排与 Agent 开发，理解主流 Harness 设计',
        '熟练使用 Coding Agent 辅助研发',
      ],
    },
    {
      title: '工程化与部署',
      items: [
        '熟悉 Git、Linux 常用命令与 Docker 部署实践',
        '了解 Prometheus、Grafana 监控体系及 ELK、Loki 日志采集',
      ],
    },
  ],
  awards: [
    '2025 年度研究生国家奖学金',
    '2021 年度本科生国家奖学金',
    '2020 年全国大学生英语竞赛国家级二等奖',
    '2022 年全国大学生数学建模大赛江苏省二等奖',
  ],
  projects: [
    {
      id: 'eventguard',
      name: 'EventGuard',
      tagline: '电商订单事件溯源与智能异常治理平台',
      period: '2026.03 - 2026.08',
      desc: '面向电商订单全生命周期的追溯与异常治理：每次状态变更沉淀为可回放、可审计的事件，结合实时异常检测、告警分析、人工补偿和运营查询，实现从订单追溯到异常处置的闭环。',
      bullets: [
        '事件溯源写入：唯一约束 + 版本校验 + commandId 去重，每 100 事件快照，重建仅回放增量',
        '读写分离读模型：消费去重、版本守卫、投影通知 + 兜底轮询，重复不更新、乱序不回退',
        'Debezium CDC 链路：WAL → Kafka，手动提交 + 3 次重试 + 死信队列，宕机恢复 2s 内零丢失',
        '智能查询与处置：意图分类 + 模板接口防注入，8 场景查询全对，补偿闭环带人工审批',
      ],
      stack: ['Java 17', 'Spring Boot 3', 'PostgreSQL', 'Kafka', 'Debezium', 'FastAPI', 'Vue 3'],
      links: [
        { label: '在线体验', url: '/eventguard' },
        { label: '体验指南', url: '/guide' },
        { label: 'GitHub', url: 'https://github.com/JRW923/EventGuard' },
      ],
      online: true,
    },
    {
      id: 'synapse',
      name: 'Synapse',
      tagline: '可观测、可扩展、可评测的 Code Agent Harness',
      period: '2026.03 - 2026.08',
      desc: '面向代码仓库长链路任务的本地 Code Agent Harness：作为模型与真实开发环境之间的运行时控制层，统一治理上下文、记忆、工具执行和结果验证，解决多轮执行中的信息膨胀、状态丢失、越界操作及结果不可验证问题。',
      bullets: [
        '可扩展运行时：可替换组件边界统一管理 Agent/Session/事件生命周期，接入 6 家 Provider、4 类 Planner、2 种 MCP 传输',
        '上下文治理：Git-aware 检索、符号提取、相关性排序、四区预算与摘要式 compaction',
        '分层记忆与恢复：Session/Project/User/Semantic 四层记忆，原子持久化 + /resume 续接 + Git 快照回滚',
        'Action-time 安全：按工作区、shlex 命令链、重定向与 SSRF 判险，覆盖 5 级风险、14 类危险命令、12 类敏感路径',
        '冻结数据评测：SWE-bench 冻结 20 题三模型×3 评测，success@3 达 40%-55%',
      ],
      stack: ['Python', 'FastAPI', 'LLM API', 'Git', 'SWE-bench'],
      links: [{ label: 'GitHub', url: 'https://github.com/JRW923/Synapse' }],
      online: false,
    },
  ],
}
