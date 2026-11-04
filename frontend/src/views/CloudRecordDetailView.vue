<template>
  <!--
    布局对齐 LiveGBS 的「云端录像 / 时间轴视图」页：标题居中 → 工具条 → 播放器（最大 700px）
    → 24 小时时间轴。不要用 .page.is-fill，它会把卡片压进一屏高度、播放器挤成扁条。
  -->
  <div class="page detail-page">
    <el-card shadow="never" class="detail-card">
      <!-- 标题：居中，对齐 LiveGBS 的 h4.text-center -->
      <div class="box-title">
        云端录像({{ channelName }})-时间轴视图
      </div>

      <!-- 工具条 -->
      <div class="filter-bar">
        <el-button size="small" @click="goBack">
          <el-icon><ArrowLeft /></el-icon> 返回
        </el-button>
        <el-button size="small" :disabled="!canShare || !playingRecord" @click="shareCurrent">
          <el-icon><Share /></el-icon> 分享页
        </el-button>
        <el-button size="small" :disabled="!canDownloadRecord(playingRecord)" @click="downloadCurrent">
          <el-icon><Download /></el-icon> 下载
        </el-button>
        <span class="filter-bar-spacer" />

        <!--
          日期选择器承载「本月有录像的日期」：无录像的日期直接禁用，
          有录像的日期用 #default 槽标成蓝底。
        -->
        <el-date-picker
          v-model="period"
          type="date"
          placeholder="选择日期"
          value-format="YYYY-MM-DD"
          class="date-picker"
          :clearable="false"
          :disabled-date="cellDisabled"
        >
          <template #default="cell">
            <div class="cal-cell" :class="{ 'has-record': cellHasRecord(cell) }">
              {{ cell.text }}
            </div>
          </template>
        </el-date-picker>

        <!-- 列表视图：跳到独立的表格页（对齐 LiveGBS 的 #/cloudrecord/listview/...） -->
        <el-button size="small" @click="goListView">
          <el-icon><Tickets /></el-icon> 列表视图
        </el-button>
      </div>

      <!-- 播放器：居中，最宽 1000px -->
      <div class="player-wrap">
        <div class="video-box">
          <!--
            H.264 直接交给 xgplayer 原生解码：Range 由容器实现，首帧只下载文件头，秒开。
            H.265 由 useRecordPlayer 的 MSE 内核接管：ZLM 录出的 MP4 夹着残缺帧（UDP 丢包），
            浏览器硬解不容错会 PIPELINE_ERROR_DECODE、只出首帧；MSE 按 GOP 灌 fMP4、坏段跳过。
            token 拼在 URL 上：/api/v1 通路带不了自定义头，后端 Sa-Token 从 query 读；
            静态 /record/... 不过拦截器，不需要 token。
          -->
          <div v-if="playingRecord" ref="videoContainer" class="video-container" />
          <div v-else class="player-tip">
            <el-icon :size="34"><VideoPlay /></el-icon>
            <span>点击下方时间轴上的录像段点播</span>
          </div>
        </div>

        <div v-if="playingRecord" class="player-bar">
          <span class="mono">{{ fmtCompactTs(playingRecord.startAt) }}</span>
          <el-tag :type="playStateType" size="small">{{ playState }}</el-tag>
          <span class="filter-bar-spacer" />
          <el-button size="small" type="danger" plain @click="stopPlay">
            <el-icon><SwitchButton /></el-icon> 停止点播
          </el-button>
          <el-button size="small" :disabled="!canDownloadRecord(playingRecord)" @click="download(playingRecord)">
            <el-icon><Download /></el-icon> 下载本段
          </el-button>
          <el-button
            size="small"
            type="danger"
            plain
            :disabled="!canDelete || !records.length"
            @click="removeDaily"
          >
            <el-icon><Delete /></el-icon> 删除当日
          </el-button>
        </div>
      </div>

      <!-- 时间轴：24 小时轨道 + 录像段色条（对齐 LiveGBS 的 .time-rule） -->
      <div v-loading="loadingDaily" class="timeline-wrap">
        <div class="timeline">
          <div class="tl-scale">
            <span
              v-for="h in 24"
              :key="h"
              class="tl-hour"
              :style="{ left: ((h - 1) / 24 * 100) + '%' }"
            >
              {{ String(h - 1).padStart(2, '0') }}
            </span>
          </div>

          <div class="tl-track">
            <el-tooltip
              v-for="seg in timelineSegments"
              :key="seg.record.recordId"
              :content="segTitle(seg)"
              placement="top"
              :show-after="120"
            >
              <div
                class="tl-seg"
                :class="{
                  'is-playing': playingRecord?.recordId === seg.record.recordId,
                  'is-important': seg.record.important
                }"
                :style="{ left: seg.left + '%', width: seg.width + '%' }"
                @click="play(seg.record)"
              />
            </el-tooltip>

            <div v-if="nowPercent != null" class="tl-now" :style="{ left: nowPercent + '%' }">
              <span class="tl-now-label">{{ nowText }}</span>
            </div>

            <span v-if="!records.length" class="tl-track-empty">当日无云端录像</span>
          </div>
        </div>

        <div class="timeline-foot">
          <span v-if="records.length">共 <b>{{ records.length }}</b> 段录像</span>
          <span v-else class="text-muted">当日无云端录像</span>
          <span class="text-muted">{{ timelineSummary }}</span>
        </div>
      </div>
    </el-card>
  </div>
