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

import { Mp4Remuxer, scanParamSets } from './Mp4Remuxer.js'

/** 重建 MediaSource 的最大次数，超过就放弃。 */
const MAX_REBUILD = 12

/** 探测 moov 时，首次取文件尾的字节数。 */
const MOOV_PROBE_BYTES = 64 * 1024

/** 探测 moov 时，单次最多放大到的字节数。 */
const MOOV_MAX_BYTES = 32 * 1024 * 1024

/**
 * 提前下载的 GOP 数量（保证连续播放不卡顿）。
 *
 * <p>GOP 约 2 秒，4 个就是 8 秒的缓冲余量。公网带宽实测 50~130KB/s
 * （波动 6 倍），而录像码率 46~370KB/s —— 带宽紧张时必须留够余量，
 * 否则缓冲一掉到 0 就转圈。
 *
 * <p>预取是「喂完当前段才发起」的，不阻塞起播；起播只等 1 个 GOP。
 */
const PREFETCH_GOP = 4

/**
 * 同时在飞的预取下载数。
 *
 * <p><b>服务器出口带宽不是瓶颈</b>（实测 9.6 MB/s），
 * 瓶颈是客户端到服务器这条链路的单流速率。实测同一条链路：
 * 1 路 77 KB/s、4 路 245 KB/s、8 路 374 KB/s。
 * 再往上（12 路）网关开始掐断（一半请求 502）。
 *
 * <p>所以取 4：比单流快 3 倍，又不会被限流。
 */
const PREFETCH_CONCURRENCY = 4

/**
 * 单次 Range 请求的最大字节数。
 *
 * <p>公网中间网关会掐断较大的响应（实测 300KB 就出 502），而且响应越大
 * 吞吐越差：16KB→53KB/s、64KB→132KB/s、256KB→115KB/s、1MB→77KB/s。
 * 取 128KB 是「不太会被掐」与「请求次数不太多」的折中。
 */
const FETCH_CHUNK_BYTES = 128 * 1024

/**
 * 单次下载的重试次数（5xx / 网络错误时）。
 *
 * <p>网络抖动和网关限流是临时的，重试就能拿到；直接跳段等于把好录像丢了。
 */
const FETCH_RETRY = 3

/**
 * 同一个 GOP 内部并发取几块。
 *
 * <p>见 {@link _fetchRangeChunked} 的说明：单流只有 77 KB/s，
 * 4 路能到 245 KB/s。超过 8 路会被网关限流。
 */
const FETCH_CONCURRENCY = 4

/**
 * 缓冲窗口（秒）：只下载「播放位置 + 这个时长」以内的数据。
 *
 * <p>不设上限的话，{@code updateend} 会一路触发，几秒钟就把整段几十 MB
 * 的录像拉到本地 —— 既费流量，也让"按需下载"名存实亡。
 * 播放推进时由 {@code timeupdate} 续上下载。
 */
const LEAD_SECONDS = 20

/**
 * 修复编码参数集时，最多往前扫几个 GOP。
 *
 * <p>GOP 通常 1~2 秒（实测 2 秒），真参数集一般在前几个关键帧就带内出现。
 * 上限存在的意义是防止"整段都捞不到"时把几十 MB 录像下载干净 ——
 * 那种情况说明录像真的坏了，再扫也没用。
 */
const PARAM_SCAN_GOP = 8

/**
 * 扫参数集时，每个关键帧只取开头这么多字节。
 *
 * <p>VPS/SPS/PPS 是关键帧 NAL 的最前面几个（VPS 23 字节、SPS 几十字节、
 * PPS 几字节），前面顶多跟一个 SEI。取 8KB 足够宽松。
 *
 * <p><b>这个值直接决定起播快慢</b>：整个 GOP 实测 640KB，而公网带宽只有
 * ~80KB/s —— 取整个 GOP 扫 8 次就是 5MB（65 秒），只取头部就是 16KB（0.2 秒）。
 */
const PARAM_HEAD_BYTES = 8 * 1024

/**
 * 调试开关。
 *
 * <p>容错通路的状态机（下载 → 塞 SourceBuffer → 解码报错 → 重建）不容易
 * 从现象反推：界面上只看到一个笼统的「容错重建…」。排查时在浏览器控制台
 * 执行 {@code window.__MSE_DEBUG__ = true} 再点播，就能看到每一步的
 * 片段序号、播放位置和缓冲区间。生产环境默认关闭，零开销。
 *
 * @param {...any} args
 */
