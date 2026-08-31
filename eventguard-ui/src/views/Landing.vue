<template>
  <div class="landing">
    <Starfield :density="150" accent-color="#4fb8aa" />

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
        </div>
        <div class="landing-nav-actions">
          <router-link class="landing-btn landing-btn--ghost" to="/guide">体验指南</router-link>
          <button class="landing-btn landing-btn--primary" @click="goEnter">
            {{ auth.isAuthenticated ? '返回控制台' : '进入系统' }}
          </button>
        </div>
      </div>
    </nav>

    <!-- Hero -->
    <header class="landing-hero">
      <div class="landing-hero-inner reveal">
        <div class="landing-eyebrow"><span class="landing-eyebrow-dot" /> EVENT-DRIVEN OPERATIONS</div>
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
          <button class="landing-btn landing-btn--primary landing-btn--lg" @click="goEnter">
            {{ auth.isAuthenticated ? '返回控制台' : '进入系统' }}
          </button>
          <router-link class="landing-btn landing-btn--glass landing-btn--lg" to="/guide">查看体验指南</router-link>
        </div>
        <p class="landing-hint">公开演示环境 · 使用下方「体验账号」即可登录体验</p>
        <div class="landing-proof" aria-label="系统能力摘要">
          <div><strong>Event Sourcing</strong><span>业务状态全程可追溯</span></div>
          <div><strong>AI Detection</strong><span>规则与模型协同检测</span></div>
          <div><strong>Saga Recovery</strong><span>异常处置形成闭环</span></div>
        </div>
      </div>
      <button class="landing-scroll" aria-label="向下浏览" @click="scrollToSection('features')">
        <span class="landing-scroll-chevron"></span>
      </button>
    </header>

    <!-- 核心能力 -->
    <section id="features" class="landing-section">
      <div class="landing-section-heading reveal">
        <span class="landing-section-kicker">01 / CAPABILITIES</span>
        <h2 class="landing-h2">核心能力</h2>
        <p class="landing-sec-sub">从事件写入到异常处置，覆盖完整的订单运营链路。</p>
      </div>
      <div class="landing-cards">
        <div
          v-for="(f, index) in features"
          :key="f.title"
          class="landing-card reveal"
          :style="{ '--accent': f.accent }"
          @pointermove="onCardPointerMove"
          @pointerleave="onCardPointerLeave"
        >
          <div class="landing-card-index">{{ String(index + 1).padStart(2, '0') }}</div>
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
      <div class="landing-section-heading reveal">
        <span class="landing-section-kicker">02 / ENGINEERING</span>
        <h2 class="landing-h2">项目技术栈</h2>
        <p class="landing-sec-sub">围绕可靠交付、可观测性与 AI 工程组织技术选型。</p>
      </div>
      <div class="landing-projtech-chips reveal">
        <div v-for="group in projectStackGroups" :key="group.title" class="landing-stack-group" :style="{ '--accent': group.accent }">
          <div class="landing-stack-group-head">
            <span class="landing-stack-group-index">{{ group.index }}</span>
            <div>
              <h3>{{ group.title }}</h3>
              <p>{{ group.description }}</p>
            </div>
          </div>
          <div class="landing-stack-items">
            <span class="landing-tech-item" v-for="item in group.items" :key="item">{{ item }}</span>
          </div>
        </div>
      </div>
    </section>

    <!-- 体验账号 -->
    <section id="accounts" class="landing-section">
      <div class="landing-section-heading reveal">
        <span class="landing-section-kicker">03 / DEMO ACCESS</span>
        <h2 class="landing-h2">体验账号</h2>
        <p class="landing-sec-sub">三种权限视角对应管理员、运营和只读访客的真实工作流。</p>
      </div>
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
import { ElMessage } from 'element-plus/es/components/message/index.mjs'
import Starfield from '@/components/landing/Starfield.vue'
import { useStandalonePage } from '@/composables/useStandalonePage'
import { auth } from '@/stores/auth'

useStandalonePage()

