<template>
  <div class="page">

    <!-- ============ 筛选条件 ============ -->
    <section class="filter-panel">
      <button
        class="panel-head"
        type="button"
        :aria-expanded="filterOpen"
        @click="filterOpen = !filterOpen"
      >
        <span class="panel-title">筛选条件</span>
        <el-icon class="panel-toggle" aria-hidden="true">
          <ArrowUp v-if="filterOpen" />
          <ArrowDown v-else />
        </el-icon>
      </button>

      <div v-show="filterOpen" class="panel-body">
        <el-form inline class="search-form" @submit.prevent="applyFilter">
          <el-form-item label="菜单名称">
            <el-input
              v-model="draft.title"
              placeholder="请输入菜单名称"
              clearable
              style="width: 220px"
              @keyup.enter="applyFilter"
            />
          </el-form-item>
          <el-form-item label="状态">
            <el-select v-model="draft.status" placeholder="菜单状态" clearable style="width: 160px">
              <el-option label="正常" value="0" />
              <el-option label="停用" value="1" />
            </el-select>
          </el-form-item>
          <el-form-item>
            <el-button type="primary" @click="applyFilter">
              <el-icon aria-hidden="true"><Search /></el-icon> 搜索
            </el-button>
            <el-button @click="resetFilter">
              <el-icon aria-hidden="true"><RefreshLeft /></el-icon> 重置
            </el-button>
          </el-form-item>
        </el-form>
      </div>
    </section>

    <!-- ============ 菜单列表 ============ -->
    <el-card shadow="never">
      <template #header>
        <div class="card-header">
          <div class="head-left">
            <span class="card-title">菜单列表</span>
            <span class="head-desc">
              支持目录 / 菜单 / 按钮三级维护，停用的节点不进侧栏、也无法访问。
            </span>
          </div>
          <div class="header-actions">
            <el-button type="primary" plain :disabled="!canWrite" @click="openCreate(null)">
              <el-icon aria-hidden="true"><Plus /></el-icon> 新增
            </el-button>
            <el-button
              type="danger"
              plain
              :disabled="!canWrite || !selection.length"
              @click="removeSelection"
            >
              <el-icon aria-hidden="true"><Delete /></el-icon> 级联删除
            </el-button>
            <el-tooltip content="刷新" placement="top">
              <el-button :loading="loading" aria-label="刷新" @click="load">
                <el-icon aria-hidden="true"><Refresh /></el-icon>
              </el-button>
            </el-tooltip>
          </div>
        </div>
      </template>

      <el-table
        v-loading="loading"
        :data="tree"
        row-key="menuKey"
        table-layout="fixed"
        :tree-props="{ children: 'children' }"
        :row-class-name="rowClass"
        :default-expand-all="expandAll"
        @selection-change="onSelectionChange"
      >
        <el-table-column type="selection" width="40" />

        <el-table-column label="菜单名称" min-width="180">
          <template #default="{ row }">
            <span class="node">
              <el-icon class="node-icon" :class="{ 'is-group': row.menuType === 'D' }" aria-hidden="true">
                <component :is="iconOf(row)" />
              </el-icon>
              <span class="node-title" :class="{ 'is-group': row.menuType === 'D' }" :title="row.title || row.menuKey">{{ row.title || row.menuKey }}</span>
              <el-tag v-if="row.hidden" size="small" type="info" effect="plain">隐藏</el-tag>
            </span>
          </template>
        </el-table-column>

        <el-table-column label="类型" width="72" align="center">
          <template #default="{ row }">
            <el-tag :type="typeTag(row.menuType)" size="small" effect="light">
              {{ typeName(row.menuType) }}
            </el-tag>
          </template>
        </el-table-column>

        <el-table-column prop="sortOrder" label="排序" width="64" align="center" />

        <el-table-column label="权限标识" min-width="118">
          <template #default="{ row }">
            <span v-if="row.perms" class="mono text-muted" :title="row.perms">{{ row.perms }}</span>
            <span v-else class="text-dim">—</span>
          </template>
        </el-table-column>

        <el-table-column label="路由地址" min-width="96">
          <template #default="{ row }">
            <span v-if="row.path" class="mono text-muted" :title="row.path">{{ row.path }}</span>
            <span v-else class="text-dim">—</span>
          </template>
        </el-table-column>

        <el-table-column label="组件路径" min-width="132">
          <template #default="{ row }">
            <span v-if="row.component" class="mono text-muted" :title="row.component">{{ row.component }}</span>
            <span v-else class="text-dim">—</span>
          </template>
        </el-table-column>

        <el-table-column label="状态" width="66" align="center">
          <template #default="{ row }">
            <el-tag :type="Number(row.status) === 1 ? 'info' : 'success'" size="small" effect="light">
              {{ Number(row.status) === 1 ? '停用' : '正常' }}
            </el-tag>
          </template>
        </el-table-column>

        <el-table-column label="操作" width="110" align="center" fixed="right">
          <template #default="{ row }">
            <div class="row-actions">
              <el-tooltip content="修改" placement="top">
                <el-button :disabled="!canWrite" :aria-label="`修改 ${row.title || row.menuKey}`" @click="openEdit(row)">
                  <el-icon aria-hidden="true"><Edit /></el-icon>
                </el-button>
              </el-tooltip>
              <el-tooltip :content="row.menuType === 'B' ? '按钮下不能再加子项' : '新增子项'" placement="top">
                <el-button
                  :disabled="!canWrite || row.menuType === 'B'"
                  :aria-label="`在 ${row.title || row.menuKey} 下新增子项`"
                  @click="openCreate(row)"
                >
                  <el-icon aria-hidden="true"><Plus /></el-icon>
                </el-button>
              </el-tooltip>
              <el-tooltip content="删除" placement="top">
                <el-button
                  :disabled="!canWrite"
                  :aria-label="`删除 ${row.title || row.menuKey}`"
                  @click="removeOne(row)"
                >
                  <el-icon aria-hidden="true"><Delete /></el-icon>
                </el-button>
              </el-tooltip>
            </div>
          </template>
        </el-table-column>

        <template #empty>
          <div class="empty-cell">
            {{ loading ? '加载中…' : '没有匹配的菜单' }}
          </div>
        </template>
      </el-table>
    </el-card>

    <!-- ============ 新增 / 修改 ============ -->
    <el-dialog v-model="dialog" :title="dialogTitle" width="600px" top="6vh" destroy-on-close>
      <el-form :model="form" label-width="94px" label-position="right">
        <el-form-item v-if="form.menuType !== 'D'" label="上级节点" required>
          <el-tree-select
            v-model="form.parentKey"
            :data="parentOptions"
            :props="{ label: 'title', children: 'children' }"
            node-key="menuKey"
            check-strictly
            default-expand-all
            :render-after-expand="false"
            placeholder="请选择上级"
            class="form-control"
          />
        </el-form-item>

        <el-form-item label="菜单类型" required>
          <el-radio-group v-model="form.menuType" :disabled="!!form.menuKey">
            <el-radio-button value="D">目录</el-radio-button>
            <el-radio-button value="M">菜单</el-radio-button>
            <el-radio-button value="B">按钮</el-radio-button>
          </el-radio-group>
          <span class="inline-hint">{{ typeHint }}</span>
        </el-form-item>

        <el-form-item label="菜单名称" required>
          <el-input v-model="form.title" maxlength="64" placeholder="显示在侧栏上的文字" class="form-control" />
        </el-form-item>

        <el-form-item v-if="form.menuType !== 'B'" label="图标">
          <el-select v-model="form.icon" clearable filterable placeholder="默认不显示图标" class="form-control">
            <el-option v-for="ic in ICON_OPTIONS" :key="ic" :label="ic" :value="ic">
              <span class="icon-option">
                <el-icon aria-hidden="true"><component :is="ic" /></el-icon>
                <span>{{ ic }}</span>
              </span>
            </el-option>
          </el-select>
        </el-form-item>

        <el-form-item label="显示排序">
          <el-input-number v-model="form.sortOrder" :min="0" :max="9999" controls-position="right" />
          <span class="inline-hint">同级升序，留空自动排到末尾</span>
        </el-form-item>

        <template v-if="form.menuType === 'M'">
          <el-form-item label="路由地址" required>
            <el-input v-model="form.path" maxlength="160" placeholder="如 /devices" class="form-control" />
          </el-form-item>
          <el-form-item label="组件路径" required>
            <el-select
              v-model="form.component"
              filterable
              placeholder="views/ 下的组件"
              class="form-control"
            >
              <el-option v-for="c in COMPONENT_OPTIONS" :key="c" :label="c" :value="c" />
            </el-select>
          </el-form-item>
        </template>

        <el-form-item v-if="form.menuType !== 'D'" label="权限标识">
          <el-input
            v-model="form.perms"
            maxlength="128"
            :placeholder="form.menuType === 'B' ? '如 device:write' : '留空 = 登录即可访问'"
            class="form-control"
          />
          <span class="inline-hint">
            {{ form.menuType === 'B' ? '需与后端权限点一致才真正生效' : '页面级权限，留空表示不校验' }}
          </span>
        </el-form-item>

        <el-form-item v-if="form.menuType !== 'B'" label="显示">
          <el-switch v-model="form.visible" active-text="显示" inactive-text="隐藏" />
          <span class="inline-hint">隐藏后不进侧栏，直连 URL 仍可打开</span>
        </el-form-item>

        <el-form-item label="状态">
          <el-radio-group v-model="form.status">
            <el-radio :value="0">正常</el-radio>
            <el-radio :value="1">停用</el-radio>
          </el-radio-group>
          <span class="inline-hint">停用后不进侧栏，路由也不注册</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submit">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { confirmAction } from '@/utils/confirm'
