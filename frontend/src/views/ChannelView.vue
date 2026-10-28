<template>
  <div class="page is-fill">

    <el-card shadow="never">
    <!-- 工具条：筛选条件在左，操作按钮在右，一行放完 -->
    <div class="filter-bar">
      <el-input
        v-model="query.q"
        placeholder="通道编码 / 名称"
        clearable
        style="width: 220px"
        @keyup.enter="search"
      >
        <template #prefix><el-icon><Search /></el-icon></template>
      </el-input>
      <el-select v-model="query.online" placeholder="全部状态" clearable style="width: 130px">
        <el-option label="在线" :value="true" />
        <el-option label="离线" :value="false" />
      </el-select>
      <el-tag v-if="query.serial" closable type="info" @close="clearSerialFilter">
        <span class="mono">{{ query.serial }}</span>
      </el-tag>
      <el-button type="primary" @click="search">
        <el-icon><Search /></el-icon> 查询
      </el-button>
      <span class="filter-bar-spacer" />
      <el-button size="small" :loading="loading" @click="load">
        <el-icon><Refresh /></el-icon> 刷新
      </el-button>
    </div>

      <!--
        列结构对齐 LiveGBS 的「设备 > 通道列表」页：
        通道号 / 通道国标编号 / 通道名称 / 在线状态 / 按需直播 / 云端录像 /
        子节点数 / 厂家 / 云台类型 / 位置 / 更新时间 / 创建时间 / 操作
      -->
      <el-table ref="tableRef" v-loading="loading" :data="channels" stripe table-layout="fixed">
        <el-table-column prop="channel" label="通道号" min-width="64" align="center" sortable>
          <template #default="{ row }">
            <span class="text-muted">{{ row.channel ?? '—' }}</span>
          </template>
        </el-table-column>

        <el-table-column prop="id" label="通道国标编号" min-width="168" sortable>
          <template #default="{ row }"><span class="mono code-sm">{{ row.id }}</span></template>
        </el-table-column>

        <el-table-column
          label="通道名称"
          min-width="124"
          :sort-by="(r) => r.customName || r.name || ''"
          sortable
          show-overflow-tooltip
        >
          <template #default="{ row }">
            <!--
              按需编辑：常驻输入框会在每行渲染「点击修改」占位符，一屏全是灰字。
              平时显示文本，点击才换输入框。
            -->
            <el-input
              v-if="row._editing"
              :id="nameInputId(row)"
              v-model="row.customName"
              size="small"
              class="name-input"
              @keydown.enter="saveName(row)"
              @keydown.esc="cancelName(row)"
              @blur="saveName(row)"
            />
            <span
              v-else
              class="name-text is-editable"
              :class="{ 'is-empty': !(row.customName || row.name) }"
              title="点击修改名称"
              @click="startNameEdit(row)"
            >{{ row.customName || row.name || '—' }}</span>
          </template>
        </el-table-column>

        <el-table-column label="在线状态" min-width="88" align="center">
          <template #default="{ row }">
            <div class="status-cell">
              <span
                class="badge is-clickable"
                :class="row.status === 'ON' ? 'is-online' : 'is-offline'"
                title="点击查看设备详情"
                @click="openDeviceDetail(row)"
              >{{ row.status === 'ON' ? '在线' : '离线' }}</span>
              <el-icon class="traffic-icon" title="点击查看设备详情" @click="openDeviceDetail(row)">
                <Histogram />
              </el-icon>
            </div>
          </template>
        </el-table-column>

        <el-table-column label="按需直播" min-width="88" align="center">
          <template #header>
            <el-tooltip
              content="按需直播：无人观看时自动停流以节省设备带宽。与「云端录像」互斥——开启它会自动关闭云端录像。"
              placement="top"
            >
              <span class="th-with-tip">按需直播 <el-icon><QuestionFilled /></el-icon></span>
            </el-tooltip>
          </template>
          <template #default="{ row }">
            <el-switch
              v-model="row.ondemand"
              :loading="ondemandPending.has(row.id)"
              :disabled="ondemandPending.has(row.id)"
              inline-prompt
              active-text="开"
              inactive-text="关"
              @change="(val) => toggleOndemand(row, val)"
            />
          </template>
        </el-table-column>

        <el-table-column label="云端录像" min-width="88" align="center">
          <template #header>
            <el-tooltip
              content="云端录像：平台持续把这段流录成 MP4，要求流一直在线。与「按需直播」互斥——开启它会自动关闭按需直播。开启后为全天录制，会忽略录像计划的时段限制。"
              placement="top"
            >
              <span class="th-with-tip">云端录像 <el-icon><QuestionFilled /></el-icon></span>
            </el-tooltip>
          </template>
          <template #default="{ row }">
            <el-switch
              v-model="row.cloudRecord"
              :loading="recordPending.has(row.id)"
              :disabled="recordPending.has(row.id)"
              inline-prompt
              active-text="开"
              inactive-text="关"
              @change="(val) => toggleCloudRecord(row, val)"
            />
          </template>
        </el-table-column>

        <el-table-column label="子节点数" min-width="68" align="center">
          <template #default="{ row }">
            <span class="text-muted">{{ row.subCount ?? 0 }}</span>
          </template>
        </el-table-column>

        <el-table-column label="厂家" min-width="96" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="text-muted">{{ row.manufacturer || '—' }}</span>
          </template>
        </el-table-column>

        <el-table-column label="云台类型" min-width="108" align="center">
          <template #default="{ row }">
            <!--
              与设备管理的「流传输模式」保持一致：文字 + 箭头，点开是下拉菜单。
              用 el-select 的话一屏几十行全是灰框输入框，很脏。

              【坑】el-dropdown 在表格里多行复用时，@command 只回传被选项的值，
              不知道自己属于哪一行（实测点第 5 行的菜单，改的却是第 1 行）。
              解决：命令值带上通道编码，由 handlePtzCommand 拆开去对应行上应用。
            -->
            <el-dropdown
              trigger="click"
              :disabled="ptzPending.has(row.id)"
              @command="handlePtzCommand"
            >
              <span class="ptz-link" :class="{ 'is-busy': ptzPending.has(row.id) }">
                {{ ptzText(row) }}
                <el-icon class="ptz-arrow"><ArrowDown /></el-icon>
              </span>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item
                    v-for="(label, value) in PTZ_TYPES"
                    :key="value"
                    :command="row.id + '|' + value"
                    :disabled="Number(value) === (row.ptzType ?? 0)"
                  >
                    {{ label }}
                  </el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
          </template>
        </el-table-column>

        <el-table-column label="位置" min-width="140" align="center">
          <template #default="{ row }">
            <span
              class="position-text is-editable"
              title="点击修改经纬度"
              @click="openPosition(row)"
            >{{ positionText(row) }}</span>
          </template>
        </el-table-column>

        <el-table-column prop="updatedAt" label="更新时间" min-width="156" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="text-muted">{{ row.updatedAt || '—' }}</span>
          </template>
        </el-table-column>

        <el-table-column prop="createdAt" label="创建时间" min-width="156" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="text-muted">{{ row.createdAt || '—' }}</span>
          </template>
        </el-table-column>

        <!--
          操作列固定在右侧：表格列多（参考站同款 13 列）必然超宽，
          不固定的话横向滚动才能看到按钮，操作入口等于藏起来了。
        -->
        <el-table-column label="操作" min-width="252" align="center" class-name="op-col" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="preview(row)">
              <el-icon><VideoPlay /></el-icon> 播放
            </el-button>
            <!--
              「停止」只在通道确实有推流时出现。
              原来是一直显示、无流时置灰 —— 但十几个通道里通常只有一两路在推，
              满屏灰按钮会让人以为功能坏了（点了没反应的按钮比没有按钮更糟）。
              判据用 streamId：后端在开流时写入、会话关闭时清除、
              平台重启时由 resetStaleChannelStates() 收敛，不会长期残留。
            -->
            <el-button
              v-if="row.streamId"
              link
              type="primary"
              title="停止该通道的推流"
              @click="stopStream(row)"
            >
              <el-icon><VideoPause /></el-icon> 停止
            </el-button>
            <el-button
              link
              type="primary"
              title="上屏到实时预览（自动播放）"
              @click="playOnWall(row)"
            >
              <el-icon><VideoCamera /></el-icon> 上屏
            </el-button>
            <el-button
              link
              type="primary"
              title="查看该通道的历史录像"
              @click="openDeviceRecord(row)"
            >
              <el-icon><Film /></el-icon> 设备录像
            </el-button>
          </template>
        </el-table-column>
        <template #empty>
          <div class="empty-cell">暂无通道</div>
        </template>
      </el-table>

      <TablePager
        :pager="pager"
        unit="个通道"
        @page-change="onPageChange"
        @size-change="onSizeChange"
      />
    </el-card>

    <!-- 预览对话框 -->
    <!--
      尺寸对齐参考站（LiveGBS）的播放弹窗：那边视频区实测 878×494（16:9），
      云台面板 303 宽贴在右侧，弹窗整体约 1200px 宽。
      这里取 1280px 上限（视口小时按 94vw 收缩），视频栏因此比参考站更宽一些。
    -->
    <el-dialog
      v-model="previewDialog"
      :title="'实时预览 · ' + (previewTarget?.customName || previewTarget?.name || previewTarget?.id || '')"
      width="min(1280px, 94vw)"
      top="5vh"
      class="preview-dialog"
      @close="closePreview"
      @closed="onPreviewClosed"
    >
      <div class="preview-wrap">
        <!--
          视频外层负责撑满栏高并居中；.video-box 自身保持 16:9，
          因此在宽而矮的弹窗里会上下留白、在窄而高的弹窗里左右留白，
          不会拉伸变形。
        -->
        <div class="preview-stage">
          <div class="video-box">
            <div ref="videoContainer" class="video-container" />
          </div>
        </div>
        <div class="preview-side">
          <!--
            状态 + 播放方式。
            el-tag 与 el-select 的高度不同（tag 24px / select 32px），
            默认基线对齐会一高一低；这里统一用 align-items: center 居中，
            并给右侧留出滚动条宽度的补偿，避免下拉框贴住滚动条。
          -->
          <div class="preview-status">
            <el-tag :type="stateType" size="small" effect="dark" class="status-tag">
              {{ state }}
            </el-tag>
          </div>
          <div class="preview-sep" />
          <!--
            云台面板只在通道被标为可云台（球机 / 半球 / 遥控枪机）时提供。
            类型未知或固定枪机时不显示 —— 面板里的方向键对固定枪机无效，
            摆在那里只会让人反复点而怀疑平台坏了。
          -->
          <PtzPanel
            v-if="previewTarget && supportsPtz(previewTarget.ptzType)"
            :serial="previewTarget.deviceId"
            :code="previewTarget.id"
            :disabled="!playing"
          />
          <el-empty
            v-else-if="previewTarget"
            description="该通道未标记云台类型，无法云台控制"
            :image-size="60"
          />
        </div>
      </div>
      <template #footer>
        <el-button @click="closePreview">关闭</el-button>
      </template>
    </el-dialog>

    <!-- 重命名对话框 -->
    <el-dialog v-model="renameDialog" title="重命名通道" width="460px">
      <div class="mono mb-3">{{ renameTarget?.id }}</div>
      <el-input v-model="renameValue" placeholder="自定义名称" />
      <template #footer>
        <el-button @click="renameDialog = false">取消</el-button>
        <el-button type="primary" @click="doRename">保存</el-button>
      </template>
    </el-dialog>

    <!-- 位置（经纬度）编辑 -->
    <el-dialog v-model="positionDialog" title="修改位置" width="440px">
      <div class="mono mb-3">{{ positionTarget?.id }}</div>
      <el-form label-width="72px">
        <el-form-item label="经度">
          <el-input v-model="positionLon" placeholder="经度，如 113.325573" />
        </el-form-item>
        <el-form-item label="纬度">
          <el-input v-model="positionLat" placeholder="纬度，如 23.132123" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="positionDialog = false">取消</el-button>
        <el-button type="primary" :loading="positionSaving" @click="savePosition">保存</el-button>
      </template>
    </el-dialog>

    <!-- 所属设备详情 -->
    <DeviceDetailDialog
      v-model:visible="detailVisible"
      :device="detailTarget"
    />
  </div>
