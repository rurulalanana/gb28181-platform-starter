<template>
  <!--
    问号帮助图标：hover（或聚焦）显示说明。统一 show-after 与 popper 宽度，
    调用处只写 <HelpTip text="..." />，避免各页面「有的等一秒、有的立刻弹」。
  -->
  <el-tooltip
    :content="text"
    :placement="placement"
    :show-after="120"
    :disabled="!text"
    popper-class="help-tip-popper"
    effect="dark"
  >
    <el-icon class="help-tip" tabindex="0" :aria-label="text || '说明'">
      <QuestionFilled aria-hidden="true" />
    </el-icon>
  </el-tooltip>
</template>

<script setup>
import { QuestionFilled } from '@element-plus/icons-vue'

defineProps({
  /** 说明文字。为空时不显示 tooltip（图标仍在，用于对齐）。 */
  text: { type: String, default: '' },
  /** 弹出方向，默认上方。 */
  placement: { type: String, default: 'top' }
})
</script>

<style scoped>
/*
 * 默认淡、hover 变亮：辅助元素不抢正文注意力。
 * popper 被 teleport 到 body，其样式写在 styles/main.css，不在这里。
 */
.help-tip {
  font-size: var(--fs-md);
  /* ink-400 太小会像灰尘；用 ink-500 保住存在感（与全局 .card-title .el-icon 同色） */
  color: var(--ink-500);
  cursor: help;
  transition: color 0.15s;
}

.help-tip:hover,
.help-tip:focus-visible {
  color: var(--brand-500);
}
</style>
