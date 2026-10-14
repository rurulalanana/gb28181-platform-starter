<template>
  <div class="page">
    <!-- 统计卡片 -->
    <el-row :gutter="18">
      <el-col v-for="card in cards" :key="card.title" :xs="12" :sm="12" :md="6">
        <el-card shadow="hover" class="stat-el-card">
          <div class="stat-card">
            <div class="stat-icon" :style="{ background: card.color }">
              <el-icon :size="26"><component :is="card.icon" /></el-icon>
            </div>
            <div class="stat-body">
              <div class="stat-label">{{ card.title }}</div>
              <div class="stat-value">{{ card.value }}</div>
              <div v-if="card.extra" class="stat-extra">{{ card.extra }}</div>
            </div>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <!-- 快捷入口 -->
    <el-card shadow="never">
      <template #header>
        <div class="card-header">
          <span><el-icon><Grid /></el-icon> 快捷入口</span>
        </div>
      </template>
      <div class="quick-grid">
        <div v-for="q in quickLinks" :key="q.path" class="quick-item" @click="$router.push(q.path)">
          <el-icon :size="22" :color="q.color"><component :is="q.icon" /></el-icon>
          <div class="quick-text">
            <div class="quick-title">{{ q.title }}</div>
            <div class="quick-desc">{{ q.desc }}</div>
          </div>
        </div>
      </div>
    </el-card>

    <el-row :gutter="18">
      <!-- 服务器信息 -->
      <el-col :xs="24" :lg="12">
        <el-card shadow="never" class="full-height">
          <template #header>
            <div class="card-header">
              <span><el-icon><Monitor /></el-icon> 服务器信息</span>
            </div>
          </template>
          <el-descriptions :column="2" border size="small">
            <el-descriptions-item v-for="row in serverRows" :key="row.label" :label="row.label">
              <span class="mono">{{ row.value }}</span>
            </el-descriptions-item>
          </el-descriptions>
        </el-card>
      </el-col>

      <!-- 在线设备 -->
      <el-col :xs="24" :lg="12">
        <el-card shadow="never" class="full-height">
          <template #header>
            <div class="card-header">
              <span><el-icon><Monitor /></el-icon> 设备状态</span>
              <el-button link type="primary" :loading="loading" @click="load">
                <el-icon><Refresh /></el-icon> 刷新
              </el-button>
            </div>
          </template>
          <el-table :data="devices" size="small" height="320" :show-overflow-tooltip="true">
            <el-table-column prop="id" label="设备编码" min-width="180">
              <template #default="{ row }"><span class="mono">{{ row.id }}</span></template>
            </el-table-column>
            <el-table-column label="名称" min-width="110">
              <template #default="{ row }">{{ row.customName || row.name || '—' }}</template>
            </el-table-column>
            <el-table-column label="状态" width="90" align="center">
              <template #default="{ row }">
                <el-tag :type="row.online ? 'success' : 'info'" size="small" effect="light">
                  {{ row.online ? '在线' : '离线' }}
                </el-tag>
              </template>
            </el-table-column>
            <template #empty>
              <div class="empty-cell">暂无设备</div>
            </template>
          </el-table>
        </el-card>
      </el-col>
    </el-row>

    <!-- 直播流 -->
    <el-card shadow="never">
      <template #header>
        <div class="card-header">
          <span><el-icon><Connection /></el-icon> 进行中的直播流</span>
          <el-button link type="primary" @click="$router.push('/live')">
            前往预览 <el-icon><ArrowRight /></el-icon>
          </el-button>
        </div>
      </template>
      <el-table :data="streams" size="small">
        <el-table-column label="通道" min-width="220">
          <template #default="{ row }">
            <div class="mono">{{ row.channelId }}</div>
            <div class="muted">{{ row.channelName || '—' }}</div>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="120" align="center">
          <template #default="{ row }">
            <el-tag
              :type="row.state === 'ONLINE' ? 'success' : 'warning'"
              size="small"
              effect="light"
            >
              {{ row.state }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="transport" label="传输" width="100" />
        <el-table-column prop="rtpPort" label="收流端口" width="120" />
        <el-table-column label="SSRC" width="130">
          <template #default="{ row }"><span class="mono">{{ row.ssrc || '—' }}</span></template>
        </el-table-column>
        <template #empty>
          <div class="empty-cell">当前无直播流</div>
        </template>
      </el-table>
    </el-card>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { deviceApi, streamApi } from '@/api'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()

const stats = ref(null)
const devices = ref([])
const streams = ref([])
const loading = ref(false)
let timer = null

const cards = computed(() => {
  const s = stats.value || {}
  const total = s.deviceTotal ?? 0
  const online = s.deviceOnline ?? 0
  const chTotal = s.channelTotal ?? 0
  const chOnline = s.channelOnline ?? 0
  return [
    {
      title: '设备总数',
      value: total,
      extra: `${online} 台在线`,
      icon: 'Monitor',
      color: '#409eff'
    },
    {
      title: '在线设备',
      value: online,
      extra: total ? `在线率 ${Math.round((online / total) * 100)}%` : '—',
      icon: 'CircleCheckFilled',
      color: '#67c23a'
    },
    {
      title: '通道总数',
      value: chTotal,
      extra: `${chOnline} 路在线`,
      icon: 'List',
      color: '#e6a23c'
    },
    {
      title: '在线通道',
      value: chOnline,
      extra: chTotal ? `在线率 ${Math.round((chOnline / chTotal) * 100)}%` : '—',
      icon: 'VideoCameraFilled',
      color: '#909399'
    }
  ]
})

const quickLinks = [
  { path: '/live', title: '实时预览', desc: '多分屏观看实时画面', icon: 'VideoCamera', color: '#409eff' },
  { path: '/control', title: '设备控制', desc: '云台、预置位、喊话', icon: 'Aim', color: '#67c23a' },
  { path: '/playback', title: '设备录像', desc: '检索并回放设备侧录像', icon: 'Film', color: '#e6a23c' },
  { path: '/cloud', title: '云端录像', desc: '平台录制与下载归档', icon: 'Cloudy', color: '#909399' }
]

const serverRows = computed(() => {
  const info = auth.serverInfo || {}
  return [
    { label: '平台编码', value: info.platformId || '—' },
    { label: 'SIP 监听', value: info.sip || '—' },
    { label: '媒体 HTTP', value: info.mediaHttp ?? '—' },
    { label: 'WebRTC 端口', value: info.mediaWebRtc ?? '—' },
    { label: '启动时间', value: info.startUpTime || '—' },
    { label: '已运行', value: info.runningTime || '—' }
  ]
})

async function load() {
  loading.value = true
  try {
    const [statsData, deviceData, streamData] = await Promise.all([
      deviceApi.onlineStats(),
      deviceApi.list({ start: 0, limit: 100 }),
      streamApi.list({ start: 0, limit: 50 })
    ])
    stats.value = statsData
    devices.value = deviceData?.deviceList || []
    streams.value = streamData?.streams || []
  } catch (e) {
    ElMessage.error(e.message || '加载失败')
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  load()
  if (!auth.serverInfo) {
    auth.loadServerInfo().catch(() => {})
  }
  timer = setInterval(load, 10000)
})

onUnmounted(() => {
  if (timer) clearInterval(timer)
})
</script>

<style scoped>
.stat-el-card {
  margin-bottom: 18px;
}

.stat-el-card :deep(.el-card__body) {
  padding: 18px 20px;
}

.stat-body {
  flex: 1;
  min-width: 0;
}

.stat-extra {
  font-size: 12px;
  color: #a8abb2;
  margin-top: 4px;
}

.full-height {
  height: 100%;
}

.quick-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  gap: 14px;
}

.quick-item {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 16px 18px;
  background: #f8f9fb;
  border: 1px solid #ebeef5;
  border-radius: 8px;
  cursor: pointer;
  transition: all 0.18s;
}

.quick-item:hover {
  background: #ecf5ff;
  border-color: #b3d8ff;
  transform: translateY(-2px);
}

.quick-title {
  font-size: 14px;
  font-weight: 600;
  color: #303133;
}

.quick-desc {
  font-size: 12px;
  color: #909399;
  margin-top: 3px;
}
</style>
