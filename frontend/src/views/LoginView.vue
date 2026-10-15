<template>
  <div class="login">
    <!-- 左侧品牌区：桌面端展示，移动端隐藏 -->
    <section class="hero">
      <div class="hero-inner">
        <div class="hero-brand">
          <div class="hero-mark"><el-icon :size="22"><VideoCamera /></el-icon></div>
          <span class="hero-name">GB28181 视频平台</span>
        </div>

        <h1 class="hero-title">
          国标视频监控<br />一体化管理平台
        </h1>
        <p class="hero-desc">
          符合 GB/T 28181—2022 信令规范，内置 SIP 信令网关与 ZLMediaKit 流媒体内核，
          支持设备接入、实时预览、历史回放、云端录像与级联共享。
        </p>

        <ul class="hero-features">
          <li v-for="f in features" :key="f.title">
            <el-icon class="feat-icon"><component :is="f.icon" /></el-icon>
            <div>
              <b>{{ f.title }}</b>
              <span>{{ f.desc }}</span>
            </div>
          </li>
        </ul>
      </div>

      <!-- 装饰网格 -->
      <div class="hero-grid" />
      <div class="hero-glow" />
    </section>

    <!-- 右侧登录表单 -->
    <section class="panel">
      <div class="panel-inner">
        <header class="panel-head">
          <h2>登录</h2>
          <p>请输入您的账号信息以继续</p>
        </header>

        <el-form :model="form" @submit.prevent="onSubmit" size="large" class="login-form">
          <el-form-item>
            <el-input v-model="form.username" placeholder="用户名" clearable autocomplete="username">
              <template #prefix><el-icon><User /></el-icon></template>
            </el-input>
          </el-form-item>

          <el-form-item>
            <el-input
              v-model="form.password"
              type="password"
              placeholder="密码"
              show-password
              autocomplete="current-password"
              @keyup.enter="onSubmit"
            >
              <template #prefix><el-icon><Lock /></el-icon></template>
            </el-input>
          </el-form-item>

          <el-alert
            v-if="error"
            :title="error"
            type="error"
            show-icon
            :closable="false"
            class="login-alert"
          />

          <el-button
            type="primary"
            size="large"
            class="submit"
            :loading="loading"
            @click="onSubmit"
          >
            {{ loading ? '登录中…' : '登 录' }}
          </el-button>
        </el-form>

        <div class="panel-tip">
          <el-icon><InfoFilled /></el-icon>
          <span>默认账号 <b class="mono">admin / admin123</b>，首次部署后请及时修改</span>
        </div>
      </div>
    </section>
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const router = useRouter()
const route = useRoute()

const form = reactive({ username: 'admin', password: '' })
const loading = ref(false)
const error = ref('')

const features = [
  { icon: 'Connection', title: '国标信令', desc: 'REGISTER / INVITE / Catalog 全流程' },
  { icon: 'VideoCamera', title: '流媒体内核', desc: 'ZLMediaKit 内嵌，多协议转码分发' },
  { icon: 'Share', title: '级联共享', desc: '上下级平台互联互通' }
]

