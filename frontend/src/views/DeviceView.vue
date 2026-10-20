<template>
  <div class="page is-fill">

    <!--
      工具条：筛选条件在左，操作按钮在右，一行放完。
      早先分成「页头放刷新/导出」+「下面一条筛选栏」两行，
      白白多占一行竖向空间，且页头与筛选栏离得远，操作与条件不在同一视觉区。
    -->
    <div class="filter-bar">
      <el-input
        v-model="query.q"
        placeholder="设备编码 / 名称"
        clearable
        style="width: 240px"
        @keyup.enter="search"
      >
        <template #prefix><el-icon><Search /></el-icon></template>
      </el-input>
      <el-select v-model="query.online" placeholder="全部状态" clearable style="width: 128px">
        <el-option label="在线" :value="true" />
        <el-option label="离线" :value="false" />
      </el-select>
      <el-button type="primary" @click="search">
        <el-icon><Search /></el-icon> 查询
      </el-button>
      <el-button @click="resetQuery">重置</el-button>
      <span class="filter-bar-spacer" />
      <el-button size="small" :loading="loading" @click="load">
        <el-icon><Refresh /></el-icon> 刷新
      </el-button>
      <el-button size="small" title="导出设备列表（CSV）" @click="exportCsv">
        <el-icon><Download /></el-icon> 导出
      </el-button>
    </div>

    <el-card shadow="never">

      <!--
        table-layout="fixed"：列宽严格按 width/min-width 分配，不再随内容重算。
        默认的 fit 布局会测量每列内容最大宽度，行内编辑的 input 一出现
        （或某个操作按钮加载态变化），列宽就会重排，整张表跟着跳。
      -->
      <el-table v-loading="loading" :data="devices" stripe table-layout="fixed">
        <el-table-column prop="id" label="设备国标编号" width="180" fixed="left" sortable show-overflow-tooltip>
          <template #default="{ row }"><span class="mono">{{ row.id }}</span></template>
        </el-table-column>
        <el-table-column
          label="名称"
          width="132"
          :sort-by="(r) => r.customName || r.name || ''"
          sortable
          show-overflow-tooltip
        >
          <template #default="{ row }">
            <!--
              按需编辑：常驻输入框会在每行渲染「点击修改」占位符，一屏几十行全是灰字，很脏。
              改为平时显示文本、点击才换成输入框。
            -->
            <el-input
              v-if="row._editing"
              v-model="row.customName"
              size="small"
              class="name-input"
              @keyup.enter="saveName(row)"
              @keyup.esc="cancelName(row)"
              @blur="saveName(row)"
            />
            <span
              v-else
              class="name-text is-editable"
              :class="{ 'is-empty': !row.customName }"
              title="点击修改名称"
              @click="startNameEdit(row)"
            >{{ row.customName || row.name || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="流传输模式" width="112" align="center">
          <template #default="{ row }">
            <!--
              用 el-dropdown（文字 + 箭头）而非 el-select：
              select 在表格里是个带边框的输入框，一屏几十行全是灰框，很脏；
              文字下拉既清爽又保留了切换能力。

              【坑】el-dropdown 在表格里多行复用时，@command 只回传被选项的值，
              不知道自己属于哪一行。实测点第 5 行的菜单，改的却是第 1 行。
              解决：命令值带上设备编码（`设备编码|模式标识`），
              由 handleTransportCommand 拆开，自己去对应行上应用。
            -->
            <el-dropdown
              trigger="click"
              :disabled="transportPending.has(row.id)"
              @command="handleTransportCommand"
            >
              <span class="transport-link" :class="{ 'is-busy': transportPending.has(row.id) }">
                {{ transportText(row) }}
                <el-icon class="transport-arrow"><ArrowDown /></el-icon>
              </span>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item
                    v-for="opt in TRANSPORT_OPTIONS"
                    :key="opt.value"
                    :command="row.id + '|' + opt.value"
                    :disabled="opt.value === transportLabel(row)"
                  >
                    {{ opt.label }}
                  </el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
          </template>
        </el-table-column>
        <!-- 通道数不排序：数字排序用处不大，而排序箭头会把「通道数」表头挤到换行 -->
        <el-table-column prop="channelCount" label="通道数" width="88" align="center">
          <template #default="{ row }">
            <el-link v-if="row.channelCount" type="primary" class="count-link" @click="viewChannels(row)">
              {{ row.channelCount }}
            </el-link>
            <span v-else class="text-dim">0</span>
          </template>
        </el-table-column>

        <el-table-column label="在线状态" width="116" align="center">
          <template #default="{ row }">
            <div class="status-cell">
              <span
                class="badge is-clickable"
                :class="row.online ? 'is-online' : 'is-offline'"
                title="点击查看设备详情"
                @click="openDetail(row)"
              >{{ row.online ? '在线' : '离线' }}</span>
              <el-icon class="traffic-icon" title="点击查看设备详情" @click="openDetail(row)">
                <Histogram />
              </el-icon>
            </div>
          </template>
        </el-table-column>

        <el-table-column label="出口 IP" min-width="186" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="mono" :class="{ 'text-dim': !row.endpoint }">{{ row.endpoint || '—' }}</span>
          </template>
        </el-table-column>

        <el-table-column label="最近心跳" width="168" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="text-muted">{{ row.lastKeepaliveAt || '—' }}</span>
          </template>
        </el-table-column>

        <el-table-column label="操作" width="232" align="center" fixed="right">
          <template #default="{ row }">
            <!-- 「更新通道」仅在线设备可用；「删除」仅离线设备可用（防误删在线设备）。 -->
            <div class="row-actions">
              <el-button link type="primary" @click="viewChannels(row)">查看通道</el-button>
              <el-button
                v-if="row.online"
                link
                class="act-collect"
                :loading="busy === row.id"
                @click="collect(row)"
              >更新通道</el-button>
              <el-button link class="act-edit" @click="openEdit(row)">编辑</el-button>
              <el-button v-if="!row.online" link type="danger" @click="remove(row)">删除</el-button>
            </div>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty :image-size="76" :description="`暂无设备。请确认设备已注册到平台（SIP ${serverSip}）`" />
        </template>
      </el-table>

      <TablePager
        :pager="pager"
        unit="台设备"
        @page-change="onPageChange"
        @size-change="onSizeChange"
      />
    </el-card>

    <!-- 编辑设备 -->
    <el-dialog v-model="editDialog" title="编辑设备" width="820px">
      <el-form :model="form" label-width="130px" class="edit-form">
        <el-row :gutter="20">
          <el-col :span="12">
            <el-form-item label="设备编号">
              <el-input :model-value="editTarget?.id" disabled />
            </el-form-item>
            <el-form-item label="自定义名称">
              <el-input v-model="form.customName" placeholder="留空则用设备上报名称" />
            </el-form-item>
            <el-form-item label="流传输模式">
              <el-select v-model="form.transportMode" style="width: 100%">
                <el-option v-for="opt in TRANSPORT_OPTIONS" :key="opt.value" :label="opt.label" :value="opt.value" />
              </el-select>
            </el-form-item>
            <el-form-item label="码流索引">
              <el-select v-model="form.streamIndex" style="width: 100%">
                <el-option
                  v-for="opt in STREAM_INDEX_OPTIONS"
                  :key="opt.value"
                  :label="opt.label"
                  :value="opt.value"
                >
                  <span>{{ opt.label }}</span>
                  <span class="stream-index-hint">{{ opt.hint }}</span>
                </el-option>
              </el-select>
              <div class="form-item-hint">{{ STREAM_INDEX_HINT }}</div>
            </el-form-item>
            <el-form-item label="字符集">
              <el-select v-model="form.charset" placeholder="默认" clearable style="width: 100%">
                <el-option label="GB2312" value="GB2312" />
                <el-option label="GBK" value="GBK" />
                <el-option label="UTF-8" value="UTF-8" />
              </el-select>
            </el-form-item>
            <el-form-item label="SIP 密码">
              <el-input v-model="form.password" placeholder="仅平台侧留存，不参与注册鉴权" show-password />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="目录抓取周期(秒)">
              <el-input-number v-model="form.catalogInterval" :min="0" :max="86400" controls-position="right" style="width: 100%" />
            </el-form-item>
            <el-form-item label="订阅周期(秒)">
              <el-input-number v-model="form.subscribeInterval" :min="0" :max="86400" controls-position="right" style="width: 100%" />
            </el-form-item>
            <el-form-item label="订阅项目">
              <el-checkbox v-model="form.catalogSubscribe">目录订阅</el-checkbox>
              <el-checkbox v-model="form.alarmSubscribe">报警订阅</el-checkbox>
              <el-checkbox v-model="form.positionSubscribe">位置订阅</el-checkbox>
            </el-form-item>
            <el-form-item label="设备录像">
              <el-checkbox v-model="form.recordCenter">中心检索</el-checkbox>
            </el-form-item>
          </el-col>
        </el-row>
      </el-form>
      <template #footer>
        <el-button @click="editDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="doSave">确定</el-button>
      </template>
    </el-dialog>

    <!-- 设备详情：状态记录 / 流量统计 / 移动轨迹 / 会话列表 -->
    <DeviceDetailDialog v-model:visible="detailVisible" :device="detailTarget" />
  </div>
</template>

<script setup>
import { computed, nextTick, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useRouter } from 'vue-router'
import { deviceApi } from '@/api'
import { useAuthStore } from '@/stores/auth'
import { usePagination } from '@/composables/usePagination'
import TablePager from '@/components/TablePager.vue'
import DeviceDetailDialog from '@/components/DeviceDetailDialog.vue'

const auth = useAuthStore()
const router = useRouter()

const devices = ref([])
const loading = ref(false)
const busy = ref('')
const saving = ref(false)
const query = reactive({ q: '', online: null })

/** 重置筛选条件并重新查询。 */
function resetQuery() {
  query.q = ''
  query.online = null
  search()
}

/** 正在保存名称的设备编号集合（防重复提交）。 */
const namePending = reactive(new Set())

/** 正在切换流传输模式的设备编号集合。 */
const transportPending = reactive(new Set())

const editDialog = ref(false)
const editTarget = ref(null)
const form = reactive({
  customName: '',
  /** 流传输模式标识，取值同 TRANSPORT_OPTIONS 的 value。 */
  transportMode: 'UDP',
  /** 码流索引，取值同 STREAM_INDEX_OPTIONS 的 value。 */
  streamIndex: 'auto',
  charset: '',
  catalogInterval: 0,
  subscribeInterval: 0,
  catalogSubscribe: false,
  alarmSubscribe: false,
  positionSubscribe: false,
  recordCenter: false,
  password: ''
})

const serverSip = computed(() => auth.serverInfo?.sip || '—')

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
    const data = await deviceApi.list(params)
    devices.value = (data?.deviceList || []).map((d) => ({
      ...d,
      // 记录上次已保存的值，用于判断是否真的改过
      _savedName: d.customName || '',
      // 编辑态：仅点击名称时才置 true，避免常驻输入框
      _editing: false,
      _draftName: d.customName || ''
    }))
    setTotal(data?.deviceCount ?? devices.value.length)
  } catch (e) {
    ElMessage.error(e.message || '加载设备失败')
  } finally {
    loading.value = false
  }
}

