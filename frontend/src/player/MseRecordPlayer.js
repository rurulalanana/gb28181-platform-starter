/**
 * 云端录像播放器（Range 流式下载 + 浏览器端重封装 + 容错）。
 *
 * <h2>要解决的两个问题</h2>
 * <p><b>1. 坏帧导致浏览器解码 fatal</b>
 * <p>ZLM 录出的 H.265 MP4 里会夹着残缺帧（UDP 收流丢包 → PS 解封装失败 →
 * ZLM 仍把残帧写进文件）。文件结构合法、帧数也对，但码流里有
 * {@code Invalid NAL unit 0} / {@code VPS 0 does not exist} /
 * {@code Could not find ref with POC N}。ffmpeg 容错能跳过，但浏览器硬解不容错，
 * 原生 {@code <video src>} 直接 {@code PIPELINE_ERROR_DECODE}，只出首帧就停。
 *
 * <p><b>2. 整文件下载导致「一直加载中」</b>
 * <p>录像动辄几十上百 MB（实测一段 158 秒的 1440p 录像 57MB），
 * 整包 {@code fetch} 再解析会让用户干等半分钟以上。所以改成：
 * <ol>
 *   <li>先只取<b>文件尾部的 moov</b>（索引，几十 KB 到几 MB）→ 解析出每个样本的
 *       偏移/大小/是否关键帧；</li>
 *   <li>按 GOP 用 HTTP Range <b>只下载当前需要的片段</b>（几百 KB），边下边播。</li>
 * </ol>
 *
 * <h2>容错怎么做</h2>
 * <p>{@link Mp4Remuxer} 把 GOP 重新包装成 fMP4 片段（每个片段从 IDR 开始，
 * 彼此独立可解码）；某段解码报错就跳过它、重建 MediaSource 继续播。
 *
 * <h2>为什么不用现成播放器</h2>
 * <ul>
 *   <li>EasyPlayer.js：H.265 走 MSE，同样在坏帧处 fatal；WASM 只用于 FLV/裸流。</li>
 *   <li>jessibuca：WASM 软解能容错，但要 1MB 解码器，且已被上游移除。</li>
 *   <li>xgplayer：底层还是原生 video / MSE，没有这层容错。</li>
 * </ul>
 *
 * @module MseRecordPlayer
 */

import { Mp4Remuxer } from './Mp4Remuxer.js'

/** 重建 MediaSource 的最大次数，超过就放弃。 */
const MAX_REBUILD = 12

/** 探测 moov 时，首次取文件尾的字节数。 */
const MOOV_PROBE_BYTES = 64 * 1024

/** 探测 moov 时，单次最多放大到的字节数。 */
const MOOV_MAX_BYTES = 32 * 1024 * 1024

/** 提前下载的 GOP 数量（保证连续播放不卡顿）。 */
const PREFETCH_GOP = 2

/**
 * 按编码给出 MSE 的 MIME。
 *
 * <p>MIME 里的编码串必须和实际码流一致，否则 {@code addSourceBuffer}
 * 直接抛 {@code NotSupportedError}（H.264 内容用 hvc1 就是这种情况）。
 *
 * @param {string} codec {@code 'h264'} / {@code 'h265'}
 * @returns {string}
 */
function mimeFor(codec) {
  return codec === 'h264'
    ? 'video/mp4; codecs="avc1.64001F"'
    : 'video/mp4; codecs="hvc1.1.6.L93.B0"'
}

/**
 * 在字节块里找 box 的起始位置。
 *
 * @param {Uint8Array} buf
 * @param {string} type 四字符类型
 * @returns {{start:number, size:number}|null}
 */
function findBox(buf, type) {
  const c0 = type.charCodeAt(0)
  const c1 = type.charCodeAt(1)
  const c2 = type.charCodeAt(2)
  const c3 = type.charCodeAt(3)
  for (let i = 4; i + 4 <= buf.length; i++) {
    if (buf[i] === c0 && buf[i + 1] === c1 && buf[i + 2] === c2 && buf[i + 3] === c3) {
      const start = i - 4
      const size = ((buf[start] << 24) | (buf[start + 1] << 16) | (buf[start + 2] << 8) | buf[start + 3]) >>> 0
      if (size >= 8) {
        return { start, size }
      }
    }
  }
  return null
}

/**
 * 录像播放器。
 */
