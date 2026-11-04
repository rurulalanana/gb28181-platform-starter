<template>
  <div>

    <!-- 工具条：操作按钮靠右，与其它页保持一致 -->
    <div class="filter-bar">
      <KeywordFilter v-model="keyword" placeholder="搜索角色名称 / 编码 / 说明" width="280px" />
      <span class="filter-bar-spacer" />
      <el-button size="small" :loading="loading" @click="loadAll">
        <el-icon><Refresh /></el-icon> 刷新
      </el-button>
      <el-button
        size="small"
        type="primary"
        :disabled="!canWrite"
        @click="openEdit(null)"
      >
        <el-icon><Plus /></el-icon> 新增角色
      </el-button>
    </div>

    <el-card shadow="never">
      <el-table v-loading="loading" :data="filteredRoles" stripe table-layout="fixed">
        <el-table-column label="角色" min-width="170">
          <template #default="{ row }">
            <div class="role-cell">
              <span class="role-name">{{ row.name }}</span>
              <el-tag v-if="row.superRole" type="primary" size="small" effect="light">超级</el-tag>
              <el-tag v-else-if="row.builtin" type="info" size="small" effect="plain">内置</el-tag>
              <span class="role-code mono">{{ row.code }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="说明" min-width="200">
          <template #default="{ row }">
            <span class="text-muted">{{ row.description || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="权限" width="120" align="center">
          <template #default="{ row }">
            <el-tag size="small" effect="plain" :type="row.superRole ? 'primary' : 'info'">
              {{ row.superRole ? '全部' : `${row.permissions?.length || 0} 项` }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="用户数" width="90" align="center">
          <template #default="{ row }">
            <span class="text-muted">{{ row.userCount ?? 0 }}</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90" align="center">
          <template #default="{ row }">
            <el-tag :type="row.enable ? 'success' : 'info'" size="small" effect="light">
              {{ row.enable ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="150" align="center" fixed="right">
          <template #default="{ row }">
            <div class="row-actions">
              <el-tooltip :content="row.superRole ? '超级角色拥有全部权限，不可编辑' : '编辑权限'">
                <el-button :disabled="!canWrite || row.superRole" aria-label="编辑权限" @click="openEdit(row)">
                  <el-icon aria-hidden="true"><Edit /></el-icon>
                </el-button>
              </el-tooltip>
              <el-tooltip :content="row.enable ? '停用' : '启用'">
                <el-button
                  :disabled="!canWrite || row.superRole"
                  :aria-label="row.enable ? '停用角色' : '启用角色'"
                  @click="toggleEnable(row)"
                >
                  <el-icon aria-hidden="true"><SwitchButton /></el-icon>
                </el-button>
              </el-tooltip>
              <el-tooltip :content="row.builtin ? '内置角色不可删除' : '删除'">
                <el-button
                  class="is-danger"
                  :disabled="!canWrite || row.builtin"
                  aria-label="删除角色"
                  @click="removeOne(row)"
                >
                  <el-icon aria-hidden="true"><Delete /></el-icon>
                </el-button>
              </el-tooltip>
            </div>
          </template>
        </el-table-column>
        <template #empty>
          <div class="empty-cell">暂无角色</div>
        </template>
      </el-table>
    </el-card>

    <!-- 编辑角色 -->
    <el-dialog
      v-model="editDialog"
      :title="form.created ? '新增角色' : `编辑角色 · ${form.name}`"
      width="min(900px, 94vw)"
      top="5vh"
      class="role-dialog"
    >
      <el-form :model="form" label-width="88px">
        <el-form-item label="角色编码">
          <el-input
            v-model="form.code"
            :disabled="!form.created"
            placeholder="小写字母开头，如 patrol"
          />
          <div class="field-hint">
            编码是系统内部标识，创建后不能修改；用户表里存的就是它。
          </div>
        </el-form-item>
        <el-form-item label="名称">
          <el-input v-model="form.name" placeholder="如 巡检员" />
        </el-form-item>
        <el-form-item label="说明">
          <el-input v-model="form.description" placeholder="这个角色是干什么的" />
        </el-form-item>
        <el-form-item label="权限" class="perm-form-item">
          <!-- 工具条：展开/折叠 · 全选/全不选 · 父子联动（对齐若依的权限分配交互） -->
          <div class="perm-toolbar">
            <el-checkbox v-model="expandAll">展开/折叠</el-checkbox>
            <el-checkbox v-model="checkAll">全选/全不选</el-checkbox>
            <el-checkbox v-model="linkage">父子联动</el-checkbox>
            <span class="perm-count">已选 {{ form.permissions.length }} / {{ allPermCodes.length }}</span>
            <el-button size="small" text @click="selectReadonly">仅只读</el-button>
          </div>

          <!-- 树形表格：左侧菜单名称（目录 → 菜单），右侧该菜单下的按钮权限 -->
          <div class="perm-tree">
            <div class="perm-tree-head">
              <span class="col-menu">菜单名称</span>
              <span class="col-btns">按钮权限</span>
            </div>
            <div class="perm-tree-body">
              <div v-for="g in permTree" :key="g.key" class="perm-group-block">
                <!-- 目录行 -->
                <div class="perm-row is-group">
                  <label class="col-menu">
                    <el-checkbox
                      :model-value="isAllChecked(g.codes)"
                      :indeterminate="isPartialChecked(g.codes)"
                      @change="(v) => setCodes(g.codes, v)"
                    />
                    <button
                      type="button"
                      class="perm-caret"
                      :aria-label="isExpanded(g.key) ? '收起' : '展开'"
                      @click="toggleExpand(g.key)"
                    >
                      <el-icon><CaretBottom v-if="isExpanded(g.key)" /><CaretRight v-else /></el-icon>
                    </button>
                    <span class="perm-name">{{ g.name }}</span>
                  </label>
                  <span class="col-btns" />
                </div>

                <!-- 菜单行 + 按钮组 -->
                <template v-if="isExpanded(g.key)">
                  <div v-for="m in g.modules" :key="m.key" class="perm-row is-module">
                    <label class="col-menu">
                      <el-checkbox
                        :model-value="isAllChecked(m.codes)"
                        :indeterminate="isPartialChecked(m.codes)"
                        @change="(v) => setCodes(m.codes, v)"
                      />
                      <span class="perm-name">{{ m.name }}</span>
                    </label>
                    <div class="col-btns">
                      <el-checkbox
                        v-for="b in m.buttons"
                        :key="b.code"
                        :model-value="form.permissions.includes(b.code) || isInherited(b)"
                        :disabled="isInherited(b)"
                        :title="b.description"
                        class="perm-btn"
                        @change="(v) => toggleCode(b, v)"
                      >
                        {{ b.name }}
                        <span v-if="isInherited(b)" class="perm-inherited">含在上项</span>
                      </el-checkbox>
                    </div>
                  </div>
                </template>
              </div>
            </div>
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { confirmAction } from '@/utils/confirm'
import { roleApi } from '@/api'
import { useAuthStore } from '@/stores/auth'
import KeywordFilter from '@/components/KeywordFilter.vue'

const auth = useAuthStore()

const roles = ref([])
const catalog = ref([])
const loading = ref(false)
const saving = ref(false)
const editDialog = ref(false)

const form = reactive({
  created: true,
  code: '',
  name: '',
  description: '',
  permissions: []
})

const canWrite = computed(() => auth.can('role:write'))

/** 聚合搜索关键词（名称 / 编码 / 说明）。 */
const keyword = ref('')

const filteredRoles = computed(() => {
  const q = keyword.value.trim().toLowerCase()
  if (!q) return roles.value
  return roles.value.filter((r) =>
    [r.name, r.code, r.description].filter(Boolean).some((v) => String(v).toLowerCase().includes(q))
  )
})

/** 目录里的全部权限点编码，用于「全选」与计数。 */
const allPermCodes = computed(() =>
  catalog.value.flatMap((g) => g.permissions.map((p) => p.code))
)

/** 上级编码 → 它包含的下级编码（由后端 PermissionVo.parent 反推）。 */
const childrenMap = computed(() => {
  const map = {}
  for (const g of catalog.value) {
    for (const p of g.permissions) {
      if (!p.parent) continue
      ;(map[p.parent] ||= []).push(p.code)
    }
  }
  return map
})

/** 下级编码 → 上级编码。 */
const parentMap = computed(() => {
  const map = {}
  for (const g of catalog.value) {
    for (const p of g.permissions) {
      if (p.parent) map[p.code] = p.parent
    }
  }
  return map
})

/** 父子联动默认开：勾目录即勾全部下级，符合多数人的预期。 */
const linkage = ref(true)
/** 已展开的目录 key 集合。 */
const expanded = ref(new Set())

/**
 * 权限树：group（目录）→ module（菜单）→ buttons。
 *
 * <p>后端 {@code /role/permissions} 返回「分组 + 扁平权限点」，每个点带 {@code module}；
 * 这里按 module 聚一次，得到角色页要的三级结构。
 */
const permTree = computed(() => {
  const out = []
  for (const g of catalog.value) {
    const node = { key: g.group, name: g.group, modules: [], codes: [] }
    const byModule = new Map()
    for (const p of g.permissions) {
      const mk = p.module || '其他'
      let m = byModule.get(mk)
      if (!m) {
        m = { key: `${g.group}/${mk}`, name: mk, buttons: [], codes: [] }
        byModule.set(mk, m)
        node.modules.push(m)
      }
      m.buttons.push(p)
      m.codes.push(p.code)
      node.codes.push(p.code)
    }
    out.push(node)
  }
  return out
})

watch(permTree, (tree) => {
  expanded.value = new Set(tree.map((g) => g.key))
})

/**
 * 展开/折叠全部。用 computed 而不是 ref + watch：
 * 用户手动收起一个目录后，总开关要自动回弹，否则会显示「已全部展开」而实际有折叠项。
 */
const expandAll = computed({
  get: () => permTree.value.length > 0 && permTree.value.every((g) => expanded.value.has(g.key)),
  set: (v) => {
    expanded.value = v ? new Set(permTree.value.map((g) => g.key)) : new Set()
  }
})

/** 全选/全不选。同样用 computed：手动取消任意一项后总开关自动回弹。 */
const checkAll = computed({
  get: () => allPermCodes.value.length > 0 && allPermCodes.value.every((c) => isChecked(c)),
  set: (v) => {
    // 只勾叶子按钮，不带「完全控制」：否则每项都显示「含在上项」，看不出真实授权
    form.permissions = v ? allPermCodes.value.filter((c) => !childrenMap.value[c]) : []
  }
})

function isExpanded(key) {
  return expanded.value.has(key)
}

function toggleExpand(key) {
  const next = new Set(expanded.value)
  if (next.has(key)) next.delete(key)
  else next.add(key)
  expanded.value = next
}

/** 某编码是否实际生效：自己勾了，或被上级「完全控制」包含。 */
function isChecked(code) {
  if (form.permissions.includes(code)) return true
  const parent = parentMap.value[code]
  return !!parent && form.permissions.includes(parent)
}

function isAllChecked(codes) {
  return codes.length > 0 && codes.every((c) => isChecked(c))
}

function isPartialChecked(codes) {
  return !isAllChecked(codes) && codes.some((c) => isChecked(c))
}

/** 被上级「完全控制」包含时置灰：取消它没有意义，取消上级即可。 */
function isInherited(p) {
  return !!p.parent && form.permissions.includes(p.parent)
}

/** 批量设置一组编码（目录行 / 菜单行）。父子联动关闭时不动下级。 */
function setCodes(codes, checked) {
  const set = new Set(form.permissions)
  const list = linkage.value
    ? codes.flatMap((c) => [c, ...(childrenMap.value[c] || [])])
    : codes
  for (const c of list) {
    if (checked) set.add(c)
    else set.delete(c)
  }
  form.permissions = [...set]
}

/** 单个按钮勾选。父子联动开启时勾上级会带上下级。 */
function toggleCode(p, checked) {
  const set = new Set(form.permissions)
  if (checked) set.add(p.code)
  else set.delete(p.code)
  if (linkage.value && childrenMap.value[p.code]) {
    for (const c of childrenMap.value[p.code]) {
      if (checked) set.add(c)
      else set.delete(c)
    }
  }
  form.permissions = [...set]
}

async function loadAll() {
  loading.value = true
  try {
    // 两个接口都只读且轻量，并行拉取；目录是静态数据，失败时页面仍可用（只是没勾选框）
    const [roleData, permData] = await Promise.all([
      roleApi.list(),
      roleApi.permissions().catch(() => null)
    ])
    roles.value = roleData?.roleList || []
    catalog.value = permData?.groupList || []
  } catch (e) {
    ElMessage.error(e.message || '加载角色失败')
  } finally {
    loading.value = false
  }
}

function openEdit(row) {
  if (row) {
    Object.assign(form, {
      created: false,
      code: row.code,
      name: row.name,
      description: row.description || '',
      // 不展开下級：「完全控制」的子项由 isChecked() 按 parent 推导显示，
      // 存盘时保留原始编码即可（后端 expand() 会展开）
      permissions: [...(row.permissions || [])]
    })
  } else {
    Object.assign(form, {
      created: true,
      code: '',
      name: '',
      description: '',
      permissions: ['device:read', 'channel:read', 'play:live']
    })
  }
  // 注意：不要在这里重置 checkAll —— 它是 computed，写它会触发 setter 把权限清空。
  // 勾选状态由 form.permissions 反推，切角色后自动正确。
  editDialog.value = true
}
/** 一键只保留「只读」类权限：新增角色时最常用的一档。 */
function selectReadonly() {
  form.permissions = allPermCodes.value.filter(
    (c) => c.endsWith(':read') || c === 'play:live' || c === 'play:record'
  )
}

async function save() {
  if (!form.code) {
    ElMessage.warning('请填写角色编码')
    return
  }
  if (!form.name) {
    ElMessage.warning('请填写角色名称')
    return
  }
  if (!form.permissions.length) {
    ElMessage.warning('至少要勾选一个权限点')
    return
  }
  saving.value = true
  try {
    await roleApi.save({
      code: form.code,
      name: form.name,
      description: form.description,
      permissions: form.permissions.join(',')
    })
    ElMessage.success('已保存')
    editDialog.value = false
    await loadAll()
    // 自己的角色被改过时，权限清单已经变了 —— 立刻刷新一次，否则界面还按旧权限渲染
    await auth.loadUser()
  } catch (e) {
    ElMessage.error(e.message || '保存失败')
  } finally {
    saving.value = false
  }
}

async function toggleEnable(row) {
  try {
    await roleApi.setEnable({ code: row.code, enable: !row.enable })
    row.enable = !row.enable
    ElMessage.success(row.enable ? '已启用' : '已停用')
    await auth.loadUser()
  } catch (e) {
    ElMessage.error(e.message || '操作失败')
  }
}

async function removeOne(row) {
  if (!(await confirmAction(
    `确定删除角色「${row.name}」？删除后使用该角色的用户将立即失去对应权限。`,
    '删除确认',
    { confirmButtonText: '删除' }
  ))) return
  try {
    await roleApi.remove(row.code)
    ElMessage.success('已删除')
    await loadAll()
  } catch (e) {
    ElMessage.error(e.message || '删除失败')
  }
}

onMounted(loadAll)
</script>

<style scoped>
.role-cell {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  flex-wrap: wrap;
}

.role-name {
  font-weight: 600;
}

.role-code {
  font-size: var(--fs-xs);
  color: var(--ink-500);
}

.field-hint {
  margin-top: var(--sp-1);
  font-size: var(--fs-xs);
  line-height: 1.6;
  color: var(--ink-600);
  white-space: normal;
}

.perm-toolbar {
  display: flex;
  align-items: center;
  gap: var(--sp-4);
  width: 100%;
  margin-bottom: var(--sp-2);
  padding-bottom: var(--sp-2);
  border-bottom: 1px solid var(--line-200);
}

.perm-count {
  margin-left: auto;
  font-size: var(--fs-xs);
  color: var(--ink-600);
}

/*
 * 权限树：仿若依的「菜单名称 | 按钮权限」两列表格。
 *
 * <p>用普通 div 而非 el-table：el-table 的 tree 模式要求固定字段名与 children 结构，
 * 而这里按钮是行内网格，塞进列模板反而更难对齐。
 */
.perm-tree {
  width: 100%;
  border: 1px solid var(--line-200);
  border-radius: var(--radius-sm, 6px);
  overflow: hidden;
}

.perm-tree-head,
.perm-row {
  display: grid;
  grid-template-columns: 260px 1fr;
  align-items: start;
}

.perm-tree-head {
  background: var(--bg-sunken);
  border-bottom: 1px solid var(--line-200);
  font-size: var(--fs-xs);
  font-weight: 600;
  color: var(--ink-700);
}

.perm-tree-head > span {
  padding: 8px 12px;
}

/* 限高 + 内部滚动：权限点会随功能增加，弹窗不能跟着无限变高 */
.perm-tree-body {
  max-height: 50vh;
  overflow-y: auto;
}

.perm-row {
  padding: 4px 0;
  border-bottom: 1px solid var(--line-100, var(--line-200));
}

.perm-row:last-child {
  border-bottom: 0;
}

/* 目录行：加底色与加粗，与菜单行拉开层次 */
.perm-row.is-group {
  background: var(--bg-sunken);
  font-weight: 600;
}

.perm-row .col-menu {
  display: flex;
  align-items: center;
  gap: 2px;
  padding: 4px 12px;
  min-height: 30px;
}

.perm-row.is-module .col-menu {
  /* 二级菜单缩进一格，与目录区分 */
  padding-left: 34px;
}

.perm-row.is-module:hover {
  background: var(--brand-50, var(--bg-sunken));
}

/* 按钮网格：每行 3~4 个等宽列。中文名长短不一时也能对齐；
   最小 118px 是「重置密码」这类 4 字标签不被挤成两行的临界值。 */
.perm-row .col-btns {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(118px, 1fr));
  gap: 2px 12px;
  padding: 4px 12px 6px 0;
}

.perm-btn {
  height: auto;
  margin-right: 0;
}

.perm-btn :deep(.el-checkbox__label) {
  white-space: normal;
  line-height: 1.45;
  font-size: var(--fs-sm);
}

.perm-name {
  font-size: var(--fs-sm);
}

/* 展开/收起箭头：用 button 而不是 el-icon 直接绑 click —— 后者点在小图标上易失效 */
.perm-caret {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 18px;
  height: 18px;
  margin-right: 2px;
  padding: 0;
  color: var(--ink-600);
  background: transparent;
  border: 0;
  cursor: pointer;
}

.perm-caret:hover {
  color: var(--brand-600);
}

.perm-inherited {
  margin-left: 4px;
  font-size: 11px;
  color: var(--brand-600);
  border: 1px solid var(--brand-200);
  border-radius: var(--r-full);
  padding: 0 5px;
  line-height: 1.5;
  white-space: nowrap;
}

.row-actions {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 2px;
  flex-wrap: nowrap;
}
</style>
