<template>
  <div class="landing">
    <Starfield :density="180" accent-color="#a78bfa" />

    <!-- 顶部导航 -->
    <nav class="landing-nav" :class="{ 'landing-nav--scrolled': scrolled }">
      <div class="landing-nav-inner">
        <div class="landing-nav-brand">
          <img src="/brand/logo-2.png" alt="EventGuard" class="landing-nav-logo" />
          <span class="landing-nav-name">EventGuard</span>
        </div>
        <div class="landing-nav-links">
          <a href="#features" @click.prevent="scrollToSection('features')">核心能力</a>
          <a href="#projtech" @click.prevent="scrollToSection('projtech')">项目技术栈</a>
          <a href="#accounts" @click.prevent="scrollToSection('accounts')">体验账号</a>
          <a href="#about" @click.prevent="scrollToSection('about')">关于我</a>
        </div>
        <div class="landing-nav-actions">
          <router-link class="landing-btn landing-btn--ghost" to="/guide">体验指南</router-link>
          <button class="landing-btn landing-btn--primary" @click="goEnter">进入系统</button>
        </div>
      </div>
    </nav>

    <!-- Hero -->
    <header class="landing-hero">
      <div class="landing-hero-inner reveal">
        <div class="landing-logo-wrap">
          <img src="/brand/logo-2.png" alt="EventGuard" class="landing-logo" />
        </div>
        <h1 class="landing-title">EventGuard <span class="landing-title-cn">事件卫士</span></h1>
        <p class="landing-sub">
          一套面向电商订单的<strong>事件溯源 + 智能异常检测 + 中文自然语言查询</strong>平台 ——
          让每一次业务变化都有据可查，让异常能被及时识别。
        </p>
        <div class="landing-chips">
          <span class="landing-chip" v-for="chip in heroChips" :key="chip">{{ chip }}</span>
        </div>
        <div class="landing-hero-actions">
          <button class="landing-btn landing-btn--primary landing-btn--lg" @click="goEnter">🚀 进入系统</button>
          <router-link class="landing-btn landing-btn--glass landing-btn--lg" to="/guide">📖 体验指南</router-link>
        </div>
        <p class="landing-hint">公开演示环境 · 使用下方「体验账号」即可登录体验</p>
      </div>
      <button class="landing-scroll" aria-label="向下浏览" @click="scrollToSection('features')">
        <span class="landing-scroll-chevron"></span>
      </button>
    </header>

    <!-- 核心能力 -->
    <section id="features" class="landing-section">
      <h2 class="landing-h2 reveal">核心能力</h2>
      <div class="landing-cards">
        <div
          v-for="f in features"
          :key="f.title"
          class="landing-card reveal"
          :style="{ '--accent': f.accent }"
          @pointermove="onCardPointerMove"
          @pointerleave="onCardPointerLeave"
        >
          <div class="landing-card-icon">{{ f.icon }}</div>
          <h3 class="landing-card-title">{{ f.title }}</h3>
          <p class="landing-card-desc">{{ f.desc }}</p>
          <div class="landing-card-tags">
            <span v-for="tag in f.tags" :key="tag" class="landing-card-tag">{{ tag }}</span>
          </div>
        </div>
      </div>
    </section>

    <!-- 项目技术栈 -->
    <section id="projtech" class="landing-section landing-section--thin">
      <h2 class="landing-h2 reveal">项目技术栈</h2>
      <div class="landing-projtech-chips reveal">
        <span class="landing-tech-item" v-for="t in projectStack" :key="t">{{ t }}</span>
      </div>
    </section>

    <!-- 体验账号 -->
    <section id="accounts" class="landing-section">
      <h2 class="landing-h2 reveal">体验账号</h2>
      <div class="landing-accounts">
        <div
          v-for="acc in accounts"
          :key="acc.username"
          class="landing-account reveal"
          :style="{ '--accent': acc.accent }"
        >
          <div class="landing-account-head">
            <span class="landing-account-role">{{ acc.role }}</span>
            <span class="landing-account-user">@{{ acc.username }}</span>
          </div>
          <p class="landing-account-desc">{{ acc.desc }}</p>
          <div class="landing-account-pwd">
            <span class="landing-account-pwd-label">默认密码</span>
            <span
              class="landing-account-pwd-value"
              :class="{ 'landing-account-pwd-value--blurred': !acc.revealed }"
              :title="acc.revealed ? '点击隐藏' : '点击查看密码'"
              @click="toggleReveal(acc)"
              >{{ acc.password }}</span
            >
            <button class="landing-account-copy" @click="copyPassword(acc)">
              {{ acc.copied ? '已复制 ✓' : '复制' }}
            </button>
          </div>
          <p class="landing-account-tip">
            {{ acc.revealed ? '点击密码可再次隐藏' : '点击模糊密码即可查看' }}
          </p>
        </div>
      </div>
      <p class="landing-account-note reveal">以上为演示账号的默认密码，可直接登录体验。</p>
    </section>

    <!-- 关于我 -->
    <section id="about" class="landing-section">
      <h2 class="landing-h2 reveal">关于我</h2>
      <div class="landing-about reveal">
        <div class="landing-about-card">
          <div class="landing-about-head">
            <div class="landing-about-avatar">吴</div>
            <div class="landing-about-info">
              <h3 class="landing-about-name">吴佳睿</h3>
              <p class="landing-about-roles">后端开发 · AI 应用开发 · Agent 开发</p>
              <p class="landing-about-edu">🎓 东南大学 · 本硕</p>
              <div class="landing-about-links">
              <a class="landing-about-link" href="https://github.com/JRW923" target="_blank" rel="noopener noreferrer">
                <svg viewBox="0 0 16 16" class="landing-about-github" aria-hidden="true">
                  <path
                    fill="currentColor"
                    d="M8 0C3.58 0 0 3.58 0 8c0 3.54 2.29 6.53 5.47 7.59.4.07.55-.17.55-.38 0-.19-.01-.82-.01-1.49-2.01.37-2.53-.49-2.69-.94-.09-.23-.48-.94-.82-1.13-.28-.15-.68-.52-.01-.53.63-.01 1.08.58 1.23.82.72 1.21 1.87.87 2.33.66.07-.52.28-.87.51-1.07-1.78-.2-3.64-.89-3.64-3.95 0-.87.31-1.59.82-2.15-.08-.2-.36-1.02.08-2.12 0 0 .67-.21 2.2.82.64-.18 1.32-.27 2-.27.68 0 1.36.09 2 .27 1.53-1.04 2.2-.82 2.2-.82.44 1.1.16 1.92.08 2.12.51.56.82 1.27.82 2.15 0 3.07-1.87 3.75-3.65 3.95.29.25.54.73.54 1.48 0 1.07-.01 1.93-.01 2.2 0 .21.15.46.55.38A8.01 8.01 0 0 0 16 8c0-4.42-3.58-8-8-8Z"
                  />
                </svg>
                GitHub · JRW923
              </a>
              <span class="landing-about-qq">💬 QQ · 471464213</span>
              </div>
            </div>
          </div>
          <div class="landing-about-groups">
            <div
              v-for="g in techGroups"
              :key="g.title"
              class="landing-tech-group reveal"
              :style="{ '--accent': g.accent }"
            >
              <div class="landing-tech-group-head">
                <span class="landing-tech-group-icon">{{ g.icon }}</span>
                <span class="landing-tech-group-title">{{ g.title }}</span>
              </div>
              <div class="landing-tech-group-items">
                <span class="landing-tech-item" v-for="t in g.items" :key="t">{{ t }}</span>
              </div>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- 页脚 -->
    <footer class="landing-footer">
      <div class="landing-footer-inner">
        <span>© 2026 EventGuard · 事件卫士</span>
        <span class="landing-footer-dot">·</span>
        <router-link to="/guide">体验指南</router-link>
        <span class="landing-footer-dot">·</span>
        <router-link to="/login">登录控制台</router-link>
      </div>
    </footer>
  </div>
