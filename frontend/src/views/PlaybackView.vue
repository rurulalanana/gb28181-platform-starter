<template>
  <div class="page pb-page">
    <div class="pb-card">
      <!-- 标题格式对齐 CMS（LiveGBS）：设备录像时间轴(设备-通道) -->
      <h4 class="pb-title">设备录像时间轴({{ timelineTitle }})</h4>

      <div class="pb-body">

        <!--
          按钮尺寸对齐 CMS 的 Bootstrap btn-sm：高 29px、字号 12px、圆角 3px。
          不用 el-button：element-plus 的 small 是 24px，和日期框并排会参差不齐。
        -->
        <div class="pb-toolbar">
          <div class="pb-tools-left">
            <button class="cms-btn cms-btn-primary" title="返回上一页" @click="goBack">
              <span class="cms-ico">‹</span> 返回
            </button>
            <span class="cms-gap" />
            <!-- CMS 的通道来自路由参数，本平台 /playback 没有，只能留在页面上 -->
            <el-select
              v-model="query.channelKey"
              placeholder="选择通道"
              filterable
              class="pb-sel pb-sel-channel"
              @change="onChannelChange"
            >
              <el-option
                v-for="c in channels"
                :key="c.deviceId + '/' + c.id"
                :label="`${c.customName || c.name || c.id}${c.deviceOnline ? '' : '（离线）'}`"
                :value="c.deviceId + '/' + c.id"
              />
            </el-select>
            <span class="cms-gap" />
            <el-select
              v-model="playRate"
              class="pb-sel pb-sel-rate"
              title="回放倍速（国标 Scale，由设备侧变速推流）"
              @change="onRateChange"
            >
              <el-option :value="0.5" label="0.5 倍速" />
              <el-option :value="1" label="1 倍速" />
              <el-option :value="2" label="2 倍速" />
              <el-option :value="4" label="4 倍速" />
            </el-select>
            <span
              v-if="speedMode === 'jump'"
              class="pb-speed-hint"
              title="该设备不支持信令变速，正在用「连续跳播」模拟快放"
            >跳播快进</span>
            <span class="cms-gap" />
            <button
              v-if="canDownload"
              class="cms-btn"
              :disabled="!current"
              title="下载当前正在回放的这一段录像"
              @click="downloadCurrent"
            >下载</button>
          </div>

          <div class="pb-tools-right">
            <el-button-group class="pb-daynav">
              <button class="cms-btn cms-btn-sq" title="前一天" @click="shiftDay(-1)">‹</button>
              <button class="cms-btn cms-btn-sq" title="后一天" :disabled="isToday" @click="shiftDay(1)">›</button>
            </el-button-group>
            <el-date-picker
              v-model="day"
              type="date"
              placeholder="选择日期"
              :clearable="false"
              :editable="false"
              value-format="YYYY-MM-DD"
              class="pb-sel-date"
              style="width: 150px"
              @change="onDayChange"
            />
            <button
              class="cms-btn"
              :disabled="searching"
              title="按当前通道与日期重新检索"
              @click="search"
            >检索</button>
            <button class="cms-btn" @click="toggleView">
              {{ viewMode === 'timeline' ? '列表视图' : '时间轴视图' }}
            </button>
          </div>
        </div>

        <!--
          播放区对齐 CMS 的 700×394 黑底居中。控制栏用 LivePlayer 自带的，不另做一套：
          自绘控件做不到全屏/截图，还会和组件内部状态打架。
        -->
        <div ref="playStage" class="pb-player">
          <div ref="videoContainer" class="pb-video" />
          <div v-if="!current" class="pb-no-video">No Video</div>
          <div v-if="seeking && current" class="pb-seeking">定位中…</div>
        </div>

        <!--
          时间轴 1:1 对齐 CMS：外框 50px 高、灰底；内层固定 1440px（1px = 1 分钟），
          288 个 5px 刻度块，有录像的点亮；24 个 60px 小时格；2px 红游标。
          外框 width:100% + max-width:1440px 而内层固定 1440px：窗口窄时右侧被裁掉，
          这是 CMS 原样行为，不做自适应缩放（一改刻度就与 CMS 对不上）。
        -->
        <div
          v-show="viewMode === 'timeline'"
          v-loading="searching"
          class="time-rule"
          @click="onTrackClick"
        >
          <div class="time-day">
            <div
              v-for="b in minuteBlocks"
              :key="b.i"
              class="time-minute"
              :class="{ active: b.has }"
              :title="b.has ? b.tip : ''"
            />
            <div
              v-for="h in hourTicks"
              :key="'h' + h"
              :class="h === 0 ? 'time-text-first' : 'time-text'"
            >{{ pad2(h) }}:00</div>
          </div>
          <div
            v-if="cursorPx !== null"
            class="time-cursor"
            :style="{ left: cursorPx + 'px' }"
          >
            <div class="time-cursor-text">{{ cursorClock }}</div>
          </div>
          <div v-if="!timelineSegments.length && searched" class="time-empty">
            {{ timelineEmptyText }}
          </div>
        </div>

      <!--
        列表与时间轴同源数据，切换不重新发起 SIP 查询。用 v-if 而非 v-else：
        时间轴用 v-show 保留 DOM（避免重建 288 个刻度块），v-else 只能跟 v-if 配对。
      -->
      <el-table
        v-if="viewMode === 'list'"
        v-loading="searching"
        :data="records"
        stripe
        table-layout="fixed"
      >
        <el-table-column v-if="!allNoFileName" label="文件名" min-width="240">
          <template #default="{ row }">
            <span class="mono">{{ shortName(row.filePath) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="开始时间" width="180">
          <template #default="{ row }"><span class="mono">{{ prettyTime(row.startTime) }}</span></template>
        </el-table-column>
        <el-table-column label="结束时间" width="180">
          <template #default="{ row }"><span class="mono">{{ prettyTime(row.endTime) }}</span></template>
        </el-table-column>
        <el-table-column label="大小" width="110" align="right">
          <template #default="{ row }">{{ formatSize(row.fileSize) }}</template>
        </el-table-column>
        <el-table-column label="类型" width="100" align="center">
          <template #default="{ row }">
            <el-tag size="small" effect="plain">{{ typeLabel(row.type) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="170" align="center" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="play(row)">
              <el-icon><VideoPlay /></el-icon> 回放
            </el-button>
            <el-button
              v-if="canDownload"
              link
              type="primary"
              :loading="isDownloadingRow(row)"
              :disabled="hasTaskOfRow(row)"
              :title="downloadHint(row)"
              @click="startDownload(row)"
            >
              <el-icon><Download /></el-icon> 下载
            </el-button>
          </template>
        </el-table-column>
        <template #empty>
          <div class="empty-cell">{{ searched ? '当日无录像' : '选择通道与日期后检索' }}</div>
        </template>
      </el-table>

      <div v-if="viewMode === 'list'" class="table-footer list-footer">
        <span>
          共 {{ records.length }} 条录像
          <span v-if="downloads.runningCount" class="dl-hint">
            · {{ downloads.runningCount }} 个后台下载进行中，可在右上角「下载任务」查看进度
          </span>
        </span>
        <span class="lf-spacer" />
        <!--
          档位来自设备目录应答的 Info/DownloadSpeed。标准 9.9.1 b) 规定倍速由请求方在
          SDP 携带（a=downloadspeed:N），8 倍速下 1 小时录像约 7.5 分钟拿完。
          只影响下载；放列表页脚是因为时间轴视角根本不提供下载。
        -->
        <template v-if="speedOptions.length > 1">
          <span class="lf-label">下载方式</span>
          <el-select
            v-model="downloadSpeed"
            size="small"
            style="width: 132px"
            title="完整：原始码率，时长与画面都准确（推荐存档）／快进：设备只推关键帧，画面会跳"
          >
            <el-option :value="1" label="完整（1x）" />
            <el-option
              v-for="n in speedOptions.filter((x) => x > 1)"
              :key="n"
              :value="n"
              :label="`快进 ${n}x`"
            />
          </el-select>
        </template>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Download, Loading, VideoPlay } from '@element-plus/icons-vue'
import { useRoute, useRouter } from 'vue-router'
import { deviceApi, playbackApi, systemApi } from '@/api'
import { resolvePlayUrls } from '@/player/resolvePlayUrls'
import { useLivePlayer } from '@/composables/useLivePlayer'
import { useDownloadStore } from '@/stores/download'
import { useAuthStore } from '@/stores/auth'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

/** 后端下载接口要求 cloud:download，无权限时直接不画按钮，不让人点了才收 403。 */
const canDownload = computed(() => auth.can('cloud:download'))

/** 本页有三个入口（通道列表/通道管理/直接打开），回退比固定路由更符合直觉；无历史时回首页。 */
function goBack() {
  if (window.history.length > 1) {
    router.back()
  } else {
    router.push('/dashboard')
  }
}
/** 后台下载任务（全局）：本页只负责发起，进度统一在顶栏面板看。 */
const downloads = useDownloadStore()

const channels = ref([])
const records = ref([])
const searching = ref(false)
const searched = ref(false)
/** 区分「设备没答」与「答了但当天没录像」：两者都是空列表，但前者显示成没录像就是报假警。 */
const deviceUnanswered = ref(false)
/** 设备未应答时后端的说明。单独存一份：提示条会消失，而设备原文（如 400 wait for recordinfo timeout[15s]）是判断找谁的唯一线索。 */
const deviceNotice = ref('')
const query = reactive({ channelKey: '' })
/** 呈现视角：`timeline` 时间轴（默认）/ `list` 明细列表。 */
const viewMode = ref('timeline')

/**
 * 查询日期，形如 2026-10-26。只允许按天查：设备返回的是「与区间有交集」的录像段，
 * 任意区间会让一半结果落在框外，时间轴也无法定位色块。
 */
const day = ref(todayStr())

const current = ref(null)
const mediaHttpPort = ref(null)

/** 播放器容器（LivePlayer 在内部创建 video 元素）。 */
const videoContainer = ref(null)
const player = useLivePlayer(videoContainer)
/** 播放区容器：列表视图点「回放」后要滚到这里（否则画面在屏幕外，看不到自己在播）。 */
const playStage = ref(null)
const playing = computed(() => player.playing.value)

const serial = computed(() => query.channelKey.split('/')[0] || '')
const code = computed(() => query.channelKey.split('/')[1] || '')

/** 标题用编码而非名称：本平台就有两个「南坑水库」，只有编码能唯一定位到一路通道。 */
const timelineTitle = computed(() => {
  if (!serial.value || !code.value) return '未选择通道'
  return `${serial.value}-${code.value}`
})

/** 设备目录应答里的 Info/DownloadSpeed（本现场实测均为 1/2/4/8）；没报时只给 1 倍速。 */
const speedOptions = computed(() => {
  const ch = channels.value.find((c) => `${c.deviceId}/${c.id}` === query.channelKey)
  const list = String(ch?.downloadSpeed || '')
    .split('/')
    .map((s) => Number.parseInt(s.trim(), 10))
    .filter((n) => Number.isFinite(n) && n >= 1)
  return list.length ? [...new Set(list)].sort((a, b) => a - b) : [1]
})

/**
 * 下载倍速，默认 1 且不自动取最高档：设备对 a=downloadspeed:8 只推关键帧
 * （同一 3 分钟段：1 倍速 ≈360MB 完整流，8 倍速 14MB 且媒体时间跨 851 秒），
 * 且按墙钟计时导致录制范围不可控。存档必须用完整模式。
 */
const downloadSpeed = ref(1)

/** 当前通道的展示名（下载任务列表用）。 */
const channelLabel = computed(() => {
  const ch = channels.value.find((c) => `${c.deviceId}/${c.id}` === query.channelKey)
  return ch ? (ch.customName || ch.name || ch.id) : code.value
})

/** 表格行唯一键：同一通道可能有多个录像段，必须带上起止时间。 */
function rowKeyOf(row) {
  return `${row?.startTime || ''}|${row?.endTime || ''}`
}

/** 多数设备（LiveGBS、国产摄像机）不填 FilePath，240px 空列会把表格撑出横向滚动，整列隐藏。 */
const allNoFileName = computed(
  () => records.value.length > 0 && records.value.every((r) => !r.filePath)
)

/* ==================== 时间轴 ==================== */

/** 一天的毫秒数：时间轴的横轴全长就是这个常数。 */
const DAY_MS = 24 * 3600 * 1000

/** 查询日 00:00:00 的本地毫秒时间戳；日期非法时为 null。 */
const dayStartMs = computed(() => {
  if (!day.value) return null
  const t = new Date(`${day.value}T00:00:00`).getTime()
  return Number.isFinite(t) ? t : null
})

/** 时间串按本地时区解析。带空格的形式在部分浏览器返回 Invalid Date，统一换成 T。 */
function parseWall(v) {
  if (!v) return NaN
  const t = new Date(String(v).trim().replace(' ', 'T')).getTime()
  return Number.isFinite(t) ? t : NaN
}

function fmtClock(ms) {
  const d = new Date(ms)
  const p = (n) => String(n).padStart(2, '0')
  return `${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
}

/**
 * 时间轴上的录像色块。必须裁剪到当天：计划录像常见 22:00→次日 06:00 的跨天段，
 * 不裁剪 left 会为负、right 超 100%，色块画到轨道外。显示裁剪，播放仍用完整时间段。
 * 最小宽度 0.1%（≈86 秒）：几秒的碎片段按真实比例不足 1px，看不见会被当成没查出来。
 */
const timelineSegments = computed(() => {
  const base = dayStartMs.value
  if (base === null) return []
  const out = []
  for (const r of records.value) {
    const a = parseWall(r.startTime)
    const b = parseWall(r.endTime)
    if (!Number.isFinite(a) || !Number.isFinite(b) || b <= a) continue
    const s = Math.max(a, base)
    const e = Math.min(b, base + DAY_MS)
    if (e <= s) continue
    out.push({
      row: r,
      type: String(r.type || 'time').toLowerCase(),
      startMs: a,
      endMs: b,
      clippedStartMs: s,
      clippedEndMs: e,
      left: ((s - base) / DAY_MS) * 100,
      width: Math.max(((e - s) / DAY_MS) * 100, 0.1),
      // 跨天段：提示里要说清楚「只显示了当天这部分」
      clipped: a < base || b > base + DAY_MS
    })
  }
  return out
})

/* ==================== CMS 风格时间轴 ==================== */

/** 一天 1440 分钟 → 1440px（1px = 1 分钟），与 CMS 写死的一致；窗口窄时右侧裁掉，不做自适应。 */
const TIME_AXIS_PX = 1440

/** 每个刻度块代表的分钟数：1440px / 288 块 = 5px = 5 分钟。 */
const MINUTE_BLOCK_MIN = 5

/** 一天有多少个刻度块。 */
const MINUTE_BLOCK_COUNT = TIME_AXIS_PX / MINUTE_BLOCK_MIN

/** 两位补零。 */
function pad2(n) {
  return String(n).padStart(2, '0')
}

/** 小时刻度：0..23（CMS 显示到 23:00，不显示 24:00）。 */
const hourTicks = Array.from({ length: 24 }, (_, i) => i)

/**
 * 288 个刻度块，标记每个 5 分钟区间有没有录像。CMS 是「一整排等宽小块」而不是
 * 任意宽度的矩形，必须按分钟网格离散化：段覆盖到的块都点亮，1 秒的碎片段也点亮整块。
 */
const minuteBlocks = computed(() => {
  const base = dayStartMs.value
  const has = new Array(MINUTE_BLOCK_COUNT).fill(false)
  const rules = new Array(MINUTE_BLOCK_COUNT).fill(null)
  if (base !== null) {
    const blockMs = MINUTE_BLOCK_MIN * 60_000
    for (const s of timelineSegments.value) {
      const from = Math.max(0, Math.floor((s.clippedStartMs - base) / blockMs))
      const to = Math.min(MINUTE_BLOCK_COUNT, Math.ceil((s.clippedEndMs - base) / blockMs))
      for (let i = from; i < to; i++) {
        has[i] = true
        // 同一块被多段覆盖时只留第一段的提示，够用了
        if (!rules[i]) rules[i] = s
      }
    }
  }
  return has.map((h, i) => ({
    i,
    has: h,
    tip: h && rules[i] ? segTitle(rules[i]) : ''
  }))
})

/**
 * 红色游标的像素位置。优先跟播放进度；没在播放时退回当前时刻（CMS 的红线就是「现在」）。
 * 非今天且没在播放时返回 null —— 历史日期上画「今天此刻」是错的。
 */
const cursorPx = computed(() => {
  const base = dayStartMs.value
  if (base === null) return null
  if (playhead.value) {
    return Math.min(TIME_AXIS_PX, Math.max(0, (playhead.value.percent / 100) * TIME_AXIS_PX))
  }
  const now = nowTick.value
  if (now < base || now >= base + DAY_MS) return null
  return ((now - base) / DAY_MS) * TIME_AXIS_PX
})

/** 只到 HH:mm：CMS 的游标标签是 60px 宽，带秒会溢出。 */
const cursorClock = computed(() => {
  const text = playhead.value ? playhead.value.clock : fmtClock(nowTick.value)
  return text.slice(0, 5)
})

/** 时间轴 / 列表 视角切换（CMS 是一个按钮，不是单选组）。 */
function toggleView() {
  viewMode.value = viewMode.value === 'timeline' ? 'list' : 'timeline'
}

/** 色块的悬浮提示：起止 + 时长（跨天段额外说明）。 */
function segTitle(s) {
  const dur = fmtDuration((s.endMs - s.startMs) / 1000)
  const tail = s.clipped ? '（跨天段，此处只画当日部分）' : ''
  return `${fmtClock(s.startMs)} ~ ${fmtClock(s.endMs)}，时长 ${dur}${tail}`
}

/**
 * 鼠标位置 → 当天毫秒 + 百分比。必须按内层 .time-day 量宽度：外框有 max-width:1440px，
 * 窗口窄时它只有几百像素而轨道仍是 1440px，用它当分母实测点 15:00 会跳到 23:00 的段。
 */
function trackPoint(ev) {
  const base = dayStartMs.value
  if (base === null) return null
  const rule = ev.currentTarget
  const track = rule && rule.querySelector ? rule.querySelector('.time-day') : null
  const rect = (track || rule).getBoundingClientRect()
  if (!rect.width) return null
  const ratio = Math.min(1, Math.max(0, (ev.clientX - rect.left) / rect.width))
  return { at: base + ratio * DAY_MS, percent: ratio * 100 }
}

/** 「当前时刻」脉冲：Date.now() 不是响应式的，直接写在 computed 里会被缓存住，需定时器推进。 */
const nowTick = ref(Date.now())

/** nowTick 的定时器句柄。 */
let nowTimer = null

/** 时间轴点击：同段 → 定位（SIP INFO npt，毫秒级、不断流）；异段或未播 → 从该时刻起播。 */
function onTrackClick(ev) {
  const base = dayStartMs.value
  if (base === null) return
  const point = trackPoint(ev)
  if (!point) return
  const hit = timelineSegments.value.find(
    (s) => point.at >= s.clippedStartMs && point.at < s.clippedEndMs
  )
  if (!hit) {
    ElMessage.info('该时刻没有录像')
    return
  }
  const offset = Math.floor((point.at - parseWall(hit.row.startTime)) / 1000)
  /*
   * 同段就走定位，条件里不能带 playing.value：缓冲/重连时它并非 true，
   * 会掉进下面的 play() → stop + 重新 INVITE，用户看到「拖一下整路重来」。
   */
  if (current.value && hit.row === current.value) {
    seekTo(Math.max(0, offset))
    return
  }
  play(hit.row, { startOffset: Math.max(0, offset) })
}

/* ==================== 设备录像状态 ==================== */

/** 设备侧的录像类型 → 中文（与时间轴图例保持一致）。 */
const TYPE_LABELS = { time: '定时', alarm: '报警', manual: '手动' }

function typeLabel(type) {
  return TYPE_LABELS[String(type || 'time').toLowerCase()] || type || '定时'
}

/** 表格里的时间去掉 ISO 的 `T`（设备不填 filePath 时它是最重要的列之一）。 */
function prettyTime(v) {
  return v ? String(v).replace('T', ' ') : '—'
}


async function loadChannels() {
  try {
    const data = await deviceApi.channelList({ start: 0, limit: 1000 })
    channels.value = data?.channelList || []
  } catch (e) {
    ElMessage.error(e.message || '加载通道失败')
  }
}

/** 确保已拿到媒体端口（播放地址要用它与当前主机名重写）。 */
async function ensureMediaPort() {
  if (mediaHttpPort.value) return true
  try {
    const info = await systemApi.serverinfo()
    mediaHttpPort.value = info?.mediaHttp
  } catch (e) {
    // 拿不到端口不影响播放地址拼装（缺省用后端返回的原始地址）
  }
  return true
}

/** 换通道后直接查当天：用户已经选了通道，还要再点一次「检索」是多余动作。 */
function onChannelChange() {
  records.value = []
  searched.value = false
  deviceUnanswered.value = false
  deviceNotice.value = ''
  // 设备侧检索是串行问答，同通道并发查询会互相覆盖应答回调，一次切换只发一条
  search()
}

/** 日期 → `YYYY-MM-DD`（本地时区，不用 toISOString 以免被 UTC 拉走一天）。 */
function fmtDate(d) {
  const p = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`
}

/** 时间 → `YYYY-MM-DD HH:mm:ss`（设备侧检索入参的格式）。 */
function fmtFull(d) {
  const p = (n) => String(n).padStart(2, '0')
  return `${fmtDate(d)} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
}

function todayStr() {
  return fmtDate(new Date())
}

/** 是否已在「今天」：今天之后没有录像可查，后一天/今天按钮置灰。 */
const isToday = computed(() => day.value >= todayStr())

/** 查询区间：当天 00:00:00 ~ 23:59:59。 */
const dayStartTime = computed(() => (day.value ? `${day.value} 00:00:00` : ''))
const dayEndTime = computed(() => (day.value ? `${day.value} 23:59:59` : ''))

/** 翻一天。不允许翻到未来（设备上不可能有未来的录像）。 */
function shiftDay(n) {
  if (!day.value) return
  const d = new Date(`${day.value}T00:00:00`)
  if (!Number.isFinite(d.getTime())) return
  d.setDate(d.getDate() + n)
  if (fmtDate(d) > todayStr()) return
  day.value = fmtDate(d)
  search()
}

function goToday() {
  day.value = todayStr()
  search()
}

function onDayChange() {
  if (query.channelKey) search()
}

/** 空列表有三种原因，文案必须分开：「设备没答」说成「当日没有录像」是报假警。 */
const timelineEmptyText = computed(() => {
  if (!searched.value) return '选择通道后点「检索」'
  if (deviceUnanswered.value) {
    return deviceNotice.value || '设备未应答检索，无法确认当日是否有录像'
  }
  return '当日没有录像'
})

/** 检索序号：只认最后一次的结果。设备检索要几秒，先发的晚回来会把新通道的结果覆盖成旧通道。 */
let searchSeq = 0

async function search() {
  if (!query.channelKey) {
    ElMessage.warning('请先选择通道')
    return
  }
  if (!day.value) {
    ElMessage.warning('请选择日期')
    return
  }
  const seq = ++searchSeq
  searching.value = true
  try {
    const data = await playbackApi.recordList({
      serial: serial.value,
      code: code.value,
      starttime: dayStartTime.value,
      endtime: dayEndTime.value
    })
    // 期间用户又换了通道/日期：这次结果已经过期，丢弃
    if (seq !== searchSeq) return
    records.value = data?.recordList || []
    searched.value = true
    deviceUnanswered.value = data?.deviceAnswered === false
    deviceNotice.value = data?.notice || ''
    // 设备没应答 ≠ 没有录像：后端 notice 会说明（含“下面是缓存结果”），不要一律弹「当日无录像」
    if (deviceUnanswered.value) {
      ElMessage.warning(data.notice || '设备未应答录像检索')
    } else if (!records.value.length) {
      ElMessage.info('当日无录像')
    }
  } catch (e) {
    if (seq === searchSeq) ElMessage.error(e.message || '检索失败')
  } finally {
    if (seq === searchSeq) searching.value = false
  }
}


/** 画面起不来时的重拉次数。只在用户主动点播时清零，自动重建沿用，否则会无限重试。 */
let stallRetry = 0

/** 最多重拉几次。超过就停下来告诉用户，而不是一直重试刷设备。 */
const MAX_STALL_RETRY = 2

/**
 * 发起回放并播放。
 * @param {object} row 录像段
 * @param {boolean} [opts.rebuild] 内部重拉（不清零重试计数）
 * @param {number} [opts.startOffset] 从本段第几秒开始播（拖动定位用）
 */
async function play(row, opts = {}) {
  if (!opts.rebuild) stallRetry = 0
  const offset = Math.max(0, Math.floor(Number(opts.startOffset) || 0))
  // 画面在页面顶部，列表里点「回放」时它可能已在屏幕外，滚进视野
  if (playStage.value) {
    playStage.value.scrollIntoView({ behavior: 'smooth', block: 'nearest' })
  }
  // 先请求后起播：请求被拒（如通道正在后台下载、会话被锁）时不会留下「有画面但空白」的状态
  await ensureMediaPort()

  let info = null
  try {
    // 回放响应已包含全部播放地址
    info = await playbackApi.start({
      serial: serial.value,
      code: code.value,
      filePath: row.filePath,
      // 定位靠「改 INVITE 的 t= 起点」，不是靠设备侧的 npt 跳转，见 seekTo
      starttime: offset > 0 ? addSeconds(row.startTime, offset) : row.startTime,
      endtime: row.endTime
    })
  } catch (e) {
    ElMessage.error(e.message || '回放启动失败')
    return
  }

  current.value = row
  // 进度条总长取录像段的起止时间差；位置归零
  segmentSeconds.value = segmentLengthOf(row)
  // 本段从第 offset 秒开始推，媒体时间轴的 0 点对应段内这个位置
  positionOffset.value = offset
  // 新会话一律回 1 倍速：设备按 INVITE 时的速率起推，本地若停在上段的 2 倍速会立刻抽干缓冲
  clearRateWatch()
  if (seekTimer) {
    clearTimeout(seekTimer)
    seekTimer = null
  }
  pendingSeek = null
  clearSeeking()
  clearSeekFallback()
  // 新会话设备从 1 倍速起推，本地记录的「已下发倍速」也要同步归位
  deviceRate = 1
  speedMode.value = 'device'
  if (playRate.value !== 1) {
    playRate.value = 1
  }
  await sleep(120)

  const urls = resolvePlayUrls(info, mediaHttpPort.value)
  if (!urls.wsFlv && !urls.webrtc) {
    ElMessage.error('未获取到回放地址')
    return
  }
  /*
   * 优先 WebRTC，退回 WS-FLV。不走纯 FLV：video.js 9 + videojs-flvjs 的 flv 源
   * 处理器注册不全，Html5.canPlayType() 会抛异常，被判成「不支持的类型」后
   * 起播看门狗反复重拉（表现为一直转圈）。WebRTC 还由 ZLM 连续重打时间戳，
   * npt 跳转不会像 FLV 那样在 MSE 里留下缓冲空洞。
   */
  const playbackProto = urls.webrtc ? 'webrtc' : 'wsflv'
  await player.play(playbackKey.value, urls, {
    proto: playbackProto,
    // 不传 _vod：国标回放的媒体面就是设备主动推 RTP，与直播同形状。按点播传会让
    // flv.js 去等一个永远不会来的时长；定位入口是页面自己的时间轴，不依赖进度条。
    // 控制栏保持开启（引擎默认）：曾传 _noControls:true 而页面并未自备控件，
    // 导致没有暂停/音量/全屏/截图。进度条由引擎按实时流统一隐藏。
    // 必须接 onStall，否则看门狗触发后什么也不发生。播放器自己重连没用 —— 连的是同一个媒体源；
    // 这里要停会话 + 重新 INVITE（换 SSRC、ZLM 重建媒体源）。
    // 典型成因：某下级平台应答后 11 秒才推视频，ZLM 等轨道超时会把 H264 轨永久丢弃。
    onStall: rebuildStream,
    // 稳定播放 10 秒后归还重拉预算，否则几次无关的起播故障会把 2 次预算永久耗光
    onStable: () => { stallRetry = 0 },
    // 官方控件动作 → 国标 SIP 指令（INFO 定位 / PLAY / PAUSE / Scale）
    vod: {
      duration: segmentSeconds.value,
      onSeek: (sec) => seekTo(sec),
      onPause: () => control('pause'),
      onResume: () => resumePlayback(),
      onRate: (rate) => control('scale', { scale: rate }),
      // 媒体时间轴突变：段内位置 = positionOffset + 媒体时间，媒体自己跳了 N 秒就
      // 要把 offset 反向退掉 N 秒，否则跳转被计入两次，进度条与画面对不上
      onJump: (delta) => {
        positionOffset.value = (positionOffset.value || 0) - delta
        // 媒体时间轴已经跳到新位置，说明设备执行完了，不用再走兜底
        clearSeeking()
        clearSeekFallback()
      }
    }
  })
}

/** 把 {"YYYY-MM-DDTHH:mm:ss"} 加上若干秒，返回同格式字符串。 */
function addSeconds(timeStr, seconds) {
  const t = new Date(String(timeStr).replace(' ', 'T')).getTime()
  if (!Number.isFinite(t)) return timeStr
  const d = new Date(t + seconds * 1000)
  const p = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`
    + `T${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
}

/**
 * 拖动/点击定位：发 SIP INFO（Range: npt=<秒>-）让设备原地跳，配 200ms 防抖。
 *
 * 实测证据：同一次会话发 Range: npt=3600-，画面 OSD 从 00:00:17 跳到 01:00:18。
 * 曾误判为「设备不支持 npt」改成停流重开，真因是 INFO 的 From/To tag 取反、
 * 报文落在对话框之外，设备回 200 OK 却不执行。
 *
 * 防抖：拖动会连续触发，设备每秒只处理几条，多余指令会排队或被丢弃。
 * 失败只如实提示，不再自动重拉 —— 不支持 npt 时重拉也定位不到目标。
 */
let seekTimer = null
let pendingSeek = null
function seekTo(sec) {
  const row = current.value
  if (!row) return
  pendingSeek = Math.max(0, Math.floor(Number(sec) || 0))
  if (seekTimer) clearTimeout(seekTimer)
  seekTimer = setTimeout(() => {
    seekTimer = null
    if (pendingSeek !== null) flushSeek(pendingSeek)
  }, 200)
}

/** 正在等设备执行定位（画面上盖一层提示，避免「卡住了」的错觉）。 */
const seeking = ref(false)
let seekingTimer = null

/** npt 定位的兜底定时器（超时未跳就退回「停会话 + 重新 INVITE」）。 */
let seekFallbackTimer = null

function clearSeeking() {
  if (seekingTimer) {
    clearTimeout(seekingTimer)
    seekingTimer = null
  }
  seeking.value = false
}

function clearSeekFallback() {
  if (seekFallbackTimer) {
    clearTimeout(seekFallbackTimer)
    seekFallbackTimer = null
  }
}

/**
 * 定位兜底：npt 是「请设备跳」，设备可以不理。6 秒内媒体时间轴没跳变就退回
 * WVP 的做法（停会话 + 按新起点重新 INVITE，代价 2~3 秒黑屏）。
 */
function armSeekFallback(offset) {
  clearSeekFallback()
  const base = player.position.value || 0
  seekFallbackTimer = setTimeout(() => {
    seekFallbackTimer = null
    if (!current.value) return
    const moved = Math.abs((player.position.value || 0) - base)
    // 时间轴已经跳了（或本来就是同一位置），说明 npt 生效
    if (moved > 3) return
    clearSeeking()
    restartAt(offset)
  }, 6000)
}

/** 真正发出定位指令，并把本地进度条挪到目标位置。 */
async function flushSeek(offset) {
  pendingSeek = null
  try {
    await playbackApi.control({
      streamid: playbackKey.value,
      serial: serial.value,
      code: code.value,
      command: 'seek',
      range: offset
    })
    // 乐观挪动进度：设备跳转后媒体时间轴不重置，段内位置 = 偏移 + 媒体已播秒数。
    // 把偏移改成「目标 - 当前」，否则进度条弹回原处，用户以为没生效（画面其实跳了）
    positionOffset.value = Math.max(0, offset - (player.position.value || 0))
    // 设备跳转有 1~2 秒处理时间，期间盖「定位中…」；媒体时间轴一跳就撤掉
    clearSeeking()
    seeking.value = true
    seekingTimer = setTimeout(clearSeeking, 3000)
    armSeekFallback(offset)
  } catch (e) {
    clearSeeking()
    clearSeekFallback()
    ElMessage.warning(e.message || '定位失败：该设备可能不支持 npt 跳转')
  }
}

/** 从本段第 offsetSeconds 秒重新开始播放（停会话 → 重新 INVITE）。只作兜底用。 */
async function restartAt(offsetSeconds) {
  const row = current.value
  if (!row) return
  try {
    await playbackApi.stop({ serial: serial.value, code: code.value })
  } catch (e) {
    // 会话可能已不存在（设备已 BYE），继续重开即可
  }
  await play(row, { rebuild: true, startOffset: offsetSeconds })
}

/** 重拉整条回放流。只在媒体源丢了视频轨、或连上却收不到字节时调用 —— 重连 WebSocket 没用。 */
async function rebuildStream() {
  const row = current.value
  if (!row) return
  if (stallRetry >= MAX_STALL_RETRY) {
    ElMessage.error('画面无法就绪，已重试多次。请返回后重新点播，或检查设备推流')
    return
  }
  stallRetry++
  // 重拉后从原来的位置接着看（靠 INVITE 的 t= 定位，不依赖设备 npt）
  await restartAt(Math.floor(player.position.value || 0))
}

/* ==================== 录像下载 ==================== */
/*
 * 设备的录像没有可直接访问的 HTTP 地址（FilePath 多数为空），所以「下载」= 用回放
 * 把流拉下来、ZLM 同时录成本地 MP4，录完给浏览器一个支持 Range 的链接。
 * 任务状态由服务端持有，本页只负责发起；进度存本组件会在切页时丢失，且离开时的
 * playback/stop 会把下载中的流停掉。
 */

/**
 * 开始下载某段录像（后台执行，可离开本页）。顺序不能颠倒：必须先有回放流 ZLM 才能录；
 * 正在看回放时复用同一会话键，不重复 INVITE。
 * @param row 录像段（框选下载时是构造的伪行）
 * @param override seconds 指定时长、speed 指定倍速（框选下载用它强制 1 倍速，不能叠加掉帧的倍速）
 */
async function startDownload(row, override = {}) {
  const seconds = override.seconds || segmentLengthOf(row)
  if (!seconds) {
    ElMessage.warning('该录像段缺少起止时间，无法下载')
    return
  }
  if (!(await ensureMediaPort())) return
  const speed = override.speed || downloadSpeed.value
  const nowKey = rowKeyOf(row)
  downloadingKey.value = nowKey
  try {
    const ok = await downloads.start({
      serial: serial.value,
      code: code.value,
      filePath: row.filePath,
      starttime: row.startTime,
      endtime: row.endTime,
      duration: seconds,
      speed,
      // 区间下载不知道这一小截的确切大小，传 0 让后端按时长判完成
      fileSize: override.seconds ? 0 : (row.fileSize || 0),
      name: channelLabel.value
    })
    if (ok) {
      ElMessage.success(
        `已提交后台下载（${fmtDuration(seconds)}），可在右上角「下载任务」查看进度`
      )
    }
  } finally {
    downloadingKey.value = ''
  }
}

/** 正在发起下载的行（防重复点击 / 按钮 loading）。 */
const downloadingKey = ref('')

/** 下载当前回放段（工具栏按钮）。复用 startDownload，两者下的是同一段。 */
async function downloadCurrent() {
  if (!current.value) return
  await startDownload(current.value)
}

/** 悬浮提示提前说明耗时与画质差异：完整≈段长；快进快但产物跳帧，不说明会被当成平台坏了。 */
function downloadHint(row) {
  if (hasTaskOfRow(row)) return '该段已在后台下载，见右上角「下载任务」'
  const sec = segmentLengthOf(row)
  if (!sec) return '下载该段录像'
  const real = fmtDuration(sec)
  const speed = downloadSpeed.value
  if (speed > 1) {
    return `快进下载 ${real} 的录像：设备只推关键帧，画面会跳，`
      + `约需 ${fmtDuration(sec / speed)}（仅适合快速翻看，存档请选「完整」）`
  }
  return `下载 ${real} 的完整录像，约需 ${real}（后台运行，可继续浏览其它页面）`
}

/** 该行是否正在发起下载（按钮 loading 用）。 */
function isDownloadingRow(row) {
  return !!downloadingKey.value && downloadingKey.value === rowKeyOf(row)
}

/** 该行是否已有后台任务（按钮禁用/改文案用）。 */
function hasTaskOfRow(row) {
  const key = rowKeyOf(row)
  return downloads.tasks.some((t) => {
    // 后端任务的 startAt/endAt 用 ISO，与表格的 startTime/endTime 一致
    const range = `${t.startAt || ''}|${t.endAt || ''}`
    return range === key && (!t.finished || t.ok)
  })
}

/**
 * 回放控制：暂停 / 继续 / 倍速 / 拖动。必须带 streamid：同一通道通常还有一路直播
 * （云端录像保活），后端按 serial/code 定位会命中直播会话，指令发到错的 SIP 对话框，
 * 设备回 200 却不执行。
 */
async function control(command, extra = {}) {
  if (!current.value) return false
  try {
    await playbackApi.control({
      streamid: playbackKey.value,
      serial: serial.value,
      code: code.value,
      command,
      ...extra
    })
    if (command === 'pause') ElMessage.success('已暂停')
    else if (command === 'play') ElMessage.success('已继续')
    return true
  } catch (e) {
    ElMessage.error(e.message || '控制失败')
    return false
  }
}

/**
 * 继续播放：优先发 SIP PLAY npt=now-（毫秒级），失败才重新协商 —— 停会话 + INVITE 要 2~3 秒黑屏。
 * 暂停期间设备停推 RTP，ZLM 对空 RTP 会话有硬超时（实测十几秒到 100 秒）会注销媒体源，
 * 那时 INFO 发成功但没画面，由看门狗（12 秒无关键帧 / 20 秒无字节）触发 rebuildStream。
 */
async function resumePlayback() {
  const row = current.value
  if (!row) return
  try {
    await playbackApi.control({
      serial: serial.value,
      code: code.value,
      command: 'play'   // 不带 range → 后端发 PLAY npt=now-（从当前位置续推）
    })
  } catch (e) {
    ElMessage.warning(e.message || '续播指令失败，正在重新拉流…')
    await restartAt(Math.floor(player.position.value || 0))
  }
}

/* ==================== 进度显示 ==================== */
/*
 * 进度条与播放控制全部由 LivePlayer 官方控制栏提供。国标回放是「伪点播」：
 * ZLM 的 .live.flv 不带 duration，整段起止只有业务侧知道（录像检索应答），
 * 由 useLivePlayer 的 vod 层喂给 video.js 的 customDuration。
 */

/** 录像段总时长（秒）。 */
const segmentSeconds = ref(0)
/** 本段起始偏移（秒）：从段中间起播时，媒体时间轴 0 点对应的段内位置。 */
const positionOffset = ref(0)
/** 播放位置（相对录像段起点的秒数）。 */
const playPosition = computed(() =>
  Math.min(segmentSeconds.value, positionOffset.value + (player.position.value || 0))
)
/** 回放倍速（国标 Scale）。 */
const playRate = ref(1)

/**
 * 播放头在当天时间轴上的位置（时间轴兼作进度条）。
 * 换算：段起始绝对时刻 + 段内偏移 + 媒体已播时间，再除以一天。
 */
const playhead = computed(() => {
  const row = current.value
  const base = dayStartMs.value
  if (!row || base === null) return null
  const segStart = parseWall(row.startTime)
  if (!Number.isFinite(segStart)) return null
  const at = segStart + (positionOffset.value + (player.position.value || 0)) * 1000
  const percent = ((at - base) / DAY_MS) * 100
  if (!Number.isFinite(percent)) return null
  return {
    percent: Math.min(100, Math.max(0, percent)),
    clock: fmtClock(at)
  }
})

/**
 * 倍速切换。不能只改本地 playbackRate：拿到的是设备按实时速率推的流，本地 2 倍速
 * 几秒就把缓冲抽干，真正加速只能让设备发 Scale。
 *
 * Scale 是「请求」不是「保证」：南坑水库（下级平台）收到 Scale: 2.0 后直接停推，
 * 媒体源被注销、播放器 404。判据不能用「媒体时间走了多少」—— 缓冲富余时它总按
 * 本地速率走。改为只发指令、本地保持 1 倍速，4 秒后看已缓冲末尾前进了多少秒
 * （只随收到的数据量走）。不达标就记入本地黑名单，改用跳播快进。
 */
async function onRateChange(rate) {
  clearRateWatch()
  playRate.value = rate
  if (!current.value) return

  if (rate === 1) {
    player.setPlaybackRate(1)
    speedMode.value = 'device'
    // 只有确实让设备加速过才需要复位，否则又是一条可能把流打死的指令
    if (deviceRate !== 1) {
      deviceRate = 1
      await control('scale', { scale: 1 })
    }
    return
  }

  if (speedUnsupported.has(speedKey())) {
    startJumpSpeed()
    return
  }

  await control('scale', { scale: rate })
  const startBuffer = player.bufferEnd()
  const startAt = Date.now()
  rateWatchTimer = setTimeout(() => {
    rateWatchTimer = null
    if (playRate.value !== rate) return
    const elapsed = (Date.now() - startAt) / 1000
    const advanced = player.bufferEnd() - startBuffer
    if (advanced >= elapsed * rate * 0.6) {
      deviceRate = rate
      player.setPlaybackRate(rate)
      speedMode.value = 'device'
      return
    }
    markSpeedUnsupported()
    deviceRate = 1
    ElMessage.warning('该设备不支持信令变速，改用跳播快进（画面按段向前跳）')
    startJumpSpeed()
  }, 4000)
}

/** 当前倍速的实现方式：{@code device} = 设备侧 Scale，{@code jump} = 本地跳播。 */
const speedMode = ref('device')

/** 最近一次成功下发给设备的倍速，用于判断要不要发复位指令。 */
let deviceRate = 1

/** 变速失败过的通道，按「设备/通道」记在本地，避免每次重试都把流打死。 */
const SPEED_UNSUPPORTED_KEY = 'gb.playback.speedUnsupported'

function loadSpeedUnsupported() {
  try {
    const raw = localStorage.getItem(SPEED_UNSUPPORTED_KEY)
    return raw ? JSON.parse(raw) : []
  } catch (e) {
    return []
  }
}

const speedUnsupported = new Set(loadSpeedUnsupported())

function speedKey() {
  return `${serial.value}/${code.value}`
}

function markSpeedUnsupported() {
  speedUnsupported.add(speedKey())
  try {
    localStorage.setItem(SPEED_UNSUPPORTED_KEY, JSON.stringify([...speedUnsupported]))
  } catch (e) {
    // 隐私模式禁写：本次会话内记住就够了
  }
}

/** 跳播快进的定时器句柄。 */
let jumpTimer = null

/** 跳播快进：设备支持 npt 却不支持 Scale，用连续跳播模拟（2 倍速 = 每 2 秒墙钟跳 4 秒）。 */
function startJumpSpeed() {
  clearJumpTimer()
  speedMode.value = 'jump'
  player.setPlaybackRate(1)
  const STEP_MS = 2000
  jumpTimer = setInterval(async () => {
    if (!current.value) return
    if (playRate.value === 1) {
      clearJumpTimer()
      speedMode.value = 'device'
      return
    }
    const next = Math.floor((player.position.value || 0) + (playRate.value * STEP_MS) / 1000)
    const duration = Number(segmentSeconds.value) || 0
    if (duration > 0 && next >= duration - 2) {
      // 到段尾：交回正常播放/切段逻辑，别把设备跳到越界位置
      clearJumpTimer()
      playRate.value = 1
      speedMode.value = 'device'
      ElMessage.info('已到本段末尾')
      return
    }
    try {
      await playbackApi.control({
        streamid: playbackKey.value,
        serial: serial.value,
        code: code.value,
        command: 'seek',
        range: next
      })
      positionOffset.value = Math.max(0, next - (player.position.value || 0))
    } catch (e) {
      clearJumpTimer()
      playRate.value = 1
      speedMode.value = 'device'
      ElMessage.warning(e.message || '跳播快进失败，已恢复 1 倍速')
    }
  }, STEP_MS)
}

function clearJumpTimer() {
  if (jumpTimer) {
    clearInterval(jumpTimer)
    jumpTimer = null
  }
}

/** 倍速生效性检查的定时器。 */
let rateWatchTimer = null

function clearRateWatch() {
  if (rateWatchTimer) {
    clearTimeout(rateWatchTimer)
    rateWatchTimer = null
  }
  clearJumpTimer()
}

/**
 * 回放会话键，必须带 _playback 后缀。用裸的「设备/通道」会命中直播会话
 * （云端录像保活让通道长期有直播流），播出来是实时画面。
 */
const playbackKey = computed(() => `${serial.value}/${code.value}_playback`)




/** 当前画面对应的墙钟时间。界面不展示（控制栏已显示段内秒数），留给截图加水印等场景。 */
const wallClock = computed(() => {
  if (!current.value?.startTime) return '—'
  const base = new Date(String(current.value.startTime).replace(' ', 'T')).getTime()
  if (!Number.isFinite(base)) return '—'
  const t = new Date(base + playPosition.value * 1000)
  const p = (n) => String(n).padStart(2, '0')
  return `${t.getFullYear()}-${p(t.getMonth() + 1)}-${p(t.getDate())} `
    + `${p(t.getHours())}:${p(t.getMinutes())}:${p(t.getSeconds())}`
})

/** 计算录像段时长（秒）。 */
function segmentLengthOf(row) {
  if (!row?.startTime || !row?.endTime) return 0
  const a = new Date(String(row.startTime).replace(' ', 'T')).getTime()
  const b = new Date(String(row.endTime).replace(' ', 'T')).getTime()
  const diff = Math.floor((b - a) / 1000)
  return Number.isFinite(diff) && diff > 0 ? diff : 0
}

/**
 * 释放当前回放会话，只在离开页面时调用（界面上没有「停止回放」按钮）。
 * 不停的话设备会继续推流，平台会话要等 45 秒空闲巡检才回收，白占上行带宽。
 * 下载中不停流：下载与回放共用同一会话键，停掉等于掐掉正在录的源（服务端也有同样保护）。
 */
function releasePlayback() {
  player.stop()
  const had = !!current.value
  current.value = null
  segmentSeconds.value = 0
  positionOffset.value = 0
  if (had && !downloads.runningCount) {
    playbackApi.stop({ serial: serial.value, code: code.value }).catch(() => {})
  }
}

function shortName(path) {
  if (!path) return '—'
  const parts = path.split('/')
  return parts[parts.length - 1]
}

/** 秒 → 「1 小时 20 分」这种可读时长（下载提示用）。 */
function fmtDuration(sec) {
  const s = Math.max(0, Math.round(Number(sec) || 0))
  const h = Math.floor(s / 3600)
  const m = Math.floor((s % 3600) / 60)
  const r = s % 60
  if (h > 0) return m > 0 ? `${h} 时 ${m} 分` : `${h} 时`
  if (m > 0) return `${m} 分 ${r} 秒`
  return `${r} 秒`
}

function formatSize(bytes) {
  if (!bytes) return '—'
  const units = ['B', 'KB', 'MB', 'GB']
  let v = Number(bytes)
  let i = 0
  while (v >= 1024 && i < units.length - 1) {
    v /= 1024
    i++
  }
  return `${v.toFixed(i ? 1 : 0)} ${units[i]}`
}

function sleep(ms) {
  return new Promise((r) => setTimeout(r, ms))
}

onMounted(async () => {
  // 未播放时游标显示当前时刻，靠这个定时器推进。30 秒足够：1 分钟 = 1px，更密看不出差别
  nowTimer = setInterval(() => { nowTick.value = Date.now() }, 30_000)
  await loadChannels()
  // 从通道列表点「设备录像」跳进来时预填设备/通道
  const serial = String(route.query.serial || '').trim()
  const code = String(route.query.code || '').trim()
  if (serial && code) {
    query.channelKey = `${serial}/${code}`
    // 预填走 query 参数不经过 onChannelChange，这里必须自己检索一次
    search()
  }
})
onUnmounted(() => {
  if (nowTimer) clearInterval(nowTimer)
  nowTimer = null
  // 清掉定位防抖与倍速检查的定时器，否则组件销毁后仍会对已失效会话发请求
  clearRateWatch()
  if (seekTimer) {
    clearTimeout(seekTimer)
    seekTimer = null
  }
  pendingSeek = null
  clearSeeking()
  clearSeekFallback()
  releasePlayback()
})
</script>

<style scoped>
/* ============================================================
   CMS（LiveGBS）风格：尺寸与颜色取自 CMS 实际渲染值
   （卡片 #fff/3px；按钮高 29px、字 12px；时间轴外框 #ccc 50px；游标 2px 红）。
   ============================================================ */

/* 对齐 CMS 的 .box */
.pb-card {
  background: #fff;
  border-radius: 3px;
  box-shadow: 0 1px 1px rgba(0, 0, 0, 0.1);
  padding: 10px;
  display: flex;
  flex-direction: column;
  min-height: 0;
}

/* 标题：CMS 是 h4，18px / 500 / 居中 / 上下 10px */
.pb-title {
  margin: 10px 0;
  font-size: 18px;
  font-weight: 500;
  text-align: center;
  color: #333;
  line-height: 1.1;
}

.pb-body {
  display: flex;
  flex-direction: column;
  min-height: 0;
}

/* ==================== 工具栏 ==================== */

.pb-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  /*
   * 能放下就保持一行（对齐 CMS），放不下才换行。
   * 去掉 wrap 会在窄屏把按钮挤出可见区域，比换行更糟。
   */
  flex-wrap: wrap;
  margin-bottom: 15px;
}

.pb-tools-left,
.pb-tools-right {
  display: flex;
  align-items: center;
  gap: 0;
  flex-wrap: wrap;
}

/* CMS 用 8px 的空 span 当间隔，这里同样处理 */
.cms-gap {
  display: inline-block;
  width: 8px;
}

.cms-ico {
  font-size: 15px;
  line-height: 1;
  margin-right: 2px;
}

/*
 * CMS 的 .btn：29px 高、12px 字。不用 el-button（small 24px / default 32px），
 * 和日期框并排会差几像素。
 */
.cms-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  height: 29px;
  padding: 0 10px;
  font-size: 12px;
  line-height: 1.5;
  border-radius: 3px;
  border: 1px solid #ddd;
  background: #f4f4f4;
  color: #444;
  cursor: pointer;
  white-space: nowrap;
  transition: background-color 0.12s, border-color 0.12s;
}

.cms-btn:hover:not(:disabled) {
  background: #e6e6e6;
  border-color: #adadad;
}

.cms-btn:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}

.cms-btn-primary {
  background: #556677;
  border-color: #404d59;
  color: #fff;
}

.cms-btn-primary:hover:not(:disabled) {
  background: #46545f;
  border-color: #333c45;
}

/* 方形的翻页按钮：宽度收到最小，只放一个箭头 */
.cms-btn-sq {
  width: 26px;
  padding: 0;
  font-size: 14px;
}

.pb-daynav {
  display: inline-flex;
}

.pb-daynav .cms-btn:first-child {
  border-radius: 3px 0 0 3px;
}

.pb-daynav .cms-btn:last-child {
  border-radius: 0 3px 3px 0;
  border-left: 0;
}

/* ==================== 下拉与日期框（压到 29px 高） ==================== */

.pb-sel {
  width: 150px;
}

.pb-sel-channel {
  width: 200px;
}

.pb-sel-rate {
  width: 96px;
}

/*
 * 宽度写在模板的 style="width:150px"：Element Plus 的 .el-date-editor.el-input 是
 * 双类选择器（0,2,0），scoped 单类压不过它，实测仍渲染成 220px 并逼工具栏换行。
 */
.pb-sel-date {
  margin: 0 8px 0 0;
}

.pb-sel :deep(.el-select__wrapper),
.pb-sel-date :deep(.el-input__wrapper) {
  min-height: 29px;
  height: 29px;
  padding: 0 8px;
  border-radius: 3px;
  box-shadow: 0 0 0 1px #ddd inset;
}

.pb-sel :deep(.el-select__wrapper.is-focused),
.pb-sel-date :deep(.el-input__wrapper.is-focus) {
  box-shadow: 0 0 0 1px #556677 inset;
}

.pb-sel :deep(.el-select__selected-item),
.pb-sel-date :deep(.el-input__inner) {
  font-size: 12px;
  color: #444;
}

/* ==================== 播放区 ==================== */

/* 700×394 是 CMS 实测尺寸（16:9）。用 max-width：窄屏按比例收缩，宽屏也不会把时间轴挤出视野。 */
.pb-player {
  position: relative;
  width: 100%;
  max-width: 700px;
  aspect-ratio: 16 / 9;
  margin: 0 auto 15px;
  background: #000;
  /* 窄屏时高度按宽度收缩，避免 aspect-ratio 把页面撑出滚动条 */
  max-height: 394px;
}

.pb-video {
  position: absolute;
  inset: 0;
}

/*
 * 只保留 播放/暂停、音量、拉伸(STD)、截图、全屏（与 CMS 一致）。隐藏的这些只对直播
 * 有意义：回放没有「回到直播」，设备录像也没有章节/字幕/音轨。
 * 用 :deep 是因为控制栏是 LivePlayer 运行时注入的 DOM，没有本组件的 scopeId。
 */
.pb-player :deep(.vjs-live-control),
.pb-player :deep(.vjs-seek-to-live-control),
.pb-player :deep(.vjs-chapters-button),
.pb-player :deep(.vjs-descriptions-button),
.pb-player :deep(.vjs-subs-caps-button),
.pb-player :deep(.vjs-audio-button),
.pb-player :deep(.vjs-picture-in-picture-control) {
  display: none !important;
}

/* 无流时的占位：CMS 在白字居中显示 No Video */
.pb-no-video {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #fff;
  font-size: 14px;
  pointer-events: none;
}

/* 国标定位是让设备跳，1~2 秒画面纹丝不动，不提示会被当成卡死。 */
.pb-seeking {
  position: absolute;
  top: 10px;
  right: 10px;
  padding: 3px 10px;
  border-radius: 3px;
  background: rgba(0, 0, 0, 0.62);
  color: #fff;
  font-size: 12px;
  line-height: 18px;
  pointer-events: none;
}

/* 倍速降级提示：只占文字宽度，别把工具栏撑开 */
.pb-speed-hint {
  margin-left: 6px;
  padding: 0 6px;
  height: 22px;
  line-height: 22px;
  border: 1px solid #e6a23c;
  border-radius: 3px;
  color: #b88230;
  font-size: 12px;
  white-space: nowrap;
  cursor: help;
}

/* ==================== 时间轴（1:1 对齐 CMS） ==================== */

/* max-width:1440px + 内层固定 1440px 是 CMS 原样行为：窄窗口右侧被裁掉。 */
.time-rule {
  position: relative;
  overflow: hidden;
  height: 50px;
  width: 100%;
  max-width: 1440px;
  margin: 0 auto;
  font-size: 12px;
  background-color: #ccc;
  cursor: pointer;
  user-select: none;
}

/* 内层轨道：一天 1440 分钟 → 1440px */
.time-day {
  position: absolute;
  left: 0;
  top: 0;
  width: 1440px;
  height: 100%;
}

/* 288 个 × 5px（= 5 分钟）。有录像的点亮成深色，无录像的透出外框 #ccc。 */
.time-minute {
  float: left;
  width: 5px;
  height: 8px;
  margin: 0;
  cursor: default;
}

.time-minute.active {
  background-color: #404d59;
  cursor: pointer;
}

/* 24 个 × 60px（= 1 小时）。左/上是 CMS 的网格线，不是装饰。 */
.time-text-first,
.time-text {
  float: left;
  width: 60px;
  height: 25px;
  line-height: 25px;
  text-align: center;
  border-left: 1px solid #999;
  border-top: 1px solid #999;
  color: #333;
  user-select: none;
}

.time-text-first {
  border-left: 0;
}

/* 红色游标：2px 竖线，高 30px，与 CMS 一致 */
.time-cursor {
  position: absolute;
  top: 0;
  width: 2px;
  height: 30px;
  background-color: red;
  text-align: center;
  pointer-events: none;
}

/* 游标标签：白底红框，挂在竖线下方，水平居中（left:-30px 让 60px 宽的框居中） */
.time-cursor-text {
  position: absolute;
  top: 30px;
  left: -30px;
  width: 60px;
  height: 15px;
  padding: 0 5px;
  line-height: 15px;
  border: 1px solid red;
  background-color: #fff;
  color: #333;
  white-space: nowrap;
}

/* 空轨道提示：CMS 没有，但完全空白会让人以为检索失败 */
.time-empty {
  position: absolute;
  inset: 33px 0 0;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #666;
  pointer-events: none;
}

/* ==================== 列表视角 ==================== */

.empty-cell {
  padding: 24px 0;
  color: var(--text-tertiary); }
</style>
