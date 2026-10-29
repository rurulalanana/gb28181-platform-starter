/**
 * FLV 拉流引擎：收 FLV（WebSocket 或 HTTP），视频走 MSE、G.711 音频走 Web Audio。
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
 * <h3>两种承载：WebSocket 与 HTTP</h3>
 * ZLM 同时提供 {@code ws://…/{流ID}.live.flv} 与 {@code http://…/{流ID}.live.flv}，
 * 两者是<b>同一路 FLV 字节流</b>，只有传输层不同。既然解封装、重封装、
 * G.711 解码的代码完全一样，就<b>只保留一套引擎</b>、按 URL 协议分支取数据
 * （见 {@link WsFlvStream#_connect}）。
 *
 * <p>HTTP 通路的价值：企业网络里 WebSocket 常被反向代理 / 防火墙拦掉
 * （升级握手 101 被改写），而普通 HTTP 长连接几乎总能通过。
 *
 * <h3>音画同步</h3>
 * 以 <b>音频为主时钟</b>：音频按 PTS 精确排程，视频每 500ms 与音频对齐一次，
 * 偏差小于 200ms 用播放速率微调（不跳帧），超过 500ms 才硬跳。
 *
 * <h3>线程与生命周期</h3>
 * 全部在浏览器主线程，无 Worker。{@link destroy} 会关闭连接（WebSocket 或
 * fetch 的 AbortController）、停止音频排程、解绑 MSE 并释放 object URL，
 * 调用后实例不可复用。
 */

import { FlvDemuxer, FMP4Remuxer, AudioCodecType } from 'xgplayer-transmuxer'
import { decodeG711, G711_SAMPLE_RATE } from './g711'

/** 直播缓冲保留时长（秒）：超过就从 SourceBuffer 里清掉，防止内存涨到爆。 */
const BUFFER_KEEP_SECONDS = 30

/**
 * 起播前要求的最小缓冲（秒）。
 *
 * <p><b>为什么必须预缓冲</b>：改造前播放器是 {@code autoplay} —— 拿到第一个
 * 关键帧、buffer 里有 0.1 秒数据就开始播。在带宽充足的局域网里没问题，
 * 但只要「到达速率」略低于「码率」，buffer 就<b>永远贴着 0</b>：
 * <pre>
 *   实测弱网（带宽 500 kbps / 码率 850 kbps）：
 *   buffered 余量长期 0.11 ~ 0.64 秒
 *   25 秒内出现 15 次 waiting（= 用户看到的一卡一卡）
 * </pre>
 * 因为只要网络有 0.2 秒的间隙，那 0.1 秒的缓冲就见底了。
 *
 * <p>先攒够 {@code PREBUFFER_SECONDS} 再起播，等于把「抖动容忍」从 0.1 秒
 * 提到 2 秒 —— 常见的一两秒网络抖动就完全无感了。
 *
 * <p><b>代价是起播变慢</b>：低码率流攒 2 秒约需 2~4 秒。监控场景下
 * 「晚 2 秒出画面」远比「画面一卡一卡」可接受，所以取 2 秒而不是更低。
 */
const PREBUFFER_SECONDS = 2

/**
 * 预缓冲的最长等待（毫秒）。
 *
 * <p>带宽严重不足时可能很久都攒不够 2 秒。不能让用户对着黑屏干等 ——
 * 超时后无论攒了多少都起播（哪怕只有 0.3 秒），画面卡但至少能看到内容，
 * 同时由 {@code onStall} 那条看门狗继续兜底。
 */
const PREBUFFER_TIMEOUT_MS = 8000

/**
 * 待写入队列的上限（段数）。
 *
 * <p>正常播放时队列只有 0~2 段（上一段 append 完成才取下一条）。持续积压
 * 说明媒体源<b>已经不再消费数据</b>（video 进入错误态、readyState 变成
 * ended/closed）。此时无限堆积只会把内存拖垮，用户看到的是整个页面卡死，
 * 比直接报错停下更糟。
 */
const MAX_APPEND_QUEUE = 256
/** 硬跳阈值（秒）：音画差超过它就直接改 currentTime。 */
const HARD_SYNC_THRESHOLD = 0.5
/** 软调阈值（秒）：超过它用播放速率追赶，避免频繁跳帧。 */
const SOFT_SYNC_THRESHOLD = 0.2

/* ==================== 直播追帧（延迟控制） ==================== */

/**
 * 稳态目标延迟（秒）：播放位置与「已到达的最新数据」之间保留的余量。
 *
 * <p><b>为什么需要追帧</b>：MSE 的 {@code currentTime} 只会随播放自然推进，
 * 而 ZLM 持续以实时速度推数据，两者的差值（缓冲余量）只会越积越多 ——
 * 表现为「越看越延迟」。实测不追帧时，看 10 分钟能积出 8~15 秒延迟，
 * 云台控制后要等十几秒画面才动。
 *
 * <p><b>为什么留 1 秒而不是 0.6 秒</b>：ZLM 是<b>按批</b>落盘的 —— 一次
 * append 往往带来 0.5~2 秒的数据，因此 {@code buffered.end - currentTime}
 * 本身就在 0.1~2.4 秒之间<b>剧烈抖动</b>（实测 80 个采样点，标准差 &gt; 0.6）。
 * 目标定 0.6 时，正常抖动就会被误判成「余量过低」而反复降速，缓冲反而
 * 更容易被吃穿。1 秒既能跨过一个 GOP 边界的到达缺口，又让端到端延迟
 * 维持在 2 秒内。
 */
