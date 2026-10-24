<template>
  <!--
    问号帮助图标：hover（或聚焦）时显示说明文字。

    <p><b>为什么单独抽组件</b>：说明型文案集中出现在设置类页面，
    一个页面就有二三十处。每处都写 {@code el-tooltip + el-icon} 既啰嗦、
    又容易忘掉 {@code show-after}、popper 宽度这类细节，导致「有的要等一秒、
    有的立刻弹」。这里统一默认值，调用处只写 {@code <HelpTip text="..." />}。
  -->
  <el-tooltip
    :content="text"
    :placement="placement"
    :show-after="120"
    :disabled="!text"
    popper-class="help-tip-popper"
    effect="dark"
  >
    <el-icon class="help-tip" tabindex="0">
      <QuestionFilled />
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
 * 图标默认淡、hover 变亮：它是辅助元素，不该和正文抢注意力，
 * 但鼠标扫过时要给「这里有说明」的反馈。
 *
 * 颜色用 scoped 即可（图标本身在组件模板里，带 data-v 标记）；
 * 但 popper（tooltip 气泡）会被 teleport 到 body 下，那个样式在
 * styles/main.css 里统一写，不在这里。
 */
.help-tip {
  font-size: 13px;
  color: var(--text-tertiary);
  cursor: help;
  transition: color 0.15s;
}

.help-tip:hover,
.help-tip:focus-visible {
  color: var(--brand-500);
  outline: none;
}
</style>
