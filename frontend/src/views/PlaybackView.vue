<template>
  <div class="page">

    <el-card shadow="never">
    <!-- 工具条：筛选条件在左，一行放完 -->
    <div class="filter-bar">
      <el-select
        v-model="query.channelKey"
        placeholder="选择通道"
        filterable
        style="width: 260px"
        @change="onChannelChange"
      >
        <el-option
          v-for="c in channels"
          :key="c.deviceId + '/' + c.id"
          :label="`${c.customName || c.name || c.id}${c.deviceOnline ? '' : '（离线）'}`"
          :value="c.deviceId + '/' + c.id"
        />
      </el-select>
      <!--
        设备录像状态。

        <p><b>为什么值得占一个位置</b>：改造前界面上只有「查出来的录像段」，
        用户点一个空日期什么都看不到，无从区分「设备没在录」与
        「在录，但今天确实没内容」—— 后者只会在有录像之后才变得可见。

        <p><b>判据</b>：向设备查【当前-15 分钟, 当前】，若返回的段中有
        {@code endTime >= 当前-3 分钟}，则这个时刻被某段录像覆盖，即正在录。
        不能只看「查到了段」：设备 2 小时一段，停止录像后 15 分钟内仍会
        查到与区间有交集的旧段。
      -->
      <el-tooltip :content="recStateHint" placement="bottom">
        <el-tag
          :type="recStateType"
          size="default"
          effect="plain"
          class="rec-tag"
          :class="{ clickable: !!query.channelKey }"
          @click="refreshRecState"
        >
          {{ recStateText }}
        </el-tag>
      </el-tooltip>
      <el-date-picker
        v-model="day"
        type="date"
        placeholder="选择日期"
        :clearable="false"
        :editable="false"
        value-format="YYYY-MM-DD"
        style="width: 150px"
        @change="onDayChange"
      />
      <!--
        按日查询：设备侧录像检索一次只查一个时间段，
        而「跨天任意区间」既难对齐时间轴，用户也很难判断哪段属于哪天的计划录像。
        固定成整天后：前一天/后一天 + 今天，三下就能翻到想看的那一天。
      -->
      <el-button-group class="day-nav">
        <el-button title="前一天" @click="shiftDay(-1)">
          <el-icon><ArrowLeft /></el-icon>
        </el-button>
        <el-button title="后一天" :disabled="isToday" @click="shiftDay(1)">
          <el-icon><ArrowRight /></el-icon>
        </el-button>
      </el-button-group>
      <el-button :disabled="isToday" @click="goToday">今天</el-button>
      <el-button type="primary" :loading="searching" @click="search">
        <el-icon><Search /></el-icon> 检索
      </el-button>
      <!--
        视角切换：时间轴看「一天里哪儿有录像」，列表看「每段多长、多大、叫什么」。
        两者数据同源，只是呈现方式不同，切来切去不会重新发起 SIP 查询。
        （用 margin-left:auto 靠右，不用 flex:1 的空 span —— 空 span 会吃掉
        剩余宽度，反而把后面的控件挤到下一行。
      -->
      <el-radio-group v-model="viewMode" size="default" class="view-switch">
        <el-radio-button value="timeline">
          <el-icon><Clock /></el-icon> 时间轴
        </el-radio-button>
        <el-radio-button value="list">列表</el-radio-button>
      </el-radio-group>
    </div>

      <!--
        时间轴视角。

        <p>横向是一整天（00:00 → 24:00），有色块的地方就有录像。
        点色块直接回放，点空白处提示该时刻无录像 —— 比先看列表再找
        「哪一段覆盖 14:30」直观得多，也顺手解决「录像在哪儿」的定位问题。

        <p>没有做拖拽「连续回放」：设备回放是按段点播的，跨段连续播放要
        平台侧排队换流，属于另一个量级的工作。

        <p><b>但拖拽可以用来框选下载区间</b> —— 而且这是当前唯一既能快、
        又不损失画质的手段：无损下载的耗时由内容时长决定（设备按 1 倍速推流），
        要快就只能少下。大部分时候用户真正需要的只是几小时里的一小段。
        拖选距离小于 {@link DRAG_THRESHOLD_PX} 像素仍当作点击（继续回放）。
      -->
      <div v-if="viewMode === 'timeline'" v-loading="searching" class="timeline">
        <div class="timeline-scale">
          <span
            v-for="(t, i) in TICK_LABELS"
            :key="t"
            :class="{ first: i === 0, last: i === TICK_LABELS.length - 1 }"
            :style="{ left: (i / (TICK_LABELS.length - 1)) * 100 + '%' }"
          >{{ t }}</span>
        </div>
        <div
          class="timeline-track"
          :class="{ 'is-dragging': drag.active }"
          @click="onTrackClick"
          @mousemove="onTrackMove"
          @mouseleave="onTrackLeave"
          @mousedown="onDragStart"
        >
          <div
            v-for="s in timelineSegments"
            :key="s.startMs + '-' + s.endMs"
            class="timeline-seg"
            :class="`seg-${s.type}`"
            :style="{ left: s.left + '%', width: s.width + '%' }"
            :title="segTitle(s)"
          />
          <!-- 框选区：下载时只取这一截，选区外变暗，一目了然 -->
          <div
            v-if="dragRect"
            class="timeline-sel"
            :style="{ left: dragRect.left + '%', width: dragRect.width + '%' }"
          >
            <span class="sel-label">{{ dragRect.label }}</span>
          </div>
          <!--
            悬停提示：刻度只有每 2 小时一根，指针落在哪仍需看具体时刻。
            跟鼠标走而不是固定在轨道底部 —— 固定位置会和刻度条挤在一起，
            跟着走时视线不用在轨道与刻度之间来回跳。
          -->
          <div
            v-if="hoverAt"
            class="timeline-tip"
            :style="{ left: hoverAt.percent + '%' }"
            :class="{ 'tip-hit': hoverAt.hit }"
          >
            <span class="tip-clock">{{ hoverAt.clock }}</span>
            <span v-if="hoverAt.hit" class="tip-seg">{{ hoverAt.segText }}</span>
          </div>
          <!--
            不画「当前播放位置」指针：回放用的是模态弹窗，遮罩会把整条时间轴盖住，
            指针即使算了也永远看不到。真要跟随进度，得先把播放器改成非模态浮层
            或右侧抽屉，那是另一个量级的交互改动，不在这里假装实现。
          -->
          <div v-if="!timelineSegments.length" class="timeline-empty">
            {{ searched ? '当日没有录像' : '选择通道后点「检索」' }}
          </div>
        </div>
        <div class="timeline-foot">
          <span class="tl-legend"><i class="tl-dot seg-time" />定时</span>
          <span class="tl-legend"><i class="tl-dot seg-alarm" />报警</span>
          <span class="tl-legend"><i class="tl-dot seg-manual" />手动</span>
          <span class="tl-spacer" />
          <span v-if="timelineSegments.length" class="tl-sum">
            共 {{ timelineSegments.length }} 段 · 当日累计 {{ fmtDuration(timelineTotalSeconds) }}
          </span>
        </div>

        <!--
          框选后的操作条。

          不直接「框完就下载」：拖拽很容易误触（尤其想点某段播放时手抖），
          而下载一旦开始就要占一路回放会话。让它停在这里等一次明确的点击，
          同时把「多长、要等多久」写在同一行，用户能一眼判断值不值得下。
        -->
        <div v-if="selection" class="tl-selbar">
          <el-icon class="selbar-icon"><Scissor /></el-icon>
          <span class="selbar-range mono">
            {{ selection.startText }} ~ {{ selection.endText }}
          </span>
          <span class="selbar-dur">
            {{ fmtDuration(selection.seconds) }} · 下载约需 {{ fmtDuration(selection.seconds) }}
          </span>
          <span class="tl-spacer" />
          <el-button size="small" type="primary" @click="downloadSelection">
            <el-icon><Download /></el-icon> 下载这一区间
          </el-button>
          <el-button size="small" text @click="clearSelection">取消</el-button>
        </div>
        <p v-else-if="timelineSegments.length" class="tl-draghint">
          在时间轴上按住拖动可框选区间 —— 只下载需要的那一段，画质不受影响
        </p>
      </div>

      <el-table
        v-else
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
    </el-card>

    <!-- 回放对话框 -->
    <el-dialog
      v-model="playDialog"
      :title="playTitle"
      width="900px"
      top="5vh"
      @close="closePlay"
      @closed="onPlayClosed"
    >
      <div class="video-box">
        <div ref="videoContainer" class="video-container" />
      </div>

      <!--
        进度条 / 暂停 / 倍速 全部由 xgplayer 自带控制栏提供（回放模式不禁用这些插件），
        底层动作转成设备 SIP INFO。这里不再自绘任何时间文字 ——
        播放器已经显示了「当前秒 / 总时长」，再加一行只会重复。
      -->
      <div class="play-bar">
        <el-tag :type="stateType" size="small">{{ state }}</el-tag>
      </div>

      <template #footer>
        <el-button @click="closePlay">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { CircleCheck, Download, Loading, Scissor, VideoPlay } from '@element-plus/icons-vue'
