/**
 * ws-flv 拉流引擎：WebSocket 收 FLV，视频走 MSE、G.711 音频走 Web Audio。
 *
 * <h3>为什么需要它</h3>
 * 国标设备的直播流是 <b>PS 封装 + G.711 音频</b>，ZLM 转成 FLV 后音频仍是
 * PCMA/PCMU。MSE 只认 AAC/MP3/Opus，所以 {@code mpegts.js}、{@code flv.js}、
 * {@code xgplayer-flv} 这类基于 MSE 的播放器一律抛 {@code CodecUnsupported}。
 *
 * <p>本模块把两条轨道拆开处理：
 * <ul>
 *   <li><b>视频</b>（H.264 / H.265）→ 复用 {@code xgplayer-transmuxer} 的
 *       fMP4 重封装 → 交给 MSE，启用浏览器硬解与零拷贝渲染；</li>
 *   <li><b>音频</b>（G.711）→ 查表解码成 PCM（见 {@code g711.js}）→
 *       交给 Web Audio 按时间戳排程播放，绕开 MSE 的编解码限制。</li>
 * </ul>
 * 这样既保住硬解画质与性能，又不用 WASM 软解整路流。
 *
 * <h3>音画同步</h3>
 * 以 <b>音频为主时钟</b>：音频按 PTS 精确排程，视频每 500ms 与音频对齐一次，
 * 偏差小于 200ms 用播放速率微调（不跳帧），超过 500ms 才硬跳。
 *
 * <h3>线程与生命周期</h3>
 * 全部在浏览器主线程，无 Worker。{@link destroy} 会关闭 WebSocket、
 * 停止音频排程、解绑 MSE 并释放 object URL，调用后实例不可复用。
 */

import { FlvDemuxer, FMP4Remuxer, AudioCodecType } from 'xgplayer-transmuxer'
import { decodeG711, G711_SAMPLE_RATE } from './g711'

/** 直播缓冲保留时长（秒）：超过就从 SourceBuffer 里清掉，防止内存涨到爆。 */
const BUFFER_KEEP_SECONDS = 30
/** 硬跳阈值（秒）：音画差超过它就直接改 currentTime。 */
const HARD_SYNC_THRESHOLD = 0.5
/** 软调阈值（秒）：超过它用播放速率追赶，避免频繁跳帧。 */
const SOFT_SYNC_THRESHOLD = 0.2
/** 音频提前排程量（秒）：留一点余量，避免第一个包就被判定为「已经迟到」。 */
const AUDIO_LEAD_SECONDS = 0.12
/** 音频最多领跑多少秒（超过说明时间戳异常，丢弃以压低延迟）。 */
const AUDIO_MAX_LEAD_SECONDS = 3

export default class WsFlvStream {
  /**
   * @param {object} opts
   * @param {string} opts.url WebSocket 地址（ZLM: ws://host/rtp/{流ID}.live.flv）
   * @param {MediaSource} opts.mediaSource 已与 video 元素绑定的 MediaSource
   * @param {HTMLVideoElement} opts.media 播放器持有的 video 元素
   * @param {(msg: string) => void} [opts.onError] 不可恢复的错误回调
   * @param {(info: object) => void} [opts.onStats] 播放信息回调（码率、分辨率等）
   */
  constructor({ url, mediaSource, media, onError, onStats }) {
    this.url = url
    this.mediaSource = mediaSource
    this.media = media
    this.onError = onError || (() => {})
    this.onStats = onStats || (() => {})

    this.destroyed = false
    this.ws = null
    this.retryCount = 0
    this.retryTimer = null

    // ---- 解封装 ----
    this.demuxer = new FlvDemuxer()
    this.remuxer = new FMP4Remuxer(this.demuxer.videoTrack, this.demuxer.audioTrack)
    /** 首个数据包之前是「不连续」的，对齐 BufferService 的初始状态。 */
    this.discontinuity = true
    this.contiguous = false
    this.mediaInfoSent = false

    // ---- 视频（MSE）----
    this.sourceBuffer = null
    this.sourceOpen = false
    this.appendQueue = []
    this.appending = false
    this.needInitSegment = true
    this.videoMime = ''

    // ---- 音频（Web Audio）----
    this.audioCtx = null
    /**
     * 独立增益节点。
     *
     * <p>G.711 走 Web Audio 输出，<b>不受 video 元素的 muted/volume 影响</b>，
     * 所以必须自己接一个 GainNode，否则播放器的音量按钮会失灵。
     */
    this.gainNode = null
    /** 当前增益（0~1），video 静音时为 0。 */
    this.volume = 0
    this.audioLaw = null
    /** 音频主时钟：流时间 0 对应的 AudioContext 时间戳。 */
    this.audioClockBase = null
    /** 首个音频包的 PTS（毫秒），后续包的时间都相对它计算。 */
    this.firstAudioPts = null
    /** 已排程到的流时间（秒），用于丢弃迟到/超前过量的包。 */
    this.audioScheduledTo = 0
    this.audioNodes = new Set()

    // ---- 同步 ----
    this.syncTimer = null

    this._bindMediaSource()
  }