</template>

<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import Starfield from '@/components/landing/Starfield.vue'
import { useStandalonePage } from '@/composables/useStandalonePage'
import { auth } from '@/stores/auth'

useStandalonePage()

const router = useRouter()

const scrolled = ref(false)

const heroChips = ['事件溯源 + 回放', 'CDC 实时管道', 'AI 异常检测', '中文 NL 查询', 'Saga 自动补偿']

interface Feature {
  icon: string
  title: string
  desc: string
  tags: string[]
  accent: string
}
const features: Feature[] = [
  {
    icon: '🧾',
    title: '事件溯源 + 回放',
    desc: '订单每一步状态变更都作为不可变事件落库，可随时回放任意历史时刻，审计与排障更清晰。',
    tags: ['Event Sourcing', 'CQRS', '乐观锁'],
    accent: '#6366f1',
  },
  {
    icon: '⚡',
    title: 'CDC 实时管道',
    desc: 'Debezium 捕获事件库变更，经 Kafka 实时流入检测侧，订单提交到异常发现端到端近实时可见。',
    tags: ['Debezium', 'Kafka', 'CDC'],
    accent: '#8b5cf6',
  },
  {
    icon: '🛰️',
    title: 'AI 异常检测',
    desc: '规则引擎 + 无监督模型双通道识别异常订单，命中规则 / 模型与异常类型清晰标注。',
    tags: ['规则引擎', 'IsolationForest'],
    accent: '#ec4899',
  },
  {
    icon: '💬',
    title: '中文 NL 查询',
    desc: '用中文直接问订单、查统计、追轨迹，意图识别后安全调用后端接口，不暴露原始 SQL。',
    tags: ['NL2API', '意图分类'],
    accent: '#06b6d4',
  },
  {
    icon: '🔁',
    title: 'Saga 自动补偿',
    desc: '失败类事件自动触发退款 / 标记缺货 / 通知用户闭环，高风险动作挂人工审批，重启也不丢在途补偿。',
    tags: ['Saga', '补偿', '审批流'],
    accent: '#f59e0b',
  },
  {
    icon: '🔐',
    title: '根因分析 + RBAC',
    desc: 'LLM 产出异常根因与白名单建议动作；JWT + 用户-角色-权限覆盖 REST / WebSocket / AI 全链路鉴权。',
    tags: ['LLM', 'JWT', 'RBAC'],
    accent: '#22c55e',
  },
]

