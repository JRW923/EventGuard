import { onMounted, onUnmounted } from 'vue'

/**
 * standalone 全屏页（欢迎页 / 体验指南）公共效果：
 * 1) 切换 body 暗背景类 `eg-landing`，避免滚动越界露出控制台亮色；
 * 2) 基于 IntersectionObserver 的滚动浮现动画（`.reveal` → `.landing-revealed`）。
 */
export function useStandalonePage(revealSelector = '.reveal') {
  let observer: IntersectionObserver | null = null

  onMounted(() => {
    document.body.classList.add('eg-landing')
    const els = document.querySelectorAll(revealSelector)
    if (!('IntersectionObserver' in window)) {
      els.forEach((el) => el.classList.add('landing-revealed'))
      return
    }
    observer = new IntersectionObserver(
      (entries) => {
        for (const en of entries) {
          if (en.isIntersecting) {
            en.target.classList.add('landing-revealed')
            observer?.unobserve(en.target)
          }
        }
      },
      { threshold: 0.12 },
    )
    els.forEach((el) => observer?.observe(el))
  })

  onUnmounted(() => {
    document.body.classList.remove('eg-landing')
    observer?.disconnect()
    observer = null
  })
}
