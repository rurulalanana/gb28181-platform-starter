<template>
  <!-- 聚合搜索框：一个输入框本地匹配多个字段（数据量小，无需后端分页查询） -->
  <el-input
    v-model="keyword"
    class="keyword-filter"
    clearable
    :placeholder="placeholder"
    :style="{ width }"
  >
    <template #prefix>
      <el-icon aria-hidden="true"><Search /></el-icon>
    </template>
  </el-input>
</template>

<script setup>
defineProps({
  /** 提示语里直接写清能搜哪些字段，避免用户猜。 */
  placeholder: { type: String, default: '搜索' },
  width: { type: String, default: '260px' }
})

const keyword = defineModel({ type: String, default: '' })
</script>

<style scoped>
.keyword-filter :deep(.el-input__wrapper) {
  border-radius: var(--r-md);
}
</style>