import { useRoute } from 'vue-router'
import { deviceApi, playbackApi, systemApi } from '@/api'
import { resolvePlayUrls } from '@/player/resolvePlayUrls'
import { useXgPlayer } from '@/composables/useXgPlayer'
import { usePlayerStateType } from '@/composables/usePlayerState'
import { useDownloadStore } from '@/stores/download'

const route = useRoute()
/** 后台下载任务（全局）：本页只负责发起，进度统一在顶栏面板看。 */
const downloads = useDownloadStore()

const channels = ref([])
const records = ref([])
const searching = ref(false)
const searched = ref(false)
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

const playDialog = ref(false)
const current = ref(null)
const mediaHttpPort = ref(null)

/** 播放器容器（xgplayer 在内部创建 video 元素）。 */
const videoContainer = ref(null)
const player = useXgPlayer(videoContainer)
const playing = computed(() => player.playing.value)
const state = computed(() => player.state.value)
const stateType = usePlayerStateType(state)

const serial = computed(() => query.channelKey.split('/')[0] || '')
const code = computed(() => query.channelKey.split('/')[1] || '')

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

/** 横向刻度：每 2 小时一个，末尾补 24:00 收尾（共 13 个）。 */
const TICK_LABELS = Array.from({ length: 13 }, (_, i) => String(i * 2).padStart(2, '0') + ':00')

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

