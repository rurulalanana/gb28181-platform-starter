/**
 * 播放器行内诊断日志（FLV / MSE 通路）。
 *
 * video.buffered 在贴近直播边缘时会低估已解码区间（实测 buffered 到 264.6 而
 * currentTime 266.46、readyState=4 仍流畅），外部截图/录屏拿不到断流判据，
 * 所以记录每段 fMP4 的 PTS/DTS、样本数、tfdt 与 append 后的 buffered 区间。
 *
 * 控制台：gbFlvLog() / .gaps() / .dump() / .clear() / .enabled = false
 * 环形缓冲 1800 条，约覆盖 15 分钟，不会无限涨内存。
 *
 * @module FlvDiag
 */

const MAX_ENTRIES = 1800

/** 单条记录最多保留的 buffered 区间数：正常 1~2 段，十几段碎片时只需知道段数。 */
const MAX_RANGES = 6

const entries = []

// 默认开：出问题时才有数据可查
let enabled = true

// 日志里的 t 是相对这个时刻的毫秒数
const t0 = typeof performance !== 'undefined' ? performance.now() : Date.now()

function now() {
  const n = typeof performance !== 'undefined' ? performance.now() : Date.now()
  return Math.round(n - t0)
}

/**
 * 写一条记录。kind：seg / append / buffered / ev / evict / err / info
 *
 * @param {string} kind
 * @param {object} [data] 附加字段
 */
export function log(kind, data) {
  if (!enabled) return
  const rec = { t: now(), k: kind, ...(data || {}) }
  entries.push(rec)
  if (entries.length > MAX_ENTRIES) entries.splice(0, entries.length - MAX_ENTRIES)
}

/** buffered → {n: 区间数, r: 前几段 [start, end]}。 */
export function packRanges(b) {
  if (!b || !b.length) return { n: 0, r: [] }
  const r = []
  for (let i = 0; i < b.length && i < MAX_RANGES; i++) {
    r.push([+b.start(i).toFixed(2), +b.end(i).toFixed(2)])
  }
  return { n: b.length, r }
}

/**
 * 解析 fMP4 片段里 moof/traf/tfdt 的 baseMediaDecodeTime。
 *
 * tfdt 是 MSE 判定「这段数据接在哪」的唯一依据：算错 PTS 或归一化出错都表现为
 * tfdt 跳变，MSE 随后在 buffered 里划出空洞，播放到洞里就停。
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
 * 找出时间轴上的跳变：相邻两段 tfdt 之差与上一段媒体时长不一致超过 100ms 即视为空洞。
 *
 * @param {number} [limit] 最多返回几条（默认 30）
 */
export function gaps(limit = 30) {
  /*
   * 用 seg 记录而不是 append：sumDur 与 tfdt 都在同一条 seg 里。
   * 早先误用 append 导致时长恒为 0、每段都报 HOLE —— 诊断工具自己的 bug 最容易被当真。
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

/** 摘要。 */
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

/** 控制台入口：挂到 window.gbFlvLog，运维敲一行就能拿到判据。 */
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