async function onSubmit() {
  if (!form.username || !form.password) {
    error.value = '请输入用户名和密码'
    return
  }
  loading.value = true
  error.value = ''
  try {
    await auth.login(form.username, form.password)
    router.push(route.query.redirect || '/dashboard')
  } catch (e) {
    error.value = e.message || '登录失败'
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login {
  display: flex;
  min-height: 100vh;
  background: var(--bg-surface);
}

/* ============ 左侧品牌区 ============ */

.hero {
  position: relative;
  flex: 1.15;
  display: flex;
  align-items: center;
  padding: 0 6vw;
  overflow: hidden;
  background: linear-gradient(150deg, #101828 0%, #16233c 45%, #1b3a6b 100%);
}

.hero-inner {
  position: relative;
  z-index: 2;
  max-width: 460px;
}

.hero-brand {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 40px;
}

.hero-mark {
  width: 36px;
  height: 36px;
  border-radius: 10px;
  background: linear-gradient(135deg, var(--brand-400), var(--brand-600));
  display: flex;
  align-items: center;
  justify-content: center;
  color: #fff;
  box-shadow: 0 4px 14px rgba(47, 109, 246, 0.45);
}

.hero-name {
  font-size: 14px;
  font-weight: 600;
  color: rgba(255, 255, 255, 0.9);
  letter-spacing: 0.4px;
}

.hero-title {
  margin: 0 0 18px;
  font-size: 38px;
  font-weight: 700;
  line-height: 1.28;
  letter-spacing: -0.8px;
  color: #fff;
}

.hero-desc {
  margin: 0 0 40px;
  font-size: 14px;
  line-height: 1.85;
  color: rgba(255, 255, 255, 0.58);
}

.hero-features {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.hero-features li {
  display: flex;
  align-items: flex-start;
  gap: 12px;
}

.feat-icon {
  width: 30px;
  height: 30px;
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.08);
  border: 1px solid rgba(255, 255, 255, 0.12);
  color: var(--brand-300);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 15px;
  flex-shrink: 0;
}

.hero-features b {
  display: block;
  font-size: 13.5px;
  font-weight: 600;
  color: rgba(255, 255, 255, 0.92);
  margin-bottom: 2px;
}

.hero-features span {
  font-size: 12.5px;
  color: rgba(255, 255, 255, 0.45);
}

/* 装饰 */
.hero-grid {
  position: absolute;
  inset: 0;
  background-image:
    linear-gradient(rgba(255, 255, 255, 0.035) 1px, transparent 1px),
    linear-gradient(90deg, rgba(255, 255, 255, 0.035) 1px, transparent 1px);
  background-size: 44px 44px;
  mask-image: radial-gradient(ellipse at 30% 50%, #000 20%, transparent 72%);
  z-index: 1;
}

.hero-glow {
  position: absolute;
  width: 460px;
  height: 460px;
  right: -140px;
  bottom: -160px;
  border-radius: 50%;
  background: radial-gradient(circle, rgba(47, 109, 246, 0.28), transparent 68%);
  z-index: 1;
}

/* ============ 右侧表单 ============ */

.panel {
  flex: 1;
  min-width: 420px;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 40px 5vw;
}

.panel-inner {
  width: 100%;
  max-width: 340px;
}

.panel-head {
  margin-bottom: 28px;
}

.panel-head h2 {
  margin: 0 0 6px;
  font-size: 25px;
  font-weight: 680;
  letter-spacing: -0.5px;
  color: var(--text-primary);
}

.panel-head p {
  margin: 0;
  font-size: 13px;
  color: var(--text-secondary);
}

.login-form :deep(.el-form-item) {
  margin-bottom: var(--sp-4);
}

.login-form :deep(.el-input__wrapper) {
  height: 44px;
  padding: 0 14px;
}

.login-alert {
  margin-bottom: var(--sp-4);
  border-radius: var(--r-md);
}

.submit {
  width: 100%;
  height: 44px;
  font-size: 14.5px;
  font-weight: 600;
  letter-spacing: 3px;
  border-radius: var(--r-lg);
  box-shadow: 0 6px 18px rgba(47, 109, 246, 0.28);
}

.panel-tip {
  display: flex;
  align-items: center;
  gap: 7px;
  margin-top: 26px;
  padding: 10px 12px;
  border-radius: var(--r-md);
  background: var(--brand-50);
  border: 1px solid var(--brand-100);
  font-size: 12px;
  color: var(--brand-800);
}

.panel-tip b {
  color: var(--brand-700);
}

/* ============ 响应式 ============ */

@media (max-width: 900px) {
  .hero {
    display: none;
  }
  .panel {
    min-width: 0;
    padding: 32px 20px;
  }
}
</style>