/** 条件变化后回到第一页再查，否则会停在越界页上看到空列表。 */
function search() {
  reset()
  load()
}

/** 查看该设备的通道（跳转通道管理页并带上设备筛选）。 */
function viewChannels(row) {
  router.push({ path: '/channels', query: { serial: row.id } })
}

/**
 * 码流索引选项。
 *
 * <p>GB/T 28181—2022 附录 G：SSRC 的第 7~10 位是码流编号，
 * 0 表示主码流、1 表示子码流。「自动」保持原有行为（域内自增序号）。
 */
const STREAM_INDEX_OPTIONS = [
  { value: 'auto', label: '自动选择', hint: '由设备决定推哪一路' },
  { value: 'streamnumber:0', label: '主码流', hint: '2022 标准，SSRC 后 4 位填 0' },
  { value: 'streamnumber:1', label: '子码流', hint: '2022 标准，SSRC 后 4 位填 1' }
]

const STREAM_INDEX_HINT = '指定后点播只拉该路码流；子码流分辨率低但省带宽'

/**
 * 流传输模式选项。
 *
 * <p>TCP 分被动/主动：被动收流由平台主动发 INVITE，设备回推；
 * 主动推流则设备建流推给平台。模式会写进 SIP 协商，填错会协商失败。
 */
