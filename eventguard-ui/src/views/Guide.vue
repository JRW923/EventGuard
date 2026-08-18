<template>
  <div class="guide">
    <Starfield :density="150" accent-color="#22d3ee" />

    <!-- 顶栏 -->
    <header class="guide-top">
      <router-link class="guide-top-back" to="/">← 返回个人主页</router-link>
      <span class="guide-top-brand">EventGuard · 事件卫士</span>
      <button class="guide-top-enter" @click="goEnter">进入系统</button>
    </header>

    <main class="guide-main">
      <h1 class="guide-title reveal">EventGuard 项目体验指南</h1>
      <p class="guide-sub reveal">建议用时约 5 分钟，可通过浏览器依次验证核心业务流程、系统架构与权限设计。</p>

      <!-- 核心链路 -->
      <div class="guide-flow reveal">
        <div class="guide-flow-title">核心业务与数据链路</div>
        <div class="guide-flow-track">
          <template v-for="(n, i) in flow" :key="n.label">
            <div class="guide-flow-node">
              <span class="guide-flow-icon">{{ n.icon }}</span>
              <span class="guide-flow-label">{{ n.label }}</span>
            </div>
            <span v-if="i < flow.length - 1" class="guide-flow-arrow">→</span>
          </template>
        </div>
      </div>

      <ol class="guide-steps">
        <li v-for="(s, i) in steps" :key="i" class="guide-step reveal" :style="{ '--accent': s.accent }">
          <div class="guide-step-num">{{ i + 1 }}</div>
          <div class="guide-step-body">
            <h3 class="guide-step-title">{{ s.title }}</h3>
            <p class="guide-step-desc">{{ s.desc }}</p>
            <div class="guide-step-tags">
              <span class="guide-step-tag" v-for="t in s.tags" :key="t">{{ t }}</span>
            </div>
          </div>
        </li>
      </ol>

      <!-- 可重点考察的系统能力 -->
      <section class="guide-section reveal">
        <h2 class="guide-h2">可重点考察的系统能力</h2>
        <p class="guide-h2-sub">以下模块均可在演示环境中直接验证</p>
        <div class="guide-highlights">
          <div
            v-for="h in highlights"
            :key="h.title"
            class="guide-highlight"
            :style="{ '--accent': h.accent }"
          >
            <span class="guide-highlight-icon">{{ h.icon }}</span>
            <h3 class="guide-highlight-title">{{ h.title }}</h3>
            <p class="guide-highlight-desc">{{ h.desc }}</p>
          </div>
        </div>
      </section>

      <!-- 按角色体验建议 -->
      <section class="guide-section reveal">
        <h2 class="guide-h2">建议体验路径</h2>
        <p class="guide-h2-sub">不同角色对应不同的业务权限和观察视角</p>
        <div class="guide-roles">
          <div v-for="r in roleSuggs" :key="r.role" class="guide-role" :style="{ '--accent': r.accent }">
            <h3 class="guide-role-name">{{ r.role }}</h3>
            <p class="guide-role-desc">{{ r.desc }}</p>
          </div>
        </div>
      </section>

      <!-- 演示账号速查 -->
      <section class="guide-accounts reveal">
        <h2 class="guide-h2">演示账号</h2>
        <p class="guide-h2-sub">提供三种预设角色，便于快速验证权限边界</p>
        <div class="guide-accounts-grid">
          <div v-for="acc in accounts" :key="acc.username" class="guide-account" :style="{ '--accent': acc.accent }">
            <div class="guide-account-head">
              <span class="guide-account-role">{{ acc.role }}</span>
              <span class="guide-account-user">@{{ acc.username }}</span>
            </div>
            <div class="guide-account-pwd-row">
              <span
                class="guide-account-pwd"
                :class="{ 'guide-account-pwd--blurred': !acc.revealed }"
                :title="acc.revealed ? '点击隐藏' : '点击查看密码'"
                @click="acc.revealed = !acc.revealed"
                >{{ acc.password }}</span
              >
              <button class="guide-account-copy" @click="copyPassword(acc)">
                {{ acc.copied ? '已复制 ✓' : '复制' }}
              </button>
            </div>
          </div>
        </div>
        <p class="guide-account-note">
          以上为演示账号的默认密码，可直接登录体验。
        </p>
      </section>

      <!-- 常见问题 -->
      <section class="guide-section reveal">
        <h2 class="guide-h2">常见问题</h2>
        <div class="guide-faq">
          <div v-for="f in faqs" :key="f.q" class="guide-faq-item">
            <div class="guide-faq-q">Q：{{ f.q }}</div>
            <div class="guide-faq-a">{{ f.a }}</div>
          </div>
        </div>
      </section>

      <!-- CTA -->
      <div class="guide-cta reveal">
        <button class="guide-btn guide-btn--primary" @click="goEnter">进入系统开始体验</button>
        <router-link class="guide-btn guide-btn--ghost" to="/">返回个人主页</router-link>
      </div>
    </main>

    <footer class="guide-footer">© 2026 EventGuard · 事件卫士</footer>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import Starfield from '@/components/landing/Starfield.vue'
