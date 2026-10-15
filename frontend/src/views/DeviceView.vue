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
        <el-table-column prop="id" label="设备编码" min-width="190" sortable>
          <template #default="{ row }"><span class="mono">{{ row.id }}</span></template>
        </el-table-column>
        <el-table-column label="名称" min-width="180" :sort-by="(r) => r.customName || r.name || ''" sortable>
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
        <el-table-column label="厂商 / 型号" min-width="160">
          <template #default="{ row }">
            <span class="text-muted">{{ [row.manufacturer, row.model].filter(Boolean).join(' / ') || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90" align="center">
          <template #default="{ row }">
            <span class="badge" :class="row.online ? 'is-online' : 'is-offline'">
              {{ row.online ? '在线' : '离线' }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="IP 地址" min-width="140">
          <template #default="{ row }"><span class="mono">{{ row.remoteIp || '—' }}</span></template>
        </el-table-column>
        <el-table-column prop="channelCount" label="通道数" width="80" align="center" sortable>
          <template #default="{ row }">{{ row.channelCount ?? 0 }}</template>
        </el-table-column>
        <el-table-column label="最后注册" min-width="160">
          <template #default="{ row }">
            <span class="text-muted">{{ row.lastRegisterAt || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="150" fixed="right" align="center">
          <template #default="{ row }">
            <div class="row-actions">
            <el-tooltip content="查看该设备的通道">
              <el-button link type="primary" @click="viewChannels(row)">
                <el-icon><List /></el-icon>
              </el-button>
            </el-tooltip>
            <el-tooltip content="更新通道（重新拉取目录）">
              <el-button
                link
                type="primary"
                :loading="busy === row.id"
                @click="collect(row)"
              >
                <el-icon><Download /></el-icon>
              </el-button>
            </el-tooltip>
            <el-tooltip content="编辑设备">
              <el-button link type="primary" @click="openEdit(row)">
                <el-icon><Edit /></el-icon>
              </el-button>
            </el-tooltip>
            <el-tooltip content="删除设备">
              <el-button link type="danger" @click="remove(row)">
                <el-icon><Delete /></el-icon>
              </el-button>
            </el-tooltip>
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
</style>
