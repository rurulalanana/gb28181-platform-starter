<template>
  <div ref="pageRef" class="page is-fill dash-page">
    <!--
      标题与副标题各占一行却不带信息量，删掉把高度让给图表；
      刷新与大屏是操作入口，保留但压成一行靠右，不单独占标题位。
    -->
    <div class="dash-actions">
      <el-button size="small" :loading="loading" @click="load(true)">
        <el-icon><Refresh /></el-icon> 刷新
      </el-button>
      <el-button size="small" @click="toggleFullscreen">
        <el-icon><FullScreen /></el-icon> 大屏展示
      </el-button>
    </div>

    <!-- 6 卡网格：3 列 × 2 行（通道信息 / 负载 / CPU / 存储 / 带宽 / 内存） -->
    <section ref="gridRef" class="dash-grid">
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

/**
 * 轮询间隔（毫秒）。
 *
 * <p>与服务端采样间隔保持一致 —— 比它快只是重复取到同一份快照，
 * 比它慢则会丢掉中间点。
 */
const POLL_MS = 3000

const pageRef = ref(null)
const gridRef = ref(null)
const loadRef = ref(null)
const cpuRef = ref(null)
const netRef = ref(null)
const memRef = ref(null)

const loading = ref(false)
const stats = ref(null)
const metrics = ref(null)
/**
 * 折线历史缓冲（由服务端快照直接覆盖，前端不自行累积）。
 *
 * <p>为什么不在前端累积：服务端以 3 秒恒定频率采样并保留固定长度的窗口，
 * 前端只需把快照画出来。两端各存一份历史必然会因为采样时刻不同而对不齐，
 * 表现为 X 轴重复刻度或曲线出现阶梯。
 */
const hist = { t: [], cpu: [], mem: [], rx: [], tx: [] }
let timer = null
/** 盯网格尺寸，窗口/全屏/侧栏变化都会改它，图表据此重算。 */
let ro = null
const charts = {}

/**
 * 组件是否已卸载。
 *
 * <p>用途见 {@link load}：{@code await} 期间用户可能切走页面，
 * 此时图表已全部 dispose，后续任何 {@code setOption}/{@code resize}
 * 都会在 ECharts 内部拿 null canvas 并抛
 * {@code Cannot read properties of null (reading 'tagName')}。
 *
 * <p>{@link disposeCharts} 也会清空 {@code charts}，两者互为双保险：
 * 标志位挡住异步回调，清空对象挡住同步遍历。
 */
let unmounted = false

/**
 * 销毁全部图表并清空句柄。
 *
 * <p><b>必须清空 {@code charts}</b>：只 dispose 不删除引用的话，
 * 对象里留着一堆已销毁的实例，任何后续遍历（{@link onResize} 的
 * {@code c.resize()}、重复卸载）都会再次踩到它们。
 * 而 {@code charts} 是 setup 作用域内的对象，同一组件重新挂载时
 * 若沿用旧引用，新图表会被写进已被污染的对象。
 */
function disposeCharts() {
  for (const k of Object.keys(charts)) {
    const c = charts[k]
    delete charts[k]
    try {
      c?.dispose()
    } catch (e) {
      // 已销毁的实例再 dispose 会抛，忽略即可
    }
  }
}

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

/* ---------------- 数据加载 ---------------- */
async function load(manual = false) {
  if (manual) loading.value = true
  try {
    const [s, m] = await Promise.all([
      deviceApi.onlineStats().catch(() => null),
      metricsApi.get().catch(() => null)
    ])
    /*
     * 卸载后不能再碰图表。
     *
     * <p>两个 await 期间用户完全可能切走页面：此时 onUnmounted 已把所有
     * 图表 dispose 掉，而这里继续往下走就会对已销毁的实例调 setOption。
     * ECharts 内部会用 canvas 的 DOM 做尺寸计算，拿到 null 后抛
     * {@code TypeError: Cannot read properties of null (reading 'tagName')}
     * —— 报错堆栈指向 echarts，看起来与页面毫无关系，极难定位。
     *
     * <p>放在 await 之后判断（而不是函数开头）：请求已经发出去了，
     * 让它自然完成即可，只是不再写状态。
     */
    if (unmounted) return
    if (s) stats.value = s
    if (m) metrics.value = m
    applyHistory(m?.history)
    updateCharts()
  } finally {
    loading.value = false
  }
}

/**
 * 用服务端历史铺满缓冲。
 *
 * <p><b>为什么不再本地追加</b>：服务端已按 3 秒恒定频率采样并保留 60 点，
 * 前端每次轮询直接取快照就是完整曲线。若前端再 push 一个「本地时刻」的点，
 * 会与快照里同一时刻的点重复 —— 表现为 X 轴出现两个相同刻度。
 * 采样源唯一，才能保证刻度与数据一一对应。
 */
