<template>
  <div class="login">
    <div class="login-box">
      <!-- 顶部石板灰标题条（参考站同款） -->
      <div class="login-head">GB28181 视频平台 · 登录</div>

      <div class="login-body">
        <el-form :model="form" @submit.prevent="onSubmit" size="large" class="login-form">
          <el-form-item>
            <el-input v-model="form.username" placeholder="用户名" clearable autocomplete="username">
              <template #suffix><el-icon><User /></el-icon></template>
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
              <template #suffix><el-icon><Lock /></el-icon></template>
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

          <el-button type="primary" class="submit" :loading="loading" @click="onSubmit">
            {{ loading ? '登录中…' : '登 录' }}
          </el-button>
        </el-form>
      </div>

      <!-- 底部信息条 -->
      <div class="login-foot">
        <span>默认账号 <code>admin / admin123</code>，首次部署后请及时修改</span>
      </div>
    </div>
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
/* 参考站登录页：浅灰底 + 细网格 + 居中白卡（石板灰标题条 + 底部信息条） */
.login {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
  background-color: #f0f2f6;
  background-image:
    linear-gradient(rgba(0, 0, 0, 0.028) 1px, transparent 1px),
    linear-gradient(90deg, rgba(0, 0, 0, 0.028) 1px, transparent 1px);
  background-size: 26px 26px;
}

.login-box {
  width: 380px;
  max-width: 100%;
  background: var(--white);
  border: 1px solid var(--line);
  border-radius: var(--r-xl);
  box-shadow: var(--sh-md);
  overflow: hidden;
}

.login-head {
  height: 62px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--bg-sidebar);
  color: #fff;
  font-size: 17px;
  font-weight: 600;
  letter-spacing: 0.5px;
}

.login-body {
  padding: 22px 26px 18px;
}

.login-form :deep(.el-form-item) {
  margin-bottom: var(--sp-3);
}

.login-form :deep(.el-input__wrapper) {
  height: 36px;
  padding: 0 11px;
}

/* 输入框内的图标放右侧（参考站同款），灰一点 */
.login-form :deep(.el-input__suffix .el-icon) {
  color: var(--ink-400);
  font-size: 15px;
}

.login-alert {
  margin-bottom: var(--sp-3);
  border-radius: var(--r-sm);
}

.submit {
  width: 100%;
  height: 36px;
  font-size: 13.5px;
  font-weight: 600;
  border-radius: var(--r-sm);
  letter-spacing: 2px;
}

.login-foot {
  padding: 10px 26px;
  border-top: 1px solid var(--line);
  font-size: 12px;
  color: var(--ink-500);
}

.login-foot code {
  font-family: var(--mono);
  font-size: 11.5px;
  padding: 1px 5px;
  background: var(--ink-50);
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  color: var(--ink-700);
}
</style>
