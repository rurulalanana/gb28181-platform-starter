import { ElMessageBox } from 'element-plus'

/**
 * 二次确认：「确定 / 取消」二选一，返回布尔。
 *
 * <h3>为什么需要这个包装</h3>
 * `ElMessageBox.confirm` 在用户点「取消」时是<b>抛异常</b>而不是返回 false，
 * 所以每个调用点都必须写成：
 * <pre>{@code
 * try {
 *   await ElMessageBox.confirm(msg, title, { type: 'warning', ... })
 * } catch (e) {
 *   return
 * }
 * }</pre>
 * 这段样板在 10 个页面里被抄了 14 遍。抄写本身不是问题，
 * <b>问题是它有三个必须同时具备的隐含要求</b>，漏一个就出错：
 * <ol>
 *   <li>`catch` 里只能 `return`，不能弹错误提示 —— 否则用户点「取消」
 *       会看到一条红色报错；</li>
 *   <li>`return` 必须真的终止后续操作 —— 少写一个就会「点了取消照样删」；</li>
 *   <li>`confirmButtonText` 要写明动作（删除/清空/重启），不能一律「确定」，
 *       否则用户在弹窗上看不出自己即将做什么。</li>
 * </ol>
 * 收敛成一个返回值之后，第 1、2 点由实现保证，调用方只剩第 3 点要做。
 *
 * @param {string} message 提示正文（支持 `\n` 换行）
 * @param {string} title   标题，写清动作对象
 * @param {object} [options] 透传给 ElMessageBox 的其它选项
 *        （最常用的是 `confirmButtonText`，应与实际动作一致）
 * @returns {Promise<boolean>} 用户点了确认返回 `true`，取消/关闭返回 `false`
 */
export async function confirmAction(message, title, options = {}) {
  try {
    await ElMessageBox.confirm(message, title, {
      type: 'warning',
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      ...options
    })
    return true
  } catch (e) {
    // 取消、点关闭、按 Esc 都走这里 —— 属于用户意图，不是错误
    return false
  }
}

export default confirmAction
