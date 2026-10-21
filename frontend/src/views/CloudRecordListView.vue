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
          :disabled-date="isDisabledDate"
        >
          <template #default="cell">
            <div class="cal-cell" :class="{ 'has-record': isRecordDay(cell) }">
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
        :data="records"
        stripe
        class="list-table"
      >
        <el-table-column prop="startAt" label="开始时间" min-width="180" sortable>
          <template #default="{ row }"><span class="mono">{{ fmtTs(row.startAt) }}</span></template>
        </el-table-column>
        <el-table-column prop="endTime" label="结束时间" min-width="180" sortable>
          <template #default="{ row }"><span class="mono">{{ fmtTs(row.endTime) }}</span></template>
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
              class="star"
              @click="toggleImportant(row)"
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
            <el-button link type="danger" @click="removeOne(row)">
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
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { cloudRecordApi } from '@/api'
import { useFillTableRows } from '@/composables/useFillTableRows'
import TablePager from '@/components/TablePager.vue'

const route = useRoute()
const router = useRouter()

const records = ref([])
const monthFlags = ref('')
const channelName = ref('')
const loading = ref(false)
const query = reactive({ q: '' })

const tableRef = ref(null)
useFillTableRows(tableRef, () => records.value.length)

/** 通道标识：设备编码 / 通道编码。 */
const serial = computed(() => String(route.query.serial || ''))
const code = computed(() => String(route.query.code || ''))

const period = ref(String(route.query.period || todayString()))

/** 当前 monthFlags 对应的月份（YYYYMM），用于判断是否需要重新拉标记。 */
const loadedMonth = ref('')
/** 当前 monthFlags 对应的月份（YYYY-MM），日历高亮/禁用时比对用。 */
const flagsMonth = ref('')

/** 本月有录像的日期。 */
const recordDays = computed(() =>
  monthFlags.value
    .split('')
    .map((flag, idx) => (flag === '1' ? idx + 1 : 0))
    .filter(Boolean)
)
const recordDaySet = computed(() => new Set(recordDays.value))

function todayString() {
  const d = new Date()
  const pad = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
}

/** 日历格子是否有录像。 */
function isRecordDay(cell) {
  if (!cell) return false
  let dateStr = ''
  if (cell.dayjs && typeof cell.dayjs.format === 'function') {
    dateStr = cell.dayjs.format('YYYY-MM-DD')
  } else if (typeof cell === 'string') {
    dateStr = cell
  } else if (cell.value) {
    dateStr = String(cell.value)
  }
  if (!dateStr) return false
  if (dateStr.slice(0, 7) !== flagsMonth.value) return false
  return recordDaySet.value.has(Number(dateStr.slice(8, 10)))
}

/** 禁用「没有录像」的日期。 */
function isDisabledDate(date) {
  if (!date) return false
  const d = date instanceof Date ? date : new Date(date)
  if (Number.isNaN(d.getTime())) return false
  const ym = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`
  if (ym !== flagsMonth.value) return false
  return !recordDaySet.value.has(d.getDate())
}

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

function fmtTs(v) {
  if (!v) return '—'
  const t = String(v).trim()
  if (t.length !== 14 || !/^\d{14}$/.test(t)) return t
  return `${t.slice(0, 4)}-${t.slice(4, 6)}-${t.slice(6, 8)} ${t.slice(8, 10)}:${t.slice(10, 12)}:${t.slice(12, 14)}`
}

/** 录像时长：优先用 duration（秒），否则按起止时间算。 */
function fmtDuration(row) {
  let secs = row.duration
  if (secs == null && row.startAt && row.endTime) {
    const s = parseTs(row.startAt)
    const e = parseTs(row.endTime)
    if (s && e && e > s) secs = Math.round((e - s) / 1000)
  }
  if (secs == null) return '—'
  const h = Math.floor(secs / 3600)
  const m = Math.floor((secs % 3600) / 60)
  const s = secs % 60
  if (h) return `${h}:${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`
  return `${m}:${String(s).padStart(2, '0')}`
}

function parseTs(v) {
  const t = String(v || '').trim()
  if (!/^\d{14}$/.test(t)) return null
  return new Date(
    +t.slice(0, 4), +t.slice(4, 6) - 1, +t.slice(6, 8),
    +t.slice(8, 10), +t.slice(10, 12), +t.slice(12, 14)
  )
}

function formatSize(size) {
  if (!size) return '—'
  const units = ['B', 'KB', 'MB', 'GB']
  let n = size
  let i = 0
  while (n >= 1024 && i < units.length - 1) {
    n /= 1024
    i++
  }
  return n.toFixed(i === 0 ? 0 : 1) + ' ' + units[i]
}

const UPLOAD_TEXT = { NONE: '无需上传', PENDING: '待上传', UPLOADED: '已上传', FAILED: '上传失败' }

function uploadText(state) {
  return UPLOAD_TEXT[state] || '—'
}

function uploadColor(state) {
  if (state === 'UPLOADED') return 'success'
  if (state === 'PENDING') return 'warning'
  if (state === 'FAILED') return 'danger'
  return 'info'
}

/* ==================== 操作 ==================== */

/** 播放：跳到时间轴页并自动点播那一段（?play=<recordId>）。 */
function play(row) {
  router.push({
    path: '/cloud/detail',
    query: {
      serial: serial.value,
      code: code.value,
      period: period.value,
      play: row.recordId
    }
  })
}

function download(row) {
  const url = row.downloadUrl
  if (!url) {
    ElMessage.warning('该录像暂不可下载（可能仍在录制）')
    return
  }
  if (url.startsWith('http://') || url.startsWith('https://')) {
    window.open(url, '_blank')
    return
  }
  const token = localStorage.getItem('gb_token') || ''
  fetch(url, { headers: { Authorization: token } })
    .then((resp) => {
      if (!resp.ok) throw new Error('下载失败 HTTP ' + resp.status)
      return resp.blob()
    })
    .then((blob) => {
      const link = document.createElement('a')
      link.href = URL.createObjectURL(blob)
      link.download = row.fileName || row.name || 'record.mp4'
      link.click()
      URL.revokeObjectURL(link.href)
    })
    .catch((e) => ElMessage.error(e.message || '下载失败'))
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
  try {
    await ElMessageBox.confirm(
      `确定删除该录像（${fmtTs(record.startAt)}）？文件将同时删除。`,
      '删除确认',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
  } catch (e) {
    return
  }
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
  try {
    await ElMessageBox.confirm(
      `确定删除 ${period.value} 当天的全部录像？`,
      '删除确认',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
  } catch (e) {
    return
  }
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
</style>
