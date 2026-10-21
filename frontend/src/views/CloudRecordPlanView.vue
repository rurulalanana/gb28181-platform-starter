<template>
  <div class="page is-fill">
    <el-card shadow="never">
      <div class="filter-bar">
        <el-button size="small" @click="goBack">
          <el-icon><ArrowLeft /></el-icon> 返回
        </el-button>
        <el-button size="small" type="primary" @click="openEdit(null)">
          <el-icon><Plus /></el-icon> 添加录像计划
        </el-button>
        <span class="filter-bar-spacer" />
        <el-input
          v-model="query.q"
          placeholder="关键字"
          clearable
          style="width: 220px"
          @keyup.enter="search"
          @clear="search"
        >
          <template #prefix><el-icon><Search /></el-icon></template>
        </el-input>
        <el-button type="primary" @click="search">
          <el-icon><Search /></el-icon> 搜索
        </el-button>
        <el-button size="small" :loading="loading" @click="load">
          <el-icon><Refresh /></el-icon> 刷新
        </el-button>
      </div>

      <el-table ref="tableRef" v-loading="loading" :data="rows" stripe>
        <el-table-column prop="Name" label="名称" min-width="160" sortable>
          <template #default="{ row }">{{ row.Name }}</template>
        </el-table-column>
        <el-table-column label="是否启用" width="100" align="center">
          <template #default="{ row }">
            <el-switch
              :model-value="row.Enable"
              size="small"
              @change="(v) => toggleEnable(row, v)"
            />
          </template>
        </el-table-column>
        <el-table-column label="录像计划详情" min-width="380">
          <template #default="{ row }">
            <span class="plan-detail">{{ describePlan(row.Plan) }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="CreatedAt" label="创建时间" width="180" sortable>
          <template #default="{ row }"><span class="mono">{{ row.CreatedAt }}</span></template>
        </el-table-column>
        <el-table-column prop="UpdatedAt" label="更新时间" width="180" sortable>
          <template #default="{ row }"><span class="mono">{{ row.UpdatedAt }}</span></template>
        </el-table-column>
        <el-table-column label="操作" width="230" align="center" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openEdit(row)">
              <el-icon><Edit /></el-icon> 编辑
            </el-button>
            <el-button link type="primary" @click="openChannels(row)">
              <el-icon><Link /></el-icon> 关联通道
            </el-button>
            <el-button link type="danger" @click="removePlan(row)">
              <el-icon><Delete /></el-icon> 删除
            </el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty :image-size="76" description="暂无录像计划" />
        </template>
      </el-table>

      <TablePager
        :pager="pager"
        unit="条计划"
        @page-change="onPageChange"
        @size-change="onSizeChange"
      />
    </el-card>

    <!-- 编辑录像计划 -->
    <el-dialog
      v-model="editDialog"
      :title="editTarget ? '编辑录像计划' : '添加录像计划'"
      width="900px"
      top="6vh"
    >
      <el-form :model="form" label-width="110px">
        <el-form-item label="名称" required>
          <el-input v-model="form.name" placeholder="计划名称" style="max-width: 420px" />
        </el-form-item>
        <el-form-item label="状态">
          <el-checkbox v-model="form.enable">启用</el-checkbox>
        </el-form-item>
        <el-form-item label="录像计划详情">
          <div class="plan-editor">
            <div class="plan-editor-head">
              <span class="text-muted">配置云端录像开启时段</span>
              <div class="plan-editor-actions">
                <el-button link type="primary" size="small" @click="resetPlan">重置</el-button>
                <el-button link type="primary" size="small" @click="selectAll">全选</el-button>
                <el-button link type="primary" size="small" @click="clearAll">清空</el-button>
              </div>
            </div>
            <!--
              每周七天的时段网格：横轴 0~24 小时，按小时分格。
              选中 = 该小时开启云端录像。数据结构与 LiveGBS 一致：
              plan = [[{s1,e1}], [], [{s3,e3}], ...]（外层 7 个数组 = 周一~周日）。
            -->
            <div v-for="day in DAYS" :key="day.key" class="plan-day">
              <span class="plan-day-label">{{ day.label }}</span>
              <div class="plan-day-track">
                <div class="plan-hours">
                  <span
                    v-for="h in HOURS"
                    :key="h"
                    class="plan-hour"
                    :class="{ 'is-on': isOn(day.key, h) }"
                    :title="`${day.label} ${h}:00-${h + 1}:00`"
                    @click="toggleHour(day.key, h)"
                  />
                </div>
                <div class="plan-ticks">
                  <span
                    v-for="h in TICKS"
                    :key="h"
                    class="plan-tick"
                    :style="{ left: ((h / 24) * 100) + '%' }"
                  >{{ h }}</span>
                </div>
              </div>
            </div>
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="doSave">确定</el-button>
      </template>
    </el-dialog>

    <!-- 关联通道 -->
    <el-dialog v-model="chanDialog" :title="'录像计划关联通道(' + (chanTarget?.Name || '') + ')'" width="900px">
      <div class="filter-bar">
        <el-input
          v-model="chanQuery"
          placeholder="关键字"
          clearable
          style="width: 240px"
          @keyup.enter="loadChannels"
        >
          <template #prefix><el-icon><Search /></el-icon></template>
        </el-input>
        <el-button type="primary" @click="loadChannels">
          <el-icon><Search /></el-icon> 搜索
        </el-button>
        <span class="filter-bar-spacer" />
        <el-checkbox v-model="onlyRelated">只看已选({{ chanRows.filter(isPicked).length }})</el-checkbox>
      </div>
      <el-table v-loading="chanLoading" :data="chanRows" stripe height="380">
        <el-table-column label="选择" width="60" align="center">
          <template #default="{ row }">
            <el-checkbox :model-value="isPicked(row)" @change="(v) => togglePick(row, v)" />
          </template>
        </el-table-column>
        <el-table-column prop="deviceId" label="设备国标编号" min-width="190">
          <template #default="{ row }"><span class="mono">{{ row.deviceId }}</span></template>
        </el-table-column>
        <el-table-column prop="channelId" label="通道国标编号" min-width="190">
          <template #default="{ row }"><span class="mono">{{ row.channelId }}</span></template>
        </el-table-column>
        <el-table-column prop="name" label="通道名称" min-width="200" show-overflow-tooltip />
        <el-table-column label="在线状态" width="100" align="center">
          <template #default="{ row }">
            <span class="badge" :class="row.online ? 'is-online' : 'is-offline'">
              {{ row.online ? '在线' : '离线' }}
            </span>
          </template>
        </el-table-column>
      </el-table>
      <template #footer>
        <el-button @click="chanDialog = false">取消</el-button>
        <el-button type="primary" :loading="chanSaving" @click="saveChannels">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { cloudRecordApi } from '@/api'
import { usePagination } from '@/composables/usePagination'
import { useFillTableRows } from '@/composables/useFillTableRows'
import TablePager from '@/components/TablePager.vue'

const route = useRoute()
const router = useRouter()

const rows = ref([])
const loading = ref(false)
const query = reactive({ q: '' })

const tableRef = ref(null)
useFillTableRows(tableRef, () => rows.value.length)

const { pager, offset, reset, setTotal, onPageChange, onSizeChange } = usePagination({
  pageSize: 10,
  load: () => load()
})

/* ==================== 星期与时段 ==================== */

const DAYS = [
  { key: 0, label: '星期一' },
  { key: 1, label: '星期二' },
  { key: 2, label: '星期三' },
  { key: 3, label: '星期四' },
  { key: 4, label: '星期五' },
  { key: 5, label: '星期六' },
  { key: 6, label: '星期日' }
]

/** 0~23 共 24 格。 */
const HOURS = Array.from({ length: 24 }, (_, i) => i)

/** 刻度：每 2 小时一个。 */
const TICKS = Array.from({ length: 13 }, (_, i) => i * 2)

/**
 * 计划时段（按天 → 小时集合）。
 *
 * <p>内部用「天索引 → Set<小时>」表示，存库时转成 LiveGBS 的 JSON 结构
 * {@code [[{s1,e1}], ...]}：外层 7 个数组对应周一~周日，每项是该天的时段列表。
 */
const planHours = reactive([])

function resetPlanState() {
  planHours.length = 0
  for (let i = 0; i < 7; i++) planHours.push(new Set())
}

function isOn(dayKey, hour) {
  const s = planHours[dayKey]
  return !!s && s.has(hour)
}

function toggleHour(dayKey, hour) {
  if (!planHours[dayKey]) planHours[dayKey] = new Set()
  const s = planHours[dayKey]
  if (s.has(hour)) s.delete(hour)
  else s.add(hour)
}

function selectAll() {
  for (let i = 0; i < 7; i++) {
    if (!planHours[i]) planHours[i] = new Set()
    for (let h = 0; h < 24; h++) planHours[i].add(h)
  }
}

function clearAll() {
  for (let i = 0; i < 7; i++) planHours[i] = new Set()
}

/** 内部结构 → LiveGBS 的 JSON 字符串（连续小时合并成一段）。 */
function toPlanJson() {
  const days = []
  for (let i = 0; i < 7; i++) {
    const hours = [...(planHours[i] || [])].sort((a, b) => a - b)
    const segs = []
    let start = null
    let prev = null
    for (const h of hours) {
      if (start === null) {
        start = h
      } else if (h !== prev + 1) {
        segs.push({ ['s' + (i + 1)]: fmtHour(start), ['e' + (i + 1)]: fmtHour(prev + 1) })
        start = h
      }
      prev = h
    }
    if (start !== null) {
      segs.push({ ['s' + (i + 1)]: fmtHour(start), ['e' + (i + 1)]: fmtHour(prev + 1) })
    }
    days.push(segs)
  }
  return JSON.stringify(days)
}

/** 小时数 → "HH:MM"（24 点写作 24:00）。 */
function fmtHour(h) {
  const hh = String(h).padStart(2, '0')
  return hh + ':00'
}

/** LiveGBS 的 JSON 字符串 → 内部结构。 */
function fromPlanJson(json) {
  resetPlanState()
  let days = []
  try {
    days = JSON.parse(json || '[]')
  } catch (e) {
    return
  }
  for (let i = 0; i < 7; i++) {
    const segs = Array.isArray(days[i]) ? days[i] : []
    for (const seg of segs) {
      const sKey = 's' + (i + 1)
      const eKey = 'e' + (i + 1)
      const s = parseHour(seg[sKey])
      const e = parseHour(seg[eKey])
      if (s === null || e === null) continue
      for (let h = s; h < e; h++) {
        if (!planHours[i]) planHours[i] = new Set()
        planHours[i].add(h % 24)
      }
    }
  }
}

/** "HH:MM" → 小时数（"24:00" 记作 24）。 */
function parseHour(v) {
  const m = /^(\d{1,2}):(\d{2})$/.exec(String(v || '').trim())
  if (!m) return null
  return Number(m[1])
}

/** 列表里的「录像计划详情」文案：星期一[✓] 星期二[✗] … */
function describePlan(json) {
  const parts = []
  const days = DAYS.map((d) => d.label.replace('星期', '星期'))
  let parsed = []
  try {
    parsed = JSON.parse(json || '[]')
  } catch (e) {
    return '—'
  }
  for (let i = 0; i < 7; i++) {
    const segs = Array.isArray(parsed[i]) ? parsed[i] : []
    const ok = segs.length > 0
    let label = days[i]
    if (ok && segs.length === 1) {
      const seg = segs[0]
      const s = seg['s' + (i + 1)]
      const e = seg['e' + (i + 1)]
      label += ` ${s}-${e}`
    } else if (ok) {
      label += ` ${segs.length}段`
    }
    parts.push(label + '[✓]')
    if (!ok) parts[parts.length - 1] = days[i] + '[✗]'
  }
  return parts.join(' ')
}

/* ==================== 数据加载 ==================== */

async function load() {
  loading.value = true
  try {
    const params = { start: offset.value, limit: pager.size }
    if (query.q) params.q = query.q
    const data = await cloudRecordApi.planList(params)
    rows.value = data?.rows || []
    setTotal(data?.total ?? rows.value.length)
  } catch (e) {
    ElMessage.error(e.message || '加载录像计划失败')
  } finally {
    loading.value = false
  }
}

function search() {
  reset()
  load()
}

function goBack() {
  router.push('/cloud')
}

/* ==================== 编辑 ==================== */

const editDialog = ref(false)
const editTarget = ref(null)
const saving = ref(false)
const form = reactive({ name: '', enable: true })

function openEdit(row) {
  editTarget.value = row
  form.name = row?.Name || ''
  form.enable = row ? !!row.Enable : true
  resetPlanState()
  if (row?.Plan) fromPlanJson(row.Plan)
  editDialog.value = true
}

async function doSave() {
  if (!form.name.trim()) {
    ElMessage.warning('请填写计划名称')
    return
  }
  saving.value = true
  try {
    await cloudRecordApi.planSave({
      id: editTarget.value?.ID,
      name: form.name.trim(),
      plan: toPlanJson(),
      enable: form.enable
    })
    ElMessage.success('已保存')
    editDialog.value = false
    load()
  } catch (e) {
    ElMessage.error(e.message || '保存失败')
  } finally {
    saving.value = false
  }
}

async function toggleEnable(row, enable) {
  try {
    await cloudRecordApi.planEnable({ id: row.ID, enable })
    row.Enable = enable
    ElMessage.success(enable ? '已启用' : '已停用')
  } catch (e) {
    ElMessage.error(e.message || '操作失败')
  }
}

async function removePlan(row) {
  try {
    await ElMessageBox.confirm(`确定删除录像计划「${row.Name}」？`, '删除确认', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消'
    })
  } catch (e) {
    return
  }
  try {
    await cloudRecordApi.planRemove({ id: row.ID })
    ElMessage.success('已删除')
    load()
  } catch (e) {
    ElMessage.error(e.message || '删除失败')
  }
}

