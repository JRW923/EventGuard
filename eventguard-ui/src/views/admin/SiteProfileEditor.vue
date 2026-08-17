<template>
  <div class="spe-page">
    <div class="spe-heading">
      <div>
        <div class="spe-eyebrow">SITE CONTENT</div>
        <h1>主页内容</h1>
        <p>编辑个人主页（jrwdev.site 首页）展示的内容，保存后立即生效，无需重新部署。</p>
      </div>
      <el-button tag="a" href="/" target="_blank">预览主页 ↗</el-button>
    </div>

    <el-alert
      title="保存即生效"
      description="内容存入数据库 site_profile 表，主页打开时实时读取；留空的条目板块会自动隐藏。手机号、邮箱等请自行斟酌公开范围。"
      type="info"
      :closable="false"
      show-icon
      class="spe-alert"
    />

    <el-card class="spe-card" v-loading="loading">
      <el-form label-position="top">
        <h3 class="spe-group-title">基本信息</h3>
        <div class="spe-grid spe-grid--two">
          <el-form-item label="姓名"><el-input v-model="form.name" /></el-form-item>
          <el-form-item label="求职定位"><el-input v-model="form.title" /></el-form-item>
          <el-form-item label="邮箱"><el-input v-model="form.email" /></el-form-item>
          <el-form-item label="手机号"><el-input v-model="form.phone" /></el-form-item>
          <el-form-item label="GitHub"><el-input v-model="form.github" /></el-form-item>
          <el-form-item label="QQ"><el-input v-model="form.qq" /></el-form-item>
          <el-form-item label="照片路径"><el-input v-model="form.photo" placeholder="/brand/profile-photo.jpeg" /></el-form-item>
          <el-form-item label="简历 PDF 路径"><el-input v-model="form.resumeUrl" placeholder="/resume.pdf（public 下静态文件）" /></el-form-item>
        </div>

        <h3 class="spe-group-title">教育背景</h3>
        <div v-for="(e, i) in form.education" :key="'edu' + i" class="spe-item">
          <div class="spe-grid spe-grid--two">
            <el-form-item label="学校"><el-input v-model="e.school" /></el-form-item>
            <el-form-item label="起止时间"><el-input v-model="e.period" /></el-form-item>
            <el-form-item label="专业"><el-input v-model="e.major" /></el-form-item>
            <el-form-item label="学位"><el-input v-model="e.degree" /></el-form-item>
          </div>
          <el-form-item label="亮点（排名/奖学金等）"><el-input v-model="e.highlight" /></el-form-item>
          <el-button text type="danger" @click="form.education.splice(i, 1)">删除此条</el-button>
        </div>
        <el-button text type="primary" @click="form.education.push({ school: '', major: '', degree: '', period: '', highlight: '' })">+ 添加教育经历</el-button>

        <h3 class="spe-group-title">实习经历</h3>
        <div v-for="(job, i) in form.internships" :key="'job' + i" class="spe-item">
          <div class="spe-grid spe-grid--two">
            <el-form-item label="公司"><el-input v-model="job.company" /></el-form-item>
            <el-form-item label="部门"><el-input v-model="job.dept" /></el-form-item>
            <el-form-item label="岗位"><el-input v-model="job.role" /></el-form-item>
            <el-form-item label="起止时间"><el-input v-model="job.period" /></el-form-item>
          </div>
          <el-form-item label="工作背景"><el-input v-model="job.background" type="textarea" :rows="2" /></el-form-item>
          <el-form-item label="职责亮点（每行一条）">
            <el-input v-model="job.bulletsText" type="textarea" :rows="4" />
          </el-form-item>
          <el-button text type="danger" @click="form.internships.splice(i, 1)">删除此条</el-button>
        </div>
        <el-button
          text
          type="primary"
          @click="form.internships.push({ company: '', dept: '', role: '', period: '', background: '', bulletsText: '' })"
          >+ 添加实习经历</el-button
        >

        <h3 class="spe-group-title">项目经历</h3>
        <div v-for="(p, i) in form.projects" :key="'proj' + i" class="spe-item">
          <div class="spe-grid spe-grid--two">
            <el-form-item label="项目名"><el-input v-model="p.name" /></el-form-item>
            <el-form-item label="一句话定位"><el-input v-model="p.tagline" /></el-form-item>
            <el-form-item label="起止时间"><el-input v-model="p.period" /></el-form-item>
            <el-form-item label="是否已上线体验">
              <el-switch v-model="p.online" active-text="已上线" inactive-text="部署中" />
            </el-form-item>
          </div>
          <el-form-item label="项目描述"><el-input v-model="p.desc" type="textarea" :rows="3" /></el-form-item>
          <el-form-item label="亮点（每行一条）">
            <el-input v-model="p.bulletsText" type="textarea" :rows="5" />
          </el-form-item>
          <el-form-item label="技术栈（逗号分隔）"><el-input v-model="p.stackText" /></el-form-item>
          <el-form-item label="链接（每行一个，格式：名称|URL）">
            <el-input v-model="p.linksText" type="textarea" :rows="3" placeholder="在线体验|/eventguard&#10;GitHub|https://github.com/JRW923/EventGuard" />
          </el-form-item>
          <el-button text type="danger" @click="form.projects.splice(i, 1)">删除此项目</el-button>
        </div>
        <el-button
          text
          type="primary"
          @click="
            form.projects.push({
              name: '',
              tagline: '',
              period: '',
              desc: '',
              bulletsText: '',
              stackText: '',
              linksText: '',
              online: false,
            })
          "
          >+ 添加项目</el-button
        >

        <h3 class="spe-group-title">专业技能</h3>
        <div v-for="(g, i) in form.skills" :key="'skill' + i" class="spe-item">
          <el-form-item label="分类名"><el-input v-model="g.title" /></el-form-item>
          <el-form-item label="条目（每行一条）">
            <el-input v-model="g.itemsText" type="textarea" :rows="3" />
          </el-form-item>
          <el-button text type="danger" @click="form.skills.splice(i, 1)">删除此分类</el-button>
        </div>
        <el-button text type="primary" @click="form.skills.push({ title: '', itemsText: '' })">+ 添加技能分类</el-button>

        <h3 class="spe-group-title">获奖荣誉</h3>
        <el-form-item label="奖项（每行一条）">
          <el-input v-model="awardsText" type="textarea" :rows="4" />
        </el-form-item>
      </el-form>

      <el-alert v-if="error" :title="error" type="error" :closable="false" class="spe-error" />
      <div class="spe-actions">
        <el-button type="primary" :loading="saving" data-testid="spe-save" @click="save">保存并生效</el-button>
        <el-button @click="load">放弃修改并重新加载</el-button>
      </div>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus/es/components/message/index.mjs'
