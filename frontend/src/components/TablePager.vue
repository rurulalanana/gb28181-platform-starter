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
 * 用 :current-page + @current-change 而非 v-model：页码由 usePagination 持有，
 * 翻页要连带触发重新加载，单向传值 + 事件更直白，也避免两处同时改同一个值。
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
  color: var(--text-secondary);
  font-size: var(--fs-sm);
}
</style>
