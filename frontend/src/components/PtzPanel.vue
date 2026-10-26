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
      变倍 / 光圈 / 聚焦。

      <p>三者是<b>互不相同</b>的指令：变倍 0x10/0x20、光圈 0x44/0x48、聚焦 0x41/0x42
      （标准附录 A.3.1）。早先版本误以为「光圈复用变倍位」，把光圈映射到变倍，
      按下光圈画面会缩放；同一误判也让「近焦/远焦」被并进了变倍按钮。
      指令码修正后三者必须分开暴露。

      <p>变倍要求「按住持续、松开停止」，所以用 pointerdown 而不是 click；
      光圈/聚焦是单步动作，一次点击下发一次即可。
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
    <div class="ptz-row ptz-zoom-row">
      <el-button-group class="zoom-group">
        <el-button size="small" :disabled="inactive" title="光圈放大" @click="cmd('fi', { command: 'irisin' }, '光圈 +')">
          光圈+
        </el-button>
        <el-button size="small" :disabled="inactive" title="光圈缩小" @click="cmd('fi', { command: 'irisout' }, '光圈 −')">
          光圈−
        </el-button>
      </el-button-group>
      <el-button-group class="zoom-group">
        <el-button size="small" :disabled="inactive" title="聚焦近" @click="cmd('fi', { command: 'focusnear' }, '聚焦近')">
          聚焦+
        </el-button>
        <el-button size="small" :disabled="inactive" title="聚焦远" @click="cmd('fi', { command: 'focusfar' }, '聚焦远')">
          聚焦−
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
          <!--
            预置位面板：<b>列出全部槽位</b>而只列「设备上已有的」。

            为什么这样设计：只列已有的话，用户想「把当前位置存到 6 号」
            还得先记住设备上没用到哪个编号，再在输入框里敲；
            而列出全部槽位后，空槽位就是「还能往哪儿存」的直观答案。
            有预置位的行给「调用/设置/删除」，空槽位只给「设置」——
            按钮本身就是能力边界，不需要额外说明文字。
          -->
          <div class="preset-head">
            <span class="preset-label">预置位</span>
            <span v-if="usedCount" class="preset-count">已用 {{ usedCount }}</span>
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

          <el-table
            :data="presetSlots"
            size="small"
            height="248"
            class="preset-table"
            :row-class-name="presetRowClass"
          >
            <el-table-column label="序号" width="64" align="center">
              <template #default="{ row }">
                <span class="mono">{{ row.presetId }}</span>
              </template>
            </el-table-column>
            <el-table-column label="备注" min-width="110">
              <template #default="{ row }">
                <!-- 只有「设备上存在」的槽位才有名字可改 -->
                <el-input
                  v-if="editingPreset.no === row.presetId"
                  ref="presetNameInput"
                  v-model="editingPreset.value"
                  size="small"
                  placeholder="留空=默认名称"
                  :disabled="editingPreset.saving"
                  @keydown.enter.prevent="commitPresetName(row)"
                  @keydown.esc.stop.prevent="cancelPresetName"
                  @blur="commitPresetName(row)"
                />
                <el-button
                  v-else-if="row.used"
                  link
                  type="primary"
                  class="preset-name-btn"
                  title="点击修改备注"
                  @click="startRenamePreset(row)"
                >
                  {{ row.name }}
                </el-button>
                <span v-else class="preset-name-empty">预置位 {{ row.presetId }}</span>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="158" align="center">
              <template #default="{ row }">
                <el-button
                  v-if="row.used"
                  link
                  type="primary"
                  :disabled="inactive"
                  @click="callPreset(row)"
                >
                  调用
                </el-button>
                <el-button
                  link
                  type="primary"
                  :disabled="inactive"
                  :title="row.used ? '用当前位置覆盖该预置位' : '把当前位置存到该编号'"
                  @click="savePreset(row)"
                >
                  设置
                </el-button>
                <el-button
                  v-if="row.used"
                  link
                  type="danger"
                  :disabled="inactive"
                  @click="removePreset(row)"
                >
                  删除
                </el-button>
              </template>
            </el-table-column>
          </el-table>

          <p v-if="!presetQueriedAt" class="preset-hint">
            点「刷新」向设备查询；空槽位可直接「设置」
          </p>
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
import { computed, nextTick, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
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

const talking = ref(false)

/**
 * 设备侧预置位列表（只含设备上真实存在的）。
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

/** 设备上已用的预置位个数。 */
const usedCount = computed(() => presets.value.length)

/**
 * 全部预置位槽位（固定 1~255，含未使用的空槽）。
 *
 * <p><b>为什么要补齐空槽</b>：只渲染设备上已有的预置位时，
 * 用户想「把当前位置存到 6 号」，得先自己去猜哪个编号没被占用，
 * 再在输入框里敲数字。补成连续槽位后，「还能存哪儿」一眼可见，
 * 空槽位只有一个「设置」按钮，能力边界自明。
 *
 * <p><b>为什么固定 255 而不是跟着已用编号动</b>：国标给预置位编号的上限
 * 就是 255（1 字节），设备实际支持多少个是设备自己的事，平台无法事先知道。
 * 早期的「max(8, 最大已用+1)」写法看着整齐，但有两个坑：
 * <ul>
 *   <li>设备若只支持 8 个，用户看到第 9 行会以为能存，点了才失败 —— 平台无权替设备作答；</li>
 *   <li>用户想把机位存在 100 号（跨设备统一的巡检编号）时，行还得等设置完才出现。</li>
 * </ul>
 * 固定 255 + 表内滚动，既不用猜设备能力，也保证任何编号都能直接点。
 */
const PRESET_MAX = 255

const presetSlots = computed(() => {
  const used = new Map()
  for (const p of presets.value) {
    if (!p || !Number.isFinite(p.presetId)) continue
    used.set(p.presetId, p)
  }
  const rows = []
  for (let i = 1; i <= PRESET_MAX; i++) {
    const p = used.get(i)
    rows.push({
      presetId: i,
      used: !!p,
      // 已用：优先平台自定义名，否则设备名，再否则默认名（后端已合并好）
      name: p ? p.name || `预置位 ${i}` : '',
      status: p?.status || ''
    })
  }
  return rows
})

/** 空槽位行加淡色底，扫一眼就能看出哪些还能存。 */
function presetRowClass({ row }) {
  return row.used ? '' : 'preset-row-empty'
}

/* ---------- 备注（自定义名称）行内编辑 ---------- */
const editingPreset = reactive({ no: 0, value: '', saving: false })
const presetNameInput = ref(null)

async function startRenamePreset(row) {
  if (editingPreset.saving) return
  editingPreset.no = row.presetId
  // 用「默认名」作为空值：用户看到的就是当前显示的那个名字
  editingPreset.value = row.name === `预置位 ${row.presetId}` ? '' : row.name
  await nextTick()
  const el = presetNameInput.value
  const target = Array.isArray(el) ? el[0] : el
  target?.focus?.()
  target?.select?.()
}

function cancelPresetName() {
  editingPreset.no = 0
  editingPreset.value = ''
}

/**
 * 提交备注。
 *
 * <p>回车与失焦都会走到这里，必须幂等 —— 回车后输入框被移除，
 * 若同时触发 blur 会重复提交。
 */
async function commitPresetName(row) {
  if (editingPreset.no !== row.presetId || editingPreset.saving) return
  const value = (editingPreset.value || '').trim()
  const original = row.name === `预置位 ${row.presetId}` ? '' : row.name
  if (value === original) {
    cancelPresetName()
    return
  }
  editingPreset.saving = true
  try {
    // 传空字符串 = 清除自定义名，回落到设备名或默认名
    await deviceApi.setPresetName({
      serial: props.serial,
      code: props.code || props.serial,
      preset: row.presetId,
      name: value
    })
    // 本地同步，避免为了一个名字去重新走一遍 10 秒的 SIP 查询
    const hit = presets.value.find((p) => p.presetId === row.presetId)
    if (hit) {
      hit.name = value || `预置位 ${row.presetId}`
      hit.customName = !!value
    }
    ElMessage.success('备注已保存')
    cancelPresetName()
  } catch (e) {
    ElMessage.error(e.message || '保存备注失败')
  } finally {
    editingPreset.saving = false
  }
}

/* ---------- 三个动作 ---------- */

/** 调用预置位。 */
async function callPreset(row) {
  await cmd('preset', { command: 'goto', preset: row.presetId },
    `已调用预置位 ${row.presetId}`)
}

/**
 * 设置预置位（把当前位置存到该编号）。
 *
 * <p>空槽位设置成功后要本地补一条记录：设备侧确实多了一个预置位，
 * 不补的话这一行仍是「空槽、只有设置按钮」，用户会以为没成功。
 * 名称先按默认名占位，点「刷新」可从设备拉回真实名称。
 */
async function savePreset(row) {
  const ok = await cmd('preset', { command: 'set', preset: row.presetId },
    row.used ? `已更新预置位 ${row.presetId}` : `已设置预置位 ${row.presetId}`)
  if (!ok) return
  if (!row.used) {
    presets.value = [
      ...presets.value,
      { presetId: row.presetId, name: `预置位 ${row.presetId}`, customName: false }
    ].sort((a, b) => a.presetId - b.presetId)
  }
}

/** 删除预置位（需确认：删了要重新去现场调机位才能恢复）。 */
async function removePreset(row) {
  try {
    await ElMessageBox.confirm(
      `确定删除预置位 ${row.presetId}？删除后需要重新调整机位才能恢复。`,
      '删除预置位',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
  } catch (e) {
    return
  }
  const ok = await cmd('preset', { command: 'remove', preset: row.presetId },
    `已删除预置位 ${row.presetId}`)
  if (ok) {
    presets.value = presets.value.filter((p) => p.presetId !== row.presetId)
  }
}

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
        /*
         * 再等一拍、再拉一次。
         *
         * <p>设备会把预置位<b>拆成多帧</b>上报（实测 5 个预置位 = 4 + 1 两帧），
         * 而 SN 变化才是新批次的标志 —— 第一帧到达时后端就已经开始累积了。
         * 如果此刻立刻渲染，只能看到第一帧的内容（4 个），
         * 用户会以为「又少了几个」。补拉一次把后续帧也带上。
         */
        await new Promise((r) => setTimeout(r, 500))
        const full = await deviceApi.presetList(props.serial, props.code)
        applyPresetList(
          full && (full.items?.length || 0) >= (vo.items?.length || 0) ? full : vo
        )
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
    cancelPresetName()
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
    return false
  }
  const fn = ACTIONS[action]
  if (!fn) {
    if (!silent) ElMessage.error('未实现的控制指令：' + action)
    return false
  }
  try {
    await fn({ serial: props.serial, code: props.code, ...extra })
    if (okMsg && !silent) ElMessage.success(okMsg)
    return true
  } catch (e) {
    if (!silent) ElMessage.error(e.message || '控制指令下发失败')
    return false
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
  gap: 8px;
}

.preset-label {
  flex: 0 0 auto;
  font-size: var(--fs-sm);
  color: var(--text-secondary);
}

/*
 * 预置位表头。
 *
 * 「已用 N」这个计数比「共 N 个」有用：用户真正想知道的是
 * 「还有多少空位可以存机位」，总数是固定的，不值得占版面。
 */
.preset-head {
  display: flex;
  align-items: center;
  gap: 6px;
}

.preset-head .preset-label {
  margin-right: auto;
}

.preset-count {
  font-size: var(--fs-xs);
  color: var(--text-secondary);
}

/*
 * 预置位表。
 *
 * 限高 248px（≈ 8 行）后内部滚动：球机常见 8~255 个预置位，
 * 全展开会把方向盘挤出视口，而方向盘才是这个面板的主要用途。
 * 表头固定（el-table 自带）—— 滚到 100 号时还能看见列名。
 */
.preset-table {
  width: 100%;
}

.preset-table :deep(.el-table__cell) {
  padding: 4px 0;
}

/* 序号用等宽数字：1 和 128 右对齐后竖排扫读不会跳 */
.preset-table .mono {
  font-variant-numeric: tabular-nums;
  color: var(--text-secondary);
}

/* 空槽位淡底：一眼区分「能存」和「已存」 */
.preset-table :deep(.preset-row-empty) {
  background: var(--fill-color-lighter, rgba(128, 128, 128, 0.04));
}

/* 备注做成按钮样式（可点即改），但视觉上要像普通文字，别抢操作列的注意力 */
.preset-name-btn {
  justify-content: flex-start !important;
  width: 100%;
  padding: 0 !important;
  font-weight: normal !important;
  overflow: hidden;
}

.preset-name-btn :deep(span) {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.preset-name-empty {
  display: block;
  padding: 0 2px;
  font-size: var(--fs-xs);
  color: var(--text-secondary);
  opacity: 0.7;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 操作列三个链接按钮：默认间距偏大，窄边栏里会换行 */
.preset-table :deep(.el-button + .el-button) {
  margin-left: 6px;
}

/* 按钮文字绝不折行：折了就变两行，行高忽大忽小，扫读很难受 */
.preset-table :deep(.el-button > span) {
  white-space: nowrap;
}

.preset-hint {
  margin: 0;
  font-size: var(--fs-xs);
  color: var(--text-secondary);
  text-align: center;
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