  /** 建立连接。 */
  start() {
    if (this.destroyed) return
    this._connect()
    this.syncTimer = setInterval(() => this._syncAudioVideo(), 500)
  }

  // ==================== WebSocket ====================

  _connect() {
    if (this.destroyed) return
    let ws
    try {
      ws = new WebSocket(this.url)
    } catch (e) {
      this._fail(`WebSocket 建立失败：${e.message}`)
      return
    }
    ws.binaryType = 'arraybuffer'
    this.ws = ws

    ws.onmessage = (ev) => {
      if (this.destroyed || !(ev.data instanceof ArrayBuffer)) return
      this.retryCount = 0
      this._push(new Uint8Array(ev.data))
    }

    ws.onerror = () => {
      // onerror 之后必定跟 onclose，重连逻辑统一放 onclose，避免重复重连
    }

    ws.onclose = () => {
      this.ws = null
      if (this.destroyed) return
      // 直播断流很常见（设备重启、网络抖动），指数退避重连，上限 10 秒
      const delay = Math.min(500 * 2 ** this.retryCount, 10000)
      this.retryCount++
      this.retryTimer = setTimeout(() => this._connect(), delay)
    }
  }

  // ==================== 解封装与分发 ====================

  /**
   * 喂一段 FLV 字节。
   *
   * <p>{@link FlvDemuxer} 自己处理跨包边界（内部有 {@code _remainingData}），
   * 所以这里按 WebSocket 消息边界直接投喂即可。
   */
  _push(chunk) {
    if (this.destroyed || this.failed) return
    let tracks
    try {
      tracks = this.demuxer.demuxAndFix(chunk, this.discontinuity, this.contiguous, 0, false)
    } catch (e) {
      // 单个坏包不该打断整路播放，丢掉继续
      return
    }
    if (!tracks) return
    // 首包解析成功后转为「连续」，让 fixer 按连续时间戳处理
    this.discontinuity = false
    this.contiguous = true

    const videoTrack = this.demuxer.videoTrack
    const audioTrack = this.demuxer.audioTrack

    this._handleAudio(audioTrack)
    this._handleVideo(videoTrack)

    if (!this.mediaInfoSent && videoTrack.exist()) {
      this.mediaInfoSent = true
      this.onStats({
        codec: videoTrack.codec,
        width: videoTrack.width,
        height: videoTrack.height,
        fps: videoTrack.fps,
        hasAudio: !!this.audioLaw
      })
    }
  }

  /**
   * 音频分支。
   *
   * <p>G.711 的样本取走后，**把整条音频轨 reset 掉**——
   * 否则 {@code FMP4Remuxer.remux()} 里的 {@code hasAudio = audioTrack.exist()}
   * 仍为 true，生成的 init segment 会混入 G.711 音频轨，
   * 而 MSE 的视频 SourceBuffer 只接受视频轨，会直接报解码错误（表现为黑屏）。
   * AAC 等其他编码原样留给 MSE。
   */
  _handleAudio(audioTrack) {
    if (!audioTrack.samples.length) return

    const type = audioTrack.codecType
    const isAlaw = type === AudioCodecType.G711PCMA
    const isMulaw = type === AudioCodecType.G711PCMU
    if (!isAlaw && !isMulaw) return

    const law = isAlaw ? 'alaw' : 'mulaw'
    if (this.audioLaw !== law) {
      this.audioLaw = law
    }

    for (const sample of audioTrack.samples) {
      this._enqueueAudio(sample.pts, sample.data, law)
    }
    // 彻底清空音频轨：让 remux 看不到音频轨
    audioTrack.reset()
  }

