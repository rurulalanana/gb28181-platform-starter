import { computed } from 'vue'

/**
 * 播放状态 → Element Plus 标签类型的统一映射。
 *
 * 4 个视图各自渲染状态标签，早先各写一份 stateType，同一状态在不同页面配色不同
 * （「连接中断」在通道页是红的、在设备页落到灰）。规则集中在这里：
 * 「播放中」success；含失败/异常/中断/超时的 danger；其余 info。
 * 「连接超时，重试中…」算失败（本次连接已断，只是播放器在自动重连），标灰会让人以为正常。
 *
 * @param {import('vue').Ref<string>|import('vue').ComputedRef<string>} state 状态文案
 * @returns {import('vue').ComputedRef<'success'|'danger'|'info'>}
 */
export function usePlayerStateType(state) {
  return computed(() => {
    const text = state?.value || ''
    if (text === '播放中') return 'success'
    if (text.includes('失败') || text.includes('异常')) return 'danger'
    if (text.includes('中断') || text.includes('超时')) return 'danger'
    return 'info'
  })
}