import { menuApi } from '@/api'
import { COMPONENT_OPTIONS, ICON_OPTIONS } from '@/router/routes'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()

const canWrite = computed(() => auth.can('menu:write'))

const rows = ref([])
const loading = ref(false)
const saving = ref(false)
const dialog = ref(false)
const filterOpen = ref(true)
const expandAll = ref(true)
const selection = ref([])

/** 输入中的条件；点「搜索」才写进 {@link applied}，与其它列表页行为一致。 */
const draft = reactive({ title: '', status: '' })
const applied = reactive({ title: '', status: '' })

function applyFilter() {
  applied.title = draft.title.trim()
  applied.status = draft.status
}

function resetFilter() {
  draft.title = ''
  draft.status = ''
  applyFilter()
}

async function load() {
  loading.value = true
  try {
    const data = await menuApi.config()
    rows.value = data?.items || []
  } catch (e) {
    ElMessage.error(e.message || '加载菜单失败')
  } finally {
    loading.value = false
  }
}

/* ==================== 树与筛选 ==================== */

const TYPE_ORDER = { D: 0, M: 1, B: 2 }

function bySort(a, b) {
  const d = (a.sortOrder ?? 0) - (b.sortOrder ?? 0)
  return d !== 0 ? d : String(a.menuKey).localeCompare(String(b.menuKey))
}

