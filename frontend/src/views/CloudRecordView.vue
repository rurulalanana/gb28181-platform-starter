<template>
  <div>
    <header class="page-header">
      <div class="page-title-group">
        <h2 class="page-title">云端录像</h2>
        <p class="page-subtitle">平台侧录制归档与时间轴检索</p>
      </div>
    </header>

    <el-card shadow="never">
      <el-form :inline="true" class="search-form">
        <el-form-item label="通道">
          <el-select
            v-model="selectedChannel"
            placeholder="选择有录像的通道"
            filterable
            style="width: 300px"
            :loading="loadingChannels"
            @change="onChannelChange"
          >
            <el-option
              v-for="opt in channelOptions"
              :key="opt.value"
              :label="opt.label"
              :value="opt.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="日期">
          <el-date-picker
            v-model="period"
            type="date"
            placeholder="选择日期"
            value-format="YYYY-MM-DD"
            style="width: 160px"
            @change="loadDaily"
          />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="loadingDaily" @click="loadDaily">
            <el-icon><Search /></el-icon> 查询
          </el-button>
          <el-button
            type="danger"
            plain
            :disabled="!selectedChannel || !records.length"
            @click="removeDaily"
          >
            <el-icon><Delete /></el-icon> 删除当日
          </el-button>
        </el-form-item>
      </el-form>

      <!-- 本月有录像的日子 -->
      <div v-if="monthFlags" class="month-flags">
        <span class="text-muted">本月有录像的日期：</span>
        <el-button
          v-for="(flag, idx) in monthFlags.split('')"
          :key="idx"
          size="small"
          :type="flag === '1' ? 'primary' : 'default'"
          :plain="flag !== '1'"
          :disabled="flag !== '1'"
          class="day-btn"
          @click="pickDay(idx + 1)"
        >
          {{ idx + 1 }}
        </el-button>
      </div>

      <el-table v-loading="loadingDaily" :data="records" stripe>
        <el-table-column label="开始时间" min-width="160">
          <template #default="{ row }"><span class="mono">{{ row.startAt || '—' }}</span></template>
        </el-table-column>
        <el-table-column label="时长" width="100" align="center">
          <template #default="{ row }">
            {{ row.duration != null ? row.duration + ' 秒' : '—' }}
          </template>
        </el-table-column>
        <el-table-column label="大小" width="110" align="center">
          <template #default="{ row }">{{ formatSize(row.fileSize) }}</template>
        </el-table-column>
        <el-table-column label="存储" width="110" align="center">
          <template #default="{ row }">
            <el-tag :type="row.storageType === 'OSS' ? 'success' : 'info'" size="small" effect="plain">
              {{ row.storageType === 'OSS' ? '对象存储' : '本地' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="上传状态" width="110" align="center">
          <template #default="{ row }">
            <el-tag :type="uploadColor(row.uploadState)" size="small" effect="light">
              {{ uploadText(row.uploadState) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="重要" width="70" align="center">
          <template #default="{ row }">
            <el-icon
              :size="18"
              :color="row.important ? '#e6a23c' : '#c0c4cc'"
              class="star"
              @click="toggleImportant(row)"
            >
              <StarFilled v-if="row.important" />
              <Star v-else />
            </el-icon>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="200" fixed="right" align="center">
          <template #default="{ row }">
            <el-button
              link
              type="primary"
              :disabled="row.recording || row.storageType === 'OSS'"
              @click="play(row)"
            >
              <el-icon><VideoPlay /></el-icon> 点播
            </el-button>
            <el-button
              link
              type="primary"
              :disabled="row.recording || !row.downloadUrl"
              @click="download(row)"
            >
              <el-icon><Download /></el-icon> 下载
            </el-button>
            <el-button link type="danger" @click="removeOne(row)">
              <el-icon><Delete /></el-icon>
            </el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty :image-size="76" description="当日无云端录像。可在「通道管理」开启云端录像开关，或设备推流时手动开始录像" />
        </template>
      </el-table>
    </el-card>

    <!-- 点播播放器 -->
    <el-card v-if="playingRecord" shadow="never" class="mt-4">
      <template #header>
        <div class="card-header">
          <span>
            <el-icon><VideoPlay /></el-icon>
            录像点播 · <span class="mono">{{ playingRecord.startAt }}</span>
          </span>
          <div class="header-actions">
            <el-select v-model="proto" size="small" style="width: 168px" @change="switchProto">
              <el-option label="WebSocket-FLV" value="wsflv" />
              <el-option label="WebRTC（低延迟）" value="webrtc" />
            </el-select>
            <el-tag :type="playStateType" size="small">{{ playState }}</el-tag>
          </div>
        </div>
      </template>

      <div class="video-box">
        <div ref="videoContainer" class="video-container" />
      </div>

      <div class="play-actions">
        <el-button size="small" type="danger" plain @click="stopPlay">
          <el-icon><SwitchButton /></el-icon> 停止点播
        </el-button>
        <el-button
          size="small"
          :disabled="!playingRecord.downloadUrl"
          @click="download(playingRecord)"
        >
          <el-icon><Download /></el-icon> 下载本段
        </el-button>
      </div>
    </el-card>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { cloudRecordApi } from '@/api'
import { useVideoPlayer } from '@/composables/useVideoPlayer'
import { usePlayerStateType } from '@/composables/usePlayerState'

const channels = ref([])
const selectedChannel = ref('')
const period = ref(todayString())
const records = ref([])
const monthFlags = ref('')
const loadingChannels = ref(false)
const loadingDaily = ref(false)

const playingRecord = ref(null)
const proto = ref('wsflv')
/** 当前点播流的播放地址（用于切换协议时重连）。 */
let currentUrls = {}

/** 播放器容器（jessibuca 在内部创建 canvas，WebRTC 通路创建 video）。 */
const videoContainer = ref(null)
const player = useVideoPlayer(videoContainer)
const playState = computed(() => player.state.value)
const playStateType = usePlayerStateType(playState)

const channelOptions = computed(() =>
  channels.value.map((c) => ({
    label: `${c.code}${c.customName || c.name ? ' · ' + (c.customName || c.name) : ''}`,
    value: `${c.serial}/${c.code}`
  }))
)

function todayString() {
  const d = new Date()
  const pad = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
}

function formatSize(size) {
  if (!size) return '—'
  const units = ['B', 'KB', 'MB', 'GB']
  let n = size
  let i = 0
  while (n >= 1024 && i < units.length - 1) {
    n /= 1024
    i++
  }
  return n.toFixed(i === 0 ? 0 : 1) + ' ' + units[i]
}

const UPLOAD_TEXT = { NONE: '无需上传', PENDING: '待上传', UPLOADED: '已上传', FAILED: '上传失败' }

function uploadText(state) {
  return UPLOAD_TEXT[state] || '—'
}

function uploadColor(state) {
  if (state === 'UPLOADED') return 'success'
  if (state === 'PENDING') return 'warning'
  if (state === 'FAILED') return 'danger'
  return 'info'
}

async function loadChannels() {
  loadingChannels.value = true
  try {
    const data = await cloudRecordApi.queryChannels({ start: 0, limit: 500 })
    channels.value = data?.rows || []
    if (!selectedChannel.value && channels.value.length) {
      selectedChannel.value = `${channels.value[0].serial}/${channels.value[0].code}`
      onChannelChange()
    }
  } catch (e) {
    ElMessage.error(e.message || '加载有录像的通道失败')
  } finally {
    loadingChannels.value = false
  }
}

function onChannelChange() {
  loadDaily()
  loadMonthFlags()
}

/** 载入当月标记。 */
async function loadMonthFlags() {
  if (!selectedChannel.value) return
  const [serial, code] = selectedChannel.value.split('/')
  const month = period.value.slice(0, 7).replace('-', '')
  try {
    const data = await cloudRecordApi.queryMonthly({ serial, code, period: month })
    monthFlags.value = data?.flag || ''
  } catch (e) {
    monthFlags.value = ''
  }
}

async function loadDaily() {
  if (!selectedChannel.value) {
    ElMessage.warning('请先选择通道')
    return
  }
  const [serial, code] = selectedChannel.value.split('/')
  loadingDaily.value = true
  try {
    const data = await cloudRecordApi.queryDaily({
      serial,
      code,
      period: period.value.replace(/-/g, '')
    })
    records.value = data?.list || []
  } catch (e) {
    ElMessage.error(e.message || '查询失败')
  } finally {
    loadingDaily.value = false
  }
}

function pickDay(day) {
  const [y, m] = period.value.split('-')
  period.value = `${y}-${m}-${String(day).padStart(2, '0')}`
  loadDaily()
}

/** 点播：把 MP4 拉入媒体服务后播放。 */
async function play(record) {
  try {
    const data = await cloudRecordApi.play(record.recordId)
    playingRecord.value = { ...record, ...data }
    // webrtc://host:port/app/stream → 取 app/stream 作为流标识
    let stream = ''
    if (data.webrtc) {
      const match = data.webrtc.match(/^webrtc:\/\/[^/]+\/(.+)$/)
      stream = match ? match[1] : ''
    }
    if (!stream) {
      ElMessage.error('未获取到点播流地址')
      return
    }
    currentUrls = {
      wsFlv: data.wsFlv || (data.flv ? data.flv.replace(/^http/, 'ws') : ''),
      httpFlv: data.flv,
      webrtc: data.webrtc,
      hls: data.hls
    }
    await new Promise((r) => setTimeout(r, 500))
    await player.play(stream, currentUrls, { proto: proto.value })
  } catch (e) {
    ElMessage.error(e.message || '点播失败')
    playingRecord.value = null
  }
}

/** 切换播放协议。 */
async function switchProto() {
  if (!playingRecord.value) return
  await player.switchProtocol(proto.value, currentUrls)
}

async function stopPlay() {
  if (playingRecord.value) {
    try {
      await cloudRecordApi.stopPlay(playingRecord.value.recordId)
    } catch (e) {
      // 忽略
    }
  }
  player.stop()
  playingRecord.value = null
  currentUrls = {}
}

/** 下载：对象存储直接打开签名地址，本地走平台文件接口（需带 token）。 */
function download(record) {
  const url = record.downloadUrl
  if (!url) {
    ElMessage.warning('该录像暂不可下载（可能仍在录制）')
    return
  }
  if (url.startsWith('http://') || url.startsWith('https://')) {
    window.open(url, '_blank')
    return
  }
  // 本地接口需带登录态：用 fetch 拉流后触发浏览器下载
  const token = localStorage.getItem('gb_token') || ''
  fetch(url, { headers: { Authorization: token } })
    .then((resp) => {
      if (!resp.ok) throw new Error('下载失败 HTTP ' + resp.status)
      return resp.blob()
    })
    .then((blob) => {
      const link = document.createElement('a')
      link.href = URL.createObjectURL(blob)
      link.download = record.fileName || record.name || 'record.mp4'
      link.click()
      URL.revokeObjectURL(link.href)
    })
    .catch((e) => ElMessage.error(e.message || '下载失败'))
}

async function toggleImportant(record) {
  const [serial, code] = selectedChannel.value.split('/')
  try {
    await cloudRecordApi.setImportant({
      serial,
      code,
      period: record.startAt,
      important: !record.important
    })
    record.important = !record.important
    ElMessage.success(record.important ? '已标记为重要' : '已取消重要标记')
  } catch (e) {
    ElMessage.error(e.message || '操作失败')
  }
}

async function removeOne(record) {
  try {
    await ElMessageBox.confirm(
      `确定删除该录像（${record.startAt}）？文件将同时删除。`,
      '删除确认',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
  } catch (e) {
    return
  }
  const [serial, code] = selectedChannel.value.split('/')
  try {
    await cloudRecordApi.remove({ serial, code, period: record.startAt })
    ElMessage.success('已删除')
    loadDaily()
    loadMonthFlags()
  } catch (e) {
    ElMessage.error(e.message || '删除失败')
  }
}

async function removeDaily() {
  try {
    await ElMessageBox.confirm(
      `确定删除 ${period.value} 当天的全部录像？`,
      '删除确认',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
  } catch (e) {
    return
  }
  const [serial, code] = selectedChannel.value.split('/')
  try {
    const data = await cloudRecordApi.removeDaily({
      serial,
      code,
      period: period.value.replace(/-/g, '')
    })
    ElMessage.success(`已删除 ${data?.deleted ?? 0} 条`)
    loadDaily()
    loadMonthFlags()
  } catch (e) {
    ElMessage.error(e.message || '删除失败')
  }
}

function waitIceGathering(conn) {
  return new Promise((resolve) => {
    if (conn.iceGatheringState === 'complete') {
      resolve()
      return
    }
    const timer = setTimeout(resolve, 3000)
    conn.addEventListener('icegatheringstatechange', () => {
      if (conn.iceGatheringState === 'complete') {
        clearTimeout(timer)
        resolve()
      }
    })
  })
}

onMounted(async () => {
  await loadChannels()
  if (selectedChannel.value) {
    await loadDaily()
    await loadMonthFlags()
  }
})

onUnmounted(() => {
  player.stop()
})
</script>

<style scoped>
.month-flags {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 4px;
  padding: 10px 12px;
  background: var(--bg-sunken);
  border-radius: 6px;
  margin-bottom: 12px;
}

.month-flags .muted {
  margin-right: 6px;
}

.day-btn {
  min-width: 34px;
  padding: 6px 0;
}

.play-actions {
  margin-top: 12px;
  display: flex;
  gap: 8px;
}

.star {
  cursor: pointer;
}
</style>
