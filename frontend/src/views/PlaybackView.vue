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
      <el-date-picker
        v-model="range"
        type="datetimerange"
        range-separator="至"
        start-placeholder="开始时间"
        end-placeholder="结束时间"
        style="width: 380px"
        value-format="YYYY-MM-DD HH:mm:ss"
      />
      <el-button type="primary" :loading="searching" @click="search">
        <el-icon><Search /></el-icon> 检索
      </el-button>
      <!--
        下载倍速：档位来自设备目录应答的 Info/DownloadSpeed（如 1/2/4/8）。
        标准 9.9.1 b) 规定倍速由请求方在 SDP 里携带（a=downloadspeed:N），
        设备支持就快进推流 —— 8 倍速下 1 小时录像约 7.5 分钟就能拿完。
        只对「下载」生效，回放永远是 1 倍速。
      -->
      <el-select
        v-model="downloadSpeed"
        style="width: 160px"
        :title="`设备声明支持的下载倍速：${speedOptions.join('/')}（设备若不支持会按 1 倍速推流）`"
      >
        <el-option
          v-for="n in speedOptions"
          :key="n"
          :label="n === 1 ? '下载 1x（实时）' : `下载 ${n}x`"
          :value="n"
        />
      </el-select>
    </div>

      <el-table v-loading="searching" :data="records" stripe table-layout="fixed">
        <el-table-column label="文件名" min-width="240">
          <template #default="{ row }">
            <span class="mono">{{ shortName(row.filePath) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="开始时间" width="180">
          <template #default="{ row }"><span class="mono">{{ row.startTime || '—' }}</span></template>
        </el-table-column>
        <el-table-column label="结束时间" width="180">
          <template #default="{ row }"><span class="mono">{{ row.endTime || '—' }}</span></template>
        </el-table-column>
        <el-table-column label="大小" width="110" align="right">
          <template #default="{ row }">{{ formatSize(row.fileSize) }}</template>
        </el-table-column>
        <el-table-column label="类型" width="100" align="center">
          <template #default="{ row }">
            <el-tag size="small" effect="plain">{{ row.type || 'time' }}</el-tag>
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
              :title="hasTaskOfRow(row) ? '该段已在后台下载，见右上角「下载任务」' : ''"
              @click="startDownload(row)"
            >
              <el-icon><Download /></el-icon> 下载
            </el-button>
          </template>
        </el-table-column>
        <template #empty>
          <div class="empty-cell">{{ searched ? '该时间段内无录像' : '请选择通道与时间范围后检索' }}</div>
        </template>
      </el-table>

      <div class="table-footer">
        共 {{ records.length }} 条录像
        <span v-if="downloads.runningCount" class="dl-hint">
          · {{ downloads.runningCount }} 个后台下载进行中，可在右上角「下载任务」查看进度
        </span>
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
import { CircleCheck, Download, Loading, VideoPlay } from '@element-plus/icons-vue'
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
const range = ref(null)
const query = reactive({ channelKey: '' })

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

/** 下载倍速（只影响下载，不影响回放播放速度）。 */
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

/* 换通道后默认取该设备支持的最高档：下载要的是快，用户一般不会主动选低档 */
watch(speedOptions, (list) => {
  downloadSpeed.value = list[list.length - 1] || 1
}, { immediate: true })

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

function onChannelChange() {
  records.value = []
  searched.value = false
  if (!range.value) {
    const end = new Date()
    const start = new Date(end.getTime() - 24 * 3600 * 1000)
    range.value = [fmt(start), fmt(end)]
  }
}

function fmt(d) {
  const p = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
}

async function search() {
  if (!query.channelKey) {
    ElMessage.warning('请先选择通道')
    return
  }
  if (!range.value || range.value.length !== 2) {
    ElMessage.warning('请选择时间范围')
    return
  }
  searching.value = true
  try {
    const data = await playbackApi.recordList({
      serial: serial.value,
      code: code.value,
      starttime: range.value[0],
      endtime: range.value[1]
    })
    records.value = data?.recordList || []
    searched.value = true
    if (!records.value.length) {
      ElMessage.info('该时间段内无录像')
    }
  } catch (e) {
    ElMessage.error(e.message || '检索失败')
  } finally {
    searching.value = false
  }
}

/** 发起回放并播放。 */
async function play(row) {
  current.value = row
  playDialog.value = true
  // 进度条总长取录像段的起止时间差；位置归零
  segmentSeconds.value = segmentLengthOf(row)
  await ensureMediaPort()
  await sleep(120)

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
 */
async function startDownload(row) {
  const seconds = segmentLengthOf(row)
  if (!seconds) {
    ElMessage.warning('该录像段缺少起止时间，无法下载')
    return
  }
  if (!(await ensureMediaPort())) return
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
      speed: downloadSpeed.value,
      // 设备上报的该段大小：后端据此判断「内容已录完」（设备不会按结束时间停推）
      fileSize: row.fileSize || 0,
      name: channelLabel.value
    })
    if (ok) {
      /*
       * 不提「N 倍速」：倍速只是请求值，设备普遍不支持（实测现场设备上报
       * 支持 8 倍速但实际仍按 1 倍速推流）。宣称倍速只会让用户按倍速估算
       * 完成时间，结果等不到。
       */
      ElMessage.success('已提交后台下载，可在右上角「下载任务」查看进度')
    }
  } finally {
    downloadingKey.value = ''
  }
}

/** 正在发起下载的行（防重复点击 / 按钮 loading）。 */
const downloadingKey = ref('')

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
  }
})
onUnmounted(() => {
  player.stop()
})
</script>

<style scoped>
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