  /** 视频分支：攒够一个周期就重封装成 fMP4 段。 */
  _handleVideo(videoTrack) {
    if (!this.sourceOpen || !videoTrack.hasSample()) return

    // 编码要等首个视频包解析完才知道，所以 SourceBuffer 在这里按需创建
    if (!this.sourceBuffer) {
      if (!videoTrack.codec) return
      if (!this._ensureSourceBuffer(videoTrack.codec)) {
        this._fail(
          `当前浏览器无法硬解该视频编码（${videoTrack.codec}）。` +
            'H.265 需要 Chrome/Edge 107+ 且系统具备 HEVC 解码能力。'
        )
        return
      }
      this.needInitSegment = true
    }

    let result
    try {
      // initMerge=false：init segment 只含视频轨。
      // 若用 initMerge=true，音频轨会被并进同一个 init segment，
      // 而 MSE 的视频 SourceBuffer 不接受音频轨描述符。
      result = this.remuxer.remux(this.needInitSegment, { initMerge: false })
    } catch (e) {
      return
    }
    if (result.videoInitSegment) {
      this.needInitSegment = false
      this._append(result.videoInitSegment)
    }
    if (result.videoSegment) this._append(result.videoSegment)
    if (result.audioSegment) this._append(result.audioSegment)
  }

  // ==================== MSE ====================

  _bindMediaSource() {
    const ms = this.mediaSource
    const onOpen = () => {
      if (this.destroyed) return
      this.sourceOpen = true
      // 首个数据包到达时才知道编码，这里先只标记就绪
    }
    if (ms.readyState === 'open') onOpen()
    else ms.addEventListener('sourceopen', onOpen, { once: true })
  }

  /**
   * 按实际编码创建 SourceBuffer。编码字符串来自解封装结果，必须动态生成。
   *
   * <p><b>H.265 的 codec 要把 {@code hev1} 换成 {@code hvc1}</b>：
   * {@code xgplayer-transmuxer} 生成 fMP4 时，sample entry 的 box 类型
   * 硬编码为 {@code hvc1}（见 {@code MP4.avc1hev1vvc1}），即参数集放在
   * init segment 里。若 SourceBuffer 的 mime 写成 {@code hev1}
   * （参数集在样本里），浏览器会发现「声明的格式」与「实际收到的格式」不符，
   * 直接抛 MEDIA_ERR_DECODE，画面全黑。
   */
  _ensureSourceBuffer(codec) {
    if (this.sourceBuffer) return true
    const mime = `video/mp4;codecs="${codec.replace(/^hev1/, 'hvc1')}"`
    if (!window.MediaSource || !MediaSource.isTypeSupported(mime)) return false
    try {
      const sb = this.mediaSource.addSourceBuffer(mime)
      sb.mode = 'segments'
      this.sourceBuffer = sb
      this.videoMime = mime
      return true
    } catch (e) {
      return false
    }
  }

  _append(data) {
    if (!data || !data.byteLength) return
    this.appendQueue.push(data)
    this._pump()
  }

  _pump() {
    const sb = this.sourceBuffer
    if (!sb || this.appending || sb.updating || !this.appendQueue.length) return
    if (this.mediaSource.readyState !== 'open') return
    const data = this.appendQueue.shift()
    this.appending = true
    sb.onupdateend = () => {
      this.appending = false
      this._evict()
      this._pump()
    }
    try {
      sb.appendBuffer(data)
    } catch (e) {
      this.appending = false
      // QuotaExceededError：清掉旧缓冲再重试
      if (e && e.name === 'QuotaExceededError') {
        this._evict(true)
        this.appendQueue.unshift(data)
      } else {
        this._fail('写入解码数据失败：' + (e && e.name ? e.name + ' - ' + e.message : e))
      }
    }
  }

  /** 清掉过旧的缓冲，直播只需要保留最近一小段。 */
  _evict(aggressive = false) {
    const sb = this.sourceBuffer
    const v = this.media
    if (!sb || sb.updating || !v || !v.buffered.length) return
    const start = v.buffered.start(0)
    const removeTo = v.currentTime - (aggressive ? 2 : BUFFER_KEEP_SECONDS)
    if (removeTo - start > 1) {
      try {
        sb.remove(start, removeTo)
      } catch (e) {
        // 忽略
      }
    }
  }

  // ==================== Web Audio ====================

