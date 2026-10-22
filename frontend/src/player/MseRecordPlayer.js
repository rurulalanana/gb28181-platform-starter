/**
 * 云端录像播放器（浏览器端重封装 + 容错）。
 *
 * <h2>要解决的问题</h2>
 * <p>ZLM 录出的 H.265 MP4 里会夹着<b>残缺帧</b>（UDP 收流丢包 →
 * PS 解封装失败 → ZLM 仍把残帧写进文件）。文件结构完全合法、帧数也对，
 * 但码流里有 {@code Invalid NAL unit 0} / {@code VPS 0 does not exist} /
 * {@code Could not find ref with POC N} 这类错误。
 *
 * <p>ffmpeg 容错能跳过继续解，但<b>浏览器硬解不容错</b>：
 * 原生 {@code <video src="xxx.mp4">} 直接 {@code PIPELINE_ERROR_DECODE}，
 * 只出首帧就停。
 *
 * <h2>怎么解</h2>
 * <ol>
 *   <li>把整个 MP4 拉下来（后端接口本来就支持 HTTP Range）；</li>
 *   <li>{@link Mp4Remuxer} 在浏览器里<b>只换容器、不转码</b>，
 *       按 GOP 切成 fMP4 片段（每个片段从 IDR 开始 → 彼此独立可解码）；</li>
 *   <li>逐片段喂给 {@code SourceBuffer}；</li>
 *   <li>解码报错时，按 {@code currentTime} 定位<b>当前正在播的那个片段</b>
 *       并跳过它，重建 MediaSource 继续播后面的内容。</li>
 * </ol>
 *
 * <p>实测（真 Chrome 158，2560x1440 H.265，44.6s，含坏帧）：
 * 跳过第 1 个 GOP 后可以从 1.5s 一路播到 44.6s，全程无错误。
 *
 * <h2>为什么不用现成播放器</h2>
 * <ul>
 *   <li>EasyPlayer.js：H.265 走 MSE，同样在坏帧处 fatal；WASM 只用于 FLV/裸流。</li>
 *   <li>jessibuca：WASM 软解能容错，但要 1MB 解码器，且已被上游移除。</li>
 *   <li>xgplayer：底层还是原生 video / MSE，没有这层容错。</li>
 * </ul>
 * <p>所以只在<b>播放端</b>加一层重封装 + 跳帧，不需要服务端转码，
 * 也不需要用户改收流协议（UDP/TCP 都一样）。
 *
 * @module MseRecordPlayer
 */

import { Mp4Remuxer } from './Mp4Remuxer.js'

/** 每次重建 MediaSource 的最大次数，超过就放弃。 */
const MAX_REBUILD = 12

/**
 * 按编码给出 MSE 的 MIME。
 *
 * <p>MIME 里的编码串必须和实际码流一致，否则 {@code addSourceBuffer}
 * 会直接抛 {@code NotSupportedError}（H.264 内容用 hvc1 就是这种情况）。
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
 * 录像播放器。
 */
export class MseRecordPlayer {
  /**
   * @param {HTMLVideoElement} videoEl 承载播放的 video 元素
   * @param {object} [options]
   * @param {function} [options.onState] 状态回调（'加载中'/'播放中'/'容错重建'…）
   * @param {function} [options.onFatal] 彻底失败回调（重建次数耗尽）
   * @param {function} [options.onSkip]  跳过片段回调 (gopIndex, 原因)
   */
  constructor(videoEl, options = {}) {
    this.video = videoEl
    this.onState = options.onState || (() => {})
    this.onFatal = options.onFatal || (() => {})
    this.onSkip = options.onSkip || (() => {})

    this.remuxer = null
    this.initSegment = null
    this.gops = []
    /** 已确认损坏、要跳过的 GOP 序号 */
    this.skipSet = new Set()
    /** 下一个要喂的 GOP 序号 */
    this.nextIndex = 0
    this.rebuilds = 0
    this.aborted = false
    this.playing = false
    this.ms = null
    this.sb = null
    this.objectUrl = ''
    this._rebuilding = false

    this._onError = this._onError.bind(this)
    this.video.addEventListener('error', this._onError)
  }

