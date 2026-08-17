<template>
  <div class="ph">
    <Starfield :density="120" accent-color="#5eead4" />

    <!-- 顶栏 -->
    <nav class="ph-nav">
      <div class="ph-nav-inner">
        <div class="ph-nav-brand">
          <img src="/brand/logo-2.png" alt="jrwdev" class="ph-nav-logo" />
          <span class="ph-nav-name">jrwdev.site</span>
        </div>
        <div class="ph-nav-links">
          <a href="#education" @click.prevent="scrollTo('education')">教育背景</a>
          <a href="#internship" @click.prevent="scrollTo('internship')">实习经历</a>
          <a href="#projects" @click.prevent="scrollTo('projects')">项目经历</a>
          <a href="#skills" @click.prevent="scrollTo('skills')">专业技能</a>
        </div>
        <a
          v-if="profile.resumeUrl"
          class="ph-btn ph-btn--primary"
          :href="profile.resumeUrl"
          target="_blank"
          rel="noopener noreferrer"
          >下载简历</a
        >
      </div>
    </nav>

    <main class="ph-main">
      <!-- Hero：基本信息 -->
      <header class="ph-hero reveal">
        <div class="ph-hero-card">
          <div class="ph-hero-left">
            <div class="ph-avatar">
              <img :src="profile.photo" alt="个人照片" loading="lazy" decoding="async" />
            </div>
          </div>
          <div class="ph-hero-right">
            <h1 class="ph-name">{{ profile.name }}</h1>
            <p class="ph-title">{{ profile.title }}</p>
            <div class="ph-meta">
              <span v-if="profile.email">✉ {{ profile.email }}</span>
              <span v-if="profile.phone">☏ {{ profile.phone }}</span>
              <a v-if="profile.github" :href="profile.github" target="_blank" rel="noopener noreferrer"
                >⌥ GitHub · JRW923</a
              >
            </div>
            <div class="ph-hero-actions">
              <a
                v-if="profile.resumeUrl"
                class="ph-btn ph-btn--primary"
                :href="profile.resumeUrl"
                target="_blank"
                rel="noopener noreferrer"
                >下载简历 PDF</a
              >
              <button class="ph-btn ph-btn--glass" @click="scrollTo('projects')">查看项目 ↓</button>
            </div>
          </div>
        </div>
      </header>

      <!-- 教育背景 -->
      <section id="education" class="ph-section reveal">
        <h2 class="ph-h2">教育背景</h2>
        <div class="ph-edu-list">
          <div v-for="(e, i) in profile.education" :key="i" class="ph-edu">
            <div class="ph-edu-head">
              <span class="ph-edu-school">{{ e.school }}</span>
              <span class="ph-edu-period">{{ e.period }}</span>
            </div>
            <p class="ph-edu-major">{{ e.major }} · {{ e.degree }}</p>
            <p v-if="e.highlight" class="ph-edu-highlight">{{ e.highlight }}</p>
          </div>
        </div>
      </section>

      <!-- 实习经历 -->
      <section v-if="profile.internships?.length" id="internship" class="ph-section reveal">
        <h2 class="ph-h2">实习经历</h2>
        <div v-for="(job, i) in profile.internships" :key="i" class="ph-job">
          <div class="ph-job-head">
            <span class="ph-job-company">{{ job.company }}<span class="ph-job-dept"> · {{ job.dept }}</span></span>
            <span class="ph-job-period">{{ job.period }}</span>
          </div>
          <p class="ph-job-role">{{ job.role }}</p>
          <p class="ph-job-bg">{{ job.background }}</p>
          <ul class="ph-bullets">
            <li v-for="(b, j) in job.bullets" :key="j">{{ b }}</li>
          </ul>
        </div>
      </section>

      <!-- 项目经历 -->
      <section id="projects" class="ph-section reveal">
        <h2 class="ph-h2">项目经历 <span class="ph-h2-sub">点击卡片进入在线体验</span></h2>
        <div class="ph-projects">
          <article
            v-for="p in profile.projects"
            :key="p.id"
            class="ph-project"
            :class="{ 'ph-project--offline': !p.online }"
          >
            <div class="ph-project-head">
              <h3 class="ph-project-name">{{ p.name }}</h3>
              <span class="ph-project-period">{{ p.period }}</span>
            </div>
            <p class="ph-project-tagline">{{ p.tagline }}</p>
            <p class="ph-project-desc">{{ p.desc }}</p>
            <ul class="ph-bullets ph-bullets--compact">
              <li v-for="(b, i) in p.bullets" :key="i">{{ b }}</li>
            </ul>
            <div class="ph-stack">
              <span v-for="s in p.stack" :key="s" class="ph-stack-item">{{ s }}</span>
            </div>
            <div class="ph-project-links">
              <template v-for="l in p.links" :key="l.label + l.url">
                <router-link v-if="l.url.startsWith('/') && p.online" class="ph-btn ph-btn--primary ph-btn--sm" :to="l.url">
                  {{ l.label }} →
                </router-link>
                <a v-else-if="l.url.startsWith('/') && !p.online" class="ph-btn ph-btn--sm ph-btn--disabled" aria-disabled="true">
                  {{ l.label }}（部署中）
                </a>
                <a v-else class="ph-btn ph-btn--ghost ph-btn--sm" :href="l.url" target="_blank" rel="noopener noreferrer">
                  {{ l.label }}
                </a>
              </template>
            </div>
          </article>
        </div>
      </section>

      <!-- 专业技能 -->
      <section id="skills" class="ph-section reveal">
        <h2 class="ph-h2">专业技能</h2>
        <div class="ph-skills">
          <div v-for="(g, i) in profile.skills" :key="i" class="ph-skill">
            <h3 class="ph-skill-title">{{ g.title }}</h3>
            <ul class="ph-bullets">
              <li v-for="(s, j) in g.items" :key="j">{{ s }}</li>
            </ul>
          </div>
        </div>
      </section>

      <!-- 获奖荣誉 -->
      <section v-if="profile.awards?.length" class="ph-section reveal">
        <h2 class="ph-h2">获奖荣誉</h2>
        <div class="ph-awards">
          <span v-for="(a, i) in profile.awards" :key="i" class="ph-award">{{ a }}</span>
        </div>
      </section>
    </main>

    <footer class="ph-footer">
      <span>© 2026 {{ profile.name }} · {{ profile.site }}</span>
      <span v-if="profile.qq" class="ph-footer-qq">QQ · {{ profile.qq }}</span>
    </footer>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import Starfield from '@/components/landing/Starfield.vue'