/** 全部行 → 三层树（目录 → 菜单 → 按钮），同级按 sortOrder 升序。 */
const fullTree = computed(() => {
  const nodes = new Map(rows.value.map((r) => [r.menuKey, { ...r, children: [] }]))
  const roots = []
  nodes.forEach((n) => {
    const parent = n.parentKey ? nodes.get(n.parentKey) : null
    if (parent) {
      parent.children.push(n)
    } else {
      roots.push(n)
    }
  })
  const sortRec = (list) => {
    list.sort((a, b) => (TYPE_ORDER[a.menuType] ?? 9) - (TYPE_ORDER[b.menuType] ?? 9) || bySort(a, b))
    list.forEach((n) => sortRec(n.children))
  }
  sortRec(roots)
  return roots
})

/** 聚合搜索：名称 / 标识 / 路由 / 组件 任一命中；命中子节点时保留父链。 */
function matches(row) {
  const kw = applied.title.toLowerCase()
  if (kw) {
    const hay = [row.title, row.menuKey, row.perms, row.path, row.component]
      .filter(Boolean)
      .map((v) => String(v).toLowerCase())
    if (!hay.some((v) => v.includes(kw))) return false
  }
  if (applied.status !== '' && String(row.status ?? 0) !== applied.status) return false
  return true
}

const tree = computed(() => {
  const kw = applied.title.toLowerCase()
  const statusFilter = applied.status !== ''
  const walk = (list) =>
    list
      .map((n) => {
        const kids = walk(n.children || [])
        const self =
          (!kw && !statusFilter) ||
          [n.title, n.menuKey, n.perms, n.path, n.component]
            .filter(Boolean)
            .some((v) => String(v).toLowerCase().includes(kw)) &&
          (!statusFilter || String(n.status ?? 0) === applied.status)
        return self || kids.length ? { ...n, children: kids } : null
      })
      .filter(Boolean)
  // 没有任何筛选时直接用原树，避免每次都深拷贝
  if (!kw && !statusFilter) return fullTree.value
  return walk(fullTree.value)
})

