<template>
  <div class="page is-fill">
    <el-card shadow="never">
      <div class="filter-bar">
        <el-button size="small" @click="goBack">
          <el-icon><ArrowLeft /></el-icon> 返回
        </el-button>
        <el-button size="small" type="primary" :disabled="!canWrite" @click="openEdit(null)">
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
        <el-table-column prop="name" label="名称" min-width="160" sortable>
          <template #default="{ row }">{{ row.name }}</template>
        </el-table-column>
        <el-table-column label="是否启用" width="100" align="center">
          <template #default="{ row }">
            <el-switch
              :model-value="row.enable"
              size="small"
              @change="(v) => toggleEnable(row, v)"
            />
          </template>
        </el-table-column>
        <el-table-column label="录像计划详情" min-width="380">
          <template #default="{ row }">
            <span class="plan-detail">{{ describePlan(row.plan) }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="createdAt" label="创建时间" width="180" sortable>
          <template #default="{ row }"><span class="mono">{{ row.createdAt }}</span></template>
        </el-table-column>
        <el-table-column prop="updatedAt" label="更新时间" width="180" sortable>
          <template #default="{ row }"><span class="mono">{{ row.updatedAt }}</span></template>
        </el-table-column>
        <el-table-column label="操作" width="230" align="center" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" :disabled="!canWrite" @click="openEdit(row)">
              <el-icon><Edit /></el-icon> 编辑
            </el-button>
            <el-button link type="primary" @click="openChannels(row)">
              <el-icon><Link /></el-icon> 关联通道
            </el-button>
            <el-button link type="danger" :disabled="!canWrite" @click="removePlan(row)">
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
          <div class="plan-editor" @mouseleave="endGridDrag">
            <div class="plan-editor-head">
              <span class="text-muted">
                配置云端录像开启时段（可按住鼠标拖动批量选择）
              </span>
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

              <p><b>支持拖动批量选择</b>：7 天 × 24 小时 = 168 格，
              一格格点太慢。按住鼠标从起点拖到终点，按「矩形区域」批量设置
              （与 Excel 选单元格一致：起点状态取反，拖过的格子统一成该值）。
              起点未选 → 拖过的全选；起点已选 → 拖过的全取消。
            -->
            <div
              v-for="day in DAYS"
              :key="day.key"
              class="plan-day"
              :class="{ 'is-today': day.key === todayIndex }"
            >
              <span class="plan-day-label">
                {{ day.label }}
                <el-tag v-if="day.key === todayIndex" size="small" type="success" effect="plain">
                  今天
                </el-tag>
              </span>
              <div class="plan-day-track">
                <div class="plan-hours">
                  <span
                    v-for="h in HOURS"
                    :key="h"
                    class="plan-hour"
                    :class="{ 'is-on': isOn(day.key, h), 'is-now': day.key === todayIndex && h === nowHour }"
                    :title="`${day.label} ${h}:00-${h + 1}:00`"
                    @mousedown.prevent="onHourMouseDown(day.key, h)"
                    @mouseenter="onHourEnter(day.key, h)"
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
    <el-dialog v-model="chanDialog" :title="'录像计划关联通道(' + (chanTarget?.name || '') + ')'" width="900px">
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
        <el-button :disabled="!chanRows.length" @click="pickAllVisible">全选本页</el-button>
        <el-button :disabled="!picked.size" @click="clearPicked">清空选择</el-button>
        <span class="filter-bar-spacer" />
        <el-checkbox v-model="onlyRelated">只看已选({{ picked.size }})</el-checkbox>
      </div>
      <!--
        批量勾选：整行可点，且支持「按下鼠标拖动」连续勾选。

        <p>原实现只有一个小复选框，几十个通道要点几十次 —— 这是用户明确
        抱怨的痛点。现在三种方式并存：
        <ol>
          <li>点复选框（精确增减）；</li>
          <li>点行其它位置（切换该行）；</li>
          <li>按住鼠标从第一行拖到最后一行（整段按「首行状态取反」批量设置，
              Excel 风格：首行原本未选则全选，原本已选则全不选）。</li>
        </ol>

        <p><b>为什么 mousedown 挂在外层 div 而不是 el-table 事件</b>：
        Element Plus 的 Table 只有 cell-mouse-enter / cell-click 等，
        <b>没有 row-mousedown</b>。用外层原生监听 + closest('tr') 定位行索引，
        比给每一行绑定更好维护（不用改渲染结构）。
      -->
      <div @mousedown="onChanTableMouseDown" @mouseleave="endChanDrag">
        <el-table
          ref="chanTableRef"
          v-loading="chanLoading"
          :data="chanRows"
          stripe
          height="380"
          row-key="channelId"
          :row-class-name="chanRowClass"
          @cell-mouse-enter="onChanCellEnter"
        >
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
      </div>
      <div class="pick-hint">
        提示：可按住鼠标拖动连续勾选；点「全选本页」选中当前列表全部通道。
        <br />
        <b>录制规则</b>：通道在「通道管理」里<b>手动开启</b>云端录像 → <b>全天录制</b>，
        完全忽略本计划；<b>手动关闭</b> → 按本计划时段录制。
        计划<b>不会改动</b>通道的「云端录像 / 按需直播」开关，那两个开关始终由你决定。
        多个计划包含同一通道时取<b>并集</b>，任一时段命中即录制。
      </div>
      <template #footer>
        <el-button @click="chanDialog = false">取消</el-button>
        <el-button type="primary" :loading="chanSaving" :disabled="!canWrite" @click="saveChannels">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { confirmAction } from '@/utils/confirm'
import { cloudRecordApi } from '@/api'
import { useAuthStore } from '@/stores/auth'
import { usePagination } from '@/composables/usePagination'
import { useFillTableRows } from '@/composables/useFillTableRows'
import TablePager from '@/components/TablePager.vue'

const router = useRouter()
const auth = useAuthStore()

/** 录像计划属于「管理云端录像」，cloud:write。 */
const canWrite = computed(() => auth.can('cloud:write'))

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

/**
 * 今天在 {@link DAYS} 里的下标（周一 = 0），以及当前小时。
 *
 * <p><b>为什么界面上必须标出来</b>：7 行格子长得一模一样，用户很容易
 * 在「星期五」那行画了却发现没录 —— 其实他画的是「星期六」。实测踩过：
 * 今天是周五、计划只配了周六 08:00-22:00，用户以为坏了，
 * 实际是配错了行，而界面完全看不出哪行是今天。
 *
 * <p>用 `getDay()`：0 = 周日，所以 `(getDay() + 6) % 7` 换成
 * 「周一 = 0」的索引，与 {@link DAYS} 对齐。
 */
const nowInfo = ref(currentNow())
const todayIndex = computed(() => nowInfo.value.dayIndex)
const nowHour = computed(() => nowInfo.value.hour)

function currentNow() {
  const d = new Date()
  return { dayIndex: (d.getDay() + 6) % 7, hour: d.getHours() }
}

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

/* ==================== 时段格子：拖动批量选择 ==================== */

/**
 * 拖动状态。
 *
 * <p>{@code anchor} 是按下鼠标的那一格（天, 小时）；{@code value} 是这一段
 * 要统一设成的值 —— 起点状态取反，与 Excel 选区一致：
 * 从空白格开始拖 = 全部选中，从已选格开始拖 = 全部取消。
 *
 * <p>选区按<b>矩形</b>算（起点的天到当前的天 × 起点的小时到当前的小时），
 * 这样「周三 8 点 → 周五 18 点」一次就能刷出 3 天 × 11 小时的时段。
 */
const gridDrag = reactive({
  active: false,
  anchorDay: -1,
  anchorHour: -1,
  value: true
})

function onHourMouseDown(dayKey, hour) {
  // 起点状态取反：未选 → 这一段全选；已选 → 这一段全取消
  gridDrag.value = !isOn(dayKey, hour)
  gridDrag.anchorDay = dayKey
  gridDrag.anchorHour = hour
  gridDrag.active = true
  setHour(dayKey, hour, gridDrag.value)
}

function onHourEnter(dayKey, hour) {
  if (!gridDrag.active) return
  applyGridRect(dayKey, hour)
}

/** 把「锚点 → 当前格」的矩形区域统一设为 {@link gridDrag}.value。 */
function applyGridRect(dayKey, hour) {
  const d0 = Math.min(gridDrag.anchorDay, dayKey)
  const d1 = Math.max(gridDrag.anchorDay, dayKey)
  const h0 = Math.min(gridDrag.anchorHour, hour)
  const h1 = Math.max(gridDrag.anchorHour, hour)
  for (let d = d0; d <= d1; d++) {
    for (let h = h0; h <= h1; h++) {
      setHour(d, h, gridDrag.value)
    }
  }
}

/** 幂等写值（避免重复 new Set / 反复触发响应式）。 */
function setHour(dayKey, hour, on) {
  if (!planHours[dayKey]) planHours[dayKey] = new Set()
  const s = planHours[dayKey]
  const had = s.has(hour)
  if (on === had) return
  if (on) s.add(hour)
  else s.delete(hour)
}

/**
 * 结束时段拖拽。
 *
 * <p><b>只能挂在最外层 {@code .plan-editor} 上，不能挂在每行的
 * {@code .plan-hours} 上</b>：行与行之间有 margin，行下方还有刻度条
 * （{@code .plan-ticks}），从周二拖到周四时鼠标必然经过这些非格子元素，
 * 触发行的 mouseleave 就会中断拖拽 —— 表现为「只能选中起点那一格」
 * （实测踩过）。
 *
 * <p>挂在最外层则「只要还在编辑器内」就一直有效，跨行、跨刻度都不中断。
 */
function endGridDrag() {
  gridDrag.active = false
  gridDrag.anchorDay = -1
  gridDrag.anchorHour = -1
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

/**
 * 把接口返回的计划行归一化为本地结构。
 *
 * <p><b>为什么需要这一步</b>：后端历史上把 VO 字段写成 {@code ID / Name / Plan}
 * （大写开头），而 Jackson 按 getter 推导属性名时会 decapitalize 成
 * {@code id / name / plan} —— 前端照字段名读 {@code row.Name} 拿到 undefined，
 * 于是列表名称空白、时段全显示 ✗、编辑弹窗回显不出内容，
 * 「关联通道」拿到 undefined 的 id 导致通道表格为空、拖拽选择看起来也失效。
 *
 * <p>后端已统一改为小写驼峰。这里仍兼容两种写法：让前端不受后端序列化细节
 * 影响，任一侧改动都不会再出现「界面空了但接口有数据」这种难查的问题。
 */
function normalizePlan(r) {
  if (!r) return null
  return {
    id: r.id ?? r.ID ?? '',
    name: r.name ?? r.Name ?? '',
    plan: r.plan ?? r.Plan ?? '[[],[],[],[],[],[],[]]',
    enable: r.enable ?? r.Enable ?? true,
    createdAt: r.createdAt ?? r.CreatedAt ?? '',
    updatedAt: r.updatedAt ?? r.UpdatedAt ?? '',
    channelCount: r.channelCount ?? r.ChannelCount ?? 0
  }
}

async function load() {
  loading.value = true
  try {
    const params = { start: offset.value, limit: pager.size }
    if (query.q) params.q = query.q
    const data = await cloudRecordApi.planList(params)
    rows.value = (data?.rows || []).map(normalizePlan).filter(Boolean)
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
  form.name = row?.name || ''
  form.enable = row ? !!row.enable : true
  resetPlanState()
  if (row?.plan) fromPlanJson(row.plan)
  // 每次打开都重新取「今天/当前小时」：页面可能开了很久，
  // 跨过零点后高亮会错位
  nowInfo.value = currentNow()
  editDialog.value = true
}

async function doSave() {
  if (!form.name.trim()) {
    ElMessage.warning('请填写计划名称')
    return
  }
  // 【保存前提醒】计划没覆盖「今天」时给一次确认。
  //
  // 实测踩过：用户在今天（周五）画了格子，其实画到了「星期六」那行，
  // 保存后一直不录，以为是 bug。格子本身没有语义上的"今天"，
  // 光靠视觉标「今天」还不够 —— 保存时再确认一次，代价很小。
  if (form.enable && !planCoversToday()) {
    const todayLabel = DAYS[todayIndex.value]?.label || '今天'
    if (!(await confirmAction(
      `该计划没有覆盖「今天（${todayLabel}）」，今天不会因本计划自动录像。\n\n` +
      '（若该通道已在通道管理里手动开启云端录像，则不受影响，仍会录制。）\n\n' +
      '如果只想在其它星期录，可以继续保存；如果搞错了行，请点「取消」回去调整。',
      '今天不会自动录像',
      { confirmButtonText: '仍然保存', cancelButtonText: '回去调整' }
    ))) return
  }
  saving.value = true
  try {
    await cloudRecordApi.planSave({
      id: editTarget.value?.id,
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

/** 当前编辑中的计划是否覆盖「今天」的任意时段。 */
function planCoversToday() {
  const s = planHours[todayIndex.value]
  return !!s && s.size > 0
}

async function toggleEnable(row, enable) {
  try {
    await cloudRecordApi.planEnable({ id: row.id, enable })
    row.enable = enable
    ElMessage.success(enable ? '已启用' : '已停用')
  } catch (e) {
    ElMessage.error(e.message || '操作失败')
  }
}

async function removePlan(row) {
  if (!(await confirmAction(`确定删除录像计划「${row.name}」？`, '删除确认',
    { confirmButtonText: '删除' }))) return
  try {
    await cloudRecordApi.planRemove({ id: row.id })
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
  loadRelated(row.id)
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
      id: chanTarget.value.id,
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

// ==================== 批量选择（点行 / 拖拽） ====================

const chanTableRef = ref(null)

/**
 * 拖拽状态。
 *
 * <p>{@code anchor} 是按下鼠标的那一行；{@code dragValue} 是这一整段要设成的值。
 * 「首行状态取反」是 Excel 的习惯：从已选行拖到未选行 = 全部取消，
 * 反之全部选中 —— 用户不必先想「我要选还是取消」。
 */
const dragging = ref(false)
/** 当前拖到第几行（用于高亮覆盖范围）。 */
const dragHover = ref(-1)
let dragAnchor = -1
let dragValue = true

/** 拖拽范围内整段高亮，让用户看清这次拖拽覆盖了哪些行。 */
function chanRowClass({ rowIndex }) {
  if (!dragging.value || dragAnchor < 0) return ''
  const lo = Math.min(dragAnchor, dragHover.value)
  const hi = Math.max(dragAnchor, dragHover.value)
  return rowIndex >= lo && rowIndex <= hi ? 'is-drag-range' : ''
}

/**
 * 按下鼠标：定位行 → 记锚点 → 立即切换该行 → 进入拖拽态。
 *
 * <p>点复选框列时直接返回，让 checkbox 自己的 change 处理（否则会切换两次，
 * 表现为「点了没反应」）。
 */
function onChanTableMouseDown(event) {
  if (event.button !== 0) return
  if (event.target?.closest?.('.el-checkbox')) return
  const tr = event.target?.closest?.('.el-table__body tbody tr')
  if (!tr) return
  const idx = Array.from(tr.parentElement.children).indexOf(tr)
  const row = chanRows.value[idx]
  if (!row) return
  dragAnchor = idx
  dragHover.value = idx
  // 首行状态取反：未选 → 本次全选；已选 → 本次全取消
  dragValue = !isPicked(row)
  dragging.value = true
  togglePick(row, dragValue)
}

/** 鼠标移入某单元格：若在拖拽中，把锚点到该行整段应用 dragValue。 */
function onChanCellEnter(row) {
  if (!dragging.value) return
  const idx = chanRows.value.indexOf(row)
  if (idx < 0) return
  dragHover.value = idx
  const lo = Math.min(dragAnchor, idx)
  const hi = Math.max(dragAnchor, idx)
  for (let i = lo; i <= hi; i++) {
    const r = chanRows.value[i]
    if (r) togglePick(r, dragValue)
  }
}

/** 结束拖拽：在行上松手、拖出表格、窗口任意位置松手都会走到。 */
function endChanDrag() {
  dragging.value = false
  dragAnchor = -1
  dragHover.value = -1
}

/** 全选当前列表（受搜索/只看已选影响，即「所见即所选」）。 */
function pickAllVisible() {
  for (const r of chanRows.value) togglePick(r, true)
}

/** 清空全部选择（含不在当前列表里的，所以直接 clear 而不是遍历）。 */
function clearPicked() {
  picked.clear()
}

async function saveChannels() {
  if (!chanTarget.value) return
  chanSaving.value = true
  try {
    await cloudRecordApi.planSetChannels({
      id: chanTarget.value.id,
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

/** 统一的松手收尾：两种拖拽（时段格子 / 关联通道表格）都要复位。 */
function onWindowMouseUp() {
  endGridDrag()
  endChanDrag()
}

onMounted(() => {
  load()
  // 拖拽可能在容器外松手（甚至拖出浏览器窗口），
  // 挂在 window 上兜底，否则 dragging / gridDrag 会一直为 true。
  window.addEventListener('mouseup', onWindowMouseUp)
})

onUnmounted(() => {
  window.removeEventListener('mouseup', onWindowMouseUp)
})
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

/* 批量选择提示：弱化色，避免抢表格的注意力 */
.pick-hint {
  margin-top: 8px;
  font-size: 12px;
  line-height: 1.6;
  color: var(--text-secondary);
}

/* 拖拽覆盖范围高亮：让用户看清这次拖拽选中了哪一段 */
:deep(.el-table__body tr.is-drag-range > td) {
  background: var(--el-color-primary-light-9) !important;
}

/* ==================== 计划时段编辑器 ==================== */

.plan-editor {
  width: 100%;
  background: var(--bg-sunken);
  /* 拖动选时段时不要选中文字/不要触发浏览器原生拖拽 */
  user-select: none;
  -webkit-user-select: none;
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

/* 今天这一行：整体加浅色底 + 左侧色条，与其它 6 行一眼区分 */
.plan-day.is-today .plan-hours {
  outline: 1px solid var(--el-color-success-light-5);
  outline-offset: 1px;
  border-radius: 3px;
}

.plan-day.is-today .plan-day-label {
  color: var(--el-color-success-dark-2);
  font-weight: 600;
}

/*
 * 当前小时：加一条竖向标记。
 * 让用户打开弹窗就知道「现在正处于哪一格」，配合「今天」标签，
 * 一眼能判断当前时刻是否在计划时段内。
 */
.plan-hour.is-now {
  box-shadow: inset 0 0 0 2px var(--el-color-warning);
  position: relative;
  z-index: 1;
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