import { useStandalonePage } from '@/composables/useStandalonePage'
import { fetchProfile } from '@/api/siteProfile'
import { defaultProfile, type ProfileContent } from '@/config/profileDefaults'

useStandalonePage()

const profile = ref<ProfileContent>({ ...defaultProfile })

onMounted(async () => {
  profile.value = await fetchProfile()
})

function scrollTo(id: string) {
  document.getElementById(id)?.scrollIntoView({ behavior: 'smooth', block: 'start' })
}
</script>

<style>
body.eg-landing {
  background: #06120f;
  color: #e2e8f0;
}
</style>

<style scoped>
.ph {
  position: relative;
  z-index: 1;
  min-height: 100vh;
  background: #06120f;
  overflow-x: hidden;
  overflow-x: clip;
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', 'PingFang SC', 'Microsoft YaHei', sans-serif;
}

/* ---------- 顶栏 ---------- */
.ph-nav {
  position: fixed;
  inset: 0 0 auto 0;
  z-index: 20;
  backdrop-filter: blur(14px);
  -webkit-backdrop-filter: blur(14px);
  background: rgba(6, 18, 15, 0.66);
  border-bottom: 1px solid rgba(255, 255, 255, 0.08);
}
.ph-nav-inner {
  max-width: 1060px;
  margin: 0 auto;
  padding: 12px 20px;
  display: flex;
  align-items: center;
  gap: 16px;
}
.ph-nav-brand {
  display: flex;
  align-items: center;
  gap: 8px;
  font-weight: 700;
  font-size: 15px;
  color: #f1f5f9;
  white-space: nowrap;
}
.ph-nav-logo {
  width: 26px;
  height: 26px;
  border-radius: 6px;
}
.ph-nav-links {
  flex: 1;
  display: flex;
  gap: 22px;
  justify-content: center;
}
.ph-nav-links a {
  color: #cbd5e1;
  text-decoration: none;
  font-size: 14px;
  transition: color 0.2s ease;
}
.ph-nav-links a:hover {
  color: #5eead4;
}

