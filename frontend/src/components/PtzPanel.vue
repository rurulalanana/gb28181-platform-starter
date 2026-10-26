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

      <!--
        中心键 = 语音喊话开关。

        原先是一个不可点的装饰图标，喊话单独占一个 Tab —— 340px 宽的侧栏里
        四个 Tab 已经挤满一行，而喊话只有一个按钮，放进去浪费一整个面板高度。
        挪到方向盘正中：位置显眼、不占额外高度，语义上也贴近"对着画面说话"。
        开启时按钮转红，状态一眼可见（不再需要额外的提示文字）。
      -->
      <el-button
        :style="{ gridArea: 'c' }"
        class="ptz-btn ptz-talk"
        :class="{ 'is-talking': talking }"
        :type="talking ? 'danger' : 'default'"
        :disabled="inactive"
        :title="talking ? '停止喊话' : '开始喊话'"
        @click="toggleTalk"
      >
        <el-icon :size="18"><Microphone /></el-icon>
      </el-button>
    </div>

    <!--
      变倍。

      变倍曾有三处入口：方向盘下方两个放大镜按钮、这里的「近焦 / 远焦」、
      以及一对「光圈 + / −」。三者最终下发的 PTZ 指令逐字节相同
      （全走 PtzEncoder.ZOOM_IN/ZOOM_OUT 的 0x10 / 0x20），按下任何一个
      都是同一个「变焦」动作。现合并为一对 +/−，与方向键统一为
      「按住持续、松开停止」。

      <p><b>光圈按钮已删除</b>：GB/T 28181 附录 A.3 的指令码里<b>没有光圈位</b>
      （只有四方向 + 变倍±），它此前被映射到变倍位，所以按「光圈+」画面会
      放大 —— 那是变焦，不是光圈。真正的光圈只能走厂商私有指令或 ONVIF，
      保留一对假按钮只会先让用户以为「光圈坏了」，再让运维去查设备。
    -->
    <div class="ptz-row ptz-zoom-row">
      <el-button-group class="zoom-group">
        <el-button
          size="small"
          :disabled="inactive"
          title="放大（变倍+）"
          @pointerdown="beginMove('zoomin', $event)"
        >
          <el-icon><Plus /></el-icon>
        </el-button>
        <el-button
          size="small"
          :disabled="inactive"
          title="缩小（变倍−）"
          @pointerdown="beginMove('zoomout', $event)"
        >
          <el-icon><Minus /></el-icon>
        </el-button>
      </el-button-group>
    </div>

    <!--
      精简模式（实时预览页）：只保留方向 + 变倍/光圈，以下运维功能不下放。

      完整模式下运维功能用 <b>Tab 页</b>而不是折叠面板：
      右侧栏只有 ~340px 宽，四组折叠标题占 4×38px，展开后还要在栏里
      上下滚动才能找到按钮；Tab 只占一行标题栏（~40px），
      内容区固定位置、不撑高面板，切换也不用滚动。
    -->
    <template v-if="!simple">
    <el-tabs v-model="activeSection" class="ptz-tabs">
      <!-- 预置位 -->
      <el-tab-pane label="预置位" name="preset">
        <div class="ptz-preset">
          <div class="ptz-row">
            <span class="preset-label">编号</span>
            <el-input-number
              v-model="presetNo"
              :min="1"
              :max="255"
              size="small"
              controls-position="right"
              class="preset-input"
            />
          </div>
          <el-button-group class="preset-actions">
            <el-button size="small" type="primary" :disabled="inactive" @click="cmd('preset', { command: 'set', preset: presetNo })">
              设置
            </el-button>
            <el-button size="small" :disabled="inactive" @click="cmd('preset', { command: 'goto', preset: presetNo })">
              调用
            </el-button>
            <el-button size="small" type="danger" plain :disabled="inactive" @click="cmd('preset', { command: 'remove', preset: presetNo })">
              删除
            </el-button>
          </el-button-group>

          <!--
            设备侧预置位列表。

            <p>平台早就能下发 PresetQuery，但设备应答此前被丢弃、
            前端也没有查询入口，所以「设备明明有预置位，界面却什么都看不到」。
            现在的流程：点「刷新」→ POST fetchpreset → 轮询 GET presetlist
            等设备应答（SIP 是异步的，一问一答之间必然有间隔）。
            点列表中的条目直接「调用」，比先在输入框里敲编号高效。
          -->
          <div class="preset-head">
            <span class="preset-label">设备预置位</span>
            <span v-if="presets.length" class="preset-count">{{ presets.length }} 个</span>
            <el-button
              size="small"
              text
              :loading="presetLoading"
              :disabled="!serial"
              title="向设备查询预置位列表"
              @click="refreshPresets"
            >
              <el-icon><RefreshRight /></el-icon> 刷新
            </el-button>
          </div>
          <div v-if="presets.length" class="preset-list">
            <div
              v-for="p in presets"
              :key="p.presetId"
              class="preset-item"
              :title="`调用预置位 ${p.presetId}${p.deviceName ? ' · ' + p.deviceName : ''}`"
              @click="gotoPreset(p)"
            >
              <span class="preset-no">{{ p.presetId }}</span>
              <span class="preset-name">{{ p.name }}</span>
              <span v-if="p.status" class="preset-status">{{ p.status }}</span>
            </div>
          </div>
          <p v-else-if="presetQueriedAt" class="preset-empty">设备上没有预置位</p>
          <p v-else class="preset-empty">点「刷新」向设备查询</p>
        </div>
      </el-tab-pane>

      <!-- 设备功能 -->
      <el-tab-pane label="设备" name="device">
        <!--
          用 grid 而不是 flex-wrap。
          flex-wrap 按按钮的<b>自然宽度</b>换行，长一点的词会挤掉后面的按钮 ——
          实测「雨刷开/雨刷关/补光开」占满一行后「补光关」被单独甩到第二行，
          视觉上参差不齐。固定两列等宽后每行严格对齐，也更好点。
        -->
        <div class="ptz-actions">
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
      </el-tab-pane>

      <!-- 高级操作 -->
      <el-tab-pane label="高级" name="advanced">
        <div class="ptz-actions">
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
        </div>
        <!--
          破坏性操作单独占一行，不放进上面的两列网格。
          el-popconfirm 的 class 落在它内部渲染的按钮上，
          scoped 的 grid-column 匹配不稳（实测仍是单列宽度），
          包一层 div 最省事，视觉上也正好把高风险动作隔开。
        -->
        <div class="ptz-actions-danger">
          <el-popconfirm title="确定格式化设备存储卡？数据将丢失" @confirm="cmd('formatSdCard', { sdCardId: 1 }, '格式化指令已下发')">
            <template #reference>
              <el-button size="small" type="danger" plain :disabled="inactive">
                <el-icon><Delete /></el-icon> 格式化存储卡
              </el-button>
            </template>
          </el-popconfirm>
        </div>
      </el-tab-pane>
    </el-tabs>
    </template>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { controlApi, deviceApi } from '@/api'