const LIVE_TARGET_LATENCY = 1.0

/**
 * 硬跳阈值（秒）：缓冲余量超过它就丢帧直接跳到边缘前 {@link LIVE_TARGET_LATENCY}。
 *
 * <p><b>这个值直接决定「OSD 会不会往前跳」</b>。OSD 是设备烧进画面的，
 * 它跟着帧走 —— 只要没有丢帧，OSD 就只会一秒一秒地走；一旦发生向前 seek，
 * 用户就会看到画面卡一下、右上角时间猛地跳到几秒之后。
 *
 * <p>因此它的取值不能只看「延迟」，要看「什么情况下丢帧用户能接受」：
 * <ul>
 *   <li>正常观看时<b>一次都不应该丢</b>（哪怕延迟多一两秒）；</li>
 *   <li>只有从后台切回来、或系统卡顿过，积压了几十秒时才值得一次跳完。</li>
 * </ul>
 * 实测正常观看的缓冲余量在 0.1~2.4 秒之间抖动（ZLM 按批落盘），
 * 取 5 秒就完全盖住了这个抖动区间 —— 日常零跳帧，只有真正的积压才触发。
 *
 * <p>历史：取 1.6 时 40 秒跳了 26 次，取 3.0 时仍有 1 次；
 * 两者都会让 OSD “往前跳”，所以抬高到 5.0。
 */
const LIVE_MAX_LATENCY = 5.0

/**
 * 从后台切回来时允许的硬跳阈值（秒）：比常驻阈值宽松。
 *
 * <p>后台标签下 Chrome 会挂起哑音视频（本项目的音频走 Web Audio、
 * video 元素恒为静音），而 ZLM 这期间一直在推 —— 切回来时往往积压
 * 十几秒到几十秒。这种场景用户<b>预期</b>画面会跳到最新，不跳反而奇怪。
 *
 * <p>0.5 秒而不是 0：后台停留几秒、余量只多了 0.5 秒以上才值得跳，
 * 否则一次短暂切窗回来也要丢一帧，白白让 OSD 跳一下。
 */
const LIVE_RESUME_JUMP_LATENCY = 0.5

/**
 * 页面隐藏超过多久，切回来时才允许「立即硬跳」（毫秒）。
 *
 * <p>切个窗口看资料、几秒钟就回来，不该因此丢帧；离开 3 秒以上，
 * 积压已经明显到值得一次追齐。
 */
const LIVE_HIDDEN_RESUME_MS = 3000

/**
 * 两次硬跳之间的最小间隔（毫秒）。
 *
 * <p>突发到达会让余量反复越线，没有冷却就会连续跳 —— 用户看到的是
 * 「画面一顿一顿地往前跳」。
 */
const LIVE_JUMP_COOLDOWN_MS = 5000

/**
 * 软追帧倍速：余量在 {@code [目标, 硬跳阈值]} 之间时用它慢慢吃掉。
 *
 * <p>取 1.08 而不是更大：1.08 倍速在监控画面上几乎看不出（人眼对监控这类
 * 静态场景的匀速变化不敏感），但 10 秒就能吃掉 0.8 秒余量。
 * 再大就会明显"快进感"，且音频走 Web Audio 是按 PTS 排程的，
 * 视频加速会造成音画分离。
 */
const LIVE_CHASE_RATE = 1.08

/**
 * 软追帧的死区（秒）：余量超过 {@code 目标 + 本值} 才开始加速。
 *
 * <p>0.3 而不是 0.15：余量本身就抖 ±0.6 秒，死区太窄会让倍速在
 * 1.00 / 1.08 之间反复跳，每次都触发 {@code ratechange}。
 */
const LIVE_CHASE_DEADBAND = 0.3

/**
 * 追帧的目标下限（秒）：余量小于它时才降速回填。
 *
 * <p>调低到 0.5 而不是更高：降速回填每秒只能补 0.02 秒，
 * 靠它救不了大缺口，只能防「马上就要吃穿」的最后一下。
 */
const LIVE_MIN_LATENCY = 0.5
/** 音频提前排程量（秒）：留一点余量，避免第一个包就被判定为「已经迟到」。 */
const AUDIO_LEAD_SECONDS = 0.12
/** 音频最多领跑多少秒（超过说明时间戳异常，丢弃以压低延迟）。 */
const AUDIO_MAX_LEAD_SECONDS = 3