/* ---------- 按钮 ---------- */
.ph-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  border: none;
  cursor: pointer;
  font-size: 14px;
  font-family: inherit;
  padding: 9px 20px;
  border-radius: 8px;
  text-decoration: none;
  transition: transform 0.18s ease, box-shadow 0.18s ease, background 0.2s ease;
  user-select: none;
}
.ph-btn--primary {
  color: #04211c;
  background: linear-gradient(135deg, #2dd4bf, #34d399);
  box-shadow: 0 4px 22px rgba(45, 212, 191, 0.3);
}
.ph-btn--primary:hover {
  transform: translateY(-2px);
  box-shadow: 0 8px 30px rgba(45, 212, 191, 0.42);
}
.ph-btn--glass {
  color: #e2e8f0;
  background: rgba(255, 255, 255, 0.08);
  border: 1px solid rgba(255, 255, 255, 0.2);
}
.ph-btn--glass:hover {
  background: rgba(255, 255, 255, 0.14);
}
.ph-btn--ghost {
  color: #cbd5e1;
  background: rgba(255, 255, 255, 0.06);
  border: 1px solid rgba(255, 255, 255, 0.14);
}
.ph-btn--ghost:hover {
  color: #fff;
  border-color: rgba(255, 255, 255, 0.3);
}
.ph-btn--sm {
  font-size: 13px;
  padding: 7px 14px;
}
.ph-btn--disabled {
  color: #64748b;
  background: rgba(255, 255, 255, 0.04);
  border: 1px dashed rgba(255, 255, 255, 0.16);
  cursor: not-allowed;
}

/* ---------- 主体 ---------- */
.ph-main {
  max-width: 1060px;
  margin: 0 auto;
  padding: 0 20px 40px;
}
.ph-section {
  margin-top: 64px;
  scroll-margin-top: 80px;
}
.ph-h2 {
  margin: 0 0 22px;
  font-size: 24px;
  font-weight: 800;
  color: #f1f5f9;
}
.ph-h2::after {
  content: '';
  display: block;
  width: 36px;
  height: 2px;
  margin-top: 10px;
  background: #2dd4bf;
}
.ph-h2-sub {
  font-size: 13px;
  font-weight: 400;
  color: #64748b;
  margin-left: 10px;
}

/* ---------- Hero ---------- */
.ph-hero {
  padding-top: 120px;
}
.ph-hero-card {
  display: flex;
  gap: 30px;
  padding: 34px;
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.045);
  border: 1px solid rgba(255, 255, 255, 0.1);
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
}
.ph-avatar {
  width: 120px;
  height: 150px;
  flex-shrink: 0;
  padding: 3px;
  border-radius: 10px;
  background: rgba(255, 255, 255, 0.08);
  box-shadow: 0 8px 28px rgba(0, 30, 25, 0.4);
}
.ph-avatar img {
  display: block;
  width: 100%;
  height: 100%;
  border-radius: 8px;
  object-fit: cover;
  object-position: center 25%;
}
.ph-name {
  margin: 4px 0 0;
  font-size: 40px;
  font-weight: 800;
  color: #f8fafc;
}
.ph-title {
  margin: 10px 0 0;
  font-size: 16px;
  color: #5eead4;
  font-weight: 600;
}
.ph-meta {
  margin-top: 16px;
  display: flex;
  flex-wrap: wrap;
  gap: 14px;
  font-size: 13px;
  color: #94a3b8;
}
.ph-meta a {
  color: #94a3b8;
  text-decoration: none;
}
.ph-meta a:hover {
  color: #5eead4;
}
.ph-hero-actions {
  margin-top: 22px;
  display: flex;
  gap: 12px;
  flex-wrap: wrap;
}

/* ---------- 教育背景 ---------- */
.ph-edu-list {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(320px, 1fr));
  gap: 16px;
}
.ph-edu {
  padding: 22px;
  border-radius: 10px;
  background: rgba(255, 255, 255, 0.045);
  border: 1px solid rgba(255, 255, 255, 0.1);
  border-left: 3px solid #2dd4bf;
}
.ph-edu-head {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  gap: 10px;
  flex-wrap: wrap;
}
.ph-edu-school {
  font-size: 17px;
  font-weight: 700;
  color: #f1f5f9;
}
.ph-edu-period {
  font-size: 13px;
  color: #64748b;
}
.ph-edu-major {
  margin: 8px 0 0;
  font-size: 14px;
  color: #cbd5e1;
}
.ph-edu-highlight {
  margin: 10px 0 0;
  font-size: 13px;
  line-height: 1.7;
  color: #94a3b8;
}

/* ---------- 实习/项目 bullets ---------- */
.ph-bullets {
  margin: 12px 0 0;
  padding-left: 18px;
  display: flex;
  flex-direction: column;
  gap: 8px;
  font-size: 13.5px;
  line-height: 1.75;
  color: #9fb3b0;
}
.ph-bullets li::marker {
  color: #2dd4bf;
}
.ph-bullets--compact li {
  font-size: 13px;
  line-height: 1.65;
}