/** 当日累计录像时长（秒）。按裁剪后的可见部分累加，跨天段两天各算一半。 */
const timelineTotalSeconds = computed(() =>
  timelineSegments.value.reduce((sum, s) => sum + (s.clippedEndMs - s.clippedStartMs) / 1000, 0)
)

/** 色块的悬浮提示：起止 + 时长（跨天段额外说明）。 */
function segTitle(s) {
  const dur = fmtDuration((s.endMs - s.startMs) / 1000)
  const tail = s.clipped ? '（跨天段，此处只画当日部分）' : ''
  return `${fmtClock(s.startMs)} ~ ${fmtClock(s.endMs)}，时长 ${dur}${tail}`
}

/**
 * 把鼠标位置换算成「当天第几毫秒 + 百分比」。
 *
 * <p>点击与悬停共用同一套换算，否则两处各自算一遍迟早会算出不同的值 ——
 * 用户看到 tip 显示有录像，点下去却提示没有，是最难排查的那类 bug。
 */
function trackPoint(ev) {
  const base = dayStartMs.value
  if (base === null) return null
  const rect = ev.currentTarget.getBoundingClientRect()
  if (!rect.width) return null
  const ratio = Math.min(1, Math.max(0, (ev.clientX - rect.left) / rect.width))
  return { at: base + ratio * DAY_MS, percent: ratio * 100 }
}

/**
 * 悬停状态：显示鼠标所指的时刻，命中录像段时附带该段的起止与时长。
 *
 * <p>只更新一个响应式对象、不做任何查询，因此可以跟着 mousemove 直接跑。
 * {@code percent} 限制在 [0.5, 99.5] 内给 CSS 用于防溢出（tip 有最小宽度，
 * 贴边时会被裁掉一半）。
 */
const hoverAt = ref(null)