import { useStandalonePage } from '@/composables/useStandalonePage'
import { auth } from '@/stores/auth'

useStandalonePage()

const router = useRouter()

interface Step {
  title: string
  desc: string
  tags: string[]
  accent: string
}
const steps: Step[] = [
  {
    title: '登录系统',
    desc: '在登录页使用下方任一演示账号登录（admin / operator / viewer），进入对应权限范围的控制台。',
    tags: ['预设角色', 'RBAC 权限'],
    accent: '#818cf8',
  },
  {
    title: '创建一笔订单',
    desc: '进入「订单列表」新建订单，并在时间线中查看从创建到关闭的状态变更。每一步均以不可变事件记录，可用于回放与审计。',
    tags: ['事件溯源', '时间线回放'],
    accent: '#22d3ee',
  },
  {
    title: '看异常如何被发现',
    desc: '提交金额偏离或状态异常的订单，规则引擎与 AI 模型识别后，告警经 WebSocket 推送至「异常看板」，并提供根因分析与建议动作。',
    tags: ['CDC 实时管道', 'AI 异常检测', '根因分析'],
    accent: '#ec4899',
  },
  {
    title: '中文提问与自动补偿',
    desc: '在「NL 查询」中使用中文查询订单状态与统计；针对异常订单执行受控补偿动作，高风险操作进入人工审批流程。',
    tags: ['中文 NL 查询', 'Saga 自动补偿', '审批流'],
    accent: '#f59e0b',
  },
]

interface Account {
  username: string
  role: string
  password: string
  revealed: boolean
  copied: boolean
  accent: string
}
const accounts = ref<Account[]>([
  { username: 'admin', role: '管理员', password: 'admin123456', revealed: false, copied: false, accent: '#818cf8' },
  { username: 'operator', role: '运营', password: 'operator123456', revealed: false, copied: false, accent: '#22d3ee' },
  { username: 'viewer', role: '只读', password: 'viewer123456', revealed: false, copied: false, accent: '#c084fc' },
])

// 核心链路（一次体验看到的数据流动）
const flow = [
  { icon: '🧾', label: '创建订单' },
  { icon: '🗄️', label: '事件入库' },
  { icon: '⚡', label: 'CDC 实时管道' },
  { icon: '🤖', label: '规则 / AI 检测' },
  { icon: '🚨', label: '异常告警推送' },
  { icon: '🔁', label: '查询 / 补偿闭环' },
]

