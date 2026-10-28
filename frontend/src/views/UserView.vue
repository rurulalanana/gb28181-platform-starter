<template>
  <div>

    <!-- 工具条：操作按钮靠右，与其它页保持一致 -->
    <div class="filter-bar">
      <span class="filter-bar-spacer" />
      <el-button size="small" :loading="loading" @click="load">
        <el-icon><Refresh /></el-icon> 刷新
      </el-button>
      <el-button size="small" type="primary" @click="openEdit(null)">
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
        <el-table-column label="角色" width="110" align="center">
          <template #default="{ row }">
            <el-tag :type="row.role === 'admin' ? 'primary' : (row.role === 'operator' ? 'warning' : 'info')" size="small" effect="light">
              {{ row.role === 'admin' ? '管理员' : (row.role === 'operator' ? '操作员' : '只读用户') }}
            </el-tag>
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
                <el-button @click="openEdit(row)"><el-icon><Edit /></el-icon></el-button>
              </el-tooltip>
              <el-tooltip content="重置密码">
                <el-button @click="openReset(row)"><el-icon><Key /></el-icon></el-button>
              </el-tooltip>
              <el-tooltip :content="row.enable ? '停用' : '启用'">
                <el-button @click="toggleEnable(row)"><el-icon><SwitchButton /></el-icon></el-button>
              </el-tooltip>
              <el-tooltip content="删除">
                <el-button
                  class="is-danger"
                  :disabled="row.username === 'admin'"
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
          <el-select v-model="form.role">
            <el-option label="管理员（含系统配置与用户管理）" value="admin" />
            <el-option label="操作员（设备控制与日常运维）" value="operator" />
            <el-option label="只读用户（只能查看与播放）" value="user" />
          </el-select>
          <div class="role-hint">
            只读用户不能执行任何修改操作；操作员可控制设备与录像，但不能改系统配置、管理用户或调整安全设置。
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
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { confirmAction } from '@/utils/confirm'
import { userApi } from '@/api'
import { usePagination } from '@/composables/usePagination'
import TablePager from '@/components/TablePager.vue'

const users = ref([])
const loading = ref(false)
const saving = ref(false)

const editDialog = ref(false)
const form = reactive({ id: null, username: '', password: '', nickname: '', role: 'user' })

const resetDialog = ref(false)
const resetTarget = ref(null)
const newPassword = ref('')

const { pager, offset, setTotal, onPageChange, onSizeChange } = usePagination({
  pageSize: 10,
  load: () => load()
})

async function load() {
  loading.value = true
  try {
    const data = await userApi.list({ start: offset.value, limit: pager.size })
    users.value = data?.userList || []
    setTotal(data?.userCount ?? users.value.length)
  } catch (e) {
    ElMessage.error(e.message || '加载用户失败（需管理员权限）')
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

onMounted(load)
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

/* .card-header 由 styles/main.css 统一提供 */
</style>
