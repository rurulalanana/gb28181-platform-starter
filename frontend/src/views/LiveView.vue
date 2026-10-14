<template>
  <div class="live-page">
    <!-- 工具栏 -->
    <el-card shadow="never" class="toolbar-card">
      <div class="toolbar">
        <div class="toolbar-left">
          <el-radio-group v-model="gridSize" size="default">
            <el-radio-button :value="1">单画面</el-radio-button>
            <el-radio-button :value="4">四分屏</el-radio-button>
            <el-radio-button :value="6">六分屏</el-radio-button>
            <el-radio-button :value="9">九分屏</el-radio-button>
          </el-radio-group>

          <el-select v-model="proto" style="width: 168px" @change="onProtoChange">
            <el-option label="WebSocket-FLV" value="wsflv" />
            <el-option label="WebRTC（低延迟）" value="webrtc" />
          </el-select>
        </div>

        <div class="toolbar-right">
          <el-input v-model="filterText" placeholder="过滤通道" clearable style="width: 200px">
            <template #prefix><el-icon><Search /></el-icon></template>
          </el-input>
          <el-button :loading="loading" @click="loadChannels">
            <el-icon><Refresh /></el-icon> 刷新
          </el-button>
          <el-button type="danger" plain @click="stopAll">
            <el-icon><SwitchButton /></el-icon> 全部停止
          </el-button>
        </div>
      </div>
    </el-card>

    <!-- 主体：画面网格 + 通道列表 -->
    <div class="live-body">
      <div class="grid-area" :class="'grid-' + gridSize">
        <div v-for="tile in visibleTiles" :key="tile.id" class="tile">
          <div class="video-box">
            <video :ref="(el) => setVideoRef(tile.id, el)" autoplay playsinline muted controls />
            <div
              v-if="!tile.playing"
              class="tile-overlay"
              :class="{ clickable: tile.channelKey }"
              @click="tile.channelKey && playTile(tile)"
            >
              <el-icon :size="34"><VideoCamera /></el-icon>
              <span class="overlay-text">{{ tile.state }}</span>
              <span v-if="tile.channelKey" class="overlay-hint">点击重试</span>
            </div>
            <div v-if="tile.playing" class="tile-badge">
              <span class="dot" />
              {{ tile.channelName || tile.code }}
            </div>
          </div>

          <div class="tile-bar">
            <el-select
              v-model="tile.channelKey"
              placeholder="选择通道"
              filterable
              clearable
              size="small"
              class="tile-select"
              @change="onChannelChange(tile)"
            >
              <el-option
                v-for="opt in channelOptions"
                :key="opt.value"
                :label="opt.label"
                :value="opt.value"
              />
            </el-select>

            <el-button
              v-if="!tile.playing"
              type="primary"
              size="small"
              :disabled="!tile.channelKey"
              @click="playTile(tile)"
            >
              <el-icon><VideoPlay /></el-icon>
            </el-button>
            <template v-else>
              <el-button size="small" title="云台控制" @click="openPtz(tile)">
                <el-icon><Aim /></el-icon>
              </el-button>
              <el-button type="danger" size="small" @click="stopTile(tile)">
                <el-icon><VideoPause /></el-icon>
              </el-button>
            </template>
          </div>
        </div>
      </div>

      <!-- 右侧通道列表 -->
      <el-card shadow="never" class="channel-panel">
        <template #header>
          <div class="panel-header">
            <span>通道列表</span>
            <el-tag size="small" type="info" effect="plain">{{ filteredChannels.length }}</el-tag>
          </div>
        </template>

        <el-empty v-if="!filteredChannels.length" description="暂无通道" :image-size="70" />

        <div v-else class="channel-list">
          <div
            v-for="c in filteredChannels"
            :key="c.deviceId + '/' + c.id"
            class="channel-item"
            :class="{ offline: !c.deviceOnline }"
            @click="pickChannel(c)"
          >
            <div class="channel-main">
              <div class="channel-name">{{ c.customName || c.name || c.id }}</div>
              <div class="channel-code mono">{{ c.id }}</div>
            </div>
            <el-tag :type="c.deviceOnline ? 'success' : 'info'" size="small" effect="light">
              {{ c.deviceOnline ? '在线' : '离线' }}
            </el-tag>
          </div>
        </div>
      </el-card>
    </div>

    <!-- 云台控制抽屉 -->
    <el-drawer
      v-model="ptzDrawer"
      :title="'云台控制 · ' + (ptzTile?.channelName || ptzTile?.code || '')"
      size="380px"
    >
      <PtzPanel
        v-if="ptzTile"
        :serial="ptzTile.serial"
        :code="ptzTile.code"
        :disabled="!ptzTile.playing"
      />
    </el-drawer>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { deviceApi, streamApi, systemApi } from '@/api'
