<template>
  <!--
    框选图层。叠在播放器上，激活时才渲染并接管指针事件；
    未激活时整个节点不存在，完全不挡播放器自身的点击/双击全屏。

    为什么用 div 画框而不是 canvas：框选是矩形，一个带 border 的 div 就够，
    不需要像素级绘制，还能直接用 CSS 做半透明遮罩（canvas 要实现同样效果得手写合成）。
  -->
  <div
    v-if="active"
    ref="layerRef"
    class="track-layer"
    :class="{ 'is-drawing': drawing }"
    @pointerdown="onDown"
    @pointermove="onMove"
    @pointerup="onUp"
    @pointercancel="cancel"
    @contextmenu.prevent="cancel"
  >
    <div v-if="rect" class="track-mask">
      <div
        class="track-rect"
        :style="{ left: rect.left + 'px', top: rect.top + 'px', width: rect.w + 'px', height: rect.h + 'px' }"
      >
        <span v-if="rect.w > 60 && rect.h > 24" class="track-size">
          {{ boxW }} × {{ boxH }}
        </span>
      </div>
    </div>

    <div class="track-tip">
      <span>{{ tipText }}</span>
      <button type="button" class="track-tip-cancel" @click.stop="cancel">取消（Esc）</button>
    </div>
  </div>
</template>

