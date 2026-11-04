<template>
  <div class="page is-fill">

    <!-- 工具条：筛选在左、操作在右，一行放完（早先拆两行，白白多占竖向空间） -->
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
      <!--
        添加设备：三个入口收成一个分裂按钮。

        <p>为什么不并排三个按钮：它们都是「让平台多一台设备」，并排会把工具栏挤满，
        而且新人分不清「主动注册」和「按 IP 添加」该点哪个。主按钮放最常用的手工录入，
        另外两个进下拉；主动注册那项带数量角标 —— 有设备排队却没提示，等于没这个功能。

        <p>国标设备不需要这里：它由设备 REGISTER 推上来，平台从不主动建。
      -->
      <el-dropdown
        v-if="canWrite"
        split-button
        type="primary"
        size="small"
        trigger="click"
        class="add-device"
        @click="openDahua()"
        @command="onAddCommand"
      >
        添加设备
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item command="manual">
              <el-icon><Plus /></el-icon> 按 IP 添加
            </el-dropdown-item>
            <el-dropdown-item command="register">
              <el-icon><Connection /></el-icon> 主动注册设备
              <el-badge
                v-if="pendingRegistered > 0"
                :value="pendingRegistered"
                class="add-device-badge"
              />
            </el-dropdown-item>
            <el-dropdown-item command="discover" divided>
              <el-icon><Search /></el-icon> 搜索局域网设备
            </el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
    </div>

    <el-card shadow="never">

      <!--
        table-layout="fixed"：默认 fit 布局会测量每列内容最大宽度，行内编辑的 input
        出现或按钮 loading 变化时列宽会重排，整张表跟着跳。
      -->
      <el-table ref="tableRef" v-loading="loading" :data="devices" stripe table-layout="fixed">
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
            <!-- 常驻输入框会在每行渲染「点击修改」占位符，一屏几十行全是灰字 -->
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
              用文字 + 箭头而不是 el-select（后者一屏几十行全是灰框）。
              el-dropdown 在表格里复用时 @command 只回传值、不知道行（实测点第 5 行改的是第 1 行），
              所以命令值带上设备编码，由 handleTransportCommand 拆开。
            -->
            <el-dropdown
              v-if="canWrite"
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
            <span v-else class="text-muted">{{ transportText(row) }}</span>
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
            <!--
              删除按钮的可用条件按接入方式区分：
                · SDK 直连设备（大华等）：连接是平台主动发起的，设备侧不会自己断开，
                  所以<b>在线也能删</b>（后端会先登出 SDK 会话）。
                · 国标设备：它自己在往平台 REGISTER，删了立刻注册回来，
                  必须先离线才能删，否则等于没删。
              早期一刀切用 !row.online，导致大华设备永远看不到删除按钮。
            -->
            <div class="row-actions">
              <el-button link type="primary" @click="viewChannels(row)">查看通道</el-button>
              <el-button
                v-if="row.online && canSync"
                link
                class="act-collect"
                :loading="busy === row.id"
                @click="collect(row)"
              >更新通道</el-button>
              <el-button v-if="canWrite" link class="act-edit" @click="openEdit(row)">编辑</el-button>
              <el-button
                v-if="canDelete && (isSdkDevice(row) || !row.online)"
                link
                type="danger"
                @click="remove(row)"
              >删除</el-button>
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

    <!-- 接入 SDK 直连设备（大华 NetSDK） -->
    <el-dialog v-model="dahuaDialog" title="接入 SDK 设备（大华 NetSDK）" width="460px">
      <el-form :model="dahuaForm" label-width="92px">
        <el-form-item label="设备编号" required>
          <el-input v-model="dahuaForm.serial" placeholder="设备序列号，如 10001" />
        </el-form-item>
        <el-form-item label="设备地址" required>
          <el-input v-model="dahuaForm.host" placeholder="192.168.10.156" />
        </el-form-item>
        <el-form-item label="SDK 端口" required>
          <el-input-number v-model="dahuaForm.port" :min="1" :max="65535" controls-position="right" />
        </el-form-item>
        <el-form-item label="用户名" required>
          <el-input v-model="dahuaForm.username" placeholder="admin" />
        </el-form-item>
        <el-form-item label="密码" required>
          <el-input v-model="dahuaForm.password" type="password" show-password placeholder="设备登录密码" />
        </el-form-item>
        <el-form-item label="显示名称">
          <el-input v-model="dahuaForm.name" placeholder="留空则用设备编号" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dahuaDialog = false">取消</el-button>
        <el-button type="primary" :loading="dahuaSaving" @click="submitDahua">接入</el-button>
      </template>
    </el-dialog>


    <!--
      主动注册设备：设备已经连上平台（9500），但平台不知道它的账号密码。

      <p>为什么不自动纳管：不同现场设备密码各不相同，全局默认值只能覆盖一部分；
      更糟的是自动纳管会覆盖用户手填的凭据（表现为「昨天能连，今天密码错」）。
    -->
    <el-dialog v-model="registerDialog" title="主动注册设备" width="720px">
      <el-table :data="registerRows" size="small" max-height="360"
                empty-text="没有等待纳管的设备">
        <el-table-column prop="serial" label="序列号" width="110" />
        <el-table-column prop="ip" label="IP" width="130" />
        <el-table-column label="最后注册" width="150">
          <template #default="{ row }">{{ fmtRegTime(row.lastSeenAt) }}</template>
        </el-table-column>
        <el-table-column label="账号" width="120">
          <template #default="{ row }">
            <el-input v-model="row.username" size="small" placeholder="admin" />
          </template>
        </el-table-column>
        <el-table-column label="密码" width="140">
          <template #default="{ row }">
            <el-input
              v-model="row.password"
              size="small"
              type="password"
              show-password
              placeholder="设备密码"
            />
          </template>
        </el-table-column>
        <el-table-column label="操作" width="130">
          <template #default="{ row }">
            <el-button
              size="small"
              type="primary"
              link
              :loading="row.adopting"
              @click="adoptRegistered(row)"
            >
              纳管
            </el-button>
            <!-- 忽略：只是把它从这张待办表里去掉，设备下次注册还会回来 -->
            <el-button size="small" link @click="ignoreRegistered(row)">忽略</el-button>
          </template>
        </el-table-column>
      </el-table>
      <template #footer>
        <el-button size="small" @click="loadRegister">刷新</el-button>
        <el-button size="small" @click="registerDialog = false">关闭</el-button>
      </template>
    </el-dialog>

    <!-- 局域网搜索：SDK 广播发现，只在同一广播域有效 -->
    <el-dialog v-model="discoverDialog" title="搜索局域网大华设备" width="620px">
      <div class="discover-bar">
        <el-input
          v-model="discoverLocalIp"
          size="small"
          placeholder="本机网卡 IP（多网卡机器建议填）"
          style="width: 240px"
        />
        <el-button size="small" type="primary" :loading="discovering" @click="runDiscover">
          {{ discovering ? '搜索中…' : '开始搜索' }}
        </el-button>
      </div>
      <el-table :data="discoverRows" size="small" max-height="320" empty-text="尚未搜索，或该网段没有设备应答">
        <el-table-column prop="serial" label="序列号" width="110" />
        <el-table-column prop="ip" label="IP" width="130" />
        <el-table-column prop="model" label="型号" />
        <el-table-column prop="channels" label="通道" width="70" />
        <el-table-column label="操作" width="100">
          <template #default="{ row }">
            <el-tag v-if="row.managed" size="small" type="success">已纳管</el-tag>
            <el-button v-else size="small" type="primary" link @click="useDiscovered(row)">添加</el-button>
          </template>
        </el-table-column>
      </el-table>
      <template #footer>
        <el-button size="small" @click="discoverDialog = false">关闭</el-button>
      </template>
    </el-dialog>
    <DeviceDetailDialog v-model:visible="detailVisible" :device="detailTarget" />
  </div>
