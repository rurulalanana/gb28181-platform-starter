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
            <el-button
              link
              type="primary"
              :disabled="!row.streamId"
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
    <el-dialog
      v-model="previewDialog"
      :title="'实时预览 · ' + (previewTarget?.customName || previewTarget?.name || previewTarget?.id || '')"
      width="880px"
      top="6vh"
      @close="closePreview"
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
          <div class="preview-status">
            <el-tag :type="stateType" size="small">{{ state }}</el-tag>
            <el-select v-model="proto" size="small" style="width: 160px" @change="switchProto">
              <el-option label="WebSocket-FLV" value="wsflv" />
              <el-option label="WebRTC（低延迟）" value="webrtc" />
            </el-select>
          </div>
          <el-divider />
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
import { resolvePlayUrls } from '@/player/resolvePlayUrls'
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
  await ensureMediaPort()
  await sleep(120)
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
  const urls = resolvePlayUrls(info, mediaHttpPort.value)
  if (!urls.wsFlv && !urls.webrtc) {
    ElMessage.error('未获取到播放地址')
    return
  }
  await player.play(streamId, urls, { proto: proto.value })
}

async function switchProto() {
  const row = previewTarget.value
  if (!row || !playing.value) return
  const streamId = `${row.deviceId}/${row.id}`
  let urls = {}
  try {
    const info = await streamApi.info(streamId, true)
    urls = resolvePlayUrls(info, mediaHttpPort.value)
  } catch (e) {
    // 忽略
  }
  await player.switchProtocol(proto.value, urls)
}

function closePreview() {
  player.stop()
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
    ElMessage.success(enable ? '已开启云端录像' : '已关闭云端录像')
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

/** 切换「按需直播」开关。 */
async function toggleOndemand(row, enable) {
  ondemandPending.add(row.id)
  try {
    await deviceApi.setChannelOnDemand({
      serial: row.deviceId,
      code: row.id,
      ondemand: enable
    })
    ElMessage.success(enable ? '已开启按需直播' : '已关闭按需直播')
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
    await streamApi.stop({ serial: row.deviceId, code: row.id })
    row.streamId = null
    ElMessage.success('已停止推流')
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

.preview-wrap {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 300px;
  gap: 20px;
  /*
   * 本行高度的唯一持有者。
   *
   * 之前左栏高度由宽度决定（.video-box 的 aspect-ratio: 16/9），
   * 右栏由视口决定（max-height: 62vh），两个来源互不相关 ——
   * 1080p 下左栏约 293px、右栏上限约 670px，差近 380px，永远对不上。
   * 现在两栏都拉伸到这里的高度，视频改由栏内居中满足 16:9。
   */
  height: 62vh;
}

/*
 * 左栏是「舞台」：撑满行高，内部把 16:9 的视频按 contain 方式居中。
 *
 * container-type: size 让下面能用 cqw/cqh 表达「舞台自身的宽高」。
 * 纯百分比做不到：只写 height:100% + width:auto 时宽度由 16:9 推出，
 * 超出栏宽被 max-width 夹取后浏览器不会回算高度，画面会被压扁
 * （实测 528×558 的栏里视频变成 0.946 而非 1.778）；
 * 只写 width:100% + max-height 则在「宽而矮」的栏里同样压扁。
 * 用 min(100cqw, 100cqh*16/9) 一次选出受限的那一边，两种情形都正确。
 */
.preview-stage {
  height: 100%;
  min-height: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  container-type: size;
}

.preview-stage .video-box {
  width: min(100cqw, calc(100cqh * 16 / 9));
  aspect-ratio: 16 / 9;
}

.preview-side {
  border-left: 1px solid var(--border-color);
  padding-left: 18px;
  height: 100%;
  overflow-y: auto;
}

/*
 * 窄视口：改为单列堆叠，行高不再由 .preview-wrap 持有（height: auto），
 * 因此两栏都要退回按内容自然高度 —— 否则 height: 100% 在 auto 高度的父级里
 * 会塌成 0，视频和云台面板都会消失。此时舞台也不需要容器尺寸，
 * 直接用全局 .video-box 的「宽 100% + 16:9」。
 */
@media (max-width: 900px) {
  .preview-wrap {
    grid-template-columns: minmax(0, 1fr);
    height: auto;
  }

  .preview-stage {
    height: auto;
    display: block;
    container-type: normal;
  }

  .preview-stage .video-box {
    width: 100%;
  }

  .preview-side {
    height: auto;
    border-left: none;
    padding-left: 0;
    border-top: 1px solid var(--border-color);
    padding-top: 14px;
  }
}

.preview-status {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 4px;
}
</style>
