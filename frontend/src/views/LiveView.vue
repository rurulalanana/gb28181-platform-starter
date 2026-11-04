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
        <el-option
          v-for="o in LIVE_PROTOCOLS"
          :key="o.value"
          :label="o.label"
          :value="o.value"
        />
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
            320ms 内两次点同一格视为双击 → 该格全屏。xgplayer 默认接管 video 上的
            单击/双击并 stopPropagation，这个 @click 收不到；useXgPlayer 里用
            closeVideoClick / closeVideoDblclick 关掉了。
          -->
          <div class="video-box" @click="onTileClick(tile)">
            <div :ref="(el) => setContainerRef(tile.id, el)" class="video-container" />

            <!--
              框选图层必须挂在 .video-box 内：组件靠 parentElement 找同层的 <video>
              来算画面真实矩形（区分黑边），挂在外面就拿不到 video 元素。
              每个格子只渲染自己那一个，避免多层重叠抢指针事件。
            -->
            <TrackBox
              v-if="tile.channelKey && trackTileId === tile.id"
              v-model:active="trackActive"
              :serial="tile.serial"
              :code="tile.code"
              mode="targettrack"
            />

            <div v-if="!tile.playing" class="tile-overlay">
              <el-icon :size="30"><VideoCamera /></el-icon>
              <span class="overlay-text">{{ tile.state }}</span>
              <span v-if="tile.channelKey" class="overlay-hint">点击重试</span>
              <span v-else class="overlay-hint">从左侧选择通道</span>
            </div>

            <button
              v-if="!tile.playing && tile.channelKey"
              type="button"
              class="tile-click"
              aria-label="播放该通道"
              @click="playTile(tile)"
            />

            <button
              v-if="tile.playing || tile.channelKey"
              class="tile-clear"
              title="清除该画面"
              aria-label="清除该画面"
              @click.stop="clearTile(tile)"
            >
              <el-icon :size="13" aria-hidden="true"><Close /></el-icon>
            </button>
          </div>
        </div>
      </div>

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
              :disabled="ptzTile.ptzType === 3"
              simple
            />
            <!--
              目标跟踪（A.2.3.1.14）。精简模式的云台面板不放运维 Tab，
              但跟踪是日常操作，单独占一行；框选图层要叠在视频上，
              由本页统一管理状态。
            -->
            <div class="ptz-track-row">
              <!--
                自动跟踪是设备侧指令（SDK/CGI），不依赖画面是否在播；
                框选跟踪要在画面上拖框，必须有画面，所以各自判断。
              -->
              <el-button
                size="small"
                :type="tracking ? 'danger' : 'default'"
                :title="tracking ? '停止目标跟踪' : '让设备自动跟踪（球机智能分析固件支持才生效）'"
                @click="toggleAutoTrack"
              >
                <el-icon><Aim /></el-icon> {{ tracking ? '停止跟踪' : '自动跟踪' }}
              </el-button>
              <el-button
                size="small"
                :disabled="!ptzTile.playing"
                title="在画面上拖拽框选要跟踪的目标"
                @click="beginTrack(ptzTile)"
              >
                <el-icon><Crop /></el-icon> 框选跟踪
              </el-button>
            </div>
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
import { deviceApi, streamApi, systemApi, controlApi } from '@/api'
import { resolvePlayUrls, hasPlayUrl, LIVE_PROTOCOLS, LIVE_DEFAULT_PROTOCOL } from '@/player/resolvePlayUrls'
import { useLivePlayer } from '@/composables/useLivePlayer'
import PtzPanel from '@/components/PtzPanel.vue'
import TrackBox from '@/components/TrackBox.vue'

const channels = ref([])
const gridSize = ref(4)
const filterText = ref('')
const proto = ref(LIVE_DEFAULT_PROTOCOL)
const mediaHttpPort = ref(null)

/**
 * 每格的播放器实例。用普通对象（非 reactive）：reactive 会自动解包嵌套 ref，
 * 取 DOM 时拿到解包后的值而非 ref，无法再赋值绑定。
 */
const players = {}
const containerRefs = {}
const stoppers = {}

const activeTileId = ref(1)

/**
 * 云台绑定的小格：优先最近操作的格子，没有通道时退回第一个已选通道的格子。
 * 不跟随 activeTileId：连续上屏时选中位置会自动前移，云台跟着移会让刚播的通道立刻失去控制。
 */
