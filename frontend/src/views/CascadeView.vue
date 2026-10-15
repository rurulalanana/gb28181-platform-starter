<template>
  <div class="page">
    <header class="page-header">
      <div class="page-title-group">
        <h2 class="page-title">级联配置</h2>
        <p class="page-subtitle">上下级平台对接与目录共享，共 {{ list.length }} 个级联节点</p>
      </div>
      <div class="page-actions">
        <el-button size="small" :loading="loading" @click="load">
          <el-icon><Refresh /></el-icon> 刷新
        </el-button>
        <el-button size="small" type="primary" @click="openEdit(null)">
          <el-icon><Plus /></el-icon> 新增级联
        </el-button>
      </div>
    </header>

    <el-card shadow="never">

      <el-table v-loading="loading" :data="list" stripe>
        <el-table-column label="名称" min-width="120">
          <template #default="{ row }">{{ row.name || '—' }}</template>
        </el-table-column>
        <el-table-column label="平台编码" min-width="190">
          <template #default="{ row }"><span class="mono">{{ row.serial || '—' }}</span></template>
        </el-table-column>
        <el-table-column label="对端地址" min-width="160">
          <template #default="{ row }">
            <span class="mono">{{ row.host ? row.host + ':' + row.port : '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="本地地址" min-width="160">
          <template #default="{ row }">
            <span class="mono">{{ row.localHost ? row.localHost + ':' + row.localPort : '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="启用" width="80" align="center">
          <template #default="{ row }">
            <el-switch
              :model-value="!!row.enable"
              size="small"
              @change="(v) => toggleEnable(row, v)"
            />
          </template>
        </el-table-column>
        <el-table-column label="在线" width="80" align="center">
          <template #default="{ row }">
            <span class="badge" :class="row.online ? 'is-online' : 'is-offline'">
              {{ row.online ? '在线' : '离线' }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="共享全部通道" width="120" align="center">
          <template #default="{ row }">
            <el-icon :color="row.shareAllChannel ? '#67c23a' : '#c0c4cc'" :size="18">
              <CircleCheckFilled v-if="row.shareAllChannel" />
              <CircleCloseFilled v-else />
            </el-icon>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="150" align="center" fixed="right">
          <template #default="{ row }">
            <el-tooltip content="推送目录">
              <el-button link type="primary" @click="pushCatalog(row)">
                <el-icon><Refresh /></el-icon>
              </el-button>
            </el-tooltip>
            <el-tooltip content="编辑">
              <el-button link type="primary" @click="openEdit(row)">
                <el-icon><Edit /></el-icon>
              </el-button>
            </el-tooltip>
            <el-tooltip content="删除">
              <el-button link type="danger" @click="removeOne(row)">
                <el-icon><Delete /></el-icon>
              </el-button>
            </el-tooltip>
          </template>
        </el-table-column>
        <template #empty>
          <div class="empty-cell">
            暂未配置级联。级联用于与本平台之外的上下级 GB28181 平台互联
          </div>
        </template>
      </el-table>

      <TablePager
        :pager="pager"
        unit="条级联"
        @page-change="onPageChange"
        @size-change="onSizeChange"
      />
    </el-card>

    <!-- 编辑对话框 -->
    <el-dialog v-model="editDialog" :title="form.ID ? '编辑级联' : '新增级联'" width="700px">
      <el-form :model="form" label-width="130px">
        <el-row :gutter="16">
          <el-col :span="12">
            <el-form-item label="名称">
              <el-input v-model="form.Name" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="上级平台编码">
              <el-input v-model="form.Serial" class="mono" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="SIP 域">
              <el-input v-model="form.Realm" class="mono" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="上级平台地址">
              <el-input v-model="form.Host" class="mono" />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="上级端口">
              <el-input-number v-model="form.Port" :min="1" :max="65535" controls-position="right" />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="本地地址">
              <el-input v-model="form.LocalHost" class="mono" />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="本地端口">
              <el-input-number
                v-model="form.LocalPort"
                :min="1"
                :max="65535"
                controls-position="right"
              />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="传输协议">
              <el-select v-model="form.CommandTransport">
                <el-option label="UDP" value="UDP" />
                <el-option label="TCP" value="TCP" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="用户名（可选）">
              <el-input v-model="form.Username" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="密码（可选）">
              <el-input v-model="form.Password" type="password" show-password />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="启用">
              <el-switch v-model="form.Enable" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="共享全部通道">
              <el-switch v-model="form.ShareAllChannel" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="允许控制">
              <el-switch v-model="form.AllowControl" />
            </el-form-item>
          </el-col>
        </el-row>
      </el-form>
      <template #footer>
        <el-button @click="editDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { cascadeApi } from '@/api'
import { usePagination } from '@/composables/usePagination'
import TablePager from '@/components/TablePager.vue'

const list = ref([])
const loading = ref(false)
const saving = ref(false)
const editDialog = ref(false)

const emptyForm = () => ({
  ID: null,
  Name: '',
  Serial: '',
  Realm: '',
  Host: '',
  Port: 5060,
  LocalHost: '',
  LocalPort: 5060,
  Username: '',
  Password: '',
  CommandTransport: 'UDP',
  Enable: true,
  ShareAllChannel: false,
  AllowControl: true
})

const form = reactive(emptyForm())

const { pager, offset, setTotal, onPageChange, onSizeChange } = usePagination({
  pageSize: 20,
  load: () => load()
})

async function load() {
  loading.value = true
  try {
    const data = await cascadeApi.list({ start: offset.value, limit: pager.size })
    // 后端 CascadeListVo 的字段名拼成了 casecadeList / casecadeCount（少了 s 后多打 a）。
    // 这里两种拼写都读，避免后端日后修正拼写时前端静默变成空列表。
    const rows = data?.casecadeList || data?.cascadeList || []
    list.value = rows
    setTotal(data?.casecadeCount ?? data?.cascadeCount ?? rows.length)
  } catch (e) {
    ElMessage.error(e.message || '加载级联失败')
  } finally {
    loading.value = false
  }
}

function openEdit(row) {
  Object.assign(form, emptyForm())
  if (row) {
    Object.assign(form, {
      ID: row.id,
      Name: row.name,
      Serial: row.serial,
      Realm: row.realm,
      Host: row.host,
      Port: row.port,
      LocalHost: row.localHost,
      LocalPort: row.localPort,
      Username: row.username,
      CommandTransport: row.commandTransport || 'UDP',
      Enable: !!row.enable,
      ShareAllChannel: !!row.shareAllChannel,
      AllowControl: row.allowControl !== false
    })
  }
  editDialog.value = true
}

async function save() {
  if (!form.Serial || !form.Host) {
    ElMessage.warning('请填写上级平台编码与地址')
    return
  }
  saving.value = true
  try {
    await cascadeApi.save({ ...form })
    ElMessage.success('已保存')
    editDialog.value = false
    load()
  } catch (e) {
    ElMessage.error(e.message || '保存失败')
  } finally {
    saving.value = false
  }
}

async function toggleEnable(row, value) {
  try {
    await cascadeApi.setEnable({ id: row.id, enable: value })
    row.enable = value
    ElMessage.success(value ? '已启用' : '已停用')
  } catch (e) {
    ElMessage.error(e.message || '操作失败')
  }
}

async function pushCatalog(row) {
  try {
    await cascadeApi.pushCatalog({ id: row.id })
    ElMessage.success('已推送目录')
  } catch (e) {
    ElMessage.error(e.message || '推送失败')
  }
}

async function removeOne(row) {
  try {
    await ElMessageBox.confirm(`确定删除级联「${row.name || row.serial}」？`, '删除确认', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消'
    })
  } catch (e) {
    return
  }
  try {
    await cascadeApi.remove(row.id)
    ElMessage.success('已删除')
    load()
  } catch (e) {
    ElMessage.error(e.message || '删除失败')
  }
}

onMounted(load)
</script>

<style scoped>
/* .card-header 由 styles/main.css 统一提供 */
</style>
