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
              :loading="download.rowKey === rowKeyOf(row)"
              :disabled="!!download.rowKey && download.rowKey !== rowKeyOf(row)"
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

      <div class="table-footer">共 {{ records.length }} 条录像</div>
    </el-card>

    <!--
        下载进度条（列表页顶部）。
        下载会占用一路回放流并持续录制，属于「后台任务」——
        放在列表页而不是回放弹窗里，用户可以边等边继续检索别的时段，
        也不必为了下载先去回放。
    -->
    <el-card v-if="download.rowKey" class="download-card" shadow="never">
      <div class="pb-download">
        <template v-if="download.url">
          <el-icon class="pb-dl-ok"><CircleCheck /></el-icon>
          <span>
            已生成 {{ formatSize(download.fileSize) }}
            <span class="pb-dl-name mono">{{ download.fileName }}</span>
          </span>
          <div class="spacer" />
          <el-button size="small" type="primary" @click="saveFile">保存到本地</el-button>
          <el-button size="small" @click="resetDownload">关闭</el-button>
        </template>
        <template v-else>
          <el-icon class="is-loading"><Loading /></el-icon>
          <!--
            显示「已生成 / 共」而不是只有百分比：录像按实时速度推流，
            2 小时的录像要下 2 小时，早期进度永远是 0%，光看百分比会以为卡死了。
          -->
          <span>正在生成录像 {{ fmtDuration(download.recordedSecond) }} / {{ fmtDuration(download.totalSecond) }}</span>
          <el-progress
            :percentage="download.percent"
            :stroke-width="8"
            :show-text="false"
            class="pb-dl-progress"
          />
          <span class="mono pb-dl-pct">{{ download.percent }}%</span>
          <el-button size="small" @click="cancelDownload">取消</el-button>
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
        <div class="spacer" />
        <el-select v-model="proto" size="small" style="width: 160px" @change="switchProto">
          <el-option label="WebSocket-FLV" value="wsflv" />
          <el-option label="WebRTC（低延迟）" value="webrtc" />
        </el-select>
      </div>

      <template #footer>
        <el-button @click="closePlay">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { CircleCheck, Download, Loading, VideoPlay } from '@element-plus/icons-vue'
import { useRoute } from 'vue-router'
import { deviceApi, playbackApi, systemApi } from '@/api'
import { resolvePlayUrls } from '@/player/resolvePlayUrls'
import { useXgPlayer } from '@/composables/useXgPlayer'
import { usePlayerStateType } from '@/composables/usePlayerState'

const route = useRoute()

const channels = ref([])
const records = ref([])
const searching = ref(false)
const searched = ref(false)
const range = ref(null)
const query = reactive({ channelKey: '' })

const playDialog = ref(false)
const current = ref(null)
const proto = ref('wsflv')
const mediaHttpPort = ref(null)

/** 播放器容器（xgplayer 在内部创建 video 元素）。 */
const videoContainer = ref(null)
const player = useXgPlayer(videoContainer)
const playing = computed(() => player.playing.value)
const state = computed(() => player.state.value)
const stateType = usePlayerStateType(state)