/* ==================== 显示辅助 ==================== */

function typeName(t) {
  return t === 'D' ? '目录' : t === 'B' ? '按钮' : '菜单'
}

function typeTag(t) {
  return t === 'D' ? 'primary' : t === 'B' ? 'warning' : 'success'
}

/** 目录用文件夹图标；菜单/按钮用自身图标，按钮没有图标时退化成圆形标记。 */
function iconOf(row) {
  if (row.menuType === 'D') return 'FolderOpened'
  return row.icon || (row.menuType === 'B' ? 'Pointer' : 'Document')
}

function rowClass({ row }) {
  return row.menuType === 'D' ? 'menu-group-row' : ''
}

function onSelectionChange(rowsSelected) {
  selection.value = rowsSelected
}

/* ==================== 表单 ==================== */

const form = reactive({
  menuKey: '',
  parentKey: '',
  menuType: 'M',
  title: '',
  icon: '',
  sortOrder: null,
  visible: true,
  path: '',
  component: '',
  perms: '',
  status: 0
})

const dialogTitle = computed(() => {
  if (!form.menuKey) return form.menuType === 'D' ? '新增目录' : form.menuType === 'B' ? '新增按钮' : '新增菜单'
  return `修改${typeName(form.menuType)} · ${form.title || form.menuKey}`
})

const typeHint = computed(() => {
  if (form.menuType === 'D') return '目录只做侧栏分组，没有页面'
  if (form.menuType === 'B') return '按钮只承载权限标识，不产生路由'
  return '菜单对应一个可打开的页面'
})

/** 上级候选：菜单只能挂目录；按钮挂菜单（也允许挂目录，兼容历史数据）。 */
const parentOptions = computed(() => {
  const dirs = fullTree.value.filter((n) => n.menuType === 'D')
  if (form.menuType === 'B') {
    return dirs.map((d) => ({
      ...d,
      children: (d.children || []).filter((c) => c.menuType === 'M')
    }))
  }
  return dirs.map((d) => ({ ...d, children: [] }))
})

function openCreate(parentRow) {
  Object.assign(form, {
    menuKey: '',
    parentKey: '',
    menuType: 'M',
    title: '',
    icon: '',
    sortOrder: null,
    visible: true,
    path: '',
    component: '',
    perms: '',
    status: 0
  })
  if (parentRow) {
    if (parentRow.menuType === 'D') {
      form.menuType = 'M'
      form.parentKey = parentRow.menuKey
    } else if (parentRow.menuType === 'M') {
      form.menuType = 'B'
      form.parentKey = parentRow.menuKey
    }
  }
  dialog.value = true
}

function openEdit(row) {
  Object.assign(form, {
    menuKey: row.menuKey,
    parentKey: row.parentKey || '',
    menuType: row.menuType,
    title: row.title || '',
    icon: row.icon || '',
    sortOrder: row.sortOrder ?? null,
    visible: !row.hidden,
    path: row.path || '',
    component: row.component || '',
    perms: row.perms || '',
    status: Number(row.status ?? 0)
  })
  dialog.value = true
}

async function submit() {
  if (!form.title.trim()) {
    ElMessage.warning('菜单名称不能为空')
    return
  }
  if (form.menuType !== 'D' && !form.parentKey) {
    ElMessage.warning('请选择上级节点')
    return
  }
  if (form.menuType === 'M' && (!form.path.trim() || !form.component)) {
    ElMessage.warning('菜单必须有路由地址和组件路径')
    return
  }
  if (form.menuType === 'B' && !form.perms.trim()) {
    ElMessage.warning('按钮必须有权限标识')
    return
  }
  saving.value = true
  try {
    await menuApi.save({
      menu_key: form.menuKey || null,
      parent_key: form.menuType === 'D' ? null : form.parentKey,
      menu_type: form.menuType,
      title: form.title.trim(),
      icon: form.menuType === 'B' ? null : form.icon || null,
      sort_order: form.sortOrder,
      hidden: form.menuType === 'B' ? false : !form.visible,
      path: form.menuType === 'M' ? form.path.trim() : null,
      component: form.menuType === 'M' ? form.component : null,
      perms: form.menuType === 'D' ? null : form.perms.trim() || null,
      status: form.status
    })
    ElMessage.success('已保存')
    dialog.value = false
    await refresh()
  } catch (e) {
    ElMessage.error(e.message || '保存失败')
  } finally {
    saving.value = false
  }
}