  /**
   * 解码并把一段 G.711 挂到 AudioContext 的时间轴上。
   *
   * @param {number} pts 采样点的 PTS（单位是 audioTrack.timescale）
   * @param {Uint8Array} data 裸 G.711 字节
   * @param {'alaw'|'mulaw'} law 律制
   */
  _enqueueAudio(pts, data, law) {
    if (!data || !data.length) return
    const pcm = decodeG711(data, law)

    if (!this.audioCtx) {
      const Ctx = window.AudioContext || window.webkitAudioContext
      if (!Ctx) return
      this.audioCtx = new Ctx()
      this.gainNode = this.audioCtx.createGain()
      this.gainNode.gain.value = this.volume
      this.gainNode.connect(this.audioCtx.destination)
    }
    const ctx = this.audioCtx
    if (ctx.state === 'suspended') ctx.resume().catch(() => {})

    if (!this.audioInfoSent) {
      this.audioInfoSent = true
      this.onStats({
        audioCodec: law === 'alaw' ? 'PCMA' : 'PCMU',
        audioSampleRate: G711_SAMPLE_RATE
      })
    }

    if (this.firstAudioPts === null) this.firstAudioPts = pts
    // FLV 标签的时间戳单位是**毫秒**（不是采样点），所以除以 1000 换成秒
    const streamTime = (pts - this.firstAudioPts) / 1000

    if (this.audioClockBase === null) {
      // 第一段音频：留一点排程余量，之后所有时间都相对它计算
      this.audioClockBase = ctx.currentTime + AUDIO_LEAD_SECONDS
    }

    const when = this.audioClockBase + streamTime
    const now = ctx.currentTime
    // 迟到太多（例如重连后追帧）直接丢，否则会累积延迟
    if (when + 0.05 < now) return
    // 领跑太多说明时间戳跳变，也丢
    if (when - now > AUDIO_MAX_LEAD_SECONDS) return

    const buffer = ctx.createBuffer(1, pcm.length, G711_SAMPLE_RATE)
    buffer.copyToChannel(pcm, 0)

    const src = ctx.createBufferSource()
    src.buffer = buffer
    src.connect(this.gainNode)
    this.audioNodes.add(src)
    src.onended = () => this.audioNodes.delete(src)
    try {
      src.start(Math.max(when, now))
    } catch (e) {
      this.audioNodes.delete(src)
      return
    }
    this.audioScheduledTo = Math.max(this.audioScheduledTo, streamTime)
  }

  // ==================== 音画同步 ====================

  /** 以音频为主时钟校正视频：小幅微调速率，大幅直接跳。 */
  _syncAudioVideo() {
    const ctx = this.audioCtx
    const v = this.media
    if (!ctx || !v || this.audioClockBase === null || this.destroyed) return
    if (v.readyState < 2 || v.seeking || v.paused) return
    // 起播前 2 秒不校正：此时音频尚未真正出声，比较没有意义
    if (ctx.currentTime - this.audioClockBase < 2) return

    const audioTime = ctx.currentTime - this.audioClockBase
    const drift = v.currentTime - audioTime
    if (Math.abs(drift) > HARD_SYNC_THRESHOLD) {
      v.currentTime = audioTime
      v.playbackRate = 1
    } else if (Math.abs(drift) > SOFT_SYNC_THRESHOLD) {
      // 视频跑快了就放慢，跑慢了就加快，最多 ±10%
      v.playbackRate = 1 - Math.sign(drift) * 0.1
    } else if (v.playbackRate !== 1) {
      v.playbackRate = 1
    }
  }

  // ==================== 音量 ====================

  /**
   * 设置音量（0~1）。
   *
   * <p>外部应在 video 元素触发 {@code volumechange} 时调用，
   * 好让播放器的音量按钮对 Web Audio 这条链路同样生效。
   *
   * @param {number} value 0~1
   */
  setVolume(value) {
    this.volume = Math.max(0, Math.min(1, Number(value) || 0))
    if (this.gainNode) {
      // 用 setTargetAtTime 而不是直接赋值，避免音量突变产生爆音
      this.gainNode.gain.setTargetAtTime(this.volume, this.audioCtx.currentTime, 0.02)
    }
  }

  // ==================== 收尾 ====================

  _fail(msg) {
    if (this.destroyed || this.failed) return
    this.failed = true
    this.onError(msg)
  }

  destroy() {
    this.destroyed = true
    if (this.retryTimer) clearTimeout(this.retryTimer)
    if (this.syncTimer) clearInterval(this.syncTimer)
    this.retryTimer = null
    this.syncTimer = null

    if (this.ws) {
      this.ws.onclose = null
      this.ws.onmessage = null
      this.ws.onerror = null
      try {
        this.ws.close()
      } catch (e) {
        // 忽略
      }
      this.ws = null
    }

    for (const node of this.audioNodes) {
      try {
        node.stop()
      } catch (e) {
        // 忽略
      }
    }
    this.audioNodes.clear()
    if (this.audioCtx) {
      this.audioCtx.close().catch(() => {})
      this.audioCtx = null
    }

    const sb = this.sourceBuffer
    if (sb) sb.onupdateend = null
    this.appendQueue = []
    this.sourceBuffer = null

    if (this.mediaSource && this.mediaSource.readyState === 'open') {
      try {
        this.mediaSource.endOfStream()
      } catch (e) {
        // 忽略
      }
    }
  }
}