export class MseRecordPlayer {
  /**
   * @param {HTMLVideoElement} videoEl 承载播放的 video 元素
   * @param {object} [options]
   * @param {function} [options.onState] 状态回调（'加载中'/'播放中'/'容错重建'…）
   * @param {function} [options.onFatal] 彻底失败回调
   * @param {function} [options.onSkip]  跳过片段回调 (gopIndex, 原因)
   */
  constructor(videoEl, options = {}) {
    this.video = videoEl
    this.onState = options.onState || (() => {})
    this.onFatal = options.onFatal || (() => {})
    this.onSkip = options.onSkip || (() => {})

    /** 录像地址（已带 token） */
    this.url = ''
    this.fileSize = 0
    this.remuxer = null
    this.initSegment = null
    this.gops = []
    /** 已确认损坏、要跳过的 GOP 序号 */
    this.skipSet = new Set()
    /** 下一个要喂的 GOP 序号 */
    this.nextIndex = 0
    /** 已下载的 GOP 数据缓存：index -> Uint8Array */
    this.chunkCache = new Map()
    /** 正在下载的 GOP：index -> Promise */
    this.downloading = new Map()
    this.rebuilds = 0
    this.aborted = false
    this.started = false
    this.ms = null
    this.sb = null
    this.objectUrl = ''
    this._rebuilding = false
    /** 上一次上报的状态（避免重复回调「播放中」）。 */
    this._state = ''

    this._onError = this._onError.bind(this)
    /*
     * video 的 playing 事件是「真的开始出画面」的唯一可靠信号：
     * 首次起播、暂停后恢复、容错重建后重新起播都会触发。
     * 用它把状态改回「播放中」，否则「容错重建」会一直挂在界面上。
     */
    this._onPlaying = () => {
      if (this.aborted || this._state === 'playing') {
        return
      }
      this._state = 'playing'
      this.onState('播放中')
    }
    this.video.addEventListener('error', this._onError)
    this.video.addEventListener('playing', this._onPlaying)
  }

  /** 销毁并释放资源。 */
  destroy() {
    this.aborted = true
    this.video.removeEventListener('error', this._onError)
    this.video.removeEventListener('playing', this._onPlaying)
    try {
      if (this.ms && this.ms.readyState === 'open') {
        this.ms.endOfStream()
      }
    } catch (e) { /* 已关闭 */ }
    if (this.objectUrl) {
      URL.revokeObjectURL(this.objectUrl)
      this.objectUrl = ''
    }
    this.chunkCache.clear()
    this.downloading.clear()
    this.sb = null
    this.ms = null
    this.video.removeAttribute('src')
  }

  /**
   * 加载并开始播放。
   *
   * @param {string} url   录像文件地址
   * @param {string} [token] 登录 token
   */
  async start(url, token) {
    this.onState('加载中')
    const u = new URL(url, location.href)
    if (token) {
      u.searchParams.set('Authorization', token)
    }
    this.url = u.toString()

    // ---- 1) 先拿文件大小（HEAD 不下载 body）----
    this.fileSize = await this._probeSize()

    // ---- 2) 只取文件尾部的 moov ----
    this.onState('读取索引')
    const index = await this._fetchMoov()
    const remuxer = new Mp4Remuxer(index.bytes, index.offset)
    this.remuxer = remuxer
    this.initSegment = remuxer.initSegment()
    this.gops = remuxer.gops()
    if (!this.gops.length) {
      throw new Error('录像里没有可播放的画面')
    }

    // ---- 3) 挂上 MSE，开始按需下载 + 播放 ----
    await this._open()
    this.started = true
    this._feed()
  }

  /**
   * 探测文件大小。
   *
   * @returns {Promise<number>}
   */
  async _probeSize() {
    const resp = await fetch(this.url, { method: 'HEAD' })
    if (!resp.ok) {
      throw new Error('读取录像信息失败：HTTP ' + resp.status)
    }
    const len = Number(resp.headers.get('Content-Length') || 0)
    if (!len) {
      throw new Error('服务端未返回文件大小')
    }
    return len
  }

  /**
   * 取文件尾部的 moov。
   *
   * <p>ZLM 录出的 MP4 是 {@code ftyp + free + mdat + moov}，索引在最后。
   * 先取尾部 64KB，不够就翻倍再取，直到拿到完整的 moov。
   *
   * @returns {Promise<{bytes:Uint8Array, offset:number}>}
   */
  async _fetchMoov() {
    let take = Math.min(MOOV_PROBE_BYTES, this.fileSize)
    for (;;) {
      const offset = this.fileSize - take
      const bytes = await this._fetchRange(offset, this.fileSize - 1)
      const hit = findBox(bytes, 'moov')
      if (hit && hit.start + hit.size <= bytes.length) {
        // 拿到完整的 moov；把它单独切出来（附带前面的 ftyp 更好，但没有也能播）
        return { bytes: bytes.subarray(hit.start, hit.start + hit.size), offset: offset + hit.start }
      }
      if (take >= this.fileSize || take >= MOOV_MAX_BYTES) {
        throw new Error('找不到录像索引（moov），录像可能仍在录制中')
      }
      take = Math.min(take * 4, this.fileSize, MOOV_MAX_BYTES)
    }
  }