/* ==================== 关联通道 ==================== */

const chanDialog = ref(false)
const chanTarget = ref(null)
const chanRows = ref([])
const chanLoading = ref(false)
const chanSaving = ref(false)
const chanQuery = ref('')
const onlyRelated = ref(false)
/** 已选中的通道键集合（`<设备编码>/<通道编码>`）。 */
const picked = reactive(new Set())
/** 该计划原本已关联的通道键集合（用于「只看已选」）。 */
const related = reactive(new Set())

function openChannels(row) {
  chanTarget.value = row
  chanQuery.value = ''
  onlyRelated.value = false
  picked.clear()
  related.clear()
  chanDialog.value = true
  loadChannels()
  loadRelated(row.ID)
}

async function loadRelated(planId) {
  try {
    const list = await cloudRecordApi.planChannelList({ id: planId, related: true, limit: 1000 })
    for (const c of list || []) related.add(`${c.deviceId}/${c.channelId}`)
    for (const k of related) picked.add(k)
  } catch (e) {
    // 忽略：已关联列表取不到时按「未选」处理
  }
}

async function loadChannels() {
  if (!chanTarget.value) return
  chanLoading.value = true
  try {
    const list = await cloudRecordApi.planChannelList({
      id: chanTarget.value.ID,
      q: chanQuery.value,
      related: onlyRelated.value,
      limit: 500
    })
    chanRows.value = list || []
  } catch (e) {
    ElMessage.error(e.message || '加载通道失败')
  } finally {
    chanLoading.value = false
  }
}