export default class WsFlvStream {
  /**
   * @param {object} opts
   * @param {string} opts.url 拉流地址：{@code ws(s)://…} 走 WebSocket，
   *                          {@code http(s)://…} 或相对路径走 HTTP 长连接
   *                          （ZLM 两种都是 {@code /rtp/{流ID}.live.flv}）
   * @param {MediaSource} opts.mediaSource 已与 video 元素绑定的 MediaSource
   * @param {HTMLVideoElement} opts.media 播放器持有的 video 元素
   * @param {(msg: string) => void} [opts.onError] 不可恢复的错误回调
   * @param {(info: object) => void} [opts.onStats] 播放信息回调（码率、分辨率等）
   * @param {() => void} [opts.onPrebuffered] 预缓冲攒够、可以起播时回调一次
   * @param {boolean} [opts.vod] 是否为录像回放（伪点播）模式
   */
  constructor({ url, mediaSource, media, onError, onStats, onPrebuffered, vod }) {
    this.url = url
    this.mediaSource = mediaSource
    this.media = media
    this.onError = onError || (() => {})
    this.onStats = onStats || (() => {})
    /**
     * 预缓冲完成回调。
     *
     * <p><b>为什么要回调出去而不是直接调 {@code video.play()}</b>：
     * 直播的 {@code autoplay} 已关掉（为了等预缓冲），此时 xgplayer 内部
     * 认为自己是「暂停」状态 —— 直接 play video 元素不会通知它，
     * 结果就是<b>画面在播、中央却盖着一个大播放按钮</b>。
     * 必须由业务层通过播放器自己的 API 起播，状态才会同步。
     */
    this.onPrebuffered = onPrebuffered || (() => {})
    /**
     * 录像回放模式。
     *
     * <p><b>为什么回放必须关掉音画同步</b>：同步逻辑以 Web Audio 时钟为主时钟，
     * 会直接改 {@code currentTime}（硬跳）与 {@code playbackRate}（软调）。
     * 但回放里的位置由<b>设备侧的 npt</b> 决定、倍速由<b>设备侧的 Scale</b> 决定，
     * 本地再按音频时钟去拽视频就会把两者全部推翻：
     * 实测日志里用户选 2 倍速后出现 {@code Scale 1.0 / 0.9 / 1.1} 乱跳，
     * 正是因为同步逻辑每 500ms 把 {@code media.playbackRate} 拉回 1 ——
     * 表现为「改倍速没用」，而且拖动进度条也会被硬跳覆盖。
     */
    this.vod = !!vod

    this.destroyed = false
    this.ws = null
    /** HTTP 通路的连接中止器（与 {@code ws} 互斥，同一时刻只有一个生效）。 */
    this.httpAbort = null
    this.retryCount = 0
    this.retryTimer = null
    /**
     * HTTP 通路的「标签重组」缓冲：收到的字节先攒在这里，凑齐完整 FLV
     * 标签再喂给解封装器。见 {@link _pushHttp}。
     */
    this.flvRest = null
    /** 会话内是否已投喂过 FLV 文件头（重连时不再喂）。 */
    this.flvHeaderPushed = false

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
    /** 是否已经拿到关键帧：没有关键帧不能喂给解码器。 */
    this.gotKeyframe = false
    /**
     * 预缓冲是否已完成。
     *
     * <p>见 {@link PREBUFFER_SECONDS} 的说明：攒够缓冲才允许起播。
     */
    this.prebuffered = false
    /** 预缓冲的兜底定时器。 */
    this.prebufferTimer = null
    /** 已收到的 FLV 字节数（用于判断「连上了但设备还没推流」）。 */
    this.receivedBytes = 0

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
    /** 追帧开始的时刻（毫秒）：起播后先等 2 秒再开始校正。 */
    this.chaseStartAt = null
    /**
     * 上次硬跳的时刻（毫秒）：用于冷却，见 {@link LIVE_JUMP_COOLDOWN_MS}。
     *
     * <p>0 表示从未跳过。
     */
    this.lastJumpAt = 0
    /**
     * 「刚从后台切回来」标记：为 true 时允许一次更激进的拍平。
     *
     * <p>见 {@link LIVE_RESUME_JUMP_LATENCY}。用一就清，不会常驻生效。
     */
    this.resumeJump = false
    /** 页面转入后台的时刻（毫秒），0 表示当前在前台。 */
    this.hiddenAt = 0
    /** 可见性监听器（销毁时解绑）。 */
    this._onVisibility = null

    this._bindMediaSource()
  }

  /** 建立连接。 */
  start() {
    if (this.destroyed) return
    this._connect()
    this.syncTimer = setInterval(() => this._syncAudioVideo(), 500)
    /*
     * 可见性变化：记下转入后台的时刻，回到前台时开一次「拍平」窗口。
     *
     * <p><b>为什么需要它</b>：后台标签下 Chrome 会挂起哑音视频
     * （本项目音频走 Web Audio、video 元素恒为静音），而 ZLM 这期间
     * 一直在推 —— 切回来时余量往往已经积了十几秒。不回前台时不能跳
     * （会与正常观看混淆），回前台时又必须跳（否则永远落后）。
     *
     * <p>开窗口而不是直接在这里改 {@code currentTime}：此刻
     * {@code buffered} 末段可能还没铺开，交给下一拍 {@code _syncAudioVideo}
     * 判定更安全。
     */
    this._onVisibility = () => {
      if (this.destroyed) return
      if (document.hidden) {
        this.hiddenAt = Date.now()
        return
      }
      if (this.hiddenAt && Date.now() - this.hiddenAt >= LIVE_HIDDEN_RESUME_MS) {
        this.resumeJump = true
        // 冷却也一并解除：后台积压的这几十秒必须一次追齐
        this.lastJumpAt = 0
      }
      this.hiddenAt = 0
    }
    if (typeof document !== 'undefined' && document.addEventListener) {
      document.addEventListener('visibilitychange', this._onVisibility)
    }
    /*
     * 预缓冲兜底：到点无论攒了多少都起播。
     *
     * <p>见 {@link PREBUFFER_TIMEOUT_MS}。带宽严重不足时 2 秒缓冲可能永远
     * 攒不到，用户不该对着黑屏无限等待 —— 先出画面（卡也比黑屏好），
     * 剩下的交给 onStall 看门狗。
     */
    this.prebufferTimer = setTimeout(() => {
      this.prebufferTimer = null
      this._releasePrebuffer()
    }, PREBUFFER_TIMEOUT_MS)
  }

