import type { Directive, DirectiveBinding } from 'vue'
import { auth } from '@/stores/auth'

/**
 * 按钮级权限指令：v-permission="'order:create'" 或数组（任一满足）。
 * 无权限时移除 DOM 节点。仅做展示层控制，后端仍强制校验。
 */
export const permission: Directive<HTMLElement, string | string[]> = {
  mounted(el: HTMLElement, binding: DirectiveBinding<string | string[]>) {
    const required = Array.isArray(binding.value) ? binding.value : [binding.value]
    const has = required.every((p) => auth.hasPermission(p))
    if (!has) {
      el.parentNode?.removeChild(el)
    }
  },
}
