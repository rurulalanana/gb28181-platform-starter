<template>
  <div>

    <!-- 工具条：操作按钮靠右，与其它页保持一致 -->
    <div class="filter-bar">
      <span class="filter-bar-spacer" />
      <el-button size="small" :loading="loading" @click="load">
        <el-icon><Refresh /></el-icon> 刷新
      </el-button>
      <el-button size="small" type="primary" :disabled="!canWrite" @click="openEdit(null)">
        <el-icon><Plus /></el-icon> 新增用户
      </el-button>
    </div>

    <el-card shadow="never">
      <el-table v-loading="loading" :data="users" stripe table-layout="fixed">
        <el-table-column prop="username" label="用户名" min-width="140" sortable>
          <template #default="{ row }"><span class="mono">{{ row.username }}</span></template>
        </el-table-column>
        <el-table-column label="昵称" width="132">
          <template #default="{ row }">{{ row.nickname || '—' }}</template>
        </el-table-column>
        <el-table-column label="角色" width="150" align="center">
          <template #default="{ row }">
            <div class="role-tags">
              <el-tag
                v-for="r in splitRoles(row.role)"
                :key="r"
                :type="r === 'admin' ? 'primary' : (r === 'operator' ? 'warning' : 'info')"
                size="small"
                effect="light"
              >
                {{ roleName(r) }}
              </el-tag>
              <span v-if="!splitRoles(row.role).length" class="text-muted">—</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90" align="center">
          <template #default="{ row }">
            <el-tag :type="row.enable ? 'success' : 'info'" size="small" effect="light">
              {{ row.enable ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="lastLoginTime" label="最后登录" width="176" sortable>
          <template #default="{ row }">
            <span class="text-muted">{{ row.lastLoginAt || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="创建时间" width="176">
          <template #default="{ row }">
            <span class="text-muted">{{ row.createdAt || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="170" align="center" fixed="right">
          <template #default="{ row }">
            <div class="row-actions">
              <el-tooltip content="编辑">
                <el-button :disabled="!canWrite" @click="openEdit(row)"><el-icon><Edit /></el-icon></el-button>
              </el-tooltip>
              <el-tooltip content="重置密码">
                <el-button :disabled="!canWrite" @click="openReset(row)"><el-icon><Key /></el-icon></el-button>
              </el-tooltip>
              <el-tooltip :content="row.enable ? '停用' : '启用'">
                <el-button :disabled="!canWrite" @click="toggleEnable(row)"><el-icon><SwitchButton /></el-icon></el-button>
              </el-tooltip>
              <el-tooltip content="删除">
                <el-button
                  class="is-danger"
                  :disabled="!canWrite || row.username === 'admin'"
                  @click="removeOne(row)"
                >
                  <el-icon><Delete /></el-icon>
                </el-button>
              </el-tooltip>
            </div>
          </template>
        </el-table-column>
        <template #empty>
          <div class="empty-cell">暂无用户</div>
        </template>
      </el-table>

      <TablePager
        :pager="pager"
        unit="个用户"
        @page-change="onPageChange"
        @size-change="onSizeChange"
      />
    </el-card>

    <!-- 编辑用户 -->
    <el-dialog v-model="editDialog" :title="form.id ? '编辑用户' : '新增用户'" width="480px">
      <el-form :model="form" label-width="90px">
        <el-form-item label="用户名">
          <el-input v-model="form.username" :disabled="!!form.id" />
        </el-form-item>
        <el-form-item v-if="!form.id" label="密码">
          <el-input v-model="form.password" type="password" show-password />
        </el-form-item>
        <el-form-item label="昵称">
          <el-input v-model="form.nickname" />
        </el-form-item>
        <el-form-item label="角色">
          <el-select v-model="form.role" placeholder="选择角色">
            <el-option
              v-for="r in roleOptions"
              :key="r.code"
              :label="`${r.name}（${r.code}）`"
              :value="r.code"
              :disabled="r.enable === false"
            />
          </el-select>
          <div class="role-hint">
            {{ roleHint }}
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>

    <!-- 重置密码 -->
    <el-dialog v-model="resetDialog" :title="'重置密码 · ' + (resetTarget?.username || '')" width="440px">
      <el-input v-model="newPassword" placeholder="新密码" type="password" show-password />
      <template #footer>
        <el-button @click="resetDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="doReset">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { confirmAction } from '@/utils/confirm'
import { roleApi, userApi } from '@/api'
import { useAuthStore } from '@/stores/auth'
import { usePagination } from '@/composables/usePagination'
import TablePager from '@/components/TablePager.vue'

const auth = useAuthStore()

const users = ref([])
const roles = ref([])
const loading = ref(false)
const saving = ref(false)

const canWrite = computed(() => auth.can('user:write'))

/** 可选角色（停用的不出现在下拉里，因为选了也不会生效）。 */
const roleOptions = computed(() => roles.value.filter((r) => r.enable !== false))

/** 角色编码 → 中文名，找不到时退回编码本身（自定义角色被删后的残留值）。 */
const roleNameMap = computed(() => {
  const map = new Map()
  roles.value.forEach((r) => map.set(r.code, r.name))
  return map
})

function roleName(code) {
  return roleNameMap.value.get(code) || code
}

/** 用户表里存的是逗号分隔的角色编码。 */
function splitRoles(raw) {
  return String(raw || '').split(',').map((s) => s.trim()).filter(Boolean)
}

const editDialog = ref(false)
const form = reactive({ id: null, username: '', password: '', nickname: '', role: 'user' })

/** 表单里的角色提示：直接复用角色自己的说明，避免界面与配置脱节。 */
const roleHint = computed(() => {
  const r = roles.value.find((x) => x.code === form.role)
  if (!r) return '从「角色权限」页可自定义每个角色能做什么。'
  const count = r.superRole ? '全部' : `${r.permissions?.length || 0} 项`
  return `${r.description || r.name}；拥有 ${count} 项权限。可在「角色权限」页调整。`
})

const resetDialog = ref(false)
const resetTarget = ref(null)
const newPassword = ref('')

const { pager, offset, setTotal, onPageChange, onSizeChange } = usePagination({
  pageSize: 10,
  load: () => load()
})

async function loadRoles() {
  try {
    const data = await roleApi.list()
    roles.value = data?.roleList || []
  } catch (e) {
    // 拉不到角色时页面仍可浏览用户，只是新增/编辑里的下拉为空
    roles.value = []
  }
}

async function load() {
  loading.value = true
  try {
    const data = await userApi.list({ start: offset.value, limit: pager.size })
    users.value = data?.userList || []
    setTotal(data?.userCount ?? users.value.length)
  } catch (e) {
    ElMessage.error(e.message || '加载用户失败（需 user:read 权限）')
  } finally {
    loading.value = false
  }
}

function openEdit(row) {
  Object.assign(form, {
    id: row?.id || null,
    username: row?.username || '',
    password: '',
    nickname: row?.nickname || '',
    role: row?.role || 'user'
  })
  editDialog.value = true
}

async function save() {
  if (!form.username) {
    ElMessage.warning('请填写用户名')
    return
  }
  saving.value = true
  try {
    const params = { username: form.username, nickname: form.nickname, role: form.role }
    if (form.id) params.id = form.id
    if (form.password) params.password = form.password
    await userApi.save(params)
    ElMessage.success('已保存')
    editDialog.value = false
    load()
  } catch (e) {
    ElMessage.error(e.message || '保存失败')
  } finally {
    saving.value = false
  }
}

function openReset(row) {
  resetTarget.value = row
  newPassword.value = ''
  resetDialog.value = true
}

async function doReset() {
  if (!newPassword.value) {
    ElMessage.warning('请输入新密码')
    return
  }
  saving.value = true
  try {
    await userApi.resetPassword({ id: resetTarget.value.id, password: newPassword.value })
    ElMessage.success('密码已重置')
    resetDialog.value = false
  } catch (e) {
    ElMessage.error(e.message || '重置失败')
  } finally {
    saving.value = false
  }
}

async function toggleEnable(row) {
  try {
    await userApi.setEnable({ id: row.id, enable: !row.enable })
    row.enable = !row.enable
    ElMessage.success(row.enable ? '已启用' : '已停用')
  } catch (e) {
    ElMessage.error(e.message || '操作失败')
  }
}

async function removeOne(row) {
  if (!(await confirmAction(`确定删除用户「${row.username}」？`, '删除确认',
    { confirmButtonText: '删除' }))) return
  try {
    await userApi.remove(row.id)
    ElMessage.success('已删除')
    load()
  } catch (e) {
    ElMessage.error(e.message || '删除失败')
  }
}

onMounted(() => {
  load()
  loadRoles()
})
</script>

<style scoped>
/*
 * 角色说明文字。
 *
 * <p>必须写在界面上而不是只放 tooltip：角色是安全配置，
 * 选错一个选项就等于开了一个越权口子。让运维在选择时直接看到
 * 「这个角色能做什么、不能做什么」，比事后发现权限不对要便宜得多。
 */
.role-hint {
  margin-top: var(--sp-1);
  font-size: var(--fs-xs);
  line-height: 1.6;
  color: var(--ink-600);
  white-space: normal;
}

/* 操作列：一排图标按钮，不换行 */
.row-actions {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 2px;
  flex-wrap: nowrap;
}

/* 角色列：多角色时自动换行，避免撑破单元格 */
.role-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  justify-content: center;
}

/* .card-header 由 styles/main.css 统一提供 */
</style>