</template>

<script setup>
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { cloudRecordApi } from '@/api'
import { useAuthStore } from '@/stores/auth'
import { useLivePlayer } from '@/composables/useLivePlayer'
import {
  canDownloadRecord,
  downloadRecord,
  fmtCompactTs,
  formatSize,
  isDisabledDate,
  isRecordDay,
  parseCompactTs,
  recordDaysOf,
  todayString,
  uploadColor,
  uploadText
} from '@/utils/cloudRecord'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

/** 删除当日录像不可恢复，用独立的 cloud:delete。 */
const canDelete = computed(() => auth.can('cloud:delete'))

/**
 * 生成分享链接需 cloud:write：分享页免登录、凭签名令牌访问，
 * 等于把录像交给平台之外的人，属于「管理」而不是「观看」。
 */
const canWrite = computed(() => auth.can('cloud:write'))
const canShare = computed(() => auth.can('cloud:share'))

const serial = computed(() => String(route.query.serial || ''))
const code = computed(() => String(route.query.code || ''))

/** 初始日期：URL 上带了 period 就用它（从列表页跳过来时）。 */
const period = ref(String(route.query.period || todayString()))
const records = ref([])
const monthFlags = ref('')
const channelName = ref('')
const loadingDaily = ref(false)

/** 日历逐格调用（28~42 次），用 Set 而不是数组 includes。 */
const recordDaySet = computed(() => new Set(recordDaysOf(monthFlags.value)))
const cellHasRecord = (cell) => isRecordDay(cell, flagsMonth.value, recordDaySet.value)
const cellDisabled = (date) => isDisabledDate(date, flagsMonth.value, recordDaySet.value)

function goListView() {
  router.push({
    path: '/cloud/detail/list',
    query: { serial: serial.value, code: code.value, period: period.value }
  })
}

/**
 * 日期变了就重新加载。用 watch 而不是 @change：el-date-picker 用了 #default 槽
 * 自定义格子后，内部 pick 流程与默认不一致，实测 @change 不触发（modelValue 变了但没发请求）。
 */
watch(period, async (value, old) => {
  if (!value || value === old) return
  const month = value.slice(0, 7).replace('-', '')
  if (month !== loadedMonth.value) {
    await loadMonthFlags()
  }
  await loadDaily()
})

const playingRecord = ref(null)