interface TechGroup {
  icon: string
  title: string
  items: string[]
  accent: string
}
const techGroups: TechGroup[] = [
  {
    icon: '⌨️',
    title: '语言',
    items: ['Java', 'Python'],
    accent: '#6366f1',
  },
  {
    icon: '🧩',
    title: '后端基础',
    items: ['Spring Boot', 'Spring MVC', 'MyBatis', 'MyBatis-Plus', 'Spring Security / JWT', 'RESTful API'],
    accent: '#8b5cf6',
  },
  {
    icon: '🗄️',
    title: '数据库与中间件',
    items: ['MySQL', 'PostgreSQL', 'Redis', 'Kafka'],
    accent: '#06b6d4',
  },
  {
    icon: '🤖',
    title: 'AI 工程',
    items: ['Prompt Engineering', 'RAG', 'Agent Harness', 'Workflow', 'Proficient with Coding Agents'],
    accent: '#ec4899',
  },
  {
    icon: '🚀',
    title: '部署与运维',
    items: ['Git', 'Linux', 'Docker + Nginx', 'Prometheus + Grafana', 'ELK + Loki'],
    accent: '#22c55e',
  },
]

// 项目技术栈（EventGuard 这套系统实际用到的技术）
const projectStack = [
  'Java 21',
  'Spring Boot',
  'PostgreSQL',
  'Debezium',
  'Kafka',
  'Vue 3',
  'TypeScript',
  'Element Plus',
  'Python',
  'WebSocket',
  'Prometheus',
  'Grafana',
  'Docker',
  'PWA',
]

interface Account {
  username: string
  role: string
  desc: string
  password: string
  revealed: boolean
  copied: boolean
  accent: string
}
const accounts = ref<Account[]>([
  { username: 'admin', role: '管理员', desc: '用户 / 角色 / 审计全权限', password: 'admin123456', revealed: false, copied: false, accent: '#818cf8' },
  { username: 'operator', role: '运营', desc: '下单 · 状态 · 异常 · 补偿', password: 'operator123456', revealed: false, copied: false, accent: '#22d3ee' },
  { username: 'viewer', role: '只读', desc: '订单 / 看板 / NL 查询', password: 'viewer123456', revealed: false, copied: false, accent: '#c084fc' },
])

// 个人技能见「关于我」板块（按 语言/后端/数据库与中间件/AI工程/部署运维 分类）
function goEnter() {
  if (auth.isAuthenticated) {
    router.push('/orders')
  } else {
    router.push('/login')
  }
}

function scrollToSection(id: string) {
  document.getElementById(id)?.scrollIntoView({ behavior: 'smooth', block: 'start' })
}

