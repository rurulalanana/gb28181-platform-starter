<template>
  <div class="page live-page is-fill">

    <!-- 工具条：分屏/协议/轮播/全屏，与其它页保持同一套工具条 -->
    <div class="filter-bar">
      <span class="filter-bar-spacer" />
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
    </div>
    <!-- 页头 -->

    <!-- 主体：设备树 + 视频墙 + 云台 -->
    <div class="live-body">
      <!-- 左侧：设备树（对齐 LiveGBS：搜索 + 设备/通道树） -->
      <aside class="side-panel tree-panel">
        <div class="panel-head">
          <span class="panel-title">设备树</span>
          <span class="count-pill">{{ filteredChannels.length }}</span>
        </div>

        <!-- 视图切换：设备树 / 平铺列表 -->
        <div class="panel-tabs">
          <button class="ptab" :class="{ 'is-on': treeTab === 'tree' }" @click="treeTab = 'tree'">
            设备树
          </button>
          <button class="ptab" :class="{ 'is-on': treeTab === 'list' }" @click="treeTab = 'list'">
            通道列表
          </button>
        </div>

        <div class="panel-search">
          <el-input v-model="filterText" placeholder="搜索名称或编码" clearable size="small">
            <template #prefix><el-icon><Search /></el-icon></template>
          </el-input>
        </div>

        <div v-if="!filteredChannels.length" class="panel-empty">
          <el-empty :description="channels.length ? '无匹配通道' : '暂无通道'" :image-size="64" />
        </div>

        <!-- 设备树：设备为可折叠节点，通道挂在设备下 -->
        <div v-else-if="treeTab === 'tree'" class="channel-list dark-scroll">
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
                title="点击上屏到选中格；再次点击已在播放的通道可停止"
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
          </div>
        </div>

        <!-- 平铺列表 -->
        <div v-else class="channel-list dark-scroll">
          <button
            v-for="c in filteredChannels"
            :key="c.deviceId + '/' + c.id"
            class="channel-item"
            :class="{ 'is-offline': !c.deviceOnline, 'is-used': usedKeys.has(c.deviceId + '/' + c.id) }"
            title="点击上屏到选中格；再次点击已在播放的通道可停止"
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

      <!-- 视频墙 -->
      <div ref="wallRef" class="wall" :class="'wall-' + gridSize">
        <div
          v-for="tile in visibleTiles"
          :key="tile.id"
          class="tile"
          :class="{ 'is-active': tile.id === activeTileId }"
        >
          <!--
            点击画面格：切换「当前操作格」（active）与云台目标；
            320ms 内再次点同一格视为双击 → 该格全屏。

            注意：xgplayer 默认会接管 video 上的单击（暂停）和双击（全屏），
            并且在处理函数里 stopPropagation —— 那样这个 @click 根本收不到。
            所以 useXgPlayer 里统一配了 closeVideoClick / closeVideoDblclick，
            让播放器不再拦截点击事件（详见该文件 baseUiConfig 的说明）。
          -->
          <div class="video-box" @click="onTileClick(tile)">
            <!--
              xgplayer 会在容器内自建 video 元素渲染（直播走 MSE + Web Audio，
              录像回放走原生 video），所以这里不是 <video> 而是普通容器 div。
            -->
            <div :ref="(el) => setContainerRef(tile.id, el)" class="video-container" />

            <!-- 空闲态：提示如何上屏 -->
            <div v-if="!tile.playing" class="tile-overlay">
              <el-icon :size="30"><VideoCamera /></el-icon>
              <span class="overlay-text">{{ tile.state }}</span>
              <span v-if="tile.channelKey" class="overlay-hint">点击重试</span>
              <span v-else class="overlay-hint">从左侧选择通道</span>
            </div>

            <!-- 空闲态且已选通道时整块可点 -->
            <div
              v-if="!tile.playing && tile.channelKey"
              class="tile-click"
              @click="playTile(tile)"
            />

            <!-- 右上角：清除该格（断流并腾空） -->
            <button
              v-if="tile.playing || tile.channelKey"
              class="tile-clear"
              title="清除该画面"
              @click.stop="clearTile(tile)"
            >
              <el-icon :size="13"><Close /></el-icon>
            </button>
          </div>
        </div>
      </div>

      <!-- 右侧：云台控制（常驻，绑定当前操作的画面格） -->
      <aside class="side-panel ptz-aside">
        <div class="panel-head">
          <span class="panel-title">云台控制</span>
          <span v-if="ptzTile" class="ptz-target-cell">画面 {{ ptzTile.id }}</span>
        </div>

        <div class="ptz-tab dark-scroll">
          <template v-if="ptzTile">
            <div class="ptz-target">
              <span class="ch-dot" :class="ptzTile.playing ? 'is-online' : 'is-offline'" />
              <span class="ellipsis">{{ ptzTile.channelName || ptzTile.code }}</span>
            </div>
            <div v-if="ptzTile.ptzType === 3" class="ptz-note">
              该通道标记为固定枪机，云台指令可能不被设备支持
            </div>
            <PtzPanel
              :key="ptzTile.id"
              :serial="ptzTile.serial"
              :code="ptzTile.code"
              :disabled="!ptzTile.playing || ptzTile.ptzType === 3"
              simple
            />
          </template>
          <div v-else class="panel-empty">
            <el-empty description="先在左侧上屏一个通道，再进行云台控制" :image-size="64" />
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
import { resolvePlayUrls } from '@/player/resolvePlayUrls'
import { useXgPlayer } from '@/composables/useXgPlayer'
import PtzPanel from '@/components/PtzPanel.vue'

