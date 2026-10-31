<template>
  <!--
    云端录像「列表视图」：独立页面（对齐 LiveGBS 的
    #/cloudrecord/listview/<设备编码>/<通道编码>/<日期>）。

    与时间轴页的区别：这里就是一张录像明细表，不带播放器。
  -->
  <div class="page is-fill">
    <el-card shadow="never">
      <!-- 标题：居中 -->
      <div class="box-title">
        云端录像({{ channelName }})-列表视图
      </div>

      <!-- 工具条：左 返回/删除当天，右 日期选择器 + 时间轴视图 -->
      <div class="filter-bar">
        <el-button size="small" @click="goBack">
          <el-icon><ArrowLeft /></el-icon> 返回
        </el-button>
        <el-button size="small" type="danger" plain :disabled="!records.length" @click="removeDaily">
          <el-icon><Delete /></el-icon> 删除当天
        </el-button>
        <span class="filter-bar-spacer" />

        <!-- 日期选择器：没录像的日期禁用 -->
        <el-date-picker
          v-model="period"
          type="date"
          placeholder="选择日期"
          value-format="YYYY-MM-DD"
          class="date-picker"
          :clearable="false"
          :disabled-date="cellDisabled"
        >
          <template #default="cell">
            <div class="cal-cell" :class="{ 'has-record': cellHasRecord(cell) }">
              {{ cell.text }}
            </div>
          </template>
        </el-date-picker>

        <!-- 切回时间轴视图 -->
        <el-button size="small" @click="goTimeline">
          <el-icon><Clock /></el-icon> 时间轴视图
        </el-button>
      </div>

      <el-table
        ref="tableRef"
        v-loading="loading"
        :data="pagedRecords"
        stripe
        class="list-table"
      >
        <el-table-column prop="startAt" label="开始时间" min-width="180" sortable>
          <template #default="{ row }"><span class="mono">{{ fmtCompactTs(row.startAt) }}</span></template>
        </el-table-column>
        <el-table-column prop="endTime" label="结束时间" min-width="180" sortable>
          <template #default="{ row }"><span class="mono">{{ fmtCompactTs(row.endTime) }}</span></template>
        </el-table-column>
        <el-table-column prop="duration" label="录像时长" width="120" align="center" sortable>
          <template #default="{ row }">{{ fmtDuration(row) }}</template>
        </el-table-column>
        <el-table-column prop="fileSize" label="收流大小" width="120" align="center" sortable>
          <template #default="{ row }">{{ formatSize(row.fileSize) }}</template>
        </el-table-column>
        <el-table-column label="存储" width="100" align="center">
          <template #default="{ row }">
            <el-tag :type="row.storageType === 'OSS' ? 'success' : 'info'" size="small" effect="plain">
              {{ row.storageType === 'OSS' ? '对象存储' : '本地' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="上传状态" width="100" align="center">
          <template #default="{ row }">
            <el-tag :type="uploadColor(row.uploadState)" size="small" effect="light">
              {{ uploadText(row.uploadState) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="重要标记" width="90" align="center">
          <template #default="{ row }">
            <el-icon
              :size="18"
              :color="row.important ? '#e6a23c' : '#c0c4cc'"
              :class="canWrite ? 'star' : ''"
              @click="canWrite && toggleImportant(row)"
            >
              <StarFilled v-if="row.important" />
              <Star v-else />
            </el-icon>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="200" align="center" fixed="right">
          <template #default="{ row }">
            <el-button
              link
              type="primary"
              :disabled="row.recording || row.storageType === 'OSS'"
              @click="play(row)"
            >
              <el-icon><VideoPlay /></el-icon> 播放
            </el-button>
            <el-button
              link
              type="primary"
              :disabled="row.recording || !row.downloadUrl"
              @click="download(row)"
            >
              <el-icon><Download /></el-icon> 下载
            </el-button>
            <el-button v-if="canDelete" link type="danger" @click="removeOne(row)">
              <el-icon><Delete /></el-icon> 删除
            </el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty :image-size="76" description="当日无云端录像" />
        </template>
      </el-table>

      <TablePager
        :pager="pager"
        unit="条录像"
        @page-change="onPageChange"
        @size-change="onSizeChange"
      />
    </el-card>

    <!--
      点播弹窗。

      <p><b>为什么用弹窗而不是跳转时间轴页</b>：列表视图的用途是「翻记录、
      找某一段、看一眼」——跳走再回来会丢掉当前页、筛选和滚动位置，
      来回翻十几次很折磨人。弹窗就地播放，关掉还在原处。

      <p>{@code destroy-on-close} 让关闭时整块 DOM 消失，配合
      {@code @closed} 释放播放器（不停会继续下载录像数据）。
    -->
    <el-dialog
      v-model="playerVisible"
      :title="playerTitle"
      width="min(1000px, 94vw)"
      top="6vh"
      destroy-on-close
      append-to-body
      class="record-player-dialog"
      @closed="onPlayerClosed"
    >
      <div class="video-box">
        <div ref="dialogVideoContainer" class="video-container" />
      </div>
      <div class="player-bar">
        <span class="mono">{{ playingRecord ? fmtCompactTs(playingRecord.startAt) : '' }}</span>
        <span v-if="playingRecord?.endTime" class="text-dim">
          至 {{ fmtCompactTs(playingRecord.endTime) }}
        </span>
        <el-tag :type="playStateType" size="small">{{ playState }}</el-tag>
        <span class="player-bar-spacer" />
        <!--
          这里不设「停止」按钮：弹窗关闭时会自动停播并释放播放器
          （见 onPlayerClosed），单独放一个停止键属于重复入口。
        -->
        <el-button
          size="small"
          :disabled="!canDownloadRecord(playingRecord)"
          @click="download(playingRecord)"
        >
          <el-icon><Download /></el-icon> 下载本段
        </el-button>
      </div>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { confirmAction } from '@/utils/confirm'
import { cloudRecordApi } from '@/api'
import { useAuthStore } from '@/stores/auth'
import { useFillTableRows } from '@/composables/useFillTableRows'
import { usePagination } from '@/composables/usePagination'
import { useLivePlayer } from '@/composables/useLivePlayer'
import {
  canDownloadRecord,
  downloadRecord,
  fmtCompactTs,
  formatSize,
  isDisabledDate,
  isRecordDay,
  parseCompactTs,
  recordDaysOf,
  todayString,
  uploadColor,
  uploadText
} from '@/utils/cloudRecord'
import TablePager from '@/components/TablePager.vue'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

/** 重要标记：可撤销，cloud:write 即可。 */
const canWrite = computed(() => auth.can('cloud:write'))
/** 删除录像：不可恢复，cloud:delete。 */
const canDelete = computed(() => auth.can('cloud:delete'))

const records = ref([])
const monthFlags = ref('')
const channelName = ref('')
const loading = ref(false)

const tableRef = ref(null)

/**
 * 前端分页。
 *
 * <p>{@code queryDaily} 一次返回当天的全部录像段（一天也就二三十段），
 * 所以这里不往后端要分页参数，直接在浏览器里切页。
 */
const { pager, setTotal, onPageChange, onSizeChange } = usePagination({ pageSize: 10 })

/** 当前页要显示的录像段。 */
const pagedRecords = computed(() => {
  const start = (pager.page - 1) * pager.size
  return records.value.slice(start, start + pager.size)
})

useFillTableRows(tableRef, () => pagedRecords.value.length)

/** 通道标识：设备编码 / 通道编码。 */
const serial = computed(() => String(route.query.serial || ''))
const code = computed(() => String(route.query.code || ''))

const period = ref(String(route.query.period || todayString()))

/** 当前 monthFlags 对应的月份（YYYYMM），用于判断是否需要重新拉标记。 */
const loadedMonth = ref('')
/** 当前 monthFlags 对应的月份（YYYY-MM），日历高亮/禁用时比对用。 */
const flagsMonth = ref('')

/** 本月有录像的日期。 */
const recordDaySet = computed(() => new Set(recordDaysOf(monthFlags.value)))

/** 日历格子是否有录像 / 是否禁用（绑定到日期选择器）。 */
const cellHasRecord = (cell) => isRecordDay(cell, flagsMonth.value, recordDaySet.value)
const cellDisabled = (date) => isDisabledDate(date, flagsMonth.value, recordDaySet.value)

watch(period, async (value, old) => {
  if (!value || value === old) return
  const month = value.slice(0, 7).replace('-', '')
  if (month !== loadedMonth.value) {
    await loadMonthFlags()
  }
  await loadDaily()
})

/* ==================== 数据加载 ==================== */

async function loadDaily() {
  if (!serial.value || !code.value) {
    ElMessage.warning('缺少通道参数')
    return
  }
  loading.value = true
  try {
    const data = await cloudRecordApi.queryDaily({
      serial: serial.value,
      code: code.value,
      period: period.value.replace(/-/g, '')
    })
    records.value = data?.list || []
    setTotal(records.value.length)
    if (data?.name) channelName.value = data.name
  } catch (e) {
    ElMessage.error(e.message || '查询失败')
  } finally {
    loading.value = false
  }
}

async function loadMonthFlags() {
  if (!serial.value || !code.value) return
  const month = period.value.slice(0, 7).replace('-', '')
  loadedMonth.value = month
  flagsMonth.value = period.value.slice(0, 7)
  try {
    const data = await cloudRecordApi.queryMonthly({ serial: serial.value, code: code.value, period: month })
    monthFlags.value = data?.flag || ''
  } catch (e) {
    monthFlags.value = ''
  }
}

/* ==================== 格式化 ==================== */

/* fmtCompactTs / formatSize / uploadText / uploadColor 见 utils/cloudRecord.js */

/** 录像时长：优先用 duration（秒），否则按起止时间算。 */
function fmtDuration(row) {
  let secs = row.duration
  if (secs == null && row.startAt && row.endTime) {
    const s = parseCompactTs(row.startAt)
    const e = parseCompactTs(row.endTime)
    if (s && e && e > s) secs = Math.round((e - s) / 1000)
  }
  if (secs == null) return '—'
  const h = Math.floor(secs / 3600)
  const m = Math.floor((secs % 3600) / 60)
  const s = secs % 60
  if (h) return `${h}:${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`
  return `${m}:${String(s).padStart(2, '0')}`
}

/* ==================== 操作 ==================== */

/* ---------- 点播弹窗 ---------- */

/** 弹窗是否显示。 */
const playerVisible = ref(false)
/** 当前在播的录像（弹窗底栏展示用）。 */
const playingRecord = ref(null)
/** 播放器挂载容器（弹窗内，dialog 打开后才有 DOM）。 */
const dialogVideoContainer = ref(null)

/** 弹窗标题：通道名 + 起止时间。 */
const playerTitle = computed(() => {
  const r = playingRecord.value
  if (!r) {
    return '录像回放'
  }
  const span = r.endTime ? `${fmtCompactTs(r.startAt)} 至 ${fmtCompactTs(r.endTime)}` : fmtCompactTs(r.startAt)
  return `${channelName.value || '录像回放'} · ${span}`
})

/** 播放状态（展示用，直接映射播放器状态）。 */
const playState = computed(() => recordPlayer.state.value)
const playStateType = computed(() => {
  if (playState.value === '播放中') return 'success'
  if (playState.value === '播放异常') return 'danger'
  return 'info'
})

/**
 * 弹窗内的播放器。
 *
 * <p>与实时预览、录像回放是<b>同一个播放器</b>（LivePlayer）：
 * 云端录像是标准 mp4，走 LivePlayer 的官方点播通路
 * （{@code live=false} 显示进度条，支持拖动定位）。
 */
const recordPlayer = useLivePlayer(dialogVideoContainer, {
  onFatal: (msg) => ElMessage.error('播放失败：' + msg)
})

/**
 * 弹窗点播一段录像。
 *
 * <p>注意 {@code nextTick}：播放器容器在 {@code el-dialog} 内部，
 * 而 dialog 默认是<b>懒渲染</b>的 —— 没打开过时 DOM 根本不存在，
 * 直接拿去建播放器会拿到 null 容器。先开弹窗、等渲染完再播。
 *
 * @param {object} row 录像记录
 */
async function play(row) {
  playingRecord.value = { ...row }
  playerVisible.value = true
  await nextTick()
  /*
   * 静态地址（/record/**）由 nginx sendfile 直读，鉴权走地址签名
   * （后端返回的 playUrl 已带 ?e=…&t=…），不需要 token；
   * 只有退回受保护接口时才带。
   */
  const authToken = row.playUrl ? '' : (localStorage.getItem('gb_token') || '')
  const source = row.playUrl || `/api/v1/cloudrecord/file/${row.recordId}`
  try {
    await recordPlayer.playFile(source, { token: authToken })
  } catch (e) {
    ElMessage.error(e.message || '点播失败')
  }
}

/**
 * 弹窗完全关闭后释放播放器。
 *
 * <p>用 {@code @closed} 而不是 {@code @close}：{@code close} 在动画开始时就触发，
 * 那时 DOM 还在；{@code closed} 是动画结束、内容真被销毁之后。
 * 不释放的话，播放器会继续下载录像数据（自控下载是持续的 Range 请求）。
 */
function onPlayerClosed() {
  recordPlayer.stop()
  playingRecord.value = null
}

/** 下载一段录像（判据与动作统一在 {@code utils/cloudRecord.js}，与时间轴页共用）。 */
function download(row) {
  downloadRecord(row, channelName.value)
}

async function toggleImportant(record) {
  try {
    await cloudRecordApi.setImportant({
      serial: serial.value,
      code: code.value,
      period: record.startAt,
      important: !record.important
    })
    record.important = !record.important
    ElMessage.success(record.important ? '已标记为重要' : '已取消重要标记')
  } catch (e) {
    ElMessage.error(e.message || '操作失败')
  }
}

async function removeOne(record) {
  if (!(await confirmAction(
    `确定删除该录像（${fmtCompactTs(record.startAt)}）？文件将同时删除。`,
    '删除确认',
    { confirmButtonText: '删除' }
  ))) return
  try {
    await cloudRecordApi.remove({ serial: serial.value, code: code.value, period: record.startAt })
    ElMessage.success('已删除')
    await loadDaily()
    await loadMonthFlags()
  } catch (e) {
    ElMessage.error(e.message || '删除失败')
  }
}

async function removeDaily() {
  if (!(await confirmAction(
    `确定删除 ${period.value} 当天的全部录像？`,
    '删除确认',
    { confirmButtonText: '删除' }
  ))) return
  try {
    const data = await cloudRecordApi.removeDaily({
      serial: serial.value,
      code: code.value,
      period: period.value.replace(/-/g, '')
    })
    ElMessage.success(`已删除 ${data?.deleted ?? 0} 条`)
    await loadDaily()
    await loadMonthFlags()
  } catch (e) {
    ElMessage.error(e.message || '删除失败')
  }
}

function goBack() {
  router.push('/cloud')
}

function goTimeline() {
  router.push({
    path: '/cloud/detail',
    query: { serial: serial.value, code: code.value, period: period.value }
  })
}

onMounted(async () => {
  await loadMonthFlags()
  await loadDaily()
})
</script>

<style scoped>
/*
 * 表格与工具条沿用全局样式（.filter-bar / .el-table），
 * 与「设备管理」「通道管理」保持一致。
 */
.box-title {
  text-align: center;
  font-size: 15px;
  font-weight: 600;
  color: var(--text-primary);
  padding: 4px 0 12px;
}

/* 日历里有录像的日期：蓝底高亮 */
.cal-cell {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 4px;
  font-size: 13px;
}

.cal-cell.has-record {
  background: var(--brand-500);
  color: #fff;
  font-weight: 600;
}

/* 没有录像的日期：置灰 + 禁止点击 */
.date-picker :deep(.el-date-table td.disabled .cal-cell) {
  color: var(--text-disabled, #c0c4cc);
  cursor: not-allowed;
  background: transparent;
  text-decoration: line-through;
  text-decoration-color: var(--line);
}

.date-picker :deep(.el-date-table td.disabled) {
  cursor: not-allowed;
}

.date-picker {
  width: 160px;
}

.star {
  cursor: pointer;
}

/* ==================== 点播弹窗 ==================== */

/*
 * 弹窗内容的样式必须用 :global()。
 *
 * <p>因为 el-dialog 挂了 {@code append-to-body}：DOM 实际挂在 {@code <body>}
 * 下，不在本组件的模板树里，拿不到 scoped 的 {@code data-v-*} 属性 ——
 * 写成普通 scoped 规则会编译成 {@code .player-bar[data-v-xxx]}，永远匹配不上
 * （表现就是「样式写了但没生效」）。
 */

/* 播放器控制条：`video-box` / `video-container` 用全局样式，这行是弹窗独有 */
:global(.record-player-dialog .player-bar) {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 10px;
  flex-wrap: wrap;
  font-size: var(--fs-sm);
  color: var(--text-secondary);
}

/* 把「停止 / 下载本段」推到右边 */
:global(.record-player-dialog .player-bar-spacer) {
  flex: 1;
}

/*
 * 弹窗正文去掉默认的上下内边距 —— 16:9 视频本身就有高度，
 * 默认 padding 会把弹窗撑出滚轮。
 */
:global(.record-player-dialog .el-dialog__body) {
  padding-top: 8px;
  padding-bottom: 4px;
}
</style>