const TRANSPORT_OPTIONS = [
  { value: 'UDP', label: 'UDP', transport: 'UDP', mode: 'passive' },
  { value: 'TCP', label: 'TCP 被动', transport: 'TCP', mode: 'passive' },
  { value: 'TCP_ACTIVE', label: 'TCP 主动', transport: 'TCP', mode: 'active' }
]

/** 设备当前的流传输模式标识。 */
function transportLabel(row) {
  const transport = (row.mediaTransport || 'UDP').toUpperCase()
  if (transport !== 'TCP') return 'UDP'
  return row.mediaTransportMode === 'active' ? 'TCP_ACTIVE' : 'TCP'
}

/** 设备当前的流传输模式显示文案。 */
function transportText(row) {
  return TRANSPORT_OPTIONS.find((o) => o.value === transportLabel(row))?.label || 'UDP'
}

/**
 * 处理流传输下拉的命令。
 *
 * <p>命令值格式为 {@code 设备编码|模式标识}。
 * 必须自带设备编码：表格里多行共用 el-dropdown，命令本身不携带行信息。
 */
function handleTransportCommand(cmd) {
  const sep = String(cmd).indexOf('|')
  if (sep < 0) return
  const deviceId = cmd.slice(0, sep)
  const mode = cmd.slice(sep + 1)
  const row = devices.value.find((d) => d.id === deviceId)
  if (!row) return
  changeTransport(row, mode)
}

