<template>
  <div>
    <div class="filter-bar">
      <span class="filter-bar-desc">
        菜单的名称、图标、分组与顺序都可以在这里调整，保存后全站侧栏立即生效。
        隐藏只影响菜单显示，不影响页面访问与权限。
      </span>
      <span class="filter-bar-spacer" />
      <el-button size="small" :loading="loading" @click="load">
        <el-icon><Refresh /></el-icon> 刷新
      </el-button>
      <el-button size="small" type="danger" plain :disabled="!canWrite || !hasAnyCustom" @click="resetAll">
        <el-icon><RefreshLeft /></el-icon> 全部恢复默认
      </el-button>
    </div>

    <el-card shadow="never">
      <el-table
        v-loading="loading"
        :data="tree"
        row-key="rowKey"
        stripe
        table-layout="fixed"
        :tree-props="{ children: 'children' }"
        default-expand-all
      >
        <el-table-column label="菜单 / 分组" min-width="240">
          <template #default="{ row }">
            <div class="menu-cell" :class="{ 'is-group': row.isGroup }">
              <el-icon v-if="row.isGroup" class="group-icon"><FolderOpened /></el-icon>
              <el-icon v-else class="item-icon"><component :is="row.icon" /></el-icon>
              <span class="menu-title">{{ row.title }}</span>
              <el-tag v-if="row.customized" size="small" type="warning" effect="plain">已改</el-tag>
              <span v-if="row.hidden" class="hidden-flag">已隐藏</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="标识" width="150">
          <template #default="{ row }">
            <!-- 分组编码带内部前缀（g-xxx），对使用者无意义，不展示 -->
            <span class="mono text-muted">{{ row.isGroup ? '—' : row.name }}</span>
          </template>
        </el-table-column>
        <el-table-column label="路径" width="150">
          <template #default="{ row }">
            <span class="mono text-muted">{{ row.isGroup ? '—' : row.path }}</span>
          </template>
        </el-table-column>
        <el-table-column label="默认" min-width="150">
          <template #default="{ row }">
            <span class="text-muted">
              {{ row.defaultTitle }}
              <template v-if="!row.isGroup"> · {{ row.defaultIcon }}</template>
            </span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="140" align="center" fixed="right">
          <template #default="{ row }">
            <div class="row-actions">
              <el-tooltip :content="row.isGroup ? '修改分组名称' : '修改菜单'">
                <el-button :disabled="!canWrite" @click="openEdit(row)">
                  <el-icon><Edit /></el-icon>
                </el-button>
              </el-tooltip>
              <el-tooltip content="恢复默认">
                <el-button :disabled="!canWrite || !row.customized" @click="resetOne(row)">
                  <el-icon><RefreshLeft /></el-icon>
                </el-button>
              </el-tooltip>
            </div>
          </template>
        </el-table-column>
        <template #empty>
          <div class="empty-cell">暂无菜单</div>
        </template>
      </el-table>
    </el-card>

    <el-dialog v-model="editDialog" :title="dialogTitle" width="520px">
      <el-form :model="form" label-width="88px">
        <el-form-item v-if="!form.isGroup" label="所属分组">
          <el-select v-model="form.parentKey" placeholder="用默认分组" clearable>
            <el-option
              v-for="g in groups"
              :key="g.code"
              :label="g.title"
              :value="g.code"
            />
          </el-select>
          <div class="field-hint">清空则回到代码里声明的默认分组。</div>
        </el-form-item>
        <el-form-item :label="form.isGroup ? '分组名称' : '显示名称'">
          <el-input v-model="form.title" :placeholder="form.defaultTitle" />
          <div class="field-hint">
            留空则使用默认名称「{{ form.defaultTitle }}」。
            {{ form.isGroup ? '分组名称会直接显示在侧栏上，建议填写。' : '' }}
          </div>
        </el-form-item>
        <el-form-item v-if="!form.isGroup" label="图标">
          <el-select v-model="form.icon" placeholder="用默认图标" clearable filterable>
            <el-option v-for="ic in ICON_OPTIONS" :key="ic" :label="ic" :value="ic">
              <span class="icon-option">
                <el-icon><component :is="ic" /></el-icon>
                <span>{{ ic }}</span>
              </span>
            </el-option>
          </el-select>
          <div class="field-hint">默认图标：{{ form.defaultIcon }}</div>
        </el-form-item>
        <el-form-item label="排序">
          <el-input-number v-model="form.sortOrder" :min="0" :max="999" controls-position="right" />
          <div class="field-hint">同级内由小到大。留空用代码里的声明顺序。</div>
        </el-form-item>
        <el-form-item v-if="!form.isGroup" label="在菜单中隐藏">
          <el-switch v-model="form.hidden" />
          <div class="field-hint">
            隐藏后侧栏看不到，但直接输地址仍可访问（受角色权限约束）。
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
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { confirmAction } from '@/utils/confirm'
import { menuApi } from '@/api'
import { ICON_OPTIONS, menuCatalog } from '@/router/routes'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()