/**
 * 时间轴拖拽框选。
 *
 * <h3>为什么用拖拽来「选下载区间」</h3>
 * 无损下载的耗时由内容时长决定 —— 设备是按 1 倍速推流的，2 小时的录像
 * 就得等 2 小时，这是物理上限，任何平台都改不了。想更快只有两条路：
 * <ul>
 *   <li>让设备只推关键帧（现有「快进」模式）—— 快，但画质没了；</li>
 *   <li><b>少下一部分</b> —— 快得更多，且画质完全不受影响。</li>
 * </ul>
 * 用户真正需要的往往只是几小时里那几分钟（事故、异常），
 * 所以「框选区间」比任何倍速都更贴近实际需求。
 *
 * <h3>为什么要区分点击与拖拽</h3>
 * 「点色块回放」是这一页的主要操作，不能被拖拽抢走。用位移阈值区分：
 * 按下到抬起位移小于 {@link DRAG_THRESHOLD_PX} 视为点击，照常回放。
 */
const DRAG_THRESHOLD_PX = 4

const drag = reactive({ active: false, startX: 0, startAt: 0, moved: false })

/** 框选结果；`null` 表示没有选区。 */
const selection = ref(null)

/**
 * 框选区的渲染矩形（百分比 + 标签）。
 *
 * <p>宽度下限 0.6%：选 30 秒在 1000px 轨道上不足 1px，看不见自己选了什么。
 */
const dragRect = computed(() => {
  const s = selection.value
  const base = dayStartMs.value
  if (!s || base === null) return null
  const left = ((s.startMs - base) / DAY_MS) * 100
  const width = ((s.endMs - s.startMs) / DAY_MS) * 100
  return {
    left: Math.max(0, left),
    width: Math.max(width, 0.6),
    label: fmtDuration(s.seconds)
  }
})

function onDragStart(ev) {
  const point = trackPoint(ev)
  if (!point) return
  drag.active = true
  drag.moved = false
  drag.startX = ev.clientX
  drag.startAt = point.at
  selection.value = null
}

function onTrackMove(ev) {
  const point = trackPoint(ev)
  if (!point) {
    hoverAt.value = null
    return
  }
  if (drag.active) {
    // 位移不足阈值：还没决定是点击还是拖拽，先不画选区
    if (!drag.moved && Math.abs(ev.clientX - drag.startX) < DRAG_THRESHOLD_PX) {
      return
    }
    drag.moved = true
    selection.value = buildSelection(drag.startAt, point.at)
    hoverAt.value = null
    return
  }
  const hit = timelineSegments.value.find(
    (s) => point.at >= s.clippedStartMs && point.at < s.clippedEndMs
  )
  hoverAt.value = {
    percent: Math.min(99.5, Math.max(0.5, point.percent)),
    clock: fmtClock(point.at),
    hit: !!hit,
    segText: hit
      ? `${fmtClock(hit.startMs)}–${fmtClock(hit.endMs)} · ${fmtDuration((hit.endMs - hit.startMs) / 1000)}`
      : ''
  }
}

/**
 * 把拖拽的两个端点整理成选区。
 *
 * <p>端点可能左拖也可能右拖，先归一化再交给下游；不足 1 秒的选区直接丢弃，
 * 避免用户手抖点出「0 秒区间」后点下载拿到空文件。
 */
function buildSelection(atA, atB) {
  const a = Math.min(atA, atB)
  const b = Math.max(atA, atB)
  const seconds = Math.round((b - a) / 1000)
  if (seconds < 1) return null
  return {
    startMs: a,
    endMs: b,
    seconds,
    startText: fmtClock(a),
    endText: fmtClock(b)
  }
}

function onTrackLeave() {
  hoverAt.value = null
  // 鼠标移出轨道时结束拖拽：否则在轨道外松手收不到 mouseup，选区会一直挂着
  drag.active = false
}

/**
 * 时间轴点击：有选区时先清选区（用户的意图是回到「点播」），
 * 否则播命中时刻的那一段。
 *
 * <p>拖拽结束后浏览器还会补一个 click，用 {@code drag.moved} 把它吞掉 ——
 * 否则松手瞬间会在选区之外又开始播放一段录像，两个动作打架。
 */
function onTrackClick(ev) {
  if (drag.moved) {
    drag.moved = false
    return
  }
  if (selection.value) {
    clearSelection()
    return
  }
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
  play(hit.row)
}

function clearSelection() {
  selection.value = null
  drag.active = false
  drag.moved = false
}