  /** 销毁并释放资源。 */
  destroy() {
    this.aborted = true
    this.video.removeEventListener('error', this._onError)
    try {
      if (this.ms && this.ms.readyState === 'open') {
        this.ms.endOfStream()
      }
    } catch (e) { /* 已关闭 */ }
    if (this.objectUrl) {
      URL.revokeObjectURL(this.objectUrl)
      this.objectUrl = ''
    }
    this.sb = null
    this.ms = null
    this.video.removeAttribute('src')
  }

  /**
   * 加载并开始播放。
   *
   * @param {string} url   录像文件地址（{@code /api/v1/cloudrecord/file/{recordId}}）
   * @param {string} [token] 登录 token（拼在 query 上，媒体请求带不了自定义头）
   */
  async start(url, token) {
    this.onState('加载中')
    const u = new URL(url, location.href)
    if (token) {
      u.searchParams.set('Authorization', token)
    }
    const resp = await fetch(u.toString())
    if (!resp.ok) {
      throw new Error('加载录像失败：HTTP ' + resp.status)
    }
    const bytes = new Uint8Array(await resp.arrayBuffer())

    const remuxer = new Mp4Remuxer(bytes)
    this.remuxer = remuxer
    this.initSegment = remuxer.initSegment()
    this.gops = remuxer.gops()
    if (!this.gops.length) {
      throw new Error('录像里没有可播放的画面')
    }

    await this._open()
    this.playing = true
    this._feed()
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
           * 用 'segments' 保留 fMP4 里的绝对时间戳 —— 这样进度条可以任意拖动。
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
            // SourceBuffer 级错误：多半是某段数据有问题，按当前播放位置跳过
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

  /** 依次喂片段（串行，等 updateend 再喂下一个）。 */
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
    this.nextIndex++
    try {
      sb.appendBuffer(this.remuxer.fragment(this.gops[i], i + 1))
    } catch (e) {
      if (e.name === 'QuotaExceededError') {
        // 缓冲满了：丢掉最早的一段再重试本次
        this.nextIndex--
        try {
          if (sb.buffered.length) {
            sb.remove(0, Math.max(0, sb.buffered.start(0) + 20))
          }
        } catch (e2) { /* 忽略 */ }
      } else {
        this.skipSet.add(i)
        this.onSkip(i, 'append 失败：' + e.name)
      }
    }
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
   * <p><b>为什么按 currentTime 归因，而不是「最后喂进去的片段」</b>：
   * MSE 是<b>边喂边播</b>，而且解码只发生在播放位置附近。
   * 也就是说坏片段可能在喂进去很久之后、播到它时才报错 ——
   * 那时 {@code nextIndex} 早就跑到很后面了，按它归因会误删好片段、
   * 真正的坏片段却一直留着，表现为「一直重建、始终播不出来」。
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

  /** 重建 MediaSource（一旦 video 报解码错误，旧的 SourceBuffer 就不能再用了）。 */
  async _rebuild() {
    if (this._rebuilding || this.aborted) {
      return
    }
    this._rebuilding = true
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
    if (this.aborted || !this.playing) {
      return
    }
    this._skipCurrent('解码错误')
  }

  /**
   * 跳到指定时间（秒）。
   *
   * <p>目标位置之前的数据可能因为跳过坏片段而不存在 —— 这种情况下
   * 会从最近的已缓冲位置开始，后续片段会陆续喂进来。
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
    // 已经喂过的范围才能直接跳；没喂到的部分把 nextIndex 拨过去
    if (targetGop < this.nextIndex) {
      try { v.currentTime = target } catch (e) { /* 忽略 */ }
    } else {
      this.nextIndex = targetGop
      this._feed()
    }
  }
}
