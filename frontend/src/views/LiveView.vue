<template>
  <div class="page live-page is-fill">
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
        <el-checkbox v-model="showName" size="small" class="tool-check">显示名称</el-checkbox>
        <el-button
          size="small"
          class="rotate-btn"
          :class="{ 'is-on': rotating }"
          title="选择轮巡播放通道"
          @click="rotating ? stopRotate() : openRotateDialog()"
        >
          <el-icon><RefreshRight /></el-icon> {{ rotating ? '停止轮播' : '轮播' }}
        </el-button>
        <el-button size="small" title="全屏显示视频墙" @click="toggleWallFullscreen">
          <el-icon><FullScreen /></el-icon> 全屏
        </el-button>
        <el-button size="small" :loading="loading" @click="loadChannels">
          <el-icon><Refresh /></el-icon> 刷新通道
        </el-button>
        <el-button size="small" class="stop-all" :disabled="!playingCount" @click="stopAll">
          <el-icon><SwitchButton /></el-icon> 全部停止
        </el-button>
      </div>
    </header>

    <!-- 主体：视频墙 + 通道列表 -->
    <div class="live-body">
      <!-- 视频墙 -->
      <div ref="wallRef" class="wall" :class="'wall-' + gridSize">
        <div
          v-for="tile in visibleTiles"
          :key="tile.id"
          class="tile"
          :class="{ 'is-active': tile.id === activeTileId }"
        >
          <!--
            注意：不要用 @dblclick —— 播放器（jessibuca）在 canvas 上吞掉了 dblclick，
            冒泡与捕获都收不到；这里用 click 自己判定"双击"（间隔 320ms 内点同一格）。
          -->
          <div class="video-box" @click="onTileClick(tile)">
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

            <!-- 左上角：通道名 + 序号（可用工具栏「显示名称」关掉） -->
            <div v-if="tile.playing && showName" class="tile-tag">
              <span class="tile-index">{{ tile.id }}</span>
              <span class="ellipsis">{{ tile.channelName || tile.code }}</span>
            </div>

            <!-- 右上角：状态灯 -->
            <div v-if="tile.playing" class="tile-state" :title="tile.state">
              <span class="pulse-dot" />
            </div>

            <!-- 悬浮工具条：全屏 / 抓图 / 停止（双击画面格同样可全屏） -->
            <div v-if="tile.playing" class="tile-tools">
              <button class="tt-btn" title="全屏（双击画面亦可）" @click.stop="toggleFullscreen(tile)">
                <el-icon><FullScreen /></el-icon>
              </button>
              <button class="tt-btn" title="抓图并下载" @click.stop="snapshot(tile)">
                <el-icon><Camera /></el-icon>
              </button>
              <button class="tt-btn" title="停止播放" @click.stop="stopTile(tile)">
                <el-icon><Close /></el-icon>
              </button>
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
              class="tile-btn tile-btn-play"
              :disabled="!tile.channelKey"
              @click="playTile(tile)"
            >
              <el-icon><VideoPlay /></el-icon>
            </el-button>
            <template v-else>
              <el-button
                v-if="supportsPtz(tile.ptzType)"
                size="small"
                class="tile-btn"
                title="云台控制"
                @click="openPtz(tile)"
              >
                <el-icon><Aim /></el-icon>
              </el-button>
              <el-button
                type="danger"
                size="small"
                class="tile-btn tile-btn-stop"
                title="停止"
                @click="stopTile(tile)"
              >
                <el-icon><VideoPause /></el-icon>
              </el-button>
            </template>
          </div>
        </div>
      </div>

      <!-- 通道列表 -->
      <aside class="channel-panel">
        <div class="panel-head">
          <span class="panel-title">{{ panelTab === 'ptz' ? '云台控制' : '通道列表' }}</span>
          <span class="count-pill">{{ filteredChannels.length }}</span>
        </div>

        <!-- 视图切换：平铺列表 / 按设备分组 / 云台 -->
        <div class="panel-tabs">
          <button class="ptab" :class="{ 'is-on': panelTab === 'list' }" @click="panelTab = 'list'">
            通道列表
          </button>
          <button class="ptab" :class="{ 'is-on': panelTab === 'group' }" @click="panelTab = 'group'">
            按设备分组
          </button>
          <button class="ptab" :class="{ 'is-on': panelTab === 'ptz' }" @click="panelTab = 'ptz'">
            云台
          </button>
        </div>

        <div v-if="panelTab !== 'ptz'" class="panel-search">
          <el-input v-model="filterText" placeholder="搜索名称或编码" clearable size="small">
            <template #prefix><el-icon><Search /></el-icon></template>
          </el-input>
        </div>

        <!-- 云台面板（常驻，对齐 LiveGBS：绑定当前选中画面格）） -->
        <div v-if="panelTab === 'ptz'" class="ptz-tab dark-scroll">
          <template v-if="activeTile && activeTile.channelKey">
            <div class="ptz-target">
              <span class="ch-dot" :class="activeTile.playing ? 'is-online' : 'is-offline'" />
              <span class="ellipsis">{{ activeTile.channelName || activeTile.code }}</span>
              <span class="ptz-target-cell">画面 {{ activeTile.id }}</span>
            </div>
            <PtzPanel
              :key="activeTile.id"
              :serial="activeTile.serial"
              :code="activeTile.code"
              :disabled="!activeTile.playing"
            />
          </template>
          <div v-else class="panel-empty">
            <el-empty description="先上屏一个通道，再使用云台控制" :image-size="64" />
          </div>
        </div>

        <div v-else-if="!filteredChannels.length" class="panel-empty">
          <el-empty :description="channels.length ? '无匹配通道' : '暂无通道'" :image-size="64" />
        </div>

        <!-- 平铺列表 -->
        <div v-else-if="panelTab === 'list'" class="channel-list dark-scroll">
          <button
            v-for="c in filteredChannels"
            :key="c.deviceId + '/' + c.id"
            class="channel-item"
            :class="{ 'is-offline': !c.deviceOnline, 'is-used': usedKeys.has(c.deviceId + '/' + c.id) }"
            title="单击上屏到空位，双击上屏到选中格"
            @click="pickChannel(c)"
            @dblclick="playOnActiveTile(c)"
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

        <!-- 按设备分组（同一台设备下的通道折叠在一起） -->
        <div v-else class="channel-list dark-scroll">
          <div v-for="g in channelGroups" :key="g.deviceId" class="ch-group">
            <button class="ch-group-head" @click="toggleGroup(g.deviceId)">
              <el-icon :size="11">
                <ArrowDown v-if="expandedDevices.has(g.deviceId)" />
                <ArrowRight v-else />
              </el-icon>
              <span class="ch-dot" :class="g.online ? 'is-online' : 'is-offline'" />
              <span class="ch-group-main">
                <span class="ch-group-name ellipsis">{{ g.deviceName }}</span>
                <span class="ch-group-code mono ellipsis">{{ g.deviceId }}</span>
              </span>
              <span class="ch-group-count">{{ g.channels.length }}</span>
            </button>
            <div v-show="expandedDevices.has(g.deviceId)" class="ch-group-body">
              <button
                v-for="c in g.channels"
                :key="c.id"
                class="channel-item"
                :class="{ 'is-offline': !c.deviceOnline, 'is-used': usedKeys.has(c.deviceId + '/' + c.id) }"
                title="单击上屏到空位，双击上屏到选中格"
                @click="pickChannel(c)"
                @dblclick="playOnActiveTile(c)"
              >
                <span class="ch-dot" :class="c.deviceOnline ? 'is-online' : 'is-offline'" />
                <span class="ch-main">
                  <span class="ch-name ellipsis">{{ c.customName || c.name || c.id }}</span>
                  <span class="ch-code mono ellipsis">{{ c.id }}</span>
                </span>
                <span v-if="usedKeys.has(c.deviceId + '/' + c.id)" class="ch-playing">
                  <span class="pulse-dot" />
                </span>
              </button>
            </div>
          </div>
        </div>
      </aside>
    </div>

    <!-- 轮播设置：选择轮巡通道 + 间隔（参考站的「轮播」是弹窗多选，不是直接开跑） -->
    <el-dialog v-model="rotateDialog" title="选择轮巡播放通道" width="520px" append-to-body>
      <div class="rotate-tip">
        勾选参与轮巡的在线通道，按当前分屏格数分批轮换；不勾选则轮巡全部在线通道。
      </div>
      <div class="rotate-list dark-scroll">
        <el-checkbox-group v-model="rotatePicked">
          <el-checkbox
            v-for="c in onlineChannels"
            :key="c.deviceId + '/' + c.id"
            :value="c.deviceId + '/' + c.id"
          >
            {{ c.customName || c.name || c.id }}
            <span class="mono text-dim">{{ c.id }}</span>
          </el-checkbox>
        </el-checkbox-group>
        <div v-if="!onlineChannels.length" class="rotate-empty">当前没有在线通道</div>
      </div>
      <div class="rotate-sec">
        轮巡间隔
        <el-input-number v-model="rotateSeconds" :min="5" :max="300" :step="5" size="small" />
        秒
      </div>
      <template #footer>
        <el-button @click="rotateDialog = false">取消</el-button>
        <el-button type="primary" :disabled="!onlineChannels.length" @click="confirmRotate">
          开始轮巡
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>