  // ==================== 连接（WebSocket / HTTP） ====================

  /**
   * 建立连接。按 URL 协议选传输层：
   * {@code ws://}、{@code wss://} 走 WebSocket，其余（{@code http(s)://} 与
   * 相对路径）走 HTTP 长连接。
   */
  _connect() {
    if (this.destroyed) return
    /*
     * 新连接一律丢掉上一次的半截标签。
     *
     * <p>断流时最后一个标签很可能只收到一半，残留在 {@code flvRest} 里；
     * 重连后字节从头开始（先 FLV 头再标签），拼接起来就是一段错位数据，
     * 会让解封装器切出错误的样本，表现为画面花屏或解码失败。
     */
    this.flvRest = null
    if (/^wss?:/i.test(this.url || '')) {
      this._connectWs()
    } else {
      this._connectHttp()
    }
  }

  /**
   * 排一次重连。直播断流很常见（设备重启、网络抖动），指数退避，上限 10 秒。
   *
   * <p>两种承载共用：WebSocket 的 {@code onclose} 与 HTTP 的读流结束/异常
   * 都走这里，保证重连节奏一致。
   */
  _scheduleRetry() {
    if (this.destroyed) return
    const delay = Math.min(500 * 2 ** this.retryCount, 10000)
    this.retryCount++
    this.retryTimer = setTimeout(() => this._connect(), delay)
  }

