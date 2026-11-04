<template>
  <el-dialog
    v-model="visible"
    title="安全验证"
    width="380px"
    align-center
    :close-on-click-modal="false"
    append-to-body
    class="slide-captcha-dialog"
    @closed="onClosed"
  >
    <div v-loading="loading" class="sc-body">
      <!-- 图区：底图 + 浮在左边缘的拼图块 -->
      <div class="sc-pic" :style="{ height: displayHeight + 'px' }">
        <img v-if="bg" :src="bg" class="sc-bg" alt="" @load="onBgLoad" />
        <img
          v-if="piece"
          :src="piece"
          class="sc-piece"
          :style="{ left: dragLeft + 'px', width: pieceDisplayWidth + 'px' }"
          alt=""
        />
        <div v-if="result" class="sc-mask" :class="result">
          <el-icon :size="26">
            <component :is="result === 'ok' ? 'CircleCheckFilled' : 'CircleCloseFilled'" />
          </el-icon>
          <span>{{ result === 'ok' ? '验证通过' : '验证失败，请重试' }}</span>
        </div>
        <button type="button" class="sc-refresh" title="换一张" @click="fetchCaptcha">
          <el-icon><Refresh /></el-icon>
        </button>
      </div>

      <!-- 滑条 -->
      <div ref="barEl" class="sc-bar" :class="barState" :style="{ width: boxWidth + 'px' }">
        <div class="sc-bar-fill" :style="{ width: dragLeft + 'px' }" />
        <div class="sc-handle" :style="{ left: dragLeft + 'px' }" @pointerdown.prevent="onDown">
          <el-icon><DArrowRight /></el-icon>
        </div>
        <span class="sc-tip">{{ tip }}</span>
      </div>
    </div>

    <template #footer>
      <span class="sc-hint">拖动滑块使图案重合</span>
    </template>
  </el-dialog>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { captchaApi } from '@/api'

/**
 * 滑块验证码（tianai-captcha 协议，弹出式）。
 *
 * <p>三轮：{@code /captcha/gen} 拿底图与拼图块 → 拖动后提交轨迹给
 * {@code /captcha/check} → 通过后拿 {@code id} 作为登录请求的 {@code captchaId}，
 * 后端再按「已通过且未消费」二次校验。
 *
 * <p><b>为什么不引官方 SDK</b>：官方 {@code tac-web.zip} / {@code load.min.js}
 * 不在 npm 也不在稳定 CDN 上（实测 404），把一个无法锁定版本的远端脚本塞进
 * 登录路径风险更大。协议本身很简单，自己画一个反而可控。
 *
 * <p><b>坐标必须换算回原图像素</b>：服务端校验的是相对底图原始尺寸的位移，
 * 而滑条量到的是屏幕像素。显示尺寸一旦被 CSS 缩放，直接提交屏幕像素必然超出容差，
 * 表现为「明明对齐了却验证失败」。
 */
const props = defineProps({
  modelValue: { type: Boolean, default: false }
})
const emit = defineEmits(['update:modelValue', 'success'])

const visible = computed({
  get: () => props.modelValue,
  set: (v) => emit('update:modelValue', v)
})

/** 名义展示宽度（首次计算用）。实际一律以 boxWidth（DOM 实测）为准。 */
const displayWidth = ref(320)
const displayHeight = ref(160)
const pieceDisplayWidth = ref(48)

/** 底图容器的实测宽度。换算坐标必须用它，不能用写死的 displayWidth。 */
const boxWidth = ref(320)

/** 服务端给的原始尺寸，用于把屏幕位移换算回原图坐标。 */
const rawWidth = ref(600)
const rawHeight = ref(360)
const rawPieceWidth = ref(90)

const bg = ref('')
const piece = ref('')
const captchaId = ref('')

const loading = ref(false)
const dragLeft = ref(0)
const result = ref('')
const tip = ref('向右拖动滑块填充拼图')
const barEl = ref(null)

const barState = computed(() => ({ 'is-ok': result.value === 'ok', 'is-fail': result.value === 'fail' }))

let dragging = false
let startX = 0
let startTime = 0
/** 轨迹点：tianai 的校验器会看轨迹是否像人（有加减速、非匀速直线）。 */
let track = []
let lastPointAt = 0

