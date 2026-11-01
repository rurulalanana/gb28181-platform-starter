<template>
  <div class="page is-fill">

    <el-card shadow="never">
    <!-- 工具条：筛选条件在左，操作按钮在右，一行放完 -->
    <div class="filter-bar">
      <el-select v-model="query.priority" placeholder="全部级别" clearable style="width: 160px">
        <el-option label="一级（紧急）" value="1" />
        <el-option label="二级（重要）" value="2" />
        <el-option label="三级（一般）" value="3" />
        <el-option label="四级（提示）" value="4" />
      </el-select>
      <KeywordFilter v-model="keyword" placeholder="搜索设备/通道编码 / 方式 / 描述" width="300px" />
      <span class="filter-bar-spacer" />
      <el-button size="small" :loading="loading" @click="load">
        <el-icon><Refresh /></el-icon> 刷新
      </el-button>
      <el-button v-if="canDelete" size="small" type="danger" plain @click="clearAll">
        <el-icon><Delete /></el-icon> 清空报警
      </el-button>
    </div>

      <el-table ref="tableRef" v-loading="loading" :data="pagedAlarms" stripe table-layout="fixed">
        <el-table-column prop="alarmTime" label="时间" width="160" sortable>
          <template #default="{ row }"><span class="mono">{{ row.time || '—' }}</span></template>
        </el-table-column>
        <el-table-column prop="alarmPriority" label="级别" width="84" align="center" sortable>
          <template #default="{ row }">
            <el-tag :type="priorityColor(row.alarmPriority)" size="small" effect="light">
              {{ row.alarmPriorityName || priorityText(row.alarmPriority) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="设备" width="160">
          <template #default="{ row }"><span class="mono">{{ row.deviceId || '—' }}</span></template>
        </el-table-column>
        <el-table-column label="通道" width="160">
          <template #default="{ row }"><span class="mono">{{ row.channelId || '—' }}</span></template>
        </el-table-column>
        <el-table-column label="报警方式" width="104">
          <template #default="{ row }">{{ row.alarmMethodName || '—' }}</template>
        </el-table-column>
        <el-table-column label="描述" min-width="150" show-overflow-tooltip>
          <template #default="{ row }">{{ row.alarmDescription || '—' }}</template>
        </el-table-column>
        <el-table-column label="操作" width="72" align="center">
          <template #default="{ row }">
            <el-button v-if="canWrite" link type="danger" aria-label="删除报警" @click="removeOne(row)">
              <el-icon aria-hidden="true"><Delete /></el-icon>
            </el-button>
            <span v-else class="text-muted">—</span>
          </template>
        </el-table-column>
        <template #empty>
          <div class="empty-cell">暂无报警记录</div>
        </template>
      </el-table>

      <TablePager
        :pager="pager"
        unit="条报警"
        @page-change="onPageChange"
        @size-change="onSizeChange"
      />
    </el-card>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { confirmAction } from '@/utils/confirm'
import { alarmApi } from '@/api'
import { useAuthStore } from '@/stores/auth'
import { usePagination } from '@/composables/usePagination'
import KeywordFilter from '@/components/KeywordFilter.vue'
import { useFillTableRows } from '@/composables/useFillTableRows'
import TablePager from '@/components/TablePager.vue'

const auth = useAuthStore()

/** 处理单条报警。 */
const canWrite = computed(() => auth.can('alarm:write'))
/** 清空全部报警：与单条删除分开，因为它不可恢复。 */
const canDelete = computed(() => auth.can('alarm:delete'))

const alarms = ref([])

/** 行高随表体可用高度自适应，数据少时铺满、数据多时内部滚动。 */
const tableRef = ref(null)
useFillTableRows(tableRef, () => alarms.value.length)
const loading = ref(false)
const query = reactive({ priority: null, serial: '' })

const PRIORITY_TEXT = { 1: '一级', 2: '二级', 3: '三级', 4: '四级' }
const PRIORITY_COLOR = { 1: 'danger', 2: 'warning', 3: 'primary', 4: 'info' }

function priorityText(p) {
  return PRIORITY_TEXT[p] || '—'
}

function priorityColor(p) {
  return PRIORITY_COLOR[p] || 'info'
}

/** 聚合搜索关键词（设备/通道编码、报警方式、描述）。 */
const keyword = ref('')

const { pager, offset, onPageChange, onSizeChange } = usePagination({
  pageSize: 10,
  // 翻页只切本地切片
  load: () => {}
})

async function load() {
  loading.value = true
  try {
    // limit=0：一次取全量，搜索/筛选才是全库聚合
    const data = await alarmApi.list({ start: 0, limit: 0 })
    alarms.value = data?.alarmList || []
    pager.page = 1
  } catch (e) {
    ElMessage.error(e.message || '加载报警失败')
  } finally {
    loading.value = false
  }
}

/** 级别 + 关键词过滤（本地）。 */
const filteredAlarms = computed(() => {
  const q = keyword.value.trim().toLowerCase()
  return alarms.value.filter((a) => {
    if (query.priority && String(a.alarmPriority) !== String(query.priority)) return false
    if (!q) return true
    return [a.deviceId, a.channelId, a.alarmMethodName, a.alarmDescription, a.alarmPriority]
      .filter(Boolean)
      .some((v) => String(v).toLowerCase().includes(q))
  })
})

const pagedAlarms = computed(() =>
  filteredAlarms.value.slice(offset.value, offset.value + pager.size)
)

watch([filteredAlarms, () => pager.size], () => {
  pager.total = filteredAlarms.value.length
  if (offset.value >= filteredAlarms.value.length) pager.page = 1
})

async function removeOne(row) {
  try {
    await alarmApi.remove(row.id)
    ElMessage.success('已删除')
    load()
  } catch (e) {
    ElMessage.error(e.message || '删除失败')
  }
}

async function clearAll() {
  if (!(await confirmAction('确定清空全部报警记录？', '清空确认',
    { confirmButtonText: '清空' }))) return
  try {
    const data = await alarmApi.clear()
    ElMessage.success(`已清空 ${data?.deleted ?? 0} 条`)
    load()
  } catch (e) {
    ElMessage.error(e.message || '清空失败')
  }
}

onMounted(load)
</script>

<style scoped>
/* .card-header / .search-form / .empty-cell 由 styles/main.css 统一提供 */
</style>
