/**
 * 播放器行内诊断日志（FLV / MSE 通路）。
 *
 * <h2>为什么需要它</h2>
 *
 * 用户反馈「FLV 播一下就卡住」，但**外部观测（Playwright / 截图 / 录屏）
 * 拿不到判据**：
 * <ul>
 *   <li>{@code video.buffered} 在贴近直播边缘时会<b>低估</b>已解码区间
 *       （实测 {@code buffered=[[236.8,264.6]]} 而 {@code currentTime=266.46}、
 *       {@code readyState=4}、播放流畅），只看它会把正常播放误判成断流；</li>
 *   <li>测试页常常在后台标签，Chrome 会挂起静音视频，卡顿数据不可信；</li>
 *   <li>真正的断点（时间戳跳变、段内空洞、append 阻塞）都在播放器内部。</li>
 * </ul>
 *
 * 所以必须把<b>播放器内部的时间轴</b>记下来：每一段 fMP4 的
 * PTS/DTS 首尾、样本数、写的 tfdt、append 后的 buffered 区间。
 * 时间轴有洞还是没洞，一眼就能看出来。
 *
 * <h2>怎么用</h2>
 *
 * <pre>
 *   // 浏览器控制台
 *   gbFlvLog()            // 打印摘要 + 最近记录
 *   gbFlvLog.gaps()       // 只列出「时间轴跳变」
 *   gbFlvLog.dump()       // 导出完整 JSON（可复制）
 *   gbFlvLog.clear()      // 清空
 *   gbFlvLog.enabled = false   // 关掉（默认开，环形容量有限）
 * </pre>
 *
 * <h2>容量</h2>
 *
 * 环形缓冲 {@link MAX_ENTRIES} 条，超出丢最旧的。按 1 段/秒 + 每段 2 条记录
 * 估算，可覆盖约 15 分钟。不会无限涨内存。
 *
 * @module FlvDiag
 */

/** 环形缓冲容量（条）。 */
const MAX_ENTRIES = 1800

/**
 * 单条记录里同时保留的最大 buffered 区间数。
 *
 * <p>正常只有 1~2 段；出现十几个碎片时只需要知道「有多少段」，
 * 全部存下来没意义还占内存。
 */
const MAX_RANGES = 6

/** 记录条目。 */
const entries = []

/** 是否记录。默认开：出问题时才有数据可查。 */
let enabled = true

/** 起始时刻（performance.now），日志里的 t 都是相对它的毫秒数。 */
const t0 = typeof performance !== 'undefined' ? performance.now() : Date.now()

/** 当前相对时刻（毫秒，整数）。 */
function now() {
  const n = typeof performance !== 'undefined' ? performance.now() : Date.now()
  return Math.round(n - t0)
}

/**
 * 写一条记录。
 *
 * @param {string} kind 类型：`seg` / `append` / `buffered` / `ev` / `evict` / `err` / `info`
 * @param {object} [data] 附加字段
 */
export function log(kind, data) {
  if (!enabled) return
  const rec = { t: now(), k: kind, ...(data || {}) }
  entries.push(rec)
  if (entries.length > MAX_ENTRIES) entries.splice(0, entries.length - MAX_ENTRIES)
}

/**
 * 把 buffered 压成紧凑形式。
 *
 * @param {TimeRanges} b
 * @returns {{n:number, r:number[][]}} 区间数 + 前几段 `[start, end]`
 */
export function packRanges(b) {
  if (!b || !b.length) return { n: 0, r: [] }
  const r = []
  for (let i = 0; i < b.length && i < MAX_RANGES; i++) {
    r.push([+b.start(i).toFixed(2), +b.end(i).toFixed(2)])
  }
  return { n: b.length, r }
}

/**
 * 解析 fMP4 片段里 {@code moof/traf/tfdt} 的 baseMediaDecodeTime。
 *
 * <p><b>为什么值得自己解析</b>：这是 MSE 判定「这段数据接在哪」的<b>唯一依据</b>。
 * 分段逻辑算错 PTS、或 fixer 归一化出错，最终都体现为 tfdt 跳变 ——
 * 而 tfdt 一跳，MSE 就在 buffered 里划出一道空洞，播放到洞里就停。
 *
 * @param {Uint8Array} u8 一段 fMP4（moof + mdat）
 * @returns {number|null} 解码时间（毫秒）；解析失败返回 null
 */
