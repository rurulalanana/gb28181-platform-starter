<template>
  <div class="page is-fill">

    <el-card shadow="never">
      <template #header>
        <div class="card-header">
          <div class="card-title">
            <el-icon><Menu /></el-icon>
            <span>菜单结构</span>
            <span class="count-pill">{{ itemCount }}</span>
            <!-- 说明收进 tooltip：内容区宽度有限，一整句放在卡头会把标题挤成两行 -->
            <el-tooltip placement="top">
              <template #content>
                改名、换图标、挪分组、调顺序，保存后侧栏立即生效；<br />
                隐藏只影响菜单显示，不影响页面访问与权限。
              </template>
              <el-icon class="hint-icon"><QuestionFilled /></el-icon>
            </el-tooltip>
          </div>
          <div class="header-actions">
            <el-button size="small" :loading="loading" @click="load">
              <el-icon><Refresh /></el-icon> 刷新
            </el-button>
            <el-button
              size="small"
              type="danger"
              plain
              :disabled="!canWrite || !hasAnyCustom"
              @click="resetAll"
            >
              <el-icon><RefreshLeft /></el-icon> 全部恢复默认
            </el-button>
          </div>
        </div>
      </template>

      <el-table
        ref="tableRef"
        v-loading="loading"
        :data="tree"
        row-key="rowKey"
        table-layout="fixed"
        :tree-props="{ children: 'children' }"
        :row-class-name="rowClass"
        default-expand-all
      >
        <!--
          一级列承载「图标 + 名称 + 状态 + 标识」。
          标识（路由 name）挂在名称右侧而不是单开一列：
          它只在排查问题时有用，单占一列会把宽度浪费在 6 个字符上，
          还会把「默认值」列挤出可视区（改版前就是这样横向溢出的）。
        -->
        <el-table-column label="菜单" min-width="330" class-name="col-menu">
          <template #default="{ row }">
            <div class="menu-cell">
              <el-icon class="menu-icon" :class="{ 'is-group': row.isGroup }">
                <FolderOpened v-if="row.isGroup" />
                <component :is="row.icon" v-else />
              </el-icon>
              <span class="menu-title" :class="{ 'is-group': row.isGroup }">{{ row.title }}</span>
              <el-tag v-if="row.customized" size="small" type="warning" effect="plain" class="menu-badge">
                已改
              </el-tag>
              <el-tag v-if="row.hidden" size="small" type="info" effect="plain" class="menu-badge">
                已隐藏
              </el-tag>
              <span class="menu-meta">
                {{ row.isGroup ? `${row.children.length} 项` : row.name }}
              </span>
            </div>
          </template>
        </el-table-column>

        <!--
          默认值列：<b>只在确实有自定义时才出现</b>。
          没改过时整列都是「—」，既没用又抢注意力（改版前就是那样）。
        -->
        <el-table-column v-if="hasAnyCustom" label="默认" min-width="190">
          <template #default="{ row }">
            <span v-if="row.customized" class="default-value">
              {{ row.defaultTitle }}<template v-if="!row.isGroup"> · {{ row.defaultIcon }}</template>
            </span>
            <span v-else class="text-dim">—</span>
          </template>
        </el-table-column>

        <el-table-column label="操作" width="96" align="right">
          <template #default="{ row }">
            <div class="row-actions">
              <el-tooltip :content="row.isGroup ? '改分组名称' : '改菜单'" placement="top">
                <el-button :disabled="!canWrite" @click="openEdit(row)">
                  <el-icon><Edit /></el-icon>
                </el-button>
              </el-tooltip>
              <el-tooltip content="恢复默认" placement="top">
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

    <el-dialog v-model="editDialog" :title="dialogTitle" width="520px" top="6vh">
      <el-form :model="form" label-width="92px" label-position="right">
        <el-form-item v-if="!form.isGroup" label="所属分组">
          <el-select v-model="form.parentKey" placeholder="用默认分组" clearable class="form-control">
            <el-option v-for="g in groups" :key="g.code" :label="g.title" :value="g.code" />
          </el-select>
        </el-form-item>
        <el-form-item :label="form.isGroup ? '分组名称' : '显示名称'">
          <el-input
            v-model="form.title"
            :placeholder="`默认：${form.defaultTitle}`"
            maxlength="64"
            class="form-control"
          />
        </el-form-item>
        <el-form-item v-if="!form.isGroup" label="图标">
          <el-select
            v-model="form.icon"
            :placeholder="`默认：${form.defaultIcon}`"
            clearable
            filterable
            class="form-control"
          >
            <el-option v-for="ic in ICON_OPTIONS" :key="ic" :label="ic" :value="ic">
              <span class="icon-option">
                <el-icon><component :is="ic" /></el-icon>
                <span>{{ ic }}</span>
              </span>
            </el-option>
          </el-select>
        </el-form-item>
        <el-form-item label="排序">
          <el-input-number
            v-model="form.sortOrder"
            :min="0"
            :max="999"
            controls-position="right"
            placeholder="默认"
          />
          <!--
            默认顺序放进说明文字，不放进输入框占位符：
            占位符是灰色数字（如「2」），看起来跟真实值一模一样，
            容易让人以为已经设了 2，实际保存时又被当成「未修改」。
          -->
          <span class="inline-hint">默认 {{ form.defaultSort }}，同级内由小到大</span>
        </el-form-item>
        <el-form-item v-if="!form.isGroup" label="隐藏">
          <el-switch v-model="form.hidden" />
          <span class="inline-hint">隐藏后侧栏看不到，直接输地址仍可访问</span>
        </el-form-item>
      </el-form>
      <p class="dialog-tip">
        留空即使用默认值（显示为占位符）。点「恢复默认」可以一次清掉这项的所有自定义。
      </p>
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
import { useFillTableRows } from '@/composables/useFillTableRows'