/** 切换流传输模式（只影响后续点播发流方式）。 */
async function changeTransport(row, value) {
  const opt = TRANSPORT_OPTIONS.find((o) => o.value === value)
  if (!opt) return
  const old = transportLabel(row)
  if (value === old) return
  transportPending.add(row.id)
  try {
    await deviceApi.setMediaTransport(row.id, opt.transport, opt.mode)
    row.mediaTransport = opt.transport
    row.mediaTransportMode = opt.mode
    ElMessage.success(`已切换为 ${opt.label}（对下次点播生效）`)
  } catch (e) {
    ElMessage.error(e.message || '切换失败')
  } finally {
    transportPending.delete(row.id)
  }
}

/* ---------------- 设备详情弹窗 ---------------- */

const detailVisible = ref(false)
const detailTarget = ref(null)

/** 打开设备详情（状态记录 / 流量统计 / 移动轨迹 / 会话列表）。 */
function openDetail(row) {
  detailTarget.value = row
  detailVisible.value = true
}

/* ---------------- 导出设备列表（CSV） ---------------- */

async function exportCsv() {
  try {
    // limit=0 不分页，拉全量后导出
    const data = await deviceApi.list({ start: 0, limit: 0 })
    const list = data?.deviceList || []
    if (!list.length) {
      ElMessage.warning('没有可导出的设备')
      return
    }
    const header = ['设备国标编号', '名称', '厂商', '型号', '在线状态', '流传输', 'IP 地址', '通道数', '最后注册']
    const rows = list.map((d) => [
      d.id,
      d.customName || d.name || '',
      d.manufacturer || '',
      d.model || '',
      d.online ? '在线' : '离线',
      d.mediaTransport || 'UDP',
      d.remoteIp || '',
      d.channelCount ?? 0,
      d.lastRegisterAt || ''
    ])
    const csv = '\uFEFF' + [header, ...rows]
      .map((r) => r.map((v) => `"${String(v).replace(/"/g, '""')}"`).join(','))
      .join('\r\n')
    const blob = new Blob([csv], { type: 'text/csv;charset=utf-8' })
    const a = document.createElement('a')
    a.href = URL.createObjectURL(blob)
    a.download = `设备列表-${new Date().toISOString().slice(0, 10)}.csv`
    a.click()
    URL.revokeObjectURL(a.href)
    ElMessage.success(`已导出 ${list.length} 台设备`)
  } catch (e) {
    ElMessage.error(e.message || '导出失败')
  }
}