const playState = computed(() => recordPlayer.state.value)
const playStateType = computed(() => {
  if (playState.value === '播放中') return 'success'
  if (playState.value === '播放异常') return 'danger'
  return 'info'
})

/** 播放器挂载容器（xgplayer 在内部创建 video 元素）。 */
const videoContainer = ref(null)

/**
 * 录像播放器：与实时预览、设备录像回放是同一个播放器。
 * 云端录像是标准 mp4，走官方点播通路（live=false）：显示进度条，支持拖动与倍速。
 */
const recordPlayer = useLivePlayer(videoContainer, {
  onFatal: (msg) => ElMessage.error('播放失败：' + msg),
  onEnded: () => playNext()
})

/**
 * 自动连播下一段：云端录像是一段段独立 MP4（断线/中断都切段），不连播看半小时要点二十次。
 * 跳过仍在录制的段（没结束、文件在增长，后端不回填 playUrl，点了必然失败）。
 * 用 setTimeout 推迟：本函数由 xgplayer 的 ended 事件触发，直接 play() 会在回调里
 * 销毁当前播放器，容易触发清理竞态；推到下一个宏任务就绕开了。
 */
function playNext() {
  const cur = playingRecord.value
  if (!cur) {
    return
  }
  const list = records.value
  const i = list.findIndex((r) => r.recordId === cur.recordId)
  if (i < 0) {
    return
  }
  // 往后找第一段「已结束」的录像（跳过正在录制的）
  let next = null
  for (let k = i + 1; k < list.length; k++) {
    if (!list[k].recording) {
      next = list[k]
      break
    }
  }
  if (!next) {
    // 已是当天最后一段可播的：保持「播放结束」，不再往后续
    return
  }
  setTimeout(() => {
    // 期间用户可能已手动停止或点了别的段
    if (playingRecord.value?.recordId !== cur.recordId) {
      return
    }
    play(next)
  }, 0)
}

const nowPercent = ref(null)
const nowText = ref('')
let nowTimer = null

