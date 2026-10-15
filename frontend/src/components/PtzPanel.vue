<template>
  <div class="ptz-panel">
    <!-- 云台方向盘：按住持续转动，松开停止（全局 pointerup 兜底，见脚本） -->
    <div class="ptz-grid">
      <el-button
        v-for="d in DIRECTIONS"
        :key="d.cmd"
        class="ptz-btn"
        :style="{ gridArea: d.area }"
        :disabled="inactive"
        @pointerdown="beginMove(d.cmd, $event)"
      >
        <el-icon :size="18"><component :is="d.icon" /></el-icon>
      </el-button>

      <div class="ptz-center" :style="{ gridArea: 'c' }">
        <el-icon :size="20" color="#909399"><Aim /></el-icon>
      </div>
    </div>

    <!--
      变倍与光圈。

      变倍曾有两处入口：方向盘下方两个放大镜按钮 + 这里的「近焦 / 远焦」。
      但两者最终下发的 PTZ 指令逐字节相同（都走 PtzEncoder.ZOOM_IN/ZOOM_OUT），
      且方向盘下那两个按钮的 grid-area 名（zin/zout）在 grid-template-areas
      里并不存在，被浏览器自动排到 3×3 网格之外，看起来像游离的怪按钮。
      现合并为一对 +/−，与方向键统一为「按住持续、松开停止」。
    -->
    <div class="ptz-row">
      <el-button-group>
        <el-button
          size="small"
          :disabled="inactive"
          title="放大"
          @pointerdown="beginMove('zoomin', $event)"
        >
          <el-icon><Plus /></el-icon>
        </el-button>
        <el-button
          size="small"
          :disabled="inactive"
          title="缩小"
          @pointerdown="beginMove('zoomout', $event)"
        >
          <el-icon><Minus /></el-icon>
        </el-button>
      </el-button-group>
      <el-button-group>
        <el-button size="small" :disabled="inactive" @click="cmd('fi', { command: 'irisin' })">
          光圈+
        </el-button>
        <el-button size="small" :disabled="inactive" @click="cmd('fi', { command: 'irisout' })">
          光圈−
        </el-button>
      </el-button-group>
    </div>

    <!-- 预置位 -->
    <div class="ptz-section">
      <div class="section-title">预置位</div>
      <div class="ptz-row">
        <el-input-number v-model="presetNo" :min="1" :max="255" size="small" controls-position="right" style="width: 100px" />
        <el-button size="small" type="primary" :disabled="inactive" @click="cmd('preset', { command: 'set', preset: presetNo })">
          设置
        </el-button>
        <el-button size="small" :disabled="inactive" @click="cmd('preset', { command: 'goto', preset: presetNo })">
          调用
        </el-button>
        <el-button size="small" type="danger" plain :disabled="inactive" @click="cmd('preset', { command: 'remove', preset: presetNo })">
          删除
        </el-button>
      </div>
    </div>

    <!-- 功能开关 -->
    <div class="ptz-section">
      <div class="section-title">设备功能</div>
      <div class="ptz-row wrap">
        <el-button size="small" :disabled="inactive" @click="cmd('wiper', { command: 'on' })">
          <el-icon><Umbrella /></el-icon> 雨刷开
        </el-button>
        <el-button size="small" :disabled="inactive" @click="cmd('wiper', { command: 'off' })">
          雨刷关
        </el-button>
        <el-button size="small" :disabled="inactive" @click="cmd('fillLight', { command: 'on' })">
          <el-icon><Sunny /></el-icon> 补光开
        </el-button>
        <el-button size="small" :disabled="inactive" @click="cmd('fillLight', { command: 'off' })">
          补光关
        </el-button>
      </div>
      <div class="ptz-row wrap mt-2">
        <el-button size="small" :disabled="inactive" @click="cmd('record', { command: 'start' })">
          <el-icon><VideoCamera /></el-icon> 设备录像
        </el-button>
        <el-button size="small" :disabled="inactive" @click="cmd('record', { command: 'stop' })">
          停止录像
        </el-button>
        <el-button size="small" :disabled="inactive" @click="cmd('guard', { command: 'setguard' })">
          <el-icon><Lock /></el-icon> 布防
        </el-button>
        <el-button size="small" :disabled="inactive" @click="cmd('guard', { command: 'resetguard' })">
          撤防
        </el-button>
      </div>
    </div>

    <!-- 高级操作 -->
    <div class="ptz-section">
      <div class="section-title">高级操作</div>
      <div class="ptz-row wrap">
        <el-button size="small" :disabled="inactive" @click="cmd('iframe', {}, '已下发关键帧请求')">
          <el-icon><Picture /></el-icon> 强制关键帧
        </el-button>
        <el-popconfirm title="确定重启该设备？" @confirm="cmd('teleBoot', {}, '重启指令已下发')">
          <template #reference>
            <el-button size="small" type="warning" plain :disabled="inactive">
              <el-icon><RefreshRight /></el-icon> 远程重启
            </el-button>
          </template>
        </el-popconfirm>
        <el-popconfirm title="确定格式化设备存储卡？数据将丢失" @confirm="cmd('formatSdCard', { sdCardId: 1 }, '格式化指令已下发')">
          <template #reference>
            <el-button size="small" type="danger" plain :disabled="inactive">
              <el-icon><Delete /></el-icon> 格式化存储卡
            </el-button>
          </template>
        </el-popconfirm>
      </div>
    </div>

    <!-- 语音喊话 -->
    <div class="ptz-section">
      <div class="section-title">语音喊话</div>
      <el-button
        :type="talking ? 'danger' : 'primary'"
        :disabled="inactive"
        style="width: 100%"
        @click="toggleTalk"
      >
        <el-icon><Microphone /></el-icon>
        {{ talking ? '停止喊话' : '开始喊话' }}
      </el-button>
      <div v-if="talking" class="muted mt-1">正在推流设备音频通道…</div>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { controlApi } from '@/api'

