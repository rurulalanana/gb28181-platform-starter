<template>
  <div class="notfound">
    <div class="code">404</div>
    <p class="text">页面不存在，或该菜单已被停用 / 删除。</p>
    <div class="actions">
      <el-button type="primary" @click="goHome">回到首页</el-button>
      <el-button @click="back">返回上一页</el-button>
    </div>
  </div>
</template>

<script setup>
import { useRouter } from 'vue-router'

const router = useRouter()

function goHome() {
  router.push('/')
}

function back() {
  if (window.history.length > 1) {
    router.back()
  } else {
    router.push('/')
  }
}
</script>

<style scoped>
.notfound {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: var(--sp-3);
  background: var(--bg-body);
  color: var(--text-secondary);
}

.code {
  font-size: 72px;
  font-weight: 700;
  line-height: 1;
  color: var(--brand-500);
  letter-spacing: 2px;
}

.text {
  margin: 0;
  font-size: var(--fs-md);
}

.actions {
  display: flex;
  gap: var(--sp-2);
  margin-top: var(--sp-2);
}
</style>
