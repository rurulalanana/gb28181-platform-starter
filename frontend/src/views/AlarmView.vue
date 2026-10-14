<template>
  <div>
    <el-card shadow="never">
      <template #header>
        <div class="card-header">
          <span><el-icon><Bell /></el-icon> 报警管理</span>
          <div>
            <el-button size="small" :loading="loading" @click="load">
              <el-icon><Refresh /></el-icon> 刷新
            </el-button>
            <el-button size="small" type="danger" plain @click="clearAll">
              <el-icon><Delete /></el-icon> 清空报警
            </el-button>
          </div>
        </div>
      </template>

      <el-form :inline="true" class="search-form">
        <el-form-item label="报警级别">
          <el-select v-model="query.priority" placeholder="全部级别" clearable style="width: 160px">
            <el-option label="一级（紧急）" value="1" />
            <el-option label="二级（重要）" value="2" />
            <el-option label="三级（一般）" value="3" />
            <el-option label="四级（提示）" value="4" />
          </el-select>
        </el-form-item>
        <el-form-item label="设备编码">
          <el-input v-model="query.serial" placeholder="设备编码" clearable style="width: 200px" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="load">查询</el-button>
        </el-form-item>
      </el-form>

      <el-table v-loading="loading" :data="alarms" stripe>
        <el-table-column label="时间" min-width="170">
          <template #default="{ row }"><span class="mono">{{ row.time || '—' }}</span></template>
        </el-table-column>
        <el-table-column label="级别" width="100" align="center">
          <template #default="{ row }">
            <el-tag :type="priorityColor(row.alarmPriority)" size="small" effect="light">
              {{ row.alarmPriorityName || priorityText(row.alarmPriority) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="设备" min-width="190">
          <template #default="{ row }"><span class="mono">{{ row.deviceId || '—' }}</span></template>
        </el-table-column>
        <el-table-column label="通道" min-width="190">
          <template #default="{ row }"><span class="mono">{{ row.channelId || '—' }}</span></template>
        </el-table-column>
        <el-table-column label="报警方式" min-width="110">
          <template #default="{ row }">{{ row.alarmMethodName || '—' }}</template>
        </el-table-column>
        <el-table-column label="描述" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">{{ row.alarmDescription || '—' }}</template>
        </el-table-column>
        <el-table-column label="操作" width="80" align="center" fixed="right">
          <template #default="{ row }">
            <el-button link type="danger" @click="removeOne(row)">
              <el-icon><Delete /></el-icon>
            </el-button>
          </template>
        </el-table-column>
        <template #empty>
          <div class="empty-cell">暂无报警记录</div>
        </template>
      </el-table>

      <div class="table-footer">共 {{ total }} 条报警</div>
    </el-card>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { alarmApi } from '@/api'

const alarms = ref([])
const total = ref(0)
const loading = ref(false)
const query = reactive({ priority: null, serial: '' })

const PRIORITY_TEXT = { 1: '一级', 2: '二级', 3: '三级', 4: '四级' }
const PRIORITY_COLOR = { 1: 'danger', 2: 'warning', 3: 'primary', 4: 'info' }

function priorityText(p) {
  return PRIORITY_TEXT[p] || '—'
}

function priorityColor(p) {
  return PRIORITY_COLOR[p] || 'info'
}

async function load() {
  loading.value = true
  try {
    const params = { start: 0, limit: 500 }
    if (query.priority) params.priority = query.priority
    if (query.serial) params.serial = query.serial
    const data = await alarmApi.list(params)
    alarms.value = data?.alarmList || []
    total.value = data?.alarmCount ?? alarms.value.length
  } catch (e) {
    ElMessage.error(e.message || '加载报警失败')
  } finally {
    loading.value = false
  }
}

async function removeOne(row) {
  try {
    await alarmApi.remove(row.id)
    ElMessage.success('已删除')
    load()
  } catch (e) {
    ElMessage.error(e.message || '删除失败')
  }
}

async function clearAll() {
  try {
    await ElMessageBox.confirm('确定清空全部报警记录？', '清空确认', {
      type: 'warning',
      confirmButtonText: '清空',
      cancelButtonText: '取消'
    })
  } catch (e) {
    return
  }
  try {
    const data = await alarmApi.clear()
    ElMessage.success(`已清空 ${data?.deleted ?? 0} 条`)
    load()
  } catch (e) {
    ElMessage.error(e.message || '清空失败')
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
</style>
