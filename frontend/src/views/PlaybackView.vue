<template>
  <div class="page pb-page">
    <div class="pb-card">
      <!--
        标题：居中，格式对齐 CMS（LiveGBS）的「设备录像时间轴(设备-通道)」。
      -->
      <h4 class="pb-title">设备录像时间轴({{ timelineTitle }})</h4>

      <div class="pb-body">

        <!--
          工具栏：单行两段。左段是「对哪条通道、怎么播」，右段是「看哪天、什么视角」。

          <p>按钮尺寸对齐 CMS 的 Bootstrap {@code btn-sm}：高 29px、字号 12px、
          圆角 3px、主色 {@code #556677}。不直接用 el-button 是为了拿到一致的高度 ——
          element-plus 的 small 按钮是 24px，和日期框、下拉并排会参差不齐。
        -->
        <div class="pb-toolbar">
          <div class="pb-tools-left">
            <button class="cms-btn cms-btn-primary" title="返回上一页" @click="goBack">
              <span class="cms-ico">‹</span> 返回
            </button>
            <span class="cms-gap" />
            <!--
              通道选择：CMS 的通道来自路由参数，本平台的 /playback 没有，
              所以必须留在页面上。尺寸压到和 cms-btn 同高。
            -->
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
            <!-- 日期：CMS 是一个输入框 + 日历图标；这里用日期选择器并保留前后翻页 -->
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
          播放区：对齐 CMS 的 700×394 黑底居中。

          <p>播放器用的是 LivePlayer 官方组件，<b>控制栏就是它自带的那个</b>
          （播放/暂停、进度条、音量、全屏、截图），不另做一套 ——
          自己画的控件既做不到全屏、截图这些能力，也会和组件内部状态打架。

          <p>没有回放时显示「No Video」，与 CMS 一致：黑块上加一句白字，
          比纯黑更像「播放器已就位、只是还没选片」，而不是「加载失败」。
        -->
        <div ref="playStage" class="pb-player">
          <div ref="videoContainer" class="pb-video" />
          <div v-if="!current" class="pb-no-video">No Video</div>
        </div>

        <!--
          这里<b>不再有</b>「时间范围 / 进度计数 / 停止回放」那一行。

          <p>CMS（LiveGBS）的页面就是「标题 → 工具栏 → 播放器 → 时间轴」四段，
          播放器下方直接接时间轴，没有任何信息行。之前我们额外加了一行，
          显示 {@code 04:00~06:00}、{@code 01:08:40 / 02:00:00} 和「停止回放」，
          与参照页面不一致，看起来就像多出来的一块。

          <p>这些信息在新布局里由别处承担：
          <ul>
            <li><b>进度</b> —— 播放器自带控制栏的进度条（已把段时长喂给
                {@code customDuration}），以及时间轴上的红色游标；</li>
            <li><b>段范围</b> —— 点过的那个刻度块本身就是位置指示；</li>
            <li><b>停止</b> —— 播放器控制栏的暂停，或直接点别的刻度块换段
                （服务端会自动替换旧会话）。</li>
          </ul>
        -->

        <!--
          时间轴：1:1 对齐 CMS 的 LiveGBS 实现。

          <p>结构（与 CMS 完全一致）：
          <ul>
            <li>{@code .time-rule} —— 外框，50px 高、灰底 {@code #ccc}、{@code overflow:hidden}；</li>
            <li>{@code .time-day} —— 固定 1440px 宽（一天 1440 分钟，<b>1px = 1 分钟</b>），绝对定位；</li>
            <li>288 个 {@code .time-minute}（每块 5px = 5 分钟，高 8px），有录像的加
                {@code .active} 变深色 {@code #404d59}；</li>
            <li>24 个 {@code .time-text}（每格 60px = 1 小时，高 25px），带 {@code #999} 左边框与上边框；</li>
            <li>{@code .time-cursor} —— 2px 红竖线 + 下方白底红框时刻标签。</li>
          </ul>

          <p><b>外框是 {@code width:100% + max-width:1440px}，而内层固定 1440px</b>：
          窗口窄于 1440px 时右侧会被裁掉，这是 CMS 的原样行为，保持一致。
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
        列表视角。与时间轴同源数据，只是呈现方式不同，切换不会重新发起 SIP 查询。

        <p>{@code v-if} 而不是 {@code v-else}：上面的时间轴用 {@code v-show}
        控制显隐（切换视角时保留 DOM，避免重建 288 个刻度块），
        {@code v-else} 只能跟 {@code v-if} 配对。
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
          下载倍速：档位来自设备目录应答的 Info/DownloadSpeed（如 1/2/4/8）。
          标准 9.9.1 b) 规定倍速由请求方在 SDP 里携带（a=downloadspeed:N），
          设备支持就快进推流 —— 8 倍速下 1 小时录像约 7.5 分钟就能拿完。
          只对「下载」生效，回放永远是 1 倍速。

          放在列表页脚而不是顶部筛选栏：时间轴视角根本不提供下载，
          一个只能影响下载的设置摆在那里，既占位置又容易让人以为改了会影响回放。
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

/**
 * 能否下载录像。
 *
 * <p>后端对 {@code /playback/download/*} 要求 {@code cloud:write} ——
 * 下载会让服务器拉流并写盘，不只是「看」。没权限时干脆不画按钮，
 * 而不是让人点了才收到 403。
 */
const canDownload = computed(() => auth.can('cloud:write'))

/**
 * 返回上一页。
 *
 * <p>用 {@code router.back()} 而不是固定跳某个路由：本页可从「设备管理」
 * 的通道列表、通道管理、以及直接输入 URL 三种方式进入，
 * 回退到「用户来的地方」才符合直觉。没有历史记录时（直接打开链接）
 * 退回首页，避免点了没反应。
 */
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
/**
 * 上一次检索设备是否应答。
 *
 * <p>用来区分「设备没答」与「设备答了：当天没录像」——两者都是空列表，
 * 但前者不能显示成「当日没有录像」（那是报假警）。
 */
const deviceUnanswered = ref(false)
/**
 * 设备未应答时后端给出的说明（含设备自己的拒绝原因）。
 *
 * <p>单独存一份而不只是弹一条消息：提示条几秒就消失了，
 * 而用户盯着空时间轴时需要可信的理由；设备返回的原文（如
 * {@code 400 wait for recordinfo timeout[15s]}）是判断「该找谁」的唯一线索。
 */
const deviceNotice = ref('')
const query = reactive({ channelKey: '' })
/** 呈现视角：`timeline` 时间轴（默认）/ `list` 明细列表。 */
const viewMode = ref('timeline')

/**
 * 查询日期，形如 {@code 2026-10-26}。
 *
 * <p><b>为什么只允许按天查</b>：设备侧录像检索的入参是一个时间段，
 * 平台的「起止时间任意选」看起来更灵活，实际有两个问题：
 * <ul>
 *   <li>时间轴上的色块没法定位——不知道用户选的区间落在一天的哪一段；</li>
 *   <li>设备返回的是「与该区间有交集」的录像段，随便框一个区间，
 *       拿回来的段往往一半在框外，用户会怀疑结果不准。</li>
 * </ul>
 * 固定成整天后，界面和时间轴一一对应，翻页（前一天/后一天）也顺。
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

/**
 * 标题里的「(设备-通道)」。
 *
 * <p>格式对齐 CMS：{@code 设备录像时间轴(34020000001320000111-34020000001310000001)}。
 * 用编码而不是名称 —— 名称可能重名（本平台就有两个「南坑水库」），
 * 只有编码才能唯一定位到一路通道，运维报障时也是拿编码沟通。
 *
 * <p>未选通道时给占位文案，而不是留一对空括号：空括号看起来像数据没加载出来。
 */
const timelineTitle = computed(() => {
  if (!serial.value || !code.value) return '未选择通道'
  return `${serial.value}-${code.value}`
})

/**
 * 当前通道设备声明支持的下载倍速档位。
 *
 * <p>来源是设备目录应答里的 {@code Info/DownloadSpeed}（本现场实测全部为
 * {@code 1/2/4/8}）。设备没报时只给 1 倍速，界面不会出现选不了的值。
 */
const speedOptions = computed(() => {
  const ch = channels.value.find((c) => `${c.deviceId}/${c.id}` === query.channelKey)
  const list = String(ch?.downloadSpeed || '')
    .split('/')
    .map((s) => Number.parseInt(s.trim(), 10))
    .filter((n) => Number.isFinite(n) && n >= 1)
  return list.length ? [...new Set(list)].sort((a, b) => a - b) : [1]
})

/**
 * 下载倍速（只影响下载，不影响回放播放速度）。
 *
 * <p><b>默认 1 且不自动选最高档</b>：早先这里会「自动取设备声明的最高档」，
 * 实测后果很坑 —— 设备对 {@code a=downloadspeed:8} 的响应是
 * <b>只推关键帧</b>（同一个 3 分钟段：1 倍速产出 ≈360MB 的完整流，
 * 8 倍速产出 14MB 且媒体时间跨 851 秒），拿到的是一份快进片，
 * 而且因为推得快、平台又按墙钟计时，录到的内容范围完全控制不住。
 * 存档下载必须默认完整模式。
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

/**
 * 本页结果里是否一条都没有 FilePath。
 *
 * <p>大量设备（LiveGBS、多数国产摄像机）按时间段管理录像、根本不填 FilePath，
 * 于是那一列每条都是破折号 —— 240px 的空白加上后面几列，直接把表格撑出
 * 横向滚动，“大小”这种有用信息反而要拖动才能看到。这种情况下整列隐藏。
 */
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

/**
 * 把后端时间串解析成本地毫秒时间戳。
 *
 * <p>设备应答里的时间是 {@code 2026-10-26T08:00:00}（ISO，本地时间无时区）。
 * {@code new Date('2026-10-26T08:00:00')} 在 ES2015+ 里按本地时区解析，
 * 符合预期；但带空格的形式 {@code '2026-10-26 08:00:00'} 在部分浏览器里
 * 直接返回 Invalid Date，所以统一把空格换成 T 再解析。
 */
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
 * 时间轴上的录像色块。
 *
 * <p><b>为什么必须裁剪到当天</b>：设备返回的是「与查询区间有交集」的录像段，
 * 计划录像常见 {@code 22:00 → 次日 06:00} 这种跨天段。直接拿原始起止算百分比，
 * left 会变成负数、left+width 会超过 100%，色块直接画到轨道外面去。
 * 显示按当天裁剪；播放仍用完整时间段（设备要原始入参才能取到流）。
 *
 * <p><b>为什么要设最小宽度</b>：一天的 0.1% 只有 86 秒。设备偶尔返回几秒的
 * 碎片段，按真实比例在 1000px 轨道上不足 1px，肉眼完全看不见，
 * 用户会以为「这段没查出来」。给 0.1% 下限后至少是一条可见的细线。
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

/**
 * 时间轴内层固定宽度：一天 1440 分钟，<b>1px = 1 分钟</b>。
 *
 * <p>与 CMS（LiveGBS）完全一致 —— 它的 {@code .time-day} 就是写死 1440px，
 * 外框 {@code width:100%; max-width:1440px}。窗口窄于 1440px 时右侧被裁掉，
 * 这是原样行为，不做「自适应缩放」的改良：一改，刻度和录像块的位置关系
 * 就与 CMS 对不上，用户对照两边看时会以为数据不同。
 */
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
 * 288 个刻度块，标记每个 5 分钟区间有没有录像。
 *
 * <p><b>为什么不复用 {@code timelineSegments} 的百分比</b>：CMS 的时间轴不是
 * 若干个彩色块，而是<b>一整排等宽小块</b>，有录像的变深色。要 1:1 就得
 * 按分钟网格离散化，而不是画任意位置任意宽度的矩形。
 *
 * <p>离散化规则：段覆盖到的每一块都算「有」。因此 1 秒的碎片段也会点亮
 * 它所在的 5 分钟块 —— 这与 CMS 的观感一致（它同样按整块点亮），
 * 也避免「明明查到了录像，时间轴上却什么都没有」。
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
 * 红色游标的像素位置（相对 {@code .time-day} 左边缘）。
 *
 * <p>优先跟播放进度走（时间轴兼作进度条）；没在播放时退回「当前时刻」，
 * 与 CMS 一致 —— 它的红线就是「现在」。
 *
 * <p>返回 {@code null} 表示不显示：日期不是今天、且没在播放时，
 * 画一条「今天此刻」的线在历史日期上是错的。
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

/**
 * 游标上显示的时刻。
 *
 * <p>只到分钟（{@code HH:mm}），与 CMS 一致 —— 它的游标标签是 60px 宽的
 * 白底红框，塞下 {@code HH:mm} 刚好，带上秒就溢出了。
 */
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
 * 把鼠标位置换算成「当天第几毫秒 + 百分比」。
 *
 * <p><b>必须按内层 {@code .time-day} 量宽度，不能按外框 {@code .time-rule}</b>：
 * 外框是 {@code max-width:1440px} + {@code overflow:hidden}，窗口窄于 1440px 时
 * 它只有几百像素，而里面那条轨道仍然是完整的 1440px（右侧被裁掉）。
 * 用外框宽度当分母，会把「可见部分」拉伸成一整天 ——
 * 实测点 15:00 附近会跳到 23:00 的录像段，用户完全无法理解。
 *
 * <p>比例夹在 0~100%，所以点到被裁掉的区域之外也会自然落在 24:00。
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

/**
 * 「当前时刻」的脉冲值。
 *
 * <p>游标在未播放时要跟着真实时间走，但 {@code Date.now()} 不是响应式的 ——
 * 直接写在 computed 里，值会被缓存住，游标永远停在进入页面那一刻。
 * 由定时器推进这个 ref 来驱动重算（见 {@link onMounted}）。
 */
const nowTick = ref(Date.now())

/** 推进 {@link nowTick} 的定时器句柄。 */
let nowTimer = null

/**
 * 时间轴点击：播放命中时刻的那一段。
 *
 * <p>时间轴兼作进度条 —— 点哪播哪。
 * <ul>
 *   <li>正在播同一段 → 当作「定位」（拖动进度条）；</li>
 *   <li>正在播别的段或还没播 → 从该时刻起播。</li>
 * </ul>
 *
 * <p>定位优先用 SIP {@code INFO}（npt），设备原地跳，不必拆会话重新协商 ——
 * 后者的代价是 2~3 秒黑屏。
 */
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
  /*
   * 时间轴兼作进度条：点哪播哪。
   *
   * <p>正在播同一段 → 当作「定位」（拖动进度条）。
   * 正在播别的段或还没播 → 从该时刻起播。
   *
   * <p>定位优先用 SIP {@code INFO}（npt），设备原地跳，
   * 不必拆会话重新协商 —— 后者的代价是 2~3 秒黑屏。
   */
  const offset = Math.floor((point.at - parseWall(hit.row.startTime)) / 1000)
  if (current.value && hit.row === current.value && playing.value) {
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



/*
 * 不再自动选最高档：见 downloadSpeed 上的说明 —— 倍速产物是「快进片」，
 * 只适合快速翻看，不能当存档。默认完整模式，仅在设备确实声明多档时才
 * 提供快进选项供用户主动选择。
 */

async function loadChannels() {
  try {
    const data = await deviceApi.channelList({ start: 0, limit: 1000 })
    channels.value = data?.channelList || []
  } catch (e) {
    ElMessage.error(e.message || '加载通道失败')
  }
}

/**
 * 确保已拿到媒体端口（播放地址需要用它与当前主机名重写）。
 *
 * @returns {Promise<boolean>} 是否可用
 */
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
  /*
   * 直接检索当天。
   *
   * <p>设备侧的录像检索是<b>串行问答</b>，同通道两个并发查询会互相覆盖
   * 应答回调（其中一个必然超时，表现为「查询录像老是查不出来」），
   * 所以一次通道切换只发这一条 SIP 查询。
   */
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

/**
 * 时间轴空位文案。
 *
 * <p><b>「设备没答」不能说成「当日没有录像」</b>：那是在报假警 ——
 * 用户会据此认为设备没录、甚至跑去现场。空列表有三种完全不同的原因，
 * 文案必须分开：还没查 / 设备没答 / 确实没录。
 */
const timelineEmptyText = computed(() => {
  if (!searched.value) return '选择通道后点「检索」'
  if (deviceUnanswered.value) {
    return deviceNotice.value || '设备未应答检索，无法确认当日是否有录像'
  }
  return '当日没有录像'
})

/**
 * 检索请求序号：只认最后一次发起的结果。
 *
 * <p>设备检索要几秒，用户在这期间换了通道或日期时，先发的那次会晚回来，
 * 直接写 {@code records} 就会把新通道的结果覆盖成旧通道的 ——
 * 界面上表现为「选了 A 通道，时间轴上却是 B 通道的录像」。
 */
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
    /*
     * 设备没应答 ≠ 没有录像。
     *
     * <p>后端会在 {@code notice} 里把这两者说清楚（含“下面是缓存结果”）。
     * 早先不管三七二十一弹「当日无录像」，等于告诉用户“设备没录”，
     * 而实际上可能是设备侧的录像检索坏了 —— 排查方向直接错掉。
     */
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


/**
 * 画面起不来时的重拉次数。
 *
 * <p>只在<b>用户主动点播</b>时清零；自动重建沿用计数，否则会变成无限重试。
 */
let stallRetry = 0

/** 最多重拉几次。超过就停下来告诉用户，而不是一直重试刷设备。 */
const MAX_STALL_RETRY = 2

/**
 * 发起回放并播放。
 *
 * @param {object} row 录像段
 * @param {object} [opts]
 * @param {boolean} [opts.rebuild] 内部重拉（不清零重试计数）
 * @param {number} [opts.startOffset] 从本段第几秒开始播（拖动定位用，默认 0）
 */
async function play(row, opts = {}) {
  if (!opts.rebuild) stallRetry = 0
  const offset = Math.max(0, Math.floor(Number(opts.startOffset) || 0))
  /*
   * 列表视图点「回放」时把播放区滚进视野。
   *
   * <p>回放不再用弹窗，画面在页面顶部的播放区。用户在表格里点了「回放」，
   * 播放区可能在屏幕外 —— 画面在下面播、用户看不到，还以为没反应。
   */
  if (playStage.value) {
    playStage.value.scrollIntoView({ behavior: 'smooth', block: 'nearest' })
  }
  /*
   * 【先请求、成功后再起播】
   *
   * <p>请求被拒时（典型场景：该通道正在后台下载，回放会话被锁），
   * 页面只留一条说明原因的提示，不会把播放器切到「有画面但其实是空白」的状态。
   */
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
  await sleep(120)

  const urls = resolvePlayUrls(info, mediaHttpPort.value)
  if (!urls.wsFlv && !urls.webrtc) {
    ElMessage.error('未获取到回放地址')
    return
  }
  /*
   * 回放只走 WebSocket-FLV。
   *
   * <p><b>为什么回放不提供 WebRTC</b>（虽然直播预览提供）：回放是「伪点播」——
   * 进度条/拖动/倍速靠 {@code media.currentTime} + 设备信令实现。而 WebRTC 通路
   * 拿到的是 <b>MediaStream</b>，实测其 {@code currentTime} 不推进（恒定值），
   * 进度条会卡死；且设备停推时 RTC 连接会直接断开（实测 ~20 秒）。
   * 与其给一个看着能选、选了就报错的选项，不如不提供。
   */
  await player.play(playbackKey.value, urls, {
    proto: 'wsflv',
    /*
     * <b>不传 {@code _vod}：设备回放就是直播形式。</b>
     *
     * <p>国标回放的媒体面就是「设备主动往平台推一路 RTP」，与直播同一形状 ——
     * flv.js 也必须按 {@code isLive=true} 处理（它会因此不尝试 seek、
     * 不等待 duration），这才是真实语义。早先按「点播」传会让 flv.js
     * 去等一个永远不会来的时长。
     *
     * <p><b>代价与弥补</b>：live 模式下没有进度条。本平台的定位入口是
     * 页面自己的时间轴（点刻度 → SIP INFO 让设备跳），不依赖播放器进度条，
     * 所以这不是能力缺失。
     */
    /*
     * <b>控制栏保持开启</b>（引擎默认 {@code controls:true}）。
     *
     * <p>早先这里传 {@code _noControls: true} 把控制栏关掉，注释写「控制条由
     * 本页面提供」—— 但页面从未提供过，于是用户拿不到任何播放操作：
     * 没有暂停、没有音量、没有全屏、没有截图。这就是「播放器不可操作」的由来。
     * 该开关已于本次一并删除（无任何调用方）。
     *
     * <p>控制栏里最终保留 播放/暂停、音量、拉伸(STD)、截图、全屏。
     * 没有进度条 —— 由引擎按「实时流」统一隐藏（见 {@code useLivePlayer#play}）。
     */
    /*
     * 画面永远起不来时重拉整条流。
     *
     * <p><b>这里必须接 onStall，否则那个看门狗是死的</b>：播放器里只能在
     * “自己无救”时回调出来 —— 它连的是同一个媒体源，重连一万次也没有视频轨。
     * 真正要做的是停掉回放会话 + 重新 INVITE（换 SSRC、ZLM 重建媒体源）。
     * 早先这里没传，于是看门狗触发后什么也没发生，界面永远停在
     * 「等待设备推流…」，只能关掉重播。
     *
     * <p>典型成因：设备推流慢（实测某下级平台应答后要 11 秒才推视频），
     * ZLM 等轨道就绪超时就把 H264 轨<b>永久丢弃</b>，媒体源只剩 PCMA。
     */
    onStall: rebuildStream,
    /*
     * 稳定播放 10 秒后把重拉预算还回去。
     *
     * <p>否则一个长时间的观看过程里，几次彼此无关的起播故障（设备慢一次、
     * 丢包坏帧一次…）会把 2 次预算永久耗光，之后再出问题就直接放弃了。
     */
    onStable: () => { stallRetry = 0 },
    /*
     * 国标回放控制：官方进度条/播放控件的动作转成设备 SIP 指令。
     *
     * <p>这些是国标协议的语义（INFO 定位 / PLAY / PAUSE / Scale），
     * 播放器本身不做任何自定义实现。
     */
    vod: {
      duration: segmentSeconds.value,
      onSeek: (sec) => seekTo(sec),
      onPause: () => control('pause'),
      onResume: () => resumePlayback(),
      onRate: (rate) => control('scale', { scale: rate })
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
 * 拖动/点击进度条定位：发 SIP {@code INFO} 让设备自己跳。
 *
 * <h3>为什么可以直接发 npt</h3>
 * 标准附录 D 的 {@code PLAY RTSP/1.0 + Range: npt=<秒>-} 就是正解，
 * WVP-GB28181-pro 的 {@code playSeekCmd} 也是这么实现的（逐字对比过，
 * 连 {@code Content-Type: application/MANSRTSP} 都一样）。
 *
 * <p><b>之前这里改成「停流 + 重新 INVITE」是误判</b>：那时 INFO 的
 * 对话框 tag 取反了（To 用了自己、From 用了对端），报文落在对话框之外，
 * 设备宽容地回 200 OK 却不执行。修好 tag 之后 npt 一直是生效的，
 * 只是没人回来改这里。
 *
 * <p>实测证据（同一次会话里发 {@code Range: npt=3600-}）：
 * 画面 OSD 从 {@code 00:00:17} 直接跳到 {@code 01:00:18}，正好 +3600 秒。
 *
 * <p>与停流重开相比：
 * <ul>
 *   <li>耗时：INFO 是毫秒级；重新 INVITE 要 2~3 秒协商 + 设备重新起推</li>
 *   <li>不中断：RTP 通道不断，进度条不会闪回 0</li>
 *   <li>不打扰设备：不会反复拆建会话（部分设备有并发会话数限制）</li>
 * </ul>
 */
async function seekTo(sec) {
  const row = current.value
  if (!row) return
  const offset = Math.max(0, Math.floor(Number(sec) || 0))
  try {
    await playbackApi.control({
      serial: serial.value,
      code: code.value,
      command: 'seek',
      range: offset
    })
  } catch (e) {
    //
    // 设备不支持 npt（少数老型号）或会话已被回收：退回重新协商。
    // 这只作为兜底 —— 正常设备走上面的 INFO 就行，不应该常走这里。
    //
    ElMessage.warning(e.message || '设备不支持跳转，正在按目标时间重新拉流…')
    await restartAt(offset)
  }
}

/**
 * 从本段第 {@code offsetSeconds} 秒重新开始播放（停会话 → 重新 INVITE）。
 *
 * <p>现在只作为<b>兜底路径</b>使用：设备不支持 npt、或媒体源已失效时。
 * 正常的拖动定位与「继续播放」都走 SIP INFO，不再拆会话。
 */
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

/**
 * 重拉整条回放流（停会话 → 重新 INVITE）。
 *
 * <p>只在「播放器自己无救」时被调用：媒体源已经丢了视频轨、或连上了却
 * 一个字节都收不到。这两种情况重连 WebSocket 都没用，必须换一路流。
 */
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
 * 为什么不用 <a href="设备文件路径" download>：
 * 国标设备的录像在设备侧，没有可直接访问的 HTTP 地址（FilePath 多数还是空的）。
 * 所以「下载」= 用回放把这段录像拉下来，同时让 ZLM 录成本地 MP4，
 * 录完再给浏览器一个标准 MP4 链接（支持 Range，断点续传）。
 *
 * 【后台任务】任务由服务端持有多份状态（DownloadService），进度与结果
 * 统一在顶栏的「下载任务」面板里看。本页只负责「发起」，不再自己轮询——
 * 早先进度存在本组件里，用户切走页面就丢了，而且离开时的 playback/stop
 * 还会把下载中的流停掉（下载直接中断）。
 */

/**
 * 开始下载某段录像（后台执行，可离开本页）。
 *
 * <p>顺序不能颠倒：必须先有回放流，ZLM 才有东西可录。
 * 若用户此时正在看回放，那段流会被这次 download 复用（同一个会话键），
 * 不会重复向设备发起 INVITE。
 *
 * @param row 录像段（起点/终点/大小；框选下载时构造的伪行）
 * @param override 可选覆盖项：{@code seconds} 指定时长、{@code speed} 指定倍速。
 *                 框选区间下载用它把「整段」换成「用户选的那一截」并强制 1 倍速 ——
 *                 框选的目的就是「少下一点、画质别动」，不能再叠加掉帧的倍速。
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

/**
 * 下载当前正在回放的那一段。
 *
 * <p>工具栏的「下载」按钮走这里。早先模板绑的是 {@code downloadCurrent}，
 * 但函数从未定义过 —— 点一下就是 {@code ReferenceError}，
 * 表现为「下载按钮没反应」，控制台里只有一条 undefined 的报错。
 *
 * <p>复用列表视图的行下载逻辑（{@link startDownload}）：两者下的是同一段，
 * 只是入口不同，没必要写第二套。
 */
async function downloadCurrent() {
  if (!current.value) return
  await startDownload(current.value)
}

/**
 * 下载按钮的悬浮提示：把「要等多久」提前说清楚。
 *
 * <p>完整模式（默认）耗时 ≈ 段长本身，下载在后台跑，用户可以去干别的事。
 * 快进模式会快一些，但产物是跳帧画面 —— 这个差别必须在点之前说明，
 * 否则用户拿到文件才发现画面一跳一跳，只会认为平台坏了。
 */
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

/** 回放控制：暂停 / 继续 / 倍速 / 拖动。 */
async function control(command, extra = {}) {
  if (!current.value) return
  try {
    await playbackApi.control({
      serial: serial.value,
      code: code.value,
      command,
      ...extra
    })
    if (command === 'pause') ElMessage.success('已暂停')
    else if (command === 'play') ElMessage.success('已继续')
  } catch (e) {
    ElMessage.error(e.message || '控制失败')
  }
}

/**
 * 继续播放：优先发 SIP {@code PLAY npt=now-}，失败再重新协商。
 *
 * <p><b>为什么先试 INFO</b>：「继续」本质上只是让设备接着推，
 * 发一条 INFO 是毫秒级的；而停会话 + 重新 INVITE 要 2~3 秒协商，
 * 用户按下「继续」后要盯着黑屏，体验差距很明显。
 * PAUSE / PLAY 的对话框 tag 已实测正确，正常设备会立即续推。
 *
 * <p><b>为什么要留重开兜底</b>：暂停期间设备停推 RTP，ZLM 对没有数据的
 * RTP 会话有硬超时（实测十几秒到 100 秒），一过就注销媒体源 ——
 * 此后设备即使续推，平台侧也没有可用的源。这种情况 INFO 会「发成功但
 * 没画面」，由播放器的看门狗（12 秒无关键帧 / 20 秒无字节）
 * 触发 {@code onStall} → {@link rebuildStream} 重新协商，用户感知只是慢几秒。
 *
 * <p>所以这里不再主动拆会话：拆会话本身就是「体验很差」的来源。
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
 * 进度条与播放控制全部由 LivePlayer 的<b>官方控制栏</b>提供（含官方进度条）。
 *
 * <p>国标回放是「伪点播」—— 平台收到的是设备持续推来的 FLV 流，流里没有
 * 总时长（ZLM 的 .live.flv 不带 duration 字段）。整段录像的起止时间只有
 * 业务侧知道（来自录像检索应答），由 {@code useLivePlayer} 的 vod 控制层
 * 喂给 video.js 的官方 {@code customDuration}，官方进度条即可正确显示长度。
 *
 * <p>拖动/暂停/倍速同样走官方控件，动作由 vod 控制层转成国标 SIP 指令
 * （{@code INFO} / {@code PLAY} / {@code PAUSE} / {@code Scale}）。
 */

/** 录像段总时长（秒）。 */
const segmentSeconds = ref(0)
/**
 * 本段起始偏移（秒）：从段中间开始播时，媒体时间轴的 0 点对应的段内位置。
 *
 * <p>进度条显示位置 = positionOffset + player.position。
 */
const positionOffset = ref(0)
/** 播放位置（相对录像段起点的秒数）。 */
const playPosition = computed(() =>
  Math.min(segmentSeconds.value, positionOffset.value + (player.position.value || 0))
)
/** 回放倍速（国标 Scale）。 */
const playRate = ref(1)

/**
 * 播放头：当前播放位置在当天时间轴上的位置。
 *
 * <p><b>时间轴兼作进度条</b> —— 它既标出「现在播到哪儿」，也是定位入口。
 * 位置换算：段的起始绝对时刻 + 段内偏移 + 媒体已播放时间，
 * 再换算成「距当天 00:00 的毫秒数 / 一天」得到百分比。
 *
 * @returns {{percent:number, clock:string}|null}
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

/** 倍速切换：国标 Scale，设备侧变速推流。 */
async function onRateChange(rate) {
  playRate.value = rate
  await control('scale', { scale: rate })
}

/**
 * 回放会话键。
 *
 * <p><b>必须带 {@code _playback} 后缀</b>：直播与回放是两个独立会话
 * （见 {@code RtpSession#getSessionKey}）。用裸的 {@code 设备/通道} 会命中
 * <b>直播</b>会话 —— 自动云端录像会让通道长期有直播流在跑，于是回放弹窗里
 * 播的是实时画面（OSD 显示今天），切 WebRTC 也会拿到直播的地址/或直接 404。
 */
const playbackKey = computed(() => `${serial.value}/${code.value}_playback`)




/**
 * 当前播放画面对应的墙钟时间。
 *
 * <p>保留计算逻辑但不展示在界面上：xgplayer 的控制栏已显示「段内秒数」，
 * 再画一行「当前画面/段内」只是重复。若以后需要（如给截图加水印）
 * 直接取这个 computed 即可。
 */
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
 * 释放当前回放会话（停播放器 + 通知设备停流）。
 *
 * <p>界面上已经没有「停止回放」按钮了（对齐 CMS，它的页面只有
 * 标题/工具栏/播放器/时间轴）。这个方法现在<b>只在离开页面时</b>调用，
 * 见 {@code onUnmounted}。
 *
 * <p>为什么离开页面必须主动停：不停的话设备会继续按回放通道推流，
 * 平台侧会话要等空闲巡检（45 秒）才回收 —— 用户看到的就是
 * 「页面都关了，设备还在被拉流」，白占上行带宽。
 *
 * <p>正在下载时<b>不要</b>主动停流：下载与回放共用同一路回放流（同一个会话键），
 * 停掉它等于把正在录制的源掐了，用户拿到的 MP4 会是半截。
 * 与后端一致：{@code playback/stop} 在下载中也会返回 ok=false 并跳过停流，
 * 真正的保护在服务端。
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
  /*
   * 时间脉冲：游标在「未播放」状态下显示的是当前时刻，要自己往前走。
   *
   * <p>30 秒一次足够：游标位置按分钟算，1 分钟 = 1px，
   * 更密的刷新用户看不出差别，只是白费 CPU。播放中游标跟的是
   * {@code playhead}（由播放器 timeupdate 驱动），与本定时器无关。
   */
  nowTimer = setInterval(() => { nowTick.value = Date.now() }, 30_000)
  await loadChannels()
  // 从通道列表点「设备录像」跳进来时预填设备/通道
  const serial = String(route.query.serial || '').trim()
  const code = String(route.query.code || '').trim()
  if (serial && code) {
    query.channelKey = `${serial}/${code}`
    /*
     * 预填走的是 query 参数，不会经过 onChannelChange，
     * 所以这里要自己查一次。
     *
     * <p><b>顺序不能反</b>：先检索当天、再判录像状态。原实现只查了状态，
     * 用户从通道列表点「设备录像」跳进来时时间轴/列表都是空的，
     * 必须先手动点一次「检索」才看得到内容 —— 而他跳进来的目的
     * 往往就是直接找某一段。
     */
    search()
  }
})
onUnmounted(() => {
  if (nowTimer) clearInterval(nowTimer)
  nowTimer = null
  /*
   * 离开页面必须释放设备侧的会话。
   *
   * <p>界面上没有「停止回放」按钮了（对齐 CMS），所以这是唯一的释放入口 ——
   * 只调 {@code player.stop()} 会断掉浏览器与 ZLM 之间的拉流，但设备仍在
   * 按回放通道推流，平台侧会话要等 45 秒空闲巡检才回收。
   */
  releasePlayback()
})
</script>

<style scoped>
/* ============================================================
   CMS（LiveGBS）风格：设备录像时间轴页
   ------------------------------------------------------------
   颜色与尺寸取自 CMS 实际渲染值：
     卡片      #fff，圆角 3px，阴影 0 1px 1px rgba(0,0,0,.1)，内边距 10px
     主按钮    bg #556677 / border #404d59 / 白字
     次按钮    bg #f4f4f4 / border #ddd   / 字 #444
     按钮尺寸  高 29px、字号 12px、圆角 3px（Bootstrap btn-sm）
     时间轴    外框 #ccc、高 50px；刻度块 #404d59、5×8px；小时格 60×25px、边线 #999
     游标      2px 红竖线 + 白底红框标签
   ============================================================ */

/* 卡片：对齐 CMS 的 .box */
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
 * CMS 的 .btn：29px 高、12px 字、3px 圆角。
 *
 * <p>不用 el-button —— element-plus 的 small 是 24px、default 是 32px，
 * 和日期框、下拉并排都会差几像素，工具栏看起来就参差不齐。
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
 * 日期选择器。
 *
 * <p>宽度用模板上的 {@code style="width:150px"} 设定，不在这里写：
 * Element Plus 的 {@code .el-date-editor.el-input} 是<b>双类</b>选择器
 * （{@code width: var(--el-date-editor-width)}），scoped 下的单类规则压不过它，
 * 实测写 {@code width:150px} 与 {@code --el-date-editor-width} 都无效、
 * 渲染出来仍是 220px， toolbar 因此多占 70px 被迫换行。
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

/*
 * 700×394 是 CMS 的实测尺寸（16:9）。用 max-width 而不是写死宽度：
 * 窗口更窄时按比例收缩，宽屏时也不无限撑大 —— 录像回放不需要铺满整屏，
 * 太大的画面反而让时间轴被挤出视野。
 */
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
 * 精简回放控制栏。
 *
 * <p>LivePlayer 用的是 video.js 默认控制栏，默认会把这些按钮全摆上：
 * 直播标识、Seek to live、章节、字幕、音轨、画中画。它们对<b>录像回放</b>
 * 全都不适用 —— 回放不是直播（没有「回到直播」这回事）、设备录像也没有
 * 章节和字幕轨。留着只会让控制栏拥挤，还容易让用户以为「点了能回到实时画面」。
 *
 * <p>最终保留：播放/暂停、音量、拉伸（STD）、截图、全屏 —— 与 CMS 一致。
 * <b>没有进度条</b>：进度条由引擎层统一隐藏（{@code .lp-no-seek}，
 * 见 {@code LivePlayerEngine#ensureFillStyle} 的说明），这里不再重复声明。
 *
 * <p>用 {@code :deep} 是因为控制栏是 LivePlayer 在运行时注入的 DOM，
 * 没有本组件的 scopeId，普通 scoped 选择器选不中。
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

/* ==================== 时间轴（1:1 对齐 CMS） ==================== */

/*
 * 外框。注意 max-width:1440px 与内层固定 1440px 的组合 ——
 * 这是 CMS 的原样行为：窄窗口时右侧被 overflow:hidden 裁掉。
 */
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

/*
 * 分钟刻度块：288 个 × 5px（= 5 分钟），float 排成一行。
 * 有录像的点亮成深色，无录像的透出外框的 #ccc。
 */
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

/*
 * 小时格：24 个 × 60px（= 1 小时），高 25px。
 * 左/上各一条 #999 细线 —— 这就是 CMS 的网格线，不是装饰。
 */
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