async function fetchCaptcha() {
  result.value = ''
  dragLeft.value = 0
  tip.value = '向右拖动滑块填充拼图'
  loading.value = true
  try {
    const d = await captchaApi.gen('SLIDER')
    if (!d || !d.id) {
      tip.value = '验证码加载失败，请点击右上角刷新'
      return
    }
    captchaId.value = d.id
    bg.value = d.backgroundImage
    piece.value = d.templateImage
    rawWidth.value = d.backgroundImageWidth || 600
    rawHeight.value = d.backgroundImageHeight || 360
    rawPieceWidth.value = d.templateImageWidth || 90
    // 图还没加载出来时先按名义宽度算一版，onBgLoad 会用实测值纠正
    displayHeight.value = Math.round((displayWidth.value * rawHeight.value) / rawWidth.value)
    pieceDisplayWidth.value = Math.round((displayWidth.value * rawPieceWidth.value) / rawWidth.value)
  } catch (e) {
    tip.value = e.message || '验证码加载失败，请点击右上角刷新'
  } finally {
    loading.value = false
  }
}

/**
 * 底图加载完：以图片自然尺寸与容器实测宽度为准重算展示尺寸。
 *
 * <p>必须用 {@code getBoundingClientRect()} 而不是写死的 displayWidth ——
 * 实测过弹窗内可用宽度与 320 不一致（浏览器缩放/滚动条都会影响），
 * 用错宽度会把坐标按比例算歪，表现为「明明对齐了却验证失败」。
 */
function onBgLoad(ev) {
  const img = ev.target
  if (!img.naturalWidth || !img.naturalHeight) return
  rawWidth.value = img.naturalWidth
  rawHeight.value = img.naturalHeight
  measure()
}

/** 重算展示尺寸：底图与拼图块都按容器宽度等比缩放，两者比例必须一致。 */
function measure() {
  const box = document.querySelector('.sc-pic')
  const w = box ? box.getBoundingClientRect().width : 0
  if (!w) return
  boxWidth.value = w
  displayHeight.value = Math.round((w * rawHeight.value) / rawWidth.value)
  pieceDisplayWidth.value = Math.round((w * rawPieceWidth.value) / rawWidth.value)
}

function onDown(ev) {
  if (result.value === 'ok' || loading.value) return
  dragging = true
  startX = ev.clientX
  startTime = Date.now()
  lastPointAt = startTime
  track = []
  pushTrack(0, startTime)
  window.addEventListener('pointermove', onMove)
  window.addEventListener('pointerup', onUp)
  window.addEventListener('pointercancel', onUp)
}

function onMove(ev) {
  if (!dragging || !barEl.value) return
  const max = barEl.value.clientWidth - handleWidth()
  const next = Math.min(Math.max(0, ev.clientX - startX), max)
  dragLeft.value = next
  tip.value = '松开后自动校验'
  const now = Date.now()
  // 采样节流：每 16ms 记一个点就够了，太多会被当成脚本
  if (now - lastPointAt >= 16) {
    lastPointAt = now
    pushTrack(next, now)
  }
}

function pushTrack(left, now) {
  track.push({
    x: round((left * rawWidth.value) / boxWidth.value),
    y: round((displayHeight.value / 2) * rawHeight.value / displayHeight.value),
    t: now - startTime,
    type: 'move'
  })
}

async function onUp() {
  if (!dragging) return
  dragging = false
  window.removeEventListener('pointermove', onMove)
  window.removeEventListener('pointerup', onUp)
  window.removeEventListener('pointercancel', onUp)
  if (dragLeft.value <= 0) {
    tip.value = '向右拖动滑块填充拼图'
    return
  }
  await verify()
}

async function verify() {
  loading.value = true
  tip.value = '校验中…'
  const stopTime = Date.now()
  pushTrack(dragLeft.value, stopTime)
  try {
    const res = await captchaApi.check({
      id: captchaId.value,
      data: {
        bgImageWidth: rawWidth.value,
        bgImageHeight: rawHeight.value,
        templateImageWidth: rawPieceWidth.value,
        templateImageHeight: rawHeight.value,
        startTime,
        stopTime,
        // left/top 是拼图块最终位置，服务端优先看这两个值，轨迹用于人机判定
        left: Math.round((dragLeft.value * rawWidth.value) / boxWidth.value),
        top: 0,
        trackList: track
      }
    })
    if (res && res.id) {
      result.value = 'ok'
      tip.value = '验证通过'
      emit('success', res.id)
      setTimeout(() => (visible.value = false), 600)
    } else {
      result.value = 'fail'
      tip.value = '验证失败，请重试'
      setTimeout(fetchCaptcha, 900)
    }
  } catch (e) {
    result.value = 'fail'
    tip.value = e.message || '验证失败，请重试'
    setTimeout(fetchCaptcha, 900)
  } finally {
    loading.value = false
  }
}