export function readTfdt(u8) {
  if (!u8 || u8.length < 16) return null
  const dv = new DataView(u8.buffer, u8.byteOffset, u8.byteLength)
  let o = 0
  while (o + 8 <= u8.length) {
    const size = dv.getUint32(o)
    const type = String.fromCharCode(u8[o + 4], u8[o + 5], u8[o + 6], u8[o + 7])
    if (size < 8) return null
    if (type === 'moof') {
      let p = o + 8
      while (p + 8 <= o + size) {
        const s2 = dv.getUint32(p)
        const t2 = String.fromCharCode(u8[p + 4], u8[p + 5], u8[p + 6], u8[p + 7])
        if (s2 < 8) return null
        if (t2 === 'traf') {
          let q = p + 8
          while (q + 8 <= p + s2) {
            const s3 = dv.getUint32(q)
            const t3 = String.fromCharCode(u8[q + 4], u8[q + 5], u8[q + 6], u8[q + 7])
            if (s3 < 8) return null
            if (t3 === 'tfdt') {
              const ver = u8[q + 8]
              return ver === 1 ? Number(dv.getBigUint64(q + 12)) : dv.getUint32(q + 12)
            }
            q += s3
          }
        }
        p += s2
      }
      return null
    }
    o += size
  }
  return null
}

/** 时间轴跳变容忍度（毫秒）：2 帧的抖动不算洞。 */
const GAP_TOLERANCE_MS = 100

/**
 * 找出时间轴上的「跳变」。
 *
 * <p>判据：相邻两段的 {@code tfdt} 之差不等于前一段的媒体时长，
 * 差值超过 {@link GAP_TOLERANCE_MS} 即为空洞。
 *
 * @param {number} [limit] 最多返回几条（默认 30）
 */
export function gaps(limit = 30) {
  /*
   * 用 {@code seg} 记录（不是 {@code append}）：
   * 时长 {@code sumDur} 与 {@code tfdt} 都在同一条 {@code seg} 里，
   * 而 {@code append} 只有 buffered 快照。早先误用 append，导致
   * 时长恒为 0、每段都报 HOLE —— 诊断工具自己的 bug 最容易被当真。
   */
  const segs = entries.filter((e) => e.k === 'seg' && e.tfdt != null)
  const durOf = (e) => (Number.isFinite(e.sumDur) ? e.sumDur : 0)
  const out = []
  for (let i = 1; i < segs.length; i++) {
    const prev = segs[i - 1]
    const cur = segs[i]
    const prevDur = durOf(prev)
    const expected = prev.tfdt + prevDur
    const diff = cur.tfdt - expected
    if (Math.abs(diff) > GAP_TOLERANCE_MS) {
      out.push({
        t: cur.t,
        from: +prev.tfdt.toFixed(1),
        prevDur: +prevDur.toFixed(1),
        expect: +expected.toFixed(1),
        actual: +cur.tfdt.toFixed(1),
        diff: +diff.toFixed(1),
        kind: diff > 0 ? 'HOLE' : 'OVERLAP'
      })
      if (out.length >= limit) break
    }
  }
  return out
}

/** 打印摘要。 */
function summary() {
  const byKind = {}
  for (const e of entries) byKind[e.k] = (byKind[e.k] || 0) + 1
  const appends = entries.filter((e) => e.k === 'append')
  const waits = entries.filter((e) => e.k === 'ev' && e.ev === 'waiting')
  const errs = entries.filter((e) => e.k === 'err')
  const spans = entries.filter((e) => e.k === 'buffered').map((e) => e.n)
  return {
    记录数: entries.length,
    按类型: byKind,
    append次数: appends.length,
    最近tfdt: appends.length ? appends[appends.length - 1].tfdt : null,
    buffered区间数分布: summarizeNums(spans),
    waiting次数: waits.length,
    waiting时刻: waits.slice(-10).map((w) => ({ t: w.t, ct: w.ct, rs: w.rs })),
    错误: errs.slice(-10),
    时间轴跳变: gaps(10)
  }
}

/** 数字数组压缩成 {min,max,常见值}。 */
function summarizeNums(arr) {
  if (!arr.length) return null
  const cnt = {}
  for (const n of arr) cnt[n] = (cnt[n] || 0) + 1
  return {
    min: Math.min(...arr),
    max: Math.max(...arr),
    分布: cnt
  }
}

/**
 * 控制台入口。
 *
 * <p>挂到 {@code window} 上，用户/运维不用装任何东西，出问题时在
 * 控制台敲一行就能拿到判据，复制给我即可。
 */
export function installGlobalLog() {
  if (typeof window === 'undefined') return
  const fn = () => {
    const s = summary()
    // eslint-disable-next-line no-console
    console.log('[gbFlvLog] 摘要', s)
    // eslint-disable-next-line no-console
    console.table(entries.slice(-40))
    return s
  }
  fn.dump = () => JSON.parse(JSON.stringify(entries))
  fn.gaps = gaps
  fn.clear = () => {
    entries.length = 0
    return '已清空'
  }
  fn.entries = entries
  Object.defineProperty(fn, 'enabled', {
    get: () => enabled,
    set: (v) => {
      enabled = !!v
    }
  })
  window.gbFlvLog = fn
}