</template>

<script setup>
import { computed, nextTick, onMounted, onUnmounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useRoute, useRouter } from 'vue-router'
import { deviceApi, streamApi, systemApi } from '@/api'
import { resolvePlayUrls, hasPlayUrl, LIVE_PROTOCOLS, LIVE_PROTOCOL_SHORT } from '@/player/resolvePlayUrls'
import { useXgPlayer } from '@/composables/useXgPlayer'
import { usePlayerStateType } from '@/composables/usePlayerState'
import { usePagination } from '@/composables/usePagination'
import { useFillTableRows } from '@/composables/useFillTableRows'
import PtzPanel from '@/components/PtzPanel.vue'
import TablePager from '@/components/TablePager.vue'
import DeviceDetailDialog from '@/components/DeviceDetailDialog.vue'


/** 上屏预览需要跳转路由。 */
const router = useRouter()
const route = useRoute()

const channels = ref([])
const loading = ref(false)
const query = reactive({ q: '', online: null, serial: '' })

/** 行高随表体可用高度自适应，10 条数据正好铺满表格区。 */
const tableRef = ref(null)
useFillTableRows(tableRef, () => channels.value.length)

/** 正在切换云端录像开关的通道编码集合（用于开关 loading / 防重复点击）。 */
const recordPending = reactive(new Set())