import { fetchProfile, saveProfile } from '@/api/siteProfile'
import type { ProfileContent } from '@/config/profileDefaults'

// 编辑态：列表字段以纯文本（每行一条）编辑，保存时拆回数组
interface EditorState {
  name: string; title: string; phone: string; email: string; site: string; github: string; qq: string
  photo: string; resumeUrl: string
  education: { school: string; major: string; degree: string; period: string; highlight: string }[]
  internships: { company: string; dept: string; role: string; period: string; background: string; bulletsText: string }[]
  skills: { title: string; itemsText: string }[]
  projects: {
    name: string; tagline: string; period: string; desc: string; bulletsText: string
    stackText: string; linksText: string; online: boolean
  }[]
}

const loading = ref(false)
const saving = ref(false)
const error = ref('')
const awardsText = ref('')

const form = reactive<EditorState>({
  name: '', title: '', phone: '', email: '', site: '', github: '', qq: '', photo: '', resumeUrl: '',
  education: [], internships: [], skills: [], projects: [],
})

function toEditor(p: ProfileContent): EditorState {
  return {
    ...p,
    internships: p.internships.map((j) => ({ ...j, bulletsText: j.bullets.join('\n') })),
    skills: p.skills.map((g) => ({ title: g.title, itemsText: g.items.join('\n') })),
    projects: p.projects.map((pr) => ({
      name: pr.name, tagline: pr.tagline, period: pr.period, desc: pr.desc,
      bulletsText: pr.bullets.join('\n'),
      stackText: pr.stack.join(', '),
      linksText: pr.links.map((l) => `${l.label}|${l.url}`).join('\n'),
      online: !!pr.online,
    })),
  }
}

