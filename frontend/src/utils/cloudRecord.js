import { ElMessage } from 'element-plus'
import { downloadFile, recordFileName } from './download'

/**
 * 云端录像的展示与交互工具。
 *
 * <p><b>为什么单独抽出来</b>：时间轴页（{@code CloudRecordDetailView}）与
 * 列表页（{@code CloudRecordListView}）展示的是同一份后端数据，格式化规则
 * 必须完全一致 —— 早先两页各存一份实现，改了一处另一页就显示不一致
 * （同一个文件在一页说「已上传」、另一页说「已 上传」）。
 *
 * <p>只放<b>纯函数</b>与一次性动作，不放组件状态。
 */

/** 本地日期 {@code YYYY-MM-DD}（接口的 {@code period} 参数格式）。 */
export function todayString() {
  const d = new Date()
  const pad = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
}

/**
 * 「当月有录像」的标记串 → 日期号数组。
 *
 * @param {string} monthFlags 形如 {@code "0100...1"}，下标即「日 - 1」
 * @returns {number[]} 有录像的日期号（1~31）
 */
export function recordDaysOf(monthFlags) {
  return String(monthFlags || '')
    .split('')
    .map((flag, idx) => (flag === '1' ? idx + 1 : 0))
    .filter(Boolean)
}

/**
 * 日历格子对应的日期串（{@code YYYY-MM-DD}）。
 *
 * <p>Element 的日期格子有三种形态：dayjs 对象、字符串、数字，这里统一收敛。
 * 取不到时返回空串，调用方按「不命中」处理。
 */
function cellDate(cell) {
  if (!cell) return ''
  if (cell.dayjs && typeof cell.dayjs.format === 'function') {
    return cell.dayjs.format('YYYY-MM-DD')
  }
  if (typeof cell === 'string') return cell
  if (cell.value) return String(cell.value)
  return ''
}

/**
 * 日历格子是否要标「有录像」。
 *
 * <p>只对<b>已加载标记的那个月</b>生效：面板翻到别的月时标记是未知的，
 * 一律不高亮，否则会把本月的日期错标到相邻月份上。
 */
export function isRecordDay(cell, flagsMonth, recordDaySet) {
  const dateStr = cellDate(cell)
  if (!dateStr) return false
  if (dateStr.slice(0, 7) !== flagsMonth) return false
  return recordDaySet.has(Number(dateStr.slice(8, 10)))
}

/**
 * 日历里禁用「没有录像」的日期（返回 true = 灰掉、点不动）。
 *
 * <p>未加载标记的月份不禁用：让用户能翻月查看。
 */
export function isDisabledDate(date, flagsMonth, recordDaySet) {
  if (!date) return false
  const d = date instanceof Date ? date : new Date(date)
  if (Number.isNaN(d.getTime())) return false
  const ym = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`
  if (ym !== flagsMonth) return false
  return !recordDaySet.has(d.getDate())
}

/** {@code YYYYMMDDHHmmss} → {@code YYYY-MM-DD HH:mm:ss}；其它形态原样返回。 */
export function fmtCompactTs(v) {
  if (!v) return '—'
  const t = String(v).trim()
  if (t.length !== 14 || !/^\d{14}$/.test(t)) return t
  return `${t.slice(0, 4)}-${t.slice(4, 6)}-${t.slice(6, 8)} ${t.slice(8, 10)}:${t.slice(10, 12)}:${t.slice(12, 14)}`
}

/** {@code YYYYMMDDHHmmss} → {@link Date}；格式不符返回 {@code null}。 */
export function parseCompactTs(v) {
  const t = String(v || '').trim()
  if (!/^\d{14}$/.test(t)) return null
  return new Date(
    +t.slice(0, 4), +t.slice(4, 6) - 1, +t.slice(6, 8),
    +t.slice(8, 10), +t.slice(10, 12), +t.slice(12, 14)
  )
}

/** 文件大小 → {@code 1.2 MB} 形式；空值返回 {@code —}。 */
export function formatSize(size) {
  if (!size) return '—'
  const units = ['B', 'KB', 'MB', 'GB']
  let n = size
  let i = 0
  while (n >= 1024 && i < units.length - 1) {
    n /= 1024
    i++
  }
  return n.toFixed(i === 0 ? 0 : 1) + ' ' + units[i]
}

const UPLOAD_TEXT = { NONE: '无需上传', PENDING: '待上传', UPLOADED: '已上传', FAILED: '上传失败' }

/** 上传状态文案。 */
export function uploadText(state) {
  return UPLOAD_TEXT[state] || '—'
}

/** 上传状态对应的 el-tag 类型。 */
export function uploadColor(state) {
  if (state === 'UPLOADED') return 'success'
  if (state === 'PENDING') return 'warning'
  if (state === 'FAILED') return 'danger'
  return 'info'
}

/**
 * 这段录像能否下载。
 *
 * <p>两个条件缺一不可：{@code downloadUrl} 非空（后端只在文件确实存在时才回填）、
 * 且不在录制中（文件还在增长，下到一半的残缺文件没有意义）。
 */
export function canDownloadRecord(record) {
  return !!record && !!record.downloadUrl && !record.recording
}

/**
 * 下载一段录像。
 *
 * <p>本地录像的 {@code downloadUrl} 就是播放用的同源静态路径
 * （{@code /record/...}，由 nginx sendfile 提供），交给浏览器原生下载即可 ——
 * 有进度、能续传、不占内存。
 *
 * @param {object|null} record      录像记录
 * @param {string} channelName      通道名（用于拼下载文件名）
 */
export function downloadRecord(record, channelName) {
  if (!canDownloadRecord(record)) {
    ElMessage.warning('该录像暂不可下载（可能仍在录制或文件已丢失）')
    return
  }
  downloadFile(record.downloadUrl, recordFileName(record, channelName))
}