const serial = computed(() => query.channelKey.split('/')[0] || '')
const code = computed(() => query.channelKey.split('/')[1] || '')

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
  resetDownload()
  await ensureMediaPort()
  await sleep(120)

  const streamId = `${serial.value}/${code.value}`
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
   * vod 参数让播放器切成「伪点播」：进度条 / 暂停 / 倍速 都用 xgplayer 自带的，
   * 但底层动作全部转成设备信令。见 useXgPlayer#applyVodControls。
   */
  await player.play(streamId, urls, {
    proto: proto.value,
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
 * 入口放在列表操作列而不是回放弹窗里：下载会占一路流并持续几十秒到几分钟，
 * 属于后台任务，用户不该为了下个文件被迫先点开回放。
 */

/** 当前下载任务所属的行（`设备:通道:起始时间`）；空表示无任务。 */
const download = reactive({
  rowKey: '',
  recordedSecond: 0,
  totalSecond: 0,
  percent: 0,
  fileName: '',
  fileSize: 0,
  url: ''
})
let downloadTimer = null

/** 表格行唯一键：同一通道可能有多个录像段，必须带上起始时间。 */
function rowKeyOf(row) {
  return `${row?.startTime || ''}|${row?.endTime || ''}`
}

function resetDownload() {
  stopDownloadPolling()
  download.rowKey = ''
  download.recordedSecond = 0
  download.totalSecond = 0
  download.percent = 0
  download.fileName = ''
  download.fileSize = 0
  download.url = ''
}

function stopDownloadPolling() {
  if (downloadTimer) {
    clearInterval(downloadTimer)
    downloadTimer = null
  }
}

/** 录像段在设备侧的流名（回放会话键）。 */
function playbackKeyOf() {
  return `${serial.value}/${code.value}_playback`
}

/**
 * 开始下载某段录像。
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
  resetDownload()
  download.rowKey = rowKeyOf(row)
  download.totalSecond = seconds

  try {
    // 1) 起回放（已有则复用），拿到流
    await playbackApi.start({
      serial: serial.value,
      code: code.value,
      filePath: row.filePath,
      starttime: row.startTime,
      endtime: row.endTime
    })
    // 2) 让 ZLM 对该流开录；片长给足，避免长录像被切成多个文件
    await playbackApi.downloadStart({
      streamid: playbackKeyOf(),
      duration: seconds
    })
    ElMessage.success('已开始生成录像文件')
    pollDownload()
  } catch (e) {
    resetDownload()
    ElMessage.error(e.message || '下载失败')
  }
}

/**
 * 轮询下载进度。
 *
 * <p>每秒一次：录制时长以秒计，更密的轮询没有意义还会压后端。
 * 进度到 100% 自动收尾 —— 用户点完下载通常会去干别的，
 * 让他回来手动点「完成」不符合预期。
 */
function pollDownload() {
  stopDownloadPolling()
  downloadTimer = setInterval(async () => {
    try {
      const p = await playbackApi.downloadProgress({ streamid: playbackKeyOf() })
      download.recordedSecond = p.recordedSecond || 0
      download.totalSecond = p.totalSecond || download.totalSecond
      /*
       * 百分比保留一位小数。
       * 整数百分比在下载长录像时几乎不动（2 小时的段，前 72 秒都是 0%），
       * 用户会以为任务卡死而反复点取消。后端返回的是整数，
       * 这里用「已录秒数 / 总秒数」自己算更细的值。
       */
      download.percent = download.totalSecond > 0
        ? Math.min(100, Math.round(download.recordedSecond * 1000 / download.totalSecond) / 10)
        : 0
      if (p.finished) {
        stopDownloadPolling()
        await finishDownload()
      }
    } catch (e) {
      // 任务可能已被取消或超时清理，停止轮询避免刷屏
      stopDownloadPolling()
      resetDownload()
    }
  }, 1000)
}

/** 收尾：停录制、取文件地址。 */
async function finishDownload() {
  try {
    const res = await playbackApi.downloadFinish({ streamid: playbackKeyOf() })
    if (!res?.ok) {
      resetDownload()
      ElMessage.error(res?.message || '录制未产出文件')
      return
    }
    download.url = res.url || ''
    download.fileName = res.fileName || 'record.mp4'
    download.fileSize = res.fileSize || 0
    download.percent = 100
    ElMessage.success('录像文件已生成，点「保存到本地」下载')
  } catch (e) {
    resetDownload()
    ElMessage.error(e.message || '生成下载文件失败')
  }
}

async function cancelDownload() {
  stopDownloadPolling()
  try {
    await playbackApi.downloadCancel({ streamid: playbackKeyOf() })
  } catch (e) {
    // 取消失败不影响界面复位
  }
  resetDownload()
}

/** 保存到本地（用临时 <a> 触发浏览器下载，保证带上文件名）。 */
function saveFile() {
  if (!download.url) return
  const a = document.createElement('a')
  a.href = download.url
  a.download = download.fileName
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
}

async function switchProto() {
  if (!playing.value) return
  let urls = {}
  try {
    const info = await playbackApi.streamInfo(`${serial.value}/${code.value}`)
    urls = resolvePlayUrls(info, mediaHttpPort.value)
  } catch (e) {
    // 忽略
  }
  await player.switchProtocol(proto.value, urls)
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
   * 正在下载时不能停流：下载与回放共用同一路回放流（同一个会话键），
   * 停掉它就等于把正在录制的源掐了，用户拿到的 MP4 会是半截。
   * 录制结束后由 finishDownload 自己停流。
   */
  if (download.rowKey && !download.url) {
    return
  }
  if (current.value) {
    playbackApi.stop({ serial: serial.value, code: code.value }).catch(() => {})
  }
}

/** 弹窗完全关闭（动画结束、DOM 销毁后）再清数据。 */
function onPlayClosed() {
  current.value = null
  resetDownload()
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
  stopDownloadPolling()
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

/* 进度条：左右时间用等宽字体，数值变化时不抖 */
/* 下载进度条 */
.pb-download {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 10px;
  padding: 8px 12px;
  background: var(--fill-2, #f5f7fa);
  border-radius: 6px;
  font-size: var(--fs-sm);
}

.pb-download .spacer {
  flex: 1;
}

.pb-dl-progress {
  flex: 1;
  min-width: 120px;
}

/* 百分比等宽：0.4% → 12.3% 变化时右侧按钮不会左右跳 */
.pb-dl-pct {
  min-width: 48px;
  text-align: right;
}

.pb-dl-ok {
  color: var(--success-600, #16a34a);
}
</style>
