<template>
  <div class="page live-page">
    <!-- 页头 -->
    <header class="page-header">
      <div class="page-title-group">
        <h2 class="page-title">实时预览</h2>
        <p class="page-subtitle">
          点击右侧通道快速上屏，或在画面格内下拉选择通道 · 当前
          <b class="text-brand">{{ playingCount }}</b> / {{ gridSize }} 路播放中
        </p>
      </div>
      <div class="page-actions">
        <el-radio-group v-model="gridSize" size="small">
          <el-radio-button :value="1">1</el-radio-button>
          <el-radio-button :value="4">4</el-radio-button>
          <el-radio-button :value="6">6</el-radio-button>
          <el-radio-button :value="9">9</el-radio-button>
          <el-radio-button :value="16">16</el-radio-button>
        </el-radio-group>
        <el-select v-model="proto" style="width: 158px" size="small" @change="onProtoChange">
          <el-option label="WebSocket-FLV" value="wsflv" />
          <el-option label="WebRTC（低延迟）" value="webrtc" />
        </el-select>
        <el-button size="small" :loading="loading" @click="loadChannels">
          <el-icon><Refresh /></el-icon> 刷新通道
        </el-button>
        <el-button size="small" type="danger" plain :disabled="!playingCount" @click="stopAll">
          <el-icon><SwitchButton /></el-icon> 全部停止
        </el-button>
      </div>
    </header>

    <!-- 主体：视频墙 + 通道列表 -->
    <div class="live-body">
      <!-- 视频墙 -->
      <div class="wall" :class="'wall-' + gridSize">
        <div v-for="tile in visibleTiles" :key="tile.id" class="tile">
          <div class="video-box">
            <!--
              jessibuca 会在容器内自建 canvas 渲染（支持 PCMA/PCMU 音频），
              所以这里不是 <video> 而是普通容器 div。
            -->
            <div :ref="(el) => setContainerRef(tile.id, el)" class="video-container" />

            <!-- 空闲态：提示如何上屏 -->
            <div v-if="!tile.playing" class="tile-overlay">
              <el-icon :size="30"><VideoCamera /></el-icon>
              <span class="overlay-text">{{ tile.state }}</span>
              <span v-if="tile.channelKey" class="overlay-hint">点击重试</span>
              <span v-else class="overlay-hint">从右侧选择通道</span>
            </div>

            <!-- 空闲态且已选通道时整块可点 -->
            <div
              v-if="!tile.playing && tile.channelKey"
              class="tile-click"
              @click="playTile(tile)"
            />

            <!-- 左上角：通道名 + 序号 -->
            <div v-if="tile.playing" class="tile-tag">
              <span class="tile-index">{{ tile.id }}</span>
              <span class="ellipsis">{{ tile.channelName || tile.code }}</span>
            </div>

            <!-- 右上角：状态灯 -->
            <div v-if="tile.playing" class="tile-state" :title="tile.state">
              <span class="pulse-dot" />
            </div>
          </div>

          <!-- 格内工具条 -->
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
              <el-button
                v-if="supportsPtz(tile.ptzType)"
                size="small"
                title="云台控制"
                @click="openPtz(tile)"
              >
                <el-icon><Aim /></el-icon>
              </el-button>
              <el-button type="danger" size="small" title="停止" @click="stopTile(tile)">
                <el-icon><VideoPause /></el-icon>
              </el-button>
            </template>
          </div>
        </div>
      </div>

      <!-- 通道列表 -->
      <aside class="channel-panel">
        <div class="panel-head">
          <span class="panel-title">通道列表</span>
          <span class="count-pill">{{ filteredChannels.length }}</span>
        </div>

        <div class="panel-search">
          <el-input v-model="filterText" placeholder="搜索名称或编码" clearable size="small">
            <template #prefix><el-icon><Search /></el-icon></template>
          </el-input>
        </div>

        <div v-if="!filteredChannels.length" class="panel-empty">
          <el-empty :description="channels.length ? '无匹配通道' : '暂无通道'" :image-size="64" />
        </div>

        <div v-else class="channel-list dark-scroll">
          <button
            v-for="c in filteredChannels"
            :key="c.deviceId + '/' + c.id"
            class="channel-item"
            :class="{ 'is-offline': !c.deviceOnline, 'is-used': usedKeys.has(c.deviceId + '/' + c.id) }"
            @click="pickChannel(c)"
          >
            <span class="ch-dot" :class="c.deviceOnline ? 'is-online' : 'is-offline'" />
            <span class="ch-main">
              <span class="ch-name ellipsis">{{ c.customName || c.name || c.id }}</span>
              <span class="ch-code mono ellipsis">{{ c.id }}</span>
            </span>
            <span v-if="usedKeys.has(c.deviceId + '/' + c.id)" class="ch-playing">
              <span class="pulse-dot" />
            </span>
            <span v-else-if="!c.deviceOnline" class="ch-offline">离线</span>
          </button>
        </div>
      </aside>
    </div>

    <!-- 云台控制抽屉 -->
    <el-drawer
      v-model="ptzDrawer"
      :title="'云台控制 · ' + (ptzTile?.channelName || ptzTile?.code || '')"
      size="380px"
      :destroy-on-close="false"
      append-to-body
      :lock-scroll="false"
    >
      <PtzPanel
        v-if="ptzTile"
        :key="ptzTile.id"
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
 * 导致取 DOM 元素时拿到的是解包后的值而非 ref，无法再赋值绑定。
 */
