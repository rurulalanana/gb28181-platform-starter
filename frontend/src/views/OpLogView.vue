<template>
  <div class="page is-fill">

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
      <span class="filter-bar-spacer" />
      <el-button size="small" :loading="loading" @click="load">
        <el-icon><Refresh /></el-icon> 刷新
      </el-button>
      <el-button
        v-if="canClear"
        size="small"
        type="danger"
        plain
        :disabled="!pager.total"
        @click="clearAll"
      >
        <el-icon><Delete /></el-icon> 清空
      </el-button>
    </div>

    <el-card shadow="never">
      <el-table ref="tableRef" v-loading="loading" :data="rows" stripe table-layout="fixed">
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
        <el-table-column label="远端地址" width="140" show-overflow-tooltip>
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
        <!--
          请求参数列：审计真正要看的东西。
          「谁调了哪个 URL」只能说明动过，而「把哪一项改成了多少」必须靠参数，
          没有这一列，前面的记录再多也回答不了追责问题。
          敏感字段（密码/密钥/token）后端已脱敏成 ***(N字符)，这里原样展示即可。
        -->
        <el-table-column label="参数" min-width="260" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="mono params-cell">{{ row.params || '—' }}</span>
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
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { confirmAction } from '@/utils/confirm'
import { opLogApi } from '@/api'
import { useAuthStore } from '@/stores/auth'
import TablePager from '@/components/TablePager.vue'
import { usePagination } from '@/composables/usePagination'
import { useFillTableRows } from '@/composables/useFillTableRows'

const auth = useAuthStore()

/** 清空审计日志是独立的敏感权限，不能等同于「能看日志」。 */
const canClear = computed(() => auth.can('oplog:clear'))

const rows = ref([])

/** 行高随表体可用高度自适应，数据少时铺满、数据多时内部滚动。 */
const tableRef = ref(null)
useFillTableRows(tableRef, () => rows.value.length)
const loading = ref(false)
const query = reactive({ method: '', keyword: '', dateRange: null })

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
  if (!(await confirmAction('确定清空全部操作日志？该操作不可恢复。', '清空确认',
    { confirmButtonText: '清空' }))) return
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

/*
 * 参数列：用等宽字并允许折行。
 * 参数是 key=value&key=value 形式，等宽字下「哪个参数对应哪个值」最容易扫读；
 * 非等宽会让人把连在一起的字符误读成同一个词。
 */
.params-cell {
  display: block;
  max-width: 100%;
  font-size: var(--fs-xs);
  color: var(--ink-700);
  word-break: break-all;
}
</style>
