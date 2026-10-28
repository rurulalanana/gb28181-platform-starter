<template>
  <div class="page is-fill">

    <!-- 工具条：操作按钮靠右，与其它页保持一致 -->
    <div class="filter-bar">
      <span class="filter-bar-spacer" />
      <el-button size="small" :loading="loading" @click="load">
        <el-icon><Refresh /></el-icon> 刷新
      </el-button>
      <el-button size="small" type="primary" @click="openEdit(null)">
        <el-icon><Plus /></el-icon> 新增级联
      </el-button>
    </div>

    <el-card shadow="never">

      <el-table ref="tableRef" v-loading="loading" :data="list" stripe table-layout="fixed">
        <el-table-column label="名称" width="132">
          <template #default="{ row }">{{ row.name || '—' }}</template>
        </el-table-column>
        <el-table-column label="平台编码" min-width="190">
          <template #default="{ row }"><span class="mono">{{ row.serial || '—' }}</span></template>
        </el-table-column>
        <el-table-column label="对端地址" width="168">
          <template #default="{ row }">
            <span class="mono">{{ row.host ? row.host + ':' + row.port : '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="本地地址" width="168">
          <template #default="{ row }">
            <span class="mono">{{ row.localHost ? row.localHost + ':' + row.localPort : '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="启用" width="80" align="center">
          <template #default="{ row }">
            <el-switch
              :model-value="!!row.enable"
              size="small"
              @change="(v) => toggleEnable(row, v)"
            />
          </template>
        </el-table-column>
        <el-table-column label="在线" width="80" align="center">
          <template #default="{ row }">
            <span class="badge" :class="row.online ? 'is-online' : 'is-offline'">
              {{ row.online ? '在线' : '离线' }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="共享全部通道" width="120" align="center">
          <template #default="{ row }">
            <el-icon :color="row.shareAllChannel ? '#67c23a' : '#c0c4cc'" :size="18">
              <CircleCheckFilled v-if="row.shareAllChannel" />
              <CircleCloseFilled v-else />
            </el-icon>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="186" align="center" fixed="right">
          <template #default="{ row }">
            <el-tooltip content="选择共享通道">
              <el-button link type="primary" @click="openChannels(row)">
                <el-icon><Connection /></el-icon>
              </el-button>
            </el-tooltip>
            <el-tooltip content="推送目录到上级">
              <el-button link type="primary" @click="pushCatalog(row)">
                <el-icon><Refresh /></el-icon>
              </el-button>
            </el-tooltip>
            <el-tooltip content="编辑">
              <el-button link type="primary" @click="openEdit(row)">
                <el-icon><Edit /></el-icon>
              </el-button>
            </el-tooltip>
            <el-tooltip content="删除">
              <el-button link type="danger" @click="removeOne(row)">
                <el-icon><Delete /></el-icon>
              </el-button>
            </el-tooltip>
          </template>
        </el-table-column>
        <template #empty>
          <div class="empty-cell">
            暂未配置级联。级联用于与本平台之外的上下级 GB28181 平台互联
          </div>
        </template>
      </el-table>

      <TablePager
        :pager="pager"
        unit="条级联"
        @page-change="onPageChange"
        @size-change="onSizeChange"
      />
    </el-card>

    <!-- 编辑对话框 -->
    <!-- 布局说明：级联表单字段名普遍较长（「上级平台编码」「共享全部通道」等），
         早先用 label-width=130px + 两列 el-col span=12 时，每个输入框只剩约 200px，
         而「上级端口/本地地址/本地端口」三个字段挤在 span=8 里更只剩 130px，
         数字输入框的增减按钮直接把内容压没了。改为单列 + 分组卡片：
         每个字段占满整行宽度，长编码也有足够空间完整显示。 -->
    <el-dialog
      v-model="editDialog"
      :title="form.ID ? '编辑级联' : '新增级联'"
      width="560px"
      class="cascade-dialog"
      top="6vh"
    >
      <el-form :model="form" label-width="120px" label-position="right">
        <!-- 基础信息 -->
        <div class="form-section">
          <div class="form-section-title">基础信息</div>
          <el-form-item label="名称">
            <el-input v-model="form.Name" placeholder="用于在列表中区分，如：市级平台" />
          </el-form-item>
          <el-form-item label="上级平台编码">
            <el-input v-model="form.Serial" class="mono" placeholder="20 位国标编码" />
          </el-form-item>
          <el-form-item label="SIP 域">
            <el-input v-model="form.Realm" class="mono" placeholder="通常与上级平台编码前 10 位一致" />
          </el-form-item>
        </div>

        <!-- 网络配置 -->
        <div class="form-section">
          <div class="form-section-title">网络配置</div>
          <el-form-item label="上级平台地址">
            <el-input v-model="form.Host" class="mono" placeholder="上级平台的 IP 或域名" />
          </el-form-item>
          <el-form-item label="上级端口">
            <el-input-number
              v-model="form.Port"
              :min="1"
              :max="65535"
              controls-position="right"
              class="full-width"
            />
          </el-form-item>
          <el-form-item label="本地地址">
            <el-input v-model="form.LocalHost" class="mono" placeholder="留空则自动探测" />
          </el-form-item>
          <el-form-item label="本地端口">
            <el-input-number
              v-model="form.LocalPort"
              :min="1"
              :max="65535"
              controls-position="right"
              class="full-width"
            />
          </el-form-item>
          <el-form-item label="传输协议">
            <el-select v-model="form.CommandTransport" class="full-width">
              <el-option label="UDP" value="UDP" />
              <el-option label="TCP" value="TCP" />
            </el-select>
          </el-form-item>
          <el-form-item label="推流传输">
            <el-select v-model="form.mediaTransport" class="full-width">
              <el-option label="自动（跟随上级 SDP）" value="auto" />
              <el-option label="强制 TCP 主动" value="tcp" />
              <el-option label="强制 UDP 主动" value="udp" />
            </el-select>
            <span class="form-item-hint">
              上级点播看不到画面（提示 none rtp data receive）时改选 TCP 主动
            </span>
          </el-form-item>
        </div>

        <!-- 鉴权（可选） -->
        <div class="form-section">
          <div class="form-section-title">
            鉴权
            <span class="form-section-hint">上级平台要求认证时填写</span>
          </div>
          <el-form-item label="用户名">
            <el-input v-model="form.Username" placeholder="可选" />
          </el-form-item>
          <el-form-item label="密码">
            <el-input v-model="form.Password" type="password" show-password placeholder="可选" />
          </el-form-item>
        </div>

        <!-- 行为开关 -->
        <div class="form-section">
          <div class="form-section-title">行为开关</div>
          <el-form-item label="启用">
            <el-switch v-model="form.Enable" />
          </el-form-item>
          <el-form-item label="共享全部通道">
            <el-switch v-model="form.ShareAllChannel" />
            <span class="form-item-hint">开启后本级所有通道都会同步给上级</span>
          </el-form-item>
          <el-form-item label="允许控制">
            <el-switch v-model="form.AllowControl" />
            <span class="form-item-hint">允许上级对本级通道下发云台等控制指令</span>
          </el-form-item>
        </div>
      </el-form>
      <template #footer>
        <el-button @click="editDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>

    <!-- ---------- 选择共享通道 ---------- -->
    <!--
      为什么要有这个弹窗：
      没有它的时候，级联只有一个「共享全部通道」开关 —— 要么全共享，要么一个都不共享。
      而现场最常见的需求恰恰是「只给上级看这几路」：既满足上级调阅要求，
      又不把无关通道（比如内部区域、宿舍楼）暴露出去。
      参考站也有独立的「选择通道」入口，这是级联功能可用性的最低要求。
    -->
    <el-dialog
      v-model="channelDialog"
      :title="`选择通道 · ${channelTarget?.name || ''}`"
      width="min(1180px, 94vw)"
      append-to-body
      :close-on-press-escape="!editingCustomId.row"
      @closed="resetChannelDialog"
    >
      <div class="ch-toolbar">
        <div class="ch-toolbar-left">
          <span class="ch-label">搜索</span>
          <el-input
            v-model="channelKeyword"
            placeholder="关键字"
            clearable
            style="width: 200px"
            @keyup.enter="reloadChannelList"
          />
          <span class="ch-label">通道类型</span>
          <el-select v-model="channelType" style="width: 120px" @change="reloadChannelList">
            <el-option label="全部" value="all" />
            <el-option label="视频" value="video" />
            <el-option label="音频" value="audio" />
            <el-option label="报警" value="alarm" />
            <el-option label="其它" value="other" />
          </el-select>
          <el-checkbox v-model="channelOnlyRelated" @change="reloadChannelList">
            只看已选({{ channelSelected.size }})
          </el-checkbox>
          <el-checkbox v-model="shareAllLocal">全部共享</el-checkbox>
        </div>
        <div class="ch-toolbar-right">
          <span class="ch-count">已选 {{ channelSelected.size }} 路 / 共 {{ channelTotal }} 条</span>
        </div>
      </div>

      <el-alert
        v-if="shareAllLocal"
        type="warning"
        :closable="false"
        show-icon
        class="ch-alert"
        title="已开启「全部共享」"
        description="保存后上级能看到本级所有通道，本页的勾选不生效。"
      />

      <el-table
        ref="channelTableRef"
        v-loading="channelLoading"
        :data="channelRows"
        size="small"
        height="440"
        row-key="rowKey"
        :class="{ 'is-disabled': shareAllLocal }"
        @selection-change="onChannelSelect"
        @sort-change="onChannelSort"
      >
        <el-table-column type="selection" width="44" reserve-selection />
        <el-table-column label="设备国标编号" prop="deviceId" width="168" sortable="custom">
          <template #default="{ row }"><span class="mono">{{ row.deviceId }}</span></template>
        </el-table-column>
        <el-table-column label="通道国标编号" prop="id" width="168" sortable="custom">
          <template #default="{ row }"><span class="mono">{{ row.id }}</span></template>
        </el-table-column>
        <!--
          自定义通道国标编号：行内编辑，与上级平台（LiveGBS）一致 ——
          点一下单元格就地变成输入框，回车/失焦保存，Esc 取消。
          用弹框改一个 20 位字符串太重：改错一位就要重开弹框，
          而且看不到相邻行做对照（编号通常是按设备顺序批量微调的）。
          编辑框预填的是**当前生效的编号**（自定义编号，没有则原编号），
          用户可以在此基础上改几位，而不是对着空框重打 20 位。
          改的是 custom_id，不动 channel_id —— 后者是设备实际使用的编号。
        -->
        <el-table-column label="自定义通道国标编号" prop="customId" width="190" sortable="custom">
          <template #default="{ row }">
            <el-input
              v-if="editingCustomId.row === row"
              ref="customIdInputRef"
              v-model="editingCustomId.value"
              size="small"
              class="mono cid-input"
              placeholder="清空=恢复原编号"
              :disabled="editingCustomId.saving"
              @keydown.enter.prevent="commitCustomId(row)"
              @keydown.esc.stop.prevent="cancelCustomId"
              @blur="commitCustomId(row)"
            />
            <el-button
              v-else
              link
              type="primary"
              class="mono"
              :title="row.customId ? '点击修改（当前生效编号）' : '点击设置自定义编号（预填原编号）'"
              @click="startEditCustomId(row)"
            >
              {{ row.customId || row.id }}
            </el-button>
          </template>
        </el-table-column>
        <el-table-column label="通道名称" min-width="150" show-overflow-tooltip>
          <template #default="{ row }">
            {{ row.customName || row.name || '—' }}
          </template>
        </el-table-column>
        <el-table-column label="快照" width="76" align="center">
          <template #default="{ row }">
            <img
              v-if="row.snapshot"
              :src="row.snapshot"
              class="ch-snap"
              alt="快照"
              @click="previewSnap(row)"
            />
            <div v-else class="ch-snap is-empty"><el-icon><Picture /></el-icon></div>
          </template>
        </el-table-column>
        <el-table-column label="在线状态" width="84" align="center">
          <template #default="{ row }">
            <span :class="row.online ? 'ch-online' : 'ch-offline'">
              {{ row.online ? '在线' : '离线' }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="厂家" width="100" show-overflow-tooltip>
          <template #default="{ row }">{{ row.manufacturer || '—' }}</template>
        </el-table-column>
      </el-table>

      <div class="ch-pager">
        <el-pagination
          v-model:current-page="channelPage"
          v-model:page-size="channelPageSize"
          :page-sizes="[10, 20, 50, 100]"
          :total="channelTotal"
          layout="total, prev, pager, next, sizes"
          background
          @current-change="loadChannelPage"
          @size-change="onChannelPageSize"
        />
      </div>

      <template #footer>
        <el-button @click="channelDialog = false">取消</el-button>
        <el-button type="primary" :loading="channelSaving" @click="saveChannels">
          保存
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { nextTick, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { confirmAction } from '@/utils/confirm'
import { Connection, Picture } from '@element-plus/icons-vue'
import { cascadeApi, deviceApi } from '@/api'
import { usePagination } from '@/composables/usePagination'
import { useFillTableRows } from '@/composables/useFillTableRows'
import TablePager from '@/components/TablePager.vue'

const list = ref([])

/** 行高随表体可用高度自适应，数据少时铺满、数据多时内部滚动。 */
const tableRef = ref(null)
useFillTableRows(tableRef, () => list.value.length)
const loading = ref(false)
const saving = ref(false)
const editDialog = ref(false)

const emptyForm = () => ({
  ID: null,
  Name: '',
  Serial: '',
  Realm: '',
  Host: '',
  Port: 5060,
  LocalHost: '',
  LocalPort: 5060,
  Username: '',
  Password: '',
  CommandTransport: 'UDP',
  mediaTransport: 'auto',
  Enable: true,
  ShareAllChannel: false,
  AllowControl: true
})

const form = reactive(emptyForm())

const { pager, offset, setTotal, onPageChange, onSizeChange } = usePagination({
  pageSize: 10,
  load: () => load()
})

async function load() {
  loading.value = true
  try {
    const data = await cascadeApi.list({ start: offset.value, limit: pager.size })
    // 后端 CascadeListVo 的字段名拼成了 casecadeList / casecadeCount（少了 s 后多打 a）。
    // 这里两种拼写都读，避免后端日后修正拼写时前端静默变成空列表。
    const rows = data?.casecadeList || data?.cascadeList || []
    list.value = rows
    setTotal(data?.casecadeCount ?? data?.cascadeCount ?? rows.length)
  } catch (e) {
    ElMessage.error(e.message || '加载级联失败')
  } finally {
    loading.value = false
  }
}

function openEdit(row) {
  Object.assign(form, emptyForm())
  if (row) {
    Object.assign(form, {
      ID: row.id,
      Name: row.name,
      Serial: row.serial,
      Realm: row.realm,
      Host: row.host,
      Port: row.port,
      LocalHost: row.localHost,
      LocalPort: row.localPort,
      Username: row.username,
      CommandTransport: row.commandTransport || 'UDP',
      mediaTransport: row.mediaTransport || 'auto',
      Enable: !!row.enable,
      ShareAllChannel: !!row.shareAllChannel,
      AllowControl: row.allowControl !== false
    })
  }
  editDialog.value = true
}

async function save() {
  if (!form.Serial || !form.Host) {
    ElMessage.warning('请填写上级平台编码与地址')
    return
  }
  saving.value = true
  try {
    await cascadeApi.save({ ...form })
    ElMessage.success('已保存')
    editDialog.value = false
    load()
  } catch (e) {
    ElMessage.error(e.message || '保存失败')
  } finally {
    saving.value = false
  }
}

async function toggleEnable(row, value) {
  try {
    await cascadeApi.setEnable({ id: row.id, enable: value })
    row.enable = value
    ElMessage.success(value ? '已启用' : '已停用')
  } catch (e) {
    ElMessage.error(e.message || '操作失败')
  }
}

async function pushCatalog(row) {
  try {
    const n = await cascadeApi.pushCatalog({ id: row.id })
    const count = typeof n === 'number' ? n : (n?.pushed ?? 0)
    ElMessage.success(`已推送 ${count} 路通道目录到上级`)
  } catch (e) {
    // 后端会带出具体原因（未注册到上级 / 无共享通道 / 上级不可达）
    ElMessage.error(e.message || '推送失败')
  }
}

/* ==================== 共享通道 ==================== */
/*
 * 对齐上级平台（LiveGBS）的「选择通道」弹窗：
 * 服务端分页 + 服务端筛选/排序，而不是把全部通道拉到前端再过滤。
 *
 * 为什么改成服务端分页：原来一次性拉全部通道（limit=0）在 500 条时还能忍，
 * 但现场接入十几个下级平台后通道数是几千级，一次几 MB 的 JSON
 * 加上浏览器渲染几千行表格，弹窗要卡好几秒。
 *
 * 为什么勾选状态与列表分开存：翻页会换掉整个数据集，
 * 只看当前页的勾选会丢掉其它页的选择。已选集合独立维护，
 * 表格用 reserve-selection 保留本页勾选，保存时提交整个集合。
 */

const channelDialog = ref(false)
const channelSaving = ref(false)
const channelLoading = ref(false)
const channelTarget = ref(null)
const channelRows = ref([])
const channelTotal = ref(0)
const channelKeyword = ref('')
const channelType = ref('all')
const channelOnlyRelated = ref(false)
/** 「全部共享」开关：对应级联的 shareAllChannel，保存时一并写回。 */
const shareAllLocal = ref(false)
const channelSelected = ref(new Set())
const channelTableRef = ref(null)
const channelPage = ref(1)
const channelPageSize = ref(10)
const channelSort = reactive({ prop: 'deviceId', order: 'asc' })

/** 已共享通道的完整编码集合（跨页），保存时整体提交。 */
const channelSharedAll = ref(new Set())

/**
 * 表格行唯一键。
 *
 * <p>不能用通道编码：不同设备的通道编码可以完全相同（国标允许），
 * 用编码做 row-key 会让两台设备的同号通道被当成同一行，
 * 勾一行等于勾了两行、reserve-selection 也会串。
 */
function rowKeyOf(row) {
  return `${row.deviceId}:${row.id}`
}

function resetChannelDialog() {
  channelRows.value = []
  channelSelected.value = new Set()
  channelSharedAll.value = new Set()
  channelKeyword.value = ''
  channelType.value = 'all'
  channelOnlyRelated.value = false
  channelPage.value = 1
  channelTotal.value = 0
  // 行内编辑态必须清掉：下次打开弹窗时 editingCustomId.row 指向的是
  // 上一批（已丢弃的）行对象，引用比对永远不成立，输入框会卡在编辑态
  cancelCustomId()
}

/** 拉取当前页。 */
async function loadChannelPage() {
  if (!channelTarget.value) return
  channelLoading.value = true
  try {
    const data = await cascadeApi.channelList({
      id: channelTarget.value.id,
      start: (channelPage.value - 1) * channelPageSize.value,
      limit: channelPageSize.value,
      q: channelKeyword.value.trim() || undefined,
      channel_type: channelType.value,
      related: channelOnlyRelated.value || undefined,
      sort: channelSort.prop,
      order: channelSort.order
    })
    const rows = data?.channelList || []
    // 附加唯一键：reserve-selection 靠 row-key 识别行
    channelRows.value = rows.map((r) => ({ ...r, rowKey: rowKeyOf(r) }))
    channelTotal.value = data?.channelCount || 0
    // 回填本页勾选。必须在下一个 tick：表格要先拿到 data 才能 toggleRowSelection
    await nextTick()
    const table = channelTableRef.value
    if (!table) return
    table.clearSelection()
    channelRows.value.forEach((r) => {
      if (channelSharedAll.value.has(rowKeyOf(r))) table.toggleRowSelection(r, true)
    })
  } catch (e) {
    ElMessage.error(e.message || '加载通道失败')
  } finally {
    channelLoading.value = false
  }
}

/** 筛选条件变了：回到第一页再查。 */
function reloadChannelList() {
  channelPage.value = 1
  loadChannelPage()
}

function onChannelPageSize() {
  channelPage.value = 1
  loadChannelPage()
}

function onChannelSort({ prop, order }) {
  // 取消排序时回落默认：按设备编码升序
  channelSort.prop = prop || 'deviceId'
  channelSort.order = order === 'descending' ? 'desc' : 'asc'
  reloadChannelList()
}

async function openChannels(row) {
  channelTarget.value = row
  shareAllLocal.value = Boolean(row.shareAllChannel)
  channelSelected.value = new Set()
  channelSharedAll.value = new Set()
  channelPage.value = 1
  channelDialog.value = true
  try {
    /*
     * 单独取一次「全部已共享通道」用于初始化勾选集合。
     * 不能只靠当前页的 cascadeId 判断：翻到第 5 页时前三页的已选状态就丢了，
     * 保存时会把这些通道当成「取消勾选」而删掉。
     */
    const shared = await cascadeApi.channelList({
      id: row.id,
      related: true,
      start: 0,
      limit: 0
    })
    const keys = (shared?.channelList || []).map((c) => rowKeyOf(c))
    channelSharedAll.value = new Set(keys)
    channelSelected.value = new Set(keys)
    await loadChannelPage()
  } catch (e) {
    ElMessage.error(e.message || '加载通道失败')
  }
}

function onChannelSelect(rows) {
  /*
   * 只更新「当前页覆盖到的那部分」：
   *   1. 先把本页所有行从已选集合里删掉（它们的状态由本次事件全权代表）
   *   2. 再把当前勾选的行加回去
   * 这样其它页的选择不受影响 —— 直接整体替换会丢掉跨页选择。
   */
  const next = new Set(channelSharedAll.value)
  channelRows.value.forEach((r) => next.delete(rowKeyOf(r)))
  rows.forEach((r) => next.add(rowKeyOf(r)))
  channelSharedAll.value = next
  channelSelected.value = new Set(next)
}

/**
 * 自定义通道国标编号的行内编辑状态。
 *
 * <p>同一时刻只允许编辑一行：`row` 保存正在编辑的那一行对象本身，
 * 提交时用引用比对确认「这次提交属于当前编辑行」——
 * 只用行号判断会在列表刷新/翻页后错位。
 */
const editingCustomId = reactive({
  row: null,
  value: '',
  saving: false
})
const customIdInputRef = ref(null)

/**
 * 通道当前「对外生效」的编号。
 *
 * <p>设置过自定义编号就用它，否则用原通道编号 —— 与列里的显示保持一致。
 * 编辑时必须基于这个值预填，不能用 {@code customId || ''}：
 * 大多数通道没有自定义编号，那样一进编辑框就是空的，
 * 用户还得把 20 位原编号手打一遍才能改一位，等于不能改。
 */
function effectiveChannelId(row) {
  return row.customId || row.id || ''
}

/** 进入行内编辑，光标定位到末尾（不是全选）。 */
async function startEditCustomId(row) {
  if (editingCustomId.saving) return
  editingCustomId.row = row
  editingCustomId.value = effectiveChannelId(row)
  await nextTick()
  const el = customIdInputRef.value
  const target = Array.isArray(el) ? el[0] : el
  if (!target) return
  target.focus?.()
  /*
   * 光标放在末尾而不是全部选中。
   *
   * 全选看着「方便重新输入」，实际很坑：编号是 20 位，最常见的操作是
   * 改末尾几位（按设备顺序微调）。全选状态下随手敲一个数字就把整串冲掉，
   * 接着就弹「必须是 20 位数字」，还得重来一遍。
   * 放末尾可以正常退格/方向键修改；要整体替换按 Ctrl+A 即可。
   */
  const inner = target.input || target.$el?.querySelector?.('input')
  const len = (editingCustomId.value || '').length
  try {
    inner?.setSelectionRange?.(len, len)
  } catch (e) {
    // 某些浏览器在非文本 input 上会抛错，忽略即可（光标停在末尾是默认行为）
  }
}

/** 退出行内编辑（不保存）。 */
function cancelCustomId() {
  editingCustomId.row = null
  editingCustomId.value = ''
}

/**
 * 提交行内编辑。
 *
 * <p>回车与失焦都会调到这里，必须幂等：
 * 回车后输入框会被移除，若同时触发 blur 就会重复提交。
 *
 * <p>判定「有没有真的改过」要比较<b>生效编号</b>而不是输入框原文：
 * 编辑框是带着原编号进去的，用户什么都没动就提交时，
 * 原文 {@code 34020000001320000001} 与 {@code customId=''} 并不相等，
 * 按原文比较会把每个通道都写成「自定义编号=原编号」的冗余记录。
 */
async function commitCustomId(row) {
  // 已经不是当前编辑行（Esc 取消、或已被另一次提交收尾）→ 直接忽略
  if (editingCustomId.row !== row || editingCustomId.saving) return
  const value = (editingCustomId.value || '').trim()
  const before = effectiveChannelId(row)
  // 留空 = 恢复使用原通道编号
  const after = value || (row.id || '')
  if (after === before) {
    cancelCustomId()
    return
  }
  if (value && !/^\d{20}$/.test(value)) {
    // 保持编辑态：清空用户刚敲的内容比让他继续改更烦人
    ElMessage.warning('国标编号必须是 20 位数字，留空表示使用原编号')
    await nextTick()
    const el = customIdInputRef.value
    ;(Array.isArray(el) ? el[0] : el)?.focus?.()
    return
  }
  editingCustomId.saving = true
  try {
    /*
     * 与通道原编号相同时存 null 而不是原样存下来：
     * 存了也能显示对，但库里会多出一条「自定义编号 = 原编号」的无意义记录，
     * 日后排查「这条通道到底被人改过没有」时会造成误判。
     */
    const toSave = value === row.id ? '' : value
    // 必须带 serial：通道编码只在单设备内唯一，只传 code 会改到别的设备的同号通道
    await deviceApi.setChannelId({
      serial: row.deviceId,
      code: row.id,
      id: toSave
    })
    row.customId = toSave || null
    ElMessage.success('已保存，重新推送目录后上级生效')
    cancelCustomId()
  } catch (e) {
    ElMessage.error(e.message || '保存失败')
  } finally {
    editingCustomId.saving = false
  }
}

function previewSnap(row) {
  if (!row.snapshot) return
  window.open(row.snapshot, '_blank')
}

async function saveChannels() {
  if (!channelTarget.value) return
  channelSaving.value = true
  try {
    // 「全部共享」是级联配置上的开关，与具体通道列表互斥，先写它
    if (shareAllLocal.value !== Boolean(channelTarget.value.shareAllChannel)) {
      await cascadeApi.setShareAllChannel({
        id: channelTarget.value.id,
        shareallchannel: shareAllLocal.value
      })
      channelTarget.value.shareAllChannel = shareAllLocal.value
    }
    const keys = [...channelSharedAll.value]
    /*
     * 不勾任何通道时给出明确警告：上级会看不到本级的通道，
     * 而界面上只会显示「已选 0 路」，容易被误当成「还没加载完」。
     */
    if (keys.length === 0 && !shareAllLocal.value) {
      ElMessage.warning('未选择任何通道，上级将看不到本级的通道')
    }
    // 后端契约是全量覆盖（channels[] 传 serial:code），取消勾选也能生效
    await cascadeApi.saveChannels({
      id: channelTarget.value.id,
      'channels[]': keys
    })
    ElMessage.success(
      `已保存 ${keys.length} 路共享通道，可点「推送目录」通知上级`
    )
    channelDialog.value = false
    load()
  } catch (e) {
    ElMessage.error(e.message || '保存失败')
  } finally {
    channelSaving.value = false
  }
}

async function removeOne(row) {
  if (!(await confirmAction(`确定删除级联「${row.name || row.serial}」？`, '删除确认',
    { confirmButtonText: '删除' }))) return
  try {
    await cascadeApi.remove(row.id)
    ElMessage.success('已删除')
    load()
  } catch (e) {
    ElMessage.error(e.message || '删除失败')
  }
}

onMounted(load)
</script>

<style scoped>
/* .card-header 由 styles/main.css 统一提供 */

/* 对话框高度自适应：内容较长时可滚动，不撑破视口。
   注意必须同时约束 .el-dialog 本身（它是 flex 容器，body 才能拿到剩余高度），
   只给 __body 加 max-height 在 flex 布局下不生效，底部按钮会被挤出视口。 */
:deep(.cascade-dialog.el-dialog) {
  display: flex;
  flex-direction: column;
  max-height: 88vh;
  margin-bottom: 0;
}

:deep(.cascade-dialog .el-dialog__body) {
  flex: 1 1 auto;
  min-height: 0;
  overflow-y: auto;
  padding-top: var(--sp-2);
}

/* 分组：每组一个浅色标题 + 分隔线，避免十几个字段糊成一片 */
.form-section {
  padding-bottom: var(--sp-2);
  margin-bottom: var(--sp-2);
  border-bottom: 1px dashed var(--line-soft);
}

.form-section:last-child {
  border-bottom: none;
  margin-bottom: 0;
  padding-bottom: 0;
}

.form-section-title {
  display: flex;
  align-items: baseline;
  gap: var(--sp-2);
  margin: var(--sp-2) 0 var(--sp-3);
  font-size: var(--fs-sm);
  font-weight: 600;
  color: var(--text-secondary);
  letter-spacing: 0.02em;
}

.form-section-hint {
  font-size: var(--fs-xs);
  font-weight: 400;
  color: var(--text-tertiary);
}

/* 开关类字段右侧的说明文字 */
.form-item-hint {
  margin-left: var(--sp-2);
  font-size: var(--fs-xs);
  color: var(--text-tertiary);
}

/* 让输入框/数字框/下拉框撑满整行可用宽度 */
.full-width {
  width: 100%;
}

:deep(.cascade-dialog .el-input-number.full-width) {
  width: 100%;
}

/* 数字输入框的增减按钮在窄宽度下会挤压数值，这里留足右侧空间 */
:deep(.cascade-dialog .el-input-number .el-input__inner) {
  text-align: left;
  padding-right: 40px;
}

/* 开关行垂直居中对齐，避免与标签错位 */
:deep(.cascade-dialog .el-form-item) {
  margin-bottom: var(--sp-3);
}

:deep(.cascade-dialog .el-form-item:last-child) {
  margin-bottom: 0;
}

/* ==================== 共享通道选择 ==================== */

/*
 * 顶部工具条：搜索在左、批量操作在右。
 * 通道数量可能上百，不提供搜索就只能一屏屏翻 —— 而现场往往只记得通道名。
 */
.ch-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sp-3);
  margin-bottom: var(--sp-3);
  flex-wrap: wrap;
}

/* 弹窗顶部工具栏：搜索 / 类型 / 只看已选 / 全部共享 */
.ch-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sp-3);
  margin-bottom: var(--sp-3);
  flex-wrap: wrap;
}

.ch-toolbar-left {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  flex-wrap: wrap;
}

.ch-label {
  font-size: var(--fs-sm);
  color: var(--text-2, #606266);
  white-space: nowrap;
}

/* 快照缩略图：与上级平台一致，固定 64×36（16:9），点开看大图 */
.ch-snap {
  width: 64px;
  height: 36px;
  object-fit: cover;
  border-radius: 3px;
  cursor: pointer;
  display: block;
  margin: 0 auto;
}

/* 无快照时的占位：不现抓（列表几百条会打爆设备），只提示「没有」 */
.ch-snap.is-empty {
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--fill-2, #f5f7fa);
  color: var(--text-3, #c0c4cc);
  cursor: default;
  font-size: 16px;
}

/* 在线绿色 / 离线灰色：与上级平台的配色习惯一致，
   不用 badge 圆点是因为通道列表行高小，圆点太占视觉。 */
.ch-online {
  color: var(--success-600, #16a34a);
  font-weight: 600;
}

.ch-offline {
  color: var(--text-3, #c0c4cc);
}

/* 分页右对齐：左侧留白让「共 N 条」不贴着表格边框 */
.ch-pager {
  display: flex;
  justify-content: flex-end;
  margin-top: var(--sp-3);
}

/* 行内编辑输入框：与单元格等宽，不留内边距造成的错位 */
.cid-input {
  width: 100%;
}

.cid-input :deep(.el-input__wrapper) {
  padding: 0 var(--sp-2);
}

/* 「全部共享」开启时表格仅作浏览：勾选不生效，操作它只会造成误解 */
:deep(.is-disabled) .el-table__body,
:deep(.is-disabled) .el-table__header {
  opacity: 0.55;
}

.ch-head-right {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
}

/* 已选数量：等宽数字，勾选时不会因位数变化而左右抖动 */
.ch-count {
  font-family: var(--mono);
  font-size: var(--fs-sm);
  font-weight: 600;
  color: var(--brand-600, var(--brand-500));
}

.ch-alert {
  margin-bottom: var(--sp-3);
}
</style>
