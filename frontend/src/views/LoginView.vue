<template>
  <div class="login">
    <div class="login-box">
      <!-- 顶部石板灰标题条（参考站同款） -->
      <div class="login-head">GB28181 视频平台 · 登录</div>

      <div class="login-body">
        <el-form :model="form" @submit.prevent="onSubmit" size="large" class="login-form">
          <el-form-item>
            <el-input
              v-model="form.username"
              placeholder="用户名"
              aria-label="用户名"
              clearable
              autocomplete="username"
            >
              <template #suffix><el-icon aria-hidden="true"><User /></el-icon></template>
            </el-input>
          </el-form-item>

          <el-form-item>
            <el-input
              v-model="form.password"
              type="password"
              placeholder="密码"
              aria-label="密码"
              show-password
              autocomplete="current-password"
              @keyup.enter="onSubmit"
            >
              <template #suffix><el-icon aria-hidden="true"><Lock /></el-icon></template>
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
            {{ loading ? '登录中…' : '登录' }}
          </el-button>
        </el-form>
      </div>

      <!-- 底部信息条 -->
      <div class="login-foot">
        <span>首次部署后请及时修改默认密码</span>
      </div>
    </div>

    <!-- 滑动验证码：每次登录都要过一次，凭证一次性、失败需重验 -->
    <SlideCaptcha v-model="captchaVisible" @success="onCaptchaSuccess" />
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { captchaApi } from '@/api'
import SlideCaptcha from '@/components/SlideCaptcha.vue'

const auth = useAuthStore()
const router = useRouter()
const route = useRoute()

const form = reactive({ username: 'admin', password: '' })
const loading = ref(false)
const error = ref('')

/** 验证码弹窗开关。 */
const captchaVisible = ref(false)
/** 服务端下发的一次性验证凭证，用完即弃。 */
let captchaId = ''

/**
 * 本次部署是否要验证码。
 *
 * <p>由后端 `gb28181.login.captcha-enabled` 决定，前端不写死：
 * 本地调试或验证码组件故障时会被关掉，此时接口根本不校验 captchaId，
 * 前端若仍无条件弹滑块，用户就会卡在「拖了也登不进去」。
 *
 * <p>默认 {@code true}：查询失败时按「要验证码」处理，宁可多一步也不放过裸奔。
 */
const captchaRequired = ref(true)

onMounted(async () => {
  try {
    const r = await captchaApi.required()
    captchaRequired.value = r?.required !== false
  } catch {
    captchaRequired.value = true
  }
})

/**
 * 点「登录」：需要验证码时先过滑块，拿到凭证后再真正提交。
 *
 * <p>不在打开页面时就弹验证码 —— 用户还没填完账号密码，先弹个拼图只会打断节奏。
 */
async function onSubmit() {
  if (!form.username || !form.password) {
    error.value = '请输入用户名和密码'
    return
  }
  if (captchaRequired.value && !captchaId) {
    captchaVisible.value = true
    return
  }
  await doLogin()
}

/** 验证通过：暂存凭证并自动提交。 */
function onCaptchaSuccess(id) {
  captchaId = id || ''
  if (captchaId) {
    doLogin()
  }
}

async function doLogin() {
  loading.value = true
  error.value = ''
  try {
    await auth.login(form.username, form.password, captchaId)
    router.push(route.query.redirect || '/dashboard')
  } catch (e) {
    error.value = e.message || '登录失败'
    // 凭证是一次性的，后端已消费；不清理会导致下次点击直接被判失效
    captchaId = ''
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
  font-size: var(--fs-xl);
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
  font-size: var(--fs-lg);
}

.login-alert {
  margin-bottom: var(--sp-3);
  border-radius: var(--r-sm);
}

.submit {
  width: 100%;
  height: 36px;
  font-size: var(--fs-md);
  font-weight: 600;
  border-radius: var(--r-sm);
  letter-spacing: 2px;
}

.login-foot {
  padding: 10px 26px;
  border-top: 1px solid var(--line);
  font-size: var(--fs-xs);
  color: var(--ink-500);
}

.login-foot code {
  font-family: var(--mono);
  font-size: var(--fs-xs);
  padding: 1px 5px;
  background: var(--ink-50);
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  color: var(--ink-700);
}
</style>