</template>

<script setup>
import { computed, nextTick, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { confirmAction } from '@/utils/confirm'
import { useRouter } from 'vue-router'
import { deviceApi, dahuaApi } from '@/api'
import { useAuthStore } from '@/stores/auth'
import { usePagination } from '@/composables/usePagination'
import { useFillTableRows } from '@/composables/useFillTableRows'
import TablePager from '@/components/TablePager.vue'
import DeviceDetailDialog from '@/components/DeviceDetailDialog.vue'

const auth = useAuthStore()
const router = useRouter()

/** 只读账号仍可查看通道/播放，但看不到编辑、删除、切换传输模式。真正拦截在后端 device:write。 */
const canWrite = computed(() => auth.can('device:write'))
const canSync = computed(() => auth.can('device:sync'))
const canDelete = computed(() => auth.can('device:delete'))

/**
 * 是否 SDK 直连设备（大华等）。
 *
 * <p>判断依据是后端返回的 {@code source}（{@code gb_device.source}）。
 * 需要这个区分是因为删除条件不同：
 * SDK 设备的连接由平台发起，设备侧不会自己断开，所以<b>在线也能删</b>；
 * 国标设备自己在往平台 REGISTER，必须先离线。
 */
function isSdkDevice(row) {
  const s = String(row?.source || '').toLowerCase()
  // netsdk 是大华的历史别名
  return s === 'dahua' || s === 'netsdk' || s === 'sdk'
}

const devices = ref([])

const tableRef = ref(null)
useFillTableRows(tableRef, () => devices.value.length)
const loading = ref(false)
const busy = ref('')
const saving = ref(false)
const query = reactive({ q: '', online: null })

/* ==================== 接入 SDK 直连设备（大华 NetSDK） ==================== */

const dahuaDialog = ref(false)
const dahuaSaving = ref(false)
const dahuaForm = reactive({
  serial: '',
  host: '',
  port: 37777,
  username: 'admin',
  password: '',
  name: ''
})

/**
 * 打开手工录入弹窗。
 *
 * @param prefill 预填值：从「局域网搜索」点「添加」时带过来，
 *                直接填好序列号/IP/端口，用户只差账号密码
 */
function openDahua(prefill) {
  dahuaForm.serial = prefill?.serial || ''
  dahuaForm.host = prefill?.host || ''
  dahuaForm.port = prefill?.port || 37777
  dahuaForm.username = prefill?.username || 'admin'
  dahuaForm.password = prefill?.password || ''
  dahuaForm.name = prefill?.name || ''
  dahuaDialog.value = true
}

async function submitDahua() {
  const serial = dahuaForm.serial.trim()
  const host = dahuaForm.host.trim()
  if (!serial || !host) {
    ElMessage.warning('设备编号与设备地址必填')
    return
  }
  if (!dahuaForm.username.trim() || !dahuaForm.password) {
    ElMessage.warning('SDK 直连需要用户名与密码')
    return
  }
  dahuaSaving.value = true
  try {
    await deviceApi.addDahua({
      serial,
      host,
      port: dahuaForm.port,
      username: dahuaForm.username.trim(),
      password: dahuaForm.password,
      name: dahuaForm.name.trim() || undefined
    })
    dahuaDialog.value = false
    ElMessage.success('已接入，正在同步通道…')
    await load()
    try {
      // SDK 设备不会自己上报目录，必须平台主动去问
      await deviceApi.refreshSdk(serial)
      await load()
      ElMessage.success('通道同步完成')
    } catch (e) {
      ElMessage.warning('设备已接入，但通道同步失败：' + (e.message || e))
    }
  } catch (e) {
    ElMessage.error(e.message || '接入失败')
  } finally {
    dahuaSaving.value = false
  }
}

function resetQuery() {
  query.q = ''
  query.online = null
  search()
}

/** 正在保存名称的设备编号集合（防重复提交）。 */
const namePending = reactive(new Set())

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

/** GB/T 28181—2022 附录 G：SSRC 第 7~10 位是码流编号，0 主码流 / 1 子码流。
 * 「自动」保持原有行为（域内自增序号）。 */
const STREAM_INDEX_OPTIONS = [
  { value: 'auto', label: '自动选择', hint: '由设备决定推哪一路' },
  { value: 'streamnumber:0', label: '主码流', hint: '2022 标准，SSRC 后 4 位填 0' },
  { value: 'streamnumber:1', label: '子码流', hint: '2022 标准，SSRC 后 4 位填 1' }
]

const STREAM_INDEX_HINT = '指定后点播只拉该路码流；子码流分辨率低但省带宽'

/** TCP 分被动/主动：被动由平台发 INVITE、设备回推，主动则设备建流推给平台。
 * 模式会写进 SIP 协商，填错会协商失败。 */
const TRANSPORT_OPTIONS = [
  { value: 'UDP', label: 'UDP', transport: 'UDP', mode: 'passive' },
  { value: 'TCP', label: 'TCP 被动', transport: 'TCP', mode: 'passive' },
  { value: 'TCP_ACTIVE', label: 'TCP 主动', transport: 'TCP', mode: 'active' }
]

function transportLabel(row) {
  const transport = (row.mediaTransport || 'UDP').toUpperCase()
  if (transport !== 'TCP') return 'UDP'
  return row.mediaTransportMode === 'active' ? 'TCP_ACTIVE' : 'TCP'
}

function transportText(row) {
  return TRANSPORT_OPTIONS.find((o) => o.value === transportLabel(row))?.label || 'UDP'
}

/** 命令值格式「设备编码|模式标识」：表格多行共用 el-dropdown，命令本身不带行信息。 */
function handleTransportCommand(cmd) {
  const sep = String(cmd).indexOf('|')
  if (sep < 0) return
  const deviceId = cmd.slice(0, sep)
  const mode = cmd.slice(sep + 1)
  const row = devices.value.find((d) => d.id === deviceId)
  if (!row) return
  changeTransport(row, mode)
}

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

const detailVisible = ref(false)
const detailTarget = ref(null)

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

function startNameEdit(row) {
  if (row._editing) return
  row._editing = true
  row._draftName = row.customName || ''
  nextTick(() => {
    const input = document.querySelector('.name-input input')
    if (input) input.focus()
  })
}

function cancelName(row) {
  row.customName = row._draftName || ''
  row._editing = false
}

/** 值未变化时不发请求：否则点一下别处就会打一次接口。 */
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
  if (!(await confirmAction(
    `确定删除设备 ${row.id}？该操作会同时删除其通道数据。`,
    '删除确认',
    { confirmButtonText: '删除' }
  ))) return
  try {
    await deviceApi.remove(row.id)
    ElMessage.success('已删除')
    load()
  } catch (e) {
    ElMessage.error(e.message || '删除失败')
  }
}