async function collect(row) {
  busy.value = row.id
  try {
    await deviceApi.collect(row.id)
    ElMessage.success(`已下发采集：${row.id}`)
    setTimeout(load, 2000)
  } catch (e) {
    ElMessage.error(e.message || '采集失败')
  } finally {
    busy.value = ''
  }
}

/** 进入名称编辑态。 */
function startNameEdit(row) {
  if (row._editing) return
  row._editing = true
  row._draftName = row.customName || ''
  nextTick(() => {
    const input = document.querySelector('.name-input input')
    if (input) input.focus()
  })
}

/** 放弃编辑，还原原值。 */
function cancelName(row) {
  row.customName = row._draftName || ''
  row._editing = false
}

/**
 * 行内保存名称。
 *
 * <p>值未变化时不发请求：否则点一下别处就会打一次接口。
 */
async function saveName(row) {
  if (!row._editing) {
    return
  }
  row._editing = false
  const next = (row.customName || '').trim()
  if (next === (row._savedName || '')) {
    return
  }
  namePending.add(row.id)
  try {
    await deviceApi.setName({ serial: row.id, custom_name: next })
    row._savedName = next
    row.customName = next
    ElMessage.success('名称已保存')
  } catch (e) {
    // 回滚到上次成功的值，避免界面显示与后端不一致
    row.customName = row._savedName || ''
    ElMessage.error(e.message || '保存失败')
  } finally {
    namePending.delete(row.id)
  }
}

function openEdit(row) {
  editTarget.value = row
  form.customName = row.customName || ''
  form.transportMode = transportLabel(row)
  form.streamIndex = row.streamIndex || 'auto'
  form.charset = row.charset || ''
  form.catalogInterval = row.catalogInterval ?? 0
  form.subscribeInterval = row.subscribeInterval ?? 0
  form.catalogSubscribe = !!row.catalogSubscribe
  form.alarmSubscribe = !!row.alarmSubscribe
  form.positionSubscribe = !!row.positionSubscribe
  form.recordCenter = !!row.recordCenter
  form.password = ''
  editDialog.value = true
}

async function doSave() {
  if (!editTarget.value) return
  saving.value = true
  try {
    const payload = { serial: editTarget.value.id, custom_name: form.customName }
    // 后端对 null 是「不改」，传空串会真的清空，所以可选字段只在有值时下发
    const transport = TRANSPORT_OPTIONS.find((o) => o.value === form.transportMode)
    if (transport) {
      payload.media_transport = transport.transport
      payload.media_transport_mode = transport.mode
    }
    if (form.streamIndex) payload.stream_index = form.streamIndex
    if (form.charset) payload.charset = form.charset
    payload.catalog_interval = form.catalogInterval ?? 0
    payload.subscribe_interval = form.subscribeInterval ?? 0
    payload.catalog_subscribe = form.catalogSubscribe
    payload.alarm_subscribe = form.alarmSubscribe
    payload.position_subscribe = form.positionSubscribe
    payload.record_center = form.recordCenter
    if (form.password) payload.password = form.password
    await deviceApi.setInfo(payload)
    ElMessage.success('已保存')
    editDialog.value = false
    load()
  } catch (e) {
    ElMessage.error(e.message || '保存失败')
  } finally {
    saving.value = false
  }
}