/** 正在修改云台类型的通道编码集合（用于下拉 loading / 防重复点击）。 */
const ptzPending = reactive(new Set())

/** 正在切换「按需直播」开关的通道编码集合。 */
const ondemandPending = reactive(new Set())

/** 位置（经纬度）编辑弹窗。 */
const positionDialog = ref(false)
const positionTarget = ref(null)
const positionLon = ref('')
const positionLat = ref('')
const positionSaving = ref(false)

/** 所属设备详情弹窗（状态记录 / 流量统计 / 移动轨迹 / 会话列表）。 */
const detailVisible = ref(false)
const detailTarget = ref(null)

const previewDialog = ref(false)
const previewTarget = ref(null)
const proto = ref('wsflv')
const mediaHttpPort = ref(null)

/** 播放器容器（xgplayer 在内部创建 video 元素）。 */
const videoContainer = ref(null)
const player = useXgPlayer(videoContainer)
const playing = computed(() => player.playing.value)
const state = computed(() => player.state.value)
const stateType = usePlayerStateType(state)

const renameDialog = ref(false)
const renameTarget = ref(null)
const renameValue = ref('')

/**
 * 云台类型：0 未知 / 1 球机 / 2 半球 / 3 固定枪机 / 4 遥控枪机。
 *
 * <p>与后端 {@code gb_channel.ptz_type} 的取值一致。
 */
const PTZ_TYPES = { 0: '未知', 1: '球机', 2: '半球', 3: '固定枪机', 4: '遥控枪机' }

/** 云台类型显示文案。 */
function ptzText(row) {
  return PTZ_TYPES[row.ptzType ?? 0] || '未知'
}

/**
 * 处理云台类型下拉菜单的命令。
 *
 * <p>命令值形如「通道编码|类型」，因为 el-dropdown 不知道命令来自哪一行。
 */
function handlePtzCommand(cmd) {
  const sep = cmd.lastIndexOf('|')
  if (sep < 0) return
  const channelId = cmd.slice(0, sep)
  const type = Number(cmd.slice(sep + 1))
  const row = channels.value.find((c) => c.id === channelId)
  if (!row) return
  changePtzType(row, type)
}

/**
 * 该类型是否支持云台控制。
 *
 * <p>0（未知）与 3（固定枪机）不支持：前者设备没报，后者物理上就不能转。
 */
function supportsPtz(type) {
  return type === 1 || type === 2 || type === 4
}

/**
 * 把设备上报的码流编号列表转成可读文案。
 *
 * @param {string} list 形如 {@code "0,1"} 的逗号分隔编号
 * @returns {string} 如「主+子」；无法识别时原样返回
 */
function streamLabels(list) {
  const names = { 0: '主', 1: '子', 2: '子2' }
  const parts = String(list)
    .split(',')
    .map((s) => s.trim())
    .filter(Boolean)
  if (!parts.length) return '—'
  return parts.map((p) => names[p] || p).join('+')
}

const { pager, offset, reset, setTotal, onPageChange, onSizeChange } = usePagination({
  pageSize: 10,
  load: () => load()
})

