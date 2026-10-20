<template>
  <el-dialog
    v-model="visibleProxy"
    :title="title"
    width="860px"
    top="7vh"
    class="device-detail-dialog"
    destroy-on-close
  >
    <el-tabs v-model="activeTab" @tab-change="onTabChange">
      <el-tab-pane name="status" label="状态记录">
        <el-table v-loading="loading.status" :data="statusRows" size="small" max-height="420">
          <el-table-column prop="createdAt" label="时间" width="180">
            <template #default="{ row }"><span class="mono">{{ row.createdAt }}</span></template>
          </el-table-column>
          <el-table-column label="状态" width="90" align="center">
            <template #default="{ row }">
              <span class="badge" :class="isOnline(row.status) ? 'is-online' : 'is-offline'">
                {{ isOnline(row.status) ? '在线' : '离线' }}
              </span>
            </template>
          </el-table-column>
          <el-table-column prop="description" label="描述" min-width="240" show-overflow-tooltip />
          <template #empty>
            <el-empty description="暂无状态记录" :image-size="64" />
          </template>
        </el-table>
        <div class="dialog-foot-tip">记录保留 {{ statusReserveDays }} 天，由平台定时清理</div>
      </el-tab-pane>

      <el-tab-pane name="traffic" label="流量统计">
        <el-empty v-if="!trafficRows.length" :description="trafficNote || '暂无流量数据'" :image-size="72" />
        <el-table v-else v-loading="loading.traffic" :data="trafficRows" size="small" max-height="400">
          <el-table-column prop="time" label="时间" min-width="170" />
          <el-table-column prop="channelId" label="通道" min-width="180">
            <template #default="{ row }"><span class="mono">{{ row.channelId }}</span></template>
          </el-table-column>
          <el-table-column prop="bytes" label="流量(字节)" width="120" align="right" />
        </el-table>
      </el-tab-pane>

      <el-tab-pane name="position" label="移动轨迹">
        <el-table v-loading="loading.position" :data="positionRows" size="small" max-height="400">
          <el-table-column prop="createdAt" label="时间" width="180">
            <template #default="{ row }"><span class="mono">{{ row.createdAt }}</span></template>
          </el-table-column>
          <el-table-column prop="longitude" label="经度" width="120" align="right" />
          <el-table-column prop="latitude" label="纬度" width="120" align="right" />
          <el-table-column prop="speed" label="速度" width="100" align="right" />
          <el-table-column prop="direction" label="方向" width="100" align="right" />
          <template #empty>
            <el-empty description="暂无位置记录" :image-size="64" />
          </template>
        </el-table>
      </el-tab-pane>

      <el-tab-pane name="sessions" label="会话列表">
        <el-table v-loading="loading.sessions" :data="sessionRows" size="small" max-height="400">
          <el-table-column prop="channelName" label="通道" min-width="150" show-overflow-tooltip />
          <el-table-column prop="channelId" label="通道编码" min-width="180">
            <template #default="{ row }"><span class="mono">{{ row.channelId }}</span></template>
          </el-table-column>
          <el-table-column prop="startAt" label="开始时间" width="180">
            <template #default="{ row }"><span class="mono">{{ row.startAt }}</span></template>
          </el-table-column>
          <el-table-column prop="state" label="状态" width="110" align="center">
            <template #default="{ row }">
              <span class="badge" :class="row.state === 'ONLINE' ? 'is-online' : 'is-offline'">
                {{ row.state }}
              </span>
            </template>
          </el-table-column>
          <el-table-column prop="transport" label="传输" width="80" align="center" />
          <template #empty>
            <el-empty description="该设备当前没有推流会话" :image-size="64" />
          </template>
        </el-table>
      </el-tab-pane>
    </el-tabs>
  </el-dialog>
</template>

<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { deviceApi, streamApi } from '@/api'

const props = defineProps({
  /** 目标设备（整行对象）。为空时弹窗关闭。 */
  device: { type: Object, default: null },
  /** 弹窗显隐（v-model:visible）。 */
  visible: { type: Boolean, default: false }
})

const emit = defineEmits(['update:visible'])

const activeTab = ref('status')
const loading = reactive({ status: false, traffic: false, position: false, sessions: false })

const statusRows = ref([])
const statusReserveDays = ref(30)
const trafficRows = ref([])
const trafficNote = ref('')
const positionRows = ref([])
const sessionRows = ref([])

const title = computed(() => {
  const d = props.device
  if (!d) return '设备详情'
  return `设备(${d.id})`
})

/** 弹窗显隐的可写代理：写回父组件的 v-model:visible。 */
const visibleProxy = computed({
  get: () => props.visible,
  set: (v) => emit('update:visible', v)
})

// 父组件切换目标设备时，重新加载首个 Tab
watch(
  () => props.device,
  (d) => {
    if (d && props.visible) {
      activeTab.value = 'status'
      loadTab('status')
    }
  }
)

// 弹窗每次打开都从「状态记录」开始，避免看到上次的残留 Tab
watch(
  () => props.visible,
  (v) => {
    if (v) {
      activeTab.value = 'status'
      loadTab('status')
    }
  }
)

function onTabChange(name) {
  loadTab(name)
}

/**
 * 按 Tab 懒加载。
 *
 * <p>四个 Tab 分属不同接口，全部预加载会让打开弹窗变慢；
 * 已加载过的 Tab 切换时不重复请求。
 */
function loadTab(name) {
  const d = props.device
  if (!d || !props.visible) return
  const loader = {
    status: loadStatus,
    traffic: loadTraffic,
    position: loadPosition,
    sessions: loadSessions
  }[name]
  if (loader) loader()
}

async function loadStatus() {
  loading.status = true
  try {
    const data = await deviceApi.statusLog({ serial: props.device.id, limit: 200 })
    statusRows.value = data?.logList || []
    statusReserveDays.value = data?.logReserveDays ?? 30
  } catch (e) {
    ElMessage.error(e.message || '加载状态记录失败')
  } finally {
    loading.status = false
  }
}

async function loadTraffic() {
  loading.traffic = true
  try {
    const data = await deviceApi.streamLog({ serial: props.device.id })
    trafficRows.value = data?.list || []
    trafficNote.value = data?.note || ''
  } catch (e) {
    ElMessage.error(e.message || '加载流量统计失败')
  } finally {
    loading.traffic = false
  }
}

async function loadPosition() {
  loading.position = true
  try {
    const data = await deviceApi.positionLog({ serial: props.device.id, limit: 200 })
    positionRows.value = data?.logList || data?.list || []
  } catch (e) {
    ElMessage.error(e.message || '加载移动轨迹失败')
  } finally {
    loading.position = false
  }
}

async function loadSessions() {
  loading.sessions = true
  try {
    const data = await streamApi.list({ serial: props.device.id, start: 0, limit: 100 })
    sessionRows.value = data?.streams || []
  } catch (e) {
    ElMessage.error(e.message || '加载会话列表失败')
  } finally {
    loading.sessions = false
  }
}

function isOnline(status) {
  return String(status || '').toUpperCase() === 'ON'
}
</script>

<style scoped>
:deep(.device-detail-dialog.el-dialog) {
  display: flex;
  flex-direction: column;
  max-height: 86vh;
  margin-bottom: 0;
}

:deep(.device-detail-dialog .el-dialog__body) {
  flex: 1 1 auto;
  min-height: 0;
  overflow-y: auto;
}

.dialog-foot-tip {
  margin-top: var(--sp-2);
  font-size: var(--fs-xs);
  color: var(--text-tertiary);
}
</style>
