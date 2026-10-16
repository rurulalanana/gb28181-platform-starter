<template>
  <div ref="pageRef" class="page dash-page">
    <!-- 页头：标题 + 大屏展示（对齐参考站概览页头部） -->
    <header class="page-header">
      <div class="page-title-group">
        <h2 class="page-title">服务器概览</h2>
        <p class="page-subtitle">
          主机指标与负载计数，每 3 秒自动刷新<span v-if="lastSync"> · 最近同步 {{ lastSync }}</span>
        </p>
      </div>
      <div class="page-actions">
        <el-button size="small" :loading="loading" @click="load(true)">
          <el-icon><Refresh /></el-icon> 刷新
        </el-button>
        <el-button size="small" @click="toggleFullscreen">
          <el-icon><FullScreen /></el-icon> 大屏展示
        </el-button>
      </div>
    </header>

    <!-- 6 卡网格：3 列 × 2 行（通道信息 / 负载 / CPU / 存储 / 带宽 / 内存） -->
    <section class="dash-grid">
      <!-- 1. 通道信息（两个圆环） -->
      <div class="dash-card">
        <div class="dc-title">通道信息</div>
        <div class="dc-body gauge-row">
          <div v-for="g in gauges" :key="g.label" class="gauge">
            <el-progress
              type="circle"
              :percentage="g.pct"
              :width="92"
              :stroke-width="7"
              :color="g.color"
            >
              <template #default>
                <span class="gauge-pct">{{ g.pct.toFixed(2) }}%</span>
              </template>
            </el-progress>
            <div class="gauge-caption">
              在线数：<b>{{ g.online }}</b><br />
              总{{ g.label }}：<b>{{ g.total }}</b>
            </div>
          </div>
        </div>
      </div>

      <!-- 2. 负载信息（柱状） -->
      <div class="dash-card">
        <div class="dc-title">负载信息</div>
        <div class="dc-body"><div ref="loadRef" class="chart-fill" /></div>
      </div>

      <!-- 3. CPU 使用（面积折线） -->
      <div class="dash-card">
        <div class="dc-title">CPU 使用</div>
        <div class="dc-body"><div ref="cpuRef" class="chart-fill" /></div>
      </div>

      <!-- 4. 存储使用（横向条） -->
      <div class="dash-card">
        <div class="dc-title">存储使用</div>
        <div class="dc-body disk-body">
          <div v-if="!disks.length" class="empty-hint">暂无数据</div>
          <div v-for="d in disks" :key="d.path" class="disk-row">
            <div class="disk-head">
              <span class="mono disk-path">{{ d.path }}</span>
              <span class="text-dim disk-nums">
                {{ fmtSize(d.used) }} / {{ fmtSize(d.total) }}（{{ diskPct(d).toFixed(1) }}%）
              </span>
            </div>
            <div class="disk-bar">
              <div
                class="disk-fill"
                :class="{ 'is-high': diskPct(d) >= 85 }"
                :style="{ width: Math.max(diskPct(d), 1.5) + '%' }"
              />
            </div>
          </div>
        </div>
      </div>

      <!-- 5. 带宽使用（接收/发送双线） -->
      <div class="dash-card">
        <div class="dc-title">带宽使用 (Mbps)</div>
        <div class="dc-body"><div ref="netRef" class="chart-fill" /></div>
      </div>

      <!-- 6. 内存使用（面积折线） -->
      <div class="dash-card">
        <div class="dc-title">内存使用</div>
        <div class="dc-body"><div ref="memRef" class="chart-fill" /></div>
      </div>
    </section>
  </div>
</template>