function handleWidth() {
  const el = barEl.value?.querySelector('.sc-handle')
  return el ? el.offsetWidth : 40
}

function round(v) {
  return Math.round(v * 100) / 100
}

/** 打开时取一张新图；关闭时清干净，避免下次进来看到上次的结果。 */
watch(visible, (v) => {
  if (v) fetchCaptcha()
})

/**
 * 窗口尺寸变化时重测。
 *
 * <p>不重测的话，用户在弹窗打开状态下缩放浏览器/改窗口尺寸，
 * 图会跟着变宽变窄，而换算基准还停在旧值，后续拖动就会算错坐标。
 */
function onResize() {
  if (visible.value && bg.value) measure()
}

onMounted(() => {
  window.addEventListener('resize', onResize)
})

function onClosed() {
  bg.value = ''
  piece.value = ''
  captchaId.value = ''
  dragLeft.value = 0
  result.value = ''
  track = []
}

onBeforeUnmount(() => {
  window.removeEventListener('pointermove', onMove)
  window.removeEventListener('pointerup', onUp)
  window.removeEventListener('pointercancel', onUp)
  window.removeEventListener('resize', onResize)
})
</script>

<style scoped>
.sc-body {
  user-select: none;
}

.sc-pic {
  position: relative;
  /* 撑满弹窗内部宽度即可，不要写死 —— 写死 310 与实测的容器宽度（随浏览器缩放
     与弹窗宽度变化）会不一致，坐标换算会按错的比例歪掉。 */
  width: 100%;
  margin: 0 auto;
  overflow: hidden;
  border-radius: var(--radius-sm, 6px);
  background: #f4f5f7;
}

.sc-bg {
  display: block;
  width: 100%;
  height: 100%;
  object-fit: fill;
}

.sc-piece {
  position: absolute;
  top: 0;
  z-index: 2;
  pointer-events: none;
  filter: drop-shadow(0 1px 2px rgba(0, 0, 0, 0.45));
}

.sc-mask {
  position: absolute;
  inset: 0;
  z-index: 3;
  display: flex;
  flex-direction: column;
  gap: 6px;
  align-items: center;
  justify-content: center;
  color: #fff;
  font-size: var(--fs-sm);
}

.sc-mask.ok {
  background: rgba(22, 163, 74, 0.82);
}

.sc-mask.fail {
  background: rgba(220, 38, 38, 0.82);
}

.sc-refresh {
  position: absolute;
  top: 6px;
  right: 6px;
  z-index: 4;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 26px;
  height: 26px;
  color: #fff;
  background: rgba(0, 0, 0, 0.35);
  border: 0;
  border-radius: 50%;
  cursor: pointer;
}

.sc-refresh:hover {
  background: rgba(0, 0, 0, 0.55);
}

.sc-bar {
  position: relative;
  height: 40px;
  margin: 12px auto 0;
  overflow: hidden;
  background: var(--bg-sunken);
  border: 1px solid var(--ink-200);
  border-radius: var(--radius-sm, 6px);
}

.sc-bar.is-ok {
  border-color: #16a34a;
}

.sc-bar.is-fail {
  border-color: #dc2626;
}

.sc-bar-fill {
  position: absolute;
  inset: 0 auto 0 0;
  background: var(--brand-100, #dbeafe);
}

.sc-handle {
  position: absolute;
  top: 0;
  left: 0;
  z-index: 2;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 40px;
  height: 100%;
  color: #fff;
  background: var(--brand-500, #3b82f6);
  border-radius: var(--radius-sm, 6px);
  cursor: grab;
  touch-action: none;
}

.sc-handle:active {
  cursor: grabbing;
}

.sc-tip {
  position: absolute;
  inset: 0;
  z-index: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: var(--fs-xs);
  color: var(--text-tertiary);
  pointer-events: none;
}

.sc-hint {
  font-size: var(--fs-xs);
  color: var(--text-tertiary);
}
</style>