import { resolvePlayUrls, useVideoPlayer } from '@/composables/useVideoPlayer'
import PtzPanel from '@/components/PtzPanel.vue'

const channels = ref([])
const loading = ref(false)
const gridSize = ref(4)
const filterText = ref('')
const proto = ref('wsflv')
const mediaHttpPort = ref(null)

/**
 * 每格的播放器实例。
 *
 * 用普通对象（非 reactive）：reactive 会自动解包嵌套 ref，
 * 导致 `api.videoEl` 拿到的是 DOM 元素而非 ref，无法再赋值绑定。
 */
const players = {}
/** 每格的视频元素引用（DOM 引用，无需响应式）。 */
const videoRefs = {}
/** 播放器状态监听停止句柄。 */
const stoppers = {}

const ptzDrawer = ref(false)
const ptzTile = ref(null)

/** 画面格子。 */
const tiles = ref(
  Array.from({ length: 9 }, (_, i) => ({
    id: i + 1,
    channelKey: '',
    serial: '',
    code: '',
    channelName: '',
    state: '未选择通道',
    playing: false
  }))
)

const visibleTiles = computed(() => tiles.value.slice(0, gridSize.value))

const filteredChannels = computed(() => {
  const needle = (filterText.value || '').trim().toLowerCase()
  if (!needle) return channels.value
  return channels.value.filter((c) => {
    const name = (c.customName || c.name || '').toLowerCase()
    return c.id.toLowerCase().includes(needle) || name.includes(needle)
  })
})

const channelOptions = computed(() =>
  filteredChannels.value.map((c) => ({
    label: `${c.customName || c.name || c.id}${c.deviceOnline ? '' : '（离线）'}`,
    value: `${c.deviceId}/${c.id}`
  }))
)

function setVideoRef(id, el) {
  if (el) videoRefs[id] = el
}

/** 获取（或创建）某格的播放器，并绑定当前 DOM 元素与状态监听。 */
function playerOf(tile) {
  if (!players[tile.id]) {
    const api = useVideoPlayer()
    players[tile.id] = { api, tile }
    // 播放器状态同步到格子（替代轮询）
    stoppers[tile.id] = watch(
      [api.state, api.playing],
      ([s, p]) => {
        tile.state = s
        tile.playing = p
      },
      { immediate: true }
    )
  }
  players[tile.id].api.videoEl.value = videoRefs[tile.id]
  return players[tile.id].api
}

async function loadChannels() {
  loading.value = true
  try {
    const data = await deviceApi.channelList({ start: 0, limit: 1000 })
    channels.value = data?.channelList || []
    if (!mediaHttpPort.value) {
      try {
        const info = await systemApi.serverinfo()
        mediaHttpPort.value = info?.mediaHttp
      } catch (e) {
        // 端口获取失败不影响 ws-flv（后端已返回完整地址）
      }
    }
  } catch (e) {
    ElMessage.error(e.message || '加载通道失败')
  } finally {
    loading.value = false
  }
}

/** 点击右侧通道：填充第一个空闲格子并播放。 */
function pickChannel(channel) {
  const target = visibleTiles.value.find((t) => !t.playing && !t.channelKey) || visibleTiles.value[0]
  if (!target) return
  if (target.playing) stopTile(target)
  target.channelKey = `${channel.deviceId}/${channel.id}`
  applyChannel(target, channel)
  playTile(target)
}

function onChannelChange(tile) {
  if (tile.playing) stopTile(tile)
  const channel = channels.value.find((c) => `${c.deviceId}/${c.id}` === tile.channelKey)
  if (channel) {
    applyChannel(tile, channel)
  } else {
    tile.serial = ''
    tile.code = ''
    tile.channelName = ''
    tile.state = '未选择通道'
  }
}

function applyChannel(tile, channel) {
  tile.serial = channel.deviceId
  tile.code = channel.id
  tile.channelName = channel.customName || channel.name || channel.id
}

/** 点播 + 播放。 */
async function playTile(tile) {
  if (!tile.channelKey) {
    ElMessage.warning('请先选择通道')
    return
  }
  const player = playerOf(tile)
  tile.state = '点播中…'

  const streamId = `${tile.serial}/${tile.code}`
  let info = null
  try {
    // 点播响应已包含全部播放地址
    info = await streamApi.start({ serial: tile.serial, code: tile.code })
  } catch (e) {
    // 已在推流：回查流信息拿地址
    try {
      info = await streamApi.info(streamId, true)
    } catch (e2) {
      tile.state = '点播失败'
      ElMessage.error(e.message || '点播失败')
      return
    }
  }

  const urls = resolvePlayUrls(info, mediaHttpPort.value)
  if (!urls.wsFlv && !urls.webrtc) {
    tile.state = '未获取到播放地址'
    ElMessage.error('未获取到播放地址，请确认设备在线')
    return
  }

  await player.play(streamId, urls, { proto: proto.value })
}