const channels = ref([])
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
/** 每格的容器 DOM 引用（xgplayer 在容器内自建 video）。 */
const containerRefs = {}
/** 播放器状态监听停止句柄。 */
const stoppers = {}

/** 当前选中的画面格（加 2px 强调描边，方便看清操作的是哪一格）。 */
const activeTileId = ref(1)

/**
 * 云台面板绑定的小格：优先取最近操作的格子，该格没有通道时退回到第一个已选通道的格子。
 *
 * <p>不直接跟随 {@code activeTileId}：连续上屏时选中位置会自动前移（见 pickChannel），
 * 若云台跟着前移，刚播放的通道会立刻失去云台控制。
 */
const ptzTile = computed(() => {
  const picked = tiles.value.find((t) => t.id === ptzTileId.value && t.channelKey)
  if (picked) return picked
  return tiles.value.find((t) => t.channelKey) || null
})

/** 视频墙容器（整体全屏用）。 */
const wallRef = ref(null)
/** 左侧面板视图：tree=设备树，list=平铺列表。 */
const treeTab = ref('tree')
/** 云台面板绑定的小格（最近操作的格子）。 */
const ptzTileId = ref(0)
/** 已展开的设备（设备树）。 */
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
    /** 通道的云台类型：3（固定枪机）时右侧云台面板置灰。 */
    ptzType: 0,
    state: '未选择通道',
    playing: false
  }))
)

const visibleTiles = computed(() => tiles.value.slice(0, gridSize.value))

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

/** 画面格点击：单选该格；320ms 内再次点击同一格视为双击 → 切换全屏。 */
const tileClick = { id: 0, ts: 0 }

