import { computed } from 'vue'

/**
 * 播放状态 → Element Plus 标签类型的统一映射。
 *
 * ## 为什么必须共享
 *
 * 播放状态文案由 {@link useXgPlayer} 产生（`空闲` / `连接中…` / `播放中` /
 * `连接中断` / `播放异常：…` / `失败：…` 等），4 个视图各自渲染这个标签。
 * 早先每个视图各写一份 `stateType`，结果**同一状态在不同页面配色不同**：
 * 通道管理把「连接中断」标红，设备控制与设备录像却落到 `info`（灰），
 * 用户无法凭颜色判断问题严重程度。
 *
 * 因此判定规则集中在这里，视图只消费结果。
 *
 * ## 判定顺序
 *
 * 1. 明确成功 —— `播放中`
 * 2. 明确失败 —— 以 `失败` / `异常` 开头，或包含 `中断` / `超时`
 * 3. 其余为中间态（`空闲` / `加载播放器…` / `连接中…` / `协商中…` / `已停止`）
 *
 * 注意 `连接超时，重试中…` 属于**失败**而非中间态：它意味着本次连接已断，
 * 只是播放器正在自动重连；标成灰色会让用户以为一切正常。
 *
 * @param {import('vue').Ref<string>|import('vue').ComputedRef<string>} state 播放状态文案
 * @returns {import('vue').ComputedRef<'success'|'danger'|'info'>} Element Plus 标签类型
 */
export function usePlayerStateType(state) {
  return computed(() => {
    const text = state?.value || ''
    if (text === '播放中') return 'success'
    if (text.startsWith('失败') || text.startsWith('异常')) return 'danger'
    if (text.includes('中断') || text.includes('超时') || text.includes('异常')) return 'danger'
    return 'info'
  })
}