const props = defineProps({
  /** 设备编码 */
  serial: { type: String, default: '' },
  /** 通道编码 */
  code: { type: String, default: '' },
  /** 是否禁用（如设备离线） */
  disabled: { type: Boolean, default: false }
})

/**
 * 按钮是否不可用。
 *
 * <p>判据只能是「外部禁用」或「未选通道」：模板里曾写 {@code disabled || !channel}，
 * 而 {@code channel} 并非本组件的 prop（只有 {@code serial}/{@code code}），
 * 在 {@code <script setup>} 中解析为 undefined，{@code !channel} 恒为 true，
 * 导致整个面板的按钮永久禁用、点不动。
 */
const inactive = computed(() => props.disabled || !props.serial)

/**
 * 云台速度（0~255）。
 *
 * <p>固定值，界面上不提供调节：速度对云台是调试参数而非日常操作项。
 * 接口 {@code /control/ptz} 的 {@code speed} 参数保持不变，后端仍做 0~255 夹取，
 * 日后需要时可随时改为可配置。
 */
const SPEED = 129

const presetNo = ref(1)
const talking = ref(false)

/** 方向/变倍按钮当前是否处于「按下持续」状态，避免重复发 stop。 */
const moving = ref(false)

/** 八方向 + 变倍按钮布局。 */
const DIRECTIONS = [
  { cmd: 'upleft', icon: 'TopLeft', area: 'ul' },
  { cmd: 'up', icon: 'Top', area: 'u' },
  { cmd: 'upright', icon: 'TopRight', area: 'ur' },
  { cmd: 'left', icon: 'Back', area: 'l' },
  { cmd: 'right', icon: 'Right', area: 'r' },
  { cmd: 'downleft', icon: 'BottomLeft', area: 'dl' },
  { cmd: 'down', icon: 'Bottom', area: 'd' },
  { cmd: 'downright', icon: 'BottomRight', area: 'dr' }
]

/** 云台动作键名 → 接口路径。 */
const ACTIONS = {
  ptz: controlApi.ptz,
  fi: controlApi.fi,
  preset: controlApi.preset,
  wiper: controlApi.wiper,
  fillLight: controlApi.fillLight,
  record: controlApi.record,
  guard: controlApi.guard,
  iframe: controlApi.iframe,
  teleBoot: controlApi.teleBoot,
  formatSdCard: controlApi.formatSdCard,
  talk: controlApi.talk
}

/**
 * 按下开始转动。
 *
 * <p>用 pointer 事件统一鼠标/触摸，不必分别绑 mouse* 与 touch*。
 * {@code setPointerCapture} 让指针移出按钮后仍归属本元素，
 * 松开时按钮能收到 pointerup（但停止不依赖它，见全局兜底）。
 */