const ptzTile = computed(() => {
  const picked = tiles.value.find((t) => t.id === ptzTileId.value && t.channelKey)
  if (picked) return picked
  return tiles.value.find((t) => t.channelKey) || null
})

/** 框选图层当前挂在哪一格（0 = 未激活）。同一时刻只允许一格在框选。 */
const trackTileId = ref(0)
const trackActive = ref(false)

/**
 * 自动跟踪状态。设备不支持时不会报错也不做任何动作（标准里没有回传通道），
 * 所以只表示「命令发过」，再点一次发 Stop 复位，不假装知道设备真的在跟。
 */
const tracking = ref(false)

/** 「框选跟踪」：在指定格子上打开拖拽图层。 */
function beginTrack(tile) {
  if (!tile || !tile.playing) {
    ElMessage.warning('请等画面出来后再框选')
    return
  }
  trackTileId.value = tile.id
  trackActive.value = true
}

async function toggleAutoTrack() {
  const t = ptzTile.value
  if (!t || !t.channelKey) {
    ElMessage.warning('请先上屏一个通道')
    return
  }
  const next = !tracking.value
  try {
    const res = await controlApi.targetTrack({
      serial: t.serial,
      code: t.code || t.serial,
      target_track: next ? 'Auto' : 'Stop'
    })
    tracking.value = next
    reportTrackResult(res, next ? '自动跟踪' : '停止跟踪')
  } catch (e) {
    ElMessage.error(e.message || '目标跟踪指令下发失败')
  }
}

/**
 * 按设备应答类型给提示。三种情况必须分开：
 * OK = 设备受理了；ERROR = 设备拒绝；未答 = 设备没回话（多数设备如此）。
 */
function reportTrackResult(res, action) {
  const name = action || '跟踪'
  if (res && res.answered && res.result === 'OK') {
    ElMessage.success(`${name}：设备已受理`)
  } else if (res && res.answered) {
    ElMessage.warning(`${name}：设备拒绝（${res.result || 'ERROR'}）`)
  } else {
    ElMessage.warning(`${name}指令已下发，设备未回应答（以画面是否跟随为准）`)
  }
}

/** 视频墙容器（整体全屏用）。 */
const wallRef = ref(null)
/** 左侧面板视图：tree=设备树，list=平铺列表。 */
const treeTab = ref('tree')
const ptzTileId = ref(0)

/**
 * 切到另一格时把跟踪状态全部清掉：框选图层是根据 `trackTileId` 挂到具体格子上的，
 * 不清会留在旧格上，而且「自动跟踪」的开关状态属于上一路通道，不能带过去。
 */
watch(ptzTileId, () => {
  trackActive.value = false
  trackTileId.value = 0
  tracking.value = false
})
const expandedDevices = ref(new Set())

const rotating = ref(false)
let rotateTimer = null
let rotateIndex = 0

/** 勾选过就只轮这些，否则轮全部在线通道。 */
const rotatePool = computed(() => {
  const all = onlineChannels.value
  if (!rotateKeys.value.length) return all
  const set = new Set(rotateKeys.value)
  return all.filter((c) => set.has(`${c.deviceId}/${c.id}`))
})

/** 轮巡间隔范围 5~300 秒，默认 15 秒 */
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

/** 已被画面格占用的通道 key，通道列表用它标记使用中 */
const usedKeys = computed(
  () => new Set(tiles.value.filter((t) => t.channelKey).map((t) => t.channelKey))
)

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

/** ?play=<设备编码>/<通道编码>（逗号分隔多个）自动上屏，供列表页「上屏预览」跳转过来播放。 */
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