/**
 * 下载框选的区间。
 *
 * <p>与「整段下载」走同一套后端流程，只是把时长换成选区秒数：
 * {@code playback/start} 的 t= 用选区起止，{@code download/start} 的
 * duration 用选区长度，收尾时按 wall clock 卡住，正好录完这一截。
 *
 * <p><b>区间下载必须快进模式之外保持 1 倍速</b>：倍速会掉帧，
 * 而用户框选区间的目的就是「少下一点、画质别动」，两者不能混。
 */
async function downloadSelection() {
  const s = selection.value
  if (!s) return
  const day = day.value
  const row = {
    startTime: `${day}T${s.startText}`,
    endTime: `${day}T${s.endText}`,
    filePath: '',
    fileSize: 0
  }
  await startDownload(row, { seconds: s.seconds, speed: 1 })
  clearSelection()
}

/* ==================== 设备录像状态 ==================== */

/**
 * 设备当前是否在录像：`null` 未知 / `true` 在录 / `false` 未录。
 *
 * <p>用「最近一段时间有没有覆盖到当前时刻的录像段」来判断，是当前
 * GB/T 28181 下唯一通用的办法 —— 设备不会主动上报「我要开始录像了」，
 * 目录应答里也没有录像状态字段。
 */
const recordingNow = ref(null)
const recChecking = ref(false)

/** 判为「正在录」的容差：段尾距当前不超过这么久就算在录（秒）。 */
const REC_TOLERANCE_SECOND = 180

/** 状态查询区间：往回看 15 分钟。计划录像常见 2 小时一段，够覆盖。 */
const REC_LOOKBACK_MS = 15 * 60 * 1000

const recStateText = computed(() => {
  if (!query.channelKey) return '录像状态 —'
  if (recChecking.value) return '状态查询中…'
  if (recordingNow.value === null) return '录像状态 —'
  return recordingNow.value ? '● 正在录像' : '○ 当前未录像'
})

const recStateType = computed(() => {
  if (recordingNow.value === true) return 'success'
  if (recordingNow.value === false) return 'info'
  return ''
})

const recStateHint = computed(() => {
  if (!query.channelKey) return '先选择通道'
  if (recChecking.value) return '正在向设备查询最近 15 分钟有无录像'
  if (recordingNow.value === null) return '点一下重新查询设备录像状态'
  return recordingNow.value
    ? '设备最近 15 分钟有录像覆盖到当前时刻（点击重新查询）'
    : '设备最近 15 分钟没有覆盖到当前时刻的录像（点击重新查询）'
})

/**
 * 查询设备当前录像状态。
 *
 * <p>刻意<b>不</b>用当天的检索结果判断：那查的是「用户选中的日期」，
 * 用户翻到上周三时，看的是上周三有没有录像，与「现在是否在录」无关。
 * 必须单独问一次设备「最近 15 分钟」。
 *
 * <p>查询失败一律记为「未知」而不是「未录像」：设备离线、SIP 超时都会
 * 走到异常分支，把它们显示成「未录像」会变成误报 —— 用户可能据此去
 * 现场检查一台其实在正常录像的设备。
 */
async function refreshRecState() {
  if (!query.channelKey || recChecking.value) return
  recChecking.value = true
  recordingNow.value = null
  try {
    const now = Date.now()
    const data = await playbackApi.recordList({
      serial: serial.value,
      code: code.value,
      starttime: fmtFull(new Date(now - REC_LOOKBACK_MS)),
      endtime: fmtFull(new Date(now))
    })
    const list = data?.recordList || []
    const deadline = now - REC_TOLERANCE_SECOND * 1000
    recordingNow.value = list.some((r) => {
      const end = parseWall(r.endTime)
      return Number.isFinite(end) && end >= deadline
    })
  } catch (e) {
    recordingNow.value = null
  } finally {
    recChecking.value = false
  }
}

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
  hoverAt.value = null
  // 录像状态与日期无关，跟通道走；失败不打断主流程
  refreshRecState()
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

async function search() {
  if (!query.channelKey) {
    ElMessage.warning('请先选择通道')
    return
  }
  if (!day.value) {
    ElMessage.warning('请选择日期')
    return
  }
  searching.value = true
  try {
    const data = await playbackApi.recordList({
      serial: serial.value,
      code: code.value,
      starttime: dayStartTime.value,
      endtime: dayEndTime.value
    })
    records.value = data?.recordList || []
    searched.value = true
    if (!records.value.length) {
      ElMessage.info('当日无录像')
    }
  } catch (e) {
    ElMessage.error(e.message || '检索失败')
  } finally {
    searching.value = false
  }
}