  /**
   * 用 HTTP Range 取一段字节。
   *
   * @param {number} start 起始偏移（含）
   * @param {number} end   结束偏移（含）
   * @returns {Promise<Uint8Array>}
   */
  async _fetchRange(start, end) {
    const resp = await fetch(this.url, { headers: { Range: `bytes=${start}-${end}` } })
    if (!resp.ok) {
      throw new Error('读取录像数据失败：HTTP ' + resp.status)
    }
    const buf = new Uint8Array(await resp.arrayBuffer())
    if (buf.length !== end - start + 1) {
      throw new Error(`录像数据长度不符（期望 ${end - start + 1}，实际 ${buf.length}）`)
    }
    return buf
  }

  /**
   * 下载一个 GOP 的数据（带缓存与去重）。
   *
   * @param {number} i GOP 序号
   * @returns {Promise<Uint8Array>}
   */
  _fetchGop(i) {
    if (this.chunkCache.has(i)) {
      return Promise.resolve(this.chunkCache.get(i))
    }
    if (this.downloading.has(i)) {
      return this.downloading.get(i)
    }
    const g = this.gops[i]
    const p = this._fetchRange(g.dataStart, g.dataEnd - 1)
      .then(data => {
        this.chunkCache.set(i, data)
        this.downloading.delete(i)
        return data
      })
      .catch(e => {
        this.downloading.delete(i)
        throw e
      })
    this.downloading.set(i, p)
    return p
  }

  /**
   * 建 MediaSource 并挂到 video 上。
   *
   * @returns {Promise<void>}
   */
  _open() {
    return new Promise((resolve, reject) => {
      const ms = new MediaSource()
      this.ms = ms
      this.objectUrl = URL.createObjectURL(ms)
      this.video.src = this.objectUrl
      ms.addEventListener('sourceopen', () => {
        try {
          /*
           * 'segments' 保留 fMP4 里的绝对时间戳 → 进度条可以任意拖动。
           * （'sequence' 会丢掉时间戳，拖动就失效。）
           */
          const sb = ms.addSourceBuffer(mimeFor(this.remuxer.codec))
          sb.mode = 'segments'
          this.sb = sb
          sb.addEventListener('updateend', () => {
            this._ensurePlayable()
            this._feed()
          })
          sb.addEventListener('error', () => {
            this._skipCurrent('SourceBuffer 错误')
          })
          sb.appendBuffer(this.initSegment)
          resolve()
        } catch (e) {
          reject(e)
        }
      }, { once: true })
      ms.addEventListener('error', () => reject(new Error('MediaSource 打开失败')), { once: true })
    })
  }

  /**
   * 喂片段：串行，一次一个，等 {@code updateend} 再喂下一个。
   *
   * <p>数据是按需下载的 —— 只下当前要播的（外加 {@link PREFETCH_GOP} 个预取），
   * 所以打开一段 57MB 的录像只需要下载 moov + 第一个 GOP（几百 KB）。
   */
  _feed() {
    const sb = this.sb
    if (this.aborted || this._rebuilding || !sb || sb.updating) {
      return
    }
    if (this.nextIndex >= this.gops.length) {
      try {
        if (this.ms && this.ms.readyState === 'open') {
          this.ms.endOfStream()
        }
      } catch (e) { /* 忽略 */ }
      return
    }

    const i = this.nextIndex
    if (this.skipSet.has(i)) {
      this.nextIndex++
      this._feed()
      return
    }

    // 预取后面的片段（不阻塞当前）
    for (let k = i + 1; k <= i + PREFETCH_GOP && k < this.gops.length; k++) {
      if (!this.chunkCache.has(k) && !this.downloading.has(k) && !this.skipSet.has(k)) {
        this._fetchGop(k).catch(() => { /* 失败时播放到那里再重试 */ })
      }
    }

    this.nextIndex++
    this._fetchGop(i).then(data => {
      if (this.aborted) {
        return
      }
      // 下载期间可能已经重建过，丢弃过期结果
      if (this.sb !== sb) {
        return
      }
      try {
        sb.appendBuffer(this.remuxer.fragment(this.gops[i], i + 1, data))
      } catch (e) {
        if (e.name === 'QuotaExceededError') {
          // 缓冲满了：丢掉最早的一段，稍后重试本片段
          this.nextIndex--
          try {
            if (sb.buffered.length) {
              sb.remove(0, Math.max(0, sb.buffered.start(0) + 20))
            }
          } catch (e2) { /* 忽略 */ }
        } else {
          this.skipSet.add(i)
          this.onSkip(i, 'append 失败：' + e.name)
          this._feed()
        }
      }
    }).catch(e => {
      if (this.aborted) {
        return
      }
      this.skipSet.add(i)
      this.onSkip(i, '下载失败：' + e.message)
      this._feed()
    })
  }

