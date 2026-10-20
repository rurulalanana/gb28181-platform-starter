import { computed, reactive } from 'vue'

/**
 * 列表分页状态。
 *
 * <p>后端 {@code PageQuery} 用的是<b>游标分页</b>：{@code start} 是偏移量（从 0 开始）、
 * {@code limit} 是每页条数、{@code limit=0} 表示不分页。
 * 本组合式负责在「页码」与「偏移量」之间换算，并把总数收敛在一处，
 * 避免每个列表各写一套 `(page - 1) * size`。
 *
 * <p>用法：
 * <pre>{@code
 * const { pager, offset, reset, setTotal, onPageChange, onSizeChange } =
 *   usePagination({ pageSize: 10, load })
 *
 * async function load() {
 *   const data = await api.list({ start: offset.value, limit: pager.size })
 *   rows.value = data.list
 *   setTotal(data.count)
 * }
 * }</pre>
 *
 * @param pageSize 初始每页条数
 * @param load     翻页 / 改每页条数后要重新执行的加载函数
 */
export function usePagination({ pageSize = 10, load } = {}) {
  const pager = reactive({
    page: 1,
    size: pageSize,
    total: 0
  })

  /** 当前页对应的偏移量（后端 `start` 参数）。 */
  const offset = computed(() => (pager.page - 1) * pager.size)

  /** 回到第一页（查询条件变化时调用，需自行触发加载）。 */
  function reset() {
    pager.page = 1
  }

  /** 记录总数（由加载函数在拿到响应后调用）。 */
  function setTotal(n) {
    pager.total = Number(n) || 0
    // 删除末页最后一条后页码可能越界，回退一页并重载
    const maxPage = Math.max(1, Math.ceil(pager.total / pager.size))
    if (pager.page > maxPage) {
      pager.page = maxPage
      load?.()
    }
  }

  function onPageChange(page) {
    pager.page = page
    load?.()
  }

  function onSizeChange(size) {
    pager.size = size
    pager.page = 1
    load?.()
  }

  return { pager, offset, reset, setTotal, onPageChange, onSizeChange }
}