async function remove(row) {
  try {
    await ElMessageBox.confirm(
      `确定删除设备 ${row.id}？该操作会同时删除其通道数据。`,
      '删除确认',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
  } catch (e) {
    return
  }
  try {
    await deviceApi.remove(row.id)
    ElMessage.success('已删除')
    load()
  } catch (e) {
    ElMessage.error(e.message || '删除失败')
  }
}

onMounted(load)
</script>

<style scoped>
/* .card-header / .search-form / .empty-cell 由 styles/main.css 统一提供 */
.edit-form :deep(.el-form-item) {
  margin-bottom: 18px;
}

/* 操作组：文字按钮紧凑排列，编辑按钮用警示橙（对齐 LiveGBS 的操作语义色） */
.row-actions {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  white-space: nowrap;
}
.row-actions .el-button + .el-button {
  margin-left: 0;
}
.row-actions .act-edit {
  color: var(--warning-500, #e6a23c);
}
.row-actions .act-edit:hover {
  color: var(--warning-600, #d48806);
}

/*
  「更新通道」按钮在 loading 时，Element 会把文字换成 spinner 图标，
  按钮宽度随之变窄 —— 行内其它内容被挤动，整行跟着抖动。
  这里给一个固定宽度，文字与图标都在其中居中。
*/
.row-actions .act-collect {
  width: 62px;
  justify-content: center;
}

/* 状态列：可点击的状态徽标 + 流量图标 */
.status-cell {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.status-cell .is-clickable {
  cursor: pointer;
}
.traffic-icon {
  font-size: var(--fs-sm);
  color: var(--ink-400, #909399);
  cursor: pointer;
}
.traffic-icon:hover {
  color: var(--brand-500, #2f5c94);
}

/* 弹窗底部说明 */
.dialog-foot-tip {
  margin-top: 8px;
  font-size: var(--fs-xs);
  color: var(--ink-400, #909399);
}

/* 行内名称：平时是文本，编辑时才换成输入框 */
.name-text.is-editable {
  cursor: pointer;
  border-bottom: 1px dashed transparent;
  transition: border-color 0.15s, color 0.15s;
  /* 单行显示：名称过长时靠 ellipsis 截断，绝不换行撑高行 */
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.name-text.is-editable:hover {
  color: var(--brand-500, #2f5c94);
  border-bottom-color: var(--brand-300, #93b4e3);
}
.name-text.is-empty {
  color: var(--text-tertiary, #a8abb2);
}

/*
  编辑态的输入框必须严格限制在单元格内：
  el-input 默认 min-width 会把列撑宽，表格（fit 布局）随之重排。
  table-layout="fixed" 之外再加这层约束，双保险。
*/
.name-input {
  width: 100%;
  min-width: 0;
}

.name-input :deep(.el-input__wrapper) {
  padding: 0 8px;
  min-height: 28px;
}

.name-input :deep(.el-input__inner) {
  height: 26px;
  font-size: var(--fs-sm);
  padding: 0;
}

/* 流传输：文字 + 箭头，点开是下拉菜单（对齐 LiveGBS） */
.transport-link {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  cursor: pointer;
  color: var(--accent-ink);
  font-size: var(--fs-sm);
  font-weight: 500;
  user-select: none;
}

.transport-link:hover {
  text-decoration: underline;
}

.transport-link.is-busy {
  opacity: 0.6;
  pointer-events: none;
}

.transport-arrow {
  font-size: 11px;
  color: var(--text-tertiary);
}

/* 通道数：可点击跳转到通道管理 */
.count-link {
  font-variant-numeric: tabular-nums;
  font-weight: 500;
}

/* 下拉选项右侧的说明文字（标准依据） */
.stream-index-hint {
  margin-left: var(--sp-3);
  font-size: var(--fs-xs);
  color: var(--text-tertiary);
}
</style>