function fromEditor(): ProfileContent {
  return {
    name: form.name, title: form.title, phone: form.phone, email: form.email,
    site: form.site, github: form.github, qq: form.qq, photo: form.photo, resumeUrl: form.resumeUrl,
    education: form.education.map((e) => ({ ...e })),
    internships: form.internships.map((j) => ({
      company: j.company, dept: j.dept, role: j.role, period: j.period, background: j.background,
      bullets: j.bulletsText.split('\n').map((s) => s.trim()).filter(Boolean),
    })),
    skills: form.skills.map((g) => ({
      title: g.title,
      items: g.itemsText.split('\n').map((s) => s.trim()).filter(Boolean),
    })),
    awards: awardsText.value.split('\n').map((s) => s.trim()).filter(Boolean),
    projects: form.projects.map((p) => ({
      id: 'eventguard',
      name: p.name, tagline: p.tagline, period: p.period, desc: p.desc,
      bullets: p.bulletsText.split('\n').map((s) => s.trim()).filter(Boolean),
      stack: p.stackText.split(/[,，]/).map((s) => s.trim()).filter(Boolean),
      links: p.linksText
        .split('\n')
        .map((line) => line.trim())
        .filter(Boolean)
        .map((line) => {
          const idx = line.indexOf('|')
          return idx > 0 ? { label: line.slice(0, idx), url: line.slice(idx + 1) } : { label: line, url: '#' }
        }),
      online: p.online,
    })),
  }
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const p = await fetchProfile()
    Object.assign(form, toEditor(p))
    awardsText.value = p.awards.join('\n')
  } catch (e: any) {
    error.value = '加载失败：' + (e?.message ?? e)
  } finally {
    loading.value = false
  }
}

async function save() {
  saving.value = true
  error.value = ''
  try {
    await saveProfile(fromEditor())
    ElMessage.success('已保存，主页立即生效')
  } catch (e: any) {
    error.value = '保存失败：' + (e?.response?.data?.error ?? e?.message ?? e)
  } finally {
    saving.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.spe-page { max-width: 980px; margin: 0 auto; }
.spe-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; margin-bottom: 16px; }
.spe-eyebrow { font-size: 11px; letter-spacing: 2px; color: #7c9cb5; margin-bottom: 6px; }
.spe-heading h1 { margin: 0; font-size: 22px; }
.spe-heading p { margin: 6px 0 0; color: #8496a5; font-size: 13px; }
.spe-alert { margin-bottom: 16px; }
.spe-card { padding: 4px 6px; }
.spe-group-title { margin: 26px 0 12px; font-size: 15px; border-left: 3px solid #4f9e94; padding-left: 10px; }
.spe-group-title:first-of-type { margin-top: 8px; }
.spe-grid { display: grid; gap: 0 16px; }
.spe-grid--two { grid-template-columns: repeat(auto-fit, minmax(280px, 1fr)); }
.spe-item { padding: 14px; margin-bottom: 12px; border: 1px dashed rgba(128, 145, 158, 0.4); border-radius: 8px; }
.spe-error { margin-top: 12px; }
.spe-actions { margin-top: 20px; display: flex; gap: 10px; }
</style>
