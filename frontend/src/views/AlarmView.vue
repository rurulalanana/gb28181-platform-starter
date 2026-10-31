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
      <el-input
        v-model="query.serial"
        placeholder="设备编码"
        clearable
        style="width: 200px"
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
      <el-button v-if="canDelete" size="small" type="danger" plain @click="clearAll">
        <el-icon><Delete /></el-icon> 清空报警
      </el-button>
    </div>

      <el-table ref="tableRef" v-loading="loading" :data="alarms" stripe table-layout="fixed">
        <el-table-column prop="alarmTime" label="时间" width="172" sortable>
          <template #default="{ row }"><span class="mono">{{ row.time || '—' }}</span></template>
        </el-table-column>
        <el-table-column prop="alarmPriority" label="级别" width="100" align="center" sortable>
          <template #default="{ row }">
            <el-tag :type="priorityColor(row.alarmPriority)" size="small" effect="light">
              {{ row.alarmPriorityName || priorityText(row.alarmPriority) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="设备" width="196">
          <template #default="{ row }"><span class="mono">{{ row.deviceId || '—' }}</span></template>
        </el-table-column>
        <el-table-column label="通道" width="196">
          <template #default="{ row }"><span class="mono">{{ row.channelId || '—' }}</span></template>
        </el-table-column>
        <el-table-column label="报警方式" width="116">
          <template #default="{ row }">{{ row.alarmMethodName || '—' }}</template>
        </el-table-column>
        <el-table-column label="描述" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">{{ row.alarmDescription || '—' }}</template>
        </el-table-column>
        <el-table-column label="操作" width="80" align="center" fixed="right">
          <template #default="{ row }">
            <el-button v-if="canWrite" link type="danger" @click="removeOne(row)">
              <el-icon><Delete /></el-icon>
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
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { confirmAction } from '@/utils/confirm'
import { alarmApi } from '@/api'
import { useAuthStore } from '@/stores/auth'
import { usePagination } from '@/composables/usePagination'
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

const { pager, offset, reset, setTotal, onPageChange, onSizeChange } = usePagination({
  pageSize: 10,
  load: () => load()
})

async function load() {
  loading.value = true
  try {
    const params = { start: offset.value, limit: pager.size }
    if (query.priority) params.priority = query.priority
    if (query.serial) params.serial = query.serial
    const data = await alarmApi.list(params)
    alarms.value = data?.alarmList || []
    setTotal(data?.alarmCount ?? alarms.value.length)
  } catch (e) {
    ElMessage.error(e.message || '加载报警失败')
  } finally {
    loading.value = false
  }
}

/** 条件变化后回到第一页再查，否则会停在越界页上看到空列表。 */
function search() {
  reset()
  load()
}

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