// 亮点清单
const highlights = [
  {
    icon: '🕰️',
    title: '事件时间线回放',
    desc: '打开任意订单的时间线，回放每一步状态变更 —— 像看录像一样复盘一笔订单的完整一生。',
    accent: '#818cf8',
  },
  {
    icon: '🚨',
    title: '实时异常看板',
    desc: '提交一笔金额偏离的订单，几秒内告警经 WebSocket 实时推送到看板，并被自动分类标注。',
    accent: '#ec4899',
  },
  {
    icon: '🛰️',
    title: 'AI 根因分析',
    desc: '点开任意告警，查看 LLM 给出的根因判断与建议动作 —— 而不是一条冷冰冰的日志。',
    accent: '#22d3ee',
  },
  {
    icon: '💬',
    title: '中文自然语言查询',
    desc: '直接输入“最近 7 天金额大于 1000 的订单有哪些”，系统用中文回答，无需写 SQL。',
    accent: '#06b6d4',
  },
  {
    icon: '🔁',
    title: 'Saga 自动补偿',
    desc: '对异常订单执行退款 / 通知等补偿动作，高风险操作自动挂起，等待人工审批后继续。',
    accent: '#f59e0b',
  },
  {
    icon: '🔐',
    title: '多角色权限体验',
    desc: '用 admin / operator / viewer 三个账号切换登录，感受同一系统下的不同视野与边界。',
    accent: '#22c55e',
  },
]

// 按角色建议的体验路径
const roleSuggs = [
  { role: '管理员 admin', desc: '查看用户、角色、权限分配与审计日志，重点考察 RBAC 的权限粒度和管理能力。', accent: '#818cf8' },
  { role: '运营 operator', desc: '创建订单、推进状态、处理异常并执行补偿，验证完整的业务处置闭环。', accent: '#22d3ee' },
  { role: '只读 viewer', desc: '以只读视角查看订单、异常看板与 NL 查询结果，验证权限隔离效果。', accent: '#c084fc' },
]

// 常见问题
const faqs = [
  { q: '演示环境中的数据来源？', a: '系统提供演示数据与异常注入能力，用于稳定复现订单事件、异常识别和补偿流程；识别过程仍由规则引擎与 AI 模型实时执行。' },
  { q: '提交异常订单后，告警多久会出现？', a: '订单写入后经 CDC 实时管道进入检测链路，通常在几秒内即可在「异常看板」看到告警，并经 WebSocket 实时推送，无需手动刷新。' },
  { q: '我的操作会影响其他人或真实数据吗？', a: '不会。演示环境为独立部署，数据与真实业务完全隔离；你创建、推进、补偿的订单仅影响当前演示环境，可放心操作。' },
  { q: '三个演示账号能否同时登录？', a: '可以。admin / operator / viewer 相互独立，可在多个浏览器同时登录，各自看到与角色对应的权限范围，互不影响。' },
  { q: 'NL 查询可以问哪些问题？', a: '支持按状态、金额、时间范围等条件用中文提问，例如“最近 7 天金额大于 1000 的订单有哪些”，系统会翻译为查询并返回结果与统计。' },
]

function goEnter() {
  if (auth.isAuthenticated) {
    router.push('/orders')
  } else {
    router.push('/login')
  }
}

function copyText(text: string): Promise<boolean> {
  if (navigator.clipboard?.writeText) {
    return navigator.clipboard
      .writeText(text)
      .then(() => true)
      .catch(() => fallbackCopy(text))
  }
  return Promise.resolve(fallbackCopy(text))
}
function fallbackCopy(text: string): boolean {
  try {
    const ta = document.createElement('textarea')
    ta.value = text
    ta.style.position = 'fixed'
    ta.style.opacity = '0'
    document.body.appendChild(ta)
    ta.select()
    document.execCommand('copy')
    document.body.removeChild(ta)
    return true
  } catch {
    return false
  }
}
async function copyPassword(acc: Account) {
  const ok = await copyText(acc.password)
  if (ok) {
    acc.copied = true
    setTimeout(() => {
      acc.copied = false
    }, 1500)
  }
}
</script>

<style>
body.eg-landing {
  background: #070b1a;
  color: #e2e8f0;
}
</style>

<style scoped>
.guide {
  position: relative;
  z-index: 1;
  min-height: 100vh;
  /* 根元素自带暗底：即使 body.eg-landing 尚未挂载，首帧也是深空背景而非灰底 */
  background: #070b1a;
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', 'PingFang SC', 'Microsoft YaHei', sans-serif;
  overflow-x: hidden;
  overflow-x: clip;
}