/** 发起回放并播放。 */
async function play(row) {
  /*
   * 【先请求、成功后再开窗】
   *
   * <p>改造前这里第一步就是 {@code playDialog=true}，于是请求被拒时
   * （典型场景：该通道正在后台下载，回放会话被锁）用户会看到一个
   * 空弹窗 + 一条错误提示 —— 弹窗里没有任何内容，关掉还得再点一次。
   * 先拿到结果再开窗，失败时停在列表页、只留一条说明原因的提示。
   */
  await ensureMediaPort()

  let info = null
  try {
    // 回放响应已包含全部播放地址
    info = await playbackApi.start({
      serial: serial.value,
      code: code.value,
      filePath: row.filePath,
      starttime: row.startTime,
      endtime: row.endTime
    })
  } catch (e) {
    ElMessage.error(e.message || '回放启动失败')
    return
  }

  current.value = row
  playDialog.value = true
  // 进度条总长取录像段的起止时间差；位置归零
  segmentSeconds.value = segmentLengthOf(row)
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
    vod: {
      duration: segmentSeconds.value,
      onSeek: (sec) => control('seek', { range: String(Math.floor(sec)) }),
      onPause: () => control('pause'),
      onResume: () => control('play', { range: 'now' }),
      onRate: (rate) => control('scale', { scale: rate })
    }
  })
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

/* ==================== 进度显示 ==================== */
/*
 * 进度条本身由 xgplayer 控制（回放模式不 ignores 进度插件），
 * 拖动/暂停/倍速都通过 vod 回调转成设备信令。
 * 这里只做两件事：把播放器报上来的位置换算成墙钟时间、给下载算段长。
 */

/** 录像段总时长（秒）。 */
const segmentSeconds = ref(0)
/** 播放位置（相对录像段起点的秒数）；由播放器的 timeupdate 驱动。 */
const playPosition = computed(() => player.position.value)

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
 * 回放弹窗标题。
 *
 * <p>不能拼 {@code filePath}：大量设备（LiveGBS、多数国产摄像机）根本不填
 * FilePath，标题会变成「录像回放 · —」这种没有信息量的东西。
 * 时间段才是用户真正用来确认「我点的是哪一段」的依据。
 */
const playTitle = computed(() => {
  const r = current.value
  if (!r) return '录像回放'
  if (r.startTime && r.endTime) {
    return `录像回放 ${r.startTime.replace('T', ' ')} ~ `
      + `${String(r.endTime).slice(11).replace('T', ' ')}`
  }
  return r.filePath ? `录像回放 · ${shortName(r.filePath)}` : '录像回放'
})

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

/** * 关闭回放弹窗。
 *
 * <p><b>必须显式把 {@code playDialog} 置 false</b>。早先这里只做了
 * 「停播放器 + 停设备回放 + 清 current」，底部那个「关闭」按钮就变成了
 * 一句空指令 —— 点了既不关窗也不断流，看起来像按钮坏了。
 * （右上角 × 走的是 el-dialog 内部的 {@code update:modelValue false}，
 * 跟这个函数无关，所以那边一直正常。）
 *
 * <p>清 {@code current} 放到 {@link onPlayClosed}：这里清掉的话，
 * 标题（依赖 current.filePath）会在关闭动画还没跑完时先变成空串，闪一下。
 */
function closePlay() {
  playDialog.value = false
  // 立即停播并通知设备停止回放：别等 300ms 的关闭动画
  player.stop()
  /*
   * 正在下载时不要主动停流：下载与回放共用同一路回放流（同一个会话键），
   * 停掉它等于把正在录制的源掐了，用户拿到的 MP4 会是半截。
   * 与后端一致：{@code playback/stop} 在下载中也会返回 ok=false 并跳过停流，
   * 这里少发一次请求只是省一个无用调用；真正的保护在服务端。
   */
  if (current.value && !downloads.runningCount) {
    playbackApi.stop({ serial: serial.value, code: code.value }).catch(() => {})
  }
}

