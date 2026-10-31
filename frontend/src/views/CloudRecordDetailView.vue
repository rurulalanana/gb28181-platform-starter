<template>
  <!--
    布局对齐 LiveGBS 的「云端录像 / 时间轴视图」页
    （路由 #/cloudrecord/timeview/<设备编码>/<通道编码>）：

      标题（居中）
      工具条：左 返回/分享页/下载，右 日期选择器 + 列表视图
      播放器（居中，最大 700px，16:9）+ 可拖动进度条
      时间轴（整宽，无录像段列表 —— 列表在独立页面）

    注意不要用 .page.is-fill —— 那个类会把卡片压进一屏高度，
    播放器会被挤成扁条（实测过）。
  -->
  <div class="page detail-page">
    <el-card shadow="never" class="detail-card">
      <!-- 标题：居中，对齐 LiveGBS 的 h4.text-center -->
      <div class="box-title">
        云端录像({{ channelName }})-时间轴视图
      </div>

      <!-- 工具条：左 返回/分享页/下载，右 日期选择器 + 列表视图 -->
      <div class="filter-bar">
        <el-button size="small" @click="goBack">
          <el-icon><ArrowLeft /></el-icon> 返回
        </el-button>
        <el-button size="small" :disabled="!canWrite || !playingRecord" @click="shareCurrent">
          <el-icon><Share /></el-icon> 分享页
        </el-button>
        <el-button size="small" :disabled="!canDownloadRecord(playingRecord)" @click="downloadCurrent">
          <el-icon><Download /></el-icon> 下载
        </el-button>
        <span class="filter-bar-spacer" />

        <!--
          日期选择器：用日历组件（el-date-picker）承载「本月有录像的日期」。

          <b>没有录像的日期直接禁用</b>（disabledDate）—— 选了也是空，
          不如让用户一眼看出哪天有录像。面板里再用 #default 槽把
          有录像的日期标成蓝底，禁用不了的日期是灰的。
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

      <!-- 播放器：居中，最宽 1000px（与实时预览的播放弹窗观感一致） -->
      <div class="player-wrap">
        <div class="video-box">
          <!--
            录像播放：统一用 xgplayer 渲染（挂载点是普通容器，播放器在内部自建 video），
            与实时预览是同一套控制栏、同一套全屏与音量交互。

            1) H.264：地址直接交给 xgplayer 走原生解码。Range 由容器实现，
               首帧只下载文件头，秒开；拖动进度条是浏览器原生行为。
            2) H.265：ZLM 录出的 MP4 里夹着残缺帧（UDP 丢包 → PS 解封装失败），
               浏览器硬解不容错会 PIPELINE_ERROR_DECODE，只出首帧。
               此时由 useRecordPlayer 内的 MSE 内核接管：先建 MediaSource
               把 blob URL 交给 xgplayer 当媒体源，再按 GOP 灌 fMP4，
               坏段跳过继续播（浏览器端只换容器，不转码）。

            token 拼在 URL 上：/api/v1/... 通路带不了自定义头，
            后端 Sa-Token 配了从 query 读（is-read-body）。
            静态 /record/... 通路不过拦截器，不需要 token。
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
 * 生成分享链接需要 cloud:write。
 *
 * <p>分享页免登录、凭签名令牌访问 —— 生成分享等于把这段录像交给平台之外的人，
 * 属于「管理」而不是「观看」，因此不给只看不放的账号。
 */
const canWrite = computed(() => auth.can('cloud:write'))

/** 通道标识：设备编码 / 通道编码。 */
const serial = computed(() => String(route.query.serial || ''))
const code = computed(() => String(route.query.code || ''))

/** 初始日期：URL 上带了 period 就用它（从列表页跳过来时）。 */
const period = ref(String(route.query.period || todayString()))
const records = ref([])
const monthFlags = ref('')
const channelName = ref('')
const loadingDaily = ref(false)

/**
 * 日期格子是否「有录像」/ 是否禁用（判据见 {@code utils/cloudRecord.js}）。
 *
 * <p>日历面板逐格调用（28~42 次），所以用 Set 而不是数组 {@code includes}。
 */