  /**
   * 让播放位置落在已缓冲区间内，并保持播放。
   *
   * <p>跳过某段之后 {@code buffered} 起点会晚于 0，
   * {@code currentTime} 停在 0 会永远等不到数据（画面全黑、进度条不动）。
   */
  _ensurePlayable() {
    const sb = this.sb
    const v = this.video
    if (!sb || !v) {
      return
    }
    try {
      if (!sb.buffered.length) {
        return
      }
      const start = sb.buffered.start(0)
      const end = sb.buffered.end(sb.buffered.length - 1)
      if (v.currentTime < start || v.currentTime > end) {
        v.currentTime = start
      }
    } catch (e) { /* 还没准备好 */ }
    if (v.paused) {
      v.play().catch(() => { /* 需要用户手势时由用户点播放键 */ })
    }
    /*
     * 把状态改回「播放中」。
     *
     * <p>不能只靠 video 的 {@code playing} 事件：MSE 重建后 video 元素本身
     * 没有经历 paused → playing 的转换（只是换了 src），事件不会重复触发，
     * 「容错重建」就会一直挂在界面上。
     *
     * <p>这里用 {@code buffered.length}（真有数据可播）判断，
     * 而不是 {@code readyState}（重建后可能还停在 HAVE_METADATA）。
     */
    if (this._state !== 'playing' && sb.buffered.length) {
      this._state = 'playing'
      this.onState('播放中')
    }
  }

  /**
   * 按时间找到对应的 GOP 序号。
   *
   * @param {number} time 秒
   * @returns {number}
   */
  gopIndexAt(time) {
    const ts = this.remuxer.timescale
    let acc = 0
    for (let i = 0; i < this.gops.length; i++) {
      acc += this.gops[i].duration / ts
      if (time < acc) {
        return i
      }
    }
    return this.gops.length - 1
  }

  /**
   * 跳过「当前正在播放的」那个片段并重建播放。
   *
   * <p><b>为什么按 currentTime 归因</b>：MSE 边喂边播，解码只发生在播放位置附近。
   * 坏片段可能在喂进去很久之后、播到它时才报错 —— 那时 {@code nextIndex}
   * 早就跑到很后面了，按它归因会误删好片段、真正的坏片段却一直留着。
   *
   * @param {string} reason 原因
   */
  _skipCurrent(reason) {
    if (this.aborted) {
      return
    }
    const bad = this.gopIndexAt(this.video.currentTime || 0)
    if (!this.skipSet.has(bad)) {
      this.skipSet.add(bad)
      this.onSkip(bad, reason)
    }
    this.rebuilds++
    if (this.rebuilds > MAX_REBUILD) {
      this.onFatal('解码失败次数过多：' + reason)
      return
    }
    this.onState('容错重建')
    this._rebuild()
  }

  /** 重建 MediaSource（video 报解码错误后，旧 SourceBuffer 就不能再用）。 */
  async _rebuild() {
    if (this._rebuilding || this.aborted) {
      return
    }
    this._rebuilding = true
    // 重建后要重新判断「是否在播」，否则状态会一直停在「容错重建」
    this._state = ''
    try {
      if (this.sb) {
        try { this.sb.abort() } catch (e) { /* 忽略 */ }
      }
      if (this.objectUrl) {
        URL.revokeObjectURL(this.objectUrl)
      }
      this.video.removeAttribute('src')
      this.sb = null
      // 从出问题的片段之后继续（坏的那段已进 skipSet）
      this.nextIndex = Math.min(this.gopIndexAt(this.video.currentTime || 0) + 1, this.gops.length)
      await this._open()
      this._rebuilding = false
      this._feed()
    } catch (e) {
      this._rebuilding = false
      this.onFatal('重建播放失败：' + e.message)
    }
  }

  /** video 元素报错（解码错误）。 */
  _onError() {
    if (this.aborted || !this.started) {
      return
    }
    this._skipCurrent('解码错误')
  }

  /**
   * 跳到指定时间（秒）。
   *
   * @param {number} time 秒
   */
  seek(time) {
    const v = this.video
    if (!Number.isFinite(v.duration)) {
      return
    }
    const target = Math.max(0, Math.min(time, v.duration - 0.1))
    const targetGop = this.gopIndexAt(target)
    if (targetGop < this.nextIndex) {
      // 已喂过的范围：直接跳
      try { v.currentTime = target } catch (e) { /* 忽略 */ }
    } else {
      // 还没喂到：把指针拨过去，让 _feed 从那里继续下载
      this.nextIndex = targetGop
      this._feed()
    }
  }
}