<script setup>
import { computed, nextTick, onMounted, onUnmounted, ref } from 'vue'
import * as echarts from 'echarts/core'
import { BarChart, LineChart } from 'echarts/charts'
import { GridComponent, LegendComponent, TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import { deviceApi, metricsApi } from '@/api'

echarts.use([BarChart, LineChart, GridComponent, TooltipComponent, LegendComponent, CanvasRenderer])

/** 折线历史采样上限（60 点 × 3 秒 ≈ 3 分钟视窗）。 */
const MAX_POINTS = 60
/** 轮询间隔（毫秒）。 */
const POLL_MS = 3000

const pageRef = ref(null)
const loadRef = ref(null)
const cpuRef = ref(null)
const netRef = ref(null)
const memRef = ref(null)

const loading = ref(false)
const lastSync = ref('')
const stats = ref(null)
const metrics = ref(null)
/** 折线历史（页面打开期间累积）。 */
const hist = { t: [], cpu: [], mem: [], rx: [], tx: [] }
let timer = null
const charts = {}

/* ---------------- 主题色（取自自家设计令牌） ---------------- */
function cssVar(name, fallback) {
  const v = getComputedStyle(document.documentElement).getPropertyValue(name).trim()
  return v || fallback
}

/* ---------------- 计算属性 ---------------- */
const gauges = computed(() => {
  const s = stats.value || {}
  const brand = cssVar('--brand-500', '#2f5c94')
  const devPct = pct(s.deviceOnline, s.deviceTotal)
  const chPct = pct(s.channelOnline, s.channelTotal)
  return [
    { label: '设备', pct: devPct, online: s.deviceOnline ?? 0, total: s.deviceTotal ?? 0, color: brand },
    { label: '通道', pct: chPct, online: s.channelOnline ?? 0, total: s.channelTotal ?? 0, color: brand }
  ]
})

const disks = computed(() => metrics.value?.disks || [])

function pct(online, total) {
  return total > 0 ? Math.round(((online || 0) / total) * 10000) / 100 : 0
}

function diskPct(d) {
  return d.total > 0 ? (d.used / d.total) * 100 : 0
}

function fmtSize(bytes) {
  if (bytes == null) return '—'
  const gb = bytes / 1024 / 1024 / 1024
  if (gb >= 1024) return (gb / 1024).toFixed(2) + ' TB'
  return gb.toFixed(1) + ' GB'
}

function fmtTime(d) {
  return d.toLocaleTimeString('zh-CN', { hour12: false })
}

/* ---------------- 数据加载 ---------------- */
async function load(manual = false) {
  if (manual) loading.value = true
  try {
    const [s, m] = await Promise.all([
      deviceApi.onlineStats().catch(() => null),
      metricsApi.get().catch(() => null)
    ])
    if (s) stats.value = s
    if (m) metrics.value = m
    pushHistory(m)
    updateCharts()
    lastSync.value = fmtTime(new Date())
  } finally {
    loading.value = false
  }
}

/** 把最新指标追加进历史缓冲。 */
function pushHistory(m) {
  const now = new Date()
  const label = `${String(now.getHours()).padStart(2, '0')}:${String(now.getMinutes()).padStart(2, '0')}:${String(now.getSeconds()).padStart(2, '0')}`
  hist.t.push(label)
  hist.cpu.push(m?.cpu ?? null)
  hist.mem.push(m?.memTotal ? Math.round((m.memUsed / m.memTotal) * 1000) / 10 : null)
  hist.rx.push(m?.netRxMbps ?? null)
  hist.tx.push(m?.netTxMbps ?? null)
  for (const k of Object.keys(hist)) {
    if (hist[k].length > MAX_POINTS) hist[k].shift()
  }
}

/* ---------------- 图表 ---------------- */
function initCharts() {
  const ink = cssVar('--ink-400', '#909399')
  const line = cssVar('--line', '#e5e7eb')
  const axisCommon = {
    axisLine: { lineStyle: { color: line } },
    axisTick: { show: false },
    axisLabel: { color: ink, fontSize: 10 },
    splitLine: { lineStyle: { color: line, type: 'dashed', opacity: 0.5 } }
  }
  const grid = { left: 34, right: 12, top: 26, bottom: 22 }

  if (loadRef.value) {
    charts.load = echarts.init(loadRef.value)
    charts.load.setOption({
      grid: { left: 40, right: 16, top: 30, bottom: 24 },
      tooltip: { trigger: 'axis' },
      xAxis: { type: 'category', data: ['直播', '播放', '录像'], ...axisCommon },
      yAxis: { type: 'value', minInterval: 1, ...axisCommon },
      series: [
        {
          type: 'bar',
          data: [],
          barWidth: 34,
          itemStyle: { color: cssVar('--brand-500', '#2f5c94'), borderRadius: [4, 4, 0, 0] },
          label: { show: true, position: 'top', color: ink, fontSize: 11 }
        }
      ]
    })
  }

  if (cpuRef.value) {
    charts.cpu = echarts.init(cpuRef.value)
    charts.cpu.setOption(lineOption(grid, axisCommon, '%', 100))
  }

  if (netRef.value) {
    charts.net = echarts.init(netRef.value)
    charts.net.setOption({
      grid,
      tooltip: { trigger: 'axis', valueFormatter: (v) => (v == null ? '—' : v + ' Mbps') },
      legend: { show: true, top: 0, right: 4, itemWidth: 12, itemHeight: 8, textStyle: { fontSize: 10, color: ink } },
      xAxis: { type: 'category', data: [], ...axisCommon },
      yAxis: { type: 'value', ...axisCommon },
      series: [
        {
          name: '接收',
          type: 'line',
          smooth: true,
          showSymbol: false,
          data: [],
          lineStyle: { width: 2, color: cssVar('--brand-500', '#2f5c94') },
          itemStyle: { color: cssVar('--brand-500', '#2f5c94') },
          areaStyle: { opacity: 0.12, color: cssVar('--brand-500', '#2f5c94') }
        },
        {
          name: '发送',
          type: 'line',
          smooth: true,
          showSymbol: false,
          data: [],
          lineStyle: { width: 2, color: cssVar('--online-500', '#17914f') },
          itemStyle: { color: cssVar('--online-500', '#17914f') }
        }
      ]
    })
  }

  if (memRef.value) {
    charts.mem = echarts.init(memRef.value)
    charts.mem.setOption(lineOption(grid, axisCommon, '%', 100, cssVar('--accent', '#7c5cff')))
  }
}

/** 通用面积折线配置。 */
function lineOption(grid, axisCommon, unit, max, color) {
  const c = color || cssVar('--brand-500', '#2f5c94')
  return {
    grid,
    tooltip: { trigger: 'axis', valueFormatter: (v) => (v == null ? '—' : v + unit) },
    xAxis: { type: 'category', data: [], ...axisCommon },
    yAxis: { type: 'value', max, ...axisCommon },
    series: [
      {
        type: 'line',
        smooth: true,
        showSymbol: false,
        data: [],
        lineStyle: { width: 2, color: c },
        itemStyle: { color: c },
        areaStyle: { opacity: 0.16, color: c }
      }
    ]
  }
}

/** 用最新数据刷新各图表。 */
function updateCharts() {
  const m = metrics.value
  if (charts.load) {
    charts.load.setOption({
      series: [{ data: [m?.live ?? 0, m?.play ?? 0, m?.record ?? 0] }]
    })
  }
  if (charts.cpu) {
    charts.cpu.setOption({ xAxis: { data: hist.t }, series: [{ data: hist.cpu }] })
  }
  if (charts.net) {
    charts.net.setOption({
      xAxis: { data: hist.t },
      series: [{ data: hist.rx }, { data: hist.tx }]
    })
  }
  if (charts.mem) {
    charts.mem.setOption({ xAxis: { data: hist.t }, series: [{ data: hist.mem }] })
  }
}

/* ---------------- 大屏展示 ---------------- */
function toggleFullscreen() {
  const el = pageRef.value
  if (!el) return
  if (document.fullscreenElement) {
    document.exitFullscreen().catch(() => {})
  } else {
    el.requestFullscreen().catch(() => {})
  }
}

function onResize() {
  for (const c of Object.values(charts)) c.resize()
}

function onFullscreenChange() {
  // 全屏切换后布局尺寸变化，图表需要重算
  setTimeout(() => onResize(), 120)
}

onMounted(async () => {
  await load()
  await nextTick()
  initCharts()
  updateCharts()
  window.addEventListener('resize', onResize)
  document.addEventListener('fullscreenchange', onFullscreenChange)
  timer = setInterval(() => load(), POLL_MS)
})

onUnmounted(() => {
  if (timer) clearInterval(timer)
  window.removeEventListener('resize', onResize)
  document.removeEventListener('fullscreenchange', onFullscreenChange)
  for (const c of Object.values(charts)) c.dispose()
})
</script>

<style scoped>
/* 6 卡网格：3 列 × 2 行（小屏降列） */
.dash-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 14px;
}