async function load() {
  loading.value = true
  try {
    const params = { start: offset.value, limit: pager.size }
    if (query.q) params.q = query.q
    if (query.online !== null && query.online !== undefined) params.online = query.online
    // 设备筛选（从设备列表「查看通道」跳转而来，或由页面筛选保持）
    if (query.serial) params.serial = query.serial
    const data = await deviceApi.channelList(params)
    channels.value = (data?.channelList || []).map((c) => ({
      ...c,
      // 名称按需编辑的临时状态：_editing 控制是否显示输入框，
      // _savedName 记住后端值用于「值未变则不发请求」与失败回滚
      _editing: false,
      _savedName: c.customName || '',
      _draftName: c.customName || ''
    }))
    setTotal(data?.channelCount ?? channels.value.length)
  } catch (e) {
    ElMessage.error(e.message || '加载通道失败')
  } finally {
    loading.value = false
  }
}

/** 清除设备筛选（回到全部通道）。 */
function clearSerialFilter() {
  query.serial = ''
  search()
}

/** 条件变化后回到第一页再查，否则会停在越界页上看到空列表。 */
function search() {
  reset()
  load()
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

/**
 * 上屏到实时预览：跳转过去并带上 ?play=<设备编码>/<通道编码>，
 * 预览页挂载后会自动把这个通道铺到第一个空闲画面格。
 */
function playOnWall(row) {
  router.push({ path: '/live', query: { play: `${row.deviceId}/${row.id}` } })
}

/** 打开预览并自动播放。 */
async function preview(row) {
  previewTarget.value = row
  previewDialog.value = true
  // 用户主动点开一个通道才算新的一轮，重置卡轨重建计数
  stallRetry = 0
  await ensureMediaPort()
  await sleep(120)
  await startPlay()
}

/**
 * 「画面未就绪」自动重建的次数上限（每个通道一轮最多 2 次）。
 *
 * <p>重建 = 停会话 + 重新 INVITE，成本不低（设备要重新起推），
 * 而如果是设备自己坏了，重试多少次都没用。限定次数避免变成死循环。
 */
let stallRetry = 0

/**
 * 流是通的、却始终没有视频轨 → 重建整路流。
 *
 * <p><b>为什么必须停会话而不是重连播放器</b>：ZLM 等轨就绪超时后会把视频轨
 * <b>永久丢弃</b>（{@code Track not ready for a long time, ignored: H264}），
 * 这个决定绑定在那个媒体源上。重连 FLV 连的还是同一个媒体源，所以永远没画面。
 * 只有 {@code stream/stop} 把会话拆掉（同时清掉 ZLM 的媒体源），再重新
 * INVITE 让设备重推，才会生成一个全新的媒体源。
 *
 * <p>这也正好对应用户的土办法「关掉重播一下就好了」—— 只是现在由程序自己做。
 */
async function rebuildStalled() {
  const row = previewTarget.value
  if (!row || stallRetry >= 2) return
  stallRetry++
  ElMessage.warning('画面未就绪，正在重新拉流…')
  try {
    await streamApi.stop({ serial: row.deviceId, code: row.id })
  } catch (e) {
    // 会话可能已被空闲巡检关掉，继续重播即可
  }
  if (!previewDialog.value) return
  await startPlay()
}

async function startPlay() {
  const row = previewTarget.value
  if (!row) return
  const streamId = `${row.deviceId}/${row.id}`
  let info = null
  try {
    // 点播响应已包含全部播放地址
    info = await streamApi.start({ serial: row.deviceId, code: row.id })
  } catch (e) {
    try {
      info = await streamApi.info(streamId, true)
    } catch (e2) {
      ElMessage.error(e.message || '点播失败')
      return
    }
  }
  /*
   * 会话已建立，同步到行上，让操作列的「停止」立刻出现。
   *
   * <p>不写这一行的话，用户点「播放」后设备已经在推流，但列表里没有停止入口 ——
   * 只有刷新页面才看得到（列表数据来自 channellist，不轮询）。
   * 那条路上用户会以为「停不了」，只能去实时预览页找。
   */
  row.streamId = streamId
  const urls = resolvePlayUrls(info, mediaHttpPort.value)
  if (!hasPlayUrl(proto.value, urls)) {
    ElMessage.error('未获取到播放地址')
    return
  }
  await player.play(streamId, urls, {
    proto: proto.value,
    // 通了但始终没视频轨时，重建整路流（见 rebuidStalled 的说明）
    onStall: () => rebuildStalled(),
    protocolSwitch: {
      value: proto.value,
      // 控制栏按钮放短标签（FLV / HTTP-FLV / WebRTC / HLS），
      // 菜单项放完整名（含「HLS（无声音）」这类限制说明），像视频网站的清晰度菜单
      options: LIVE_PROTOCOLS.map((o) => ({
        value: o.value,
        label: o.label,
        short: LIVE_PROTOCOL_SHORT[o.value] || o.label
      })),
      onChange: (v) => {
        proto.value = v
        switchProto(v)
      }
    }
  })
}

/**
 * 切换播放方式（ws-flv ↔ WebRTC）。
 *
 * <p><b>拿不到播放地址时要重新点播，不能直接调 switchProtocol</b>：
 * 切协议时 {@code stop()} 会断开旧通路，这一瞬间 ZLM 的读者数归零，
 * 平台侧的空闲流巡检（45 秒无观看者即关流）可能刚好在此时执行把流关掉。
 * 之后再查 {@code stream/info} 就是 400，地址为空 ——
 * 而 {@code switchProtocol} 拿到空地址只会把状态置成「缺少播放地址」，
 * 播放器就停在那儿了（这就是用户看到的提示）。
 *
 * <p>所以这里一旦发现地址不齐，就重新走完整的 {@link startPlay}
 * （内部会重新 INVITE 拉起设备推流），而不是拿空地址去切。
 */
async function switchProto(next) {
  const row = previewTarget.value
  if (!row) return
  if (!playing.value) {
    // 未在播放时只记住选择，下次点播按它走
    proto.value = next || proto.value
    return
  }
  const streamId = `${row.deviceId}/${row.id}`
  let urls = null
  try {
    const info = await streamApi.info(streamId, true)
    urls = resolvePlayUrls(info, mediaHttpPort.value)
  } catch (e) {
    // 流可能已被空闲巡检关闭，下面重新点播
  }
  if (!urls || !hasPlayUrl(next || proto.value, urls)) {
    await startPlay()
    return
  }
  await player.switchProtocol(next || proto.value, urls)
}

/**
 * 关闭预览弹窗。
 *
 * <p><b>必须显式把 {@code previewDialog} 置 false</b>。早先这里只做了
 * 「停播放器 + 清目标」，底部那个「关闭」按钮就变成了一句空指令 ——
 * 点了既不关窗也不断流，看起来像按钮坏了。
 * （右上角 × 之所以能用，是因为 el-dialog 自己会 emit
 * {@code update:modelValue false}，走的是 v-model 那条路，跟这个函数无关。）
 *
 * <p>清 {@code previewTarget} 放到 {@link onPreviewClosed}：这里清掉的话，
 * 标题（依赖 target）会在关闭动画还没跑完时先变成空串，肉眼能看到闪一下。
 */
function closePreview() {
  previewDialog.value = false
  // 立即断流：不要等 300ms 的关闭动画，让 ZLM 早点释放这路拉流
  // 先退掉「CSS 全屏」兜底（切协议重建播放器时可能用上），否则它固定覆盖整屏
  player.exitCssFullscreen()
  player.stop()
}

/** 弹窗完全关闭（动画结束、DOM 销毁后）再清数据。 */
function onPreviewClosed() {
  // stop 已在 @close 里做过（幂等），这里只清掉标题依赖的数据
  previewTarget.value = null
}

/**
 * 修改通道的云台类型。
 *
 * <p>下拉菜单不是 v-model，值不会自动改到行对象上，
 * 所以成功后要显式写回，否则列表显示的还是旧值。
 * 失败时回滚，保证显示与后端一致。
 *
 * <p>另外后端已改为「目录同步时设备报 0 不覆盖」，所以这里改的值不会被设备
 * 下一次注册冲回未知。
 */
async function changePtzType(row, type) {
  const previous = row.ptzType
  ptzPending.add(row.id)
  try {
    await deviceApi.setChannelPtzType({
      serial: row.deviceId,
      code: row.id,
      ptz_type: type
    })
    row.ptzType = type
    ElMessage.success(`云台类型已改为「${PTZ_TYPES[type] ?? type}」`)
  } catch (e) {
    row.ptzType = previous
    ElMessage.error(e.message || '修改失败')
  } finally {
    ptzPending.delete(row.id)
  }
}

async function toggleCloudRecord(row, enable) {
  // v-model 已先行改值：失败时必须回滚，否则开关状态与后端不一致
  recordPending.add(row.id)
  try {
    // 参数名必须是 cloudrecord（后端 @RequestParam Boolean cloudrecord），
    // 传 enable 会被忽略并落到默认值 false，开关看似生效实则永远关着
    await deviceApi.setChannelCloudRecord({ serial: row.deviceId, code: row.id, cloudrecord: enable })
    // 【互斥：后端会自动关掉「按需直播」，这里必须同步本地状态】
    //
    // 否则用户看到的是「两个开关都显示开」，可实际库里是按需=关。
    // 不清同步就只能刷新页面才对得上（这是用户反馈的原问题）。
    const turnedOffOnDemand = enable && row.ondemand
    if (turnedOffOnDemand) {
      row.ondemand = false
    }
    ElMessage.success(turnedOffOnDemand
      ? '已开启云端录像，同时关闭按需直播（两者互斥）'
      : (enable ? '已开启云端录像' : '已关闭云端录像'))
  } catch (e) {
    row.cloudRecord = !enable
    ElMessage.error(e.message || '操作失败')
  } finally {
    recordPending.delete(row.id)
  }
}

function openRename(row) {
  renameTarget.value = row
  renameValue.value = row.customName || ''
  renameDialog.value = true
}

async function doRename() {
  const row = renameTarget.value
  if (!row) return
  try {
    await deviceApi.setChannelName({ serial: row.deviceId, code: row.id, name: renameValue.value })
    row.customName = renameValue.value
    renameDialog.value = false
    ElMessage.success('已保存')
  } catch (e) {
    ElMessage.error(e.message || '保存失败')
  }
}

/* ---------------- 名称按需编辑（对齐 LiveGBS：点名称即改） ---------------- */

/** 进入名称编辑态。 */
function startNameEdit(row) {
  if (row._editing) return
  row._editing = true
  row._draftName = row.customName || ''
  // 自动聚焦，Enter/Esc 才作用在输入框上（否则键盘事件落在页面里）
  nextTick(() => {
    const input = document.getElementById(nameInputId(row))
    input?.focus()
    input?.select()
  })
}

/** 名称输入框的 DOM id：通道编码跨设备可重复，必须带上设备编码。 */
function nameInputId(row) {
  return `ch-name-${row.deviceId}-${row.id}`
}

/** 放弃编辑，还原原值。 */
function cancelName(row) {
  row.customName = row._draftName || ''
  row._editing = false
}

/** 保存名称；值未变化时不发请求。 */
async function saveName(row) {
  if (!row._editing) return
  row._editing = false
  const next = (row.customName || '').trim()
  if (next === (row._savedName || '')) return
  const previous = row._savedName || ''
  row._savedName = next
  try {
    await deviceApi.setChannelName({ serial: row.deviceId, code: row.id, name: next })
    ElMessage.success('名称已保存')
  } catch (e) {
    row.customName = previous
    row._savedName = previous
    ElMessage.error(e.message || '保存失败')
  }
}

/* ---------------- 位置（经纬度） ---------------- */

/** 位置显示文案：两个都有效才显示，否则占位。 */
function positionText(row) {
  const lon = row.longitude
  const lat = row.latitude
  const hasLon = lon !== null && lon !== undefined && Number(lon) !== 0
  const hasLat = lat !== null && lat !== undefined && Number(lat) !== 0
  if (!hasLon && !hasLat) return '—'
  // 4 位小数（约 1 米精度），再长就塞不进列宽了
  return `${hasLon ? Number(lon).toFixed(4) : '-'},${hasLat ? Number(lat).toFixed(4) : '-'}`
}

/** 打开经纬度编辑弹窗。 */
function openPosition(row) {
  positionTarget.value = row
  positionLon.value = row.longitude ?? ''
  positionLat.value = row.latitude ?? ''
  positionDialog.value = true
}

/** 保存经纬度。 */
async function savePosition() {
  const row = positionTarget.value
  if (!row) return
  const lon = Number(positionLon.value)
  const lat = Number(positionLat.value)
  if (!Number.isFinite(lon) || !Number.isFinite(lat)) {
    ElMessage.warning('经纬度必须是数字')
    return
  }
  positionSaving.value = true
  try {
    await deviceApi.setChannelPosition({
      serial: row.deviceId,
      code: row.id,
      longitude: lon,
      latitude: lat
    })
    row.longitude = lon
    row.latitude = lat
    positionDialog.value = false
    ElMessage.success('位置已保存')
  } catch (e) {
    ElMessage.error(e.message || '保存失败')
  } finally {
    positionSaving.value = false
  }
}

/* ---------------- 按需直播 / 停止推流 ---------------- */

/**
 * 切换「按需直播」开关。
 *
 * <p><b>与云端录像互斥</b>：后端开按需时会自动关掉云端录像并归档，
 * 这里必须同步 row.cloudRecord，否则界面会显示「两个都开」——
 * 与实际库里状态不符，用户只能刷新页面才发现变了。
 */
async function toggleOndemand(row, enable) {
  ondemandPending.add(row.id)
  try {
    await deviceApi.setChannelOnDemand({
      serial: row.deviceId,
      code: row.id,
      ondemand: enable
    })
    // 互斥：开按需会被后端连带关掉云端录像
    const turnedOffRecord = enable && row.cloudRecord
    if (turnedOffRecord) {
      row.cloudRecord = false
    }
    ElMessage.success(turnedOffRecord
      ? '已开启按需直播，同时关闭云端录像（两者互斥）'
      : (enable ? '已开启按需直播' : '已关闭按需直播'))
  } catch (e) {
    row.ondemand = !enable
    ElMessage.error(e.message || '操作失败')
  } finally {
    ondemandPending.delete(row.id)
  }
}

/** 停止该通道的推流。 */
async function stopStream(row) {
  try {
    const res = await streamApi.stop({ serial: row.deviceId, code: row.id })
    // 无论后端有没有真的停掉会话，本行都不该再显示「停止」：
    // ok=false 表示本来就没有会话（例如被 45 秒空闲巡检关掉了，
    // 而列表数据不轮询，本地还以为在推流）。
    row.streamId = null
    if (res?.ok === false) {
      ElMessage.info('该通道当前没有推流')
    } else {
      ElMessage.success('已停止推流')
    }
  } catch (e) {
    ElMessage.error(e.message || '停止失败')
  }
}

/** 跳到「设备录像」页，并预填当前设备/通道。 */
function openDeviceRecord(row) {
  router.push({
    path: '/playback',
    query: { serial: row.deviceId, code: row.id }
  })
}

/** 打开所属设备的详情弹窗（状态记录 / 流量统计 / 移动轨迹 / 会话列表）。 */function openDeviceDetail(row) {
  // 弹窗内部按 device.id（设备编码）查日志，通道行的 id 是通道编码，
  // 直接透传会查不到数据，所以这里重组为设备对象
  detailTarget.value = {
    id: row.deviceId,
    name: row.deviceName,
    customName: row.deviceCustomName,
    status: row.deviceOnline ? 'ON' : 'OFF',
    endpoint: row.endpoint,
    streamIndex: row.streamIndex
  }
  detailVisible.value = true
}

function sleep(ms) {
  return new Promise((r) => setTimeout(r, ms))
}

onMounted(() => {
  // 支持 /channels?serial=<设备编码> 直接带筛选进入（设备列表「查看通道」入口）
  const serial = String(route.query.serial || '').trim()
  if (serial) query.serial = serial
  load()
})
onUnmounted(() => player.stop())
</script>

<style scoped>
/* 操作列：四个按钮必须排在一行，否则行高会翻倍 */
:deep(td.op-col .el-button + .el-button) {
  margin-left: 4px;
}

/*
 * 带提示的表头：文字 + 问号图标。
 *
 * 「按需直播」与「云端录像」互斥，但在表头只写四个字看不出来 ——
 * 用户会以为两个可以同时开，点了一个另一个自动关掉时还以为出 bug 了。
 * 加个问号让语义可发现（悬停显示完整说明）。
 */
.th-with-tip {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  cursor: help;
}

.th-with-tip .el-icon {
  font-size: 12px;
  color: var(--el-text-color-placeholder);
}

/* 国标编号：20 位等宽字符，用小一号字才塞得进 168px 的列 */
.code-sm {
  font-size: var(--fs-xs);
}

/* 云台类型：文字 + 箭头，点开是下拉菜单（与设备管理的流传输模式一致） */
.ptz-link {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  cursor: pointer;
  color: var(--accent-ink);
  font-size: var(--fs-sm);
  font-weight: 500;
  user-select: none;
}
.ptz-link:hover {
  text-decoration: underline;
}
.ptz-link.is-busy {
  opacity: 0.6;
  pointer-events: none;
}
.ptz-arrow {
  font-size: 11px;
  color: var(--text-tertiary);
}

/* 名称按需编辑：平时是文本，点击才换成输入框 */
.name-text {
  cursor: default;
  border-radius: 4px;
  padding: 1px 4px;
  margin: -1px -4px;
}
.name-text.is-editable {
  cursor: pointer;
  transition: background-color 0.15s;
}
.name-text.is-editable:hover {
  background-color: var(--el-fill-color-light);
  color: var(--el-color-primary);
}
.name-text.is-empty {
  color: var(--text-tertiary);
}
.name-input {
  width: 100%;
}
.name-input :deep(.el-input__wrapper) {
  padding: 0 6px;
}

/* 位置：可点击编辑经纬度 */
.position-text {
  cursor: pointer;
  border-radius: 4px;
  padding: 1px 4px;
  margin: -1px -4px;
  font-size: var(--fs-xs);
  font-variant-numeric: tabular-nums;
  color: var(--text-secondary);
  transition: background-color 0.15s;
}
.position-text:hover {
  background-color: var(--el-fill-color-light);
  color: var(--el-color-primary);
}

/* 在线状态 + 流量图标：整体可点开设备详情 */
.status-cell {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.badge.is-clickable {
  cursor: pointer;
}
.traffic-icon {
  color: var(--text-tertiary);
  cursor: pointer;
  transition: color 0.15s;
}
.traffic-icon:hover {
  color: var(--el-color-primary);
}

/* 所属设备：编码（等宽）+ 名称（灰），压成一行避免行高翻倍 */
.device-ref {
  display: inline-flex;
  align-items: baseline;
  gap: 8px;
  min-width: 0;
}
.device-ref .text-dim {
  font-size: var(--fs-xs);
}

/* 码流能力：设备上报的 StreamNumberList 摘要 */
.stream-list {
  font-size: var(--fs-xs);
  color: var(--text-secondary);
  font-variant-numeric: tabular-nums;
}
</style>

<style scoped>
.preview-wrap {
  display: grid;
  /*
   * 视频栏吃掉剩余宽度，云台面板固定 340px。
   *
   * 参考站是 303px，但它的云台面板只有方向盘 + 变倍 + 信息表；
   * 我们多了 4 个折叠的运维功能（预置位/设备功能/高级操作/语音喊话），
   * 320px 时标题文字与展开箭头离得很近，所以放宽到 340px。
   */
  grid-template-columns: minmax(0, 1fr) 340px;
  gap: 24px;
  /*
   * 两栏等高，且行高必须<b>只由左栏（视频）决定</b>。
   *
   * <p>grid 的行高默认取「两栏内容高度的较大值」。右栏是云台面板，
   * 内容高度随预置位数量变化（3 个预置位就能到 641px，比 16:9 的视频
   * 474px 高出一截）—— 一旦它成为较大值，行高就被拉高，视频按自己的
   * 16:9 停在顶部，<b>下方凭空多出 167px 空白</b>，视觉上像播放器坏了。
   *
   * <p>只靠 {@code .preview-side} 的 {@code overflow-y: auto} 解决不了：
   * 容器高度本身来自自己的内容时，{@code scrollHeight == clientHeight}，
   * 滚动条永远不会启用（实测就是这样，右栏从没真正滚动过）。
   * 真正去掉它对行高的贡献靠下面右栏的 {@code contain: size}。
   *
   * <p>{@code stretch} 仍要保留：行高定下来之后，右栏要拉伸到那个高度，
   * 它内部的 overflow 才有意义（否则右栏只有内容高，滚动同样不生效）。
   */
  align-items: stretch;
  /*
   * 视频的最大高度。视口太矮时靠它收缩，避免画面顶到弹窗底部。
   * 只通过下面的 max-width 反推，不直接约束 width，所以不会压扁比例。
   */
  --video-max-h: 62vh;
}

/*
 * 左栏是「舞台」：宽度交给视频自己决定，这里只负责居中。
 *
 * 【为什么不再固定 .preview-wrap 的高度】
 * 之前给 wrap 设了 height: 74vh（视口 900 时 666px），而视频按栏宽 874
 * 算出来只需要 492px —— 上下各空 87px，凭空多出 174px 空白，
 * 右侧云台面板又被这个虚高的行高撑开、内容溢出被裁掉。
 * 现在改成「高度由视频宽度决定」，两栏自然等高，没有任何多余留白。
 *
 * 【align-items: flex-start 不能省】
 * flex 容器默认 align-items: stretch，会把 .video-box 拉伸到容器高度。
 * 而容器高度又来自 grid 的 stretch（跟 wrap 等高）—— 结果是视频被拉成
 * 874×678（比例 1.29，画面变形），aspect-ratio 完全失效。
 * 改成 flex-start 后 video-box 保持自己的 16:9，容器高度也跟着它走。
 */
.preview-stage {
  min-width: 0;
  display: flex;
  justify-content: center;
  align-items: flex-start;
}

/*
 * 同时写 width / max-width / aspect-ratio 是这里的关键：
 *   - 栏宽够大时：宽度取 100%，高度 = 宽度 × 9/16；
 *   - 视口太矮时：宽度被 max-width 夹住（= --video-max-h × 16/9），
 *     高度随之收缩，比例保持 16:9。
 *
 * 注意不能用 max-height 代替 max-width —— max-height 不参与宽高比反算，
 * 只会把高度硬压下去，画面就变形了（这正是上一版踩过的坑）。
 */
.preview-stage .video-box {
  width: 100%;
  max-width: calc(var(--video-max-h) * 16 / 9);
  aspect-ratio: 16 / 9;
}

/*
 * 右栏：宽度固定，高度跟着视频走（grid 拉伸到行高），内容超出就在栏内滚动。
 *
 * 【contain: size 是这里的关键】
 * 它告诉浏览器「这个元素的尺寸不参考自身内容」。于是右栏对网格行高的
 * 贡献为 0，行高只剩左栏视频那一段：
 *   无 contain 时：右栏内容 641px → 行高 641px，视频 474px，下方空 167px；
 *   有 contain 时：行高 474px → 右栏拉成 474px，内部滚动 641-474=167px。
 *
 * 这正是「预置位多了以后播放器下面出现大空白」的修法 —— 预置位是从
 * 设备实时查回来的，数量不可控（3 个就够把右栏顶到 641px），
 * 所以不能指望它「比视频矮」。
 * 注意 {@code contain: size} 必须配 {@code overflow-y: auto} 才有意义，
 * 否则内容直接被裁掉且无法访问。窄视口下面会重置掉它（那里是单列堆叠）。
 */
.preview-side {
  border-left: 1px solid var(--border-color);
  padding-left: 18px;
  min-height: 0;
  contain: size;
  overflow-y: auto;
  /* 细滚动条，避免在 340px 的栏里显得笨重 */
  scrollbar-width: thin;
}

/*
 * 窄视口：改为单列堆叠，视频回到「宽 100% + 16:9」，
 * 右栏不再限高，按内容自然高度铺开。
 */
@media (max-width: 900px) {
  .preview-wrap {
    grid-template-columns: minmax(0, 1fr);
  }

  .preview-stage {
    display: block;
  }

  .preview-stage .video-box {
    max-width: none;
  }

  .preview-side {
    border-left: none;
    padding-left: 0;
    border-top: 1px solid var(--border-color);
    padding-top: 14px;
    /*
     * 单列堆叠时高度不再由视频约束，必须撤掉 contain：
     * 否则右栏尺寸被当成 0，面板内容会被裁掉且滚不出来。
     */
    contain: none;
    overflow-y: visible;
  }
}

/*
 * 状态行：状态标签 + 播放方式下拉。
 *
 * 【为什么要显式给高度和 padding】
 * el-tag（24px）与 el-select（32px）高度不同，只写 align-items: center
 * 时两者在行内居中但整行高度随内容跳动，和下面的内容贴得很紧。
 * 这里固定 32px 行高（与 select 一致）、上下各留 6px，
 * 标签和下拉框就在同一条水平线上，也与下方分隔线拉开距离。
 */
.preview-status {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  min-height: 32px;
  padding: 6px 0 12px;
}

/* 状态标签：固定宽度，避免文案变化（播放中 / 容错重建…）时左右抖动 */
.status-tag {
  flex: 0 0 auto;
  min-width: 76px;
  justify-content: center;
}

/*
 * 播放方式下拉：吃掉剩余宽度。
 * 右侧留 2px 补偿滚动条（scrollbar-width: thin 时约 8px 中已含在 padding 里），
 * 避免下拉框贴着滚动条边缘。
 */
.proto-select {
  flex: 1;
  min-width: 0;
  margin-right: 2px;
}

/* 轻量分隔线：比 el-divider 的上下 24px 紧凑得多 */
.preview-sep {
  height: 1px;
  background: var(--border-color);
  margin-bottom: 14px;
}

/*
 * 云台面板在预览弹窗里空间有限：把共享组件默认的 16px 间距收一点。
 * （只影响本弹窗，LiveView 的右侧面板保持原样。）
 *
 * 取 18px 而不是更小的值：右栏高度跟视频齐平（约 474px），
 * 折叠项都收起时内容约 415px，底部本来就留给展开折叠项；
 * 间距再压下去反而会显得元素粘在一起。
 */
.preview-side :deep(.ptz-panel) {
  gap: 18px;
}
</style>

<!--
  预览弹窗的内边距必须放在非 scoped 样式块里。

  el-dialog 会把 DOM teleport 到 <body> 下，元素上没有本组件的
  data-v-xxx 标记，scoped 样式里的 `.preview-dialog :deep(...)` 匹配不到，
  写了也不生效（实测弹窗底部仍留着约 90px 空白）。

  选择器带上 .preview-dialog 前缀，只影响这个弹窗，不会污染其它对话框。
-->
<style>
/*
 * Element Plus 默认给 header / body / footer 各留 16~20px，
 * 三者叠起来在视频下方形成一条明显的空白带 —— 视频再大也填不满。
 * 这里把上下留白收到只够放标题和按钮的程度。
 */
.preview-dialog .el-dialog__header {
  padding: 14px 20px 10px;
  margin-right: 0;
}

.preview-dialog .el-dialog__body {
  padding: 0 20px;
}

.preview-dialog .el-dialog__footer {
  padding: 8px 20px 12px;
}

/* 弹窗标题：比默认 18px 收一点，与紧凑布局更协调 */
.preview-dialog .el-dialog__title {
  font-size: 15px;
  font-weight: 600;
}
</style>