/* ==================== 添加设备：三个入口 ==================== */

const registerDialog = ref(false)
const registerRows = ref([])
/** 待补凭据的设备数，用于下拉项角标。 */
const pendingRegistered = ref(0)

const discoverDialog = ref(false)
const discoverRows = ref([])
const discovering = ref(false)
const discoverLocalIp = ref('')

function onAddCommand(cmd) {
  if (cmd === 'manual') {
    openDahua()
  } else if (cmd === 'register') {
    registerDialog.value = true
    loadRegister()
  } else if (cmd === 'discover') {
    discoverDialog.value = true
  }
}

/**
 * 读注册表（后端已过滤掉已纳管的设备，这里拿到的全是待办）。
 *
 * <p>每行补三个前端字段（username/password/adopting）：凭据只在提交时用一次，
 * 不落库、不回显，所以放在行对象上而不是后端 VO 里。
 */
async function loadRegister() {
  try {
    const list = await dahuaApi.registerList()
    registerRows.value = (list || []).map((r) => ({
      ...r,
      username: 'admin',
      password: '',
      adopting: false
    }))
    // 后端已经只返回待办，这里不再需要按 managed 过滤
    pendingRegistered.value = registerRows.value.length
  } catch (e) {
    ElMessage.error(e.message || '读取注册表失败')
  }
}

