<template>
  <div class="page">
    <el-card shadow="never">
      <template #header>
        <div class="card-header">
          <span><el-icon><Film /></el-icon> 设备录像</span>
        </div>
      </template>

      <el-form :inline="true" class="search-form">
        <el-form-item label="通道">
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
        </el-form-item>
        <el-form-item label="时间范围">
          <el-date-picker
            v-model="range"
            type="datetimerange"
            range-separator="至"
            start-placeholder="开始时间"
            end-placeholder="结束时间"
            style="width: 380px"
            value-format="YYYY-MM-DD HH:mm:ss"
          />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="searching" @click="search">
            <el-icon><Search /></el-icon> 检索
          </el-button>
        </el-form-item>
      </el-form>

      <el-table v-loading="searching" :data="records" stripe>
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
        <el-table-column label="操作" width="120" align="center" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="play(row)">
              <el-icon><VideoPlay /></el-icon> 回放
            </el-button>
          </template>
        </el-table-column>
        <template #empty>
          <div class="empty-cell">{{ searched ? '该时间段内无录像' : '请选择通道与时间范围后检索' }}</div>
        </template>
      </el-table>

      <div class="table-footer">共 {{ records.length }} 条录像</div>
    </el-card>

    <!-- 回放对话框 -->
    <el-dialog
      v-model="playDialog"
      :title="'录像回放 · ' + shortName(current?.filePath)"
      width="900px"
      top="5vh"
      @close="closePlay"
    >
      <div class="video-box">
        <div ref="videoContainer" class="video-container" />
      </div>

      <div class="play-bar">
        <el-tag :type="stateType" size="small">{{ state }}</el-tag>
        <div class="spacer" />
        <el-button-group>
          <el-button size="small" :disabled="!playing" @click="control('pause')">
            <el-icon><VideoPause /></el-icon> 暂停
          </el-button>
          <el-button size="small" :disabled="!playing" @click="control('play', { range: 'now' })">
            <el-icon><VideoPlay /></el-icon> 继续
          </el-button>
        </el-button-group>
        <el-select v-model="speed" size="small" style="width: 110px" :disabled="!playing" @change="onSpeedChange">
          <el-option label="1 倍速" :value="1" />
          <el-option label="2 倍速" :value="2" />
          <el-option label="4 倍速" :value="4" />
          <el-option label="8 倍速" :value="8" />
        </el-select>
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
import { deviceApi, playbackApi, systemApi } from '@/api'
import { resolvePlayUrls, useVideoPlayer } from '@/composables/useVideoPlayer'
import { usePlayerStateType } from '@/composables/usePlayerState'

const channels = ref([])
const records = ref([])
const searching = ref(false)
const searched = ref(false)
const range = ref(null)
const query = reactive({ channelKey: '' })

const playDialog = ref(false)
const current = ref(null)
const proto = ref('wsflv')
const speed = ref(1)
const mediaHttpPort = ref(null)

/** 播放器容器（jessibuca 在内部创建 canvas，WebRTC 通路创建 video）。 */
const videoContainer = ref(null)
const player = useVideoPlayer(videoContainer)
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

async function ensureMediaPort() {
  if (mediaHttpPort.value) return
  try {
    const info = await systemApi.serverinfo()
    mediaHttpPort.value = info?.mediaHttp
  } catch (e) {
    // 忽略
  }
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
  await player.play(streamId, urls, { proto: proto.value })
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

/** 回放控制：暂停 / 继续 / 倍速。 */
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

function onSpeedChange(val) {
  control('speed', { scale: val })
}

function closePlay() {
  player.stop()
  if (current.value) {
    playbackApi.stop({ serial: serial.value, code: code.value }).catch(() => {})
  }
  current.value = null
}

function shortName(path) {
  if (!path) return '—'
  const parts = path.split('/')
  return parts[parts.length - 1]
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

onMounted(loadChannels)
onUnmounted(() => player.stop())
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
</style>