/** 弹窗完全关闭（动画结束、DOM 销毁后）再清数据。 */
function onPlayClosed() {
  current.value = null
  segmentSeconds.value = 0
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
  await loadChannels()
  // 从通道列表点「设备录像」跳进来时预填设备/通道
  const serial = String(route.query.serial || '').trim()
  const code = String(route.query.code || '').trim()
  if (serial && code) {
    query.channelKey = `${serial}/${code}`
    /*
     * 预填走的是 query 参数，不会经过 onChannelChange，
     * 所以这里要显式查一次录像状态 —— 否则从通道列表跳进来时
     * 徽标一直停在「录像状态 —」，而用户正是为了确认状态才点进来的。
     */
    refreshRecState()
  }
})
onUnmounted(() => {
  player.stop()
})
</script>

<style scoped>
/* ==================== 时间轴 ==================== */

.timeline {
  padding: var(--sp-2) 0 var(--sp-1);
}

/* 日期导航按钮组与日期框贴紧，视为一个整体控件 */
.day-nav :deep(.el-button) {
  padding-left: 8px;
  padding-right: 8px;
}

/* 视角切换靠右：用 margin-left:auto 而不是塞一个 flex:1 的占位 span，
   占位 span 会把剩余宽度全吃掉，后面的控件只能换行 */
.view-switch {
  margin-left: auto;
}

/* 列表页脚：左侧数量、右侧下载倍速 */
.list-footer {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
}

.lf-spacer {
  flex: 1;
}

.lf-label {
  font-size: var(--fs-xs);
  color: var(--text-tertiary);
}

/*
 * 刻度条。
 *
 * 每个刻度绝对定位在 {@code (i / 12) * 100%} 处再自左平移自身宽度的一半，
 * 让文字中心严格压在网格线上。首尾两个例外：00:00 左对齐、24:00 右对齐，
 * 否则文字会溢出到轨道外面（0% 处居中的话有一半在轨道左侧）。
 *
 * 之前用 flex + space-between 看着也行，但文字宽度不参与定位，
 * 首位标签实际落在「内边距 + 半个文字宽」的位置，在 1440px 宽的轨道上
 * 相当于偏了十几分钟，把时间轴当刻度尺用时会误判。
 */
.timeline-scale {
  position: relative;
  height: 18px;
  font-size: var(--fs-xs);
  color: var(--text-tertiary);
  font-variant-numeric: tabular-nums;
  user-select: none;
}

.timeline-scale span {
  position: absolute;
  top: 0;
  transform: translateX(-50%);
  white-space: nowrap;
}

.timeline-scale span.first {
  transform: none;
}

.timeline-scale span.last {
  transform: translateX(-100%);
}

/*
 * 轨道本体。
 *
 * 高度 54px 是个折中：太薄（<30px）色块上点不准、悬浮提示也不好触发；
 * 太厚会把这页的主角（下面的播放器/列表）挤下去。
 * cursor: pointer 表示「整条轨道可点」，点空白处会提示无录像，
 * 不会让人以为点了没反应。
 */
.timeline-track {
  position: relative;
  height: 54px;
  border: 1px solid var(--line);
  border-radius: var(--radius-sm, 6px);
  background:
    repeating-linear-gradient(
      to right,
      var(--fill-color, rgba(128, 128, 128, 0.06)) 0,
      var(--fill-color, rgba(128, 128, 128, 0.06)) 1px,
      transparent 1px,
      transparent calc(100% / 12)
    );
  overflow: hidden;
  cursor: pointer;
}

