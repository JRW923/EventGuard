<template>
  <div class="starfield" aria-hidden="true">
    <div class="starfield-nebula"></div>
    <canvas ref="canvasEl"></canvas>
  </div>
</template>

<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'

const props = withDefaults(
  defineProps<{
    /** 星尘数量 */
    density?: number
    /** 星星颜色 */
    starColor?: string
    /** 流星 / 涟漪 / 迸发粒子主色 */
    accentColor?: string
  }>(),
  { density: 170, starColor: '#ffffff', accentColor: '#a78bfa' },
)

const canvasEl = ref<HTMLCanvasElement | null>(null)

let ctx: CanvasRenderingContext2D | null = null
let raf = 0
let width = 0
let height = 0
let lastTime = 0

// 鼠标（视差与点击交互共用）
let mouseX = 0
let mouseY = 0

interface Star {
  x: number
  y: number
  r: number
  baseAlpha: number
  twinkleSpeed: number
  twinklePhase: number
  parallax: number
  hue?: number
}
let stars: Star[] = []

interface Meteor {
  x: number
  y: number
  vx: number
  vy: number
  life: number
  maxLife: number
}
let meteor: Meteor | null = null
let nextMeteorAt = 0

interface Ripple {
  x: number
  y: number
  r: number
  alpha: number
  speed: number
}
interface Burst {
  x: number
  y: number
  vx: number
  vy: number
  life: number
  maxLife: number
  color: string
}
let ripples: Ripple[] = []
let bursts: Burst[] = []

let prefersReducedMotion = false
// jsdom / 旧环境无 matchMedia 时按正常动效处理
const motionQuery = window.matchMedia ? window.matchMedia('(prefers-reduced-motion: reduce)') : null

function seedStars() {
  const count = prefersReducedMotion ? Math.floor(props.density / 3) : props.density
  stars = []
  for (let i = 0; i < count; i++) {
    // 少数星星带暖/冷色调，增强星云层次
    const colorful = Math.random() < 0.16
    stars.push({
      x: Math.random() * width,
      y: Math.random() * height,
      r: Math.random() * 1.4 + 0.35,
      baseAlpha: Math.random() * 0.5 + 0.35,
      twinkleSpeed: Math.random() * 1.6 + 0.35,
      twinklePhase: Math.random() * Math.PI * 2,
      parallax: Math.random() * 0.55 + 0.1,
      hue: colorful ? 195 + Math.random() * 150 : undefined,
    })
  }
}

function resize() {
  width = window.innerWidth
  height = window.innerHeight
  const dpr = Math.min(window.devicePixelRatio || 1, 2)
  const canvas = canvasEl.value
  if (!canvas) return
  canvas.width = width * dpr
  canvas.height = height * dpr
  canvas.style.width = `${width}px`
  canvas.style.height = `${height}px`
  ctx = canvas.getContext('2d')
  if (ctx) ctx.setTransform(dpr, 0, 0, dpr, 0, 0)
  seedStars()
}

function spawnMeteor(now: number) {
  meteor = {
    x: Math.random() * width * 0.7 + width * 0.2,
    y: -Math.random() * height * 0.25,
    vx: 4 + Math.random() * 3,
    vy: 2 + Math.random() * 1.5,
    life: 0,
    maxLife: 70 + Math.random() * 45,
  }
  nextMeteorAt = now + 3800 + Math.random() * 5000
}

function onPointerMove(e: PointerEvent) {
  mouseX = e.clientX
  mouseY = e.clientY
}

/** 点击任意位置：涟漪 + 粒子迸发（核心交互） */
function onPointerDown(e: PointerEvent) {
  const x = e.clientX
  const y = e.clientY
  ripples.push({ x, y, r: 4, alpha: 0.85, speed: 3.2 })
  const n = prefersReducedMotion ? 6 : 22
  for (let i = 0; i < n; i++) {
    const angle = Math.random() * Math.PI * 2
    const speed = Math.random() * 2.8 + 0.8
    bursts.push({
      x,
      y,
      vx: Math.cos(angle) * speed,
      vy: Math.sin(angle) * speed,
      life: 0,
      maxLife: 42 + Math.random() * 26,
      color: Math.random() < 0.35 ? '#ffffff' : props.accentColor,
    })
  }
  // 防止无限累积
  if (ripples.length > 6) ripples.shift()
  if (bursts.length > 200) bursts.splice(0, bursts.length - 200)
}

