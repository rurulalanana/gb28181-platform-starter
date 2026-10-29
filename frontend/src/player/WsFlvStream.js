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
import { log, packRanges, readTfdt, installGlobalLog } from './FlvDiag'

/**
 * 直播缓冲保留时长（秒）：超过旧的部分就清掉，防止内存无限增长。
 *
 * <p><b>不要调小</b>：{@code SourceBuffer.remove()} 会打断 append 并让
 * 解码器重置。缓冲深度是抗抖动的唯一依靠（见 {@link PREBUFFER_SECONDS}），
 * 而它靠「不清理」才能攒住 —— 清得太勤等于把攒下的安全余量亲手丢掉。
 */
const BUFFER_KEEP_SECONDS = 45

/**
 * 起播前要求的最小缓冲（秒）。
 *
 * <p><b>这是「播两秒卡一下」的根治手段</b>。国标设备经 ZLM 转出 FLV 时，
 * 数据到达速率<b>恰好等于码率</b>（实测 4.9 MB / 46 秒 ≈ 856 kbps，与视频
 * 码率完全一致），所以 MSE 缓冲自己不会变厚 —— 播多快，数据就来多快。
 *
 * <p>结果：缓冲深度取决于<b>起播时攒了多少</b>，之后只会被消耗。
 * 改造前只攒 2 秒、起播又立刻 seek 到边缘丢掉 1 秒，稳态只剩 <b>0.48 秒</b>
 * （实测 {@code buffered=[[0,31.8]]} 而 {@code ct=31.32}）。这个深度下，
 * 任何一次 {@code remove()}、GC、解码尖峰都会把它吃干 → 转圈。
 *
 * <p>4 秒是按「常见单次抖动 ≤ 2 秒」留的余量：即使真来一次 2 秒抖动，
 * 也还剩下 2 秒，用户完全无感。
 *
 * <p><b>代价是起播变慢</b>：低码率流攒 4 秒约需 4~6 秒。监控场景下
 * 「晚几秒出画面」远比「画面一卡一卡」可接受。
 */
const PREBUFFER_SECONDS = 4

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

/**
 * fMP4 分段时长（毫秒）：攒够这么久媒体数据就重封装一段写进 MSE。
 *
 * <p><b>这是「播两秒转圈一下」的根治点</b>。设备按帧推流（实测 74 个
 * FLV 标签/秒），若一个标签刷一次就是 74 次/秒的 {@code appendBuffer}。
 * 实测 MSE 写入跟不上播放：{@code buffered.end} 只以 0.5~0.8 倍实时增长，
 * {@code currentTime} 按 1.0 倍前进，播着播着就跑到缓冲末尾之外，
 * 解码器没数据 → {@code waiting} → 转圈。
 *
 * <p>400 毫秒是实测平衡点：写入频率降到约 2~3 次/秒（与 flv.js /
 * mpegts.js 一致），又不会因为段太长而拉高延迟与内存。
 */
const SEGMENT_FLUSH_MS = 400

/**
 * 待刷队列的样本数上限：满这么多帧就刷一段。
 *
 * <p><b>为什么还需要按帧数兜底</b>：{@link SEGMENT_FLUSH_MS} 依赖样本上的
 * {@code duration} 字段，而某些流（或封装器版本的差异）会把它给成 0 ——
 * 此时 {@code pendingMs} 永远不涨，就只剩 {@link _scheduleFlush} 的定时器路径。
 * 而<b>后台标签的 {@code setTimeout} 会被 Chrome 限流到约 1 次/分钟</b>，
 * 于是完全不再写入 MSB，播放自然卡死（实测 appends = 0 / 20 秒）。
 *
 * <p>按帧数判定不依赖任何时间字段，是真正的兜底：8 帧在 15 fps 下约 530 毫秒。
 */
const SEGMENT_MAX_SAMPLES = 8

/**
 * 字节/帧率统计窗口（毫秒）。
 *
 * <p>见 {@link _flushStats}：把「到达字节」与「产出样本」放在同一个窗口里
 * 对比，才能分清「网没送到」和「解封装丢帧」。2 秒足够短到能看出突变，
 * 又长到不受单个标签的抖动影响。
 */
const STATS_WINDOW_MS = 2000