function stopTile(tile) {
  const entry = players[tile.id]
  if (entry) {
    entry.api.stop()
    if (stoppers[tile.id]) {
      stoppers[tile.id]()
      delete stoppers[tile.id]
    }
    delete players[tile.id]
  }
  tile.playing = false
  tile.state = '已停止'
}

function stopAll() {
  for (const tile of tiles.value) {
    if (tile.playing || tile.channelKey) stopTile(tile)
  }
  ElMessage.success('已停止全部画面')
}

/** 切换协议：所有播放中的格子重连。 */
async function onProtoChange() {
  const active = visibleTiles.value.filter((t) => t.playing && t.channelKey)
  if (!active.length) return
  ElMessage.info(`正在切换为 ${proto.value === 'wsflv' ? 'WebSocket-FLV' : 'WebRTC'}…`)
  for (const tile of active) {
    const streamId = `${tile.serial}/${tile.code}`
    const player = playerOf(tile)
    let urls = {}
    try {
      const info = await streamApi.info(streamId, true)
      urls = resolvePlayUrls(info, mediaHttpPort.value)
    } catch (e) {
      // 忽略
    }
    await player.switchProtocol(proto.value, urls)
    tile.state = player.state.value
    tile.playing = player.playing.value
  }
}

function openPtz(tile) {
  ptzTile.value = tile
  ptzDrawer.value = true
}

onMounted(loadChannels)

onUnmounted(() => {
  for (const id of Object.keys(players)) {
    players[id].api.stop()
  }
  for (const id of Object.keys(stoppers)) {
    stoppers[id]()
  }
})
</script>

<style scoped>
.live-page {
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.toolbar-card :deep(.el-card__body) {
  padding: 14px 18px;
}

.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
}

.toolbar-left,
.toolbar-right {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.live-body {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 300px;
  gap: 18px;
  align-items: start;
}

@media (max-width: 1200px) {
  .live-body {
    grid-template-columns: minmax(0, 1fr);
  }
}

.grid-area {
  display: grid;
  gap: 16px;
}

.grid-1 {
  grid-template-columns: 1fr;
}

.grid-4 {
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

.grid-6,
.grid-9 {
  grid-template-columns: repeat(3, minmax(0, 1fr));
}

.tile {
  background: #fff;
  border: 1px solid #ebeef5;
  border-radius: 8px;
  padding: 12px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.tile .video-box {
  position: relative;
  background: #000;
  border-radius: 6px;
  overflow: hidden;
  aspect-ratio: 16 / 9;
}

.tile video {
  width: 100%;
  height: 100%;
  display: block;
  object-fit: contain;
}

.tile-overlay {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 10px;
  color: #a8abb2;
  background: rgba(0, 0, 0, 0.72);
  pointer-events: none;
}

.tile-overlay.clickable {
  pointer-events: auto;
  cursor: pointer;
}

.tile-overlay.clickable:hover {
  background: rgba(0, 0, 0, 0.62);
}

.tile-overlay .overlay-text {
  font-size: 13px;
}

.tile-overlay .overlay-hint {
  font-size: 12px;
  color: #409eff;
}

.tile-badge {
  position: absolute;
  left: 10px;
  top: 10px;
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 3px 10px;
  background: rgba(0, 0, 0, 0.6);
  color: #fff;
  font-size: 12px;
  border-radius: 4px;
  pointer-events: none;
}

.tile-badge .dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #67c23a;
  animation: blink 1.6s infinite;
}

@keyframes blink {
  50% {
    opacity: 0.35;
  }
}

.tile-bar {
  display: flex;
  align-items: center;
  gap: 8px;
}

.tile-select {
  flex: 1;
  min-width: 0;
}

.channel-panel {
  position: sticky;
  top: 0;
}

.channel-panel :deep(.el-card__body) {
  padding: 10px;
  max-height: calc(100vh - 250px);
  overflow-y: auto;
}

.panel-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-weight: 600;
}

.channel-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.channel-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 12px;
  border-radius: 6px;
  cursor: pointer;
  transition: background 0.15s;
}

.channel-item:hover {
  background: #ecf5ff;
}

.channel-item.offline {
  opacity: 0.6;
}

.channel-main {
  flex: 1;
  min-width: 0;
}

.channel-name {
  font-size: 13px;
  color: #303133;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.channel-code {
  font-size: 11px;
  color: #909399;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