<script setup>
import { computed, onUnmounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { controlApi } from '@/api'

const props = defineProps({
  /** 设备编码 */
  serial: { type: String, default: '' },
  /** 通道编码 */
  code: { type: String, default: '' },
  /** 是否处于框选状态 */
  active: { type: Boolean, default: false },
  /** 框选后的动作：targettrack（目标跟踪）/ dragzoom（拉框放大） */
  mode: { type: String, default: 'targettrack' },
  /** 外部禁用（未播放/无权限） */
  disabled: { type: Boolean, default: false }
})

const emit = defineEmits(['update:active', 'sent'])

/** 拉框的最小边长（屏幕像素）：太小是误触，换算到原始分辨率后更没意义。 */
const MIN_SIDE = 12

const layerRef = ref(null)
const drawing = ref(false)
const rect = ref(null)
const sending = ref(false)

/** 指针起点（相对图层）。 */
let startX = 0
let startY = 0

const tipText = computed(() => {
  if (sending.value) return '正在下发…'
  return props.mode === 'dragzoom'
    ? '在画面上拖拽框选要放大的区域，右键取消'
    : '在画面上拖拽框选要跟踪的目标，右键取消'
})

/** 当前框换算到视频原始分辨率后的尺寸，直接显示给用户，避免怀疑坐标不对。 */
const boxW = ref(0)
const boxH = ref(0)

/**
 * 找到底层 {@code <video>} 并算出「画面内容」在屏幕上的真实矩形。
 *
 * 为什么不能直接用图层容器：video 以 object-fit: contain 渲染时有黑边，
 * 容器尺寸 ≠ 画面尺寸。框选坐标必须换算到<b>视频原始分辨率</b>，
 * 否则有黑边时框会整体偏移（黑边越宽偏得越多）。
 *
 * @returns {{v: HTMLVideoElement, left: number, top: number, scale: number,
 *            nativeW: number, nativeH: number} | null}
 */
function measureVideo() {
  const layer = layerRef.value
  if (!layer) return null
  const box = layer.parentElement
  const v = box ? box.querySelector('video') : null
  if (!v) return null
  const vr = v.getBoundingClientRect()
  const nw = v.videoWidth || 0
  const nh = v.videoHeight || 0
  if (!vr.width || !vr.height) return null
  // 元数据还没到时 videoWidth=0：退回容器尺寸，至少不会算出 NaN
  if (!nw || !nh) {
    return { v, left: vr.left, top: vr.top, scale: 1, nativeW: Math.round(vr.width), nativeH: Math.round(vr.height) }
  }
  const scale = Math.min(vr.width / nw, vr.height / nh)
  const dispW = nw * scale
  const dispH = nh * scale
  return {
    v,
    left: vr.left + (vr.width - dispW) / 2,
    top: vr.top + (vr.height - dispH) / 2,
    scale,
    nativeW: nw,
    nativeH: nh
  }
}

/** 屏幕坐标 → 视频原始分辨率像素，并夹取到画面范围内。 */
function toNative(clientX, clientY, m) {
  const x = Math.round((clientX - m.left) / m.scale)
  const y = Math.round((clientY - m.top) / m.scale)
  return {
    x: Math.min(Math.max(x, 0), m.nativeW),
    y: Math.min(Math.max(y, 0), m.nativeH)
  }
}

function onDown(e) {
  if (props.disabled || sending.value) return
  if (e.button !== 0) return
  const layer = layerRef.value
  if (!layer) return
  const lr = layer.getBoundingClientRect()
  startX = e.clientX - lr.left
  startY = e.clientY - lr.top
  drawing.value = true
  rect.value = { left: startX, top: startY, w: 0, h: 0 }
  // 捕获指针：拖到画面外再松手也能收到 pointerup，否则会一直停在"画框中"
  try {
    layer.setPointerCapture(e.pointerId)
  } catch (err) {
    // 指针已释放等情况下会抛错，不影响后续逻辑
  }
  const m = measureVideo()
  boxW.value = 0
  boxH.value = 0
  if (m) {
    boxW.value = m.nativeW
    boxH.value = m.nativeH
  }
}

function onMove(e) {
  if (!drawing.value) return
  const layer = layerRef.value
  if (!layer) return
  const lr = layer.getBoundingClientRect()
  const cx = Math.min(Math.max(e.clientX - lr.left, 0), lr.width)
  const cy = Math.min(Math.max(e.clientY - lr.top, 0), lr.height)
  rect.value = {
    left: Math.min(startX, cx),
    top: Math.min(startY, cy),
    w: Math.abs(cx - startX),
    h: Math.abs(cy - startY)
  }
  // 实时预览会换算成原始像素，用户能立刻看出「框对应多少像素」
  const m = measureVideo()
  if (m && m.scale) {
    boxW.value = Math.round(rect.value.w / m.scale)
    boxH.value = Math.round(rect.value.h / m.scale)
  }
}

async function onUp(e) {
  if (!drawing.value) return
  drawing.value = false
  const r = rect.value
  if (!r || r.w < MIN_SIDE || r.h < MIN_SIDE) {
    // 单击或误触：不当成框选，留在框选状态让用户重画
    rect.value = null
    return
  }
  const m = measureVideo()
  const layer = layerRef.value
  if (!m || !m.scale || !layer) {
    ElMessage.warning('画面尺寸未知，请等视频画面出来后再框选')
    rect.value = null
    return
  }

  /*
   * 起点与终点都要走同一个「屏幕 → 原始分辨率」映射。
   *
   * 不能用「屏幕像素差 ÷ scale」：起止点各自套一次 toNative 才能正确扣掉黑边偏移，
   * 否则 object-fit: contain 的黑边会被算进框的宽高里（黑边越宽，框越大）。
   */
  const lr = layer.getBoundingClientRect()
  const p1 = toNative(lr.left + startX, lr.top + startY, m)
  const p2 = toNative(e.clientX, e.clientY, m)

  const lenX = Math.abs(p2.x - p1.x)
  const lenY = Math.abs(p2.y - p1.y)
  if (lenX < 1 || lenY < 1) {
    rect.value = null
    return
  }

  await send(m, Math.round((p1.x + p2.x) / 2), Math.round((p1.y + p2.y) / 2), lenX, lenY)
}

async function send(m, midX, midY, lenX, lenY) {
  sending.value = true
  /*
   * length/width 传视频原始分辨率，而不是 CSS 尺寸。
   *
   * 标准要求「平台应提供画面大小：播放窗口长度像素值和播放窗口宽度像素值」，
   * 目的是让设备能除以画面尺寸得到框的相对位置。只要 length/width 与
   * MidPointX/... 处于同一坐标系，设备算出的相对位置就一致 —— 用原始分辨率
   * 比用 CSS 尺寸更稳，因为它与播放器窗口大小无关，设备也能与自己编码输出的尺寸对上。
   */
  const payload = {
    serial: props.serial,
    code: props.code || props.serial,
    length: Math.round(m.nativeW),
    width: Math.round(m.nativeH),
    midpointx: midX,
    midpointy: midY,
    lengthx: Math.round(lenX),
    lengthy: Math.round(lenY)
  }
  try {
    const res = props.mode === 'dragzoom'
      ? await controlApi.dragZoomIn(payload)
      : await controlApi.targetTrack({ ...payload, target_track: 'Manual' })
    report(res, props.mode === 'dragzoom' ? '拉框放大' : '框选跟踪')
    emit('sent', { ok: true, res })
    close()
  } catch (err) {
    ElMessage.error(err.message || '下发失败')
    // 失败保留框，用户可重试或微调；不自动关闭
    rect.value = null
  } finally {
    sending.value = false
  }
}

/**
 * 按后端返回的应答类型给提示。
 *
 * 三种情况必须分开说，不能一律「成功」：
 * OK = 设备受理了（但动作是否生效要看画面）；ERROR = 设备拒绝；未答 = 设备没回话。
 */
function report(res, action) {
  if (!res) {
    ElMessage.success(`${action}指令已下发`)
    return
  }
  if (res.answered && res.result === 'OK') {
    ElMessage.success(`${action}：设备已受理`)
  } else if (res.answered) {
    ElMessage.warning(`${action}：设备拒绝（${res.result || 'ERROR'}）`)
  } else {
    ElMessage.warning(`${action}指令已下发，设备未回应答（多数设备如此，以画面变化为准）`)
  }
}

function cancel() {
  if (sending.value) return
  rect.value = null
  drawing.value = false
  close()
}

function close() {
  emit('update:active', false)
}

/** Esc 退出框选，与「取消」按钮一致。 */
function onKeydown(e) {
  if (e.key === 'Escape' && props.active) {
    e.stopPropagation()
    cancel()
  }
}

watch(() => props.active, (on) => {
  rect.value = null
  drawing.value = false
  if (on) window.addEventListener('keydown', onKeydown)
  else window.removeEventListener('keydown', onKeydown)
})

onUnmounted(() => {
  window.removeEventListener('keydown', onKeydown)
})
</script>

<style scoped>
.track-layer {
  position: absolute;
  inset: 0;
  z-index: 20;
  cursor: crosshair;
  /* 透明但接管指针：不接管的话拖拽会被下面的播放器吃掉 */
  background: transparent;
  touch-action: none;
}

.track-layer.is-drawing {
  cursor: crosshair;
}

.track-mask {
  position: absolute;
  inset: 0;
  pointer-events: none;
}

/* 选框：只描边 + 淡蓝填充，不做整屏压暗 —— 压暗会让用户看不清框内的目标 */
.track-rect {
  position: absolute;
  border: 2px solid #2f6fed;
  background: rgba(47, 111, 237, 0.12);
  box-shadow: 0 0 0 1px rgba(255, 255, 255, 0.6) inset;
  border-radius: 2px;
  display: flex;
  align-items: flex-end;
  justify-content: flex-end;
}

.track-size {
  margin: 2px 4px;
  padding: 0 4px;
  font-size: 11px;
  line-height: 16px;
  color: #fff;
  background: rgba(0, 0, 0, 0.55);
  border-radius: 2px;
  font-variant-numeric: tabular-nums;
}

.track-tip {
  position: absolute;
  left: 50%;
  top: 12px;
  transform: translateX(-50%);
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 5px 10px;
  font-size: 12px;
  color: #fff;
  background: rgba(0, 0, 0, 0.62);
  border-radius: 14px;
  white-space: nowrap;
  pointer-events: none;
}

.track-tip-cancel {
  pointer-events: auto;
  border: none;
  background: rgba(255, 255, 255, 0.18);
  color: #fff;
  font-size: 12px;
  line-height: 18px;
  padding: 0 8px;
  border-radius: 9px;
  cursor: pointer;
}

.track-tip-cancel:hover {
  background: rgba(255, 255, 255, 0.3);
}
</style>