const players = {}
/** 每格的容器 DOM 引用（jessibuca 在容器内自建 canvas）。 */
const containerRefs = {}
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
    /** 通道的云台类型，用于决定格子工具条是否出现云台按钮。 */
    ptzType: 0,
    state: '未选择通道',
    playing: false
  }))
)

/**
 * 该类型是否支持云台控制。
 *
 * <p>0（未知）与 3（固定枪机）不支持：前者设备没报，后者物理上就不能转。
 * 与 {@code ChannelView.vue} 保持一致；类型可在通道管理列表里直接下拉修改。
 */
function supportsPtz(type) {
  return type === 1 || type === 2 || type === 4
}

const visibleTiles = computed(() => tiles.value.slice(0, gridSize.value))

/** 正在播放的格子数（用于页头提示与"全部停止"按钮的可用态）。 */
const playingCount = computed(() => tiles.value.filter((t) => t.playing).length)

/** 已被画面格占用的通道 key，通道列表用它标记"使用中"。 */
const usedKeys = computed(
  () => new Set(tiles.value.filter((t) => t.channelKey).map((t) => t.channelKey))
)

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

function setContainerRef(id, el) {
  if (el) containerRefs[id] = el
}

/** 获取（或创建）某格的播放器，并绑定当前 DOM 元素与状态监听。 */
function playerOf(tile) {
  if (!players[tile.id]) {
    // jessibuca 渲染到容器 div 内，传入容器 ref 供播放器创建 canvas
    const containerRef = ref(containerRefs[tile.id])
    const api = useVideoPlayer(containerRef)
    players[tile.id] = { api, tile, containerRef }
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
  // 格子可能被重建（如切换分屏数），同步最新 DOM
  players[tile.id].containerRef.value = containerRefs[tile.id]
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
    tile.ptzType = 0
    tile.state = '未选择通道'
  }
}

