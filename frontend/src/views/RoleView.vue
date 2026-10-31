<template>
  <div>

    <!-- 工具条：操作按钮靠右，与其它页保持一致 -->
    <div class="filter-bar">
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
      <el-table v-loading="loading" :data="roles" stripe table-layout="fixed">
        <el-table-column label="角色" min-width="180">
          <template #default="{ row }">
            <div class="role-cell">
              <span class="role-name">{{ row.name }}</span>
              <el-tag v-if="row.superRole" type="primary" size="small" effect="light">超级</el-tag>
              <el-tag v-else-if="row.builtin" type="info" size="small" effect="plain">内置</el-tag>
              <span class="role-code mono">{{ row.code }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="说明" min-width="220">
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
                <el-button :disabled="!canWrite || row.superRole" @click="openEdit(row)">
                  <el-icon><Edit /></el-icon>
                </el-button>
              </el-tooltip>
              <el-tooltip :content="row.enable ? '停用' : '启用'">
                <el-button
                  :disabled="!canWrite || row.superRole"
                  @click="toggleEnable(row)"
                >
                  <el-icon><SwitchButton /></el-icon>
                </el-button>
              </el-tooltip>
              <el-tooltip :content="row.builtin ? '内置角色不可删除' : '删除'">
                <el-button
                  class="is-danger"
                  :disabled="!canWrite || row.builtin"
                  @click="removeOne(row)"
                >
                  <el-icon><Delete /></el-icon>
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
      width="720px"
      top="6vh"
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
        <el-form-item label="权限">
          <div class="perm-toolbar">
            <span class="perm-count">已选 {{ form.permissions.length }} / {{ allPermCodes.length }} 项</span>
            <div>
              <el-button size="small" text @click="selectAll">全选</el-button>
              <el-button size="small" text @click="clearAll">清空</el-button>
              <el-button size="small" text @click="selectReadonly">仅只读</el-button>
            </div>
          </div>
          <div class="perm-groups">
            <div v-for="g in catalog" :key="g.group" class="perm-group">
              <div class="perm-group-title">{{ g.group }}</div>
              <el-checkbox-group v-model="form.permissions">
                <el-checkbox
                  v-for="p in g.permissions"
                  :key="p.code"
                  :value="p.code"
                  class="perm-item"
                >
                  <span class="perm-name">{{ p.name }}</span>
                  <span class="perm-desc">{{ p.description }}</span>
                </el-checkbox>
              </el-checkbox-group>
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
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { confirmAction } from '@/utils/confirm'
import { roleApi } from '@/api'
import { useAuthStore } from '@/stores/auth'

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

const canWrite = computed(() => auth.can('user:write'))

/** 目录里的全部权限点编码，用于「全选」与计数。 */
const allPermCodes = computed(() =>
  catalog.value.flatMap((g) => g.permissions.map((p) => p.code))
)

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
  editDialog.value = true
}

function selectAll() {
  form.permissions = [...allPermCodes.value]
}

function clearAll() {
  form.permissions = []
}

/** 一键只保留「只读」类权限：新增角色时最常用的一档。 */
function selectReadonly() {
  form.permissions = allPermCodes.value.filter((c) => c.endsWith(':read') || c === 'play:live' || c === 'play:record')
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
  justify-content: space-between;
  width: 100%;
  margin-bottom: var(--sp-2);
}

.perm-count {
  font-size: var(--fs-xs);
  color: var(--ink-600);
}

/*
 * 权限点分组区。
 *
 * <p>限高 + 内部滚动：权限点会随功能增加而变多，弹窗不能跟着无限变高，
 * 否则在小屏上「保存」按钮会被顶出视口。
 */
.perm-groups {
  width: 100%;
  max-height: 52vh;
  overflow-y: auto;
  padding-right: var(--sp-2);
}

.perm-group + .perm-group {
  margin-top: var(--sp-3);
}

.perm-group-title {
  font-size: var(--fs-xs);
  font-weight: 600;
  color: var(--ink-700);
  margin-bottom: var(--sp-1);
  padding-bottom: 4px;
  border-bottom: 1px solid var(--line-200);
}

/* 让每个权限点占一行：中文名 + 灰色说明，勾选框与文字顶端对齐 */
.perm-item {
  display: flex;
  align-items: flex-start;
  width: 100%;
  height: auto;
  margin-right: 0;
  padding: 3px 0;
}

.perm-item :deep(.el-checkbox__label) {
  display: flex;
  align-items: baseline;
  gap: var(--sp-2);
  white-space: normal;
  line-height: 1.5;
}

.perm-name {
  font-size: var(--fs-sm);
}

.perm-desc {
  font-size: var(--fs-xs);
  color: var(--ink-500);
}

.row-actions {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 2px;
  flex-wrap: nowrap;
}
</style>