/**
 * 媒体时间推进比的告警阀值。
 *
 * <p><b>为什么用「媒体时间 / 墙钟」而不是绝对码率</b>：绝对码率需要知道
 * 「这路流本来该多少 kbps」，而那取决于分辨率/编码/设备配置，前端无从得知。
 * 但两者之比是<b>自归一化</b>的：正常播放必然是 1.0。
 *
 * <p>实测（公网 ws-flv，980 kbps 的流，TCP 吞吐只有 233 kbps）：
 * 50 秒墙钟里媒体时间只前进 9.2 秒，比值 <b>0.18</b>，同时 34 次 waiting。
 * 取 0.7 作为阀值：给慢启动、GOP 边界留足余量，但真实带宽不足一定能识别。
 */
const STARVE_RATIO = 0.7

/**
 * 连续多少个统计窗口低于阀值才判定为「带宽不足」。
 *
 * <p>3 个窗口 ≈ 6 秒。单个窗口可能因网络瞬时抖动为低，连续 3 个就不会误判。
 */
const STARVE_WINDOWS = 3

/**
 * 音画硬跳阈值（秒）：偏差超过它才改 {@code currentTime}。
 *
 * <p><b>从 0.5 放宽到 2.0</b>：G.711 音频走 Web Audio 绝对时钟，而视频
 * 靠 MSE 缓冲 —— 两者本来就会差几百毫秒。原值 0.5 导致每几秒就对齐一次，
 * 每次都是「画面一顿」；而监控画面本来就对唇音同步不敏感，
 * 宁可放任 2 秒以内的偏差，也不要把画面打断。
 */
const HARD_SYNC_THRESHOLD = 2.0
/** 软调阈值（秒）：超过它用播放速率追赶，避免频繁跳帧。 */
const SOFT_SYNC_THRESHOLD = 0.2

/* ==================== 直播追帧（延迟控制） ==================== */

/**
 * 稳态目标延迟（秒）：播放位置与「已到达的最新数据」之间保留的余量。
 *
 * <p><b>为什么需要保底余量而不是追到最小</b>：数据到达速率恰好等于码率
 * （见 {@link PREBUFFER_SECONDS} 的实测），所以余量只会被消耗、很难自己变厚。
 * 目标定得太低时，任何一次抖动都会直接吃干缓冲 → 转圈。
 *
 * <p><b>从 1.0 提到 3.0</b>：1 秒在实测中依然反复见底（稳态只有 0.48 秒），
 * 用户看到的就是「播两秒卡一下」。3 秒比常见单次抖动（≤ 2 秒）大一倍，
 * 能真正把抖动吸收掉；代价是端到端延迟约 3~4 秒。
 *
 * <p><b>延迟敏感场景请用 WebRTC 协议</b>（同页切换即可，延迟 &lt; 0.5 秒）。
 * FLV 通路的定位是「稳」，WebRTC 的定位是「快」。
 */
const LIVE_TARGET_LATENCY = 3.0

/**
 * 硬跳阈值（秒）：缓冲余量超过它就丢帧直接跳到边缘前 {@link LIVE_TARGET_LATENCY}。
 *
 * <p><b>这个值直接决定「OSD 会不会往前跳」</b>。OSD 是设备烧进画面的，
 * 它跟着帧走 —— 只要没有丢帧，OSD 就只会一秒一秒地走；一旦发生向前 seek，
 * 用户就会看到画面卡一下、右上角时间猛地跳到几秒之后。
 *
 * <p>取值思路：正常观看的余量会围绕 {@link LIVE_TARGET_LATENCY} 上下浮动，
 * 这个阈值必须远高于浮动幅度；只有「切后台一小时」「系统卡顿几十秒」这类
 * 真积压才值得一次跳完。
 *
 * <p>历史：取 1.6 时 40 秒跳了 26 次（OSD 一直在跳）；取 3.0 时仍有 1 次。
 * 提到 8.0 后，正常观看完全不会触发。
 */
const LIVE_MAX_LATENCY = 8.0

/**
 * 从后台切回来时允许的硬跳阈值（秒）。
 *
 * <p>后台标签下 Chrome 会挂起哑音视频（本项目的音频走 Web Audio、
 * video 元素恒为静音），而 ZLM 这期间一直在推 —— 切回来时往往积压
 * 十几秒到几十秒。这种场景用户<b>预期</b>画面会跳到最新，不跳反而奇怪。
 *
 * <p>取 {@code 目标 + 2}：比正常浮动幅度大，避免一次短暂切窗回来就丢帧。
 */