function onTileClick(tile) {
  activeTileId.value = tile.id
  // 点了带通道的格子：云台跟着切到这一格
  if (tile.channelKey) ptzTileId.value = tile.id
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

function setContainerRef(id, el) {
  if (el) containerRefs[id] = el
}

/** 获取（或创建）某格的播放器，并绑定当前 DOM 元素与状态监听。 */
function playerOf(tile) {
  if (!players[tile.id]) {
    // 播放器渲染到容器 div 内，传入容器 ref 供播放器创建 video
    const containerRef = ref(containerRefs[tile.id])
    const api = useXgPlayer(containerRef)
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
  try {
    const data = await deviceApi.channelList({ start: 0, limit: 1000 })
    channels.value = data?.channelList || []
    // 设备树默认全展开（对齐 LiveGBS：进来就能看到通道）
    expandedDevices.value = new Set(channels.value.map((c) => c.deviceId))
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
  }
}

/**
 * 点击左侧通道：上屏到当前选中的画面格，并把选中位置往后推一格。
 *
 * <p><b>为什么是「选中格」而不是「第一个空位」</b>：用户先点画面格选出要操作的位置，
 * 再点通道，语义就是「把这一路放到我刚选的位置」。若改成找第一个空位，
 * 用户的选择被忽略，连点几个通道会跳到与预期不同的格子上。
 *
 * <p><b>为什么播放后 active 要 +1</b>：连续上屏是常见操作 —— 选好起始格后
 * 依次点几个通道，每次都期望落在下一格。自动前移后无需反复点格子，
 * 且视觉焦点跟着走，能立刻看出「下一个会落在哪」。
 *
 * <p>前移只在还有可见格时进行；已到最后一格就停在原地，
 * 再次点击会覆盖该格（而不是绕回第 1 格，那样会意外覆盖别处的画面）。
 */
function pickChannel(channel) {
  const key = `${channel.deviceId}/${channel.id}`
  const target =
    visibleTiles.value.find((t) => t.id === activeTileId.value) || visibleTiles.value[0]
  if (!target) return
  // 同一路已在当前格播放：再点一次即停止（画面格上已不放停止按钮）
  if (target.playing && target.channelKey === key) {
    stopTile(target)
    ptzTileId.value = target.id
    return
  }
  if (target.playing) stopTile(target)
  target.channelKey = key
  applyChannel(target, channel)
  ptzTileId.value = target.id
  playTile(target)
  // 焦点前移到下一格，方便连续上屏
  const next = visibleTiles.value.find((t) => t.id === target.id + 1)
  if (next) activeTileId.value = next.id
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
  // 点播就把云台面板切到这一格（上屏后马上能控云台）
  ptzTileId.value = tile.id
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

/**
 * 清除单个画面格：断流并解绑通道，格子回到「未选择通道」。
 *
 * <p>与旧版顶端「全部停止 / 清除」相比，清除粒度改到每格右上角，
 * 想腾空哪一格就清哪一格，不影响其它画面。
 */
function clearTile(tile) {
  stopTile(tile)
  tile.channelKey = ''
  tile.serial = ''
  tile.code = ''
  tile.channelName = ''
  tile.ptzType = 0
  tile.playing = false
  tile.state = '未选择通道'
  if (ptzTileId.value === tile.id) ptzTileId.value = 0
}

/**
 * 切换协议：所有播放中的格子重连。
 *
 * <p><b>拿不到播放地址时要重新完整点播，不能直接调 switchProtocol</b>：
 * 切协议时 {@code stop()} 会断开旧通路的连接，这一瞬间 ZLM 的读者数会归零，
 * 平台侧的空闲流巡检（{@code reapIdleStreams}，45 秒无观看者即关流）
 * 有可能刚好在此时执行，把流关掉。之后再查 {@code stream/info} 就是 400，
 * {@code urls} 为空 —— 而 {@code switchProtocol} 拿到空地址会直接清空播放器，
 * 表现就是「切回来画面没了，也不报错」。
 *
 * <p>所以这里一旦发现流不在了，就走完整的 {@code playTile}（内部会重新
 * INVITE 拉起设备推流），而不是继续拿空地址去切。
 */
async function onProtoChange() {
  const active = visibleTiles.value.filter((t) => t.playing && t.channelKey)
  if (!active.length) return
  ElMessage.info(`正在切换为 ${proto.value === 'wsflv' ? 'WebSocket-FLV' : 'WebRTC'}…`)
  for (const tile of active) {
    const streamId = `${tile.serial}/${tile.code}`
    const player = playerOf(tile)
    let urls = null
    try {
      const info = await streamApi.info(streamId, true)
      urls = resolvePlayUrls(info, mediaHttpPort.value)
    } catch (e) {
      // 流可能已被空闲巡检关闭，下面会重新点播
    }
    if (!urls || (!urls.wsFlv && !urls.webrtc)) {
      // 流不在了：重新完整点播（会重新 INVITE 拉起设备推流）
      tile.state = '重新点播中…'
      await playTile(tile)
      continue
    }
    await player.switchProtocol(proto.value, urls)
    tile.state = player.state.value
    tile.playing = player.playing.value
  }
}

const route = useRoute()
const router = useRouter()

onMounted(async () => {
  /* ws-flv 引擎不需要额外的 SDK 下载（解封装用 xgplayer-transmuxer，
     音频用浏览器原生 Web Audio），因此不再做播放器预热。 */
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
  /* 左树 · 中墙 · 右云台 */
  grid-template-columns: 248px minmax(0, 1fr) 252px;
  gap: var(--sp-3);
  flex: 1;
  min-height: 0;
}

/* 三栏固定列位：树在最左、墙在中间、云台在最右；
   显式指定列位后，DOM 顺序变化也不会把墙挤到侧栏位置。
   小屏单列时列位复位，靠 order 保持「树 → 墙 → 云台」的纵向顺序。 */
.tree-panel {
  grid-column: 1;
  order: 1;
}

.wall {
  grid-column: 2;
  order: 2;
}

.ptz-aside {
  grid-column: 3;
  order: 3;
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
  min-width: 0;
  min-height: 0;
}

/* ============ 画面格 ============ */

/* 画面格：靠 1px 内描边区分边界，不用投影（投影会让视频墙显得"漂浮"） */
.tile .video-box {
  /* 全局 .video-box 是 16:9 定比例的；视频墙里要让每格精确填满网格单元，
     否则多出来的比例高度会溢出/留白，故改为自适应高度 */
  aspect-ratio: auto;
  flex: 1;
  min-height: 0;
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
  font-size: var(--fs-xs);
  color: rgba(255, 255, 255, 0.5);
}

.overlay-hint {
  font-size: var(--fs-xs);
  color: rgba(255, 255, 255, 0.28);
}

/* 整块可点层（避免与遮罩的 pointer-events 冲突） */
.tile-click {
  position: absolute;
  inset: 0;
  cursor: pointer;
}

/* 右上角清除按钮：常驻但低调，悬停变红提示危险 */
.tile-clear {
  position: absolute;
  top: 8px;
  right: 8px;
  z-index: 3;
  width: 22px;
  height: 22px;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 0;
  border: none;
  border-radius: 50%;
  background: rgba(8, 13, 20, 0.62);
  color: rgba(255, 255, 255, 0.72);
  cursor: pointer;
  opacity: 0.6;
  transition: opacity 0.15s ease, background 0.15s ease, color 0.15s ease;
}

.tile:hover .tile-clear {
  opacity: 1;
}

.tile-clear:hover {
  background: var(--danger-500);
  color: var(--white);
}

/* 分屏切换（1/4/6/9/16）压到与其它控件同高，避免顶栏高低不齐 */
.filter-bar :deep(.el-radio-button__inner) {
  height: 26px;
  line-height: 24px;
  min-width: 32px;
  padding: 0 8px;
  font-size: var(--fs-xs);
  font-family: var(--mono);
  font-weight: 600;
  border-color: var(--line);
}

.filter-bar :deep(.el-radio-button:first-child .el-radio-button__inner) {
  border-radius: var(--r-md) 0 0 var(--r-md);
}

.filter-bar :deep(.el-radio-button:last-child .el-radio-button__inner) {
  border-radius: 0 var(--r-md) var(--r-md) 0;
}

/* ============ 侧栏面板（设备树 / 云台） ============ */

.side-panel {
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
  font-size: var(--fs-sm);
  background: var(--bg-sunken, #f8fafc);
}
.ptz-target .ellipsis {
  flex: 1;
  min-width: 0;
}
.ptz-target-cell {
  flex-shrink: 0;
  color: var(--ink-400, #909399);
  font-size: var(--fs-xs);
}

/* 固定枪机提示：云台面板整体置灰时给出原因，避免用户以为是故障 */
.ptz-note {
  margin: 0 8px 8px;
  padding: 6px 8px;
  border-radius: var(--r-sm);
  background: var(--warning-50, #fdf6ec);
  color: var(--warning-600, #b88230);
  font-size: var(--fs-xs);
  line-height: 1.45;
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
  font-size: var(--fs-md);
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
  font-size: var(--fs-sm);
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
  font-size: var(--fs-xs);
  color: var(--text-tertiary);
  flex-shrink: 0;
}

/* 轮播设置弹窗 */
.rotate-tip {
  font-size: var(--fs-sm);
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
  font-size: var(--fs-sm);
}

.rotate-sec {
  margin-top: var(--sp-3);
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  font-size: var(--fs-sm);
  color: var(--text-secondary);
}

/* 选中的画面格：实心强调描边 + 外发光，确保一眼能看出操作的是哪一格 */
.tile.is-active .video-box {
  box-shadow:
    inset 0 0 0 3px var(--accent),
    0 0 0 1px var(--accent),
    0 0 14px rgba(44, 98, 214, 0.55);
}

/* 工具栏里的轮播按钮 */
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
  font-size: var(--fs-sm);
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
  font-size: var(--fs-sm);
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
  font-size: var(--fs-sm);
  font-weight: 500;
  color: var(--ink-700);
}

.ch-group-code {
  font-size: 10.5px;
  color: var(--ink-400);
}

.ch-group-count {
  font-family: var(--mono);
  font-size: var(--fs-xs);
  color: var(--ink-400);
}

.ch-group-body {
  padding-left: 10px;
}

/* ============ 响应式 ============ */

@media (max-width: 1400px) {
  .live-body {
    grid-template-columns: 216px minmax(0, 1fr) 230px;
  }
}

@media (max-width: 1100px) {
  /* 单列堆叠：树在上、墙居中、云台在下 */
  .live-body {
    grid-template-columns: minmax(0, 1fr);
    overflow-y: auto;
  }
  .tree-panel,
  .wall,
  .ptz-aside {
    grid-column: auto;
  }
  .tree-panel,
  .ptz-aside {
    max-height: 280px;
  }
  .wall {
    min-height: 320px;
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
