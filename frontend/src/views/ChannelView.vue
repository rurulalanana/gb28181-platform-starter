<template>
  <div class="page is-fill">

    <el-card shadow="never">
    <div class="filter-bar">
      <el-input
        v-model="query.q"
        placeholder="通道编码 / 名称"
        clearable
        style="width: 220px"
        @keyup.enter="search"
      >
        <template #prefix><el-icon><Search /></el-icon></template>
      </el-input>
      <el-select v-model="query.online" placeholder="全部状态" clearable style="width: 130px">
        <el-option label="在线" :value="true" />
        <el-option label="离线" :value="false" />
      </el-select>
      <el-tag v-if="query.serial" closable type="info" @close="clearSerialFilter">
        <span class="mono">{{ query.serial }}</span>
      </el-tag>
      <el-button type="primary" @click="search">
        <el-icon><Search /></el-icon> 查询
      </el-button>
      <span class="filter-bar-spacer" />
      <el-button size="small" :loading="loading" @click="load">
        <el-icon><Refresh /></el-icon> 刷新
      </el-button>
    </div>

      <!-- 列结构对齐 LiveGBS 的「设备 > 通道列表」页 -->
      <el-table ref="tableRef" v-loading="loading" :data="channels" stripe table-layout="fixed">
        <el-table-column prop="channel" label="通道号" min-width="96" align="center" sortable>
          <template #default="{ row }">
            <span class="text-muted">{{ row.channel ?? '—' }}</span>
          </template>
        </el-table-column>

        <el-table-column prop="id" label="通道国标编号" min-width="168" sortable>
          <template #default="{ row }"><span class="mono code-sm">{{ row.id }}</span></template>
        </el-table-column>

        <el-table-column
          label="通道名称"
          min-width="124"
          :sort-by="(r) => r.customName || r.name || ''"
          sortable
          show-overflow-tooltip
        >
          <template #default="{ row }">
            <!-- 常驻输入框会在每行渲染「点击修改」占位符，一屏全是灰字 -->
            <el-input
              v-if="row._editing"
              :id="nameInputId(row)"
              v-model="row.customName"
              size="small"
              class="name-input"
              @keydown.enter="saveName(row)"
              @keydown.esc="cancelName(row)"
              @blur="saveName(row)"
            />
            <span
              v-else
              class="name-text"
              :class="{ 'is-editable': canUpdate, 'is-empty': !(row.customName || row.name) }"
              :title="canUpdate ? '点击修改名称' : ''"
              @click="canUpdate && startNameEdit(row)"
            >{{ row.customName || row.name || '—' }}</span>
          </template>
        </el-table-column>

        <el-table-column label="在线状态" min-width="88" align="center">
          <template #default="{ row }">
            <div class="status-cell">
              <span
                class="badge is-clickable"
                :class="row.status === 'ON' ? 'is-online' : 'is-offline'"
                title="点击查看设备详情"
                @click="openDeviceDetail(row)"
              >{{ row.status === 'ON' ? '在线' : '离线' }}</span>
              <el-icon class="traffic-icon" title="点击查看设备详情" @click="openDeviceDetail(row)">
                <Histogram />
              </el-icon>
            </div>
          </template>
        </el-table-column>

        <el-table-column label="按需直播" min-width="104" align="center">
          <template #header>
            <el-tooltip
              content="按需直播：无人观看时自动停流以节省设备带宽。与「云端录像」互斥——开启它会自动关闭云端录像。"
              placement="top"
            >
              <span class="th-with-tip">按需直播 <el-icon><QuestionFilled /></el-icon></span>
            </el-tooltip>
          </template>
          <template #default="{ row }">
            <el-switch
              v-model="row.ondemand"
              :loading="ondemandPending.has(row.id)"
              :disabled="!canOndemand || ondemandPending.has(row.id)"
              :aria-label="`按需直播：${row.customName || row.name || row.id}`"
              inline-prompt
              active-text="开"
              inactive-text="关"
              @change="(val) => toggleOndemand(row, val)"
            />
          </template>
        </el-table-column>

        <el-table-column label="云端录像" min-width="104" align="center">
          <template #header>
            <el-tooltip
              content="云端录像：平台持续把这段流录成 MP4，要求流一直在线。与「按需直播」互斥——开启它会自动关闭按需直播。开启后为全天录制，会忽略录像计划的时段限制。"
              placement="top"
            >
              <span class="th-with-tip">云端录像 <el-icon><QuestionFilled /></el-icon></span>
            </el-tooltip>
          </template>
          <template #default="{ row }">
            <el-switch
              v-model="row.cloudRecord"
              :loading="recordPending.has(row.id)"
              :disabled="!canRecord || recordPending.has(row.id)"
              :aria-label="`云端录像：${row.customName || row.name || row.id}`"
              inline-prompt
              active-text="开"
              inactive-text="关"
              @change="(val) => toggleCloudRecord(row, val)"
            />
          </template>
        </el-table-column>

        <el-table-column label="子节点数" min-width="92" align="center">
          <template #default="{ row }">
            <span class="text-muted">{{ row.subCount ?? 0 }}</span>
          </template>
        </el-table-column>

        <el-table-column label="厂家" min-width="96" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="text-muted">{{ row.manufacturer || '—' }}</span>
          </template>
        </el-table-column>

        <el-table-column label="云台类型" min-width="108" align="center">
          <template #default="{ row }">
            <!--
              文字 + 箭头（用 el-select 一屏全是灰框）。el-dropdown 在表格里复用时
              @command 只回传值、不知道属于哪一行（实测点第 5 行改的是第 1 行），
              所以命令值带上通道编码，由 handlePtzCommand 拆开。
            -->
            <el-dropdown
              v-if="canUpdate"
              trigger="click"
              :disabled="ptzPending.has(row.id)"
              @command="handlePtzCommand"
            >
              <span class="ptz-link" :class="{ 'is-busy': ptzPending.has(row.id) }">
                {{ ptzText(row) }}
                <el-icon class="ptz-arrow"><ArrowDown /></el-icon>
              </span>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item
                    v-for="(label, value) in PTZ_TYPES"
                    :key="value"
                    :command="row.id + '|' + value"
                    :disabled="Number(value) === (row.ptzType ?? 0)"
                  >
                    {{ label }}
                  </el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
            <span v-else class="text-muted">{{ ptzText(row) }}</span>
          </template>
        </el-table-column>

        <el-table-column label="位置" min-width="140" align="center">
          <template #default="{ row }">
            <span
              class="position-text"
              :class="{ 'is-editable': canPosition }"
              :title="canPosition ? '点击修改经纬度' : ''"
              @click="canPosition && openPosition(row)"
            >{{ positionText(row) }}</span>
          </template>
        </el-table-column>

        <el-table-column prop="updatedAt" label="更新时间" min-width="156" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="text-muted">{{ row.updatedAt || '—' }}</span>
          </template>
        </el-table-column>

        <el-table-column prop="createdAt" label="创建时间" min-width="156" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="text-muted">{{ row.createdAt || '—' }}</span>
          </template>
        </el-table-column>

        <!-- 操作列固定右侧：13 列必然超宽，不固定就要横向滚动才能找到入口 -->
        <el-table-column label="操作" min-width="316" align="center" class-name="op-col" fixed="right">
          <template #default="{ row }">
            <el-button
              link
              type="primary"
              title="打开预览并自动播放"
              @click="preview(row)"
            >
              <el-icon><VideoPlay /></el-icon> 播放
            </el-button>
            <!--
              只在确实有推流时出现（原来常显置灰，满屏灰按钮更像坏了）。
              判据 streamId：开流时后端写入、会话关闭清除、重启时由
              resetStaleChannelStates() 收敛，不会长期残留。
            -->
            <el-button
              v-if="row.streamId"
              link
              type="primary"
              title="停止该通道的推流"
              @click="stopStream(row)"
            >
              <el-icon><VideoPause /></el-icon> 停止
            </el-button>
            <el-button
              link
              type="primary"
              title="查看该通道的历史录像"
              @click="openDeviceRecord(row)"
            >
              <el-icon><Film /></el-icon> 设备录像
            </el-button>
            <!--
              分享：新窗口打开免登录直播页（对齐 LiveGBS 的 play.html）。
              用户要的就是看「别人打开会看到什么」，需要链接时分享页自带复制按钮。
            -->
            <el-button
              link
              type="primary"
              title="在新窗口打开该通道的免登录直播页"
              @click="shareLive(row)"
            >
              <el-icon><Share /></el-icon> 分享
            </el-button>
          </template>
        </el-table-column>
        <template #empty>
          <div class="empty-cell">暂无通道</div>
        </template>
      </el-table>

      <TablePager
        :pager="pager"
        unit="个通道"
        @page-change="onPageChange"
        @size-change="onSizeChange"
      />
    </el-card>

    <!--
      尺寸对齐 LiveGBS 的播放弹窗（视频区 878×494，云台面板 303 宽）。
      这里放宽到 1520px：云台面板 340px 决定行高（约 643px），视频要 643×16/9≈1143px
      才能不产生黑边；弹窗窄于这个值时视频只能变矮。
    -->
    <el-dialog
      v-model="previewDialog"
      :title="'实时预览 · ' + (previewTarget?.customName || previewTarget?.name || previewTarget?.id || '')"
      width="min(1520px, 96vw)"
      top="5vh"
      class="preview-dialog"
      :class="{ 'is-video-only': !hasPtz }"
      @close="closePreview"
      @closed="onPreviewClosed"
    >
      <div class="preview-wrap" :class="{ 'is-video-only': !hasPtz }">
        <!-- 视频 16:9 居中；黑底只挂在 .video-box 上（见样式说明） -->
        <div class="preview-stage">
          <div class="video-box">
            <div ref="videoContainer" class="video-container" />
            <!--
              框选图层必须挂在 .video-box 内：组件靠 parentElement 找到同层的 <video>
              来算画面真实矩形（区分黑边），挂在外面就拿不到 video 元素。

              这里保留 !playing：框选要在画面上拖框，没有画面就无从框起。
              与云台面板不同，云台走 SDK/CGI，不依赖画面。
            -->
            <TrackBox
              v-if="previewTarget"
              v-model:active="trackActive"
              :serial="previewTarget.deviceId"
              :code="previewTarget.id"
              :disabled="!playing"
              mode="targettrack"
            />
            <!--
              状态角标叠在画面左上角。挂在右栏时那 65px 会全变成视频上下的黑边，
              而「这路流现在什么状态」本来就是在说画面本身。
              pointer-events: none，不挡画面点击 / 双击全屏。
            -->
            <div class="video-state" :class="`is-${stateType}`">
              <span class="video-state-dot" />
              <span class="video-state-text">{{ state }}</span>
            </div>

            <!--
              控制栏按钮由引擎用 DOM 创建，Vue 拿不到组件实例，只能把元素当
              el-popover 虚拟锚点。placement=top：控制栏在底部，而 .video-box 是
              overflow:hidden，往下弹会被裁掉。
            -->
            <el-popover
              v-model:visible="protoMenuVisible"
              :virtual-ref="protoAnchorEl"
              virtual-triggering
              trigger="click"
              placement="top-end"
              :width="150"
              popper-class="preview-proto-menu"
            >
              <div class="proto-menu">
                <button
                  v-for="o in protoOptions"
                  :key="o.value"
                  type="button"
                  class="proto-menu-item"
                  :class="{ 'is-current': o.value === proto }"
                  @click="switchProtoTo(o.value)"
                >
                  {{ o.label }}
                </button>
              </div>
            </el-popover>

            <!-- 码流菜单：与协议菜单同一套虚拟锚点做法（按钮由播放引擎用 DOM 创建） -->
            <el-popover
              v-model:visible="streamMenuVisible"
              :virtual-ref="streamAnchorEl"
              virtual-triggering
              trigger="click"
              placement="top-end"
              :width="150"
              popper-class="preview-proto-menu"
            >
              <div class="proto-menu">
                <button
                  v-for="o in STREAM_OPTIONS"
                  :key="o.value"
                  type="button"
                  class="proto-menu-item"
                  :class="{ 'is-current': o.value === streamChoice }"
                  @click="switchStreamTo(o.value)"
                >
                  {{ o.label }}
                </button>
              </div>
            </el-popover>
          </div>
        </div>
        <div v-if="hasPtz" class="preview-side">
          <!--
            只有标为可云台（球机/半球/遥控枪机）才渲染整栏。固定枪机上方向键无效，
            只放一个 el-empty 占 340×609 白块更糟。
          -->
          <PtzPanel
            v-if="previewTarget"
            :serial="previewTarget.deviceId"
            :code="previewTarget.id"
            :source="previewTarget.source"
            :disabled="ptzDisabled"
            @track="beginTrack"
          />
        </div>
      </div>
    </el-dialog>

    <el-dialog v-model="renameDialog" title="重命名通道" width="460px">
      <div class="mono mb-3">{{ renameTarget?.id }}</div>
      <el-input v-model="renameValue" placeholder="自定义名称" />
      <template #footer>
        <el-button @click="renameDialog = false">取消</el-button>
        <el-button type="primary" @click="doRename">保存</el-button>
      </template>
    </el-dialog>

    <!-- 位置（经纬度）编辑 -->
    <el-dialog v-model="positionDialog" title="修改位置" width="440px">
      <div class="mono mb-3">{{ positionTarget?.id }}</div>
      <el-form label-width="72px">
        <el-form-item label="经度">
          <el-input v-model="positionLon" placeholder="经度，如 113.325573" />
        </el-form-item>
        <el-form-item label="纬度">
          <el-input v-model="positionLat" placeholder="纬度，如 23.132123" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="positionDialog = false">取消</el-button>
        <el-button type="primary" :loading="positionSaving" @click="savePosition">保存</el-button>
      </template>
    </el-dialog>

    <!-- 所属设备详情 -->
    <DeviceDetailDialog
      v-model:visible="detailVisible"
      :device="detailTarget"
    />
  </div>