const recordDaySet = computed(() => new Set(recordDaysOf(monthFlags.value)))
const cellHasRecord = (cell) => isRecordDay(cell, flagsMonth.value, recordDaySet.value)
const cellDisabled = (date) => isDisabledDate(date, flagsMonth.value, recordDaySet.value)

/** 跳到列表视图（独立页面，对齐 LiveGBS 的 #/cloudrecord/listview/...）。 */
function goListView() {
  router.push({
    path: '/cloud/detail/list',
    query: { serial: serial.value, code: code.value, period: period.value }
  })
}

/**
 * 日期变了就重新加载。
 *
 * <p><b>用 watch 而不是只靠 @change</b>：el-date-picker 用了 #default 槽
 * 自定义格子渲染后，Element 内部的 pick 流程与默认不完全一致，
 * 实测 @change 不触发（modelValue 变了但没有请求发出）。
 * watch modelValue 最可靠。
 */
watch(period, async (value, old) => {
  if (!value || value === old) return
  const month = value.slice(0, 7).replace('-', '')
  if (month !== loadedMonth.value) {
    await loadMonthFlags()
  }
  await loadDaily()
})

/** 播放中的录像（底栏展示用）。 */
const playingRecord = ref(null)

/** 播放状态（展示用，直接映射播放器状态）。 */
const playState = computed(() => recordPlayer.state.value)
const playStateType = computed(() => {
  if (playState.value === '播放中') return 'success'
  if (playState.value === '播放异常') return 'danger'
  return 'info'
})

/** 播放器挂载容器（xgplayer 在内部创建 video 元素）。 */
const videoContainer = ref(null)

/**
 * 录像播放器：LivePlayer。
 *
 * <p>与实时预览、设备录像回放是<b>同一个播放器</b>。
 * 云端录像是标准 mp4，走 LivePlayer 的官方点播通路：
 * {@code live=false} 显示进度条，支持拖动定位与倍速。
 */
const recordPlayer = useLivePlayer(videoContainer, {
  onFatal: (msg) => ElMessage.error('播放失败：' + msg),
  onEnded: () => playNext()
})

/**
 * 自动连播下一段。
 *
 * <p>云端录像是一段段独立的 MP4（设备断线、码流中断都会切段），
 * 看完一段还要回头去点时间轴，连续看半小时得点二十次 —— 所以播完
 * 自动接下一段。到当天最后一段就停下，停在「播放结束」。
 *
 * <p><b>跳过仍在录制的段</b>：这种段没结束、文件还在增长，后端也不回填
 * {@code playUrl}，点它必然播放失败。连播遇到就跳过，否则自动续播会
 * 以一个错误提示收场。
 *
 * <p><b>为什么用 setTimeout 推迟而不是直接调 play</b>：本函数由
 * xgplayer 的 {@code ended} 事件触发，此时事件回调还在播放器内部栈上；
 * 直接 {@code play()} 会在回调里销毁当前播放器（{@code stop()}），
 * 容易触发播放器自身的清理竞态。推到下一个宏任务就绕开了。
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

/* ==================== 时间轴 ==================== */

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

/* ==================== 数据加载 ==================== */

/* fmtCompactTs / formatSize / uploadText / uploadColor 见 utils/cloudRecord.js */

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
 * 点播一段录像。
 *
 * <p>播放器统一用 xgplayer（与实时预览同一套控制栏），这里只负责挑通路：
 *
 * <ul>
 *   <li><b>H.264 / 探测不出编码</b> → 原生直连。
 *       接口回填的 {@code playUrl}（{@code /record/...}）是 Spring 静态资源映射，
 *       Range 由容器实现，首帧只下载文件头，秒开，拖动进度条是浏览器原生行为。</li>
 *   <li><b>H.265</b> → 直接走容错通路。ZLM 录出的 H.265 MP4 里几乎必然夹着
 *       残缺帧（UDP 丢包 → PS 解封装失败 → 残帧仍被写进文件），浏览器硬解不容错，
 *       原生播放只会 {@code PIPELINE_ERROR_DECODE}；而且浏览器拿到 {@code src}
 *       后会<b>预取整个文件</b>（实测 57MB 全下完才报错），
 *       所以先花几十 KB 探一下编码，H.265 直接跳过原生尝试。</li>
 * </ul>
 *
 * <p><b>不调 {@code /cloudrecord/play}</b>：那个接口是把 MP4 拉进 ZLM
 * 转成 ws-flv/webrtc 地址，供「流式」播放用。本页直接播文件，
 * 那些地址一个都用不上；调它反而会让 ZLM 白读一遍文件、多等一个来回。
 */