const router = useRouter()

const scrolled = ref(false)

const heroChips = ['事件溯源 + 回放', 'CDC 实时管道', 'AI 异常检测', '中文 NL 查询', 'Saga 自动补偿']

interface Feature {
  title: string
  desc: string
  tags: string[]
  accent: string
}
const features: Feature[] = [
  {
    title: '事件溯源 + 回放',
    desc: '订单每一步状态变更都作为不可变事件落库，可随时回放任意历史时刻，审计与排障更清晰。',
    tags: ['Event Sourcing', 'CQRS', '乐观锁'],
    accent: '#5b8def',
  },
  {
    title: 'CDC 实时管道',
    desc: 'Debezium 捕获事件库变更，经 Kafka 实时流入检测侧，订单提交到异常发现端到端近实时可见。',
    tags: ['Debezium', 'Kafka', 'CDC'],
    accent: '#45b8a3',
  },
  {
    title: 'AI 异常检测',
    desc: '规则引擎 + 无监督模型双通道识别异常订单，命中规则 / 模型与异常类型清晰标注。',
    tags: ['规则引擎', 'IsolationForest', 'HMM 序列检测'],
    accent: '#e17865',
  },
  {
    title: '中文 NL 查询',
    desc: '用中文直接问订单、查统计、追轨迹，意图识别后安全调用后端接口，不暴露原始 SQL。',
    tags: ['NL2API', '意图分类'],
    accent: '#5fb7ce',
  },
  {
    title: 'Saga 自动补偿',
    desc: '失败类事件自动触发退款 / 标记缺货 / 通知用户闭环，高风险动作挂人工审批，重启也不丢在途补偿。',
    tags: ['Saga', '补偿', '审批流'],
    accent: '#d8a445',
  },
  {
    title: '根因分析 + RBAC',
    desc: 'LLM 产出异常根因与白名单建议动作；JWT + 用户-角色-权限覆盖 REST / WebSocket / AI 全链路鉴权。',
    tags: ['LLM', 'JWT', 'RBAC'],
    accent: '#63b87b',
  },
]

// 项目实际技术栈，按系统模块组织，方便快速理解架构职责。
const projectStackGroups = [
  { index: '01', title: 'Java 服务端', description: '命令处理、权限控制与补偿编排', items: ['Java 17', 'Spring Boot 3', 'Spring JDBC', 'JWT / RBAC'], accent: '#5b8def' },
  { index: '02', title: '事件与数据', description: '事件落库、变更捕获与异步投影', items: ['PostgreSQL', 'Debezium', 'Kafka', 'Event Sourcing', 'CQRS'], accent: '#45b8a3' },
  { index: '03', title: 'AI 服务', description: '异常识别、自然语言查询与根因分析', items: ['Python', 'FastAPI', 'LLM API', 'Rule Engine', 'WebSocket'], accent: '#e17865' },
  { index: '04', title: '前端体验', description: '管理控制台与实时交互界面', items: ['Vue 3', 'TypeScript', 'Element Plus', 'ECharts', 'Vite'], accent: '#5fb7ce' },
  { index: '05', title: '交付与观测', description: '容器化部署、指标监控与日志聚合', items: ['Docker Compose', 'Nginx', 'Prometheus', 'Grafana', 'Loki'], accent: '#d8a445' },
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
  { username: 'admin', role: '管理员', desc: '用户 / 角色 / 审计全权限', password: 'admin123456', revealed: false, copied: false, accent: '#5b8def' },
  { username: 'operator', role: '运营', desc: '下单 · 状态 · 异常 · 补偿', password: 'operator123456', revealed: false, copied: false, accent: '#45b8a3' },
  { username: 'viewer', role: '只读', desc: '订单 / 看板 / NL 查询', password: 'viewer123456', revealed: false, copied: false, accent: '#d8a445' },
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
  } else {
    // 剪贴板 API 与 execCommand 兜底都失败时（非安全上下文/无权限）必须告知，
    // 否则点「复制」毫无反应，用户会以为已经复制上了
    ElMessage.warning('复制失败，请手动选中复制')
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
  background: #071319;
  color: #e2e8f0;
}
</style>