/** 纳管一台注册设备：账号密码由用户填，成功后立刻拉目录验证是否真的能登录。 */
async function adoptRegistered(row) {
  if (!row.username || !row.password) {
    ElMessage.warning('请填写该设备的账号和密码')
    return
  }
  row.adopting = true
  try {
    await dahuaApi.adoptRegister({
      serial: row.serial,
      username: row.username,
      password: row.password,
      port: row.sdkPort
    })
    ElMessage.success(`已纳管 ${row.serial}`)
    await loadRegister()
    await load()
  } catch (e) {
    ElMessage.error(e.message || '纳管失败')
  } finally {
    row.adopting = false
  }
}

async function ignoreRegistered(row) {
  try {
    await dahuaApi.ignoreRegister({ serial: row.serial })
    await loadRegister()
  } catch (e) {
    ElMessage.error(e.message || '操作失败')
  }
}

/** 搜索局域网设备（同步等待，5 秒左右，期间按钮转圈）。 */
async function runDiscover() {
  discovering.value = true
  try {
    const list = await deviceApi.discover({
      localIp: discoverLocalIp.value || undefined,
      seconds: 5
    })
    discoverRows.value = list || []
    if (!discoverRows.value.length) {
      ElMessage.info('该网段没有设备应答（跨网段请用「按 IP 添加」）')
    }
  } catch (e) {
    ElMessage.error(e.message || '搜索失败')
  } finally {
    discovering.value = false
  }
}

/** 把搜索结果带进手工录入弹窗：IP/序列号/端口都预填，只差账号密码。 */
function useDiscovered(row) {
  discoverDialog.value = false
  openDahua({
    serial: row.serial,
    host: row.ip,
    port: row.sdkPort || 37777,
    username: 'admin',
    password: ''
  })
}

/** 后端返回的是 LocalDateTime 序列化串，去掉 T 便于阅读。 */
function fmtRegTime(v) {
  if (!v) {
    return '—'
  }
  return String(v).replace('T', ' ').slice(0, 19)
}

onMounted(() => {
  load()
  // 顺带读一次注册表：有设备在排队时按钮上要显示角标，否则用户不会想到去点下拉
  loadRegister()
})

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
  /* 单行显示：过长靠 ellipsis 截断，不换行撑高行 */
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
  编辑态输入框必须限制在单元格内：el-input 默认 min-width 会把列撑宽，
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

/* ==================== 添加设备 ==================== */

.add-device-badge {
  margin-left: 6px;
}

.discover-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 10px;
}
</style>