async function play(record) {
  /*
   * 正在录制的段点不了。
   *
   * <p>这种段 {@code endTime} 为 null、文件还在增长：后端不回填
   * {@code playUrl}（媒体索引尚未定稿），走受保护接口也会失败。
   * 与其让用户看到「播放失败」，不如直接说清原因。
   */
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

/**
 * 停止点播。
 *
 * <p>销毁播放器会释放 MediaSource，并中止对录像文件的请求。
 */
function stopPlay() {
  recordPlayer.stop()
  playingRecord.value = null
}

/**
 * 下载一段录像（判据与动作统一在 {@code utils/cloudRecord.js}，与列表页共用）。
 *
 * <p>本地录像的 {@code downloadUrl} 就是播放用的同源静态路径，交给
 * {@code downloadFile} 让浏览器原生下载即可 —— 有进度、能续传、不占内存。
 * 对象存储的地址是跨域的签名 URL，会新开窗口，由服务端的
 * {@code Content-Disposition} 决定文件名。
 */
function download(record) {
  downloadRecord(record, channelName.value)
}

/** 工具条上的「下载」：下载当前正在点播的那一段。 */
function downloadCurrent() {
  if (playingRecord.value) download(playingRecord.value)
}

/**
 * 生成分享链接。
 *
 * <p>分享页免登录（凭签名令牌），把这一段录像分享给不登录平台的人看。
 */
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
/*
 * 表格与工具条沿用全局样式（.filter-bar / .el-table），
 * 与「设备管理」「通道管理」保持一致，这里只补标题与时间轴。
 */
.box-title {
  text-align: center;
  font-size: 15px;
  font-weight: 600;
  color: var(--text-primary);
  padding: 4px 0 12px;
}

/* ==================== 播放器 ==================== */

/*
 * 播放器居中，最大 700px —— 对齐 LiveGBS 的
 * `.player-wrapper { max-width: 700px; margin: 0 auto }`。
 *
 * <p><b>为什么不用 .page.is-fill</b>：那个类会让卡片「吃掉一屏高度」，
 * 内部再放播放器 + 时间轴 + 分段表，播放器就会被 flex 挤成扁条
 * （实测画面被压成一条）。本页内容本来就要滚动，故不撑满。
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
 * xgplayer 内部的 video。
 *
 * <p>xgplayer 自己会给 video 设 {@code width/height: 100%}，
 * 但 <b>object-fit 只有在根节点带 data-xgfill 时才生效</b>。
 * 这里再兜一层 contain：录像分辨率（如 2560×1440）与 16:9 容器
 * 比例一致时看不出差别，一旦容器被拉成别的比例（窄屏、右侧留白），
 * 缺了它画面就会被<b>非等比拉伸</b> —— 这正是之前"画面变形"的原因。
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

/* ==================== 日历 ==================== */

/*
 * 日期选择器里的日期格：当天有录像的用蓝底高亮，一眼看出「哪天有录像」。
 *
 * <p>Element 的日期面板逐格渲染，走 #default 槽
 * （basic-cell-render 里 renderSlot(slots,'default',cell)），
 * 所以可以完全接管格子的渲染。
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
 * 没有录像的日期：Element 会给 td 加 .disabled，但默认样式太淡
 * （opacity 仍是 1、cursor 还是 pointer），看不出来点不了。
 * 这里明确置灰 + 禁止点击。
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

/* ==================== 时间轴 ==================== */

.timeline-wrap {
  padding: 4px 0 16px;
  display: flex;
  flex-direction: column;
  min-height: 0;
}

/* 时间轴轨道本身只占固定高度，下方跟分段表 */
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
  /*
   * 两层刻度，对齐 LiveGBS 的 .time-day（288 个 5 分钟小格 + 小时线）：
   *   细线 = 每 5 分钟一格（100% / 288）
   *   粗线 = 每小时（100% / 24）
   */
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
