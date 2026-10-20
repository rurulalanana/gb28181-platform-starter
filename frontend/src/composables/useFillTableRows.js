import { nextTick, onBeforeUnmount, onMounted, watch } from 'vue'

/**
 * 让表格的行高自适应可用高度。
 *
 * <p>背景：列表页用 {@code .page.is-fill} 把卡片拉满，但表格默认
 * {@code height: fit-content}，内容少时下方会空出一大块；把表格改成
 * {@code flex:1} 撑满后，空白又跑到了表格内部（表体底部空着）。
 * 两种都不好看 —— 用户看到的都是「没占满」。
 *
 * <p>做法：按「表体可用高度 ÷ 当前行数」算出行高写进 CSS 变量
 * {@code --row-h}，行就正好铺满表体。
 * <ul>
 *   <li>行数少（放得下）：行高被拉高，铺满整块表体</li>
 *   <li>行数多（放不下）：行高回落到最小值，表格内部滚动，分页条不动</li>
 * </ul>
 *
 * <p>行高不设上限：LiveGBS 的行高约 55px，与之一致；行数极少时（如 1 条）
 * 会拉得很高，此时靠 {@code maxRows} 兜底。
 *
 * @param {import('vue').Ref} tableRef  el-table 的组件引用
 * @param {import('vue').Ref|Function} rows 数据条数的来源
 * @param {object}  [options]
 * @param {number}  [options.min]    最小行高（px），低于此值改为表格内滚动
 * @param {number}  [options.max]    最大行高（px），避免数据极少时被拉得过高
 */
export function useFillTableRows(tableRef, rows, { min = 44, max = 56 } = {}) {
  let observer = null

  /** 重新计算行高。 */
  function sync() {
    const el = tableRef.value?.$el
    if (!el) return
    const wrap = el.querySelector('.el-table__body-wrapper')
    if (!wrap) return
    const count = Number(typeof rows === 'function' ? rows() : rows.value) || 0
    if (!count) return
    // 表体可用高度（已扣除滚动条占位，避免最后一列被滚动条压住）
    const avail = wrap.clientHeight
    if (!avail) return
    const height = Math.min(max, Math.max(min, Math.floor(avail / count)))
    el.style.setProperty('--row-h', `${height}px`)
  }

  /** 数据或表格挂载后重算。 */
  async function refresh() {
    await nextTick()
    sync()
  }

  onMounted(() => {
    refresh()
    // 表体高度随窗口变化，需重新分配行高
    const el = tableRef.value?.$el
    const wrap = el?.querySelector('.el-table__body-wrapper')
    if (wrap && typeof ResizeObserver !== 'undefined') {
      observer = new ResizeObserver(() => sync())
      observer.observe(wrap)
    }
    window.addEventListener('resize', sync)
  })

  onBeforeUnmount(() => {
    observer?.disconnect()
    observer = null
    window.removeEventListener('resize', sync)
  })

  watch(rows, refresh, { deep: false })

  return { syncRowHeight: refresh }
}