function applyHistory(h) {
  if (!h?.time?.length) return
  hist.t = [...h.time]
  hist.cpu = [...(h.cpu || [])]
  hist.mem = [...(h.mem || [])]
  hist.rx = [...(h.rx || [])]
  hist.tx = [...(h.tx || [])]
}

/* ---------------- 图表 ---------------- */
function initCharts() {
  /*
   * 重复初始化会泄漏：echarts.init 在同一个 DOM 上再调一次会新建实例，
   * 旧实例的 canvas 与事件监听都不会被回收。组件被 keep-alive 缓存后
   * 反复激活/停用，或在同一挂载周期里被调用两次，就会堆出多份实例，
   * 卸载时只 dispose 最后一份。
   */
  if (Object.keys(charts).length) return
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
  // 卸载后一律不碰：图表已 dispose，setOption 会在 ECharts 内部炸
  if (unmounted) return
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
  /*
   * ResizeObserver 的回调被 rAF 包过一层，意味着它总比 disconnect() 晚一拍：
   * 用户切走页面时，卸载流程已经跑完，这一帧的回调才被调度进来。
   * 此时图表全已 dispose，resize() 同样会在 ECharts 内部踩到 null canvas。
   */
  if (unmounted) return
  for (const c of Object.values(charts)) c.resize()
}

onMounted(async () => {
  unmounted = false
  await load()
  await nextTick()
  initCharts()
  updateCharts()
  // 直接盯网格尺寸：窗口缩放、进/退全屏、侧栏折叠都会改它。
  // 比分别监听 window 与 fullscreenchange 更全，也不会漏掉非窗口原因引起的重排。
  // rAF 包裹是惯例 —— 在观察回调里同步改布局会触发 ResizeObserver 循环告警。
  ro = new ResizeObserver(() => requestAnimationFrame(onResize))
  if (gridRef.value) ro.observe(gridRef.value)
  timer = setInterval(() => load(), POLL_MS)
})

onUnmounted(() => {
  /*
   * 顺序很重要：先立标志位再清理。
   *
   * <p>标志位挡住「已在飞行中的异步回调」（{@link load} 的 await 之后、
   * {@link onResize} 的 rAF），它们在本函数返回后仍会被调度执行；
   * 先清 timer/observer 只能阻止「新」的回调产生。
   */
  unmounted = true
  if (timer) {
    clearInterval(timer)
    timer = null
  }
  ro?.disconnect()
  ro = null
  disposeCharts()
})
</script>

<style scoped>
/* 操作入口：靠右一行，不再单独占标题位 */
.dash-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  flex-shrink: 0;
}

/* 6 卡网格：3 列 × 2 行，吃满剩余高度（小屏降列） */
.dash-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  /* 行高由容器均分 —— 不写 minmax 下限，否则小窗口会把网格撑出滚动条 */
  grid-template-rows: repeat(2, minmax(0, 1fr));
  gap: 14px;
  /* 吃掉 .page.is-fill 里除操作行外的全部高度 */
  flex: 1;
  min-height: 0;
}

@media (max-width: 992px) {
  .dash-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
    /* 2 列时 6 卡变 3 行 */
    grid-template-rows: repeat(3, minmax(0, 1fr));
  }
}

@media (max-width: 640px) {
  /*
   * 窄屏卡片改为纵向堆叠，总高必然超过一屏 ——
   * 此时必须放弃「铺满高度」，否则 grid 会被压到容器高、
   * 而卡片仍按 240px 最小值溢出到网格外，滚动计算就不准了。
   */
  .dash-page,
  .dash-grid {
    flex: none;
  }

  .dash-grid {
    grid-template-columns: 1fr;
    grid-template-rows: none;
    grid-auto-rows: minmax(240px, auto);
  }
}

/* 卡片：自家主题（白底、细边、圆角、居中标题） */
.dash-card {
  display: flex;
  flex-direction: column;
  /* 高度交给网格行，不再写 min-height —— 那是底部留空的根因 */
  min-height: 0;
  background: var(--bg-surface);
  border: 1px solid var(--line);
  border-radius: var(--r-lg);
  overflow: hidden;
}

.dc-title {
  padding: 12px 14px 6px;
  text-align: center;
  font-size: var(--fs-sm);
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
  /* 不写 min-height：那会让图表比卡片高，把卡片撑开、又留出新的空白 */
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
  font-size: var(--fs-xs);
  font-weight: 600;
  color: var(--ink-700);
}

.gauge-caption {
  font-size: var(--fs-xs);
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
  font-size: var(--fs-xs);
  font-weight: 600;
  color: var(--ink-700);
}

.disk-nums {
  font-size: var(--fs-xs);
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
  font-size: var(--fs-xs);
  padding: 30px 0;
}
</style>