/* ==================== 删除 ==================== */

async function removeOne(row) {
  const hasChildren = (row.children || []).length > 0
  const ok = await confirmAction(
    `确定删除「${row.title || row.menuKey}」？${hasChildren ? '它的全部下级会一起删除。' : ''}`,
    '删除菜单',
    { confirmButtonText: '删除' }
  )
  if (!ok) return
  try {
    await menuApi.remove(row.menuKey, hasChildren)
    ElMessage.success('已删除')
    await refresh()
  } catch (e) {
    ElMessage.error(e.message || '删除失败')
  }
}

async function removeSelection() {
  if (!selection.value.length) return
  const names = selection.value.map((r) => r.title || r.menuKey).join('、')
  const ok = await confirmAction(
    `确定删除选中的 ${selection.value.length} 个节点？它们的全部下级会一起删除。\n${names}`,
    '级联删除',
    { confirmButtonText: '删除' }
  )
  if (!ok) return
  try {
    for (const row of selection.value) {
      await menuApi.remove(row.menuKey, true)
    }
    ElMessage.success('已删除')
    selection.value = []
    await refresh()
  } catch (e) {
    ElMessage.error(e.message || '删除失败')
  }
}

/** 保存/删除后：重拉列表 + 重建动态路由与侧栏。 */
async function refresh() {
  await load()
  await auth.loadMenuRows()
}

onMounted(load)
</script>

<style scoped>
/* ==================== 筛选面板 ==================== */

.filter-panel {
  background: var(--bg-surface);
  border: 1px solid var(--line);
  border-radius: var(--r-panel);
  box-shadow: var(--sh-panel);
  margin-bottom: var(--sp-3);
  overflow: hidden;
}

.panel-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
  padding: 0 var(--sp-4);
  height: 44px;
  border: none;
  background: transparent;
  cursor: pointer;
  font: inherit;
}

.panel-head:hover .panel-title {
  color: var(--brand-500);
}

.panel-title {
  font-size: var(--fs-sm);
  font-weight: 600;
  color: var(--text-primary);
}

.panel-toggle {
  color: var(--text-tertiary);
  font-size: var(--fs-md);
}

.panel-body {
  padding: 0 var(--sp-4) var(--sp-3);
  border-top: 1px solid var(--line-soft);
  padding-top: var(--sp-3);
}

.panel-body :deep(.el-form-item) {
  margin-bottom: 0;
  margin-right: var(--sp-4);
}

.panel-body :deep(.el-form-item__label) {
  color: var(--ink-600);
  font-weight: 500;
}

/* ==================== 卡头 ==================== */

.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sp-3);
  width: 100%;
  flex-wrap: wrap;
}

.head-left {
  display: flex;
  align-items: baseline;
  gap: var(--sp-3);
  min-width: 0;
}

.card-title {
  flex: none;
  font-weight: 600;
  font-size: var(--fs-md);
  color: var(--text-primary);
}

.head-desc {
  font-size: var(--fs-xs);
  font-weight: 400;
  color: var(--text-tertiary);
}

.header-actions {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  flex: none;
}

/* ==================== 表格 ==================== */

.node {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  min-width: 0;
}

.node-icon {
  flex: none;
  color: var(--ink-400);
}

.node-icon.is-group {
  color: var(--ink-600);
}

.node-title {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

:deep(.menu-group-row > td) {
  background: var(--ink-50);
}

:deep(.menu-group-row .node-title) {
  font-weight: 600;
}

/* 树形箭头与内容同排（Element 的 .cell 默认块级流式，箭头会独占一行） */
:deep(.el-table__row .cell) {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
}

.row-actions {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 2px;
}

.row-actions :deep(.el-button) {
  padding: 4px 6px;
  height: auto;
}

/* Element 给相邻按钮加了 12px 左外边距，在 flex 里会和 gap 叠加把三颗按钮撑出单元格 */
.row-actions :deep(.el-button + .el-button) {
  margin-left: 0;
}

.icon-option {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
}

.inline-hint {
  margin-left: var(--sp-3);
  font-size: var(--fs-xs);
  color: var(--text-tertiary);
  line-height: 1.5;
}

.form-control {
  width: 100%;
}

.empty-cell {
  padding: var(--sp-4);
  color: var(--text-tertiary);
}
</style>
