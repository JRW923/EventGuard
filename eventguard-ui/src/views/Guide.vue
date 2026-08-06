<template>
  <div class="guide">
    <Starfield :density="150" accent-color="#22d3ee" />

    <!-- 顶栏 -->
    <header class="guide-top">
      <router-link class="guide-top-back" to="/">← 返回首页</router-link>
      <span class="guide-top-brand">EventGuard · 事件卫士</span>
      <button class="guide-top-enter" @click="goEnter">进入系统</button>
    </header>

    <main class="guide-main">
      <h1 class="guide-title reveal">如何体验 EventGuard</h1>
      <p class="guide-sub reveal">四步走完核心链路 —— 约 5 分钟，浏览器即可，无需安装任何东西</p>

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

      <!-- 演示账号速查 -->
      <section class="guide-accounts reveal">
        <h2 class="guide-h2">演示账号</h2>
        <p class="guide-h2-sub">三种角色任选 —— 密码默认模糊，点击即可查看</p>
        <div class="guide-accounts-grid">
          <div v-for="acc in accounts" :key="acc.username" class="guide-account" :style="{ '--accent': acc.accent }">
            <div class="guide-account-head">
              <span class="guide-account-role">{{ acc.role }}</span>
              <span class="guide-account-user">@{{ acc.username }}</span>
            </div>
            <span
              class="guide-account-pwd"
              :class="{ 'guide-account-pwd--blurred': !acc.revealed }"
              :title="acc.revealed ? '点击隐藏' : '点击查看密码'"
              @click="acc.revealed = !acc.revealed"
              >{{ acc.password }}</span
            >
          </div>
        </div>
        <p class="guide-account-note">
          以上为默认演示密码，可用 .env 的 <code>EG_*_PASSWORD</code> 覆盖；首次登录会强制修改密码。
        </p>
      </section>

      <!-- CTA -->
      <div class="guide-cta reveal">
        <button class="guide-btn guide-btn--primary" @click="goEnter">🚀 现在就开始体验</button>
        <router-link class="guide-btn guide-btn--ghost" to="/">返回首页</router-link>
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
    desc: '在登录页用下方任一演示账号登录（admin / operator / viewer）。首次登录会强制修改密码，改后即可进入控制台。',
    tags: ['选择角色', '首次登录改密'],
    accent: '#818cf8',
  },
  {
    title: '创建一笔订单',
    desc: '进入「订单列表」新建订单，然后在时间线里查看它从创建到关闭的每一步状态变更 —— 每一步都是不可变事件，可随时回放任意历史时刻。',
    tags: ['事件溯源', '时间线回放'],
    accent: '#22d3ee',
  },
  {
    title: '看异常如何被发现',
    desc: '提交一笔金额偏离或异常流水的订单，规则引擎 / AI 模型命中后，告警经 WebSocket 实时推送到「异常看板」；点开任意告警可查看 AI 给出的根因分析与建议动作。',
    tags: ['CDC 实时管道', 'AI 异常检测', '根因分析'],
    accent: '#ec4899',
  },
  {
    title: '中文提问与自动补偿',
    desc: '在「NL 查询」用中文直接问订单状态与统计；对异常订单可执行白名单补偿动作（退款 / 通知等），高风险动作会自动挂起人工审批。',
    tags: ['中文 NL 查询', 'Saga 自动补偿', '审批流'],
    accent: '#f59e0b',
  },
]

interface Account {
  username: string
  role: string
  password: string
  revealed: boolean
  accent: string
}
const accounts = ref<Account[]>([
  { username: 'admin', role: '管理员', password: 'admin123456', revealed: false, accent: '#818cf8' },
  { username: 'operator', role: '运营', password: 'operator123456', revealed: false, accent: '#22d3ee' },
  { username: 'viewer', role: '只读', password: 'viewer123456', revealed: false, accent: '#c084fc' },
])

function goEnter() {
  if (auth.isAuthenticated) {
    router.push('/orders')
  } else {
    router.push('/login')
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
  max-width: 820px;
  margin: 0 auto;
  padding: 120px 20px 60px;
}
.guide-title {
  margin: 0;
  font-size: clamp(28px, 5vw, 42px);
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
.guide-account-pwd {
  display: block;
  margin-top: 14px;
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
  opacity: 0;
  transform: translateY(24px);
  transition: opacity 0.7s ease, transform 0.7s ease;
}
.landing-revealed {
  opacity: 1;
  transform: none;
}

@media (prefers-reduced-motion: reduce) {
  .reveal {
    opacity: 1;
    transform: none;
    transition: none;
  }
  .guide-step {
    transition: none;
  }
}
</style>
