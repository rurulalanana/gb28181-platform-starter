<template>
  <div class="page">
    <!-- 页头（动作区；页面名在页签条上，与参考站一致） -->
    <header class="page-header">
      <div class="page-title-group">
        <h2 class="page-title">总览</h2>
        <p class="page-subtitle">平台运行状态与关键资源读数，数据每 10 秒自动刷新</p>
      </div>
      <div class="page-actions">
        <span v-if="lastSync" class="text-dim">最近同步 {{ lastSync }}</span>
        <el-button :loading="loading" @click="load">
          <el-icon><Refresh /></el-icon> 刷新
        </el-button>
        <el-button type="primary" @click="$router.push('/live')">
          <el-icon><VideoCamera /></el-icon> 实时预览
        </el-button>
      </div>
    </header>

    <!-- 第一屏：三栏 box（标题居中，与参考站「通道信息 / 负载信息 / …」同构） -->
    <section class="box-grid">
      <el-card shadow="never" class="box-centered">
        <template #header>
          <span class="card-title">通道信息</span>
        </template>
        <div class="gauge-row">
          <div v-for="g in gauges" :key="g.label" class="gauge">
            <div class="gauge-ring">
              <svg viewBox="0 0 108 108">
                <circle class="track" cx="54" cy="54" r="46" />
                <circle
                  class="fill"
                  :class="{ 'is-low': g.pct > 0 && g.pct < 25, 'is-critical': g.pct === 0 }"
                  cx="54"
                  cy="54"
                  r="46"
                  :stroke-dasharray="g.circ"
                  :stroke-dashoffset="g.circ * (1 - g.pct / 100)"
                />
              </svg>
              <div class="gauge-value">{{ g.pct }}%</div>
            </div>
            <div class="gauge-caption">
              在线数：<b>{{ g.online }}</b><br />
              总{{ g.label }}：<b>{{ g.total }}</b>
            </div>
          </div>
        </div>
      </el-card>

      <el-card shadow="never" class="box-centered">
        <template #header>
          <span class="card-title">资源概览</span>
        </template>
        <div class="mini-bars">
          <div v-for="b in bars" :key="b.label" class="mini-bar">
            <span class="bar-val">{{ b.value }}</span>
            <div
              class="bar"
              :class="{ 'is-zero': !b.value }"
              :style="{ height: barHeight(b.value) }"
            />
            <span class="bar-label">{{ b.label }}</span>
          </div>
        </div>
      </el-card>

      <el-card shadow="never" class="box-centered">
        <template #header>
          <span class="card-title">服务器信息</span>
        </template>
        <dl class="kv">
          <div v-for="row in serverRows" :key="row.label" class="kv-row">
            <dt>{{ row.label }}</dt>
            <dd class="mono ellipsis" :title="row.value">{{ row.value }}</dd>
          </div>
        </dl>
      </el-card>
    </section>

    <!-- 设备状态 -->
    <div class="split">
      <el-card shadow="never" class="col-main box-centered">
        <template #header>
          <div class="card-header">
            <span class="card-title">设备状态</span>
            <el-button link type="primary" @click="$router.push('/devices')">
              全部设备 <el-icon><ArrowRight /></el-icon>
            </el-button>
          </div>
        </template>
        <el-table :data="devices" size="small" :show-overflow-tooltip="true" max-height="360">
          <el-table-column label="设备编码" min-width="180">
            <template #default="{ row }"><span class="mono">{{ row.id }}</span></template>
          </el-table-column>
          <el-table-column label="名称" min-width="120">
            <template #default="{ row }">
              <span class="ellipsis">{{ row.customName || row.name || '—' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="100" align="center">
            <template #default="{ row }">
              <span class="badge" :class="row.online ? 'is-online' : 'is-offline'">
                {{ row.online ? '在线' : '离线' }}
              </span>
            </template>
          </el-table-column>
          <template #empty>
            <el-empty description="暂无设备" :image-size="72" />
          </template>
        </el-table>
      </el-card>

    </div>

    <!-- 进行中的直播流 -->
    <el-card shadow="never">
      <template #header>
        <div class="card-header">
          <span class="card-title">
            进行中的直播流
            <span v-if="streams.length" class="count-pill">{{ streams.length }}</span>
          </span>
          <el-button link type="primary" @click="$router.push('/live')">
            前往预览 <el-icon><ArrowRight /></el-icon>
          </el-button>
        </div>
      </template>
      <el-table :data="streams" size="small" :show-overflow-tooltip="true">
        <el-table-column label="通道" min-width="240">
          <template #default="{ row }">
            <div class="mono">{{ row.channelId }}</div>
            <div class="text-dim text-xs">{{ row.channelName || '—' }}</div>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="110" align="center">
          <template #default="{ row }">
            <span class="badge" :class="row.state === 'ONLINE' ? 'is-online' : 'is-warning'">
              {{ row.state === 'ONLINE' ? '在线' : row.state }}
            </span>
          </template>
        </el-table-column>
        <el-table-column prop="transport" label="传输" width="100" align="center" />
        <el-table-column prop="rtpPort" label="收流端口" width="110" align="center" />
        <el-table-column label="SSRC" width="130">
          <template #default="{ row }"><span class="mono">{{ row.ssrc || '—' }}</span></template>
        </el-table-column>
        <template #empty>
          <el-empty description="当前无直播流" :image-size="72" />
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
const lastSync = ref('')
let timer = null

function fmtTime(d) {
  return d.toLocaleTimeString('zh-CN', { hour12: false })
}

function rate(online, total) {
  return total > 0 ? Math.round((online / total) * 100) : 0
}

const deviceRate = computed(() => rate(stats.value?.deviceOnline ?? 0, stats.value?.deviceTotal ?? 0))
const channelRate = computed(() => rate(stats.value?.channelOnline ?? 0, stats.value?.channelTotal ?? 0))

const deviceRateText = computed(() => `${deviceRate.value}%`)
const channelRateText = computed(() => `${channelRate.value}%`)

/** 在线率配色：正常（>=50%）用强调色，偏低转黄、极低转红 */
function rateColor(pct) {
  if (pct >= 50) return 'var(--accent)'
  if (pct >= 20) return 'var(--warning-500)'
  return 'var(--danger-500)'
}

const gauges = computed(() => {
  const s0 = stats.value || {}
  const circ = Math.round(2 * Math.PI * 46)
  return [
    {
      label: '设备',
      pct: deviceRate.value,
      online: s0.deviceOnline ?? 0,
      total: s0.deviceTotal ?? 0,
      circ
    },
    {
      label: '通道',
      pct: channelRate.value,
      online: s0.channelOnline ?? 0,
      total: s0.channelTotal ?? 0,
      circ
    }
  ]
})

/** 资源概览柱：四个关键计数，柱高按最大值归一 */
const bars = computed(() => {
  const s0 = stats.value || {}
  return [
    { label: '设备总数', value: s0.deviceTotal ?? 0 },
    { label: '在线设备', value: s0.deviceOnline ?? 0 },
    { label: '通道总数', value: s0.channelTotal ?? 0 },
    { label: '在线通道', value: s0.channelOnline ?? 0 }
  ]
})

const barMax = computed(() => Math.max(1, ...bars.value.map((b) => b.value)))

function barHeight(v) {
  return `${Math.max(2, Math.round((v / barMax.value) * 100))}%`
}

const serverRows = computed(() => {
  const info = auth.serverInfo || {}
  return [
    { label: '平台编码', value: info.platformId || '—' },
    { label: 'SIP 监听', value: info.sip || '—' },
    { label: '媒体 HTTP', value: info.mediaHttp || '—' },
    { label: 'WebRTC 端口', value: info.mediaWebRtc || '—' },
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
    lastSync.value = fmtTime(new Date())
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
/* 第一屏三栏 box：宽屏三列，窄屏降为两列/单列 */
.box-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr) minmax(0, 1fr);
  gap: 10px;
  align-items: stretch;
}

.box-grid > .el-card {
  min-width: 0;
}

/* 设备状态：占满整行（参考站的列表 box 也是整行） */
.split {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  gap: 10px;
}

.col-main {
  min-width: 0;
}

@media (max-width: 1280px) {
  .box-grid {
    grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  }
}

@media (max-width: 900px) {
  .box-grid {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
