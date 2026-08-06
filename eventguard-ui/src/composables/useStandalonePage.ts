import { onMounted, onUnmounted } from 'vue'

/**
 * standalone 全屏页（欢迎页 / 体验指南）公共效果：
 * 1) 切换 body 暗背景类 `eg-landing`，避免滚动越界露出控制台亮色；
 * 2) 基于 IntersectionObserver 的滚动浮现动画（`.reveal` → `.landing-revealed`）。
 *
 * 可靠性设计：`.reveal` 初始 `opacity: 0`，若 IO 漏触发内容会永久隐藏。
 * 故在 IO 之外叠加三道兜底，确保任何场景（滚动位置恢复、嵌套滚动容器、IO 兼容性）下内容最终必现：
 *  - 滚动监听：凡进入视口的未浮现元素立即显示（捕获阶段，能覆盖任意元素上的滚动）；
 *  - 挂载后即时检查：覆盖「返回页面时浏览器把滚动位置恢复到中段」的错位；
 *  - 定时强制：最长约 1.6s 后仍未浮现的元素全部强制显示。
 */
export function useStandalonePage(revealSelector = '.reveal') {
  let observer: IntersectionObserver | null = null
  let safetyTimer: number | null = null

  const reveal = (el: Element) => {
    if (el.classList.contains('landing-revealed')) return
    el.classList.add('landing-revealed')
    observer?.unobserve(el)
  }

  /** 兜底：把当前在视口内但仍未浮现的元素全部显示 */
  const revealInViewport = () => {
    const els = document.querySelectorAll(`${revealSelector}:not(.landing-revealed)`)
    if (!els.length) return
    const vh = window.innerHeight || document.documentElement.clientHeight
    for (const el of els) {
      const r = (el as HTMLElement).getBoundingClientRect()
      if (r.top < vh * 0.96 && r.bottom > 0) reveal(el)
    }
  }

  onMounted(() => {
    document.body.classList.add('eg-landing')
    const els = Array.from(document.querySelectorAll(revealSelector))
    if (!('IntersectionObserver' in window)) {
      els.forEach(reveal)
      return
    }
    observer = new IntersectionObserver(
      (entries) => {
        for (const en of entries) {
          if (en.isIntersecting) reveal(en.target)
        }
      },
      { threshold: 0.08 },
    )
    els.forEach((el) => observer?.observe(el))

    // 兜底 1：滚动即检查（捕获阶段，覆盖任意滚动容器；scroll 不冒泡但捕获可命中后代滚动）
    window.addEventListener('scroll', revealInViewport, { capture: true, passive: true })
    // 兜底 2：挂载后下一帧检查一次（覆盖滚动位置恢复到中段的情形）
    if (typeof requestAnimationFrame === 'function') {
      requestAnimationFrame(revealInViewport)
    } else {
      revealInViewport()
    }
    // 兜底 3：定时强制——确保内容最迟可见，彻底消除「IO 失效导致永久隐藏」
    safetyTimer = window.setTimeout(() => {
      document.querySelectorAll(`${revealSelector}:not(.landing-revealed)`).forEach(reveal)
    }, 1600)
  })

  onUnmounted(() => {
    document.body.classList.remove('eg-landing')
    observer?.disconnect()
    observer = null
    window.removeEventListener('scroll', revealInViewport, { capture: true } as EventListenerOptions)
    if (safetyTimer !== null) {
      window.clearTimeout(safetyTimer)
      safetyTimer = null
    }
  })
}
