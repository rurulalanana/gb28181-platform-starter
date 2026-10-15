<template>
  <div class="page">
    <!-- 页头 -->
    <header class="page-header">
      <div class="page-title-group">
        <h2 class="page-title">总览</h2>
        <p class="page-subtitle">平台运行状态与快捷入口，数据每 10 秒自动刷新</p>
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

    <!-- 核心指标 -->
    <div class="stat-grid">
      <article v-for="c in cards" :key="c.label" class="stat-card">
        <div class="stat-icon" :class="c.tone"><el-icon><component :is="c.icon" /></el-icon></div>
        <div class="stat-body">
          <div class="stat-value">{{ c.value }}</div>
          <div class="stat-label">{{ c.label }}</div>
          <div v-if="c.extra" class="stat-extra" :class="c.extraTone">{{ c.extra }}</div>
        </div>
      </article>
    </div>

    <!-- 在线率进度条：把比例可视化，比纯数字更快读懂 -->
    <div class="rate-row">
      <div class="rate-block">
        <div class="rate-head">
          <span class="rate-label">设备在线率</span>
          <span class="rate-value">{{ deviceRateText }}</span>
        </div>
        <el-progress
          :percentage="deviceRate"
          :stroke-width="7"
          :show-text="false"
          :color="rateColor(deviceRate)"
        />
      </div>
      <div class="rate-block">
        <div class="rate-head">
          <span class="rate-label">通道在线率</span>
          <span class="rate-value">{{ channelRateText }}</span>
        </div>
        <el-progress
          :percentage="channelRate"
          :stroke-width="7"
          :show-text="false"
          :color="rateColor(channelRate)"
        />
      </div>
    </div>

    <!-- 快捷入口 -->
    <section class="quick-grid">
      <button v-for="q in quickLinks" :key="q.path" class="quick-card" @click="$router.push(q.path)">
        <div class="quick-icon" :class="q.tone">
          <el-icon><component :is="q.icon" /></el-icon>
        </div>
        <div class="quick-text">
          <div class="quick-title">{{ q.title }}</div>
          <div class="quick-desc">{{ q.desc }}</div>
        </div>
        <el-icon class="quick-arrow"><ArrowRight /></el-icon>
      </button>
    </section>

    <!-- 设备状态 + 服务器信息 -->
    <div class="split">
      <el-card shadow="never" class="col-main">
        <template #header>
          <div class="card-header">
            <span class="card-title"><el-icon><Monitor /></el-icon> 设备状态</span>
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

      <el-card shadow="never" class="col-side">
        <template #header>
          <div class="card-header">
            <span class="card-title"><el-icon><Cpu /></el-icon> 服务器信息</span>
          </div>
        </template>
        <dl class="kv">
          <div v-for="row in serverRows" :key="row.label" class="kv-row">
            <dt>{{ row.label }}</dt>
            <dd class="mono ellipsis" :title="row.value">{{ row.value }}</dd>
          </div>
        </dl>
      </el-card>
    </div>

    <!-- 进行中的直播流 -->
    <el-card shadow="never">
      <template #header>
        <div class="card-header">
          <span class="card-title">
            <el-icon><Connection /></el-icon> 进行中的直播流
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

/** 在线率越高越绿，低于 50% 转黄，低于 20% 转红 */
function rateColor(pct) {
  if (pct >= 80) return 'var(--success-500)'
  if (pct >= 50) return 'var(--warning-500)'
  return 'var(--danger-500)'
}

const cards = computed(() => {
  const s = stats.value || {}
  const total = s.deviceTotal ?? 0
  const online = s.deviceOnline ?? 0
  const chTotal = s.channelTotal ?? 0
  const chOnline = s.channelOnline ?? 0
  return [
    {
      label: '设备总数',
      value: total,
      extra: total ? `在线 ${online} 台` : '尚未接入',
      icon: 'Monitor',
      tone: 'is-brand',
      extraTone: total && online === 0 ? 'text-warning' : 'text-muted'
    },
    {
      label: '在线设备',
      value: online,
      extra: total ? `在线率 ${rate(online, total)}%` : '—',
      icon: 'CircleCheckFilled',
      tone: 'is-success',
      extraTone: 'text-success'
    },
    {
      label: '通道总数',
      value: chTotal,
      extra: chTotal ? `在线 ${chOnline} 路` : '未同步目录',
      icon: 'List',
      tone: 'is-warning',
      extraTone: 'text-muted'
    },
    {
      label: '进行中推流',
      value: streams.value.length,
      extra: streams.value.length ? '正在传输媒体' : '当前空闲',
      icon: 'VideoCameraFilled',
      tone: 'is-info',
      extraTone: streams.value.length ? 'text-brand' : 'text-muted'
    }
  ]
})

