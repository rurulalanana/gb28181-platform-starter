<template>
  <div>
    <el-card shadow="never">
      <template #header>
        <div class="card-header">
          <span><el-icon><Monitor /></el-icon> 设备管理</span>
          <el-button size="small" :loading="loading" @click="load">
            <el-icon><Refresh /></el-icon> 刷新
          </el-button>
        </div>
      </template>

      <!-- 查询条件 -->
      <el-form :inline="true" class="search-form">
        <el-form-item label="搜索">
          <el-input
            v-model="query.q"
            placeholder="设备编码 / 名称"
            clearable
            style="width: 220px"
            @keyup.enter="load"
          >
            <template #prefix><el-icon><Search /></el-icon></template>
          </el-input>
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="query.online" placeholder="全部" clearable style="width: 120px">
            <el-option label="在线" :value="true" />
            <el-option label="离线" :value="false" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="load">查询</el-button>
        </el-form-item>
      </el-form>

      <el-table v-loading="loading" :data="devices" stripe>
        <el-table-column prop="id" label="设备编码" min-width="190">
          <template #default="{ row }"><span class="mono">{{ row.id }}</span></template>
        </el-table-column>
        <el-table-column label="名称" min-width="120">
          <template #default="{ row }">{{ row.customName || row.name || '—' }}</template>
        </el-table-column>
        <el-table-column label="厂商 / 型号" min-width="160">
          <template #default="{ row }">
            <span class="muted">{{ [row.manufacturer, row.model].filter(Boolean).join(' / ') || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90" align="center">
          <template #default="{ row }">
            <el-tag :type="row.online ? 'success' : 'info'" size="small" effect="light">
              {{ row.online ? '在线' : '离线' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="IP 地址" min-width="140">
          <template #default="{ row }"><span class="mono">{{ row.remoteIp || '—' }}</span></template>
        </el-table-column>
        <el-table-column label="通道数" width="80" align="center">
          <template #default="{ row }">{{ row.channelCount ?? 0 }}</template>
        </el-table-column>
        <el-table-column label="最后注册" min-width="160">
          <template #default="{ row }">
            <span class="muted">{{ row.lastRegisterAt || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="160" fixed="right" align="center">
          <template #default="{ row }">
            <el-tooltip content="采集全量数据">
              <el-button
                link
                type="primary"
                :loading="busy === row.id"
                @click="collect(row)"
              >
                <el-icon><Download /></el-icon>
              </el-button>
            </el-tooltip>
            <el-tooltip content="重命名">
              <el-button link type="primary" @click="openRename(row)">
                <el-icon><Edit /></el-icon>
              </el-button>
            </el-tooltip>
            <el-tooltip content="删除设备">
              <el-button link type="danger" @click="remove(row)">
                <el-icon><Delete /></el-icon>
              </el-button>
            </el-tooltip>
          </template>
        </el-table-column>
        <template #empty>
          <div class="empty-cell">
            暂无设备。请确认设备已注册到平台（SIP {{ serverSip }}）
          </div>
        </template>
      </el-table>

      <div class="table-footer">共 {{ total }} 台设备</div>
    </el-card>

    <!-- 重命名对话框 -->
    <el-dialog v-model="renameDialog" title="重命名设备" width="440px">
      <div class="mono mb-3">{{ renameTarget?.id }}</div>
      <el-input v-model="renameValue" placeholder="自定义名称" />
      <template #footer>
        <el-button @click="renameDialog = false">取消</el-button>
        <el-button type="primary" :loading="busy === 'rename'" @click="doRename">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { deviceApi } from '@/api'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()

const devices = ref([])
const total = ref(0)
const loading = ref(false)
const busy = ref('')
const query = reactive({ q: '', online: null })

const renameDialog = ref(false)
const renameTarget = ref(null)
const renameValue = ref('')

const serverSip = computed(() => auth.serverInfo?.sip || '—')

async function load() {
  loading.value = true
  try {
    const params = { start: 0, limit: 500 }
    if (query.q) params.q = query.q
    if (query.online !== null && query.online !== undefined) params.online = query.online
    const data = await deviceApi.list(params)
    devices.value = data?.deviceList || []
    total.value = data?.deviceCount ?? devices.value.length
  } catch (e) {
    ElMessage.error(e.message || '加载设备失败')
  } finally {
    loading.value = false
  }
}

async function collect(row) {
  busy.value = row.id
  try {
    await deviceApi.collect(row.id)
    ElMessage.success(`已下发采集：${row.id}`)
    setTimeout(load, 2000)
  } catch (e) {
    ElMessage.error(e.message || '采集失败')
  } finally {
    busy.value = ''
  }
}

function openRename(row) {
  renameTarget.value = row
  renameValue.value = row.customName || row.name || ''
  renameDialog.value = true
}

async function doRename() {
  busy.value = 'rename'
  try {
    await deviceApi.setName({ serial: renameTarget.value.id, custom_name: renameValue.value })
    ElMessage.success('已保存')
    renameDialog.value = false
    load()
  } catch (e) {
    ElMessage.error(e.message || '保存失败')
  } finally {
    busy.value = ''
  }
}

async function remove(row) {
  try {
    await ElMessageBox.confirm(
      `确定删除设备 ${row.id}？该操作会同时删除其通道数据。`,
      '删除确认',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
  } catch (e) {
    return
  }
  try {
    await deviceApi.remove(row.id)
    ElMessage.success('已删除')
    load()
  } catch (e) {
    ElMessage.error(e.message || '删除失败')
  }
}

onMounted(load)
</script>

<style scoped>
.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-weight: 600;
}

.card-header span {
  display: flex;
  align-items: center;
  gap: 6px;
}

.search-form {
  margin-bottom: 4px;
}

.table-footer {
  margin-top: 12px;
  color: #909399;
  font-size: 13px;
}

.mb-3 {
  margin-bottom: 12px;
}
</style>
