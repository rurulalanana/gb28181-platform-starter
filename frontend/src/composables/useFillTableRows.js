import { nextTick, onBeforeUnmount, onMounted, watch } from 'vue'

/**
 * 让表格行高自适应可用高度：按「表体可用高度 ÷ 当前行数」写入 CSS 变量 --row-h。
 *
 * 列表页用 .page.is-fill 拉满卡片后，表格默认 fit-content 会下方空一块；改成 flex:1 又把空白
 * 挪到表体内部。行数少时拉高铺满，行数多时回落到 min 并内部滚动，分页条不动。
 *
 * @param {import('vue').Ref} tableRef el-table 组件引用
 * @param {import('vue').Ref|Function} rows 数据条数来源
 * @param {object} [options]
 * @param {number} [options.min] 最小行高（px），低于此值改为表内滚动
 * @param {number} [options.max] 最大行高（px），避免数据极少时被拉得过高
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