  _connectWs() {
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
      this._scheduleRetry()
    }
  }

  /**
   * HTTP-FLV：用 {@code fetch} 建立长连接，边收边喂给同一个解封装器。
   *
   * <p><b>为什么不用 {@code <video src>} 直连</b>：FLV 不是浏览器原生支持的
   * 容器，{@code video} 元素看到 {@code .flv} 只会报 MEDIA_ERR_SRC_NOT_SUPPORTED；
   * 而且这路流带 G.711 音频，仍要拆轨走 Web Audio。
   *
   * <p>{@code fetch} 相比 {@code XHR} 的关键优势是 {@code response.body} 是
   * {@link ReadableStream}，可以<b>流式</b>读；XHR 要等整个响应结束才给数据，
   * 直播这种永不结束的响应会一直拿不到字节。
   */
  async _connectHttp() {
    if (this.destroyed) return
    const ctrl = new AbortController()
    this.httpAbort = ctrl
    let res
    try {
      res = await fetch(this.url, { signal: ctrl.signal, cache: 'no-store' })
    } catch (e) {
      // 主动 destroy 会 abort，这里不能再抛错或重连
      if (this.destroyed || ctrl.signal.aborted) return
      this._scheduleRetry()
      return
    }
    if (this.destroyed || ctrl.signal.aborted) return
    if (!res.ok || !res.body) {
      // 404/403 这类多半是流还没起来（设备正在推第一帧），按断流重试
      this._scheduleRetry()
      return
    }
    const reader = res.body.getReader()
    try {
      for (;;) {
        const { done, value } = await reader.read()
        if (this.destroyed) return
        if (done) break
        if (value && value.length) {
          this.retryCount = 0
          this._pushHttp(value)
        }
      }
    } catch (e) {
      // abort 或网络中断：统一走重连
    } finally {
      /*
       * 释放读端。
       *
       * <p>{@code cancel()} 返回的是 Promise：销毁时 abort 已经让底层流进入
       * “aborted” 状态，这里再 cancel 会以 {@code AbortError: BodyStreamBuffer
       * was aborted} <b>异步</b> reject —— 外面那层 try/catch 抓不到异步异常，
       * 浏览器控制台会多出一串 unhandled rejection。必须显式吞掉。
       */
      try {
        const p = reader.cancel()
        if (p && typeof p.catch === 'function') p.catch(() => {})
      } catch (e) {
        // 忽略
      }
      if (this.httpAbort === ctrl) this.httpAbort = null
    }
    // 直播流不该正常结束（服务端关流 / 源下线），照断流处理
    this._scheduleRetry()
  }

  // ==================== 解封装与分发 ====================

  /**
   * HTTP 通路收字节：先按 FLV 标签重新切齐，再<b>逐个标签</b>投喂。
   *
   * <p><b>为什么必须切</b>：WebSocket 通路天然是「一条消息 = 一个 FLV 标签」，
   * 解封装器每次只看到一个完整标签；HTTP 长连接的 chunk 边界则完全任意，
   * 一次读取可能包含四五个标签、也可能把标签从中间切开。
   *
   * <p>实测（Mac + Chrome，1280×720 Main，硬解）：多标签一次性投喂时，
   * 第一段 fMP4 里会出现「本该丢弃的帧」混进同一个 sample 序列，
   * 容器结构完全合法（{@code trun} 声明的样本大小之和与 {@code mdat} 一致），
   * 但解码器报 {@code MEDIA_ERR_DECODE (code 3)}，三次重建都无效；
   * 同一路流走 WebSocket 一切正常。切齐后两条通路的投喂节奏完全一致。
   *
   * <p>切分的额外好处：重连时能干净地跳过新的 FLV 头，不会把
   * {@code 'FLV'} 魔数当成标签去解析。
   */
  _pushHttp(chunk) {
    if (this.destroyed || this.failed) return
    if (!this.flvRest || !this.flvRest.length) {
      this.flvRest = chunk.slice()
    } else {
      const merged = new Uint8Array(this.flvRest.length + chunk.length)
      merged.set(this.flvRest)
      merged.set(chunk, this.flvRest.length)
      this.flvRest = merged
    }
    this._drainFlvTags()
  }

  /**
   * 把 {@code flvRest} 里能凑齐的完整 FLV 单元（文件头 + 逐个标签）投喂出去，
   * 剩下的半截留到下次。
   *
   * <p>FLV 标签定长公式：{@code 11 字节标签头 + dataSize + 4 字节 PreviousTagSize}。
   * 必须等整段到齐再喂，否则样本会被截断。
   */
  _drainFlvTags() {
    let buf = this.flvRest
    if (!buf || !buf.length) return
    let off = 0
    // 整个会话只在第一次连接时投喂文件头（9 字节头 + 4 字节 PreviousTagSize0）
    if (!this.flvHeaderPushed) {
      if (buf.length < 13) return
      if (buf[0] !== 0x46 || buf[1] !== 0x4c || buf[2] !== 0x56) {
        // 不是 FLV 头：原样喂出去，让解封装器抛错走重建，不在这里装作正常
        this.flvRest = null
        this.flvHeaderPushed = true
        this._push(buf)
        return
      }
      this.flvHeaderPushed = true
      this._push(buf.subarray(0, 13))
      off = 13
    }
    for (;;) {
      if (buf.length - off < 11) break
      const dataSize = (buf[off + 1] << 16) | (buf[off + 2] << 8) | buf[off + 3]
      const need = 11 + dataSize + 4
      if (buf.length - off < need) break
      this._push(buf.subarray(off, off + need))
      off += need
      if (this.destroyed || this.failed) return
    }
    // 残留半截标签：复制一份，避免长期持有整块大缓冲
    this.flvRest = off >= buf.length ? null : buf.subarray(off).slice()
  }

  /**
   * 喂一段 FLV 字节。
   *
   * <p>WebSocket 通路是「一条消息 = 一个标签」，HTTP 通路经
   * {@link _drainFlvTags} 切齐后也是逐标签投喂 —— 两条通路在这里完全等价。
   */
  _push(chunk) {
    if (this.destroyed || this.failed) return
    this.receivedBytes += chunk.length
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
        /*
         * 编码不支持是<b>确定性</b>失败：重建播放器一万次也还是这个编码，
         * 标成不可重试，免得把用户丢进三次无谓的重连里。
         */
        this._fail(
          `当前浏览器无法硬解该视频编码（${videoTrack.codec}）。` +
            'H.265 需要 Chrome/Edge 107+ 且系统具备 HEVC 解码能力。',
          false
        )
        return
      }
      this.needInitSegment = true
    }

    // 等关键帧再喂数据。
    //
    // 直播刚建立时，设备是从当前 GOP 的中间开始推流的，ZLM 拿到的第一个
    // 视频帧往往是 P 帧而不是 I 帧。此时若把 init segment + 非关键帧喂给
    // 解码器，会直接抛 MEDIA_ERR_DECODE（video.error.code === 3），画面黑屏。
    //
    // 所以在拿到第一个关键帧之前，把这一批样本丢掉继续等下一个 GOP
    // （GOP 周期通常 2~5 秒）。解封装器内部会把最后一个样本缓存起来，
    // 下一批再放回来，所以这里清空 samples 不会丢帧。
    if (!this.gotKeyframe) {
      if (!videoTrack.samples.some((s) => s.keyframe)) {
        videoTrack.samples = []
        return
      }
      this.gotKeyframe = true
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
    // 已失败后不再接收数据：下游不会再消费，收到只是无谓地堆内存
    if (!data || !data.byteLength || this.destroyed || this.failed) return
    if (this.appendQueue.length >= MAX_APPEND_QUEUE) {
      this._fail('播放器已停止消费数据（解码队列积压），需要重建')
      return
    }
    this.appendQueue.push(data)
    this._pump()
  }

  _pump() {
    if (this.destroyed || this.failed) return
    const sb = this.sourceBuffer
    if (!sb || this.appending || sb.updating || !this.appendQueue.length) return
    /*
     * append 之前必须确认「下游还能收数据」。
     *
     * <p>video 元素一旦进入错误状态（解码失败等），它绑定的 MediaSource
     * 就<b>永久作废</b>：此后每次 appendBuffer 都抛
     * {@code InvalidStateError: The HTMLMediaElement.error attribute is not null}，
     * 而且不可能有哪一次成功。原实现在这里直接 append，于是真正的根因
     * （MEDIA_ERR_DECODE）被后面成百上千条 append 异常淹没，
     * 用户只能看到一条看不出原因的「写入解码数据失败」。
     *
     * <p>这里改为「主动投降」：立刻回调业务方去重建播放器
     * （新 MediaSource + 重新拉流，等下一个完好 GOP），
     * 而不是继续往一个已经死掉的解码器里灌数据。
     */
    const mediaError = this.media && this.media.error
    if (mediaError) {
      this._fail(`视频元素已出错（code=${mediaError.code}），需要重建播放器`)
      return
    }
    const readyState = this.mediaSource && this.mediaSource.readyState
    if (readyState !== 'open') {
      this._fail(`媒体源已关闭（readyState=${readyState}），需要重建播放器`)
      return
    }
    const data = this.appendQueue.shift()
    this.appending = true
    sb.onupdateend = () => {
      this.appending = false
      this._evict()
      // 每写完一段就看一次预缓冲是否够了（够了就放行起播）
      this._checkPrebuffer()
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

  /**
   * 预缓冲检查：攒够 {@link PREBUFFER_SECONDS} 就放行起播。
   *
   * <p>见 {@link PREBUFFER_SECONDS} 的说明 —— 这是「播两秒卡一下」的
   * 直接解药：不再一有数据就播，而是先攒出可用的抖动余量。
   *
   * <p>超时兜底：带宽严重不足时可能永远攒不够 2 秒，不能让用户对着黑屏
   * 干等 —— {@link PREBUFFER_TIMEOUT_MS} 到点后无论攒了多少都放行。
   */
  _checkPrebuffer() {
    if (this.prebuffered || this.destroyed || this.failed) return
    const v = this.media
    if (!v) return
    let buffered = 0
    if (v.buffered.length) {
      // 直播场景通常只有一段；取最长的那段作为余量
      for (let i = 0; i < v.buffered.length; i++) {
        buffered = Math.max(buffered, v.buffered.end(i) - v.buffered.start(i))
      }
    }
    if (buffered >= PREBUFFER_SECONDS) {
      this._releasePrebuffer()
    }
  }

  /**
   * 放行起播（幂等）。
   *
   * <p><b>不直接调 {@code media.play()}</b>：直播的 {@code autoplay} 为 false，
   * xgplayer 内部仍是「暂停」状态；绕过它去播 video 元素，画面会动，
   * 但控制栏认为在暂停 —— 中央那个大播放按钮就一直盖在画面上。
   * 因此只通知业务层，由它调播放器自己的 {@code play()}，两边状态才一致。
   */
  _releasePrebuffer() {
    if (this.prebuffered) return
    this.prebuffered = true
    if (this.prebufferTimer) {
      clearTimeout(this.prebufferTimer)
      this.prebufferTimer = null
    }
    if (this.destroyed || this.failed) return

    /*
     * 起播瞬间先追到直播边缘。
     *
     * <p>预缓冲攒了 {@link PREBUFFER_SECONDS}（2 秒）才放行，如果就此起播，
     * 这 2 秒会原封不动变成用户的观看延迟。既然要的是「实时画面」，
     * 攒够的那 2 秒里前面的 1.4 秒本来就不必看 —— 直接跳到边缘前
     * {@link LIVE_TARGET_LATENCY}，起播延迟立刻从 2 秒降到 0.6 秒。
     *
     * <p>放在 onPrebuffered 之前：业务层收到回调就调 play() 了，
     * 若之后再改 currentTime 会表现为"画面闪一下"。
     */
    const v = this.media
    if (v && v.buffered.length) {
      const edge = v.buffered.end(v.buffered.length - 1)
      const target = edge - LIVE_TARGET_LATENCY
      if (target > v.currentTime + 0.05) {
        try {
          v.currentTime = target
        } catch (e) {
          // 忽略：seek 失败时退化为从头播，下一拍追帧会补上
        }
      }
    }
    // 追帧计时从"真正开始播"算起，避免预缓冲那几秒被算进 2 秒观察期
    this.chaseStartAt = Date.now()

    this.onPrebuffered()
  }

  /** 清掉过旧的缓冲，直播只需要保留最近一小段。 */
  _evict(aggressive = false) {
    const sb = this.sourceBuffer
    const v = this.media
    // 媒体已作废时 remove 同样会抛 InvalidStateError，直接跳过
    if (!sb || sb.updating || !v || v.error || !v.buffered.length) return
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
    /*
     * 非 1 倍速时不排程音频。
     *
     * <p>Web Audio 的排程绝对依赖 PTS：{@code when = audioClockBase + streamTime}。
     * 设备按 N 倍速推流时 PTS 的增量也是 N 倍，而每个音频块的实际长度不变 ——
     * 按 PTS 排会得到「一块声音 + 一段空白」的卡顿音。
     * 与其放噪声，不如在看片时不出声（2 倍速以上用户主要看画面）。
     */
    const rate = this.media?.playbackRate
    if (Number.isFinite(rate) && Math.abs(rate - 1) > 0.01) {
      /*
       * 同时把排程基准清零：回到 1 倍速时要以「当前这一块」重新起锚，
       * 否则 audioClockBase 还停在很久以前，新块的 when 已经在过去，
       * 会被下面的「迟到丢弃」判定全部丢掉 —— 表现为切回 1x 后一直没有声音。
       */
      this.audioClockBase = null
      this.firstAudioPts = null
      return
    }
    const pcm = decodeG711(data, law)

    /*
     * 预缓冲期间不出声。
     *
     * <p>视频要攒够 {@link PREBUFFER_SECONDS} 才放行起播，而音频是直接排到
     * Web Audio 时间轴上的 —— 若这里照排，用户会先听到 2 秒声音、
     * 然后画面才开始，音画整体错位。宁可丢掉这 2 秒音频：
     * 监控场景默认静音起播，等用户点音量时画面早已在播、后续音频自然对齐。
     */
    if (!this.prebuffered) {
      // 起播后要从「那一块」重新锚定时间轴，否则累积的旧基准会让新块全被判迟到
      this.audioClockBase = null
      this.firstAudioPts = null
      return
    }

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

  // ==================== 音画同步与直播追帧 ====================

  /**
   * 每 500ms 一次的调度入口。
   *
   * <p><b>顺序很重要</b>：先追直播边缘（延迟主控），再处理音画偏移。
   * 两者都会动 {@code playbackRate}，必须由前者优先决定 ——
   * 若让音画同步先设了 1±0.1，追帧再改成 1.08，就等于两套算法互相打架，
   * 画面会在 0.9 / 1.08 / 1 之间反复跳。
   */
  _syncAudioVideo() {
    if (this.vod) return
    const v = this.media
    if (!v || this.destroyed) return
    if (v.readyState < 2 || v.seeking || v.paused) return

    // 起播后前 2 秒不校正：此时数据还在灌、buffered.end 跳变剧烈
    if (!this.prebuffered) return
    if (this.chaseStartAt === null) this.chaseStartAt = Date.now()
    if (Date.now() - this.chaseStartAt < 2000) return

    // 1) 追直播边缘：返回本次是否已接管 playbackRate
    const rateTaken = this._chaseLiveEdge(v)

    // 2) 音画同步：只在追帧没动倍速时处理，且仅做硬跳
    //    （软调用倍速会与追帧冲突，见上面的说明）
    if (rateTaken) return
    const ctx = this.audioCtx
    if (!ctx || this.audioClockBase === null) return
    const audioTime = ctx.currentTime - this.audioClockBase
    const drift = v.currentTime - audioTime
    if (Math.abs(drift) > HARD_SYNC_THRESHOLD) {
      /*
       * 硬跳目标必须落在已缓冲区间内。
       *
       * <p>音频走 Web Audio 的绝对时钟，与视频缓冲无关；而追帧会跳帧、
       * {@code _evict()} 会清掉旧数据 —— 两者都会让「音频时钟换算出的
       * 位置」落到缓冲区之外。若直接赋给 {@code currentTime}，视频会去
       * 等一个还没到的时间点，{@code buffered.end - currentTime} 变成负数，
       * 实测卡 100ms~2s（40 秒里 16 次 waiting）。
       *
       * <p>夹在区间内之后，最差只是音画对齐不完美，不会造成断流。
       */
      const b = v.buffered
      if (!b.length) return
      const lo = b.start(0)
      const hi = b.end(b.length - 1)
      const target = Math.min(Math.max(audioTime, lo), Math.max(lo, hi - 0.2))
      if (Math.abs(target - v.currentTime) > HARD_SYNC_THRESHOLD) {
        v.currentTime = target
      }
    }
  }

  /**
   * 把播放位置追赶向「已到达的最新数据」。
   *
   * <p>这是 FLV 通路延迟控制的核心。判定与动作分三档：
   * <ul>
   *   <li>余量 &gt; {@link LIVE_MAX_LATENCY}：硬跳，直接丢中间帧跳到边缘前
   *       {@link LIVE_TARGET_LATENCY}。切后台回来、系统卡顿后走这条。</li>
   *   <li>余量 &gt; 目标 + 死区：软追，用 {@link LIVE_CHASE_RATE} 慢慢吃掉。
   *       日常小幅漂移走这条，画面不闪。</li>
   *   <li>余量 &lt; {@link LIVE_MIN_LATENCY}：可能要吃穿缓冲了，降速到 1.0
   *       甚至 0.98 回填一点，避免 waiting 卡顿。</li>
   * </ul>
   *
   * @param {HTMLVideoElement} v 播放器持有的 video 元素
   * @returns {boolean} 本次是否已设置 playbackRate（音画同步据此让路）
   */
  _chaseLiveEdge(v) {
    if (!v.buffered.length) return false
    // 取最后一段的终点作为「直播边缘」
    const edge = v.buffered.end(v.buffered.length - 1)
    const latency = edge - v.currentTime
    if (!Number.isFinite(latency)) return false

    /*
     * 余量为负：{@code currentTime} 已经跑到 {@code buffered.end} 之外。
     *
     * <p><b>这通常不是真的断流，而是 MSE 的 buffered 低估</b>。实测
     * （Chrome，1280×720，硬解）：{@code buffered=[[236.8, 264.6]]} 而
     * {@code currentTime=266.46}，同时 {@code readyState=4}、播放流畅、
     * 58 秒内零 waiting —— 说明解码器手里确实有数据，只是
     * {@code buffered} 在 {@code SourceBuffer.remove()} 之后没有如实反映
     * 已解码区间。所以这个符号不能当作「缓冲吃穿」来判断。
     *
     * <p><b>但绝对不能直接 return false</b>：那样倍速会永远停在上一拍的
     * 0.98 / 1.08，再也恢复不了（实测卡在 0.98 后视频越播越慢，缓冲一路
     * 被吃穿）。这里必须把速率交还给 1 倍，等 buffered 追上即可。
     */
    if (latency < 0) {
      this.setRate(v, 1)
      return true
    }

    /*
     * 硬跳判定。
     *
     * <p>两个时机允许丢帧：
     * <ol>
     *   <li>从后台切回来后的第一拍（{@code resumeJump}）：用户预期画面
     *       直接跳到最新，余量只要超过 {@link LIVE_RESUME_JUMP_LATENCY} 就拍平；</li>
     *   <li>余量真的大到离谱（&gt; {@link LIVE_MAX_LATENCY}）：系统卡顿、
     *       长时间等待等极端情况，不跳就永远追不上。</li>
     * </ol>
     * 其余情况一律只软追 —— 因为丢帧的可见代价就是用户看到
     * 「画面卡一下、右上角 OSD 时间往前跳好几秒」。
     */
    const now = Date.now()
    const cooled = !this.lastJumpAt || now - this.lastJumpAt >= LIVE_JUMP_COOLDOWN_MS
    const wantJump = latency > LIVE_MAX_LATENCY || (this.resumeJump && latency > LIVE_RESUME_JUMP_LATENCY)

    if (wantJump) {
      if (!cooled) {
        // 冷却期内只软追，避免连续跳帧
        this.setRate(v, LIVE_CHASE_RATE)
        return true
      }
      /*
       * 跳到 {@code edge - TARGET} 而不是 {@code edge}：贴着边缘播会在
       * 下一拍就变成「余量 0」，紧接着一次网络抖动就卡住。
       */
      const target = edge - LIVE_TARGET_LATENCY
      if (target > v.currentTime + 0.05) {
        this.lastJumpAt = now
        this.resumeJump = false
        v.currentTime = target
      }
      this.setRate(v, 1)
      return true
    }
    // 本次已离开「刚回到前台」的窗口，之后只认常驻阈值
    this.resumeJump = false

    if (latency > LIVE_TARGET_LATENCY + LIVE_CHASE_DEADBAND) {
      // 软追：略快一点把余量吃掉
      this.setRate(v, LIVE_CHASE_RATE)
      return true
    }

    if (latency < LIVE_MIN_LATENCY) {
      // 快要吃穿了：略微降速回填，给缓冲留出空间
      this.setRate(v, 0.98)
      return true
    }

    // 落在目标区间内：恢复正常速度
    if (Math.abs(v.playbackRate - 1) > 0.005) {
      this.setRate(v, 1)
      return true
    }
    return false
  }

  /**
   * 设置播放速率（带抖动抑制）。
   *
   * <p>只在变化超过 0.5% 时才真正写入：{@code playbackRate} 的每次赋值
   * 都会触发 media 的 {@code ratechange}，而 xgplayer 与浏览器的媒体策略
   * 都会监听它。每 500ms 无意义地重赋同一个值会刷出一堆事件。
   *
   * @param {HTMLVideoElement} v
   * @param {number} rate
   */
  setRate(v, rate) {
    if (Math.abs((v.playbackRate || 1) - rate) <= 0.005) return
    try {
      v.playbackRate = rate
    } catch (e) {
      // 某些浏览器在 readyState 不足时拒绝改速率，下一拍会重试
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

  /**
   * 标记不可恢复的失败并回调业务方。
   *
   * @param {string} msg 给用户看的原因
   * @param {boolean} [retryable] 重建播放器是否有意义。编码不支持这类
   *        <b>确定性</b>失败传 {@code false}，避免无谓的重连循环。
   */
  _fail(msg, retryable = true) {
    if (this.destroyed || this.failed) return
    this.failed = true
    // 不会再消费了，排队的段直接丢弃，否则会滞留在内存里
    this.appendQueue = []
    this.onError(msg, retryable)
  }

  destroy() {
    this.destroyed = true
    if (this.retryTimer) clearTimeout(this.retryTimer)
    if (this.syncTimer) clearInterval(this.syncTimer)
    if (this.prebufferTimer) clearTimeout(this.prebufferTimer)
    this.retryTimer = null
    this.syncTimer = null
    this.prebufferTimer = null
    if (this._onVisibility) {
      try {
        document.removeEventListener('visibilitychange', this._onVisibility)
      } catch (e) {
        // 忽略
      }
      this._onVisibility = null
    }

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

    // HTTP 通路：abort 会让正在 await 的 reader.read() 立刻以异常返回
    if (this.httpAbort) {
      try {
        this.httpAbort.abort()
      } catch (e) {
        // 忽略
      }
      this.httpAbort = null
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