function applyChannel(tile, channel) {
  tile.serial = channel.deviceId
  tile.code = channel.id
  tile.channelName = channel.customName || channel.name || channel.id
  tile.ptzType = channel.ptzType || 0
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
/* ============ 布局 ============ */

.live-page {
  /* 视频墙要吃掉剩余高度，不随内容滚动 */
  height: 100%;
}

.live-body {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 264px;
  gap: var(--sp-4);
  flex: 1;
  min-height: 0;
}

/* ============ 视频墙 ============ */

.wall {
  display: grid;
  gap: var(--sp-3);
  min-width: 0;
  align-content: start;
}

.wall-1 { grid-template-columns: minmax(0, 1fr); }
.wall-4 { grid-template-columns: repeat(2, minmax(0, 1fr)); }
.wall-6 { grid-template-columns: repeat(3, minmax(0, 1fr)); }
.wall-9 { grid-template-columns: repeat(3, minmax(0, 1fr)); }
.wall-16 { grid-template-columns: repeat(4, minmax(0, 1fr)); }

.tile {
  display: flex;
  flex-direction: column;
  gap: 6px;
  min-width: 0;
}

/* ============ 画面格 ============ */

.tile .video-box {
  border-radius: var(--r-lg);
  box-shadow: var(--sh-sm);
  transition: box-shadow 0.18s ease;
}

.tile:hover .video-box {
  box-shadow: var(--sh-md);
}

/* 空闲遮罩 */
.tile-overlay {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 6px;
  color: rgba(255, 255, 255, 0.42);
  pointer-events: none;
  text-align: center;
  padding: var(--sp-3);
}

.overlay-text {
  font-size: 12.5px;
  color: rgba(255, 255, 255, 0.6);
}

.overlay-hint {
  font-size: 11.5px;
  color: rgba(255, 255, 255, 0.32);
}

/* 整块可点层（避免与遮罩的 pointer-events 冲突） */
.tile-click {
  position: absolute;
  inset: 0;
  cursor: pointer;
}

/* 左上角通道标签 */
.tile-tag {
  position: absolute;
  top: 8px;
  left: 8px;
  right: 34px;
  display: flex;
  align-items: center;
  gap: 6px;
  height: 22px;
  padding: 0 8px;
  border-radius: var(--r-sm);
  background: rgba(5, 7, 13, 0.66);
  backdrop-filter: blur(4px);
  color: rgba(255, 255, 255, 0.92);
  font-size: 11.5px;
  pointer-events: none;
}

.tile-index {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 15px;
  height: 15px;
  padding: 0 3px;
  border-radius: 3px;
  background: var(--brand-500);
  color: #fff;
  font-size: 10px;
  font-weight: 600;
  flex-shrink: 0;
}

/* 右上角状态灯 */
.tile-state {
  position: absolute;
  top: 10px;
  right: 10px;
  display: flex;
  align-items: center;
  pointer-events: none;
}

/* 格内工具条 */
.tile-bar {
  display: flex;
  align-items: center;
  gap: 5px;
}

.tile-select {
  flex: 1;
  min-width: 0;
}

/* ============ 通道列表面板 ============ */

.channel-panel {
  display: flex;
  flex-direction: column;
  min-height: 0;
  background: var(--bg-surface);
  border: 1px solid var(--border-color);
  border-radius: var(--r-lg);
  box-shadow: var(--sh-xs);
  overflow: hidden;
}

.panel-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: var(--sp-3) var(--sp-4);
  border-bottom: 1px solid var(--ink-100);
  flex-shrink: 0;
}

.panel-title {
  font-size: 13.5px;
  font-weight: 600;
  color: var(--text-primary);
}

.count-pill {
  display: inline-flex;
  align-items: center;
  height: 19px;
  padding: 0 7px;
  border-radius: var(--r-full);
  background: var(--brand-50);
  color: var(--brand-600);
  font-size: 11.5px;
  font-weight: 600;
}

.panel-search {
  padding: var(--sp-3) var(--sp-3) var(--sp-2);
  flex-shrink: 0;
}

.panel-empty {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
}

.channel-list {
  flex: 1;
  overflow-y: auto;
  padding: 0 var(--sp-2) var(--sp-2);
}

.channel-item {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 10px;
  margin-bottom: 2px;
  border: none;
  border-radius: var(--r-md);
  background: transparent;
  cursor: pointer;
  text-align: left;
  font: inherit;
  transition: background 0.13s ease;
}

.channel-item:hover {
  background: var(--ink-50);
}

.channel-item.is-used {
  background: var(--brand-50);
}

/* 离线通道整体降透明度 */
.channel-item.is-offline {
  opacity: 0.55;
}

.channel-item.is-offline:hover {
  opacity: 0.8;
}

.ch-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  flex-shrink: 0;
}

.ch-dot.is-online {
  background: var(--success-500);
}

.ch-dot.is-offline {
  background: var(--ink-300);
}

.ch-main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  line-height: 1.35;
}

.ch-name {
  font-size: 12.5px;
  color: var(--text-primary);
  font-weight: 500;
}

.ch-code {
  font-size: 11px;
  color: var(--text-tertiary);
}

.ch-playing {
  display: flex;
  align-items: center;
  flex-shrink: 0;
}

.ch-offline {
  font-size: 11px;
  color: var(--text-tertiary);
  flex-shrink: 0;
}

/* ============ 响应式 ============ */

@media (max-width: 1200px) {
  .live-body {
    grid-template-columns: minmax(0, 1fr) 232px;
  }
}

@media (max-width: 900px) {
  .live-body {
    grid-template-columns: minmax(0, 1fr);
  }
  .channel-panel {
    max-height: 300px;
  }
  .wall-16 {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .wall-6,
  .wall-9 {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