function playerOf(tile) {
  if (!players[tile.id]) {
    // 播放器渲染到容器 div 内，传入容器 ref 供播放器创建 video
    const containerRef = ref(containerRefs[tile.id])
    const api = useLivePlayer(containerRef)
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
 * 点击通道：上屏到当前选中格，选中位置后推一格。用「选中格」而非第一个空位 ——
 * 用户先选位置再点通道，语义就是「放到我刚选的位置」。到最后一格就停住（不绕回第 1 格，避免意外覆盖）。
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
  if (!hasPlayUrl(proto.value, urls)) {
    tile.state = '未获取到播放地址'
    ElMessage.error('未获取到播放地址，请确认设备在线')
    return
  }

  await player.play(streamId, urls, {
    proto: proto.value,
    // 通了但始终没视频轨 → 拆会话重推（ZLM 超时会把未就绪的视频轨永久丢弃）
    onStall: () => rebuildStalled(tile),
    // 播放器自动改协议时同步顶部下拉（典型：FLV 解码失败重试耗尽 → 切 WebRTC）。
    // 各格协议独立，下拉只代表「下次点播用哪个」，取最后操作的格子合理
    onProtoChange: (p) => {
      if (p && p !== proto.value) proto.value = p
    }
  })
}

/** 单格「有数据但无画面」的重建上限 2 次：电视墙长期开着，设备坏了无限重建会把整墙刷成不停 INVITE。 */
const stallRetryOf = {}

/**
 * 有数据却无视频轨 → 停会话 + 重新点播。必须停会话：ZLM 等轨超时会把未就绪的视频轨
 * 永久丢弃（Track not ready for a long time, ignored: H264），重连播放器没用。
 */
async function rebuildStalled(tile) {
  const n = stallRetryOf[tile.id] || 0
  if (n >= 2 || !tile.channelKey) return
  stallRetryOf[tile.id] = n + 1
  tile.state = '重新拉流中…'
  try {
    await streamApi.stop({ serial: tile.serial, code: tile.code })
  } catch (e) {
    // 会话可能已被空闲巡检关掉，继续重播即可
  }
  if (!tile.channelKey || !players[tile.id]) return
  await playTile(tile)
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

/** 清除单个画面格：断流并解绑通道，只影响这一格（旧版是顶端「全部停止」） */
function clearTile(tile) {
  stopTile(tile)
  // 格子被腾空就是新的一轮，重置卡轨重建计数
  delete stallRetryOf[tile.id]
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
 * 切换协议：所有播放中的格子重连。拿不到地址时必须重新完整点播，不能直接 switchProtocol：
 * stop() 后 ZLM 读者数归零，45 秒空闲巡检可能刚好关流，再查 info 就是 400；
 * switchProtocol 拿到空地址会清空播放器（画面没了也不报错）。
 */
async function onProtoChange() {
  const active = visibleTiles.value.filter((t) => t.playing && t.channelKey)
  if (!active.length) return
  const label = LIVE_PROTOCOLS.find((o) => o.value === proto.value)?.label || proto.value
  ElMessage.info(`正在切换为 ${label}…`)
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
    if (!urls || !hasPlayUrl(proto.value, urls)) {
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
  // ws-flv 引擎不需要额外的 SDK 下载（解封装用 xgplayer-transmuxer、音频用 Web Audio），不做预热
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
.live-page {
  /* .page 是 flex 列容器但没有高度，需从 .content(flex:1) 一路继承；min-height:0 允许子项收缩 */
  height: 100%;
  min-height: 0;
}

.live-body {
  display: grid;
  /*
   * 右侧云台栏 360px（原 252px）。
   *
   * <p>云台扩展里的巡航组/线扫/巡迹是 6~7 列的表格，252px 下每列不到 30px，
   * 名称列被压成 2 个字。360px 是能在不显著挤压中间画面的前提下
   * 让表格各列都放得下完整内容的最小值。
   */
  grid-template-columns: 248px minmax(0, 1fr) 420px;
  gap: var(--sp-3);
  flex: 1;
  min-height: 0;
}

/* 三栏固定列位：显式指定后 DOM 顺序变化不会把墙挤到侧栏位置；小屏单列时列位复位 */
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

.wall {
  display: grid;
  gap: var(--sp-3);
  min-width: 0;
  min-height: 0;
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

/* 靠 1px 内描边区分边界，不用投影（投影会让视频墙显得「漂浮」） */
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
  padding: 0;
  border: 0;
  background: transparent;
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

.side-panel {
  display: flex;
  flex-direction: column;
  min-height: 0;
  background: var(--bg-surface);
  border: 1px solid var(--line);
  border-radius: var(--r-lg);
  overflow: hidden;
}

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

/*
 * 目标跟踪按钮行。精简模式的云台面板不放运维 Tab，但跟踪是日常操作，
 * 单独占一行；两个按钮等宽，与上方方向盘的中轴对齐。
 */
.ptz-track-row {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px;
  margin: 4px 2px 0;
}
.ptz-track-row .el-button {
  margin: 0;
  width: 100%;
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

.rotate-btn.is-on {
  color: var(--accent-ink);
  border-color: var(--brand-300);
  background: var(--brand-50);
}

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