function toggleReveal(acc: Account) {
  acc.revealed = !acc.revealed
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

function onCardPointerMove(e: PointerEvent) {
  if (window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches) return
  const card = e.currentTarget as HTMLElement
  const rect = card.getBoundingClientRect()
  const px = (e.clientX - rect.left) / rect.width - 0.5
  const py = (e.clientY - rect.top) / rect.height - 0.5
  card.style.transform = `perspective(900px) rotateX(${(-py * 6).toFixed(2)}deg) rotateY(${(px * 8).toFixed(2)}deg) translateY(-4px)`
}
function onCardPointerLeave(e: PointerEvent) {
  ;(e.currentTarget as HTMLElement).style.transform = ''
}

function onScroll() {
  scrolled.value = window.scrollY > 12
}

let scrollListenerAttached = false

onMounted(() => {
  window.addEventListener('scroll', onScroll, { passive: true })
  scrollListenerAttached = true
  onScroll()
})

onUnmounted(() => {
  if (scrollListenerAttached) window.removeEventListener('scroll', onScroll)
})
</script>

<style>
/* 落地页暗色背景：body 类切换，避免滚动越界露出亮色 */
body.eg-landing {
  background: #070b1a;
  color: #e2e8f0;
}
</style>

<style scoped>
.landing {
  position: relative;
  z-index: 1;
  min-height: 100vh;
  /* 根元素自带暗底：即使 body.eg-landing 尚未挂载，首帧也是深空背景而非灰底 */
  background: #070b1a;
  /* 用 clip 而非 hidden：hidden 会把元素变成滚动容器，干扰 window 滚动事件与滚动浮现；clip 只裁剪不滚动（老浏览器回退 hidden） */
  overflow-x: hidden;
  overflow-x: clip;
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', 'PingFang SC', 'Microsoft YaHei', sans-serif;
}

/* ---------- 顶部导航 ---------- */
.landing-nav {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  z-index: 20;
  backdrop-filter: blur(14px);
  -webkit-backdrop-filter: blur(14px);
  background: rgba(7, 11, 26, 0.55);
  border-bottom: 1px solid rgba(255, 255, 255, 0.08);
  transition: background 0.3s ease, border-color 0.3s ease;
}
.landing-nav--scrolled {
  background: rgba(7, 11, 26, 0.82);
  border-color: rgba(255, 255, 255, 0.12);
}
.landing-nav-inner {
  max-width: 1120px;
  margin: 0 auto;
  padding: 12px 20px;
  display: flex;
  align-items: center;
  gap: 16px;
}
.landing-nav-brand {
  display: flex;
  align-items: center;
  gap: 8px;
  font-weight: 700;
  font-size: 16px;
  color: #f1f5f9;
  white-space: nowrap;
}
.landing-nav-logo {
  width: 26px;
  height: 26px;
  border-radius: 6px;
}
.landing-nav-name {
  letter-spacing: 0.2px;
}
.landing-nav-links {
  flex: 1;
  display: flex;
  gap: 22px;
  justify-content: center;
}
.landing-nav-links a {
  color: #cbd5e1;
  text-decoration: none;
  font-size: 14px;
  transition: color 0.2s ease;
}
.landing-nav-links a:hover {
  color: #a5b4fc;
}
.landing-nav-actions {
  display: flex;
  gap: 10px;
  align-items: center;
}

/* ---------- 按钮 ---------- */
.landing-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  border: none;
  cursor: pointer;
  font-size: 14px;
  font-family: inherit;
  padding: 8px 18px;
  border-radius: 10px;
  text-decoration: none;
  transition: transform 0.18s ease, box-shadow 0.18s ease, background 0.2s ease;
  user-select: none;
}
.landing-btn:active {
  transform: translateY(0) scale(0.97);
}
.landing-btn--primary {
  color: #fff;
  background: linear-gradient(135deg, #6366f1 0%, #8b5cf6 55%, #a855f7 100%);
  box-shadow: 0 4px 22px rgba(129, 140, 248, 0.4);
  position: relative;
  overflow: hidden;
}
.landing-btn--primary::after {
  content: '';
  position: absolute;
  top: 0;
  left: -80%;
  width: 50%;
  height: 100%;
  background: linear-gradient(100deg, transparent, rgba(255, 255, 255, 0.35), transparent);
  transform: skewX(-20deg);
  transition: left 0.6s ease;
}
.landing-btn--primary:hover::after {
  left: 130%;
}
.landing-btn--primary:hover {
  transform: translateY(-2px);
  box-shadow: 0 8px 32px rgba(129, 140, 248, 0.55);
}
.landing-btn--ghost {
  color: #cbd5e1;
  background: rgba(255, 255, 255, 0.06);
  border: 1px solid rgba(255, 255, 255, 0.14);
}
.landing-btn--ghost:hover {
  color: #fff;
  border-color: rgba(255, 255, 255, 0.3);
  background: rgba(255, 255, 255, 0.1);
}
.landing-btn--glass {
  color: #e2e8f0;
  background: rgba(255, 255, 255, 0.08);
  border: 1px solid rgba(255, 255, 255, 0.2);
  backdrop-filter: blur(8px);
  -webkit-backdrop-filter: blur(8px);
}
.landing-btn--glass:hover {
  background: rgba(255, 255, 255, 0.14);
  border-color: rgba(255, 255, 255, 0.34);
  transform: translateY(-2px);
}
.landing-btn--lg {
  font-size: 16px;
  padding: 13px 30px;
  border-radius: 12px;
}

/* ---------- Hero ---------- */
.landing-hero {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  text-align: center;
  padding: 96px 20px 60px;
  position: relative;
}
.landing-logo-wrap {
  display: inline-block;
  animation: landing-float 5s ease-in-out infinite;
  filter: drop-shadow(0 6px 24px rgba(129, 140, 248, 0.45));
}
.landing-logo {
  width: 88px;
  height: 88px;
  border-radius: 22px;
}
@keyframes landing-float {
  0%,
  100% {
    transform: translateY(0);
  }
  50% {
    transform: translateY(-10px);
  }
}
.landing-title {
  margin: 26px 0 0;
  font-size: clamp(34px, 6vw, 58px);
  font-weight: 800;
  color: #f8fafc;
  letter-spacing: 1px;
  text-shadow: 0 0 40px rgba(129, 140, 248, 0.45);
}
.landing-title-cn {
  background: linear-gradient(120deg, #a5b4fc, #f0abfc, #a5b4fc);
  -webkit-background-clip: text;
  background-clip: text;
  color: transparent;
}
.landing-sub {
  margin: 18px auto 0;
  max-width: 640px;
  font-size: 16px;
  line-height: 1.8;
  color: #94a3b8;
}
.landing-sub strong {
  color: #c7d2fe;
  font-weight: 600;
}
.landing-chips {
  margin-top: 26px;
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  justify-content: center;
}
.landing-chip {
  padding: 6px 14px;
  border-radius: 999px;
  font-size: 13px;
  color: #c7d2fe;
  background: rgba(129, 140, 248, 0.1);
  border: 1px solid rgba(129, 140, 248, 0.28);
}
.landing-hero-actions {
  margin-top: 34px;
  display: flex;
  gap: 16px;
  justify-content: center;
  flex-wrap: wrap;
}
.landing-hint {
  margin-top: 18px;
  font-size: 13px;
  color: #64748b;
}
.landing-scroll {
  position: absolute;
  bottom: 30px;
  left: 50%;
  transform: translateX(-50%);
  background: none;
  border: none;
  cursor: pointer;
  padding: 8px;
  animation: landing-bob 1.8s ease-in-out infinite;
}
@keyframes landing-bob {
  0%,
  100% {
    transform: translate(-50%, 0);
    opacity: 0.55;
  }
  50% {
    transform: translate(-50%, 8px);
    opacity: 1;
  }
}
.landing-scroll-chevron {
  display: block;
  width: 18px;
  height: 18px;
  border-right: 2px solid #94a3b8;
  border-bottom: 2px solid #94a3b8;
  transform: rotate(45deg);
}

/* ---------- 通用板块 ---------- */
.landing-section {
  max-width: 1120px;
  margin: 0 auto;
  padding: 52px 20px 36px;
  scroll-margin-top: 70px;
}
.landing-h2 {
  margin: 0;
  text-align: center;
  font-size: 30px;
  font-weight: 800;
  color: #f1f5f9;
}
.landing-h2::after {
  content: '';
  display: block;
  width: 56px;
  height: 4px;
  margin: 14px auto 0;
  border-radius: 2px;
  background: linear-gradient(90deg, #6366f1, #a855f7, #ec4899);
}
.landing-sec-sub {
  margin: 14px auto 0;
  text-align: center;
  color: #94a3b8;
  font-size: 15px;
}

/* 项目技术栈：紧贴上一板块的窄区块 */
.landing-section--thin {
  padding-top: 24px;
}
.landing-projtech-chips {
  --accent: #a5b4fc;
  margin-top: 30px;
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  justify-content: center;
}

/* ---------- 核心能力卡片 ---------- */
.landing-cards {
  margin-top: 40px;
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(300px, 1fr));
  gap: 22px;
}
.landing-card {
  --accent: #6366f1;
  padding: 26px 24px;
  border-radius: 16px;
  background: rgba(255, 255, 255, 0.045);
  border: 1px solid rgba(255, 255, 255, 0.1);
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
  transition: transform 0.2s ease, box-shadow 0.2s ease, border-color 0.2s ease;
  will-change: transform;
}
.landing-card:hover {
  border-color: color-mix(in srgb, var(--accent) 55%, transparent);
  box-shadow: 0 12px 40px -8px color-mix(in srgb, var(--accent) 40%, transparent);
}
.landing-card-icon {
  font-size: 30px;
  line-height: 1;
}
.landing-card-title {
  margin: 14px 0 8px;
  font-size: 18px;
  font-weight: 700;
  color: #f1f5f9;
}
.landing-card-desc {
  margin: 0;
  font-size: 14px;
  line-height: 1.7;
  color: #94a3b8;
}
.landing-card-tags {
  margin-top: 14px;
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.landing-card-tag {
  padding: 3px 10px;
  font-size: 12px;
  border-radius: 6px;
  color: #c7d2fe;
  background: rgba(129, 140, 248, 0.1);
  border: 1px solid rgba(129, 140, 248, 0.22);
}

/* ---------- 技能分组（关于我内，行式） ---------- */
.landing-tech-group {
  --accent: #6366f1;
  display: flex;
  align-items: flex-start;
  gap: 18px;
}
.landing-tech-group-head {
  flex-shrink: 0;
  width: 150px;
  display: flex;
  align-items: center;
  gap: 8px;
  padding-top: 6px;
}
.landing-tech-group-icon {
  font-size: 16px;
  line-height: 1;
}
.landing-tech-group-title {
  font-size: 15px;
  font-weight: 700;
  color: #c7d2fe;
}
.landing-tech-group-items {
  flex: 1;
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}
.landing-tech-item {
  padding: 6px 14px;
  font-size: 14px;
  border-radius: 999px;
  color: #cbd5e1;
  background: rgba(255, 255, 255, 0.05);
  border: 1px solid rgba(255, 255, 255, 0.12);
  transition: transform 0.15s ease, border-color 0.2s ease, background 0.2s ease;
}
.landing-tech-item:hover {
  transform: translateY(-3px);
  border-color: color-mix(in srgb, var(--accent) 55%, transparent);
  background: rgba(255, 255, 255, 0.08);
}

/* ---------- 体验账号 ---------- */
.landing-accounts {
  margin-top: 40px;
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(260px, 1fr));
  gap: 20px;
}
.landing-account {
  --accent: #818cf8;
  padding: 22px 22px 18px;
  border-radius: 16px;
  background: rgba(255, 255, 255, 0.045);
  border: 1px solid rgba(255, 255, 255, 0.1);
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
}
.landing-account-head {
  display: flex;
  align-items: center;
  gap: 10px;
}
.landing-account-role {
  padding: 3px 12px;
  font-size: 13px;
  font-weight: 600;
  border-radius: 999px;
  color: #fff;
  background: linear-gradient(135deg, var(--accent), color-mix(in srgb, var(--accent) 60%, #000));
}
.landing-account-user {
  font-size: 15px;
  font-weight: 700;
  color: #f1f5f9;
}
.landing-account-desc {
  margin: 12px 0 14px;
  font-size: 13px;
  color: #94a3b8;
}
.landing-account-pwd {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 12px;
  border-radius: 10px;
  background: rgba(255, 255, 255, 0.04);
  border: 1px solid rgba(255, 255, 255, 0.09);
}
.landing-account-pwd-label {
  font-size: 12px;
  color: #64748b;
  white-space: nowrap;
}
.landing-account-pwd-value {
  flex: 1;
  font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', Menlo, monospace;
  font-size: 14px;
  color: #c7d2fe;
  cursor: pointer;
  letter-spacing: 0.5px;
  transition: filter 0.2s ease;
  user-select: none;
}
.landing-account-pwd-value--blurred {
  filter: blur(5px);
  color: #e2e8f0;
}
.landing-account-pwd-value--blurred:hover {
  filter: blur(2px);
}
.landing-account-copy {
  padding: 4px 10px;
  font-size: 12px;
  border-radius: 7px;
  border: 1px solid rgba(255, 255, 255, 0.16);
  background: rgba(255, 255, 255, 0.07);
  color: #cbd5e1;
  cursor: pointer;
  transition: background 0.2s ease, color 0.2s ease;
}
.landing-account-copy:hover {
  background: rgba(255, 255, 255, 0.14);
  color: #fff;
}
.landing-account-tip {
  margin: 10px 0 0;
  font-size: 12px;
  color: #64748b;
}
.landing-account-note {
  margin-top: 22px;
  text-align: center;
  font-size: 13px;
  color: #94a3b8;
}
.landing-account-note code {
  padding: 2px 6px;
  border-radius: 5px;
  font-size: 12px;
  background: rgba(255, 255, 255, 0.08);
  color: #a5b4fc;
}

/* ---------- 关于我 ---------- */
.landing-about {
  margin-top: 40px;
}
.landing-about-card {
  max-width: 1120px;
  margin: 0 auto;
  padding: 30px;
  border-radius: 18px;
  background: rgba(255, 255, 255, 0.05);
  border: 1px solid rgba(255, 255, 255, 0.1);
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
}
.landing-about-head {
  display: flex;
  align-items: center;
  gap: 24px;
}
.landing-about-groups {
  margin-top: 26px;
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.landing-about-avatar {
  flex-shrink: 0;
  width: 76px;
  height: 76px;
  border-radius: 22px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 34px;
  font-weight: 800;
  color: #fff;
  background: linear-gradient(135deg, #6366f1, #a855f7);
  box-shadow: 0 8px 28px rgba(129, 140, 248, 0.4);
}
.landing-about-name {
  margin: 0;
  font-size: 24px;
  font-weight: 800;
  color: #f8fafc;
}
.landing-about-roles {
  margin: 6px 0 0;
  font-size: 14px;
  color: #a5b4fc;
}
.landing-about-edu {
  margin: 6px 0 0;
  font-size: 14px;
  color: #94a3b8;
}
.landing-about-links {
  margin-top: 14px;
  display: flex;
  align-items: center;
  gap: 20px;
  flex-wrap: wrap;
}
.landing-about-link {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  font-size: 14px;
  color: #a5b4fc;
  text-decoration: none;
  transition: color 0.2s ease;
}
.landing-about-link:hover {
  color: #c7d2fe;
}
.landing-about-qq {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  font-size: 14px;
  color: #94a3b8;
}
.landing-about-github {
  width: 18px;
  height: 18px;
}

/* ---------- 页脚 ---------- */
.landing-footer {
  margin-top: 60px;
  padding: 26px 20px 34px;
  border-top: 1px solid rgba(255, 255, 255, 0.08);
}
.landing-footer-inner {
  max-width: 1120px;
  margin: 0 auto;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
  flex-wrap: wrap;
  font-size: 13px;
  color: #64748b;
}
.landing-footer-inner a {
  color: #94a3b8;
  text-decoration: none;
  transition: color 0.2s ease;
}
.landing-footer-inner a:hover {
  color: #a5b4fc;
}
.landing-footer-dot {
  color: #334155;
}

/* ---------- 滚动浮现 ---------- */
/* 内容默认可见：浮现动画是“增强”而非“开关”，JS/IO 异常时也不会把内容永久隐藏 */
.reveal {
  opacity: 1;
}
.landing-revealed {
  animation: landing-fade-up 0.7s ease both;
}
@keyframes landing-fade-up {
  from {
    opacity: 0;
    transform: translateY(26px);
  }
  to {
    opacity: 1;
    transform: none;
  }
}

/* ---------- 响应式 ---------- */
@media (max-width: 820px) {
  .landing-nav-links {
    display: none;
  }
  .landing-nav-actions .landing-btn--ghost {
    display: none;
  }
  .landing-about-head {
    flex-direction: column;
    text-align: center;
  }
  .landing-tech-group {
    flex-direction: column;
    gap: 10px;
  }
  .landing-tech-group-head {
    width: auto;
  }
}

@media (prefers-reduced-motion: reduce) {
  .landing-logo-wrap,
  .landing-scroll {
    animation: none;
  }
  .landing-revealed {
    animation: none;
  }
  .landing-card {
    transition: none;
  }
}
</style>
