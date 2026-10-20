<template>
  <div class="page is-fill">
    <header class="page-header">
      <div class="page-title-group">
        <h2 class="page-title">操作日志</h2>
        <p class="page-subtitle">
          记录接口调用：方式、资源路径、远端地址、结果与用时（保留 {{ retainDays }} 天，自动清理）
        </p>
      </div>
      <div class="page-actions">
        <el-button size="small" :loading="loading" @click="load">
          <el-icon><Refresh /></el-icon> 刷新
        </el-button>
        <el-button size="small" type="danger" plain :disabled="!pager.total" @click="clearAll">
          <el-icon><Delete /></el-icon> 清空
        </el-button>
      </div>
    </header>

    <!-- 筛选栏 -->
    <div class="filter-bar">
      <el-select v-model="query.method" placeholder="全部方式" style="width: 140px" @change="search">
        <el-option label="全部方式" value="" />
        <el-option label="HTTP GET" value="HTTP_GET" />
        <el-option label="HTTP POST" value="HTTP_POST" />
      </el-select>
      <el-date-picker
        v-model="query.dateRange"
        type="daterange"
        range-separator="-"
        start-placeholder="开始日期"
        end-placeholder="结束日期"
        value-format="YYYY-MM-DD"
        style="width: 260px"
        @change="search"
      />
      <el-input
        v-model="query.keyword"
        placeholder="路径 / 远端 / 操作人"
        clearable
        style="width: 240px"
        @keyup.enter="search"
      >
        <template #prefix><el-icon><Search /></el-icon></template>
      </el-input>
      <el-button type="primary" @click="search">
        <el-icon><Search /></el-icon> 查询
      </el-button>
    </div>

    <el-card shadow="never">
      <el-table v-loading="loading" :data="rows" stripe table-layout="fixed">
        <el-table-column label="操作名称" width="110" show-overflow-tooltip>
          <template #default="{ row }">
            <span v-if="row.operationName" class="op-name">{{ row.operationName }}</span>
            <span v-else class="text-dim">—</span>
          </template>
        </el-table-column>
        <el-table-column label="调用方式" width="120">
          <template #default="{ row }">
            <span class="badge" :class="row.method === 'HTTP_POST' ? 'is-brand' : ''">
              {{ methodLabel(row.method) }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="资源路径" min-width="230" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="mono">{{ row.resource }}</span>
          </template>
        </el-table-column>
        <el-table-column label="远端地址" min-width="130" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="mono">{{ row.remoteAddr || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="结果" width="100" align="center">
          <template #default="{ row }">
            <span class="badge" :class="isOk(row.result) ? 'is-online' : 'is-offline'">
              {{ resultLabel(row.result) }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="操作人" width="100" show-overflow-tooltip>
          <template #default="{ row }">
            <span>{{ row.operator || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="用时" width="90" align="right" sortable :sort-by="(r) => r.durationMs">
          <template #default="{ row }">
            <span class="mono">{{ formatDuration(row.durationMs) }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="createTime" label="操作时间" width="170" sortable>
          <template #default="{ row }">
            <span class="mono time-cell">{{ formatTime(row.createTime) }}</span>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty image-size="76" description="暂无操作日志" />
        </template>
      </el-table>

      <TablePager
        :pager="pager"
        unit="条记录"
        @page-change="onPageChange"
        @size-change="onSizeChange"
      />
    </el-card>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { opLogApi } from '@/api'
import TablePager from '@/components/TablePager.vue'
import { usePagination } from '@/composables/usePagination'

const rows = ref([])
const loading = ref(false)
const query = reactive({ method: '', keyword: '', dateRange: null })
/** 保留天数（与后端 gb28181.starter.oplog-retain-days 对应，仅用于页面提示）。 */
const retainDays = 7

const { pager, offset, reset, setTotal, onPageChange, onSizeChange } = usePagination({
  pageSize: 10,
  load: () => load()
})

/** 日期范围 → 后端 begin/end（含首尾整天）。 */
function timeRangeParams() {
  const r = query.dateRange
  if (!r || !r.length) return {}
  return { begin: `${r[0]} 00:00:00`, end: `${r[1]} 23:59:59` }
}

/** 时间串截到秒（后端带微秒，表格里只展示到秒更清爽）。 */
function formatTime(v) {
  return v ? String(v).slice(0, 19) : '—'
}

async function load() {
  loading.value = true
  try {
    const data = await opLogApi.list({
      start: offset.value,
      limit: pager.size,
      method: query.method || undefined,
      keyword: query.keyword || undefined,
      ...timeRangeParams()
    })
    rows.value = data?.opLogList || []
    setTotal(data?.opLogCount ?? 0)
  } catch (e) {
    ElMessage.error(e.message || '加载失败')
  } finally {
    loading.value = false
  }
}

/** 条件变化后回到第一页再查。 */
function search() {
  reset()
  load()
}

async function clearAll() {
  try {
    await ElMessageBox.confirm('确定清空全部操作日志？该操作不可恢复。', '清空确认', {
      type: 'warning',
      confirmButtonText: '清空',
      cancelButtonText: '取消'
    })
  } catch (e) {
    return
  }
  try {
    const data = await opLogApi.clear()
    ElMessage.success(`已清空 ${data?.deleted ?? 0} 条`)
    search()
  } catch (e) {
    ElMessage.error(e.message || '清空失败')
  }
}

function methodLabel(method) {
  if (method === 'HTTP_GET') return 'HTTP GET'
  if (method === 'HTTP_POST') return 'HTTP POST'
  return method || '—'
}

/** 结果是否正常（HTTP 2xx / 3xx 视为正常）。 */
function isOk(result) {
  return !!result && /^[23]\d\d$/.test(result)
}

function resultLabel(result) {
  if (!result) return '—'
  if (isOk(result)) return `${result} OK`
  return result
}

function formatDuration(ms) {
  if (ms == null) return '—'
  if (ms < 1000) return `${ms} ms`
  return `${(ms / 1000).toFixed(2)} s`
}

onMounted(load)
</script>

<style scoped>
/* 操作名称：加粗引导阅读（对齐 LiveGBS 的「操作名称」列） */
.op-name {
  font-weight: 600;
  color: var(--ink-800, #303133);
}

/* 时间列：整行显示，避免换行撑高 */
.time-cell {
  white-space: nowrap;
}
</style>