function draw(now: number) {
  if (!ctx) return
  ctx.clearRect(0, 0, width, height)

  const elapsed = Math.min((now - lastTime) / 16.667, 3)
  lastTime = now
  const t = now / 1000

  // 星空 + 视差（reduced-motion 下静止）
  const dx = prefersReducedMotion ? 0 : (mouseX - width / 2) * 0.02
  const dy = prefersReducedMotion ? 0 : (mouseY - height / 2) * 0.02
  for (const s of stars) {
    const tw = prefersReducedMotion ? 1 : 0.6 + 0.4 * Math.sin(t * s.twinkleSpeed + s.twinklePhase)
    const px = s.x + dx * s.parallax
    const py = s.y + dy * s.parallax
    ctx.globalAlpha = s.baseAlpha * tw
    ctx.fillStyle = s.hue ? `hsl(${s.hue} 90% 80%)` : props.starColor
    ctx.beginPath()
    ctx.arc(px, py, s.r, 0, Math.PI * 2)
    ctx.fill()
  }
  ctx.globalAlpha = 1

  // 流星
  if (!prefersReducedMotion) {
    if (now > nextMeteorAt && !meteor) spawnMeteor(now)
    if (meteor) {
      meteor.x += meteor.vx
      meteor.y += meteor.vy
      meteor.life += elapsed
      const fade = 1 - meteor.life / meteor.maxLife
      if (fade <= 0 || meteor.x > width + 80 || meteor.y > height + 80) {
        meteor = null
      } else {
        ctx.globalAlpha = Math.max(fade, 0) * 0.85
        ctx.strokeStyle = props.accentColor
        ctx.lineWidth = 1.6
        ctx.lineCap = 'round'
        ctx.beginPath()
        ctx.moveTo(meteor.x, meteor.y)
        ctx.lineTo(meteor.x - meteor.vx * 9, meteor.y - meteor.vy * 9)
        ctx.stroke()
        ctx.globalAlpha = 1
      }
    }
  }

  // 点击涟漪
  for (let i = ripples.length - 1; i >= 0; i--) {
    const rp = ripples[i]
    rp.r += rp.speed * elapsed
    rp.alpha -= 0.014 * elapsed
    if (rp.alpha <= 0 || rp.r > Math.max(width, height) * 0.45) {
      ripples.splice(i, 1)
      continue
    }
    ctx.globalAlpha = Math.max(rp.alpha, 0)
    ctx.strokeStyle = props.accentColor
    ctx.lineWidth = 2
    ctx.beginPath()
    ctx.arc(rp.x, rp.y, rp.r, 0, Math.PI * 2)
    ctx.stroke()
    ctx.globalAlpha = 1
  }

  // 迸发粒子
  for (let i = bursts.length - 1; i >= 0; i--) {
    const b = bursts[i]
    b.x += b.vx * elapsed
    b.y += b.vy * elapsed
    b.vx *= 0.965
    b.vy *= 0.965
    b.life += elapsed
    const fade = 1 - b.life / b.maxLife
    if (fade <= 0) {
      bursts.splice(i, 1)
      continue
    }
    ctx.globalAlpha = Math.max(fade, 0)
    ctx.fillStyle = b.color
    ctx.beginPath()
    ctx.arc(b.x, b.y, 1.7, 0, Math.PI * 2)
    ctx.fill()
    ctx.globalAlpha = 1
  }

  raf = requestAnimationFrame(draw)
}

function tick(now: number) {
  draw(now)
}

function onMotionChange() {
  prefersReducedMotion = motionQuery?.matches ?? false
  seedStars()
  if (prefersReducedMotion) {
    meteor = null
  }
}

onMounted(() => {
  prefersReducedMotion = motionQuery?.matches ?? false
  resize()
  window.addEventListener('resize', resize)
  window.addEventListener('pointermove', onPointerMove, { passive: true })
  window.addEventListener('pointerdown', onPointerDown, { passive: true })
  motionQuery?.addEventListener?.('change', onMotionChange)
  lastTime = 0
  raf = requestAnimationFrame(tick)
})

onUnmounted(() => {
  cancelAnimationFrame(raf)
  window.removeEventListener('resize', resize)
  window.removeEventListener('pointermove', onPointerMove)
  window.removeEventListener('pointerdown', onPointerDown)
  motionQuery?.removeEventListener?.('change', onMotionChange)
  ctx = null
  stars = []
  ripples = []
  bursts = []
  meteor = null
})
</script>

<style scoped>
.starfield {
  position: fixed;
  inset: 0;
  z-index: 0;
  overflow: hidden;
  pointer-events: none; /* 交互走 window 监听，不挡内容点击 */
  background: #070b1a;
  box-shadow: inset 0 0 220px rgba(0, 0, 0, 0.55);
}
.starfield-nebula {
  position: absolute;
  inset: 0;
  background:
    radial-gradient(ellipse 62% 52% at 14% 18%, rgba(99, 102, 241, 0.18) 0%, transparent 62%),
    radial-gradient(ellipse 55% 46% at 86% 14%, rgba(168, 85, 247, 0.16) 0%, transparent 62%),
    radial-gradient(ellipse 72% 60% at 50% 104%, rgba(59, 130, 246, 0.14) 0%, transparent 66%),
    radial-gradient(ellipse 40% 34% at 78% 82%, rgba(236, 72, 153, 0.07) 0%, transparent 60%);
}
.starfield canvas {
  position: absolute;
  inset: 0;
  display: block;
}
</style>