const LIVE_RESUME_JUMP_LATENCY = LIVE_TARGET_LATENCY + 2.0

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
 * 软追帧倍速：余量明显超过目标时用它慢慢吃掉。
 *
 * <p>取 1.05 而不是 1.08：倍速变化会让浏览器重算解码节奏，
 * 在马赛克/硬解场景下可能丢帧；监控画面又对「快5%」毫无感知，
 * 宁可追得慢一点、只在不急的时候追。
 */
const LIVE_CHASE_RATE = 1.05

/**
 * 软追帧的死区（秒）：余量超过 {@code 目标 + 本值} 才开始加速。
 *
 * <p>1.0 而不是 0.3：宽死区让倍速几乎一直在 1.00，
 * 只在缓冲突发涨厚时才短暂加速 —— 越少改倍速，解码越稳。
 */
const LIVE_CHASE_DEADBAND = 1.0

/**
 * 追帧的目标下限（秒）：余量小于它时才降速回填。
 *
 * <p>降速回填每秒只能补 0.02 秒，靠它救不了大缺口；
 * 它的作用只是「马上要吃穿时再挤一点时间」。
 */
const LIVE_MIN_LATENCY = 1.5
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
   * @param {(info: {kbps:number, ratio:number}) => void} [opts.onSlowNetwork]
   *        带宽持续不足时回调一次（见 {@link STARVE_RATIO}）
   * @param {boolean} [opts.vod] 是否为录像回放（伪点播）模式
   */
  constructor({ url, mediaSource, media, onError, onStats, onPrebuffered, onSlowNetwork, vod }) {
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
     * 带宽不足回调（只触发一次）。
     *
     * <p>参数 {@code {kbps, ratio}}：实测到达码率与媒体时间推进比。
     * 由业务层弹提示并引导用户切 WebRTC —— 因为<b>带宽不足是 TCP 通路的固有
     * 问题</b>，播放器内部无法自愈（UDP 的 WebRTC 不受影响）。
     */
    this.onSlowNetwork = typeof onSlowNetwork === 'function' ? onSlowNetwork : null
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
     * 待刷的视频样本队列（跨多次 demux 累积）。
     *
     * <p>见 {@link _handleVideo} 与 {@link SEGMENT_FLUSH_MS}：不能每个 FLV
     * 标签就 remux 一次，否则 MSE 写入跟不上播放速度。
     */
    this.pendingSamples = []
    /** 待刷队列已有的媒体时长（毫秒），用于判定何时刷一段。 */
    this.pendingMs = 0
    /** 兜底刷段定时器，见 {@link _scheduleFlush}。 */
    this.flushTimer = null

    // ---- 诊断统计窗口（见 {@link _flushStats}）----
    /** 当前窗口起始时刻（毫秒）。 */
    this.stWinStart = 0
    /** 窗口内到达字节。 */
    this.stWinBytes = 0
    /** 窗口内投喂次数。 */
    this.stWinPush = 0
    /** 窗口内产出的视频样本数。 */
    this.stWinVideoSamples = 0
    /** 窗口内解封装抛错次数。 */
    this.stWinDemuxErr = 0
    /** 本窗口开始的 tfdt（毫秒），用于算媒体时间推进量。 */
    this.stWinTfdtStart = null
    /** 本窗口最后的 tfdt（毫秒）。 */
    this.stWinTfdtEnd = null
    /** 连续多少个窗口媒体推进比过低。 */
    this.starveWin = 0
    /** 是否已报过「带宽不足」（只报一次，避免刷屏）。 */
    this.starveReported = false
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
    /** 上次尝试清理旧缓冲的时刻（毫秒），用于限流。见 {@link _evict}。 */
    this.lastEvictAt = 0

    this._bindMediaSource()
  }

  /** 建立连接。 */
  start() {
    if (this.destroyed) return
    // 把 window.gbFlvLog 挂上（幂等，多实例共享同一份记录）
    installGlobalLog()
    this._bindDiag()
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
   * 绑定媒体事件诊断。
   *
   * <p>记录 {@code waiting} / {@code playing} / {@code error} /
   * {@code ratechange} / {@code emptied} 的<b>精确时刻</b>。
   * 「播一下就卡住」到底是 waiting 卡住了、还是 readyState 掉了、
   * 还是被改了倍速，靠这几个事件的时间线能直接看出来，
   * 不必再靠外部观测猜。
   */
  _bindDiag() {
    const v = this.media
    if (!v || v.__diagBound) return
    v.__diagBound = true
    const snap = (ev) => {
      log('ev', {
        ev,
        ct: +v.currentTime.toFixed(2),
        rs: v.readyState,
        ns: v.networkState,
        rate: v.playbackRate,
        paused: v.paused,
        err: v.error ? v.error.code : null,
        ...packRanges(v.buffered)
      })
    }
    for (const ev of ['waiting', 'playing', 'stalled', 'seeking', 'seeked', 'ratechange', 'pause', 'play', 'emptied', 'error']) {
      v.addEventListener(ev, () => snap(ev))
    }
  }

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
    log('conn', { what: 'retry-scheduled', retryCount: this.retryCount, delay })
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
      const u8 = new Uint8Array(ev.data)
      /*
       * 诊断：WebSocket 通路也要记源头标签时间戳。
       *
       * <p>WS 通路是「一条消息 = 一个标签」，不经 {@link _drainFlvTags}，
       * 所以这里单独记。少记这一处的话，对比 tfdt 时就看不到源头一侧。
       */
      if (u8.length >= 11 && (u8[0] & 0x1f) === 9) {
        const ts = ((u8[7] << 24) >>> 0) + (u8[4] << 16) + (u8[5] << 8) + u8[6]
        log('tagsrc', { ts, b: (u8[1] << 16) | (u8[2] << 8) | u8[3] })
      }
      this._push(u8)
    }

    ws.onerror = () => {
      // onerror 之后必定跟 onclose，重连逻辑统一放 onclose，避免重复重连
    }

    ws.onclose = () => {
      log('conn', { what: 'ws-close', retryCount: this.retryCount })
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
      /*
       * 诊断：记录<b>源头</b>的视频标签时间戳。
       *
       * <p>这是整条链路上最原始的时间信息。若上游设备/ZLM 给的
       * timestamp 本身就跳变，那问题不在前端分段；反之若这里连续、
       * 而 fMP4 的 tfdt 有洞，就是我们的重封装算错了。
       * 两条通路都经过这里，所以只需记这一处。
       */
      const tagType = buf[off] & 0x1f
      if (tagType === 9) {
        const ts = ((buf[off + 7] << 24) >>> 0) + (buf[off + 4] << 16) + (buf[off + 5] << 8) + buf[off + 6]
        log('tagsrc', { ts, b: dataSize })
      }
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
    /*
     * 字节级统计窗口：用来区分「网没送到」和「解封装丢帧」。
     *
     * <p><b>为什么必须区分</b>：实测 50 秒墙钟里媒体时间只前进 9.2 秒
     * （18%），同时 34 次 waiting —— 播放器一直在饿死。但原因可能是
     * ①网络/服务端只发了 18% 的字节，或 ②字节够了但解封装把帧丢了。
     * 两者的处置方向完全相反，靠猜没用。
     *
     * <p>每 {@link STATS_WINDOW_MS} 打一条 {@code 'bytes'} 记录：
     * 窗口内到达字节、解封装抛错次数、产出视频样本数。把
     * 「字节/秒」和「样本/秒」放在一起看，答案立刻分晓 ——
     * 15fps 的流应当 ≈ 15 样本/秒；若样本远少于字节所能支撑的帧数，
     * 就是解封装在丢。
     */
    this.stWinBytes += chunk.length
    this.stWinPush++
    let tracks
    try {
      tracks = this.demuxer.demuxAndFix(chunk, this.discontinuity, this.contiguous, 0, false)
    } catch (e) {
      // 单个坏包不该打断整路播放，丢掉继续
      this.stWinDemuxErr++
      this._flushStats()
      return
    }
    if (!tracks) {
      this._flushStats()
      return
    }
    // 首包解析成功后转为「连续」，让 fixer 按连续时间戳处理
    this.discontinuity = false
    this.contiguous = true

    const videoTrack = this.demuxer.videoTrack
    const audioTrack = this.demuxer.audioTrack

    this.stWinVideoSamples += videoTrack.samples.length

    this._handleAudio(audioTrack)
    this._handleVideo(videoTrack)
    this._flushStats()

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
   * 每个统计窗口结束时打一条 {@code 'bytes'} 记录。
   *
   * <p>字段含义：
   * <ul>
   *   <li>{@code ms} —— 本窗口实际时长；</li>
   *   <li>{@code bytes} / {@code kbps} —— 到达字节与折算码率；</li>
   *   <li>{@code push} —— 投喂次数（WS 消息数 / HTTP 标签数）；</li>
   *   <li>{@code vsamp} —— 解封装产出的<b>视频样本</b>数（=帧数）；</li>
   *   <li>{@code fps} —— 折算帧率，与流本身帧率对比即知是否丢帧；</li>
   *   <li>{@code derr} —— 解封装抛错次数。</li>
   * </ul>
   */
  _flushStats() {
    const now = Date.now()
    if (!this.stWinStart) {
      this.stWinStart = now
      return
    }
    const ms = now - this.stWinStart
    if (ms < STATS_WINDOW_MS) return
    /*
     * 媒体时间推进比 = 本窗口内「媒体时间前进」/ 「墙钟前进」。
     *
     * <p>正常播放恒为 1.0。远小于 1 就说明<b>数据没送到</b>（而不是解码慢、
     * 也不是解封装丢帧 —— 那两项会表现为 {@code derr} 上升或
     * {@code vsamp} 与 {@code bytes} 不成比例）。这是区分「网不够」与
     * 「代码有 bug」最直接的一个数。
     */
    const mediaMs = this.stWinTfdtEnd != null && this.stWinTfdtStart != null
      ? this.stWinTfdtEnd - this.stWinTfdtStart : null
    const ratio = mediaMs == null ? null : +(mediaMs / ms).toFixed(2)
    log('bytes', {
      ms,
      bytes: this.stWinBytes,
      kbps: Math.round((this.stWinBytes * 8) / ms),
      push: this.stWinPush,
      vsamp: this.stWinVideoSamples,
      fps: +((this.stWinVideoSamples * 1000) / ms).toFixed(1),
      derr: this.stWinDemuxErr,
      mediaMs,
      ratio
    })
    /*
     * 连续多个窗口推进比过低 → 带宽不足，回调业务方给出提示。
     *
     * <p><b>为什么必须在播放器里判</b>：用户看到的现象只是「卡」，
     * 而卡的原因可能是解码失败、代码 bug、或带宽不足。
     * 只有播放器手里的这两个数（到达字节、媒体时间推进）能直接分开。
     * 报错时报出实测值，用户才能去查网络，而不是反复重启平台。
     */
    if (ratio != null && ratio < STARVE_RATIO) {
      this.starveWin++
    } else if (ratio != null) {
      this.starveWin = 0
    }
    if (this.starveWin >= STARVE_WINDOWS && !this.starveReported) {
      this.starveReported = true
      const kbps = Math.round((this.stWinBytes * 8) / ms)
      log('err', { where: 'starve', ratio, kbps, win: this.starveWin })
      if (this.onSlowNetwork) {
        this.onSlowNetwork({ kbps, ratio, mediaMs, wallMs: ms })
      }
    }
    this.stWinStart = now
    this.stWinBytes = 0
    this.stWinPush = 0
    this.stWinVideoSamples = 0
    this.stWinDemuxErr = 0
    this.stWinTfdtStart = this.stWinTfdtEnd
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

  /**
   * 视频分支：把样本攒进待刷队列，按「时长」或「关键帧」切成 fMP4 段。
   *
   * <p><b>为什么不能一个 FLV 标签刷一次</b>（原来的做法）：</p>
   * <p>设备按帧推流，每个标签就是一个视频帧，实测 <b>74 个标签/秒</b>。
   * 若每个标签都 remux + {@code appendBuffer}，就是 74 次/秒的 MSE 写入 ——
   * 每次都要新建 moof/mdat、走一遍 {%@code SourceBuffer} 状态机。实测结果：
   * MSE 的写入速度<b>追不上播放速度</b>，{@code buffered.end} 只以 0.5~0.8 倍
   * 实时增长，而 {@code currentTime} 按 1.0 倍前进，于是播着播着就跑到
   * 缓冲末尾之外 —— 解码器没数据，触发 {@code waiting}，用户看到的就是
   * 「播两秒转圈一下」（实测 {@code ct=11.94} 而 {@code buffered.end=11.6}）。</p>
   *
   * <p>改成攒 {@link SEGMENT_FLUSH_MS} 毫秒再刷一段后，写入频率降到约 2~3 次/秒，
   * 与 flv.js / mpegts.js 的做法一致。顺带把 CPU 占用降一个数量级。</p>
   */
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

    const samples = videoTrack.samples

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
      if (!samples.some((s) => s.keyframe)) {
        videoTrack.samples = []
        return
      }
      this.gotKeyframe = true
    }

    /*
     * 搬运样本到待刷队列。
     *
     * <p>遇到关键帧时先把之前攒的刷出去 —— 这样每一段的<b>首样本都是关键帧</b>，
     * 解码器无论从哪一段开始都能独立解，seek/重建时不必回溯。
     *
     * <p>{@code videoTrack.samples = []}（而不是 {@code reset()}）：
     * 只清样本、保留 {@code codec} / {@code width} 等轨道元信息，
     * 否则下一次 remux 生成 init segment 时就没有编码参数了。
     */
    for (const s of samples) {
      if (s.keyframe && this.pendingSamples.length) {
        this._flushVideo()
      }
      this.pendingSamples.push(s)
      this.pendingMs += s.duration || 0
    }
    videoTrack.samples = []

    // 攒够一段时长（或帧数）就刷（长 GOP 下也不能一直等关键帧）
    if (
      this.pendingMs >= SEGMENT_FLUSH_MS ||
      this.pendingSamples.length >= SEGMENT_MAX_SAMPLES
    ) {
      this._flushVideo()
      return
    }
    this._scheduleFlush()
  }

  /**
   * 兜底刷段：低帧率流（如 5 fps）下 400 毫秒可能只来一两个样本，
   * 光靠 {@code pendingMs} 判定会攒太久、拉高延迟。这里用一个定时器
   * 保证「最迟 {@link SEGMENT_FLUSH_MS} 之后一定刷一次」。
   */
  _scheduleFlush() {
    if (this.flushTimer) return
    this.flushTimer = setTimeout(() => {
      this.flushTimer = null
      if (this.destroyed || this.failed) return
      this._flushVideo()
    }, SEGMENT_FLUSH_MS)
  }

  /**
   * 把待刷样本重封装成一段 fMP4 并写入 SourceBuffer。
   *
   * <p>样本会被直接塞回 {@code videoTrack.samples} 再交给 remuxer ——
   * 这是 {@code FMP4Remuxer} 的接口约定（它从轨道对象上取样本，末尾自己清空）。
   */
  _flushVideo() {
    if (!this.pendingSamples.length) return
    const videoTrack = this.demuxer.videoTrack
    videoTrack.samples = this.pendingSamples
    this.pendingSamples = []
    this.pendingMs = 0

    /*
     * 诊断：记下这一批样本的时间轴。
     *
     * <p>样本自带的 dts 是分段逻辑的输入，ppend 后从 fMP4 里解析出的
     * {@code tfdt} 才是 MSE 实际看到的。两者都要记，才能分清是
     * 「解封装算错」还是「重封装写错」。
     */
    const ss = videoTrack.samples
    const firstDts = ss[0] && Number.isFinite(ss[0].dts) ? ss[0].dts : null
    const lastDts = ss[ss.length - 1] && Number.isFinite(ss[ss.length - 1].dts)
      ? ss[ss.length - 1].dts : null
    const sumDur = ss.reduce((a, s) => a + (s.duration || 0), 0)
    const keys = ss.filter((s) => s.keyframe).length

    let result
    try {
      /*
       * 🔴 关键：必须按「本批第一个样本」重算 baseMediaDecodeTime。
       *
       * <p><b>这是「FLV 播一下就卡住」的真正原因</b>。{@code xgplayer-transmuxer}
       * 在 {@code flv/fixer.js} 里<b>每次 demux 都执行</b>：</p>
       * <pre>
       *   videoTrack.baseMediaDecodeTime = videoTrack.samples[0].dts
       * </pre>
       * <p>而 {@code mp4.js} 生成 moof 时直接拿它写 tfdt：</p>
       * <pre>
       *   var tfdt = MP42.tfdt(track.baseMediaDecodeTime)
       * </pre>
       *
       * <p>我们是<b>攒多批再 flush</b> 的（见 {@link SEGMENT_FLUSH_MS}），
       * 所以 flush 时这个字段停在「最后一批」的 dts，而样本却是从
       * 「第一批」开始的 —— 实测 tfdt 恰好等于 lastDts 而不是 firstDts：</p>
       * <pre>
       *   段1: firstDts=0   span=333 → tfdt=333  (应为 0)
       *   段2: firstDts=400 span=333 → tfdt=733  (应为 400)
       *   段3: firstDts=800 span=133 → tfdt=933  (应为 800)
       * </pre>
       *
       * <p>MSE 完全按 tfdt 决定「这段接在哪」，每段多跳一整段就把时间轴
       * 切成一堆短碎片（实测 {@code buffered=[[0.33,1.13],[3.73,4],[5.87,6.27]]}），
       * 播放头走进空洞后再无数据 —— 表现就是「播一下就卡住」。</p>
       *
       * <p>单批 flush（一个标签刷一次）时这两个值恰好相等，所以这个 bug
       * 在「攒批」改造之前不存在，是随改造一起引入的。</p>
       */
      if (firstDts != null) {
        videoTrack.baseMediaDecodeTime = firstDts
      }
      // initMerge=false：init segment 只含视频轨。
      // 若用 initMerge=true，音频轨会被并进同一个 init segment，
      // 而 MSE 的视频 SourceBuffer 不接受音频轨描述符。
      result = this.remuxer.remux(this.needInitSegment, { initMerge: false })
    } catch (e) {
      log('err', { where: 'remux', msg: String(e && e.message) })
      return
    }
    const segTfdt = result && result.videoSegment ? readTfdt(result.videoSegment) : null
    log('seg', {
      n: ss.length,
      keys,
      firstDts: firstDts == null ? null : +firstDts.toFixed(1),
      lastDts: lastDts == null ? null : +lastDts.toFixed(1),
      span: firstDts != null && lastDts != null ? +(lastDts - firstDts).toFixed(1) : null,
      sumDur: +sumDur.toFixed(1),
      // 本段写入的 tfdt（重封装后的真实解码基准），供 {@code gbFlvLog.gaps()} 判时间轴
      tfdt: segTfdt == null ? null : +segTfdt.toFixed(1),
      // duration 全为 0 就是「按帧数/定时器刷」而非「按时长刷」，是一类典型异常
      zeroDur: ss.every((s) => !s.duration),
      bytes: result && result.videoSegment ? result.videoSegment.length : 0,
      init: !!(result && result.videoInitSegment),
      /*
       * 音频段大小。
       *
       * <p>非 0 表示 remuxer 把<b>音频轨</b>也编成了 fMP4 段，而紧接着那句
       * {@code _append(result.audioSegment)} 会把它塞进<b>视频</b>
       * SourceBuffer —— 那个 SB 的 mime 是 {@code video/mp4;codecs="…"}，
       * 收音频轨必然报错。G.711 走 {@code audioTrack.reset()} 所以是 0，
       * 但 <b>AAC 流不会被 reset</b>，需靠这条日志确认。
       */
      audioBytes: result && result.audioSegment ? result.audioSegment.length : 0
    })
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
      /*
       * SourceBuffer 级错误监听：这是捕获 {@code MEDIA_ERR_DECODE (code=3)} 现场的唯一入口。
       *
       * <p>按 MSE 规范，{@code appendBuffer} 解析失败时会在 SourceBuffer 上
       * 派发 {@code error} 事件，并把 media 置为 {@code MEDIA_ERR_DECODE}。
       * 此刻 {@code buffered} 往往还是干净的（失败的那段没写进去），
       * 所以事后看缓冲查不出任何东西 —— 必须在这里把
       * 「最后写入的那一段」和当时的播放位置一起记下来。
       */
      sb.addEventListener('error', () => {
        const v = this.media
        log('err', {
          where: 'sourcebuffer-error',
          mime,
          last: this.lastAppendInfo || null,
          ct: v ? +v.currentTime.toFixed(2) : null,
          rs: v ? v.readyState : null,
          mediaErr: v && v.error ? { code: v.error.code, msg: v.error.message } : null,
          ...packRanges(v && v.buffered)
        })
      })
      this.sourceBuffer = sb
      this.videoMime = mime
      return true
    } catch (e) {
      log('err', { where: 'addSourceBuffer', mime, msg: String(e && e.message) })
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
    /*
     * 诊断：记下这次写入的 tfdt 与写入前后的 buffered。
     *
     * <p><b>tfdt 是判据的核心</b>：MSE 完全按它决定「这段接在哪」。
     * 若相邻两段的 tfdt 差 ≠ 前一段的媒体时长，MSE 就在 buffered 里
     * 划出一道空洞；播放头一旦走进空洞再无数据，画面永久停住 ——
     * 这正好对应「播一下就卡住」。记录它才能区分是分段算错、还是
     * 解封装的上游时间戳就断了。
     */
    const tfdt = readTfdt(data)
    if (tfdt != null) {
      // 给 {@link _flushStats} 算「媒体时间推进量」用：记窗口内的首尾 tfdt
      if (this.stWinTfdtStart == null) this.stWinTfdtStart = tfdt
      this.stWinTfdtEnd = tfdt
    }
    const before = packRanges(this.media && this.media.buffered)
    /*
     * 记住「正在写入的这一段」。
     *
     * <p><b>为什么必须记</b>：{@code MEDIA_ERR_DECODE (code=3)} 是间歇性的，
     * 事后看 {@code buffered} 什么都查不出来 —— 报错时缓冲往往还是干净的。
     * 只有把「出错前最后写入的那一段」的 tfdt / 字节数 / 是否以关键帧开头
     * 留下来，才能反推是哪类数据把它弄崩的。{@link _ensureSourceBuffer}
     * 里注册的 SourceBuffer error 监听会读取这个字段。
     */
    this.lastAppendInfo = {
      tfdt: tfdt == null ? null : +tfdt.toFixed(1),
      bytes: data.byteLength,
      isInit: tfdt == null
    }
    sb.onupdateend = () => {
      this.appending = false
      log('append', {
        tfdt: tfdt == null ? null : +tfdt.toFixed(1),
        bytes: data.byteLength,
        beforeN: before.n,
        ...packRanges(this.media && this.media.buffered),
        ct: this.media ? +this.media.currentTime.toFixed(2) : null,
        rs: this.media ? this.media.readyState : null
      })
      this._evict()
      // 每写完一段就看一次预缓冲是否够了（够了就放行起播）
      this._checkPrebuffer()
      this._pump()
    }
    try {
      sb.appendBuffer(data)
    } catch (e) {
      this.appending = false
      log('err', { where: 'append', name: e && e.name, msg: String(e && e.message) })
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
     * 起播时<b>不要</b>再 seek。
     *
     * <p>早先的实现会先跳到 {@code edge - 目标延迟} 再起播，理由是「预缓冲
     * 攒的那几秒白看了」。但这是个致命的取舍错误：数据到达速率恰好等于码率
     * （见 {@link PREBUFFER_SECONDS} 的实测），缓冲<b>只能靠起播时攒下的那一点
     * 慢慢消耗</b>，跳过去等于把辛苦攒的余量当场倒掉。
     *
     * <p>实测后果：稳态缓冲只剩 0.48 秒，用户看到「播两秒卡一下」。
     * 现在直接从当前 {@code currentTime} 起播，把攒下的 4 秒全带进播放。
     */
    // 追帧计时从"真正开始播"算起，避免预缓冲那几秒被算进 2 秒观察期
    this.chaseStartAt = Date.now()

    this.onPrebuffered()
  }

  /** 清掉过旧的缓冲，直播只需要保留最近一段。 */
  _evict(aggressive = false) {
    const sb = this.sourceBuffer
    const v = this.media
    // 媒体已作废时 remove 同样会抛 InvalidStateError，直接跳过
    if (!sb || sb.updating || !v || v.error || !v.buffered.length) return
    /*
     * 限流：{@code remove()} 会把 `sb.updating` 置位并让 append 停下，
     * 频繁调用等于周期性断供 —— 而缓冲深度本来就只有几秒。
     * 10 秒试一次足够（45 秒才需要清理，一次清不完下轮接着清）。
     */
    const now = Date.now()
    if (!aggressive && now - this.lastEvictAt < 10000) return
    this.lastEvictAt = now
    const start = v.buffered.start(0)
    /*
     * 紧急回收（QuotaExceededError）也不能清太狠：
     * 原来清到 {@code ct - 2}，一次丢掉近 30 秒缓冲，紧接着就是转圈。
     * 保留 20 秒，只把更早的部分扔掉。
     */
    const removeTo = v.currentTime - (aggressive ? 20 : BUFFER_KEEP_SECONDS)
    if (removeTo - start > 1) {
      log('evict', {
        aggressive,
        from: +start.toFixed(2),
        to: +removeTo.toFixed(2),
        ct: +v.currentTime.toFixed(2),
        ...packRanges(v.buffered)
      })
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
    const v = this.media
    log('err', {
      where: 'fail',
      msg,
      retryable,
      ct: v ? +v.currentTime.toFixed(2) : null,
      rs: v ? v.readyState : null,
      ranges: v ? packRanges(v.buffered) : null
    })
    // 不会再消费了，排队的段直接丢弃，否则会滞留在内存里
    this.appendQueue = []
    this.onError(msg, retryable)
  }

  destroy() {
    this.destroyed = true
    if (this.retryTimer) clearTimeout(this.retryTimer)
    if (this.syncTimer) clearInterval(this.syncTimer)
    if (this.prebufferTimer) clearTimeout(this.prebufferTimer)
    if (this.flushTimer) clearTimeout(this.flushTimer)
    this.retryTimer = null
    this.syncTimer = null
    this.prebufferTimer = null
    this.flushTimer = null
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