/* ---------- 顶栏 ---------- */
.guide-top {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  z-index: 20;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 14px 22px;
  backdrop-filter: blur(14px);
  -webkit-backdrop-filter: blur(14px);
  background: rgba(7, 11, 26, 0.6);
  border-bottom: 1px solid rgba(255, 255, 255, 0.08);
}
.guide-top-back {
  color: #94a3b8;
  text-decoration: none;
  font-size: 14px;
  transition: color 0.2s ease;
}
.guide-top-back:hover {
  color: #a5b4fc;
}
.guide-top-brand {
  font-size: 14px;
  font-weight: 700;
  color: #f1f5f9;
}
.guide-top-enter {
  padding: 7px 16px;
  font-size: 14px;
  border: none;
  border-radius: 9px;
  color: #fff;
  cursor: pointer;
  background: linear-gradient(135deg, #6366f1, #a855f7);
  box-shadow: 0 4px 18px rgba(129, 140, 248, 0.35);
  transition: transform 0.18s ease, box-shadow 0.18s ease;
  font-family: inherit;
}
.guide-top-enter:hover {
  transform: translateY(-1px);
  box-shadow: 0 6px 24px rgba(129, 140, 248, 0.5);
}

/* ---------- 主体 ---------- */
.guide-main {
  max-width: 1240px;
  margin: 0 auto;
  padding: 120px 20px 60px;
}
.guide-title {
  margin: 0;
  font-size: 42px;
  font-weight: 800;
  text-align: center;
  color: #f8fafc;
  text-shadow: 0 0 36px rgba(34, 211, 238, 0.25);
}
.guide-sub {
  margin: 14px auto 0;
  max-width: 560px;
  text-align: center;
  color: #94a3b8;
  font-size: 15px;
  line-height: 1.7;
}

/* ---------- 核心链路 ---------- */
.guide-flow {
  margin-top: 40px;
  padding: 24px;
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.045);
  border: 1px solid rgba(255, 255, 255, 0.1);
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
}
.guide-flow-title {
  text-align: center;
  font-size: 13px;
  letter-spacing: 0;
  color: #64748b;
}
.guide-flow-track {
  margin-top: 18px;
  display: flex;
  align-items: stretch;
  justify-content: stretch;
  flex-wrap: nowrap;
  gap: 8px;
}
.guide-flow-node {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 6px;
  flex: 1 1 0;
  min-width: 0;
  min-height: 92px;
  padding: 14px 10px;
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.05);
  border: 1px solid rgba(255, 255, 255, 0.1);
}
.guide-flow-icon {
  font-size: 34px;
  line-height: 1;
}
.guide-flow-label {
  font-size: 13px;
  text-align: center;
  color: #cbd5e1;
}
.guide-flow-arrow {
  flex: 0 0 20px;
  align-self: center;
  font-size: 16px;
  color: #475569;
}

/* ---------- 通用章节 ---------- */
.guide-section {
  margin-top: 56px;
  text-align: center;
}

/* ---------- 亮点 ---------- */
.guide-highlights {
  margin-top: 28px;
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(250px, 1fr));
  gap: 16px;
  text-align: left;
}
.guide-highlight {
  --accent: #818cf8;
  padding: 20px 20px 18px;
  border-radius: 14px;
  background: rgba(255, 255, 255, 0.045);
  border: 1px solid rgba(255, 255, 255, 0.1);
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
  transition: transform 0.2s ease, border-color 0.2s ease, box-shadow 0.2s ease;
}
.guide-highlight:hover {
  transform: translateY(-3px);
  border-color: color-mix(in srgb, var(--accent) 50%, transparent);
  box-shadow: 0 12px 32px -10px color-mix(in srgb, var(--accent) 35%, transparent);
}
.guide-highlight-icon {
  font-size: 26px;
  line-height: 1;
}
.guide-highlight-title {
  margin: 12px 0 6px;
  font-size: 16px;
  font-weight: 700;
  color: #f1f5f9;
}
.guide-highlight-desc {
  margin: 0;
  font-size: 13px;
  line-height: 1.7;
  color: #94a3b8;
}