const auth = useAuthStore()

const raw = ref({ items: [] })
const loading = ref(false)
const saving = ref(false)
const editDialog = ref(false)

/** 行高随表体可用高度自适应：数据少时铺满，数据多时内部滚动（页面不出现外层滚动条）。 */
const tableRef = ref(null)

const canWrite = computed(() => auth.can('system:write'))

/** 合并默认值后的目录树（分组 → 菜单项）。 */
const catalog = computed(() => menuCatalog(raw.value))
const tree = computed(() => catalog.value.tree)
const groups = computed(() => catalog.value.groups)

/** 菜单项总数（不含分组），用于卡头计数。 */
const itemCount = computed(() => catalog.value.items.length)

useFillTableRows(tableRef, () => catalog.value.items.length)

/**
 * 行样式钩子。
 *
 * <p>分组行需要单独的背景与字重：改版前分组和菜单项长得几乎一样，
 * 扫一眼分不清哪行是目录、哪行是可点的页面 —— 树形结构反而变成了干扰。
 */
function rowClass({ row }) {
  return row.isGroup ? 'menu-group-row' : ''
}

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
  defaultIcon: '',
  defaultSort: 0
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
    // 与默认值相同的字段留空：留空 = 不覆盖，保存时不会把默认值写成一条覆盖记录
    title: row.title === row.defaultTitle ? '' : row.title,
    icon: row.isGroup ? '' : (row.icon === row.defaultIcon ? '' : row.icon),
    sortOrder: row.sortOrder === row.defaultSort ? null : row.sortOrder,
    hidden: row.hidden,
    defaultTitle: row.defaultTitle,
    defaultIcon: row.defaultIcon || '',
    defaultSort: row.defaultSort
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
/*
 * 卡头收紧。
 *
 * <p>内容区在 1366 笔记本上只有 ~1100px、开必侧栏后更窄，
 * 标题 + 长句说明 + 两个按钮挤一行时，标题会被折成两行、
 * 按钮溢出卡片右边被裁。所以：标题不缩、说明进 tooltip、按钮不换行。
 */
.card-title {
  flex: none;
  white-space: nowrap;
}

.hint-icon {
  color: var(--text-tertiary);
  cursor: help;
}

.header-actions {
  flex: none;
}

/*
 * 分组行（目录）与菜单行的区分。
 *
 * <p>改版前两者只差一个加粗，扫一眼分不清「这行是可点的页面还是不能点的分组」，
 * 树形结构反而成了干扰。这里给分组行浅底色 + 更深的字色，
 * 菜单行保持白底，视线自然按「段」扫。
 */
:deep(.menu-group-row) {
  background: var(--ink-50);
}

:deep(.menu-group-row > td) {
  background: var(--ink-50);
}

/*
 * 树形箭头的排版。
 *
 * <p>Element 把展开箭头和内容都放在 .cell 里，而 .cell 默认是块级流式布局：
 * 我们的内容是个 div，于是箭头独占一行、正文被挤到第二行 ——
 * 每行高 70px，18 个菜单要滚两屏，看着就乱。
 * 把 .cell 改成 flex 后箭头与图标、名称同排，行高回到正常值。
 */
:deep(.col-menu .cell) {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  overflow: visible;
}

.menu-cell {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  flex: 1;
  min-width: 0;
}

.menu-icon {
  color: var(--ink-400);
  flex: none;
  font-size: var(--fs-md);
}

/* 分组图标略深一档，配合加粗标题形成层级 */
.menu-icon.is-group {
  color: var(--ink-600);
  font-size: 15px;
}

.menu-title {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.menu-title.is-group {
  font-weight: 600;
  color: var(--text-primary);
}

.menu-badge {
  flex: none;
}

/* 名称右侧的次要信息：菜单显示路由 name，分组显示成员数 */
.menu-meta {
  flex: none;
  margin-left: auto;
  padding-left: var(--sp-3);
  font-size: var(--fs-xs);
  color: var(--text-tertiary);
  font-variant-numeric: tabular-nums;
}

.default-value {
  font-size: var(--fs-sm);
  color: var(--text-secondary);
}

.icon-option {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
}

.icon-option .el-icon {
  color: var(--ink-500);
}

/* 表单里的行内说明：跟在控件后面，不再各占一行 —— 原来每个字段下面都压一行灰字，很碎 */
.inline-hint {
  margin-left: var(--sp-3);
  font-size: var(--fs-xs);
  color: var(--text-tertiary);
  line-height: 1.5;
}

.form-control {
  width: 100%;
}

.dialog-tip {
  margin: var(--sp-2) 0 0;
  padding: var(--sp-2) var(--sp-3);
  border-radius: var(--r-sm);
  background: var(--ink-50);
  font-size: var(--fs-xs);
  line-height: 1.6;
  color: var(--ink-600);
}

.row-actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 2px;
  flex-wrap: nowrap;
}
</style>