const quickLinks = [
  {
    path: '/live',
    title: '实时预览',
    desc: '1~16 分屏，WebSocket-FLV / WebRTC',
    icon: 'VideoCamera',
    tone: 'is-brand'
  },
  {
    path: '/playback',
    title: '设备录像',
    desc: '检索设备录像并回放、下载',
    icon: 'Film',
    tone: 'is-warning'
  },
  {
    path: '/cloud',
    title: '云端录像',
    desc: '平台侧录制归档与检索',
    icon: 'Cloudy',
    tone: 'is-info'
  },
  {
    path: '/cascade',
    title: '级联配置',
    desc: '上下级平台互联与目录共享',
    icon: 'Share',
    tone: 'is-success'
  }
]

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
.stat-extra {
  font-size: 11.5px;
  margin-top: 3px;
  color: var(--text-secondary);
}

/* ---------- 在线率进度 ---------- */

.rate-row {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(280px, 1fr));
  gap: var(--sp-5);
  padding: var(--sp-4) var(--sp-5);
  background: var(--bg-surface);
  border: 1px solid var(--border-color);
  border-radius: var(--r-lg);
  box-shadow: var(--sh-xs);
}

.rate-block {
  min-width: 0;
}

.rate-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  margin-bottom: var(--sp-2);
}

.rate-label {
  font-size: 12.5px;
  color: var(--text-secondary);
}

.rate-value {
  font-size: 15px;
  font-weight: 650;
  color: var(--text-primary);
  font-variant-numeric: tabular-nums;
}

/* ---------- 快捷入口 ---------- */

.quick-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(232px, 1fr));
  gap: var(--sp-3);
}

.quick-card {
  display: flex;
  align-items: center;
  gap: var(--sp-3);
  padding: var(--sp-4);
  background: var(--bg-surface);
  border: 1px solid var(--border-color);
  border-radius: var(--r-lg);
  cursor: pointer;
  text-align: left;
  font: inherit;
  box-shadow: var(--sh-xs);
  transition: box-shadow 0.16s ease, transform 0.16s ease, border-color 0.16s ease;
}

.quick-card:hover {
  box-shadow: var(--sh-md);
  transform: translateY(-2px);
  border-color: var(--brand-200);
}

.quick-card:hover .quick-arrow {
  opacity: 1;
  transform: translateX(0);
}

.quick-icon {
  width: 40px;
  height: 40px;
  border-radius: var(--r-lg);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 19px;
  color: #fff;
  flex-shrink: 0;
}

.quick-icon.is-brand { background: linear-gradient(135deg, var(--brand-400), var(--brand-600)); }
.quick-icon.is-warning { background: linear-gradient(135deg, #f5b344, var(--warning-500)); }
.quick-icon.is-info { background: linear-gradient(135deg, #9aa7b8, var(--info-500)); }
.quick-icon.is-success { background: linear-gradient(135deg, #2bc776, var(--success-500)); }

.quick-text {
  flex: 1;
  min-width: 0;
}

.quick-title {
  font-size: 13.5px;
  font-weight: 600;
  color: var(--text-primary);
  margin-bottom: 2px;
}

.quick-desc {
  font-size: 11.5px;
  color: var(--text-secondary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.quick-arrow {
  color: var(--ink-300);
  opacity: 0;
  transform: translateX(-4px);
  transition: opacity 0.16s ease, transform 0.16s ease;
  flex-shrink: 0;
}

/* ---------- 双栏 ---------- */

.split {
  display: grid;
  grid-template-columns: minmax(0, 1.55fr) minmax(0, 1fr);
  gap: var(--sp-4);
  align-items: stretch;
}

.col-main,
.col-side {
  min-width: 0;
}

/* ---------- 服务器信息键值表 ---------- */

.kv {
  margin: 0;
  display: flex;
  flex-direction: column;
}

.kv-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sp-4);
  padding: 10px 0;
  border-bottom: 1px dashed var(--ink-100);
}

.kv-row:last-child {
  border-bottom: none;
}

.kv-row dt {
  font-size: 12.5px;
  color: var(--text-secondary);
  flex-shrink: 0;
}

.kv-row dd {
  margin: 0;
  font-size: 12.5px;
  color: var(--text-primary);
  text-align: right;
  min-width: 0;
}

/* ---------- 杂项 ---------- */

.count-pill {
  display: inline-flex;
  align-items: center;
  height: 19px;
  padding: 0 7px;
  border-radius: var(--r-full);
  background: var(--brand-50);
  color: var(--brand-600);
  font-size: 11.5px;
  font-weight: 600;
}

.text-xs {
  font-size: 11.5px;
}

/* ---------- 响应式 ---------- */

@media (max-width: 1100px) {
  .split {
    grid-template-columns: minmax(0, 1fr);
  }
}

@media (max-width: 768px) {
  .quick-grid {
    grid-template-columns: repeat(auto-fit, minmax(100%, 1fr));
  }
  .rate-row {
    gap: var(--sp-4);
    padding: var(--sp-3) var(--sp-4);
  }
}
</style>