@media (max-width: 992px) {
  .dash-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 640px) {
  .dash-grid {
    grid-template-columns: 1fr;
  }
}

/* 卡片：自家主题（白底、细边、圆角、居中标题） */
.dash-card {
  display: flex;
  flex-direction: column;
  min-height: 280px;
  background: var(--bg-surface);
  border: 1px solid var(--line);
  border-radius: var(--r-lg);
  overflow: hidden;
}

.dc-title {
  padding: 12px 14px 6px;
  text-align: center;
  font-size: 13px;
  font-weight: 600;
  color: var(--ink-700);
  letter-spacing: 0.02em;
}

.dc-body {
  flex: 1;
  min-height: 0;
  padding: 6px 12px 14px;
}

.chart-fill {
  width: 100%;
  height: 100%;
  min-height: 200px;
}

/* 通道信息：两个圆环并排 */
.gauge-row {
  display: flex;
  align-items: center;
  justify-content: space-around;
  gap: 8px;
}

.gauge {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
  padding-top: 18px;
}

.gauge-pct {
  font-size: 12px;
  font-weight: 600;
  color: var(--ink-700);
}

.gauge-caption {
  font-size: 12px;
  line-height: 1.7;
  color: var(--ink-500);
  text-align: center;
}

.gauge-caption b {
  color: var(--ink-800);
}

/* 存储使用 */
.disk-body {
  display: flex;
  flex-direction: column;
  justify-content: center;
  gap: 16px;
  padding-top: 10px;
}

.disk-row {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.disk-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 8px;
}

.disk-path {
  font-size: 12px;
  font-weight: 600;
  color: var(--ink-700);
}

.disk-nums {
  font-size: 11.5px;
}

.disk-bar {
  height: 14px;
  background: var(--bg-sunken);
  border-radius: 7px;
  overflow: hidden;
}

.disk-fill {
  height: 100%;
  border-radius: 7px;
  background: linear-gradient(90deg, var(--brand-400), var(--brand-500));
  transition: width 0.6s ease;
}

/* 用量过高转警示色（仍属自家主题色系） */
.disk-fill.is-high {
  background: linear-gradient(90deg, var(--warning-500, #e6a23c), var(--danger-500, #e55));
}

.empty-hint {
  text-align: center;
  color: var(--ink-400);
  font-size: 12px;
  padding: 30px 0;
}
</style>
