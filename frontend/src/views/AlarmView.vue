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
        <el-table-column prop="alarmTime" label="时间" width="148" sortable>
          <template #default="{ row }"><span class="mono">{{ row.time || '—' }}</span></template>
        </el-table-column>
        <!-- 级别只显示「一级」两字：完整名称是「一级警情」，4 个汉字在 80px 列里会被压成省略号。
             完整名称放原生 title，不用 el-tooltip —— 后者会把 trigger 元素带上
             transform: scaleX(0) / opacity: 0，标签在表格里直接看不见。 -->
        <el-table-column prop="alarmPriority" label="级别" width="80" align="center" sortable>
          <template #default="{ row }">
            <el-tag :type="priorityColor(row.alarmPriority)" size="small" effect="light"
                    :title="row.alarmPriorityName || priorityText(row.alarmPriority)">
              {{ priorityText(row.alarmPriority) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="设备" width="130">
          <template #default="{ row }"><span class="mono">{{ row.deviceId || '—' }}</span></template>
        </el-table-column>
        <el-table-column label="通道" width="120">
          <template #default="{ row }"><span class="mono">{{ row.channelId || '—' }}</span></template>
        </el-table-column>
        <!-- 报警类型：国标走 AlarmType 映射，SDK 直连设备走事件码映射（认不出的码原样显示 0xXXXX） -->
        <el-table-column label="报警类型" width="116" show-overflow-tooltip>
          <template #default="{ row }">
            <span>{{ row.alarmTypeName || eventCodeText(row.alarmEventType) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="报警方式" width="92">
          <template #default="{ row }">{{ row.alarmMethodName || '—' }}</template>
        </el-table-column>
        <el-table-column label="描述" min-width="150" show-overflow-tooltip>
          <template #default="{ row }">{{ row.alarmDescription || '—' }}</template>
        </el-table-column>
        <el-table-column label="操作" width="56" align="center">
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

/**
 * 未知事件码的兜底显示。
 *
 * <p>SDK 直连设备的事件码上千个，认不出的必须原样透出成 0xXXXX ——
 * 显示成「—」会让人以为平台没收到报警。
 */
function eventCodeText(code) {
  if (code === null || code === undefined || code === '') return '—'
  const n = Number(code)
  if (!Number.isFinite(n)) return '—'
  return `事件 0x${n.toString(16).toUpperCase().padStart(4, '0')}`
}

/** 聚合搜索关键词（设备/通道编码、报警方式、描述）。 */
const keyword = ref('')

const { pager, offset, setTotal, onPageChange, onSizeChange } = usePagination({
  pageSize: 20,
  load: () => load()
})

/**
 * 加载当前页。
 *
 * <p><b>服务端分页</b>：以前是 {@code limit: 0} 一次拉全量、再在前端过滤和切片。
 * 那张表实测 5 千多行，后端又对每行补设备名（N+1，11638 次远程查询），
 * 接口要跑到 290 秒，页面直接打不开。
 *
 * <p>现在把「级别 / 关键词 / 偏移量 / 每页条数」全交给后端，
 * 前端只拿当前这一页；{@code alarmCount} 就是筛选后的总数。
 */
async function load() {
  loading.value = true
  try {
    const data = await alarmApi.list({
      start: offset.value,
      limit: pager.size,
      priority: query.priority || undefined,
      q: keyword.value.trim() || undefined
    })
    alarms.value = data?.alarmList || []
    setTotal(data?.alarmCount ?? 0)
  } catch (e) {
    ElMessage.error(e.message || '加载报警失败')
  } finally {
    loading.value = false
  }
}

/** 当前页数据（后端已经切好，直接用）。 */
const pagedAlarms = computed(() => alarms.value)

/** 条件变化时回到第一页再查。 */
function reloadFromFirstPage() {
  pager.page = 1
  load()
}

// 关键词防抖：边打字边请求会把后端打满，也和「搜索」的直觉不符
let keywordTimer = null
watch(keyword, () => {
  if (keywordTimer) clearTimeout(keywordTimer)
  keywordTimer = setTimeout(reloadFromFirstPage, 300)
})
watch(() => query.priority, reloadFromFirstPage)

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
    pager.page = 1
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
