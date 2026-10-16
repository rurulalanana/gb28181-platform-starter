<template>
  <div class="page">
    <header class="page-header">
      <div class="page-title-group">
        <h2 class="page-title">设备管理</h2>
        <p class="page-subtitle">
          已接入 {{ totalCount }} 台设备，其中
          <b class="text-success">{{ onlineCount }}</b> 台在线
        </p>
      </div>
      <div class="page-actions">
        <el-button size="small" :loading="loading" @click="load">
          <el-icon><Refresh /></el-icon> 刷新
        </el-button>
        <el-button size="small" title="导出设备列表（CSV）" @click="exportCsv">
          <el-icon><Download /></el-icon> 导出
        </el-button>
      </div>
    </header>

    <!-- 筛选栏 -->
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
      <span class="text-dim">设备编码可直接点击名称行内编辑</span>
    </div>

    <el-card shadow="never">

      <el-table v-loading="loading" :data="devices" stripe>
        <el-table-column prop="id" label="设备编码" min-width="170" sortable>
          <template #default="{ row }"><span class="mono">{{ row.id }}</span></template>
        </el-table-column>
        <el-table-column label="名称" min-width="150" :sort-by="(r) => r.customName || r.name || ''" sortable>
          <template #default="{ row }">
            <!--
              行内编辑：点即改。设备名常被上报成统一型号名，而改名是高频操作，
              弹窗多一步没必要。回车或失焦保存。
            -->
            <el-input
              v-model="row.customName"
              size="small"
              placeholder="点击修改"
              :disabled="namePending.has(row.id)"
              @focus="onNameFocus(row)"
              @keyup.enter="saveName(row)"
              @blur="saveName(row)"
            />
          </template>
        </el-table-column>
        <el-table-column label="流传输" width="104" align="center">
          <template #default="{ row }">
            <!-- 行内切换：对齐 LiveGBS 的流传输模式直接切换，只影响后续点播 -->
            <el-radio-group
              :model-value="(row.mediaTransport || 'UDP').toUpperCase()"
              size="small"
              class="transport-radio"
              :disabled="transportPending.has(row.id)"
              @change="(v) => changeTransport(row, v)"
            >
              <el-radio-button value="UDP">UDP</el-radio-button>
              <el-radio-button value="TCP">TCP</el-radio-button>
            </el-radio-group>
          </template>
        </el-table-column>
        <el-table-column prop="channelCount" label="通道数" width="76" align="center" sortable>
          <template #default="{ row }">{{ row.channelCount ?? 0 }}</template>
        </el-table-column>
        <el-table-column label="在线状态" width="120" align="center">
          <template #default="{ row }">
            <!-- 对齐参考站：状态文字点击查看状态记录，图标点击查看流量统计 -->
            <div class="status-cell">
              <span
                class="badge is-clickable"
                :class="row.online ? 'is-online' : 'is-offline'"
                title="点击查看状态记录"
                @click="openStatusLog(row)"
              >{{ row.online ? '在线' : '离线' }}</span>
              <el-icon
                class="traffic-icon"
                title="点击查看流量统计"
                @click="openStreamLog(row)"
              >
                <Histogram />
              </el-icon>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="240" align="center">
          <template #default="{ row }">
            <!--
              LiveGBS 风格操作组：文字按钮更明确。
              ＞「更新通道」仅在线设备（离线拉不动目录）；
              ＞「删除」仅离线设备（防误删在线设备，对齐 LiveGBS）。
            -->
            <div class="row-actions">
              <el-button link type="primary" @click="viewChannels(row)">查看通道</el-button>
              <el-button v-if="row.online" link :loading="busy === row.id" @click="collect(row)">更新通道</el-button>
              <el-button link class="act-edit" @click="openEdit(row)">编辑</el-button>
              <el-button v-if="!row.online" link type="danger" @click="remove(row)">删除</el-button>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="厂商 / 型号" min-width="130" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="text-muted">{{ [row.manufacturer, row.model].filter(Boolean).join(' / ') || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="IP 地址" min-width="130" show-overflow-tooltip>
          <template #default="{ row }"><span class="mono">{{ row.remoteIp || '—' }}</span></template>
        </el-table-column>
        <el-table-column label="最后注册" min-width="150" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="text-muted">{{ row.lastRegisterAt || '—' }}</span>
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
              <el-radio-group v-model="form.mediaTransport">
                <el-radio-button value="TCP">TCP</el-radio-button>
                <el-radio-button value="UDP">UDP</el-radio-button>
              </el-radio-group>
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

    <!-- 设备状态记录（对齐参考站：点击在线/离线文字查看） -->
    <el-dialog
      v-model="statusLogDialog"
      :title="'设备状态记录 · ' + (statusLogTarget?.customName || statusLogTarget?.name || statusLogTarget?.id || '')"
      width="580px"
    >
      <el-table v-loading="statusLogLoading" :data="statusLogRows" size="small" max-height="420">
        <el-table-column prop="createdAt" label="时间" width="180">
          <template #default="{ row }"><span class="mono">{{ row.createdAt }}</span></template>
        </el-table-column>
        <el-table-column label="状态" width="90" align="center">
          <template #default="{ row }">
            <span
              class="badge"
              :class="String(row.status || '').toUpperCase() === 'ON' ? 'is-online' : 'is-offline'"
            >{{ String(row.status || '').toUpperCase() === 'ON' ? '在线' : '离线' }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="description" label="描述" min-width="220" show-overflow-tooltip />
        <template #empty>
          <el-empty description="暂无状态记录" :image-size="64" />
        </template>
      </el-table>
      <div class="dialog-foot-tip">记录保留 {{ statusLogReserveDays }} 天，由平台定时清理</div>
    </el-dialog>

    <!-- 流量统计（嵌入式 ZLMediaKit 无计数接口，弹窗如实说明） -->
    <el-dialog
      v-model="streamLogDialog"
      :title="'流量统计 · ' + (streamLogTarget?.customName || streamLogTarget?.name || streamLogTarget?.id || '')"
      width="520px"
    >
      <el-empty
        v-if="!streamLogRows.length"
        :description="streamLogNote || '暂无流量数据'"
        :image-size="72"
      />
      <el-table v-else :data="streamLogRows" size="small" max-height="380">
        <el-table-column prop="time" label="时间" min-width="170" />
        <el-table-column prop="channelId" label="通道" min-width="170">
          <template #default="{ row }"><span class="mono">{{ row.channelId }}</span></template>
        </el-table-column>
        <el-table-column prop="bytes" label="流量(字节)" width="120" align="right" />
      </el-table>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useRouter } from 'vue-router'
import { deviceApi } from '@/api'
import { useAuthStore } from '@/stores/auth'
import { usePagination } from '@/composables/usePagination'
import TablePager from '@/components/TablePager.vue'

const auth = useAuthStore()
const router = useRouter()

const devices = ref([])
const loading = ref(false)
const busy = ref('')
const saving = ref(false)
const query = reactive({ q: '', online: null })

/** 设备总数（来自分页器，全量口径）。 */
const totalCount = computed(() => pager.total ?? 0)

/** 当前页在线设备数。 */
const onlineCount = computed(() => devices.value.filter((d) => d.online).length)

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
  mediaTransport: '',
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
  pageSize: 20,
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
      // 是否处于「正在编辑」状态：仅此状态下的失焦才触发保存
      _editing: false
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

/** 行内切换流传输模式（UDP/TCP，只影响后续点播发流方式）。 */
async function changeTransport(row, value) {
  const old = row.mediaTransport || 'UDP'
  if (value === old) return
  transportPending.add(row.id)
  try {
    await deviceApi.setMediaTransport(row.id, value)
    row.mediaTransport = value
    ElMessage.success(`已切换为 ${value}（对下次点播生效）`)
  } catch (e) {
    row.mediaTransport = old
    ElMessage.error(e.message || '切换失败')
  } finally {
    transportPending.delete(row.id)
  }
}

/* ---------------- 状态记录 / 流量统计（对齐参考站：点击状态查看） ---------------- */

const statusLogDialog = ref(false)
const statusLogTarget = ref(null)
const statusLogLoading = ref(false)
const statusLogRows = ref([])
const statusLogReserveDays = ref(30)

/** 打开设备状态记录弹窗（点击「在线/离线」文字触发）。 */
async function openStatusLog(row) {
  statusLogTarget.value = row
  statusLogRows.value = []
  statusLogDialog.value = true
  statusLogLoading.value = true
  try {
    const data = await deviceApi.statusLog({ serial: row.id, limit: 200 })
    statusLogRows.value = data?.logList || []
    statusLogReserveDays.value = data?.logReserveDays ?? 30
  } catch (e) {
    ElMessage.error(e.message || '加载状态记录失败')
  } finally {
    statusLogLoading.value = false
  }
}

const streamLogDialog = ref(false)
const streamLogTarget = ref(null)
const streamLogRows = ref([])
const streamLogNote = ref('')

/** 打开流量统计弹窗（点击状态旁图表图标触发）。 */
async function openStreamLog(row) {
  streamLogTarget.value = row
  streamLogRows.value = []
  streamLogNote.value = ''
  streamLogDialog.value = true
  try {
    const data = await deviceApi.streamLog({ serial: row.id })
    streamLogRows.value = data?.list || []
    streamLogNote.value = data?.note || ''
  } catch (e) {
    ElMessage.error(e.message || '加载流量统计失败')
  }
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

/**
 * 进入编辑态。
 *
 * <p><b>必须有这个状态</b>：{@code el-input} 的 blur 在「仅聚焦一下就离开」时
 * 也会触发。若不区分是否真的编辑过，点一下单元格（或点行内其它按钮导致失焦）
 * 就会把 customName 当成空值保存，把设备名清掉。
 */
function onNameFocus(row) {
  row._editing = true
}

/**
 * 行内保存名称。
 *
 * <p>两个守卫：未进入编辑态不保存；值未变化不保存 ——
 * 否则表格里点一下别处就会打一次接口。
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
  form.mediaTransport = row.mediaTransport || ''
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
    if (form.mediaTransport) payload.media_transport = form.mediaTransport
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
  font-size: 13px;
  color: var(--ink-400, #909399);
  cursor: pointer;
}
.traffic-icon:hover {
  color: var(--brand-500, #2f5c94);
}

/* 弹窗底部说明 */
.dialog-foot-tip {
  margin-top: 8px;
  font-size: 12px;
  color: var(--ink-400, #909399);
}

/* 行内流传输切换：紧凑分段按钮（比下拉在窄列里更清晰、可见当前值） */
.transport-radio :deep(.el-radio-button__inner) {
  padding: 3px 8px;
  font-size: 11px;
  letter-spacing: 0.02em;
}
.transport-radio :deep(.el-radio-button:first-child .el-radio-button__inner) {
  border-radius: 5px 0 0 5px;
}
.transport-radio :deep(.el-radio-button:last-child .el-radio-button__inner) {
  border-radius: 0 5px 5px 0;
}
</style>