<script setup>
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { useRoute, useRouter } from 'vue-router'
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

/** 当前选中的画面格（加 2px 强调描边，方便看清操作的是哪一格）。 */
const activeTileId = ref(1)

/** 当前选中的画面格（云台面板绑定它的通道）。 */
const activeTile = computed(
  () =>
    visibleTiles.value.find((t) => t.id === activeTileId.value) ||
    visibleTiles.value[0] ||
    null
)
/** 视频墙容器（整体全屏用）。 */
const wallRef = ref(null)
/** 是否在画面上显示通道名（工具栏可关）。 */
const showName = ref(true)
/** 通道面板视图：list=平铺列表，group=按设备分组。 */
const panelTab = ref('list')
/** 已展开的设备（分组视图）。 */
const expandedDevices = ref(new Set())

/** 轮播状态（间隔由弹窗里的 rotateSeconds 决定）。 */
const rotating = ref(false)
let rotateTimer = null
let rotateIndex = 0

/** 真正参与轮巡的通道：勾选过就只轮这些，否则轮全部在线通道。 */
const rotatePool = computed(() => {
  const all = onlineChannels.value
  if (!rotateKeys.value.length) return all
  const set = new Set(rotateKeys.value)
  return all.filter((c) => set.has(`${c.deviceId}/${c.id}`))
})