function updateNow() {
  if (period.value === todayString()) {
    const d = new Date()
    const secs = d.getHours() * 3600 + d.getMinutes() * 60 + d.getSeconds()
    nowPercent.value = (secs / 86400) * 100
    nowText.value = `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
  } else {
    nowPercent.value = null
    nowText.value = ''
  }
}

const timelineSegments = computed(() => {
  const out = []
  for (const r of records.value) {
    const start = parseCompactTs(r.startAt)
    if (!start) continue
    const secs = start.getHours() * 3600 + start.getMinutes() * 60 + start.getSeconds()
    let duration = r.duration
    if (!duration && r.endTime) {
      const end = parseCompactTs(r.endTime)
      if (end && end > start) duration = Math.round((end - start) / 1000)
    }
    if (!duration || duration <= 0) duration = 30
    out.push({
      record: r,
      left: (secs / 86400) * 100,
      width: Math.max((duration / 86400) * 100, 0.22)
    })
  }
  return out
})

function segTitle(seg) {
  const r = seg.record
  const bits = [fmtCompactTs(r.startAt)]
  if (r.endTime) bits.push('至 ' + fmtCompactTs(r.endTime))
  if (r.duration != null) bits.push(r.duration + ' 秒')
  if (r.fileSize) bits.push(formatSize(r.fileSize))
  // 录制中的段不能播（见 play），提示里说清楚，别让用户反复点
  bits.push(r.recording ? '录制中，结束后可回放' : '点击点播')
  return bits.join(' · ')
}

const timelineSummary = computed(() => {
  let secs = 0
  for (const r of records.value) {
    if (r.duration) {
      secs += r.duration
      continue
    }
    const s = parseCompactTs(r.startAt)
    const e = parseCompactTs(r.endTime)
    if (s && e && e > s) secs += Math.round((e - s) / 1000)
  }
  if (!secs) return ''
  const h = Math.floor(secs / 3600)
  const m = Math.floor((secs % 3600) / 60)
  return `合计约 ${h ? h + ' 小时 ' : ''}${m} 分钟`
})

async function loadDaily() {
  if (!serial.value || !code.value) {
    ElMessage.warning('缺少通道参数')
    return
  }
  loadingDaily.value = true
  try {
    const data = await cloudRecordApi.queryDaily({
      serial: serial.value,
      code: code.value,
      period: period.value.replace(/-/g, '')
    })
    records.value = data?.list || []
    if (data?.name) channelName.value = data.name
  } catch (e) {
    ElMessage.error(e.message || '查询失败')
  } finally {
    loadingDaily.value = false
    updateNow()
  }
}

async function loadMonthFlags() {
  if (!serial.value || !code.value) return
  const month = period.value.slice(0, 7).replace('-', '')
  loadedMonth.value = month
  flagsMonth.value = period.value.slice(0, 7)
  try {
    const data = await cloudRecordApi.queryMonthly({ serial: serial.value, code: code.value, period: month })
    monthFlags.value = data?.flag || ''
  } catch (e) {
    monthFlags.value = ''
  }
}

/** 当前 monthFlags 对应的月份（YYYYMM），用于判断是否需要重新拉标记。 */
const loadedMonth = ref('')

/** 当前 monthFlags 对应的月份（YYYY-MM），日历高亮时比对用。 */
const flagsMonth = ref('')

function goBack() {
  router.push('/cloud')
}

/* ==================== 点播 / 下载 / 删除 ==================== */

/**
 * 点播一段录像。H.264 / 探测不出编码 → 原生直连 playUrl（/record/... 是静态资源映射，
 * Range 由容器实现，秒开）；H.265 → 容错通路。H.265 的 MP4 几乎必然夹着残缺帧
 * （UDP 丢包 → PS 解封装失败），浏览器硬解只报 PIPELINE_ERROR_DECODE，且拿到 src 后
 * 会预取整个文件（实测 57MB 全下完才报错），所以先探几十 KB 编码再决定。
 *
 * 不调 /cloudrecord/play：那是把 MP4 拉进 ZLM 转 ws-flv/webrtc 的接口，本页直接播文件用不上。
 */
async function play(record) {
  // 录制中的段 endTime 为 null、文件还在增长，后端不回填 playUrl，必然失败
  if (record.recording) {
    ElMessage.warning('该段仍在录制中，录制结束后才能回放')
    return
  }
  playingRecord.value = { ...record }
  try {
    // 播放器挂载点是 v-if 渲染的，必须等 DOM 出来才能创建实例
    await nextTick()
    // 静态地址（/record/**）的鉴权走地址签名（playUrl 已带 ?e=…&t=…），不需要带 token；
    // 走受保护接口时才有 token
    const authToken = record.playUrl ? '' : (localStorage.getItem('gb_token') || '')
    // playUrl 存在时用它（静态地址 Range 更快），否则退回需要登录的接口
    const source = record.playUrl || `/api/v1/cloudrecord/file/${record.recordId}`
    await recordPlayer.playFile(source, { token: authToken })
  } catch (e) {
    ElMessage.error(e.message || '点播失败')
    playingRecord.value = null
  }
}

/** 停止点播：销毁播放器会释放 MediaSource，并中止对录像文件的请求。 */
function stopPlay() {
  recordPlayer.stop()
  playingRecord.value = null
}

/**
 * 下载（判据与动作统一在 utils/cloudRecord.js，与列表页共用）。
 * 本地录像的 downloadUrl 就是播放用的同源静态路径，浏览器原生下载有进度、能续传；
 * 对象存储的签名 URL 跨域，会新开窗口，文件名由服务端 Content-Disposition 决定。
 */
function download(record) {
  downloadRecord(record, channelName.value)
}

/** 工具条上的「下载」：下载当前正在点播的那一段。 */
function downloadCurrent() {
  if (playingRecord.value) download(playingRecord.value)
}

/** 生成分享链接（分享页免登录，凭签名令牌访问）。 */
async function shareCurrent() {
  if (!playingRecord.value?.recordId) {
    ElMessage.warning('请先点播一段录像')
    return
  }
  try {
    const data = await cloudRecordApi.share({ recordId: playingRecord.value.recordId })
    const link = location.origin + (data?.url || '')
    try {
      await navigator.clipboard.writeText(link)
      ElMessage.success('分享链接已复制：' + link)
    } catch (e) {
      // 剪贴板不可用（非 HTTPS / 权限），退化为提示用户手动复制
      ElMessageBox.alert(link, '分享链接', { confirmButtonText: '知道了' })
    }
  } catch (e) {
    ElMessage.error(e.message || '生成分享链接失败')
  }
}

async function toggleImportant(record) {
  try {
    await cloudRecordApi.setImportant({
      serial: serial.value,
      code: code.value,
      period: record.startAt,
      important: !record.important
    })
    record.important = !record.important
    ElMessage.success(record.important ? '已标记为重要' : '已取消重要标记')
  } catch (e) {
    ElMessage.error(e.message || '操作失败')
  }
}

async function removeOne(record) {
  if (!(await confirmAction(
    `确定删除该录像（${fmtCompactTs(record.startAt)}）？文件将同时删除。`,
    '删除确认',
    { confirmButtonText: '删除' }
  ))) return
  try {
    await cloudRecordApi.remove({ serial: serial.value, code: code.value, period: record.startAt })
    ElMessage.success('已删除')
    loadDaily()
    loadMonthFlags()
  } catch (e) {
    ElMessage.error(e.message || '删除失败')
  }
}

async function removeDaily() {
  if (!(await confirmAction(
    `确定删除 ${period.value} 当天的全部录像？`,
    '删除确认',
    { confirmButtonText: '删除' }
  ))) return
  try {
    const data = await cloudRecordApi.removeDaily({
      serial: serial.value,
      code: code.value,
      period: period.value.replace(/-/g, '')
    })
    ElMessage.success(`已删除 ${data?.deleted ?? 0} 条`)
    loadDaily()
    loadMonthFlags()
  } catch (e) {
    ElMessage.error(e.message || '删除失败')
  }
}

onMounted(async () => {
  await loadMonthFlags()
  await loadDaily()
  updateNow()
  nowTimer = setInterval(updateNow, 30000)
  // 从列表页跳过来带了 ?play=<recordId> → 自动起播那一段
  const wantPlay = String(route.query.play || '')
  if (wantPlay) {
    const target = records.value.find((r) => r.recordId === wantPlay)
    if (target) {
      await play(target)
    }
  }
})

onUnmounted(() => {
  if (nowTimer) clearInterval(nowTimer)
  recordPlayer.stop()
})
</script>

<style scoped>
/* 表格与工具条沿用全局样式，这里只补标题与时间轴 */
.box-title {
  text-align: center;
  font-size: 15px;
  font-weight: 600;
  color: var(--text-primary);
  padding: 4px 0 12px;
}

/*
 * 播放器居中，最大 1000px。不用 .page.is-fill：它让卡片吃掉一屏高度，
 * 播放器会被 flex 挤成扁条（实测画面被压成一条）。
 */
.player-wrap {
  max-width: min(1000px, 100%);
  margin: 0 auto 16px;
}

.player-tip {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 10px;
  color: var(--text-tertiary);
  font-size: var(--fs-sm);
  pointer-events: none;
}

/*
 * xgplayer 内部的 video。object-fit 只有在根节点带 data-xgfill 时才生效，
 * 再兜一层 contain：容器被拉成非 16:9 时缺了它画面会被非等比拉伸（曾出现「画面变形」）。
 */
.video-container :deep(video) {
  object-fit: contain;
  background: #000;
}

/* 点播控制条：播放器下方一行 */
.player-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 10px;
  flex-wrap: wrap;
  font-size: var(--fs-sm);
  color: var(--text-secondary);
}

/*
 * 日期格：有录像的蓝底高亮。Element 的日期面板逐格渲染 #default 槽，
 * 可以完全接管格子渲染。
 */
.cal-cell {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 4px;
  font-size: 13px;
}

.cal-cell.has-record {
  background: var(--brand-500);
  color: #fff;
  font-weight: 600;
}

/*
 * 没有录像的日期：Element 默认样式太淡（opacity 仍为 1、cursor 还是 pointer），
 * 看不出点不了，这里明确置灰 + 禁止点击。
 */
.date-picker :deep(.el-date-table td.disabled .cal-cell) {
  color: var(--text-disabled, #c0c4cc);
  cursor: not-allowed;
  background: transparent;
  text-decoration: line-through;
  text-decoration-color: var(--line);
}

.date-picker :deep(.el-date-table td.disabled) {
  cursor: not-allowed;
}

.date-picker {
  width: 160px;
}

.timeline-wrap {
  padding: 4px 0 16px;
  display: flex;
  flex-direction: column;
  min-height: 0;
}

.seg-table {
  margin-top: 14px;
}

/* 列表视图：给个高度，表格内部滚动，页面不被拉得很长 */
.detail-table {
  height: 420px;
}

.timeline {
  padding: 4px 2px 0;
  user-select: none;
}

.tl-scale {
  position: relative;
  height: 20px;
  margin: 0 8px;
}

.tl-hour {
  position: absolute;
  top: 0;
  transform: translateX(-50%);
  font-size: var(--fs-xs);
  color: var(--text-tertiary);
  font-family: var(--font-mono, monospace);
}

.tl-track {
  position: relative;
  height: 50px;
  margin: 0 8px;
  background: var(--bg-sunken);
  border: 1px solid var(--line);
  border-radius: 8px;
  /* 两层刻度对齐 LiveGBS 的 .time-day：细线每 5 分钟（100%/288），粗线每小时 */
  background-image:
    repeating-linear-gradient(
      to right,
      var(--line-soft) 0 1px,
      transparent 1px calc(100% / 288)
    ),
    repeating-linear-gradient(
      to right,
      var(--line) 0 1px,
      transparent 1px calc(100% / 24)
    );
  overflow: visible;
}

.tl-seg {
  position: absolute;
  top: 9px;
  height: 32px;
  background: var(--brand-400);
  border-radius: 3px;
  cursor: pointer;
  transition: filter 0.15s, box-shadow 0.15s;
}

.tl-seg:hover {
  filter: brightness(1.12);
  box-shadow: 0 2px 8px rgba(31, 76, 176, 0.35);
}

.tl-seg.is-important {
  background: var(--warning-500);
}

.tl-seg.is-playing {
  background: var(--online-500);
  box-shadow: 0 0 0 2px rgba(23, 145, 79, 0.28);
}

.tl-track-empty {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: var(--fs-xs);
  color: var(--text-tertiary);
  pointer-events: none;
}

.tl-now {
  position: absolute;
  top: -6px;
  bottom: -6px;
  width: 2px;
  background: var(--danger-500);
  pointer-events: none;
}

.tl-now-label {
  position: absolute;
  top: -20px;
  left: 50%;
  transform: translateX(-50%);
  padding: 1px 6px;
  font-size: var(--fs-xs);
  color: #fff;
  background: var(--danger-500);
  border-radius: 4px;
  white-space: nowrap;
}

.timeline-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin: 12px 8px 0;
  font-size: var(--fs-xs);
  color: var(--text-secondary);
}

.star {
  cursor: pointer;
}

/* 分段表的操作列：三个文字按钮排成一行，不换行 */
.seg-table :deep(.el-button.is-link) {
  font-size: 12.5px;
  margin-left: 8px;
}

.seg-table :deep(.cell) {
  white-space: nowrap;
}
</style>