function dbg(...args) {
  if (typeof window !== 'undefined' && window.__MSE_DEBUG__) {
    console.log('[MseRecordPlayer]', ...args)
  }
}

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
   * @param {string} [options.debugGlobal] 开发模式下挂到 window 的全局名
   */
  constructor(videoEl, options = {}) {
    this.video = videoEl
    this.onState = options.onState || (() => {})
    this.onFatal = options.onFatal || (() => {})
    this.onSkip = options.onSkip || (() => {})
    /** 开发调试用的全局挂点名（空字符串 = 不挂）。 */
    this.debugGlobal = options.debugGlobal || ''

    /**
     * 首次播放用的 MediaSource（由外部播放器骨架创建）。
     *
     * <p>xgplayer 这类外壳要求「构造时就必须有合法媒体源」，
     * 所以由调用方先建 MediaSource、把 blob URL 交给播放器当 url，
     * 再把同一个实例传进来，避免本类二次改写 {@code video.src}
     * 导致播放器手里的地址失效。
     *
     * <p>为空时本类自己建（保留脱离播放器独立使用的能力）。
     */
    this.initialMediaSource = options.mediaSource || null

    /** 本类自建、销毁时需要 revoke 的 blob URL。 */
    this.ownedUrls = []

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
    /**
     * 用户拖动进度条想去的位置（秒）。
     *
     * <p>MSE 的 currentTime 只能落在 buffered 内，拖到未下载的位置时浏览器
     * 会把播放位置拉回来；数据到了之后要靠这个字段再挪回去。
     */
    this._seekTarget = null
    /**
     * 程序自己设置播放位置的标志。
     *
     * <p>{@link _setCurrentTime} 期间置 true，让 {@link _installCurrentTimeHook}
     * 知道「这是程序纠偏，不是用户想看这里」。
     */
    this._selfSettingTime = false
    /** currentTime 劫持是否已装上（销毁时还原）。 */
    this._timeHookInstalled = false
    /** 已下载的 GOP 数据缓存：index -> Uint8Array */
    this.chunkCache = new Map()
    /** 正在下载的 GOP：index -> Promise */
    this.downloading = new Map()
    /** 排队等待下载的 GOP 序号（限制并发用）。 */
    this._prefetchQueue = []
    /** 当前在飞的预取下载数。 */
    this._prefetchActive = 0
    /** 下载失败后的重试定时器。 */
    this._retryTimer = null
    this.downloading = new Map()
    this.rebuilds = 0
    this.aborted = false
    this.started = false
    this.ms = null
    this.sb = null
    this._rebuilding = false
    /** 上一次上报的状态（避免重复回调「播放中」）。 */
    this._state = ''
    /** 是否已有一个片段正在下载（防止 updateend / timeupdate 并发触发重复下载）。 */
    this._feeding = false
    /**
     * 当前那次下载属于哪个 SourceBuffer。
     *
     * <p>容错重建会换掉 SourceBuffer，此时上一轮的下载 Promise 还悬在半空。
     * 如果只用一个布尔守卫，进程就成了：新缓冲的 {@code updateend} 发现
     * {@code _feeding} 仍是 true 就提前返回，而旧 Promise 落地时又因为
     * "sb 已换"直接丢弃 —— <b>两边都不喂，播放永久卡死</b>。
     * 记下归属后，旧的一轮落地时不会去动新一轮的守卫。
     */
    this._feedingSb = null
    /** 已打过「窗口外等待」日志的片段序号（避免每次 timeupdate 刷屏）。 */
    this._waitLogged = -1
    /**
     * 最近一次成功 append 的片段序号。
     *
     * <p>解码错误要归因到「哪一段坏了」。绝大多数情况按 {@code currentTime}
     * 归因是对的（MSE 播到哪儿就在哪儿报错），但<b>容错重建之后</b>
     * {@code currentTime} 会被 {@code video.src} 重置为 0 —— 这时再按时间归因
     * 会永远指向第 0 段：它已经在 skipSet 里，于是不会记入新的坏段，
     * 下一轮又从同一段坏数据开始，原地打转直到重试次数耗尽。
     * 所以时间归因落到「已跳过的段」时，改用这个字段兜底。
     */
    this.lastFedIndex = -1

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
    /*
     * 拖动支持。
     *
     * <p>按需下载模式下 buffered 只覆盖「已经喂过」的区间，
     * 用户拖到未下载的位置时浏览器会一直等数据（画面全黑、进度条不动）。
     * 这里把下载指针拨到目标片段：往前拖直接跳着下载，往回拖清空缓冲重喂。
     */
    this._onSeeking = () => {
      if (this.aborted || this._rebuilding) return
      this._onSeek()
    }
    /*
     * 播放推进时续下载。
     *
     * <p>_feed 有 {@link LEAD_SECONDS} 的缓冲窗口，喂满窗口就停手；
     * 之后靠 timeupdate（约 4 次/秒）把窗口往前推。没有这个监听，
     * 画面播到窗口边缘就会卡住不再加载。
     */
    this._onTimeUpdate = () => {
      if (this.aborted || this._rebuilding) return
      const sb = this.sb
      if (sb && !sb.updating) this._feed()
    }
    this.video.addEventListener('error', this._onError)
    this.video.addEventListener('playing', this._onPlaying)
    this.video.addEventListener('seeking', this._onSeeking)
    this.video.addEventListener('timeupdate', this._onTimeUpdate)

    this._installCurrentTimeHook()

    if (this.debugGlobal && typeof window !== 'undefined') {
      window[this.debugGlobal] = this
    }
  }

  /**
   * 劫持 {@code media.currentTime} 的 setter，捕获「用户想看哪」。
   *
   * <h3>为什么必须劫持</h3>
   * <p>MSE 有一个反直觉的��为：<b>把 currentTime 设到未缓冲的位置时，
   * 浏览器不会把播放位置挪过去，而是保持原值并挂起</b>
   * （实测：currentTime 停在 12.7 秒、paused=true、等数据等到天荒地老）。
   * 于是「用户拖到了哪里」这个信息会彻底丢失。
   *
   * <p>之前靠包装 xgplayer 的 {@code player.seek()} 来拿落点，实测<b>不生效</b>：
   * progress 插件点击时并没有走 {@code player.seek()}，
   * {@code _seekTarget} 一直是 null → 内核退化成拿被挂起的 currentTime
   * 去比对缓冲 → 误判「目标已在缓冲内」→ 什么都不做 → 永远转圈。
   *
   * <p>而 setter 拿到的是<b>赋值的那一刻的目标值</b>，不受浏览器挂起影响，
   * 且所有 seek 路径（点击、拖动、键盘）最终都必经它 —— 比猜播放器内部
   * 行为可靠得多。
   *
   * <p>代价是「程序自己纠偏也会被捕获」，用 {@link _selfSettingTime} 区分。
   */
  _installCurrentTimeHook() {
    if (typeof HTMLMediaElement === 'undefined') return
    const desc = Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype, 'currentTime')
    if (!desc || !desc.set || !desc.get) return
    const self = this
    try {
      Object.defineProperty(this.video, 'currentTime', {
        configurable: true,
        enumerable: true,
        get() {
          return desc.get.call(this)
        },
        set(value) {
          if (!self._selfSettingTime) {
            self._onSeekIntent(value)
          }
          desc.set.call(this, value)
        }
      })
      this._timeHookInstalled = true
    } catch (e) {
      /* 某些环境不允许重定义，退回只靠 seeking 事件 */
    }
  }

  /** 还原 currentTime 的原始行为（销毁时调用）。 */
  _removeCurrentTimeHook() {
    if (!this._timeHookInstalled) return
    this._timeHookInstalled = false
    try {
      delete this.video.currentTime
    } catch (e) {
      /* 忽略 */
    }
  }

  /**
   * 设置播放位置（程序内部用）。
   *
   * <p>包一层是为了让 {@link _installCurrentTimeHook} 知道「这是程序自己设的」，
   * 不把它当成用户的 seek 意图 —— 否则 {@link _ensurePlayable} 把位置
   * 拉回缓冲起点时，会被误判成「用户想看这里」。
   *
   * @param {number} t 目标时间（秒）
   */
  _setCurrentTime(t) {
    this._selfSettingTime = true
    try {
      this.video.currentTime = t
    } catch (e) {
      /* 元数据未就绪 */
    } finally {
      this._selfSettingTime = false
    }
  }

  /**
   * 收到「有人把播放位置设到 t」的信号。
   *
   * <p>只做转发：去重与驱动下载的逻辑统一在 {@link rememberSeekTarget}，
   * 免得两个入口各写一套、哪天改一处漏一处。
   *
   * @param {number} value 目标时间（秒）
   */
  _onSeekIntent(value) {
    this.rememberSeekTarget(value)
  }

  /**
   * 内部状态快照（排障用）。
   *
   * @returns {object}
   */
  debugState() {
    const sb = this.sb
    const ranges = []
    if (sb) {
      for (let i = 0; i < sb.buffered.length; i++) {
        ranges.push([+sb.buffered.start(i).toFixed(1), +sb.buffered.end(i).toFixed(1)])
      }
    }
    const ts = this.remuxer ? this.remuxer.timescale : 0
    return {
      seekTarget: this._seekTarget,
      nextIndex: this.nextIndex,
      feeding: this._feeding,
      updating: sb ? sb.updating : null,
      currentTime: +this.video.currentTime.toFixed(2),
      paused: this.video.paused,
      readyState: this.video.readyState,
      buffered: ranges,
      gopCount: this.gops.length,
      gopTotalSec: this.gops.length ? +((this.gops[this.gops.length - 1].startTime + this.gops[this.gops.length - 1].duration) / ts).toFixed(1) : 0,
      gopIndexAt: (t) => this.gopIndexAt(t),
      chunkCache: this.chunkCache.size,
      downloading: [...this.downloading.keys()],
      prefetchActive: this._prefetchActive,
      skipSet: [...this.skipSet]
    }
  }

  /** 销毁并释放资源。 */
  destroy() {
    this.aborted = true
    if (this._retryTimer) {
      clearTimeout(this._retryTimer)
      this._retryTimer = null
    }
    this._prefetchQueue.length = 0
    this._prefetchActive = 0
    this.video.removeEventListener('error', this._onError)
    this.video.removeEventListener('playing', this._onPlaying)
    this.video.removeEventListener('seeking', this._onSeeking)
    this.video.removeEventListener('timeupdate', this._onTimeUpdate)
    this._removeCurrentTimeHook()
    try {
      if (this.ms && this.ms.readyState === 'open') {
        this.ms.endOfStream()
      }
    } catch (e) { /* 已关闭 */ }
    // 只 revoke 自己建的 URL：外部传入的 MediaSource 由调用方负责释放
    for (const u of this.ownedUrls) {
      try { URL.revokeObjectURL(u) } catch (e) { /* 忽略 */ }
    }
    this.ownedUrls.length = 0
    this.chunkCache.clear()
    this.downloading.clear()
    this.sb = null
    this.ms = null
    this.initialMediaSource = null
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
    this.gops = remuxer.gops()
    if (!this.gops.length) {
      throw new Error('录像里没有可播放的画面')
    }
    /*
     * 编码参数（hvcC）可能被写坏，必须在建 MediaSource 之前修好：
     * 一旦 addSourceBuffer 按坏的 codecs 串建好了，后面再改就只能重建。
     * 见 {@link _recoverParams}。
     */
    await this._recoverParams()
    this.initSegment = remuxer.initSegment()

    // ---- 3) 挂上 MSE，开始按需下载 + 播放 ----
    await this._open()
    this.started = true
    /*
     * 缓冲就绪后再驱动一次 seek。
     *
     * <p>用户在索引还没读完时就拖了进度条的话，{@link _driveSeek} 当时
     * 因为 {@code sb} 还没建而直接返回，目标就一直记着没人管 ——
     * 而 {@code _feed} 的下载窗口又是按 {@code _seekTarget} 算的，
     * 结果就是「既不下载也不播放」，永远转圈。
     */
    this._driveSeek()
    this._feed()
  }

  /**
   * 修复被写坏的编码参数集（VPS/SPS/PPS）。
   *
   * <h3>为什么要修</h3>
   * <p>ZLM 偶尔会把 <b>整帧</b>当成参数集写进 {@code hvcC}：实测某个录文件的
   * {@code hvcC} 里 VPS 是 3133 字节（正常 23 字节），首字节还是 {@code 0xC0}
   * 而不是 {@code 0x40}（forbidden_zero_bit 被置位）。
   * ffmpeg 解析时在这段字节上直接失败返回，<b>连后面完好的 SPS/PPS 也一并没注册</b>，
   * 于是每一帧都报 {@code PPS id out of range}；浏览器侧表现就是
   * 每一段都解不出来 —— 容错跳过所以帮不上忙（全是坏段），最后重建次数耗尽。
   *
   * <h3>怎么修</h3>
   * <p>编码器通常在每个 IDR 前重复 VPS/SPS/PPS，所以真参数集往往还在 mdat 里。
   * 这里从前往后扫样本（长度前缀 NAL），校验通过就拿来替换。
   * 实测那个坏文件的第 38 个样本起每个关键帧都带着正确的 23 字节 VPS，
   * 换进去之后 ffmpeg 从「96 行错误、解不出帧」变成「879 帧、0 错误」。
   *
   * <p>扫描上限 {@link PARAM_SCAN_GOP} 个 GOP：GOP 通常 1~2 秒，
   * 参数集一般在前几个关键帧就出现，没必要为了坏文件把整个录像下下来。
   *
   * <h3>怎么扫才不慢</h3>
   * <p><b>参数集在每个 GOP 的关键帧上</b>，但<b>第一个 GOP 可能是例外</b>。
   * 实测某坏录像：18 个关键帧里只有第 2 个（GOP1）起才带
   * {@code [VPS, SPS, PPS]}，GOP0 的关键帧只有 slice。
   * 所以扫描要从 GOP0 开始逐个试，<b>不能假设第一个就有</b>。
   *
   * <p>取数据的量要小：一个 GOP 实测 640KB，8 个就是 5MB；
   * 公网带宽只有 ~80KB/s（实测 64KB 要 0.4~2.4 秒，波动 6 倍），
   * 光这一项就 65 秒。而参数集就躺在关键帧 NAL 的开头，
   * <b>取关键帧头部 8KB 就够</b>（VPS+SPS+PPS 加起来不到 200 字节）。
   *
   * <p>所以分两轮：
   * <ol>
   *   <li><b>快路径</b>：逐个 GOP 只取关键帧头部 8KB，串行，扫到就停
   *       （本文件要 2 次共 16KB）；</li>
   *   <li><b>兜底</b>：万一参数集不在关键帧上（罕见），退回
   *       「取整个 GOP 逐样本扫」的老办法。</li>
   * </ol>
   *
   * @returns {Promise<void>}
   */
  async _recoverParams() {
    const remuxer = this.remuxer
    const needed = new Set(remuxer.needsParamRecovery)
    if (!needed.size) {
      return
    }
    this.onState('修复编码参数')
    const limit = Math.min(this.gops.length, PARAM_SCAN_GOP)

    // ---- 快路径：只取关键帧头部 ----
    for (let i = 0; i < limit && needed.size; i++) {
      await this._scanKeyHead(i, needed)
    }

    // ---- 兜底：参数集不在关键帧上 → 取整个 GOP 逐样本扫 ----
    if (needed.size) {
      for (let i = 0; i < limit && needed.size; i++) {
        await this._scanGopFull(i, needed)
      }
    }

    if (needed.size) {
      // 捞不到就只能用原参数集试一次（有的解码器能容忍缺 VPS）
      console.warn('[录像] 参数集仍缺失：', [...needed].join(','))
    } else {
      dbg('参数集已修复', { sets: Object.keys(remuxer.paramSets) })
    }
  }

  /**
   * 只取某个 GOP 的关键帧头部，扫参数集。
   *
   * <p>关键帧的 NAL 序列以参数集开头（可能有 SEI 在前），
   * 取 {@link PARAM_HEAD_BYTES} 字节足够。
   *
   * @param {number} i GOP 序号
   * @param {Set<number>} needed 还缺哪些类型（命中后会被删除）
   */
  async _scanKeyHead(i, needed) {
    if (!needed.size) return
    const g = this.gops[i]
    if (!g || !g.samples.length) return
    const key = g.samples[0]
    let data
    try {
      data = await this._fetchRange(key.offset, key.offset + PARAM_HEAD_BYTES - 1)
    } catch (e) {
      return
    }
    if (this.aborted || !needed.size) return
    const found = scanParamSets(data, needed)
    for (const t of Object.keys(found)) {
      this.remuxer.setParamSets(found)
      needed.delete(Number(t))
    }
  }

  /**
   * 取整个 GOP，逐样本扫参数集（兜底路径）。
   *
   * @param {number} i GOP 序号
   * @param {Set<number>} needed 还缺哪些类型（命中后会被删除）
   */
  async _scanGopFull(i, needed) {
    if (!needed.size) return
    const g = this.gops[i]
    if (!g) return
    let data
    try {
      data = await this._fetchRange(g.dataStart, g.dataEnd - 1)
    } catch (e) {
      return
    }
    if (this.aborted || !needed.size) return
    for (const s of g.samples) {
      const from = s.offset - g.dataStart
      if (from < 0 || from + s.size > data.length) {
        continue
      }
      const found = scanParamSets(data.subarray(from, from + s.size), needed)
      for (const t of Object.keys(found)) {
        this.remuxer.setParamSets(found)
        needed.delete(Number(t))
      }
      if (!needed.size) {
        return
      }
    }
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
   * 取一段字节，**分块 + 并发**请求。
   *
   * <p><b>为什么要分块</b>：公网链路中间有网关，会掐断较大的响应 ——
   * 实测一次要 300KB 就会返回 {@code 502 Bad Gateway}（服务端日志里
   * 是正常的 206，说明是中间设备干的）。而且响应越大吞吐越差：
   * 实测 16KB→53KB/s、64KB→132KB/s、256KB→115KB/s、1MB→77KB/s。
   *
   * <p><b>为什么要并发</b>：<b>服务器出口带宽有 9.6 MB/s</b>
   * （服务器上拉清华镜像实测 9604727 B/s），带宽根本不是瓶颈 ——
   * 瓶颈是「客户端到服务器」这条链路的单流速率。实测同一条链路：
   * <pre>
   *   1 路  →  77 KB/s
   *   4 路  → 245 KB/s
   *   8 路  → 374 KB/s
   *   12 路 → 488 KB/s（但一半请求被网关掐断）
   * </pre>
   * 所以<b>并发 4 路</b>是「提速明显」与「不被掐断」的平衡点。
   *
   * <p>注意：这条链路的丢包率、延迟因人而异（我这里是 10% 丢包 / 82ms），
   * 用户所在网络可能更慢或更快。并发数保守取 4，再高容易被网关限流。
   *
   * @param {number} start 起始字节
   * @param {number} end 结束字节（含）
   * @returns {Promise<Uint8Array>}
   */
  async _fetchRangeChunked(start, end) {
    const total = end - start + 1
    if (total <= FETCH_CHUNK_BYTES) {
      return this._fetchRangeWithRetry(start, end)
    }
    // 切块
    const ranges = []
    for (let s = start; s <= end; s += FETCH_CHUNK_BYTES) {
      ranges.push([s, Math.min(s + FETCH_CHUNK_BYTES - 1, end)])
    }
    /*
     * 并发取（限 {@link FETCH_CONCURRENCY} 路）。
     *
     * <p>不能用 {@code Promise.all} 一把梭 —— 块数多时同时发十几个请求
     * 会被网关限流（实测 12 路时一半 502）。
     */
    const parts = new Array(ranges.length)
    let next = 0
    const worker = async () => {
      for (;;) {
        const i = next++
        if (i >= ranges.length) return
        const [s, e] = ranges[i]
        parts[i] = await this._fetchRangeWithRetry(s, e)
      }
    }
    const workers = []
    const n = Math.min(FETCH_CONCURRENCY, ranges.length)
    for (let i = 0; i < n; i++) {
      workers.push(worker())
    }
    await Promise.all(workers)

    const out = new Uint8Array(total)
    let off = 0
    for (const p of parts) {
      out.set(p, off)
      off += p.length
    }
    return out
  }

  /**
   * 取一段字节，失败时重试。
   *
   * <p><b>5xx 要重试，不能当坏段跳过</b>：网络抖动 / 网关限流是临时的，
   * 跳过这一段就等于把好录像弄丢了（实测 502 连着 5 次，然后
   * 「跳过异常片段 #71」，那一段其实是好的）。
   *
   * @param {number} start 起始字节
   * @param {number} end 结束字节（含）
   * @returns {Promise<Uint8Array>}
   */
  async _fetchRangeWithRetry(start, end) {
    let lastErr = null
    for (let attempt = 0; attempt < FETCH_RETRY; attempt++) {
      if (this.aborted) {
        throw new Error('已取消')
      }
      try {
        return await this._fetchRange(start, end)
      } catch (e) {
        lastErr = e
        // 4xx 是请求本身有问题（越界等），重试没意义
        if (/HTTP 4\d\d/.test(e.message || '')) {
          throw e
        }
        if (attempt < FETCH_RETRY - 1) {
          // 退避后重试
          await new Promise((r) => setTimeout(r, 400 * (attempt + 1)))
        }
      }
    }
    throw lastErr
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
    const p = this._fetchRangeChunked(g.dataStart, g.dataEnd - 1)
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
   * <p>首次优先用外部传入的实例（播放器外壳已经拿它的 blob URL 当媒体源了），
   * 之后（容错重建）由本类新建。
   *
   * @returns {Promise<void>}
   */
  _open() {
    return new Promise((resolve, reject) => {
      let ms = this.initialMediaSource
      this.initialMediaSource = null
      if (!ms) {
        try {
          ms = this._newMediaSource()
        } catch (e) {
          reject(e)
          return
        }
      }
      this.ms = ms
      const onOpen = () => {
        dbg('sourceopen 触发')
        this._attach(ms).then(resolve, reject)
      }
      this._publishDuration(ms)
      if (ms.readyState === 'open') {
        onOpen()
        return
      }
      dbg('等待 sourceopen', { readyState: ms.readyState })
      ms.addEventListener('sourceopen', onOpen, { once: true })
      ms.addEventListener('error', () => reject(new Error('MediaSource 打开失败')), { once: true })
    })
  }

  /**
   * 新建 MediaSource 并把 blob URL 设为当前媒体源。
   *
   * @returns {MediaSource}
   */
  _newMediaSource() {
    if (typeof window === 'undefined' || !window.MediaSource) {
      throw new Error('当前浏览器不支持 MSE，无法播放该录像')
    }
    const ms = new MediaSource()
    const url = URL.createObjectURL(ms)
    this.ownedUrls.push(url)
    dbg('新建 MediaSource', { prevSrc: (this.video.currentSrc || '').slice(0, 20) })
    this.video.src = url
    return ms
  }

  /**
   * 把总时长告诉 MediaSource。
   *
   * <p><b>为什么必须显式设置</b>：MSE 在 {@code endOfStream()} 之前
   * {@code duration} 一直是 {@code Infinity}。本播放器是<b>边下边播</b>，
   * 不可能等全部片段喂完才结束流 —— 那样进度条会一直是"无限长"的转圈状态，
   * 用户也拖不动。索引（moov）已经给出每段的时长，直接算出来赋给
   * {@code MediaSource.duration}，进度条立刻就有完整刻度。
   *
   * @param {MediaSource} ms
   */
  _publishDuration(ms) {
    if (!this.remuxer || !this.gops.length) return
    const ts = this.remuxer.timescale || 1
    const last = this.gops[this.gops.length - 1]
    const end = (last.startTime + last.duration) / ts
    if (!Number.isFinite(end) || end <= 0) return
    const set = () => {
      try {
        ms.duration = end
      } catch (e) { /* 还没 open 时赋值会抛错，下个时机再设 */ }
    }
    set()
    ms.addEventListener('sourceopen', set, { once: true })
  }

  /**
   * 创建 SourceBuffer 并灌入初始化段。
   *
   * @param {MediaSource} ms
   * @returns {Promise<void>}
   */
  _attach(ms) {
    return new Promise((resolve, reject) => {
      try {
        /*
         * 'segments' 保留 fMP4 里的绝对时间戳 → 进度条可以任意拖动。
         * （'sequence' 会丢掉时间戳，拖动就失效。）
         */
        const sb = ms.addSourceBuffer(mimeFor(this.remuxer.codec))
        sb.mode = 'segments'
        this.sb = sb
        sb.addEventListener('updateend', () => {
          // 数据到位后把播放位置挪到用户拖去的落点
          this._applySeekTarget()
          // 目标可能还没下，继续把下载指针拨过去
          this._driveSeek()
          this._ensurePlayable()
          this._feed()
        })
        sb.addEventListener('error', () => {
          this._skipCurrent('SourceBuffer 错误')
        })
        sb.appendBuffer(this.initSegment)
        dbg('初始化段已塞入', { codec: this.remuxer.codec, bytes: this.initSegment.length })
        resolve()
      } catch (e) {
        reject(e)
      }
    })
  }

  /**   * 预取后面的若干段。
   *
   * <p><b>调用时机很关键</b>：必须在当前段 {@code appendBuffer} 之后才调。
   * 提前调会和当前段抢带宽（公网只有 ~80KB/s，一个 GOP 640KB 就要 8 秒），
   * 反而拖慢起播。
   *
   * @param {number} from 起始段序号
   */
  _prefetch(from) {
    for (let k = from; k < from + PREFETCH_GOP && k < this.gops.length; k++) {
      if (!this.chunkCache.has(k) && !this.downloading.has(k) && !this.skipSet.has(k)) {
        this._prefetchQueue.push(k)
      }
    }
    this._pumpPrefetch()
  }

  /**
   * 启动排队中的预取。
   *
   * <p><b>并发数要限住</b>：同时发 4~5 个大响应会触发公网中间网关限流，
   * 实测直接返回 {@code 502 Bad Gateway}（服务端日志是正常的 206，
   * 说明是链路中间的设备掐的）。所以同时最多
   * {@link PREFETCH_CONCURRENCY} 个在飞，其余排队。
   */
  _pumpPrefetch() {
    if (this._prefetchActive >= PREFETCH_CONCURRENCY) {
      return
    }
    while (this._prefetchQueue.length && this._prefetchActive < PREFETCH_CONCURRENCY) {
      const k = this._prefetchQueue.shift()
      if (this.chunkCache.has(k) || this.downloading.has(k) || this.skipSet.has(k)) {
        continue
      }
      this._prefetchActive++
      this._fetchGop(k)
        .catch(() => { /* 失败时播放到那里再重试 */ })
        .finally(() => {
          this._prefetchActive--
          this._pumpPrefetch()
        })
    }
  }

  /**   * 喂片段：串行，一次一个，等 {@code updateend} 再喂下一个。
   *
   * <p>数据是按需下载的 —— 只下当前要播的（外加 {@link PREFETCH_GOP} 个预取），
   * 所以打开一段 57MB 的录像只需要下载 moov + 第一个 GOP（几百 KB）。
   */
  _feed() {
    const sb = this.sb
    if (this.aborted || this._rebuilding || !sb || sb.updating || this._feeding) {
      return
    }
    const i = this.nextIndex
    if (i >= this.gops.length) {
      try {
        if (this.ms && this.ms.readyState === 'open') {
          this.ms.endOfStream()
        }
      } catch (e) { /* 忽略 */ }
      return
    }

    if (this.skipSet.has(i)) {
      this.nextIndex = i + 1
      this._feed()
      return
    }

    /*
     * 只下到「播放位置 + {@link LEAD_SECONDS}」。
     *
     * <p>没有这个窗口的话，{@code updateend} 会一直触发下一段下载，
     * 几秒钟内就把整段几十 MB 的录像拉完 —— 用户只看了 10 秒，
     * 流量却已经跑光，拖动也失去了「按需」的意义。
     * 播放推进由 {@code timeupdate} 事件续上（见构造函数里的监听）。
     *
     * <p><b>拖动中要用「用户意图的位置」当基准</b>：浏览器把 currentTime
     * 钳回缓冲内后，真实的落点（{@link _seekTarget}）会远大于它。
     * 用 currentTime 算窗口就会判定「目标在窗口外」而永远不下载，
     * 表现为拖动后画面原地不动（实测拖到 70% 只停在 8.9 秒）。
     */
    const ts = this.remuxer.timescale || 1
    const now = this._seekTarget != null ? this._seekTarget : (this.video.currentTime || 0)
    if (this.gops[i].startTime / ts > now + LEAD_SECONDS) {
      // 这个分支每次 timeupdate 都会走到，只在换片段时打一条，避免刷屏
      if (this._waitLogged !== i) {
        this._waitLogged = i
        dbg('窗口外等待', { i, gopTs: +(this.gops[i].startTime / ts).toFixed(2), now: +now.toFixed(2) })
      }
      return
    }
    this._waitLogged = -1

    /*
     * 只下当前这一段，<b>预取推迟到它喂进去之后</b>。
     *
     * <p>原因：公网带宽只有 ~80KB/s，一个 GOP 640KB 就要 8 秒。
     * 要是同时预取后面 2 段（2MB），三份数据抢同一条带宽，
     * 当前这段反而更慢（实测起播 15 秒）。先让当前段独占带宽出画面，
     * 预取再在后台慢慢补 —— 带宽没变，但用户早 8 秒看到画面。
     */
    this._feeding = true
    this._feedingSb = sb
    this._fetchGop(i).then(data => {
      // 只有还归本轮所有时才释放守卫（旧轮次不许动新一轮的状态）
      if (this._feedingSb === sb) {
        this._feeding = false
        this._feedingSb = null
      }
      if (this.aborted) {
        return
      }
      /*
       * 下载期间可能已经重建过，丢弃过期结果。
       *
       * <p>但<b>必须再唤醒一次 _feed</b>：新的 SourceBuffer 可能正等在这里
       * （它先前被旧的 _feeding 守卫挡掉了），不叫醒就没人继续喂。
       */
      if (this.sb !== sb) {
        this._feed()
        return
      }
      /*
       * 下载期间可能已有别的 append 在跑（timeupdate 与 updateend 都会触发
       * _feed）。这时不要动 nextIndex，等那一轮的 updateend 再来喂同一段 ——
       * 提前自增会直接丢掉这一段。
       */
      if (sb.updating) {
        return
      }
      try {
        sb.appendBuffer(this.remuxer.fragment(this.gops[i], i + 1, data))
        /*
         * 只有「没人改过指针」才推进。
         *
         * <p>下载期间用户可能拖了进度条，{@link _driveSeek} 已经把
         * {@code nextIndex} 指向新目标了；这里再无脑 {@code i + 1}
         * 就会把刚设好的目标覆盖掉，表现为「拖完又跳回原处」。
         */
        if (this.nextIndex === i) {
          this.nextIndex = i + 1
        }
        this.lastFedIndex = i
        dbg('已下发', { i, bytes: data.length, ct: +this.video.currentTime.toFixed(2), rs: this.video.readyState })
        // 当前段已经下发，这时再去预取后面的段，不再抢当前段的带宽
        this._prefetch(i + 1)
      } catch (e) {
        if (e.name === 'QuotaExceededError') {
          // 缓冲满了：丢掉最早的一段，nextIndex 保持不动，等 remove 的 updateend 重试
          try {
            if (sb.buffered.length) {
              sb.remove(0, Math.max(0, sb.buffered.start(0) + 20))
            }
          } catch (e2) { /* 忽略 */ }
          return
        }
        this.skipSet.add(i)
        if (this.nextIndex === i) {
          this.nextIndex = i + 1
        }
        this.onSkip(i, 'append 失败：' + e.name)
        this._feed()
      }
    }).catch(e => {
      if (this._feedingSb === sb) {
        this._feeding = false
        this._feedingSb = null
      }
      if (this.aborted) {
        return
      }
      if (this.sb !== sb) {
        this._feed()
        return
      }
      /*
       * <b>网络失败不能当坏段跳过</b>。
       *
       * <p>502 / 超时是链路问题（网关限流、抖动），这一段的数据本身是好的。
       * 跳过它就等于在录像里挖了个洞，而且用户看到的是「后面一段没画面」。
       * 正确做法是：原地重试（{@link _fetchGop} 内部已重试过 3 次），
       * 还失败就退避一小会儿再来一轮，而不是往前跳。
       */
      this.onSkip(i, '下载失败（将重试）：' + e.message)
      const retry = setTimeout(() => {
        if (this.aborted || this.sb !== sb) return
        this._feed()
      }, 1500)
      this._retryTimer = retry
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
      /*
       * 只处理「播放位置落在缓冲起点之前」。
       *
       * <p>早期这里还判 {@code currentTime > 末尾} 并拉回起点，
       * 那是没有拖动时的权宜做法；一旦支持拖动，
       * 往前拖到未下载区域会被这句立刻拽回起点 —— 进度条刚拖完就弹回去。
       * 落在了缓冲之后说明数据还在下载，交给 {@link _onSeek} 处理即可。
       */
      if (v.currentTime < start - 0.05) {
        this._setCurrentTime(start)
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
      dbg('可播', { ct: +v.currentTime.toFixed(2), start: +sb.buffered.start(0).toFixed(2), end: +sb.buffered.end(sb.buffered.length - 1).toFixed(2) })
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
    let bad = this.gopIndexAt(this.video.currentTime || 0)
    /*
     * 归因兜底（见 {@link lastFedIndex} 的说明）：
     * 时间归因指向的片段已经跳过了，说明真正的坏段是更后面、
     * 刚刚被下发的那一段 —— 改成它，否则会在同一段上无限重建。
     */
    if (this.skipSet.has(bad) && this.lastFedIndex > bad) {
      bad = this.lastFedIndex
    }
    /*
     * <b>已跳过的段不再重复归因</b>。
     *
     * <p>重建后 currentTime 会归零（还没播起来就报错的情况），
     * 时间归因必然指向第 0 段 —— 而它已经在 skipSet 里，
     * 于是「跳过 0 → 重建 → 还是指向 0」形成死循环。
     *
     * <p>所以这里往后找第一个没跳过的段；实在没有了才退回
     * {@link lastFedIndex}（刚下发的那段，多半就是坏的）。
     */
    if (this.skipSet.has(bad)) {
      let j = bad
      while (j < this.gops.length && this.skipSet.has(j)) j++
      if (j < this.gops.length) {
        bad = j
      } else if (this.lastFedIndex >= 0 && !this.skipSet.has(this.lastFedIndex)) {
        bad = this.lastFedIndex
      }
    }
    dbg('解码失败', {
      reason,
      byTime: this.gopIndexAt(this.video.currentTime || 0),
      bad,
      lastFedIndex: this.lastFedIndex,
      rebuilds: this.rebuilds + 1
    })
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
    /*
     * 必须在清 src <b>之前</b>取当前播放位置：
     * removeAttribute('src') 会把 currentTime 归零，
     * 之后再读就只能从头重播（原有实现的隐性浪费）。
     */
    const resume = this.video.currentTime || 0
    try {
      if (this.sb) {
        try { this.sb.abort() } catch (e) { /* 忽略 */ }
      }
      this.sb = null
      this.ms = null
      // 上一轮的下载守卫作废，交给新的 SourceBuffer 重新建立
      this._feeding = false
      this._feedingSb = null
      // 从出问题的片段之后继续（坏的那段已进 skipSet），
      // 并越过此前已经确认坏掉的段，避免反复重建同一处
      let idx = Math.min(this.gopIndexAt(resume) + 1, this.gops.length)
      while (idx < this.gops.length && this.skipSet.has(idx)) idx++
      this.nextIndex = idx
      this.lastFedIndex = -1
      dbg('重建', { resume: +resume.toFixed(2), nextIndex: idx, skipped: [...this.skipSet] })
      // _open 在 initialMediaSource 为空时会自己新建 MediaSource 并设 src
      await this._open()
      this._rebuilding = false
      dbg('重建完成，继续喂', { nextIndex: this.nextIndex })
      this._feed()
    } catch (e) {
      this._rebuilding = false
      dbg('重建失败', e.message)
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
    const target = Math.max(0, Math.min(time, (v.duration || 0) - 0.1))
    if (!Number.isFinite(target) || target < 0) {
      return
    }
    // 目标可能还没下载下来，先记着，数据到了再真正跳过去
    this.rememberSeekTarget(target)
    try {
      v.currentTime = target
    } catch (e) { /* 元数据未就绪 */ }
  }

  /**
   * 记住「用户想去的位置」。
   *
   * <p><b>为什么需要单独记</b>：MSE 的 {@code currentTime} 只能落在
   * {@code buffered} 范围内。用户拖到还没下载的位置时，浏览器会把
   * {@code currentTime} 拉回缓冲内（实测拖到 70% 却只到 8.9 秒），
   * 等数据下载完了也**不会自己跳过去** —— 画面停在原地，看起来像拖动无效。
   *
   * <p>所以记下目标，等对应片段喂进缓冲后再把播放位置挪过去。
   *
   * <p>记完<b>立刻驱动下载</b>：光记不下载的话，{@code _feed} 的窗口
   * 又是按 {@code _seekTarget} 算的，会一直判定「还没到该下的位置」，
   * 结果既不下载也不播放 —— 界面上就是永远转圈。
   *
   * @param {number} time 目标时间（秒）
   */
  rememberSeekTarget(time) {
    const t = Number(time)
    if (!Number.isFinite(t) || t < 0) {
      return
    }
    if (this._seekTarget != null && Math.abs(this._seekTarget - t) < 0.05) {
      // 同一个目标重复设置（播放器内部会设好几次），不重复驱动
      return
    }
    this._seekTarget = t
    this._driveSeek()
  }

  /**
   * 目标位置的数据到了没有？到了就把播放位置挪过去。
   *
   * @returns {boolean} 是否发生了跳转
   */
  _applySeekTarget() {
    if (this._seekTarget == null) {
      return false
    }
    const v = this.video
    const sb = this.sb
    if (!sb || !sb.buffered.length) {
      return false
    }
    const t = this._seekTarget
    for (let i = 0; i < sb.buffered.length; i++) {
      if (t >= sb.buffered.start(i) - 0.1 && t <= sb.buffered.end(i) - 0.05) {
        this._seekTarget = null
        try {
          this._setCurrentTime(t)
          dbg('跳到目标位置', { t: +t.toFixed(2) })
          return true
        } catch (e) {
          // 元数据未就绪，下一轮再试
          return false
        }
      }
    }
    return false
  }

  /**
   * 处理 video 的 {@code seeking} 事件。
   *
   * <p>按需下载模式下 buffered 只覆盖「已经喂过」的区间。用户拖动进度条时：
   * <ul>
   *   <li>目标在缓冲内 → 浏览器自己能处理，什么都不用做；</li>
   *   <li>目标在缓冲之后 → 把下载指针拨过去，中间那段不下载；</li>
   *   <li>目标在缓冲之前 → 清空缓冲后从目标处重新喂
   *       （片段可能被跳过，或早先因配额被清掉）。</li>
   * </ul>
   */
  _onSeek() {
    this._driveSeek()
  }

  /**
   * 把下载指针拨到「用户想去的位置」。
   *
   * <p><b>为什么要单独一个方法</b>：seek 的入口不止一个
   * （{@code seeking} 事件、{@code currentTime} 赋值、外部调用
   * {@link rememberSeekTarget}），而它们最终都做同一件事 ——
   * 让「目标位置的数据开始下载」。统一到一处，就不会漏掉某个入口。
   *
   * <p><b>为什么不能等 {@code seeking} 事件</b>：浏览器把 currentTime 设到
   * 未缓冲的位置时会挂起这次 seek，事件可能迟迟不来（或来时 ct 已被钳回
   * 旧值），而用户看到的就是「一直转圈」。
   */
  _driveSeek() {
    const sb = this.sb
    const v = this.video
    if (!sb || this.aborted || this._rebuilding) {
      return
    }
    /*
     * 用「用户意图的目标」而不是 currentTime。
     *
     * <p>拖到没下载的位置时，浏览器会把 currentTime 钳回缓冲内
     * （实测拖到 70% 只停在 8.9 秒）。{@link _seekTarget} 里存的是
     * 用户真正的落点，比 currentTime 准。
     */
    const t = this._seekTarget != null ? this._seekTarget : (v.currentTime || 0)
    for (let i = 0; i < sb.buffered.length; i++) {
      if (t >= sb.buffered.start(i) - 0.1 && t <= sb.buffered.end(i) - 0.05) {
        // 目标已在缓冲里，浏览器自己就能处理
        this._seekTarget = null
        return
      }
    }
    /*
     * 目标还没下下来：记下来，等数据到了再把播放位置挪过去。
     * （浏览器会把 currentTime 拉回缓冲内，不会自己跳回来。）
     */
    if (this._seekTarget == null) {
      this.rememberSeekTarget(t)
      return
    }
    const target = this.gopIndexAt(t)
    if (target >= this.nextIndex) {
      // 往前拖：跳着下载
      if (this.nextIndex !== target) {
        dbg('跳着下载', { from: this.nextIndex, to: target, t: +t.toFixed(2) })
        this.nextIndex = target
      }
      this._state = ''
      if (!sb.updating) {
        this._feed()
      }
      return
    }
    // 往回拖且缓冲里没有：清空后从目标片段重新喂
    this._state = ''
    this._restartFrom(target)
  }

  /**
   * 清空缓冲并从指定片段重新开始喂。
   *
   * @param {number} index GOP 序号
   */
  _restartFrom(index) {
    const sb = this.sb
    this.nextIndex = index
    if (!sb) {
      return
    }
    const clear = () => {
      try {
        sb.remove(0, Infinity)
      } catch (e) {
        if (!sb.updating) this._feed()
      }
    }
    if (!sb.buffered.length) {
      if (!sb.updating) this._feed()
      return
    }
    if (sb.updating) {
      // 正在 append 时 remove 会抛 InvalidStateError，等这一轮结束再清
      sb.addEventListener('updateend', clear, { once: true })
      return
    }
    // remove 完成后，_attach 里注册的 updateend 会自动继续喂
    clear()
  }
}