/** 轮播设置弹窗（先选通道再开跑）。 */
const rotateDialog = ref(false)
const rotatePicked = ref([])
/** 已确认的轮巡集合（空 = 全部在线通道）。 */
const rotateKeys = ref([])
const rotateSeconds = ref(15)
/** 画面格子（最多 16 格，对应 16 分屏）。 */
const tiles = ref(
  Array.from({ length: 16 }, (_, i) => ({
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

/** 在线通道（轮播数据源）。 */
const onlineChannels = computed(() => channels.value.filter((c) => c.deviceOnline))

/** 分组视图：按设备归拢通道，设备在线状态取该设备下任一通道。 */
const channelGroups = computed(() => {
  const map = new Map()
  for (const c of filteredChannels.value) {
    if (!map.has(c.deviceId)) {
      map.set(c.deviceId, {
        deviceId: c.deviceId,
        deviceName: c.deviceName || c.deviceCustomName || c.deviceId,
        online: false,
        channels: []
      })
    }
    const g = map.get(c.deviceId)
    g.channels.push(c)
    if (c.deviceOnline) g.online = true
  }
  return [...map.values()]
})

function toggleGroup(deviceId) {
  const set = expandedDevices.value
  if (set.has(deviceId)) set.delete(deviceId)
  else set.add(deviceId)
}

/** 轮播一次：把「格数」个轮巡通道按顺序铺到当前可见的格子上。 */
function rotateOnce() {
  const list = rotatePool.value
  const cells = visibleTiles.value
  if (!list.length || !cells.length) return
  cells.forEach((tile, i) => {
    const ch = list[(rotateIndex + i) % list.length]
    if (tile.playing) stopTile(tile)
    tile.channelKey = `${ch.deviceId}/${ch.id}`
    applyChannel(tile, ch)
    playTile(tile)
  })
  rotateIndex = (rotateIndex + cells.length) % list.length
}

function openRotateDialog() {
  // 默认全选在线通道，方便直接开跑
  rotatePicked.value = rotateKeys.value.length
    ? [...rotateKeys.value]
    : onlineChannels.value.map((c) => `${c.deviceId}/${c.id}`)
  rotateDialog.value = true
}

function confirmRotate() {
  rotateKeys.value = [...rotatePicked.value]
  rotateDialog.value = false
  rotateIndex = 0
  startRotate()
}

function startRotate() {
  if (rotatePool.value.length < 2) {
    ElMessage.warning('参与轮巡的通道不足 2 路，无法轮播')
    return
  }
  rotating.value = true
  rotateOnce()
  rotateTimer = setInterval(rotateOnce, Math.max(5, rotateSeconds.value) * 1000)
}

function stopRotate() {
  rotating.value = false
  if (rotateTimer) {
    clearInterval(rotateTimer)
    rotateTimer = null
  }
}

/** 双击通道：直接上屏到当前选中的画面格。 */
function playOnActiveTile(channel) {
  const tile =
    visibleTiles.value.find((t) => t.id === activeTileId.value) || visibleTiles.value[0]
  if (!tile) return
  if (tile.playing) stopTile(tile)
  tile.channelKey = `${channel.deviceId}/${channel.id}`
  applyChannel(tile, channel)
  playTile(tile)
}

/** 画面格点击：单选该格；320ms 内再次点击同一格视为双击 → 切换全屏。 */
const tileClick = { id: 0, ts: 0 }

function onTileClick(tile) {
  activeTileId.value = tile.id
  const now = Date.now()
  if (tileClick.id === tile.id && now - tileClick.ts < 320) {
    tileClick.ts = 0
    toggleFullscreen(tile)
    return
  }
  tileClick.id = tile.id
  tileClick.ts = now
}

/** 双击画面格 / 工具条按钮：进入或退出全屏。 */
function toggleFullscreen(tile) {
  const host = containerRefs[tile.id]
  const box = host?.closest?.('.video-box')
  if (document.fullscreenElement) {
    document.exitFullscreen?.()
    return
  }
  if (box?.requestFullscreen) {
    box.requestFullscreen().catch(() => ElMessage.warning('当前浏览器不允许全屏'))
  }
}

/** 视频墙整体全屏（工具栏「全屏」按钮）。 */
function toggleWallFullscreen() {
  if (document.fullscreenElement) {
    document.exitFullscreen?.()
    return
  }
  const el = wallRef.value
  if (el?.requestFullscreen) {
    el.requestFullscreen().catch(() => ElMessage.warning('当前浏览器不允许全屏'))
  }
}

/** 抓图：jessibuca 渲染在 canvas 上，WebRTC 走 video，两条路都支持。 */
function snapshot(tile) {
  const host = containerRefs[tile.id]
  let url = null
  const canvas = host?.querySelector('canvas')
  if (canvas && canvas.width) {
    url = canvas.toDataURL('image/png')
  } else {
    const video = host?.querySelector('video')
    if (video && video.videoWidth) {
      const c = document.createElement('canvas')
      c.width = video.videoWidth
      c.height = video.videoHeight
      c.getContext('2d').drawImage(video, 0, 0, c.width, c.height)
      url = c.toDataURL('image/png')
    }
  }
  if (!url) {
    ElMessage.warning('当前画面还取不到图像，稍后再试')
    return
  }
  const name = tile.channelName || tile.code || tile.id
  const a = document.createElement('a')
  a.href = url
  a.download = `${name}-${Date.now()}.png`
  a.click()
  ElMessage.success('已保存抓图')
}

/**
 * 地址栏带 ?play=<设备编码>/<通道编码>（可逗号分隔多个）时自动上屏，
 * 供设备/通道列表的「上屏预览」直接跳过来播放。
 */
async function applyQueryPlay() {
  const raw = String(route.query.play || '').trim()
  if (!raw) return
  const keys = raw.split(',').map((x) => x.trim()).filter(Boolean)
  const cells = visibleTiles.value
  keys.slice(0, cells.length).forEach((key, i) => {
    const ch = channels.value.find((c) => `${c.deviceId}/${c.id}` === key)
    const tile = cells[i]
    if (!ch || !tile) return
    if (tile.playing) stopTile(tile)
    tile.channelKey = key
    applyChannel(tile, ch)
    playTile(tile)
  })
  // 清掉 query，避免刷新页面重复上屏
  router.replace({ path: '/live' })
}

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
  /* 不再弹抽屉：切到右侧「云台」常驻面板并选中该格（对齐 LiveGBS 布局）。 */
  activeTileId.value = tile.id
  panelTab.value = 'ptz'
}

const route = useRoute()
const router = useRouter()

onMounted(async () => {
  await loadChannels()
  await applyQueryPlay()
})

onUnmounted(() => {
  stopRotate()
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
  /* 视频墙要吃掉内容区的剩余高度，不随内容滚动。
     注意：.page 是 flex 列容器但没有高度，需要从 .content（flex:1）
     一路继承下来，这里用 min-height:0 + flex 允许子项收缩。 */
  height: 100%;
  min-height: 0;
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
  min-height: 0;
  /* 格子按可用高度均分，避免底部留白 */
  align-content: stretch;
  grid-auto-rows: minmax(0, 1fr);
}

/* 单画面：宽度铺满且限制最大宽度，避免超宽屏上画面变形过大 */
.wall-1 {
  grid-template-columns: minmax(0, 1fr);
  max-width: 1100px;
  margin: 0 auto;
  width: 100%;
}
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

/* 画面格：靠 1px 内描边区分边界，不用投影（投影会让视频墙显得"漂浮"） */
.tile .video-box {
  border-radius: var(--r-md);
}

.tile:hover .video-box {
  box-shadow: inset 0 0 0 1px rgba(255, 255, 255, 0.14);
}

/* 空闲遮罩 */
.tile-overlay {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 4px;
  color: rgba(255, 255, 255, 0.22);
  pointer-events: none;
  text-align: center;
  padding: var(--sp-3);
}

.tile-overlay .el-icon {
  font-size: 24px;
  margin-bottom: 2px;
}

.overlay-text {
  font-size: 12px;
  color: rgba(255, 255, 255, 0.5);
}

.overlay-hint {
  font-size: 11px;
  color: rgba(255, 255, 255, 0.28);
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
  min-width: 14px;
  height: 14px;
  padding: 0 3px;
  border-radius: 2px;
  background: rgba(255, 255, 255, 0.14);
  color: rgba(255, 255, 255, 0.9);
  font-family: var(--mono);
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

/* 格内工具条：与画面同宽的一条窄带，按钮压到 26px 与输入框等高 */
.tile-bar {
  display: flex;
  align-items: center;
  gap: 5px;
  height: var(--control-h);
}

.tile-select {
  flex: 1;
  min-width: 0;
}

.tile-btn {
  width: 30px;
  padding: 0;
  flex-shrink: 0;
}

/* 播放：实色主按钮；停止：只在图标上着红，不做整块红色按钮 */
.tile-btn-stop {
  background: var(--white);
  border-color: var(--line);
  color: var(--danger-500);
}

.tile-btn-stop:hover {
  background: var(--danger-50);
  border-color: var(--danger-500);
  color: var(--danger-500);
}

/* 播放按钮：默认描边幽灵态——视频墙上不该有 4 个实心蓝块抢视线 */
.tile-btn-play {
  background: var(--white);
  border-color: var(--line);
  color: var(--accent);
}

.tile-btn-play:hover:not(.is-disabled) {
  background: var(--brand-50);
  border-color: var(--brand-300);
  color: var(--accent-ink);
}

.tile-btn-play.is-disabled {
  background: var(--white);
  border-color: var(--line);
  color: var(--ink-300);
}

/* 格内下拉：不聚焦/不悬停时无边框（视频墙不是表单），避免每格都像输入框 */
.tile-select :deep(.el-select__wrapper) {
  background: transparent;
  box-shadow: 0 0 0 1px transparent inset;
}

.tile-select :deep(.el-select__wrapper:hover) {
  background: var(--white);
  box-shadow: 0 0 0 1px var(--line) inset;
}

.tile-select :deep(.el-select__wrapper.is-focused) {
  background: var(--white);
}

/* 顶部"全部停止"：默认态朴素，hover 才提示危险 */
.stop-all {
  color: var(--danger-500);
}

.stop-all:hover:not(.is-disabled) {
  border-color: var(--danger-500);
  color: var(--danger-500);
  background: var(--danger-50);
}

/* 分屏切换（1/4/6/9/16）压到与其它控件同高，避免顶栏高低不齐 */
.page-actions :deep(.el-radio-button__inner) {
  height: 26px;
  line-height: 24px;
  min-width: 32px;
  padding: 0 8px;
  font-size: 12px;
  font-family: var(--mono);
  font-weight: 600;
  border-color: var(--line);
}

.page-actions :deep(.el-radio-button:first-child .el-radio-button__inner) {
  border-radius: var(--r-md) 0 0 var(--r-md);
}

.page-actions :deep(.el-radio-button:last-child .el-radio-button__inner) {
  border-radius: 0 var(--r-md) var(--r-md) 0;
}

/* ============ 通道列表面板 ============ */

.channel-panel {
  display: flex;
  flex-direction: column;
  min-height: 0;
  background: var(--bg-surface);
  border: 1px solid var(--line);
  border-radius: var(--r-lg);
  overflow: hidden;
}

/* 云台常驻面板：目标通道条 + PtzPanel */
.ptz-tab {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 4px 2px 8px;
}
.ptz-target {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 7px 10px;
  margin-bottom: 8px;
  border: 1px solid var(--line-soft, #ebeef5);
  border-radius: 8px;
  font-size: 12.5px;
  background: var(--bg-sunken, #f8fafc);
}
.ptz-target .ellipsis {
  flex: 1;
  min-width: 0;
}
.ptz-target-cell {
  flex-shrink: 0;
  color: var(--ink-400, #909399);
  font-size: 11.5px;
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
  position: relative;
  width: 100%;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 5px 8px;
  margin-bottom: 1px;
  border: none;
  border-radius: var(--r-sm);
  background: transparent;
  cursor: pointer;
  text-align: left;
  font: inherit;
  transition: background 0.13s ease;
}

.channel-item:hover {
  background: var(--ink-50);
}

/* 已上屏：浅底 + 左侧强调条（和侧栏选中态同一套语言） */
.channel-item.is-used {
  background: var(--brand-50);
}

.channel-item.is-used::before {
  content: '';
  position: absolute;
  left: 0;
  top: 4px;
  bottom: 4px;
  width: 2px;
  border-radius: 0 2px 2px 0;
  background: var(--accent);
}

/* 离线通道降透明度，但仍可读（运维需要看得到离线设备） */
.channel-item.is-offline {
  opacity: 0.62;
}

.channel-item.is-offline:hover {
  opacity: 0.85;
}

.ch-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  flex-shrink: 0;
}

.ch-dot.is-online {
  background: var(--online-500);
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
  font-size: 10.5px;
  color: var(--text-tertiary);
  letter-spacing: -0.2px;
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

/* 画面格悬浮工具条：默认透明，鼠标移入才出现（不干扰看画面） */
.tile-tools {
  position: absolute;
  top: 8px;
  right: 8px;
  display: flex;
  align-items: center;
  gap: 4px;
  opacity: 0;
  transition: opacity 0.15s ease;
}

.tile:hover .tile-tools,
.tile.is-active .tile-tools {
  opacity: 1;
}

.tt-btn {
  width: 24px;
  height: 24px;
  display: flex;
  align-items: center;
  justify-content: center;
  border: none;
  border-radius: var(--r-sm);
  background: rgba(8, 12, 18, 0.62);
  color: rgba(255, 255, 255, 0.9);
  font-size: 13px;
  cursor: pointer;
}

.tt-btn:hover {
  background: var(--accent);
  color: #fff;
}

/* 画面格右上角状态灯与工具条不要叠在一起 */
.tile-state {
  right: 10px;
}

.tile:hover .tile-state {
  display: none;
}

/* 轮播设置弹窗 */
.rotate-tip {
  font-size: 12.5px;
  color: var(--text-secondary);
  margin-bottom: var(--sp-3);
}

.rotate-list {
  max-height: 260px;
  overflow-y: auto;
  border: 1px solid var(--line);
  border-radius: var(--r-md);
  padding: var(--sp-2) var(--sp-3);
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.rotate-list :deep(.el-checkbox) {
  height: 28px;
  margin-right: 0;
}

.rotate-list .mono {
  margin-left: 6px;
  color: var(--text-tertiary);
}

.rotate-empty {
  padding: var(--sp-4) 0;
  text-align: center;
  color: var(--text-tertiary);
  font-size: 12.5px;
}

.rotate-sec {
  margin-top: var(--sp-3);
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  font-size: 12.5px;
  color: var(--text-secondary);
}

/* 选中的画面格：2px 强调描边 */
.tile.is-active .video-box {
  box-shadow: inset 0 0 0 2px var(--accent);
}

/* 工具栏里的开关与轮播按钮 */
.tool-check {
  height: 26px;
  margin-right: 2px;
}

.rotate-btn.is-on {
  color: var(--accent-ink);
  border-color: var(--brand-300);
  background: var(--brand-50);
}

/* 通道面板：列表/分组 页签 */
.panel-tabs {
  display: flex;
  align-items: center;
  gap: 2px;
  padding: 0 6px;
  border-bottom: 1px solid var(--line);
  flex-shrink: 0;
}

.ptab {
  height: 30px;
  padding: 0 10px;
  border: none;
  border-bottom: 2px solid transparent;
  background: transparent;
  font: inherit;
  font-size: 12.5px;
  color: var(--ink-500);
  cursor: pointer;
}

.ptab:hover {
  color: var(--ink-700);
}

.ptab.is-on {
  color: var(--accent-ink);
  border-bottom-color: var(--accent);
  font-weight: 600;
}

/* 分组视图 */
.ch-group-head {
  display: flex;
  align-items: center;
  gap: 6px;
  width: 100%;
  height: 34px;
  padding: 0 8px;
  border: none;
  border-radius: var(--r-sm);
  background: transparent;
  font: inherit;
  font-size: 12.5px;
  color: var(--ink-600);
  cursor: pointer;
  text-align: left;
}

.ch-group-head:hover {
  background: var(--ink-50);
}

.ch-group-main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  line-height: 1.3;
}

.ch-group-name {
  font-size: 12.5px;
  font-weight: 500;
  color: var(--ink-700);
}

.ch-group-code {
  font-size: 10.5px;
  color: var(--ink-400);
}

.ch-group-count {
  font-family: var(--mono);
  font-size: 11px;
  color: var(--ink-400);
}

.ch-group-body {
  padding-left: 10px;
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