const raw = ref({ items: [] })
const loading = ref(false)
const saving = ref(false)
const editDialog = ref(false)

const canWrite = computed(() => auth.can('system:write'))

/** 合并默认值后的目录树（分组 → 菜单项）。 */
const catalog = computed(() => menuCatalog(raw.value))
const tree = computed(() => catalog.value.tree)
const groups = computed(() => catalog.value.groups)

const hasAnyCustom = computed(() =>
  catalog.value.groups.some((g) => g.customized) || catalog.value.items.some((i) => i.customized)
)

const form = reactive({
  isGroup: false,
  key: '',
  parentKey: '',
  title: '',
  icon: '',
  sortOrder: null,
  hidden: false,
  defaultTitle: '',
  defaultIcon: ''
})

const dialogTitle = computed(() => {
  if (form.isGroup) return `修改分组 · ${form.defaultTitle}`
  return `修改菜单 · ${form.defaultTitle}`
})

async function load() {
  loading.value = true
  try {
    raw.value = (await menuApi.config()) || { items: [] }
  } catch (e) {
    ElMessage.error(e.message || '加载菜单配置失败')
  } finally {
    loading.value = false
  }
}

function openEdit(row) {
  Object.assign(form, {
    isGroup: !!row.isGroup,
    key: row.isGroup ? row.code : row.name,
    parentKey: row.isGroup ? '' : row.groupCode,
    title: row.title === row.defaultTitle ? '' : row.title,
    icon: row.isGroup ? '' : (row.icon === row.defaultIcon ? '' : row.icon),
    sortOrder: row.sortOrder === row.defaultSort ? null : row.sortOrder,
    hidden: row.hidden,
    defaultTitle: row.defaultTitle,
    defaultIcon: row.defaultIcon || ''
  })
  editDialog.value = true
}

async function save() {
  if (form.isGroup && !form.title.trim()) {
    ElMessage.warning('分组名称不能为空')
    return
  }
  saving.value = true
  try {
    const params = {
      menu_key: form.key,
      menu_type: form.isGroup ? 'D' : 'M'
    }
    // 只在「与默认值不同」时才传，避免把默认值固化成一条覆盖记录
    if (form.title.trim()) params.title = form.title.trim()
    if (form.isGroup) {
      if (form.sortOrder != null) params.sort_order = form.sortOrder
    } else {
      if (form.parentKey) params.parent_key = form.parentKey
      if (form.icon) params.icon = form.icon
      if (form.sortOrder != null) params.sort_order = form.sortOrder
      if (form.hidden) params.hidden = true
    }
    await menuApi.save(params)
    ElMessage.success('已保存')
    editDialog.value = false
    await load()
    // 侧栏立刻跟着变，不用刷新页面
    await auth.loadMenuConfig()
  } catch (e) {
    ElMessage.error(e.message || '保存失败')
  } finally {
    saving.value = false
  }
}

async function resetOne(row) {
  const key = row.isGroup ? row.code : row.name
  const label = row.isGroup ? '分组' : '菜单'
  if (!(await confirmAction(
    `把${label}「${row.defaultTitle}」恢复成默认外观？`, '恢复默认'
  ))) return
  try {
    await menuApi.reset(key)
    ElMessage.success('已恢复默认')
    await load()
    await auth.loadMenuConfig()
  } catch (e) {
    ElMessage.error(e.message || '恢复失败')
  }
}

async function resetAll() {
  if (!(await confirmAction(
    '把全部菜单恢复成默认外观？所有自定义的名称、图标、分组与排序都会丢失。',
    '全部恢复默认', { confirmButtonText: '恢复' }
  ))) return
  try {
    await menuApi.resetAll()
    ElMessage.success('已全部恢复默认')
    await load()
    await auth.loadMenuConfig()
  } catch (e) {
    ElMessage.error(e.message || '恢复失败')
  }
}

onMounted(load)
</script>

<style scoped>
.filter-bar-desc {
  font-size: var(--fs-xs);
  color: var(--ink-600);
  max-width: 620px;
  line-height: 1.6;
}

.menu-cell {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
}

.menu-cell.is-group {
  font-weight: 600;
}

.group-icon,
.item-icon {
  color: var(--ink-500);
  flex: none;
}

.menu-title {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.hidden-flag {
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

.icon-option {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
}

.row-actions {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 2px;
  flex-wrap: nowrap;
}
</style>