function beginMove(command, event) {
  if (inactive.value) {
    return
  }
  if (event?.currentTarget?.setPointerCapture && event.pointerId !== undefined) {
    try {
      event.currentTarget.setPointerCapture(event.pointerId)
    } catch (e) {
      // 指针已释放等情况下会抛错，不影响后续下发指令
    }
  }
  moving.value = true
  send('ptz', { command, speed: SPEED }, null, true)
}

/**
 * 松开停止。
 *
 * <p><b>必须判 moving</b>：早期实现把 stop 直接绑在 {@code @mouseleave} 上，
 * 鼠标只是划过按钮（从未按下）也会向后端发一条 {@code command=stop}。
 * 且 {@code mouseleave} 在<b>禁用按钮上依然触发</b>（浏览器只屏蔽 click/pointer
 * 事件），所以面板一禁用，鼠标划过反而开始刷 stop 请求。
 * 只有确实下发过转动指令且尚未停止时才补发 stop。
 */
function endMove() {
  if (!moving.value) {
    return
  }
  moving.value = false
  send('ptz', { command: 'stop' }, null, true)
}

/** 标签页切到后台时停止：用户此时看不到画面，继续转动没有意义。 */
function onVisibilityChange() {
  if (document.visibilityState === 'hidden') {
    endMove()
  }
}

onMounted(() => {
  // 全局兜底：指针在按钮外释放（拖出去松手）、指针被系统取消、
  // 窗口失去焦点（Alt+Tab）时都要停，否则云台会一直转到设备自身超时。
  // endMove 内部有 moving 守卫，无关的 pointerup 不会产生多余请求。
  window.addEventListener('pointerup', endMove)
  window.addEventListener('pointercancel', endMove)
  window.addEventListener('blur', endMove)
  document.addEventListener('visibilitychange', onVisibilityChange)
})

onUnmounted(() => {
  window.removeEventListener('pointerup', endMove)
  window.removeEventListener('pointercancel', endMove)
  window.removeEventListener('blur', endMove)
  document.removeEventListener('visibilitychange', onVisibilityChange)
  // 面板关闭时若仍在转动，补发 stop
  endMove()
})

/**
 * 下发控制指令。
 *
 * @param action  动作名（见 ACTIONS）
 * @param extra   附加参数
 * @param okMsg   成功提示（null 表示静默）
 * @param silent  是否静默失败（用于 stop 等高频指令）
 */
async function cmd(action, extra = {}, okMsg = '指令已下发', silent = false) {
  return send(action, extra, okMsg, silent)
}

async function send(action, extra = {}, okMsg = '指令已下发', silent = false) {
  if (!props.serial) {
    if (!silent) ElMessage.warning('请先选择通道')
    return
  }
  const fn = ACTIONS[action]
  if (!fn) {
    if (!silent) ElMessage.error('未实现的控制指令：' + action)
    return
  }
  try {
    await fn({ serial: props.serial, code: props.code, ...extra })
    if (okMsg && !silent) ElMessage.success(okMsg)
  } catch (e) {
    if (!silent) ElMessage.error(e.message || '控制指令下发失败')
  }
}

async function toggleTalk() {
  if (!props.serial) {
    ElMessage.warning('请先选择通道')
    return
  }
  if (talking.value) {
    await controlApi.talk({ serial: props.serial, code: props.code })
    talking.value = false
    ElMessage.success('已停止喊话')
    return
  }
  try {
    await controlApi.talk({ serial: props.serial, code: props.code })
    talking.value = true
    ElMessage.success('已开始喊话')
  } catch (e) {
    ElMessage.error(e.message || '喊话启动失败')
  }
}
</script>

<style scoped>
.ptz-panel {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.ptz-grid {
  display: grid;
  grid-template-columns: repeat(3, 44px);
  grid-template-rows: repeat(3, 44px);
  grid-template-areas:
    'ul u  ur'
    'l  c  r'
    'dl d  dr';
  gap: 6px;
  justify-content: center;
}

.ptz-btn {
  width: 44px;
  height: 44px;
  padding: 0;
  margin: 0 !important;
}

.ptz-center {
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--ink-50);
  border-radius: 6px;
}

.ptz-row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.ptz-row.wrap {
  flex-wrap: wrap;
}

.ptz-section {
  border-top: 1px solid var(--border-color);
  padding-top: 14px;
}

.section-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--ink-900);
  margin-bottom: 10px;
}

</style>