</template>

<script setup>
import { computed, nextTick, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { useRoute, useRouter } from 'vue-router'
import { deviceApi, streamApi, systemApi } from '@/api'
import { resolvePlayUrls, hasPlayUrl, availableProtocols, LIVE_DEFAULT_PROTOCOL, isMobileClient } from '@/player/resolvePlayUrls'
import { useLivePlayer } from '@/composables/useLivePlayer'
import { usePlayerStateType } from '@/composables/usePlayerState'
import { usePagination } from '@/composables/usePagination'
import { useFillTableRows } from '@/composables/useFillTableRows'
import { useAuthStore } from '@/stores/auth'
import PtzPanel from '@/components/PtzPanel.vue'
import TrackBox from '@/components/TrackBox.vue'
import TablePager from '@/components/TablePager.vue'
import DeviceDetailDialog from '@/components/DeviceDetailDialog.vue'


const router = useRouter()
const route = useRoute()

const auth = useAuthStore()

const canWrite = computed(() => auth.can('channel:write'))
const canUpdate = computed(() => auth.can('channel:update'))
const canPosition = computed(() => auth.can('channel:position'))
const canOndemand = computed(() => auth.can('channel:ondemand'))
const canRecord = computed(() => auth.can('channel:record'))
const canAudio = computed(() => auth.can('channel:audio'))
const canShare = computed(() => auth.can('channel:share'))

const channels = ref([])
const loading = ref(false)
const query = reactive({ q: '', online: null, serial: '' })

/** 行高随表体可用高度自适应 */
const tableRef = ref(null)
useFillTableRows(tableRef, () => channels.value.length)

/** 正在切换云端录像开关的通道编码集合（loading / 防重复点击）。 */
const recordPending = reactive(new Set())

const ptzPending = reactive(new Set())

const ondemandPending = reactive(new Set())

const positionDialog = ref(false)
const positionTarget = ref(null)
const positionLon = ref('')
const positionLat = ref('')
const positionSaving = ref(false)

/** 所属设备详情弹窗（状态记录 / 流量统计 / 移动轨迹 / 会话列表）。 */
const detailVisible = ref(false)
const detailTarget = ref(null)

const previewDialog = ref(false)
const previewTarget = ref(null)
const proto = ref(LIVE_DEFAULT_PROTOCOL)
const mediaHttpPort = ref(null)

/**
 * 该通道能不能切换码流。判据由后端给（{@code ChannelVo.streamSwitchable}）：
 * <ul>
 *   <li>SDK 直连设备（大华等）：走 RTSP {@code subtype}，恒支持</li>
 *   <li>国标设备：目录里上报了两路以上 {@code StreamNumberList} 才算支持 ——
 *       2016 版设备不认 SSRC 后 4 位的码流编号，给了子码流选项也只会推主码流</li>
 * </ul>
 * 没上报就一律不显示，宁可少一个选项，也不给点了没反应的开关。
 */
const hasStreamSwitch = computed(() => !!previewTarget.value?.streamSwitchable)

/**
 * 码流选项。{@code auto} 表示不传 {@code streamnumber}，让后端按设备配置决定
 * （国标看 {@code gb_device.stream_index}，大华等 SDK 设备同样看它，缺省主码流）。
 */
const STREAM_OPTIONS = [
  { value: 'auto', label: '自动码流' },
  { value: '0', label: '主码流' },
  { value: '1', label: '子码流' }
]
const streamChoice = ref('auto')

/** 选择值 → 请求参数。{@code auto} 不传，让后端用设备配置。 */
function streamParam() {
  return streamChoice.value === 'auto' ? undefined : Number(streamChoice.value)
}

/** 播放器容器（xgplayer 在内部创建 video 元素）。 */
const videoContainer = ref(null)
const player = useLivePlayer(videoContainer)

/*
 * 协议下拉跟随播放器实际使用的协议。不同步的话弹窗一直显示 WebRTC、
 * 画面却走 FLV，排查时会被带偏。
 */
watch(
  () => player.protocol.value,
  (p) => {
    if (p && p !== proto.value) proto.value = p
  }
)
const playing = computed(() => player.playing.value)
const state = computed(() => player.state.value)
const stateType = usePlayerStateType(state)

/**
 * 云台面板是否可操作。
 *
 * <p><b>不能用 {@code !playing}</b>：云台走的是 SDK / 设备 CGI（37777 / 80），
 * 与视频流（ZLM、RTP）是两条独立通路，画面没出来照样能控云台。
 *
 * <p>早先这里传 {@code :disabled="!playing"}，导致「保存一次后所有按钮全禁用」：
 * 保存会重读设备 CGI（PtzTour 有 120KB+），把 WebRTC 协商挤超时 →
 * {@code onStall} 触发 {@code rebuildStalled()} 停会话重播 → 重建 2 次预算用完 →
 * {@code playing} 永久 false → 整个面板永久禁用，只能关掉弹窗重开。
 *
 * <p>现在只在「弹窗没开 / 没有目标通道」时禁用。
 */
const ptzDisabled = computed(() => !previewTarget.value)

/**
 * 当前通道是否支持云台（决定弹窗两栏还是单栏）。不支持时整栏不渲染：
 * 只放一个 el-empty 占着 340×609 白块比没有还难看。
 */
const hasPtz = computed(() => !!previewTarget.value && supportsPtz(previewTarget.value.ptzType))

/**
 * 框选图层是否激活。由 PtzPanel 的「框选跟踪」按钮打开，
 * 图层自己框完/取消后通过 `update:active` 关掉。
 */
const trackActive = ref(false)

/** 「框选跟踪」按钮：画面上打开拖拽图层。 */
function beginTrack() {
  if (!playing.value) {
    ElMessage.warning('请等画面出来后再框选')
    return
  }
  trackActive.value = true
}

/** 控制栏按钮上的协议名。从 protoOptions 查而不是另存一份，两份不同步会显示错。 */
const protoShort = computed(() => {
  const hit = protoOptions.value.find((o) => o.value === proto.value)
  return hit ? hit.label : proto.value
})

/**
 * 只列本浏览器真能播的协议：iPhone / iPad Safari 没有 MediaSource，
 * flv.js 起不来，菜单里摆着 WS-FLV / FLV 只会点了黑屏。
 */
const protoOptions = computed(() => availableProtocols())

const renameDialog = ref(false)
const renameTarget = ref(null)
const renameValue = ref('')

/** 云台类型：0 未知 / 1 球机 / 2 半球 / 3 固定枪机 / 4 遥控枪机，与 gb_channel.ptz_type 一致。 */
const PTZ_TYPES = { 0: '未知', 1: '球机', 2: '半球', 3: '固定枪机', 4: '遥控枪机' }

function ptzText(row) {
  return PTZ_TYPES[row.ptzType ?? 0] || '未知'
}

/** 命令值形如「通道编码|类型」，因为 el-dropdown 不知道命令来自哪一行。 */
function handlePtzCommand(cmd) {
  const sep = cmd.lastIndexOf('|')
  if (sep < 0) return
  const channelId = cmd.slice(0, sep)
  const type = Number(cmd.slice(sep + 1))
  const row = channels.value.find((c) => c.id === channelId)
  if (!row) return
  changePtzType(row, type)
}

/**
 * 该类型是否要显示云台面板。
 *
 * <p><b>只有明确是固定枪机（3）才隐藏</b>。{@code 0} 是「设备没报」，
 * 不等于「不能转」：
 * <ul>
 *   <li>国标设备普遍不上报 PTZType，大量真实球机永远是 0；</li>
 *   <li>SDK 直连设备（大华等）根本没有这个字段，恒为 0 ——
 *       早期把 0 当「不支持」，直接后果是这类设备<b>云台面板根本不渲染</b>，
 *       点播能看、控制完全用不了。</li>
 * </ul>
 * 显不显示与「命令能不能生敢」是两件事：多显示一个不可用的方向盘，
 * 比让一台能转的球机彻底无法控制代价小得多。真不能转的，
 * 用户在通道页把类型改成固定枪机即可隐藏。
 */
function supportsPtz(type) {
  return type !== 3
}

/**
 * 把设备上报的码流编号列表转成可读文案。
 * @param {string} list 形如 "0,1"
 * @returns {string} 如「主+子」；无法识别时原样返回
 */
function streamLabels(list) {
  const names = { 0: '主', 1: '子', 2: '子2' }
  const parts = String(list)
    .split(',')
    .map((s) => s.trim())
    .filter(Boolean)
  if (!parts.length) return '—'
  return parts.map((p) => names[p] || p).join('+')
}

const { pager, offset, reset, setTotal, onPageChange, onSizeChange } = usePagination({
  pageSize: 10,
  load: () => load()
})

async function load() {
  loading.value = true
  try {
    const params = { start: offset.value, limit: pager.size }
    if (query.q) params.q = query.q
    if (query.online !== null && query.online !== undefined) params.online = query.online
    // 设备筛选（从设备列表「查看通道」跳转而来，或由页面筛选保持）
    if (query.serial) params.serial = query.serial
    const data = await deviceApi.channelList(params)
    channels.value = (data?.channelList || []).map((c) => ({
      ...c,
      // 名称按需编辑的临时状态：_editing 控制是否显示输入框，
      // _savedName 记住后端值用于「值未变则不发请求」与失败回滚
      _editing: false,
      _savedName: c.customName || '',
      _draftName: c.customName || ''
    }))
    setTotal(data?.channelCount ?? channels.value.length)
  } catch (e) {
    ElMessage.error(e.message || '加载通道失败')
  } finally {
    loading.value = false
  }
}

/** 清除设备筛选（回到全部通道）。 */
function clearSerialFilter() {
  query.serial = ''
  search()
}

/** 条件变化后回到第一页再查，否则会停在越界页上看到空列表。 */
function search() {
  reset()
  load()
}

async function ensureMediaPort() {
  if (mediaHttpPort.value) return
  try {
    const info = await systemApi.serverinfo()
    mediaHttpPort.value = info?.mediaHttp
  } catch (e) {
    // 忽略
  }
}

async function preview(row) {
  previewTarget.value = row
  previewDialog.value = true
  // 用户主动点开一个通道才算新的一轮，重置卡轨重建计数
  stallRetry = 0
  await ensureMediaPort()
  await sleep(120)
  await startPlay()
}

/**
 * 在新窗口打开免登录直播页（对齐 LiveGBS 的 play.html）。链接用 location.origin：
 * 平台有内网/公网两个域名时，后端拼的地址会把用户引到错的入口。
 *
 * 用创建 <a> 而不是 window.open：带 noopener 时规范上返回 null，判不了是否被拦截；
 * 不带又会把 window.opener 交给对方页面。真实链接跳转两者都不丢。
 */
function shareLive(row) {
  const serial = row.deviceId || row.serial
  const code = row.id || row.channelId
  if (!serial || !code) {
    ElMessage.error('该通道缺少编码，无法生成分享链接')
    return
  }
  const url = `${window.location.origin}/play?serial=${encodeURIComponent(serial)}&code=${encodeURIComponent(code)}`
  const a = document.createElement('a')
  a.href = url
  a.target = '_blank'
  a.rel = 'noopener noreferrer'
  document.body.appendChild(a)
  a.click()
  a.remove()
}

/**
 * 画面未就绪的重建次数上限（每轮最多 2 次）。重建 = 停会话 + 重新 INVITE，
 * 设备要重新起推；设备自己坏了重试多少次都没用。
 */
let stallRetry = 0

/**
 * 流是通的、却始终没有视频轨 → 重建整路流。必须停会话而不是重连播放器：
 * ZLM 等轨超时会把 H264 轨永久丢弃（Track not ready for a long time, ignored: H264），
 * 该决定绑定在媒体源上，重连 FLV 连的还是同一个源；只有 stream/stop 拆掉会话、
 * 重新 INVITE 才会生成新源。
 */
async function rebuildStalled() {
  const row = previewTarget.value
  if (!row || stallRetry >= 2) return
  stallRetry++
  ElMessage.warning('画面未就绪，正在重新拉流…')
  try {
    await streamApi.stop({ serial: row.deviceId, code: row.id })
  } catch (e) {
    // 会话可能已被空闲巡检关掉，继续重播即可
  }
  if (!previewDialog.value) return
  await startPlay()
}

async function startPlay() {
  const row = previewTarget.value
  if (!row) return
  const streamId = `${row.deviceId}/${row.id}`
  let info = null
  try {
    // 点播响应已包含全部播放地址
    info = await streamApi.start({
      serial: row.deviceId,
      code: row.id,
      streamnumber: streamParam()
    })
  } catch (e) {
    try {
      info = await streamApi.info(streamId, true)
    } catch (e2) {
      ElMessage.error(e.message || '点播失败')
      return
    }
  }
  // 会话已建立，同步到行上，让操作列「停止」立刻出现。
  // 不写这一行，设备已在推流但列表没有停止入口（数据不轮询），只能去预览页找
  row.streamId = streamId
  const urls = resolvePlayUrls(info, mediaHttpPort.value)
  if (!hasPlayUrl(proto.value, urls)) {
    ElMessage.error('未获取到播放地址')
    return
  }
  await player.play(streamId, urls, {
    // 手机端固定 WebRTC：FLV 没有可用 MSE，HLS 延迟 6s+ 且非 Safari 解不了 G.711，
    // 给三个选项只会让用户点到必然失败的那个
    proto: isMobileClient() ? LIVE_DEFAULT_PROTOCOL : proto.value,
    // 「播放协议」按钮仅桌面端提供。放控制栏是因为页脚/画面右上角都试过：
    // 浮层会盖住画面，且与弹窗 header 争层级（实测点不到）
    controlButtons: isMobileClient()
      ? null
      : [
          { id: 'proto', title: '播放协议', text: protoShort.value },
          // 只在真能切码流时才显示：国标 2016 版设备不认 SSRC 码流编号，
          // 给了选项也只会推主码流，用户切了看不出变化
          ...(hasStreamSwitch.value
            ? [{ id: 'stream', title: '码流（主/子）', text: streamShort.value }]
            : [])
        ],
    onControlButton: onPlayerControlButton,
    // 浏览器放不了所选协议时引擎会自动降级；同步回 proto 并提示，
    // 否则按钮写着 FLV 画面却是 WebRTC，用户下次还会再选一遍
    onProtoChange: (used, wanted) => {
      proto.value = used
      if (wanted && wanted !== used) {
        const hit = protoOptions.value.find((o) => o.value === used)
        ElMessage.warning(`当前浏览器不支持该协议，已自动切换到「${hit ? hit.label : used}」`)
      }
    },
    // 通了但始终没视频轨时，重建整路流（见 rebuidStalled 的说明）
    onStall: () => rebuildStalled()
  })
  syncProtoLabel()
  syncStreamLabel()
}

/* ---------------- 控制栏按钮 ---------------- */

/**
 * 协议菜单是否展开。用 el-popover 虚拟触发：按钮是引擎直接用 DOM 建的，
 * 拿不到组件实例，只能把它当锚点元素传给 popover。
 */
const protoMenuVisible = ref(false)
const protoAnchorEl = ref(null)

/** 码流菜单（与协议菜单同一套做法）。 */
const streamMenuVisible = ref(false)
const streamAnchorEl = ref(null)

/**
 * 只负责把锚点交给 popover，是否展开由 el-popover 自己管：virtual-triggering
 * 会在锚点元素上挂 click 自行 toggle。若这里再写 visible = true，第二次点击会
 * 变成「它 toggle 成 false、我又设回 true」，菜单关不掉。
 * 例外是第一次拿到锚点那次点击 —— 监听要等重新渲染才挂上，得手动打开一次。
 */
function onPlayerControlButton(id, el) {
  if (id === 'proto') {
    if (protoAnchorEl.value === el) return
    protoAnchorEl.value = el
    protoMenuVisible.value = true
    return
  }
  if (id === 'stream') {
    if (streamAnchorEl.value === el) return
    streamAnchorEl.value = el
    streamMenuVisible.value = true
  }
}

/**
 * 把协议文案同步到控制栏按钮。不能只靠 watch：切协议实际是重新 play 一次，
 * 新引擎的按钮文案取自上一次 play 存的 opts（已旧），而 watch 触发时引擎还没建好。
 */
function syncProtoLabel() {
  player.setControlButtonText('proto', protoShort.value)
}

/** 协议文案变化时同步到控制栏按钮上（自动兜底时也会走到）。 */
watch(protoShort, syncProtoLabel)

/** 码流名（控制栏按钮文案）。 */
const streamShort = computed(() => {
  const hit = STREAM_OPTIONS.find((o) => o.value === streamChoice.value)
  return hit ? hit.label : streamChoice.value
})

/** 把码流文案同步到控制栏按钮。理由同 syncProtoLabel：重新 play 会重建按钮。 */
function syncStreamLabel() {
  if (!hasStreamSwitch.value) return
  player.setControlButtonText('stream', streamShort.value)
}

watch(streamShort, syncStreamLabel)

/**
 * 切换码流。
 *
 * <p>必须重建流而不是只改参数：码流编号在国标里是 INVITE 时写进 SSRC 的，
 * 在大华里是 RTSP 地址的 {@code subtype}，两者都无法在会话中途改。
 * 先 stop 再 startPlay，避免旧会话被复用（复用会把子码流当主码流一直播下去）。
 */
async function switchStreamTo(next) {
  streamMenuVisible.value = false
  if (!hasStreamSwitch.value) return
  if (!next || next === streamChoice.value) return
  streamChoice.value = next
  const row = previewTarget.value
  if (!row) return
  // 未在播放时只记住选择，下次点播按它走
  if (!playing.value) return
  try {
    await streamApi.stop({ serial: row.deviceId, code: row.id })
  } catch (e) {
    // 会话可能已被空闲巡检关掉，继续重播即可
  }
  if (!previewDialog.value) return
  await startPlay()
}

/**
 * 切换播放方式（ws-flv ↔ WebRTC）。拿不到播放地址时要重新点播，不能直接
 * switchProtocol：stop() 断开旧通路后 ZLM 读者数归零，45 秒空闲巡检可能刚好
 * 关流，再查 stream/info 就是 400；switchProtocol 拿到空地址只会把状态置成
 * 「缺少播放地址」停在那里。
 */
async function switchProto(next) {
  const row = previewTarget.value
  if (!row) return
  if (!playing.value) {
    // 未在播放时只记住选择，下次点播按它走
    proto.value = next || proto.value
    return
  }
  const streamId = `${row.deviceId}/${row.id}`
  let urls = null
  try {
    const info = await streamApi.info(streamId, true)
    urls = resolvePlayUrls(info, mediaHttpPort.value)
  } catch (e) {
    // 流可能已被空闲巡检关闭，下面重新点播
  }
  if (!urls || !hasPlayUrl(next || proto.value, urls)) {
    await startPlay()
    syncProtoLabel()
    return
  }
  await player.switchProtocol(next || proto.value, urls)
  syncProtoLabel()
}

/**
 * 先写回 proto 再调 switchProto：后者两条路径都读 proto（未播时只记住选择，
 * 已播时作为目标协议）。选中当前项时直接关菜单，避免白白断流重连。
 */
function switchProtoTo(next) {
  protoMenuVisible.value = false
  if (!next || next === proto.value) return
  proto.value = next
  switchProto(next)
}

/**
 * 必须显式把 previewDialog 置 false：否则底部「关闭」点了既不关窗也不断流。
 * （右上角 × 能用是因为 el-dialog 自己 emit update:modelValue，与这个函数无关。）
 * 清 previewTarget 放到 onPreviewClosed，否则标题会在关闭动画中先变空串、闪一下。
 */
function closePreview() {
  previewDialog.value = false
  // 关窗时框选图层必须一起退掉，否则下次打开弹窗它会保持着激活状态
  trackActive.value = false
  // 立即断流：不要等 300ms 的关闭动画，让 ZLM 早点释放这路拉流
  // 先退掉「CSS 全屏」兜底（切协议重建播放器时可能用上），否则它固定覆盖整屏
  player.exitCssFullscreen()
  player.stop()
}

/** 弹窗完全关闭（动画结束、DOM 销毁后）再清数据。 */
function onPreviewClosed() {
  // stop 已在 @close 里做过（幂等），这里只清掉标题依赖的数据
  previewTarget.value = null
}

/**
 * 修改云台类型。下拉不是 v-model，成功后必须显式写回，否则列表还是旧值。
 * 后端在目录同步时「设备报 0 不覆盖」，所以这里改的值不会被下次注册冲回未知。
 */
async function changePtzType(row, type) {
  const previous = row.ptzType
  ptzPending.add(row.id)
  try {
    await deviceApi.setChannelPtzType({
      serial: row.deviceId,
      code: row.id,
      ptz_type: type
    })
    row.ptzType = type
    ElMessage.success(`云台类型已改为「${PTZ_TYPES[type] ?? type}」`)
  } catch (e) {
    row.ptzType = previous
    ElMessage.error(e.message || '修改失败')
  } finally {
    ptzPending.delete(row.id)
  }
}

async function toggleCloudRecord(row, enable) {
  // v-model 已先行改值：失败时必须回滚，否则开关状态与后端不一致
  recordPending.add(row.id)
  try {
    // 参数名必须是 cloudrecord（后端 @RequestParam Boolean cloudrecord），传 enable 会被忽略
    await deviceApi.setChannelCloudRecord({ serial: row.deviceId, code: row.id, cloudrecord: enable })
    // 后端开云端录像会自动关掉「按需直播」，这里必须同步本地状态，
    // 否则界面显示两个都开、库里是按需=关，只能刷新页面才对得上
    const turnedOffOnDemand = enable && row.ondemand
    if (turnedOffOnDemand) {
      row.ondemand = false
    }
    ElMessage.success(turnedOffOnDemand
      ? '已开启云端录像，同时关闭按需直播（两者互斥）'
      : (enable ? '已开启云端录像' : '已关闭云端录像'))
  } catch (e) {
    row.cloudRecord = !enable
    ElMessage.error(e.message || '操作失败')
  } finally {
    recordPending.delete(row.id)
  }
}

function openRename(row) {
  renameTarget.value = row
  renameValue.value = row.customName || ''
  renameDialog.value = true
}

async function doRename() {
  const row = renameTarget.value
  if (!row) return
  try {
    await deviceApi.setChannelName({ serial: row.deviceId, code: row.id, name: renameValue.value })
    row.customName = renameValue.value
    renameDialog.value = false
    ElMessage.success('已保存')
  } catch (e) {
    ElMessage.error(e.message || '保存失败')
  }
}

/* ---------------- 名称按需编辑 ---------------- */

function startNameEdit(row) {
  if (row._editing) return
  row._editing = true
  row._draftName = row.customName || ''
  // 自动聚焦，Enter/Esc 才作用在输入框上（否则键盘事件落在页面里）
  nextTick(() => {
    const input = document.getElementById(nameInputId(row))
    input?.focus()
    input?.select()
  })
}

/** 名称输入框的 DOM id：通道编码跨设备可重复，必须带上设备编码。 */
function nameInputId(row) {
  return `ch-name-${row.deviceId}-${row.id}`
}

function cancelName(row) {
  row.customName = row._draftName || ''
  row._editing = false
}

/** 保存名称；值未变化时不发请求。 */
async function saveName(row) {
  if (!row._editing) return
  row._editing = false
  const next = (row.customName || '').trim()
  if (next === (row._savedName || '')) return
  const previous = row._savedName || ''
  row._savedName = next
  try {
    await deviceApi.setChannelName({ serial: row.deviceId, code: row.id, name: next })
    ElMessage.success('名称已保存')
  } catch (e) {
    row.customName = previous
    row._savedName = previous
    ElMessage.error(e.message || '保存失败')
  }
}

function positionText(row) {
  const lon = row.longitude
  const lat = row.latitude
  const hasLon = lon !== null && lon !== undefined && Number(lon) !== 0
  const hasLat = lat !== null && lat !== undefined && Number(lat) !== 0
  if (!hasLon && !hasLat) return '—'
  // 4 位小数（约 1 米精度），再长就塞不进列宽了
  return `${hasLon ? Number(lon).toFixed(4) : '-'},${hasLat ? Number(lat).toFixed(4) : '-'}`
}

function openPosition(row) {
  positionTarget.value = row
  positionLon.value = row.longitude ?? ''
  positionLat.value = row.latitude ?? ''
  positionDialog.value = true
}

async function savePosition() {
  const row = positionTarget.value
  if (!row) return
  const lon = Number(positionLon.value)
  const lat = Number(positionLat.value)
  if (!Number.isFinite(lon) || !Number.isFinite(lat)) {
    ElMessage.warning('经纬度必须是数字')
    return
  }
  positionSaving.value = true
  try {
    await deviceApi.setChannelPosition({
      serial: row.deviceId,
      code: row.id,
      longitude: lon,
      latitude: lat
    })
    row.longitude = lon
    row.latitude = lat
    positionDialog.value = false
    ElMessage.success('位置已保存')
  } catch (e) {
    ElMessage.error(e.message || '保存失败')
  } finally {
    positionSaving.value = false
  }
}

async function toggleOndemand(row, enable) {
  ondemandPending.add(row.id)
  try {
    await deviceApi.setChannelOnDemand({
      serial: row.deviceId,
      code: row.id,
      ondemand: enable
    })
    // 互斥：开按需会被后端连带关掉云端录像
    const turnedOffRecord = enable && row.cloudRecord
    if (turnedOffRecord) {
      row.cloudRecord = false
    }
    ElMessage.success(turnedOffRecord
      ? '已开启按需直播，同时关闭云端录像（两者互斥）'
      : (enable ? '已开启按需直播' : '已关闭按需直播'))
  } catch (e) {
    row.ondemand = !enable
    ElMessage.error(e.message || '操作失败')
  } finally {
    ondemandPending.delete(row.id)
  }
}

async function stopStream(row) {
  try {
    const res = await streamApi.stop({ serial: row.deviceId, code: row.id })
    // 无论后端有没有真停掉会话，本行都不该再显示「停止」：ok=false 表示本来
    // 就没有会话（可能被 45 秒空闲巡检关了，而列表不轮询，本地还以为在推流）
    row.streamId = null
    if (res?.ok === false) {
      ElMessage.info('该通道当前没有推流')
    } else {
      ElMessage.success('已停止推流')
    }
  } catch (e) {
    ElMessage.error(e.message || '停止失败')
  }
}

function openDeviceRecord(row) {
  router.push({
    path: '/playback',
    query: { serial: row.deviceId, code: row.id }
  })
}

function openDeviceDetail(row) {
  // 弹窗按 device.id（设备编码）查日志，通道行里的 id 是通道编码，需重组为设备对象
  detailTarget.value = {
    id: row.deviceId,
    name: row.deviceName,
    customName: row.deviceCustomName,
    status: row.deviceOnline ? 'ON' : 'OFF',
    endpoint: row.endpoint,
    streamIndex: row.streamIndex
  }
  detailVisible.value = true
}

function sleep(ms) {
  return new Promise((r) => setTimeout(r, ms))
}

onMounted(() => {
  // 支持 /channels?serial=<设备编码> 直接带筛选进入（设备列表「查看通道」入口）
  const serial = String(route.query.serial || '').trim()
  if (serial) query.serial = serial
  load()
})
onUnmounted(() => player.stop())
</script>

<style scoped>
/* 操作列：四个按钮必须排在一行，否则行高会翻倍 */
:deep(td.op-col .el-button + .el-button) {
  margin-left: 4px;
}

/*
 * 带提示的表头：「按需直播」与「云端录像」互斥，只看表头四个字会以为能同时开，
 * 点了一个另一个自动关掉时像是 bug。悬停显示完整说明。
 */
.th-with-tip {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  cursor: help;
}

.th-with-tip .el-icon {
  font-size: 12px;
  color: var(--el-text-color-placeholder);
}

/* 国标编号：20 位等宽字符，用小一号字才塞得进 168px 的列 */
.code-sm {
  font-size: var(--fs-xs);
}

/* 云台类型：文字 + 箭头，点开是下拉菜单 */
.ptz-link {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  cursor: pointer;
  color: var(--accent-ink);
  font-size: var(--fs-sm);
  font-weight: 500;
  user-select: none;
}
.ptz-link:hover {
  text-decoration: underline;
}
.ptz-link.is-busy {
  opacity: 0.6;
  pointer-events: none;
}
.ptz-arrow {
  font-size: 11px;
  color: var(--text-tertiary);
}

.name-text {
  cursor: default;
  border-radius: 4px;
  padding: 1px 4px;
  margin: -1px -4px;
}
.name-text.is-editable {
  cursor: pointer;
  transition: background-color 0.15s;
}
.name-text.is-editable:hover {
  background-color: var(--el-fill-color-light);
  color: var(--el-color-primary);
}
.name-text.is-empty {
  color: var(--text-tertiary);
}
.name-input {
  width: 100%;
}
.name-input :deep(.el-input__wrapper) {
  padding: 0 6px;
}

.position-text {
  cursor: pointer;
  border-radius: 4px;
  padding: 1px 4px;
  margin: -1px -4px;
  font-size: var(--fs-xs);
  font-variant-numeric: tabular-nums;
  color: var(--text-secondary);
  transition: background-color 0.15s;
}
.position-text:hover {
  background-color: var(--el-fill-color-light);
  color: var(--el-color-primary);
}

.status-cell {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.badge.is-clickable {
  cursor: pointer;
}
.traffic-icon {
  color: var(--text-tertiary);
  cursor: pointer;
  transition: color 0.15s;
}
.traffic-icon:hover {
  color: var(--el-color-primary);
}

/* 所属设备：编码（等宽）+ 名称（灰），压成一行避免行高翻倍 */
.device-ref {
  display: inline-flex;
  align-items: baseline;
  gap: 8px;
  min-width: 0;
}
.device-ref .text-dim {
  font-size: var(--fs-xs);
}

/* 码流能力：设备上报的 StreamNumberList 摘要 */
.stream-list {
  font-size: var(--fs-xs);
  color: var(--text-secondary);
  font-variant-numeric: tabular-nums;
}
</style>

<style scoped>
.preview-wrap {
  display: grid;
  /*
   * 视频栏吃掉剩余宽度，云台面板固定 420px。
   *
   * <p>为什么是 420 而不是 340：云台扩展里的巡航组/线扫/巡迹都是 6~7 列的表格
   * （序号 + 名称 + 若干操作列 + 删除），340px 减去左栏内边距后每列只剩 40px 左右，
   * 名称列被压到只能显示 2~3 个字。实测 420px 时名称列有 90px、操作列不再换行。
   * 参考站是 303px，但它没有「云台扩展」这组表格页。
   */
  grid-template-columns: minmax(0, 1fr) 480px;
  gap: 24px;
  /*
   * 行高由两栏内容高度的较大值决定 —— 这里是<b>右栏</b>（云台面板）。
   * 视频比它矮时就居中，四周留的是弹窗背景（不是黑框）。
   */
  align-items: stretch;
  /*
   * 视频最大高度。只通过下面的 max-width 反推，不直接约束 width，所以不压比例。
   * 取 100vh-210px（标题栏 + 页脚 + 弹窗上边距），不用更保守的 62vh：
   * 实测 1680×950 下 62vh=589，而栏宽 1082 对应 609，会把视频无谓收窄到 1047，
   * 左右各多一道黑边。
   */
  --video-max-h: calc(100vh - 210px);
  /*
   * 行高上限 = 视频最大高度，且允许行被压缩（minmax 的下限是 0）。
   *
   * 没有这两行时，网格行按<b>内容</b>高度算：切到「相机」页，43 项参数
   * 把右栏内容撑到 400+px，行跟着长高，弹窗就被顶大了（实测 644 → 888）。
   * 加上之后行高封顶在视频能占的最大高度，右栏在内部滚动，弹窗尺寸不再随页签变化。
   */
  grid-template-rows: minmax(0, 1fr);
  max-height: var(--video-max-h);
}

/* 单栏模式（不支持云台）：去掉右栏，弹窗高度只由视频决定，不再「左黑右白」 */
.preview-wrap.is-video-only {
  grid-template-columns: minmax(0, 1fr);
  gap: 0;
}

/*
 * 左栏 = 播放器画面区。黑底只挂在 .video-box（播放器自身）上，不在这一层：
 * 两栏行高由右栏决定，视频装不满时围在外面的黑就是一整块黑框，比白空白更难看。
 *
 * 也不 height:100% 拉高：object-fit:cover 会切掉画面（监控不可接受），否则就是变形。
 */
.preview-stage {
  min-width: 0;
  display: flex;
  justify-content: center;
  align-items: center;
  background: transparent;
  border-radius: 4px;
}

/*
 * 同时写 width / max-width / aspect-ratio：栏宽够大时宽度 100%、高=宽×9/16；
 * 视口太矮时宽度被 max-width 夹住（--video-max-h × 16/9），比例仍保持 16:9。
 * 不能用 max-height 代替 —— 它不参与宽高比反算，只硬压高度，画面会变形。
 */
.preview-stage .video-box {
  width: 100%;
  max-width: calc(var(--video-max-h) * 16 / 9);
  aspect-ratio: 16 / 9;
}

/*
 * 右栏：高度由云台面板内容决定，正常视口下不滚动（面板 641px + header/footer
 * 约 760px，≥800px 的视口放得下）。max-height + overflow 只是矮视口兜底。
 */
.preview-side {
  border-left: 1px solid var(--border-color);
  padding-left: 18px;
  /*
   * 顶部留白：状态角标挪进画面后，右栏第一个元素就是方向盘，不留白会贴上边缘。
   * 只留 8px：这里每多 1px，视频上下黑边各多 0.5px（两栏等高）。
   */
  padding-top: 8px;
  /*
   * 底部留白：面板现在撑满整栏，底部的「还原/保存」会紧贴边框，
   * 留 8px 让它不贴边（flex 会把子项自动收窄，不会溢出）。
   */
  padding-bottom: 8px;
  min-height: 0;
  /*
   * flex column：让云台面板（.ptz-panel 有 flex:1）吃掉整栏高度，
   * 参数页的内容区因此能一直撑到底部，不会在保存按钮下面留一条空白。
   */
  display: flex;
  flex-direction: column;
  /*
   * 兜底上限：面板实测 663px + header/footer 约 780px，视口 ≥840px 不会触发。
   * 取 100vh-150 而不是 200：200 时算出来正好等于面板高度，
   * scrollHeight 比 clientHeight 多一个子像素就常驻滚动条（实测踩到）。
   */
  max-height: calc(100vh - 150px);
  overflow-y: auto;
  /* 不预留滚动条槽位：不滚动时不会凭空少 15px 宽 */
  scrollbar-gutter: auto;
  scrollbar-width: thin;
  scrollbar-color: var(--border-color) transparent;
}

/* 细滚动条（仅矮视口兜底时出现），不抢焦点、不占领视宽 */
.preview-side::-webkit-scrollbar {
  width: 6px;
}
.preview-side::-webkit-scrollbar-track {
  background: transparent;
}
.preview-side::-webkit-scrollbar-thumb {
  background: var(--border-color);
  border-radius: 3px;
}
.preview-side::-webkit-scrollbar-thumb:hover {
  background: var(--text-secondary);
}

/*
 * 预置位表格压到 176（约 3 行 + 表头）。表格高度直接决定右栏总高，
 * 右栏每高 16px，左栏黑边上下各多 8px。只在弹窗里改，LiveView 保持 248。
 * !important 是因为 el-table 的 height 属性写成内联 style。
 */
.preview-side :deep(.preset-table) {
  height: 176px !important;
}

/*
 * 窄视口：改为单列堆叠，视频回到「宽 100% + 16:9」，
 * 右栏不再限高，按内容自然高度铺开。
 */
@media (max-width: 900px) {
  .preview-wrap {
    grid-template-columns: minmax(0, 1fr);
  }

  .preview-stage {
    display: block;
    background: none;
  }

  .preview-stage .video-box {
    max-width: none;
  }

  .preview-side {
    border-left: none;
    padding-left: 0;
    border-top: 1px solid var(--border-color);
    padding-top: 14px;
    max-height: none;
    overflow-y: visible;
  }
}

/*
 * 状态角标叠在画面左上角。xgplayer 控制栏在底部，左上角是空白区，
 * 不会与按钮抢位置；pointer-events:none 保证不拦截点击 / 双击全屏。
 */
.video-state {
  position: absolute;
  top: 10px;
  left: 10px;
  z-index: 3;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  max-width: calc(100% - 20px);
  padding: 3px 10px 3px 8px;
  border-radius: 999px;
  background: rgba(8, 13, 20, 0.66);
  border: 1px solid rgba(255, 255, 255, 0.08);
  color: rgba(255, 255, 255, 0.92);
  font-size: var(--fs-xs);
  line-height: 1.5;
  pointer-events: none;
}

.video-state-text {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.video-state-dot {
  flex: 0 0 auto;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  /* 默认灰：没有明确语义的过渡文案（如「已停止」）也能看到状态点在 */
  background: rgba(255, 255, 255, 0.45);
}

/*
 * 三档语义色，与 usePlayerStateType 的返回值一一对应。
 * 只给圆点上色、文案保持白：整块变绿/变红会让角标太抢眼，盖过画面本身。
 */
.video-state.is-success .video-state-dot {
  background: #52d47a;
}

/* 中间态（连接中 / 缓冲中 / 协商中…）：琥珀 + 呼吸，表达「正在等」 */
.video-state.is-info .video-state-dot {
  background: #f0b64a;
  animation: pulse 1.6s ease-in-out infinite;
}

.video-state.is-danger .video-state-dot {
  background: #ff7875;
}

/*
 * 云台面板在预览弹窗里空间有限：把共享组件默认的 16px 间距收一点。
 * （只影响本弹窗，LiveView 的右侧面板保持原样。）
 */
.preview-side :deep(.ptz-panel) {
  gap: 18px;
}
</style>

<!--
  预览弹窗的内边距必须放在非 scoped 样式块里：el-dialog teleport 到 <body>，
  元素上没有本组件的 data-v 标记，scoped 里的 :deep 匹配不到（实测底部仍留约 90px 空白）。
  选择器带 .preview-dialog 前缀，不影响其它对话框。
-->
<style>
/* Element Plus 默认给 header/body/footer 各留 16~20px，叠起来在视频下方形成空白带 */
.preview-dialog .el-dialog__header {
  padding: 14px 20px 10px;
  margin-right: 0;
}

.preview-dialog .el-dialog__body {
  padding: 0 20px;
}

.preview-dialog .el-dialog__footer {
  padding: 8px 20px 12px;
}

/* 弹窗标题：比默认 18px 收一点，与紧凑布局更协调 */
.preview-dialog .el-dialog__title {
  font-size: 15px;
  font-weight: 600;
}

/*
 * 单栏时收窄弹窗，否则左右多出两大块背景。公式同 .video-box 的 max-width：
 * (100vh-210px) × 16/9 + 76px（body 左右内边距，实测 74~78）。
 */
.preview-dialog.is-video-only {
  width: min(calc((100vh - 210px) * 16 / 9 + 76px), 96vw) !important;
}

/*
 * 协议菜单（el-popover 内容）。必须写在非 scoped 块：popper teleport 到 body，
 * 没有 data-v 标记。用 popper-class 限定作用域，不影响其它弹层。
 */
.preview-proto-menu .proto-menu {
  display: flex;
  flex-direction: column;
  gap: 2px;
  margin: -4px -8px;
}

.preview-proto-menu .proto-menu-item {
  display: block;
  width: 100%;
  padding: 7px 12px;
  font-family: inherit;
  font-size: 13px;
  color: var(--text-primary);
  text-align: left;
  background: transparent;
  border: 0;
  border-radius: 4px;
  cursor: pointer;
  transition: background-color 0.12s, color 0.12s;
}

.preview-proto-menu .proto-menu-item:hover {
  color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
}

/* 当前项：加粗 + 品牌色，一眼看出现在用的是哪一档 */
.preview-proto-menu .proto-menu-item.is-current {
  color: var(--el-color-primary);
  font-weight: 600;
}
</style>