/* ---------- 实习经历 ---------- */
.ph-job {
  padding: 24px;
  border-radius: 10px;
  background: rgba(255, 255, 255, 0.045);
  border: 1px solid rgba(255, 255, 255, 0.1);
}
.ph-job + .ph-job {
  margin-top: 16px;
}
.ph-job-head {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  gap: 10px;
  flex-wrap: wrap;
}
.ph-job-company {
  font-size: 17px;
  font-weight: 700;
  color: #f1f5f9;
}
.ph-job-dept {
  font-size: 14px;
  font-weight: 400;
  color: #94a3b8;
}
.ph-job-period {
  font-size: 13px;
  color: #64748b;
}
.ph-job-role {
  margin: 8px 0 0;
  font-size: 14px;
  font-weight: 600;
  color: #5eead4;
}
.ph-job-bg {
  margin: 10px 0 0;
  font-size: 13.5px;
  line-height: 1.7;
  color: #94a3b8;
}

/* ---------- 项目经历 ---------- */
.ph-projects {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(420px, 1fr));
  gap: 18px;
}
.ph-project {
  padding: 26px;
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.045);
  border: 1px solid rgba(255, 255, 255, 0.1);
  border-top: 2px solid #2dd4bf;
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
  display: flex;
  flex-direction: column;
  transition: transform 0.2s ease, box-shadow 0.2s ease, border-color 0.2s ease;
}
.ph-project:hover {
  transform: translateY(-4px);
  box-shadow: 0 16px 44px -12px rgba(45, 212, 191, 0.28);
}
.ph-project--offline {
  border-top-color: #64748b;
}
.ph-project-head {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  gap: 10px;
  flex-wrap: wrap;
}
.ph-project-name {
  margin: 0;
  font-size: 22px;
  font-weight: 800;
  color: #f8fafc;
}
.ph-project-period {
  font-size: 12.5px;
  color: #64748b;
}
.ph-project-tagline {
  margin: 8px 0 0;
  font-size: 14px;
  font-weight: 600;
  color: #5eead4;
}
.ph-project-desc {
  margin: 10px 0 0;
  font-size: 13px;
  line-height: 1.7;
  color: #94a3b8;
}
.ph-stack {
  margin-top: 16px;
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.ph-stack-item {
  padding: 3px 10px;
  font-size: 12px;
  border-radius: 6px;
  color: #a9c9c4;
  background: rgba(255, 255, 255, 0.04);
  border: 1px solid rgba(255, 255, 255, 0.09);
}
.ph-project-links {
  margin-top: auto;
  padding-top: 18px;
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}

/* ---------- 专业技能 ---------- */
.ph-skills {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(320px, 1fr));
  gap: 16px;
}
.ph-skill {
  padding: 22px;
  border-radius: 10px;
  background: rgba(255, 255, 255, 0.045);
  border: 1px solid rgba(255, 255, 255, 0.1);
}
.ph-skill-title {
  margin: 0;
  font-size: 15px;
  font-weight: 700;
  color: #c7f0e8;
}
.ph-skill .ph-bullets {
  margin-top: 12px;
}

/* ---------- 获奖 ---------- */
.ph-awards {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}
.ph-award {
  padding: 8px 16px;
  border-radius: 8px;
  font-size: 13px;
  color: #d9e8e4;
  background: rgba(45, 212, 191, 0.08);
  border: 1px solid rgba(45, 212, 191, 0.22);
}

/* ---------- 页脚 ---------- */
.ph-footer {
  margin-top: 70px;
  padding: 26px 20px 34px;
  border-top: 1px solid rgba(255, 255, 255, 0.08);
  display: flex;
  justify-content: center;
  gap: 16px;
  flex-wrap: wrap;
  font-size: 13px;
  color: #64748b;
}

.reveal {
  opacity: 1;
}
.landing-revealed {
  animation: ph-fade-up 0.7s ease both;
}
@keyframes ph-fade-up {
  from {
    opacity: 0;
    transform: translateY(24px);
  }
  to {
    opacity: 1;
    transform: none;
  }
}

/* ---------- 响应式 ---------- */
@media (max-width: 820px) {
  .ph-nav-links {
    display: none;
  }
  .ph-hero-card {
    flex-direction: column;
    align-items: center;
    text-align: center;
  }
  .ph-meta,
  .ph-hero-actions {
    justify-content: center;
  }
  .ph-projects {
    grid-template-columns: 1fr;
  }
}

@media (prefers-reduced-motion: reduce) {
  .landing-revealed {
    animation: none;
  }
  .ph-project {
    transition: none;
  }
}
</style>