/* ---------- 按角色体验 ---------- */
.guide-roles {
  margin-top: 28px;
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(240px, 1fr));
  gap: 16px;
  text-align: left;
}
.guide-role {
  --accent: #818cf8;
  padding: 20px;
  border-radius: 14px;
  background: rgba(255, 255, 255, 0.045);
  border: 1px solid rgba(255, 255, 255, 0.1);
  border-left: 3px solid var(--accent);
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
}
.guide-role-name {
  margin: 0;
  font-size: 16px;
  font-weight: 700;
  color: #f1f5f9;
}
.guide-role-desc {
  margin: 8px 0 0;
  font-size: 13px;
  line-height: 1.7;
  color: #94a3b8;
}

/* ---------- FAQ ---------- */
.guide-faq {
  margin-top: 28px;
  display: flex;
  flex-direction: column;
  gap: 12px;
  text-align: left;
}
.guide-faq-item {
  padding: 16px 20px;
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.04);
  border: 1px solid rgba(255, 255, 255, 0.09);
}
.guide-faq-q {
  font-size: 14px;
  font-weight: 700;
  color: #e2e8f0;
}
.guide-faq-a {
  margin-top: 6px;
  font-size: 13px;
  line-height: 1.7;
  color: #94a3b8;
}

/* ---------- 步骤 ---------- */
.guide-steps {
  margin: 44px 0 0;
  padding: 0;
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: 18px;
}
.guide-step {
  --accent: #818cf8;
  display: flex;
  gap: 18px;
  padding: 22px 24px;
  border-radius: 16px;
  background: rgba(255, 255, 255, 0.045);
  border: 1px solid rgba(255, 255, 255, 0.1);
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
  transition: transform 0.2s ease, box-shadow 0.2s ease, border-color 0.2s ease;
}
.guide-step:hover {
  transform: translateY(-3px);
  border-color: color-mix(in srgb, var(--accent) 50%, transparent);
  box-shadow: 0 12px 34px -10px color-mix(in srgb, var(--accent) 40%, transparent);
}
.guide-step-num {
  flex-shrink: 0;
  width: 44px;
  height: 44px;
  border-radius: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 20px;
  font-weight: 800;
  color: #fff;
  background: linear-gradient(135deg, var(--accent), color-mix(in srgb, var(--accent) 55%, #000));
  box-shadow: 0 6px 20px color-mix(in srgb, var(--accent) 35%, transparent);
}
.guide-step-title {
  margin: 0;
  font-size: 18px;
  font-weight: 700;
  color: #f1f5f9;
}
.guide-step-desc {
  margin: 8px 0 0;
  font-size: 14px;
  line-height: 1.75;
  color: #94a3b8;
}
.guide-step-tags {
  margin-top: 12px;
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.guide-step-tag {
  padding: 3px 10px;
  font-size: 12px;
  border-radius: 6px;
  color: #c7d2fe;
  background: rgba(129, 140, 248, 0.1);
  border: 1px solid rgba(129, 140, 248, 0.22);
}

/* ---------- 账号 ---------- */
.guide-accounts {
  margin-top: 56px;
  text-align: center;
}
.guide-h2 {
  margin: 0;
  font-size: 26px;
  font-weight: 800;
  color: #f1f5f9;
}
.guide-h2-sub {
  margin: 10px 0 0;
  color: #94a3b8;
  font-size: 14px;
}
.guide-accounts-grid {
  margin-top: 28px;
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(230px, 1fr));
  gap: 16px;
}
.guide-account {
  --accent: #818cf8;
  padding: 18px;
  border-radius: 14px;
  background: rgba(255, 255, 255, 0.045);
  border: 1px solid rgba(255, 255, 255, 0.1);
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
}
.guide-account-head {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
}
.guide-account-role {
  padding: 3px 12px;
  font-size: 13px;
  font-weight: 600;
  border-radius: 999px;
  color: #fff;
  background: linear-gradient(135deg, var(--accent), color-mix(in srgb, var(--accent) 60%, #000));
}
.guide-account-user {
  font-size: 15px;
  font-weight: 700;
  color: #f1f5f9;
}
.guide-account-pwd-row {
  margin-top: 14px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 8px 12px;
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.04);
  border: 1px solid rgba(255, 255, 255, 0.09);
}
.guide-account-pwd {
  font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', Menlo, monospace;
  font-size: 14px;
  letter-spacing: 0.5px;
  color: #c7d2fe;
  cursor: pointer;
  user-select: none;
  transition: filter 0.2s ease;
}
.guide-account-pwd--blurred {
  filter: blur(5px);
  color: #e2e8f0;
}
.guide-account-pwd--blurred:hover {
  filter: blur(2px);
}
.guide-account-copy {
  flex-shrink: 0;
  padding: 4px 10px;
  font-size: 12px;
  border-radius: 7px;
  border: 1px solid rgba(255, 255, 255, 0.16);
  background: rgba(255, 255, 255, 0.07);
  color: #cbd5e1;
  cursor: pointer;
  transition: background 0.2s ease, color 0.2s ease;
  font-family: inherit;
}
.guide-account-copy:hover {
  background: rgba(255, 255, 255, 0.14);
  color: #fff;
}
.guide-account-note {
  margin: 20px 0 0;
  font-size: 13px;
  color: #94a3b8;
}
.guide-account-note code {
  padding: 2px 6px;
  border-radius: 5px;
  font-size: 12px;
  background: rgba(255, 255, 255, 0.08);
  color: #a5b4fc;
}

/* ---------- CTA ---------- */
.guide-cta {
  margin-top: 48px;
  display: flex;
  justify-content: center;
  gap: 16px;
  flex-wrap: wrap;
}
.guide-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  font-size: 15px;
  font-family: inherit;
  padding: 12px 28px;
  border-radius: 12px;
  cursor: pointer;
  text-decoration: none;
  border: none;
  transition: transform 0.18s ease, box-shadow 0.18s ease, background 0.2s ease;
}
.guide-btn:active {
  transform: scale(0.97);
}
.guide-btn--primary {
  color: #fff;
  background: linear-gradient(135deg, #6366f1, #8b5cf6, #a855f7);
  box-shadow: 0 6px 26px rgba(129, 140, 248, 0.4);
}
.guide-btn--primary:hover {
  transform: translateY(-2px);
  box-shadow: 0 10px 34px rgba(129, 140, 248, 0.55);
}
.guide-btn--ghost {
  color: #cbd5e1;
  background: rgba(255, 255, 255, 0.06);
  border: 1px solid rgba(255, 255, 255, 0.16);
}
.guide-btn--ghost:hover {
  background: rgba(255, 255, 255, 0.12);
  border-color: rgba(255, 255, 255, 0.3);
  transform: translateY(-2px);
}

/* ---------- 页脚 ---------- */
.guide-footer {
  margin-top: 50px;
  padding: 24px 20px 34px;
  border-top: 1px solid rgba(255, 255, 255, 0.08);
  text-align: center;
  font-size: 13px;
  color: #64748b;
}

.reveal {
  opacity: 1;
}
.landing-revealed {
  animation: landing-fade-up 0.7s ease both;
}
@keyframes landing-fade-up {
  from {
    opacity: 0;
    transform: translateY(24px);
  }
  to {
    opacity: 1;
    transform: none;
  }
}

/* 窄屏：核心链路允许换行，避免横向溢出 */
@media (max-width: 820px) {
  .guide-title { font-size: 32px; }
  .guide-flow-track {
    flex-wrap: wrap;
  }
  .guide-flow-node {
    flex: 1 1 140px;
    min-width: 140px;
  }
  .guide-flow-arrow { display: none; }
}

@media (prefers-reduced-motion: reduce) {
  .landing-revealed {
    animation: none;
  }
  .guide-step {
    transition: none;
  }
}
</style>
