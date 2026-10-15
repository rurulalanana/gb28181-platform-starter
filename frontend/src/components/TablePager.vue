<template>
  <div class="table-pager">
    <span class="total">共 {{ pager.total }} {{ unit }}</span>
    <el-pagination
      :current-page="pager.page"
      :page-size="pager.size"
      :page-sizes="pageSizes"
      :total="pager.total"
      layout="sizes, prev, pager, next, jumper"
      background
      size="small"
      @current-change="$emit('page-change', $event)"
      @size-change="$emit('size-change', $event)"
    />
  </div>
</template>

<script setup>
/**
 * 列表底部分页条。
 *
 * <p>取代原先只显示「共 N 条」的 `.table-footer` —— 那一行在数据量大时
 * 既看不到页码也翻不了页，只能靠后端一次返回全部。
 * 各列表的标题、列宽、筛选条件各不相同，但分页交互完全一致，故抽成组件。
 *
 * <p>用 `:current-page` + `@current-change` 而非 `v-model:current-page`：
 * 页码状态由 {@link usePagination} 持有，翻页要连带触发重新加载，
 * 单向传值 + 事件更直白，也避免两处同时改同一个值。
 */
defineProps({
  /** {@link usePagination} 返回的 pager 对象。 */
  pager: { type: Object, required: true },
  /** 计数单位，如「台设备」「个通道」「条报警」。 */
  unit: { type: String, default: '条' },
  /** 可选的每页条数。 */
  pageSizes: { type: Array, default: () => [10, 20, 50, 100] }
})

defineEmits(['page-change', 'size-change'])
</script>

<style scoped>
.table-pager {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
  margin-top: 16px;
}

.table-pager .total {
  color: #909399;
  font-size: 13px;
}
</style>