function isPicked(row) {
  return picked.has(`${row.deviceId}/${row.channelId}`)
}

function togglePick(row, checked) {
  const k = `${row.deviceId}/${row.channelId}`
  if (checked) picked.add(k)
  else picked.delete(k)
}

async function saveChannels() {
  if (!chanTarget.value) return
  chanSaving.value = true
  try {
    await cloudRecordApi.planSetChannels({
      id: chanTarget.value.ID,
      channels: [...picked]
    })
    ElMessage.success('已保存关联通道')
    chanDialog.value = false
    load()
  } catch (e) {
    ElMessage.error(e.message || '保存失败')
  } finally {
    chanSaving.value = false
  }
}

onMounted(load)
</script>

<style scoped>
/*
 * 表格与工具条沿用全局样式（.filter-bar / .el-table），
 * 与「设备管理」「通道管理」保持一致，这里只补计划详情文案。
 */
.plan-detail {
  font-size: 12px;
  color: var(--text-secondary);
}

/* ==================== 计划时段编辑器 ==================== */

.plan-editor {
  width: 100%;
  background: var(--bg-sunken);
  border-radius: 6px;
  padding: 12px 16px 16px;
}

.plan-editor-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 10px;
  font-size: 12px;
}

.plan-editor-actions {
  display: flex;
  gap: 10px;
}

.plan-day {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 6px;
}

.plan-day-label {
  width: 52px;
  flex-shrink: 0;
  font-size: 12px;
  color: var(--text-secondary);
  text-align: right;
}

.plan-day-track {
  position: relative;
  flex: 1;
  min-width: 0;
}

/* 24 个小时格 */
.plan-hours {
  display: flex;
  height: 26px;
  border: 1px solid var(--line);
  border-radius: 4px;
  overflow: hidden;
  background: var(--bg-surface);
}

.plan-hour {
  flex: 1;
  min-width: 0;
  cursor: pointer;
  border-right: 1px solid var(--line-soft);
  transition: background 0.12s;
}

.plan-hour:last-child {
  border-right: none;
}

.plan-hour:hover {
  background: var(--brand-50);
}

.plan-hour.is-on {
  background: var(--brand-400);
}

/* 下方刻度：每 2 小时一个 */
.plan-ticks {
  position: relative;
  height: 16px;
  margin-top: 2px;
}

.plan-tick {
  position: absolute;
  top: 0;
  transform: translateX(-50%);
  font-size: 11px;
  color: var(--text-tertiary);
  font-family: var(--font-mono, monospace);
}
</style>
