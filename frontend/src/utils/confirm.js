import { ElMessageBox } from 'element-plus'

/**
 * 二次确认：「确定 / 取消」返回布尔。
 *
 * ElMessageBox.confirm 点取消是抛异常而不是返回 false，原来的 try/catch 样板在 10 个页面抄了 14 遍，
 * 且必须同时满足三点，漏一个就出错：catch 里只能 return（否则取消弹出红色报错）、
 * return 必须真的终止后续（否则「点了取消照样删」）、confirmButtonText 要写明动作。
 * 收敛成返回值后前两点由实现保证，调用方只剩第三点。
 *
 * @param {string} message 提示正文（支持 \n 换行）
 * @param {string} title 标题，写清动作对象
 * @param {object} [options] 透传给 ElMessageBox，最常用 confirmButtonText
 * @returns {Promise<boolean>} 确认 true，取消/关闭 false
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