<style scoped>
.landing {
  position: relative;
  z-index: 1;
  min-height: 100vh;
  /* 根元素自带暗底：即使 body.eg-landing 尚未挂载，首帧也是深空背景而非灰底 */
  background: #071319;
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
  background: rgba(7, 19, 25, 0.62);
  border-bottom: 1px solid rgba(255, 255, 255, 0.08);
  transition: background 0.3s ease, border-color 0.3s ease;
}
.landing-nav--scrolled {
  background: rgba(7, 19, 25, 0.9);
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
  letter-spacing: 0;
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
  border-radius: 8px;
  text-decoration: none;
  transition: transform 0.18s ease, box-shadow 0.18s ease, background 0.2s ease;
  user-select: none;
}
.landing-btn:active {
  transform: translateY(0) scale(0.97);
}
.landing-btn--primary {
  color: #fff;
  background: #3f63d8;
  box-shadow: 0 4px 22px rgba(63, 99, 216, 0.34);
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
  background: #4b70e3;
  box-shadow: 0 8px 30px rgba(63, 99, 216, 0.42);
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
  border-radius: 8px;
}

/* ---------- Hero ---------- */
.landing-hero {
  min-height: 86svh;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  text-align: center;
  padding: 108px 20px 74px;
  position: relative;
}
.landing-hero-inner { width: min(820px, 100%); }
.landing-eyebrow {
  display: inline-flex;
  align-items: center;
  gap: 9px;
  margin-bottom: 24px;
  color: #85bdb4;
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0;
}
.landing-eyebrow-dot { width: 7px; height: 7px; border-radius: 50%; background: #42c2a8; box-shadow: 0 0 0 5px rgba(66, 194, 168, .12); }
.landing-logo-wrap {
  display: block;
  width: max-content;
  margin: 0 auto;
  animation: landing-float 5s ease-in-out infinite;
  filter: drop-shadow(0 8px 26px rgba(69, 126, 211, 0.36));
}
.landing-logo {
  display: block;
  width: 120px;
  height: 120px;
  border-radius: 24px;
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
  font-size: 56px;
  font-weight: 800;
  color: #f4f8f8;
  letter-spacing: 0;
  text-shadow: 0 0 40px rgba(69, 126, 211, 0.24);
}
.landing-title-cn {
  color: #62c6b4;
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
  padding: 6px 12px;
  border-radius: 6px;
  font-size: 13px;
  color: #b8cfce;
  background: rgba(80, 137, 148, 0.1);
  border: 1px solid rgba(105, 163, 171, 0.24);
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
.landing-proof {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 0;
  width: min(720px, 100%);
  margin: 34px auto 0;
  padding-top: 22px;
  border-top: 1px solid rgba(255,255,255,.1);
}
.landing-proof > div { display: flex; flex-direction: column; gap: 5px; padding: 0 18px; border-right: 1px solid rgba(255,255,255,.08); }
.landing-proof > div:last-child { border-right: 0; }
.landing-proof strong { color: #dce8e7; font-size: 13px; font-weight: 700; }
.landing-proof span { color: #728b91; font-size: 12px; }
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
  padding: 68px 20px 42px;
  scroll-margin-top: 70px;
}
.landing-section-heading { max-width: 650px; margin: 0 auto; text-align: center; }
.landing-section-kicker { display: block; margin-bottom: 10px; color: #5f9f96; font-size: 11px; font-weight: 750; letter-spacing: 0; }
.landing-h2 {
  margin: 0;
  text-align: center;
  font-size: 28px;
  font-weight: 800;
  color: #f1f5f9;
}
.landing-h2::after {
  content: '';
  display: block;
  width: 36px;
  height: 2px;
  margin: 13px auto 0;
  background: #45b29f;
}
.landing-sec-sub {
  margin: 14px auto 0;
  text-align: center;
  color: #7f949a;
  font-size: 14px;
  line-height: 1.7;
}

/* 项目技术栈：紧贴上一板块的窄区块 */
.landing-section--thin {
  padding-top: 42px;
}
.landing-projtech-chips {
  margin-top: 30px;
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14px;
  text-align: left;
}
.landing-stack-group {
  --accent: #5b8def;
  min-width: 0;
  padding: 20px;
  border: 1px solid rgba(255,255,255,.09);
  border-top: 2px solid var(--accent);
  border-radius: 8px;
  background: rgba(255,255,255,.035);
}
.landing-stack-group:last-child { grid-column: 1 / -1; }
.landing-stack-group-head { display: flex; align-items: flex-start; gap: 12px; }
.landing-stack-group-index { flex: 0 0 auto; color: var(--accent); font-size: 11px; font-weight: 800; line-height: 24px; }
.landing-stack-group h3 { margin: 0; color: #e6efee; font-size: 16px; }
.landing-stack-group p { margin: 5px 0 0; color: #71878d; font-size: 12px; line-height: 1.6; }
.landing-stack-items { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 16px; }
.landing-stack-items .landing-tech-item { padding: 5px 10px; font-size: 12px; }

/* ---------- 核心能力卡片 ---------- */
.landing-cards {
  margin-top: 40px;
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(300px, 1fr));
  gap: 16px;
}
.landing-card {
  --accent: #6366f1;
  min-height: 210px;
  padding: 24px;
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.035);
  border: 1px solid rgba(255, 255, 255, 0.09);
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
  transition: transform 0.2s ease, box-shadow 0.2s ease, border-color 0.2s ease;
  will-change: transform;
}
.landing-card:hover {
  border-color: color-mix(in srgb, var(--accent) 55%, transparent);
  box-shadow: 0 12px 40px -8px color-mix(in srgb, var(--accent) 40%, transparent);
}
.landing-card-index {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 34px;
  height: 26px;
  border-radius: 5px;
  color: var(--accent);
  background: color-mix(in srgb, var(--accent) 12%, transparent);
  font-size: 11px;
  font-weight: 800;
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
  color: #a9bec0;
  background: rgba(255,255,255,.04);
  border: 1px solid rgba(255,255,255,.09);
}

.landing-tech-item {
  padding: 6px 14px;
  font-size: 14px;
  border-radius: 6px;
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
  border-radius: 8px;
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
  border-radius: 5px;
  color: #fff;
  background: color-mix(in srgb, var(--accent) 72%, #10262a);
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
  border-radius: 6px;
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
  letter-spacing: 0;
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
  .landing-proof { grid-template-columns: 1fr; width: min(420px, 100%); }
  .landing-proof > div { padding: 12px 0; border-right: 0; border-bottom: 1px solid rgba(255,255,255,.07); }
  .landing-proof > div:last-child { border-bottom: 0; }
  .landing-hero { min-height: auto; padding-bottom: 84px; }
  .landing-scroll { display: none; }
}

@media (max-width: 520px) {
  .landing-nav-inner { padding: 10px 14px; }
  .landing-nav-actions { gap: 6px; }
  .landing-btn { padding: 8px 12px; }
  .landing-hero { padding: 92px 16px 64px; }
  .landing-logo { width: 92px; height: 92px; border-radius: 18px; }
  .landing-title { margin-top: 20px; font-size: 34px; line-height: 1.16; }
  .landing-title-cn { display: block; margin-top: 5px; }
  .landing-sub { font-size: 14px; line-height: 1.7; }
  .landing-hero-actions { gap: 10px; }
  .landing-btn--lg { width: 100%; max-width: 300px; }
  .landing-section { padding: 52px 16px 30px; }
  .landing-cards { grid-template-columns: 1fr; }
  .landing-projtech-chips { grid-template-columns: 1fr; }
  .landing-stack-group:last-child { grid-column: auto; }
  .landing-account-pwd { flex-wrap: wrap; }
  .landing-account-pwd-value { min-width: 130px; }
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