const props = defineProps({
  /** 设备编码 */
  serial: { type: String, default: '' },
  /** 通道编码 */
  code: { type: String, default: '' },
  /** 是否禁用（如设备离线） */
  disabled: { type: Boolean, default: false },
  /**
   * 精简模式：只显示方向盘与变倍/光圈。
   *
   * <p>预置位、设备功能（雨刷/补光/录像/布防）、高级操作（关键帧/重启/格式化）、
   * 语音喊话都是运维动作（重启、格式化风险高），不放在实时预览页，
   * 需要时到通道管理页操作。
   */
  simple: { type: Boolean, default: false }
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

/**
 * 设备侧预置位列表。
 *
 * <p>数据结构见后端 {@code PresetListVo}：编号以设备为准，
 * {@code name} 已由服务端合并了平台自定义名称（有自定义就用自定义的）。
 */
const presets = ref([])
/** 最后一次收到设备应答的时间；`null` 表示从未查到过。 */
const presetQueriedAt = ref(null)
/** 查询中（含等待设备应答的轮询阶段）。 */
const presetLoading = ref(false)
/** 上一次查询得到的应答时间，用来判断「轮询到的是不是本次的结果」。 */
let presetBaseline = null

/**
 * 当前选中的运维功能 Tab。
 *
 * <p>默认「预置位」：它是最常用的一组（调预置位比点方向键高效得多），
 * 而且内容只有两行，不会把面板撑高。
 */
const activeSection = ref('preset')

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

/** 把后端返回的应用到界面。 */
function applyPresetList(vo) {
  if (!vo) return
  presets.value = Array.isArray(vo.items) ? vo.items : []
  presetQueriedAt.value = vo.queriedAt || null
}

/**
 * 向设备发起一次预置位查询，并等它的应答。
 *
 * <h3>为什么要轮询</h3>
 * 预置位查询走 SIP {@code MESSAGE}，接口返回只代表<b>查询已发出</b>，
 * 设备应答要过几百毫秒到几秒才到（还要经平台解析落缓存）。
 * 所以这里：先 POST 下发 → 再轮询 GET，直到 {@code queriedAt} 相比上一次变化。
 *
 * <p><b>基线比较不可省</b>：设备卡住不回应答时，缓存里仍是上一次的结果，
 * 只看「有没有数据」会立刻拿到旧数据并显示成功 —— 用户点刷新看到的是老列表，
 * 以为查询生效了。用时间戳判断才能区分「本次的应答」与「上一次的残留」。
 */
async function refreshPresets() {
  if (!props.serial || presetLoading.value) return
  presetLoading.value = true
  presetBaseline = presetQueriedAt.value
  try {
    await deviceApi.fetchPreset({ serial: props.serial, code: props.code })
    /*
     * 15 × 700ms ≈ 10.5 秒。
     *
     * 实测该海康球机从收到 PresetQuery 到应答要 3~5 秒（比常见的几百毫秒慢），
     * 3 秒的等待会误判成「设备未应答」。宁可多等几秒，
     * 也不要让用户看到「查不到」这种假失败 —— 那会引导他去查设备。
     */
    for (let i = 0; i < 15; i++) {
      await new Promise((r) => setTimeout(r, 700))
      const vo = await deviceApi.presetList(props.serial, props.code)
      if (vo && vo.queriedAt && vo.queriedAt !== presetBaseline) {
        applyPresetList(vo)
        ElMessage.success(
          presets.value.length ? `已查到 ${presets.value.length} 个预置位` : '设备上报没有预置位'
        )
        return
      }
    }
    // 超时：保留旧数据但明确告知，避免用户以为「设备上就是这些」
    ElMessage.warning('设备未应答预置位查询（可能不支持该指令或当前不可达）')
  } catch (e) {
    ElMessage.error(e.message || '预置位查询失败')
  } finally {
    presetLoading.value = false
  }
}

/** 点击列表条目：填进编号并直接调用。 */
async function gotoPreset(p) {
  presetNo.value = p.presetId
  await cmd('preset', { command: 'goto', preset: p.presetId }, `已调用预置位 ${p.presetId}`)
}

/**
 * 换通道时重新拉一次。
 *
 * <p>不做「立即向下发查询」：打开预览时面板已经渲染，
 * 每次点播都多发一条 SIP 会打扰设备；先用缓存（若有）显示，
 * 用户想要最新的点一下「刷新」即可。
 */
watch(
  () => [props.serial, props.code],
  async ([serial, code]) => {
    presets.value = []
    presetQueriedAt.value = null
    if (!serial || props.simple) return
    try {
      applyPresetList(await deviceApi.presetList(serial, code))
    } catch (e) {
      // 缓存为空时忽略：界面显示「点刷新向设备查询」即可
    }
  },
  { immediate: true }
)

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

/*
 * 方向盘 3×3。
 *
 * 按钮从 44px 收到 40px、间距从 6px 放到 8px：
 * 总宽 3×40 + 2×8 = 136px（原来 144px），占位更小但间隙更大，
 * 视觉上反而更疏朗 —— 44/6 的组合在 300px 宽的栏里显得"糊成一块"。
 */
.ptz-grid {
  display: grid;
  grid-template-columns: repeat(3, 40px);
  grid-template-rows: repeat(3, 40px);
  grid-template-areas:
    'ul u  ur'
    'l  c  r'
    'dl d  dr';
  gap: 8px;
  justify-content: center;
}

.ptz-btn {
  width: 40px;
  height: 40px;
  padding: 0;
  margin: 0 !important;
}

/*
 * 中心键（语音喊话）。
 *
 * <p>与方向键同尺寸、同圆角。默认中性底色，
 * 开启喊话时由 el-button 的 type="danger" 接管配色，
 * 这里只补一个呼吸光晕，提醒"麦克风还开着"——喊话是持续推流的动作，
 * 忘了关会让设备侧一直占用音频通道。
 */
.ptz-talk.is-talking {
  animation: talk-pulse 1.6s ease-in-out infinite;
}

/*
 * 默认态下方向键都是灰字，中间再放一个灰麦克风就"沉"进背景里看不见了。
 * 给它一点主题色，明确这是可点的功能键而不是装饰图标。
 */
.ptz-talk:not(.is-talking) :deep(.el-icon) {
  color: var(--accent-ink);
}

.ptz-talk.is-talking :deep(.el-icon) {
  color: #fff;
}

@keyframes talk-pulse {
  0%, 100% { box-shadow: 0 0 0 0 rgba(245, 108, 108, 0.55); }
  50% { box-shadow: 0 0 0 6px rgba(245, 108, 108, 0); }
}

.ptz-row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.ptz-row.wrap {
  flex-wrap: wrap;
}

/*
 * 运维动作按钮：固定两列等宽网格。
 *
 * 【为什么不用 flex-wrap】
 * flex-wrap 按按钮的自然宽度换行 —— 「雨刷开/雨刷关/补光开」正好塞满一行，
 * 「补光关」就被甩到第二行单独一个，看起来像排错了。
 * 固定两列后每行严格对齐，间距也一致。
 */
.ptz-actions {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;
}

/* 网格里的按钮撑满单元格（否则按内容宽度左对齐，又歪了） */
.ptz-actions > :deep(.el-button),
.ptz-actions > :deep(.el-popconfirm) > .el-button {
  width: 100%;
  margin-left: 0;
}

/*
 * popconfirm 的触发按钮是它的子元素，grid 的直接子项是 el-popconfirm。
 * el-popconfirm 默认 display: inline-block，会把按钮包一层导致宽度不撑满，
 * 这里让它也参与网格布局。
 */
.ptz-actions > :deep(.el-popconfirm) {
  display: block;
  min-width: 0;
}

/*
 * 破坏性操作单独一行，与上面的两列按钮隔开 8px。
 * 宽度撑满整行，避免和普通按钮同宽造成误点。
 */
.ptz-actions-danger {
  margin-top: 8px;
}

.ptz-actions-danger :deep(.el-button) {
  width: 100%;
}

/*
 * 变倍：按钮组撑满整栏宽。
 *
 * 原来这里是「变倍 + 光圈」两组平分栏宽。光圈按钮删掉后只剩一组，
 * 若仍按自然宽度（约 85px）排在 300px 的栏里，左边贴边、右边空一大块，
 * 看着像“没排完”；改成 flex:1 后与上面的方向盘、下面的折叠面板对齐。
 */
.ptz-zoom-row {
  gap: 8px;
}

.ptz-zoom-row .zoom-group {
  flex: 1;
  display: flex;
}

/* button-group 内的按钮撑满各自那一半 */
.ptz-zoom-row .zoom-group :deep(.el-button) {
  flex: 1;
  padding-left: 0;
  padding-right: 0;
}

/* 预置位：编号一行、操作按钮等分一行 */
.ptz-preset {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

/* 编号标签固定宽度，让输入框左边界与下方按钮左边界对齐 */
.preset-label {
  flex: 0 0 auto;
  font-size: var(--fs-sm);
  color: var(--text-secondary);
}

/* 输入框吃掉剩余宽度（原固定 100px，右侧会空一截，与按钮行对不齐） */
.preset-input {
  flex: 1;
  min-width: 0;
}

/* 三个按钮等分整行宽度，与上面的输入框左右边界对齐 */
.preset-actions {
  display: flex !important;
}

.preset-actions :deep(.el-button) {
  flex: 1;
  padding-left: 0;
  padding-right: 0;
}

/*
 * 设备预置位列表。
 *
 * 列表限高 + 内部滚动：设备上可能有几十个预置位（有些球机默认 255 个全开），
 * 不限高会把整个面板撑到需要滚页面，而用户真正要在意的方向盘会被挤出视口。
 * 140px ≈ 显示 5 行，再多就滚。
 */
.preset-head {
  display: flex;
  align-items: center;
  gap: 6px;
  padding-top: 2px;
  border-top: 1px dashed var(--border-color);
}

.preset-head .preset-label {
  margin-right: auto;
}

.preset-count {
  font-size: var(--fs-xs);
  color: var(--text-secondary);
}

.preset-list {
  max-height: 140px;
  overflow-y: auto;
  border: 1px solid var(--border-color);
  border-radius: 4px;
}

.preset-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 5px 8px;
  font-size: var(--fs-sm);
  cursor: pointer;
  transition: background-color 0.15s;
}

.preset-item + .preset-item {
  border-top: 1px solid var(--border-color);
}

.preset-item:hover {
  background: var(--fill-color-light, rgba(64, 128, 255, 0.08));
}

/* 编号定宽等宽字体：1 和 128 上下对齐，扫一眼就能找到目标编号 */
.preset-no {
  flex: 0 0 28px;
  font-variant-numeric: tabular-nums;
  color: var(--text-secondary);
  text-align: right;
}

.preset-name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.preset-status {
  flex: 0 0 auto;
  font-size: var(--fs-xs);
  color: var(--text-secondary);
  opacity: 0.8;
}

.preset-empty {
  margin: 0;
  padding: 10px 0;
  text-align: center;
  font-size: var(--fs-xs);
  color: var(--text-secondary);
}

/*
 * 运维功能 Tab。
 *
 * 相比折叠面板：标签栏只占一行 34px（四个折叠标题要 4×38px=152px），
 * 内容区位置固定、切换不改变面板高度，右侧栏不需要上下滚动。
 *
 * 标签用短词（预置位 / 设备 / 高级 / 喊话）：340px 的栏里四个双字标签
 * 正好一行放下；「设备功能」「高级操作」这种四字标签会被挤到换行。
 */
.ptz-tabs {
  border-top: 1px solid var(--border-color);
  padding-top: 6px;
}

.ptz-tabs :deep(.el-tabs__header) {
  margin-bottom: 12px;
}

/* 四个标签平分宽度，避免逐个按文字宽度计算时右侧空一截 */
.ptz-tabs :deep(.el-tabs__nav) {
  display: flex;
  width: 100%;
}

.ptz-tabs :deep(.el-tabs__item) {
  flex: 1;
  height: 34px;
  line-height: 34px;
  padding: 0 8px;
  text-align: center;
  font-size: var(--fs-sm);
}

/* 内容区不需要额外内边距，里面的按钮自己控制间距 */
.ptz-tabs :deep(.el-tabs__content) {
  overflow: visible;
}

</style>
