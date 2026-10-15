<template>
  <div class="ptz-panel">
    <!-- 云台方向盘 -->
    <div class="ptz-grid">
      <el-button
        v-for="d in DIRECTIONS"
        :key="d.cmd"
        class="ptz-btn"
        :style="{ gridArea: d.area }"
        :disabled="disabled || !channel"
        @mousedown="startMove(d.cmd)"
        @mouseup="stopMove"
        @mouseleave="stopMove"
        @touchstart.prevent="startMove(d.cmd)"
        @touchend.prevent="stopMove"
      >
        <el-icon :size="18"><component :is="d.icon" /></el-icon>
      </el-button>

      <div class="ptz-center" :style="{ gridArea: 'c' }">
        <el-icon :size="20" color="#909399"><Aim /></el-icon>
      </div>

      <el-button
        class="ptz-btn"
        style="grid-area: zin"
        :disabled="disabled || !channel"
        @mousedown="holdZoom('zoomin')"
        @mouseup="stopMove"
        @mouseleave="stopMove"
        @touchstart.prevent="holdZoom('zoomin')"
        @touchend.prevent="stopMove"
      >
        <el-icon :size="18"><ZoomIn /></el-icon>
      </el-button>
      <el-button
        class="ptz-btn"
        style="grid-area: zout"
        :disabled="disabled || !channel"
        @mousedown="holdZoom('zoomout')"
        @mouseup="stopMove"
        @mouseleave="stopMove"
        @touchstart.prevent="holdZoom('zoomout')"
        @touchend.prevent="stopMove"
      >
        <el-icon :size="18"><ZoomOut /></el-icon>
      </el-button>
    </div>

    <!-- 速度 -->
    <div class="ptz-speed">
      <span class="label">速度</span>
      <el-slider v-model="speed" :min="1" :max="255" :show-tooltip="false" size="small" />
      <span class="mono value">{{ speed }}</span>
    </div>

    <!-- 焦点光圈 -->
    <div class="ptz-row">
      <el-button-group>
        <el-button size="small" :disabled="disabled || !channel" @click="cmd('fi', { command: 'focusnear' })">
          近焦
        </el-button>
        <el-button size="small" :disabled="disabled || !channel" @click="cmd('fi', { command: 'focusfar' })">
          远焦
        </el-button>
        <el-button size="small" :disabled="disabled || !channel" @click="cmd('fi', { command: 'stop' })">
          停
        </el-button>
      </el-button-group>
      <el-button-group>
        <el-button size="small" :disabled="disabled || !channel" @click="cmd('fi', { command: 'irisin' })">
          光圈+
        </el-button>
        <el-button size="small" :disabled="disabled || !channel" @click="cmd('fi', { command: 'irisout' })">
          光圈−
        </el-button>
      </el-button-group>
    </div>

    <!-- 预置位 -->
    <div class="ptz-section">
      <div class="section-title">预置位</div>
      <div class="ptz-row">
        <el-input-number v-model="presetNo" :min="1" :max="255" size="small" controls-position="right" style="width: 100px" />
        <el-button size="small" type="primary" :disabled="disabled || !channel" @click="cmd('preset', { command: 'set', preset: presetNo })">
          设置
        </el-button>
        <el-button size="small" :disabled="disabled || !channel" @click="cmd('preset', { command: 'goto', preset: presetNo })">
          调用
        </el-button>
        <el-button size="small" type="danger" plain :disabled="disabled || !channel" @click="cmd('preset', { command: 'remove', preset: presetNo })">
          删除
        </el-button>
      </div>
    </div>

    <!-- 功能开关 -->
    <div class="ptz-section">
      <div class="section-title">设备功能</div>
      <div class="ptz-row wrap">
        <el-button size="small" :disabled="disabled || !channel" @click="cmd('wiper', { command: 'on' })">
          <el-icon><Umbrella /></el-icon> 雨刷开
        </el-button>
        <el-button size="small" :disabled="disabled || !channel" @click="cmd('wiper', { command: 'off' })">
          雨刷关
        </el-button>
        <el-button size="small" :disabled="disabled || !channel" @click="cmd('fillLight', { command: 'on' })">
          <el-icon><Sunny /></el-icon> 补光开
        </el-button>
        <el-button size="small" :disabled="disabled || !channel" @click="cmd('fillLight', { command: 'off' })">
          补光关
        </el-button>
      </div>
      <div class="ptz-row wrap mt-2">
        <el-button size="small" :disabled="disabled || !channel" @click="cmd('record', { command: 'start' })">
          <el-icon><VideoCamera /></el-icon> 设备录像
        </el-button>
        <el-button size="small" :disabled="disabled || !channel" @click="cmd('record', { command: 'stop' })">
          停止录像
        </el-button>
        <el-button size="small" :disabled="disabled || !channel" @click="cmd('guard', { command: 'setguard' })">
          <el-icon><Lock /></el-icon> 布防
        </el-button>
        <el-button size="small" :disabled="disabled || !channel" @click="cmd('guard', { command: 'resetguard' })">
          撤防
        </el-button>
      </div>
    </div>

    <!-- 高级操作 -->
    <div class="ptz-section">
      <div class="section-title">高级操作</div>
      <div class="ptz-row wrap">
        <el-button size="small" :disabled="disabled || !channel" @click="cmd('iframe', {}, '已下发关键帧请求')">
          <el-icon><Picture /></el-icon> 强制关键帧
        </el-button>
        <el-popconfirm title="确定重启该设备？" @confirm="cmd('teleBoot', {}, '重启指令已下发')">
          <template #reference>
            <el-button size="small" type="warning" plain :disabled="disabled || !channel">
              <el-icon><RefreshRight /></el-icon> 远程重启
            </el-button>
          </template>
        </el-popconfirm>
        <el-popconfirm title="确定格式化设备存储卡？数据将丢失" @confirm="cmd('formatSdCard', { sdCardId: 1 }, '格式化指令已下发')">
          <template #reference>
            <el-button size="small" type="danger" plain :disabled="disabled || !channel">
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
        :disabled="disabled || !channel"
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
import { ref } from 'vue'
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

const speed = ref(129)
const presetNo = ref(1)
const talking = ref(false)

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

function startMove(command) {
  send('ptz', { command, speed: speed.value })
}

/** 方向/变倍为「按下持续、松开停止」语义，松开时必须补发 stop。 */
function stopMove() {
  send('ptz', { command: 'stop' }, null, true)
}

function holdZoom(command) {
  send('ptz', { command, speed: speed.value })
}

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
  background: #f5f7fa;
  border-radius: 6px;
}

.ptz-speed {
  display: flex;
  align-items: center;
  gap: 10px;
}

.ptz-speed .label {
  color: #606266;
  font-size: 13px;
  white-space: nowrap;
}

.ptz-speed :deep(.el-slider) {
  flex: 1;
}

.ptz-speed .value {
  width: 32px;
  text-align: right;
  color: #909399;
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
  border-top: 1px solid #ebeef5;
  padding-top: 14px;
}

.section-title {
  font-size: 13px;
  font-weight: 600;
  color: #303133;
  margin-bottom: 10px;
}

</style>