/* 录像色块：垂直居中一条，上下留白让「稀疏」和「密集」都看得清 */
.timeline-seg {
  position: absolute;
  top: 12px;
  bottom: 12px;
  min-width: 2px;
  border-radius: 2px;
  background: var(--accent, #3b82f6);
  opacity: 0.85;
  transition: opacity 0.12s, transform 0.12s;
}

.timeline-seg:hover {
  opacity: 1;
  transform: scaleY(1.25);
}

/* 按录像类型区分颜色：定时录像是常态（主色），报警/手动是重点（暖色） */
.timeline-seg.seg-alarm {
  background: var(--danger, #ef4444);
}

.timeline-seg.seg-manual {
  background: var(--warning, #f59e0b);
}


.timeline-empty {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: var(--fs-xs);
  color: var(--text-tertiary);
  pointer-events: none;
}

/*
 * 悬停提示。
 *
 * 定位在轨道上方（bottom:100%），不遮住色块 —— 提示里会显示所在段的
 * 起止时间，恰好是用户想拿它去对照的东西，挡住比不显示更糟。
 * pointer-events:none 避免提示本身吃 mousemove 导致抖动。
 */
.timeline-tip {
  position: absolute;
  bottom: calc(100% + 4px);
  transform: translateX(-50%);
  z-index: 3;
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 3px 7px;
  border-radius: 4px;
  background: var(--bg-elevated, #1f2937);
  color: #fff;
  font-size: var(--fs-xs);
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
  pointer-events: none;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.18);
}

/* 命中录像段时左侧加一条与色块同色的竖线，视线不用二次确认 */
.timeline-tip.tip-hit::before {
  content: '';
  width: 3px;
  align-self: stretch;
  border-radius: 1px;
  background: var(--accent, #3b82f6);
}

.tip-clock {
  font-weight: 600;
}

.tip-seg {
  opacity: 0.8;
}

/* 录像状态标签：可点击重查，要给出手型光标 */
.rec-tag.clickable {
  cursor: pointer;
}

.rec-tag.clickable:hover {
  opacity: 0.8;
}

/*
 * 框选区。
 *
 * 用半透明色块盖在录像色块上（不在下面，否则会被色块盖住看不见），
 * overflow 不裁切，让两端的小手柄能突出去一点，明确「可再拖」。
 */
.timeline-sel {
  position: absolute;
  top: 0;
  bottom: 0;
  background: rgba(59, 130, 246, 0.24);
  border-left: 2px solid var(--accent, #3b82f6);
  border-right: 2px solid var(--accent, #3b82f6);
  pointer-events: none;
  z-index: 2;
}

/* 时长标签：贴选区顶部中间，太窄时允许溢出（比看不到好） */
.sel-label {
  position: absolute;
  top: 2px;
  left: 50%;
  transform: translateX(-50%);
  padding: 0 4px;
  border-radius: 3px;
  background: var(--accent, #3b82f6);
  color: #fff;
  font-size: 11px;
  line-height: 16px;
  white-space: nowrap;
}

/* 拖拽中：整条轨道用十字光标，暗示「这里可以拖」 */
.timeline-track.is-dragging,
.timeline-track:active {
  cursor: crosshair;
}

/* 框选后的操作条 */
.tl-selbar {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  margin-top: var(--sp-2);
  padding: 6px 10px;
  border: 1px solid var(--accent, #3b82f6);
  border-radius: var(--radius-sm, 6px);
  background: rgba(59, 130, 246, 0.06);
  font-size: var(--fs-sm);
}

.selbar-icon {
  color: var(--accent, #3b82f6);
}

.selbar-range {
  font-variant-numeric: tabular-nums;
}

.selbar-dur {
  font-size: var(--fs-xs);
  color: var(--text-tertiary);
}

.tl-draghint {
  margin: var(--sp-2) 0 0;
  font-size: var(--fs-xs);
  color: var(--text-tertiary);
}

/* 图例 + 汇总：一行放完，汇总靠右 */
.timeline-foot {
  display: flex;
  align-items: center;
  gap: var(--sp-3);
  padding-top: var(--sp-2);
  font-size: var(--fs-xs);
  color: var(--text-tertiary);
}

.tl-spacer {
  flex: 1;
}

.tl-legend {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  user-select: none;
}

.tl-dot {
  display: inline-block;
  width: 10px;
  height: 6px;
  border-radius: 2px;
  background: var(--accent, #3b82f6);
}

.tl-dot.seg-alarm {
  background: var(--danger, #ef4444);
}

.tl-dot.seg-manual {
  background: var(--warning, #f59e0b);
}

.tl-sum {
  font-variant-numeric: tabular-nums;
}

.play-bar {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 16px;
  flex-wrap: wrap;
}

.play-bar .spacer {
  flex: 1;
}

/*
 * 后台下载提示。下载进度已改到顶栏的「下载任务」面板（全局、跨页面），
 * 本页只在表格底部提一句「有几个任务在跑」，避免用户以为点了没反应。
 */
.dl-hint {
  margin-left: 6px;
  color: var(--accent-ink, #2563eb);
}
</style>
