/**
 * 统一播放器（xgplayer）：直播支持四种协议，回放直接播 MP4 文件。
 *
 * <h3>直播的四条数据通路</h3>
 * <table border="1">
 *   <tr><th>协议</th><th>延迟</th><th>实现</th><th>适用</th></tr>
 *   <tr><td>{@code wsflv}</td><td>≈1s</td><td>{@link WsFlvStream}（WebSocket）</td><td>默认，内网 / 同域</td></tr>
 *   <tr><td>{@code httpflv}</td><td>≈1s</td><td>{@link WsFlvStream}（fetch 流式）</td><td>WebSocket 被代理/防火墙拦掉时</td></tr>
 *   <tr><td>{@code webrtc}</td><td>≈0.3s</td><td>RTCPeerConnection</td><td>要最低延迟、网络尚可</td></tr>
 *   <tr><td>{@code hls}</td><td>≈4s</td><td>xgplayer-hls（按需加载）</td><td>只要 80/443 能通就能看</td></tr>
 * </table>
 * 前三者共用同一套「视频进 MSE、G.711 进 Web Audio」的拆轨逻辑：
 * 国标流是 PS + G.711，ZLM 转 FLV 后音频仍是 PCMA/PCMU，
 * MSE 只认 AAC/MP3/Opus，所以 {@code flv.js} / {@code xgplayer-flv} 这类
 * 纯 MSE 播放器一律抛 {@code CodecUnsupported}（官方插件也写明只支持
 * 「AAC 音频编码」）。HLS 的切片由 ZLM 重新封装，能交给 xgplayer-hls。
 *
 * <h3>录像回放</h3>
 * 后端提供的是 MP4 文件，直接交给 xgplayer 原生播放。浏览器靠 HTTP Range
 * 按需拉流，秒开、可任意拖动，进度条是 xgplayer 自带的。
 * 回放<b>不开放协议切换</b>：WebRTC 的 MediaStream 没有可推进的
 * {@code currentTime}（进度条不动），设备侧的 npt 定位也没法跨通路���用。
 *
 * <h3>容器约定</h3>
 * xgplayer 会在传入的容器里自建 {@code <video>} 与控件；本 composable 每次播放
 * 都新建一个宿主 div，销毁时整块移除，避免与上一轮实例的 DOM 回收打架。
 */

import { ref, shallowRef } from 'vue'
import { ElMessage } from 'element-plus'
import Player from 'xgplayer'
import 'xgplayer/dist/index.min.css'
import WsFlvStream from '@/player/WsFlvStream'
import { webrtcApi } from '@/api'

export function useXgPlayer(containerRef) {
  const playing = ref(false)
  const state = ref('空闲')
  const protocol = ref('webrtc')
  const currentStream = ref('')
  const stats = ref(null)
  /** 音频信息 `{ audioCodec: 'PCMA'|'PCMU', audioSampleRate }`。 */
  const audioInfo = ref(null)
  /** 视频信息 `{ codec, width, height, fps }`。 */
  const videoInfo = ref(null)
  /** 回放播放位置（秒，录像段内的相对位置）；直播恒为 0。 */
  const position = ref(0)

  const player = shallowRef(null)
  /** ws-flv 引擎实例。 */
  let stream = null
  /** WebRTC 通路实例。 */
  let pc = null
  /** 宿主 div（xgplayer 在其内部渲染）。 */
  let host = null
  /** MediaSource 的 object URL，销毁时必须 revoke。 */
  let objectUrl = null
  /** 待清理的定时器（等待推流提示等）。 */
  const waitTimers = []
  /**
   * 解码失败的重试次数。
   *
   * <p>放在闭包作用域而不是 playFlv 内部：重建播放器会重新走 playFlv，
   * 计数必须跨重建保留，否则会变成无限重试。
   */
  let decodeRetryCount = 0
  /**
   * 销毁标记：异步流程返回后若已停止，丢弃结果。
   */
  let disposed = false
  /** 稳定播放计时器（到时把解码重试预算归零，见 {@link playFlv} 的说明）。 */
  let stableTimer = null

  /**
   * 连续稳定播放多久算「上一次故障已恢复」（毫秒）。
   *
   * <p>10 秒大于任何一次正常重建的耗时（退避 1.2s×次数 + 等关键帧 1~2s），
   * 又远小于用户的实际观看时长 —— 能跑满就说明画面确实在动。
   */
  const STABLE_PLAY_MS = 10000
  /**
   * 直播「追帧微调」允许的倍速下界。
   *
   * <p>必须与 {@code WsFlvStream} 的 {@code LIVE_CHASE_RATE}（1.08）与
   * 回填速率（0.98）保持同步：这里是<b>放行闸门</b>，低于下界的值会被
   * 拉回 1 倍速。见 {@code playFlv} 里 {@code resetRate} 的说明。
   */
  const LIVE_RATE_MIN = 0.95
  /**
   * 直播「追帧微调」允许的倍速上界。
   *
   * <p>为什么取 1.1 而不是 1.08：留一点余量，避免浮点误差把合法的
   * 1.08 判成越界、反复拉回 1 —— 那会让追帧完全失效。
   */
  const LIVE_RATE_MAX = 1.1
  /**
   * 当前回放参数（录像段的 duration 与信令回调）。
   *
   * <p>存在闭包里是为了 <b>跨解码重试重建</b>保留：重建会重新走 playFlv，
   * 若每次都要调用方重新传，重建后的进度条就没了。
   */
  let currentVod = null

  /**
   * 播放器内「协议选择器」配置；跨重建保留。
   *
   * <p>见 {@link attachProtocolSwitch}。
   */
  let currentProtoSwitch = null

  /**
   * 播放。
   *
   * @param {string} streamId 流标识 `<设备编码>/<通道编码>`
   * @param {object} urls     播放地址集合 `{ wsFlv, httpFlv, webrtc, hls }`
   * @param {object} [opts]   `{ proto: 'wsflv'|'httpflv'|'webrtc'|'hls', vod: {...} }`
   * @param {function} [opts.onFatal] 播放彻底失败时的回调（解码错误重试耗尽后触发）
   * @param {object} [opts.vod] 录像回放参数，给了就切到「伪点播」模式：
   *   `{ duration, startOffset, onSeek, onPause, onResume, onRate }`。
   *   `startOffset` 表示本次流的 0 点对应录像段内的第几秒（默认 0）。
   *   见 {@link applyVodControls} 的说明。
   * @param {object} [opts.protocolSwitch] 把「播放协议」选择器放进播放器控制栏：
   *   `{ value, options: [{ value, label }], onChange(value) }`。
   *   见 {@link attachProtocolSwitch} 的说明。
   * @param {function} [opts.onStall] 流是通的但<b>始终没有视频轨</b>时的回调，
   *   由调用方重建整路流（停会话 + 重新 INVITE）。见 {@link playFlv} 的说明。
   * @param {boolean} [opts._rebuild] 内部重建标记（重建不清空解码重试计数）
   */
  async function play(streamId, urls, opts = {}) {
    if (!streamId) {
      state.value = '未指定流'
      return
    }
    stop()
    disposed = false
    currentStream.value = streamId
    const proto = opts.proto || protocol.value
    protocol.value = proto
    // 用户主动点播才算「重新开始」，重置解码重试计数；
    // 内部重建（解码失败自愈）要继续沿用计数，否则会变成无限重试。
    if (!opts._rebuild) decodeRetryCount = 0
    // 回放参数要跨重建保留，否则解码重试后进度条/拖动就失效了
    if (opts.vod !== undefined) currentVod = opts.vod
    /*
     * 协议菜单配置也要跨重建保留（否则切到 WebRTC 后就没有切回 FLV 的入口）。
     * 同时把 value 同步成「本次实际协议」：重建后菜单要显示当前值，
     * 不能还停在上次的选中项上。
     */
    if (opts.protocolSwitch !== undefined) currentProtoSwitch = opts.protocolSwitch
    if (currentProtoSwitch) currentProtoSwitch = { ...currentProtoSwitch, value: proto }

    if (proto === 'webrtc') {
      /*
       * WebRTC 失败时自动退回 FLV。
       *
       * <p><b>典型的失败原因</b>：
       * <ol>
       *   <li><b>设备是 H.265</b> —— WebRTC 规范里没有 H.265，大部分浏览器
       *       不会在 SDP 里协商出来，ZLM 没有可用编码 -> 轨道永远不到；</li>
       *   <li>NAT / 防火墙把 UDP 48000 拦了，ICE 连不通。</li>
       * </ol>
       * 这两种情况下 FLV（TCP）都能工作，所以退回一次，
       * 并把协议选择器同步成 FLV，不留“菜单写着 WebRTC、实际在放 FLV”的错位。
       *
       * <p>{@code _webrtcFallback} 防重入：只在第一次失败时退回，
       * 否则两路都失败就成了踢皮球。
       *
       * <p><b>为什么判 {@code ok === false} 而不是 {@code !disposed}</b>：
       * {@code playWebrtc} 失败时内部已经调过 {@code stop()}，而 stop() 会把
       * {@code disposed} 置为 true —— 此时看 disposed 永远是 true，
       * 回退就永远不会触发（实测确实如此）。所以用三态返回值：
       * {@code true} 成功 / {@code false} 真失败（该回退）/ {@code null} 已被外部停止（不回退）。
       */
      const ok = await playWebrtc(streamId)
      if (ok === false && !opts._webrtcFallback) {
        protocol.value = 'wsflv'
        if (currentProtoSwitch) currentProtoSwitch = { ...currentProtoSwitch, value: 'wsflv' }
        await play(streamId, urls, { ...opts, proto: 'wsflv', _webrtcFallback: true })
      }
    } else if (proto === 'hls') {
      await playHls(urls?.hls)
    } else {
      await playFlv(flvUrlOf(proto, urls), opts.onFatal, currentVod, opts.onStall,
        opts.onStable, opts.onLowBandwidth)
    }
  }

  /**
   * 取 FLV 地址。
   *
   * <p>{@code wsflv} 与 {@code httpflv} 是<b>同一路流</b>的两种承载
   * （见 {@link WsFlvStream}），任一地址缺失就回退到另一种，
   * 免得因为后端少拼一个字段就整路播不了。
   */
  function flvUrlOf(proto, urls) {
    if (!urls) return ''
    if (proto === 'httpflv') return urls.httpFlv || urls.wsFlv
    return urls.wsFlv || urls.httpFlv
  }

  /**
   * 直播通路：自研 ws-flv（视频喂 MSE，G.711 喂 Web Audio）。
   *
   * @param {object|null} vod 录像回放参数；非空时进入「伪点播」模式
   * @param {function} [onStall] 流通但始终无视频轨时的回调（需调用方重建会话）
   * @param {function} [onStable] 连续稳定播放达到 {@link STABLE_PLAY_MS} 时的回调，
   *        调用方可借此把自己那侧的「重建预算」也还回去
   * @param {function} [onLowBandwidth] 带宽持续不足时回调，参数含实测 kbps 与推进比
   */
  async function playFlv(url, onFatal, vod, onStall, onStable, onLowBandwidth) {
    if (!url) {
      state.value = '缺少播放地址'
      return
    }
    if (!containerRef.value) {
      state.value = '播放器容器未就绪'
      return
    }

    state.value = '连接中…'
    const uiConfig = baseUiConfig(vod ? 'vod' : 'live')

    // MediaSource 必须在创建播放器之前就绪：先拿到 object URL 交给 xgplayer，
    // 再把同一个 MediaSource 实例交给 ws-flv 引擎往里灌数据。
    if (!window.MediaSource) {
      state.value = '当前浏览器不支持 MSE，无法播放直播'
      return
    }
    const mediaSource = new MediaSource()
    objectUrl = URL.createObjectURL(mediaSource)

    const el = createHost()
    if (!el) return

    player.value = new Player({
      el,
      url: objectUrl,
      // 回放走「伪点播」：只有 isLive=false 才会渲染进度条与时间插件
      isLive: !vod,
      /*
       * 直播<b>不</b>自动播放：改由 WsFlvStream 攒够预缓冲后主动调 play()。
       *
       * <p>原来这里是 {@code true}，浏览器一看到有数据就开播 —— 而那时
       * buffer 里往往只有 0.1 秒，任何网络抖动都会 waiting，
       * 用户看到的就是「播两秒卡一下」。预缓冲逻辑见
       * {@code WsFlvStream.PREBUFFER_SECONDS}。
       *
       * <p>回放（vod）保持自动播放：它的数据来自 MP4 文件、按需 Range 拉取，
       * 不存在「到达速率跟不上」的问题，没有预缓冲的必要。
       */
      autoplay: !!vod,
      // 直播默认静音：浏览器只允许静音自动播放；点音量按钮即可出声。
      // xgplayer 的顶层配置项是 autoplayMuted（不是 muted）。
      autoplayMuted: true,
      // G.711 音频走 Web Audio，video 元素保持静音即可
      lang: 'zh-cn',
      width: '100%',
      height: '100%',
      // 直播不显示进度条（isLive 已让预设移除 Progress/Time 等点播控件）
      ...uiConfig
    })

    const p = player.value
    const media = p.media
    // 协议选择器放进控制栏（像视频网站的清晰度切换）
    attachProtocolSwitch(p, currentProtoSwitch)

    /** 解码/媒体源失败后的最大重建次数（跨重建保留计数，见 decodeRetryCount）。 */
    const MAX_DECODE_RETRY = 3

    /**
     * 重建播放器（丢包坏帧、媒体源作废后的自愈）。
     *
     * <p><b>为什么只能整个重建</b>：video 元素一旦报错，它绑定的
     * MediaSource 就永久作废 —— 不能续用、也不能清空复用，只能新建
     * MediaSource、重新连一路 FLV、重新等一个完整的关键帧。
     *
     * <p><b>为什么退避 1.2s × 次数</b>：立刻重连大概率拿到同一批坏数据，
     * 退避让设备有机会走到下一个 GOP。
     *
     * <p>所有不可恢复的错误（解码失败、媒体源关闭、队列积压、流层主动放弃）
     * 都走这里。<b>不能只处理 MEDIA_ERR_DECODE</b>：其余错误码既不重试也不
     * 报错时，播放器会停在那儿一动不动，用户只能关掉重弹窗重播。
     */
    function scheduleRebuild(reason) {
      if (disposed) return
      if (decodeRetryCount >= MAX_DECODE_RETRY) {
        state.value = `播放异常：${reason}（已重试 ${MAX_DECODE_RETRY} 次）`
        playing.value = false
        if (onFatal) onFatal()
        return
      }
      decodeRetryCount++
      state.value = `${reason}，重试中（${decodeRetryCount}/${MAX_DECODE_RETRY}）…`
      playing.value = false
      const timer = setTimeout(() => {
        if (disposed) return
        stop()
        /*
         * vod 必须显式回传：stop() 会清空 currentVod，不传就变成直播模式了。
         *
         * <p>协议也要沿用本次的：原来写死 {@code proto: 'wsflv'}，
         * 用户在 HTTP-FLV 下解码失败重建时会被默默换成 WebSocket 通路，
         * 菜单上的标签与实际通路对不上。两种 FLV 地址都传，
         * 让 {@link flvUrlOf} 按协议选。
         */
        play(currentStream.value, { wsFlv: url, httpFlv: url }, {
          proto: protocol.value === 'httpflv' ? 'httpflv' : 'wsflv',
          onFatal,
          _rebuild: true,
          vod
        })
      }, 1200 * decodeRetryCount)
      waitTimers.push(timer)
    }

    stream = new WsFlvStream({
      url,
      mediaSource,
      media,
      // 回放不做音画同步：位置/倍速由设备侧控制（见 WsFlvStream 的 vod 说明）
      vod: !!vod,
      /*
       * 预缓冲攒够 → 用播放器自己的 API 起播。
       *
       * <p><b>不能直接 media.play()</b>：直播 autoplay 为 false（为了等预缓冲），
       * xgplayer 内部仍认为自己在暂停态。绕过它播放 video 元素，
       * 画面会动但中央的大播放按钮一直盖着 —— 用户看到「视频在播却有个播放图标」。
       * 走 {@code p.play()} 后，播放器状态与控制栏一起更新。
       *
       * <p>回放（vod）不需要：它 autoplay=true，本来就在播。
       */
      onPrebuffered: () => {
        if (disposed || vod) return
        const pl = player.value
        if (!pl) return
        try {
          // play() 返回 Promise：用户后续点了暂停时这里可能 reject，忽略即可
          const r = pl.play()
          if (r && typeof r.catch === 'function') r.catch(() => {})
        } catch (e) {
          // 播放器已销毁等边缘情况：退回直接播 video，至少画面能动
          media.play().catch(() => {})
        }
      },
      onError: (msg, retryable) => {
        if (disposed) return
        /*
         * 编码不支持这类确定性失败重建也没用，直接报给用户；
         * 其余（解码失败、媒体源关闭、队列积压）都值得重建一次。
         */
        if (retryable === false) {
          state.value = '播放异常：' + msg
          playing.value = false
          if (onFatal) onFatal()
          return
        }
        scheduleRebuild(msg)
      },
      onStats: (info) => {
        if (disposed) return
        if (info.audioCodec) {
          audioInfo.value = info
        } else {
          videoInfo.value = info
          // 收到视频数据说明设备已经在推流，撤掉「等待设备推流」提示
          if (state.value === '等待设备推流…') state.value = '缓冲中…'
        }
        stats.value = info
      },
      /*
       * 带宽持续不足 → 直接告诉用户「是网络不够」，并引导切 WebRTC。
       *
       * <p><b>为什么要在播放器里判、而不是让它一直卡</b>：用户看到的现象只有
       * 「卡」，而卡可能是解码失败、代码 bug、带宽不足三种完全不同的原因。
       * 播放器手里有两个数能把它们分开 —— 到达字节 / 媒体时间推进比。
       * 报出实测值后，用户才能去查网络（或找运营商），而不是反复重启平台。
       *
       * <p>真机实测（公网 ws-flv）：流需要 980 kbps，浏览器实际只收到 233 kbps，
       * 媒体时间推进比 0.18，50 秒里 34 次 waiting —— 这种情况无论怎么调
       * 缓冲/追帧都不可能流畅，因为 TCP 从头到尾就没把数据送够。
       */
      onSlowNetwork: (info) => {
        if (disposed) return
        const pct = Math.round((info.ratio || 0) * 100)
        state.value =
          `网络带宽不足（实测 ${info.kbps} kbps，画面只能推到 ${pct}%）。` +
          '请切换协议为 WebRTC'
        /*
         * 同时弹一次 toast：{@code state} 会被后续的 {@code waiting} 改成
         * 「缓冲中…」，用户来不及看清原因就消失了。toast 独立存活 5 秒，
         * 且带实测数字，用户能拿去查网络而不是反复重启平台。
         */
        ElMessage({
          type: 'warning',
          duration: 8000,
          message:
            `当前线路带宽不足：实测 ${info.kbps} kbps，画面只能推进到 ${pct}%。` +
            'FLV 走 TCP，带宽不够必然卡；请切换为 WebRTC（UDP，不受丢包阻塞）。',
          showClose: true
        })
        if (onLowBandwidth) onLowBandwidth(info)
      }
    })
    stream.start()

    // 等待设备推流：设备收到 INVITE 后才开始推 RTP，ZLM 拿到第一帧才注册
    // 媒体源。这中间有几秒空窗，此时 WebSocket 连上了但收不到任何数据。
    // 不给提示的话画面格会一直空着，用户以为没点上。
    const waitTimer = setTimeout(() => {
      if (disposed || !stream) return
      if (stream.receivedBytes === 0) {
        state.value = '等待设备推流…'
      }
    }, 4000)
    waitTimers.push(waitTimer)

    /*
     * 「有数据但永远没画面」的看门狗。
     *
     * <p>这是现场最常见的怪病：日志里流是通的、播放器一直停在
     * 「等待设备推流…」，关掉重播就好了。根因在服务端：
     * ZLM 等各条轨就绪有时间上限，超时就把没就绪的轨<b>永久丢弃</b>
     * （{@code Track not ready for a long time, ignored: H264}），
     * 媒体源从此只有音频。而丢的往往正是开头的 SPS/PPS + 首个 I 帧 ——
     * 设备刚收到 INVITE 时会一口气把攒着的码流倾出来，RTP 收流缓冲装不下
     * 就丢包。
     *
     * <p>这个状态<b>在本播放器内无法自愈</b>：连的是同一个媒体源，
     * 重连 WebSocket 一万次也还是没有视频轨。必须让服务端重建媒体源
     * （停会话 + 重新 INVITE），所以只能回调给调用方。
     *
     * <p>判定条件要同时满足：①已经收到字节（说明编码器在推、网络通）；
     * ②始终没拿到关键帧。只满足 ②是「设备还没开始推」，那由上面的
     * 提示负责，不该触发重建。
     */
    const stallTimer = setTimeout(() => {
      if (disposed || !stream) return
      if (stream.receivedBytes > 0 && !stream.gotKeyframe) {
        state.value = '画面未就绪，正在重新拉流…'
        playing.value = false
        if (onStall) onStall()
      }
    }, 12000)
    waitTimers.push(stallTimer)

    /*
     * 「连上了但一个字节都收不到」的看门狗 —— 另一类永远黑屏。
     *
     * <p>ZLM 的媒体源可能<b>只有音频轨</b>：设备推流慢一点（实测某下级平台
     * INVITE 应答后要 11 秒才推视频），ZLM 等轨道就绪超时就把没就绪的轨
     * <b>永久丢弃</b>（日志 {@code Track not ready for a long time, ignored: H264}），
     * 媒体源从此只有 PCMA。播放器连上去一个视频字节都收不到，
     * 状态栏永远停在「等待设备推流…」，而设备其实早就在推了。
     *
     * <p><b>为什么上面的 stallTimer 救不了它</b>：那个判据要求
     * {@code receivedBytes > 0}，而这种情况字节数恒为 0 —— 一个条件都不满足，
     * 于是永远不重建，用户只能关掉重播。
     *
     * <p>窗口取 20 秒：覆盖「设备真的慢」的实测值（11 秒）并留余量；
     * 重建会换新 SSRC 与端口、ZLM 重建媒体源，通常 1~2 秒内就能拿到完整轨道。
     */
    const noDataTimer = setTimeout(() => {
      if (disposed || !stream) return
      if (stream.receivedBytes === 0) {
        state.value = '未收到视频数据，正在重新拉流…'
        playing.value = false
        if (onStall) onStall()
      }
    }, 20000)
    waitTimers.push(noDataTimer)

    // video 的音量/静音按钮由 xgplayer 操作，Web Audio 这条链路要跟着变
    const syncVolume = () => stream?.setVolume(media.muted ? 0 : media.volume)
    media.addEventListener('volumechange', syncVolume)
    syncVolume()

    /*
     * 播放中途进入缓冲时，把状态切回「缓冲中…」。
     *
     * <p><b>为什么必须补这一段</b>：原来只有下面 `playing` 里置「播放中」，
     * 之后再不更新。于是一旦中途重新缓冲（MSE 欠载、公网抖动），
     * 状态角标仍写着「播放中」，而画面正中央转着播放器自己的加载圈 ——
     * 两个指示互相矛盾，用户无法判断是「在播但卡了」还是「平台状态显示错了」。
     *
     * <p><b>为什么要 600ms 防抖</b>：`waiting` 在正常播放里也会零星触发
     * （每次换片、每个 GOP 边界之后都可能来一下，几十毫秒即恢复）。
     * 直接用会让角标在「播放中/缓冲中」之间高频闪烁，比不显示更糟。
     * 只有真正卡住超过 600ms 才是用户需要知道的事。
     *
     * <p>`playing` 会再次触发（下面那个 handler 置回「播放中」），所以
     * 这里不需要配套的恢复逻辑，只需在恢复时取消这个定时器。
     */
    let rebufferTimer = null
    const cancelRebuffer = () => {
      if (rebufferTimer) {
        clearTimeout(rebufferTimer)
        rebufferTimer = null
      }
    }
    media.addEventListener('waiting', () => {
      if (disposed) return
      cancelRebuffer()
      rebufferTimer = setTimeout(() => {
        rebufferTimer = null
        if (disposed) return
        // 已暂停不是「缓冲」，那是用户/播放器有意停住
        if (!media.paused) state.value = '缓冲中…'
      }, 600)
    })
    media.addEventListener('playing', cancelRebuffer)

    p.on('playing', () => {
      if (disposed) return
      playing.value = true
      state.value = '播放中'
      /*
       * 稳定播放一段时间后把重试预算还回去。
       *
       * <p>{@code decodeRetryCount} 必须跨重建保留（否则会变成无限重试），
       * 但它<b>只增不减</b>：一次播放里偶发几次丢包坏帧（公网 4% 丢包很正常），
       * 预算就被<b>永久</b>耗光 —— 此后哪怕已经稳定跑了半小时，
       * 再碰上一次坏帧就直接报「已重试 3 次」并停住（实测就是这样卡死的）。
       *
       * <p>这类故障彼此独立：能稳定播放就说明上一次确实恢复了，
       * 所以跑满窗口就把计数归零。撑不到窗口不会归零，因此不会退化成无限重试。
       */
      if (stableTimer) clearTimeout(stableTimer)
      stableTimer = setTimeout(() => {
        stableTimer = null
        if (disposed) return
        decodeRetryCount = 0
        if (onStable) onStable()
      }, STABLE_PLAY_MS)
    })

    // 直播不允许暂停：把 media 的 pause 事件拉回来。
    //
    // 为什么不能只靠 ignores 去掉 play 插件：键盘空格键走的是
    // player 自身的 playPause action（keyboard 插件的默认键位），
    // 不依赖 play 插件；浏览器媒体策略、页面切后台也会触发 pause。
    // 所以这里做兜底 —— 任何原因导致的暂停都立即恢复播放。
    //
    // 回放（vod）例外：暂停是有意义的操作，交给设备执行（SIP PAUSE），
    // 见 applyVodControls。
    if (!vod) {
      media.addEventListener('pause', () => {
        if (disposed) return
        // 停止播放时（stop）会主动 pause，那时要放过
        if (!stream) return
        media.play().catch(() => {})
      })
    }

    // 直播不允许变速：倍速按钮已移除，这里再兜底一次，
    // 防止外部代码或残留的 rate 状态影响直播速度。
    // 回放的倍速由 applyVodControls 转成设备侧 Scale。
    if (!vod) {
      /*
       * 倍速兜底：只拦「明显异常」的值，放行追帧的微调区间。
       *
       * <p><b>为什么不能一律拉回 1</b>：{@code WsFlvStream._chaseLiveEdge()}
       * 靠 1.08 / 0.98 这类微调来吃掉多余缓冲、回填即将吃穿的缓冲。
       * 这里若把任何非 1 的倍速都拉回 1，软追帧就<b>完全失效</b>，
       * 只剩硬跳一条路 —— 实测表现为缓冲反复被吃穿到 0.03 秒，
       * 25 秒里 8 次 waiting（每次卡 100~400ms）。
       *
       * <p>契约：追帧只使用 [LIVE_RATE_MIN, LIVE_RATE_MAX] 内的值；
       * 超出这个范围一定是外部误设（残留状态、键盘快捷键等），
       * 必须拉回 1 倍速。
       */
      const resetRate = () => {
        const r = media.playbackRate
        if (r < LIVE_RATE_MIN || r > LIVE_RATE_MAX) {
          media.playbackRate = 1
        }
      }
      media.addEventListener('ratechange', resetRate)
      resetRate()
    } else {
      applyVodControls(p, media, vod, position)
    }

    // 解码错误的自愈。
    //
    // 国标设备通过公网推流，RTP 丢包很常见（ZLM 日志里的
    // `rtp丢包` / `packet dropped`）。若丢包正好落在关键帧上，
    // 硬解会直接失败：
    //   PIPELINE_ERROR_DECODE: NSOSStatusErrorDomain Code=-12909
    //   (VTDecompressionOutputCallback)
    // 一旦 video 元素报错，MediaSource 就废了（不能续用），
    // 只能整个重建 —— 重新连 WebSocket、重新等一个完整的关键帧。
    // 网络恢复后的下一个 GOP 通常是好的，所以重建大多能救回来。
    media.addEventListener('error', () => {
      if (disposed) return
      /*
       * 任何错误码都要处理，不能只认 code 3：
       *   2 MEDIA_ERR_NETWORK        —— 读流中断，重建会重新连
       *   3 MEDIA_ERR_DECODE         —— 丢包坏帧，下一个 GOP 通常是好的
       *   4 MEDIA_ERR_SRC_NOT_SUPPORTED —— MediaSource 已作废
       * 只处理 code 3 时，其余错误既不重试也不提示，画面永远停在那里。
       */
      const err = media.error
      scheduleRebuild(err?.message || `媒体错误（code=${err?.code ?? '?'}）`)
    })

    // MSE 起播需要主动拉一次；失败不致命（等数据到了会自动起）
    try {
      await media.play()
    } catch (e) {
      // 忽略 AbortError / NotAllowedError
    }
    if (!disposed && state.value === '连接中…') state.value = '缓冲中…'
  }

  /**
   * HLS 通路（兼容性最好，延迟约 4~6 秒）。
   *
   * <p>ZLM 的 HLS 参数是 {@code segDur=2} + {@code segNum=3}，播放窗口只有
   * 3 个切片 ≈ 6 秒。比 ws-flv / WebRTC 延迟高，但它是唯一的
   * <b>纯 HTTP、无长连接</b>通路：只要 80/443 能通就能看，
   * 适合企业代理 / 防火墙环境。
   *
   * <h3>为什么用 xgplayer-hls 而不是 hls.js</h3>
   * <ul>
   *   <li><b>同一套内核</b>：xgplayer-hls 是西瓜官方插件，基于
   *       {@code xgplayer-transmuxer}（本项目已在用），与播放器版本一一对应；
   *       而 hls.js 是另一套独立实现，两边各管各的 buffer，容易出现
   *       “插件以为自己在控制 media、实际被别人改了”的怪问题。</li>
   *   <li><b>直播参数直接可用</b>：{@code targetLatency} / {@code maxLatency} /
   *       {@code disconnectTime} / {@code retryCount} / {@code loadTimeout}
   *       都是面向直播的语义；hls.js 对应的是 {@code liveSyncDurationCount}
   *       这类偏底层的配置。</li>
   *   <li><b>体积更小</b>：不额外引入 hls.js，插件体量约为它的一半。</li>
   * </ul>
   *
   * <h3>为什么优先用插件，而不是浏览器的原生 HLS</h3>
   * Chrome 148 起 {@code canPlayType('application/vnd.apple.mpegurl')} 已经返回
   * {@code "maybe"}，看起来可以省掉一整个内核 —— 但实测它<b>只拿到数据不起播</b>：
   * {@code readyState=4}、buffer 已冿 10 秒，{@code paused} 却一直是 true，
   * 手动调 {@code play()} 之后也很快又停住。原生实现既不能控直播延迟
   * （不能指定 targetLatency），也不管刷新失败重试，而且各版本行为不一。
   * <p>所以：<b>能加载插件就用插件</b>，只有插件不可用（老浏览器 / 无 MSE）
   * 时才回退到原生 HLS（Safari / iOS 走这条）。判断必须在
   * {@code new Player()} <b>之前</b>做 —— 插件要在构造时就注册进
   * {@code plugins}，事后加不了。
   *
   * <p>仅用于直播。录像回放是 MP4 文件（见 {@link playFlv} 的伪点播模式），
   * 设备侧按 npt 定位，不存在 HLS 切片。
   */
  async function playHls(url) {
    if (!url) {
      state.value = '缺少播放地址'
      return
    }
    if (!containerRef.value) {
      state.value = '播放器容器未就绪'
      return
    }

    state.value = '加载 HLS 内核…'
    let HlsPlugin = null
    let hlsEvent = null
    try {
      // 动态加载：不播 HLS 的用户不下这包，首屏不受影响
      const mod = await import('xgplayer-hls')
      if (disposed) return
      HlsPlugin = mod.default || mod
      hlsEvent = mod.EVENT
      if (!HlsPlugin.isSupported()) HlsPlugin = null
    } catch (e) {
      // 内核加载失败不致命，下面还有原生 HLS 兜底
      HlsPlugin = null
    }

    // 连插件都不可用时才看原生能力（Safari / iOS 走这条）
    const nativeHls = !HlsPlugin && !!document
      .createElement('video')
      .canPlayType('application/vnd.apple.mpegurl')
    if (!HlsPlugin && !nativeHls) {
      state.value = '当前浏览器不支持 HLS 播放'
      return
    }

    const el = createHost()
    if (!el) return
    state.value = '连接中…'

    const conf = {
      el,
      url,
      isLive: true,
      autoplay: true,
      autoplayMuted: true,
      lang: 'zh-cn',
      width: '100%',
      height: '100%',
      ...baseUiConfig()
    }
    if (HlsPlugin) {
      conf.plugins = [HlsPlugin]
      conf.hls = {
        /*
         * 延迟必须在「直播窗口」之内才有意义：ZLM 只保留 3 个切片
         * （实测 EXTINF 3.33s，窗口 ≈ 10 秒）。默认 targetLatency=10
         * 正好把整个窗口都耗完，播放点被夹到窗口起点、每次刷新重新贴边，
         * 反而起播慢。设 4 秒既留了抗抖动余量又不浪费窗口。
         */
        targetLatency: 4,
        maxLatency: 8,
        /*
         * 默认 3 秒 —— 比本平台的切片时长（3.33s）还短，意味着一但中间
         * 少一个切片就「跳不过去」，只能原地重试到超时。
         */
        maxJumpDistance: 10,
        // 用户压暂停时不立刻断流（默认 0 = 立刻），避免误触就重连一次
        disconnectTime: 10,
        retryCount: 3,
        retryDelay: 1000,
        loadTimeout: 10000
      }
    }

    /*
     * HLS 预热。
     *
     * <p>ZLM 的 HLS 是<b>按需切片</b>：没人请求 m3u8 时不写 .ts。刚点播时
     * 第一次拉到的 m3u8 里列的是「激活之前」的切片名，磁盘上根本没有这些文件，
     * 播放器去取就拿到 404，插件直接判定 fatal 并停在「网络错误」页。
     *
     * <p>所以先自己拉一次 m3u8 让 ZLM 开始切片，再交给播放器。
     */
    try {
      await fetch(url, { cache: 'no-store' })
    } catch (e) {
      // 拉不到不阻断，交给播放器自己报错（它也有重试）
    }
    if (disposed) return

    player.value = new Player(conf)

    const p = player.value
    attachProtocolSwitch(p, currentProtoSwitch)

    p.on('playing', () => {
      if (disposed) return
      playing.value = true
      state.value = '播放中'
    })

    /*
     * 直播不允许「停在那儿」。
     *
     * <p>实测插件在某些时序下会把 media 留在 paused 状态：buffer 已经叠了
     * 10 秒、readyState=4，currentTime 却不再推进（画面定格）。
     * 这与 WebRTC / ws-flv 直播通路是同一个约束，处理方式也保持一致：
     * 只要不是我们主动销毁，一旦被暂停就立刻恢复。
     *
     * <p>不会死循环：{@code play()} 被浏览器拒绝时是 reject 而不是再触发
     * pause，所以最多多调一次。
     */
    const media = p.media
    media.addEventListener('pause', () => {
      if (disposed || p.__closing || !media.src) return
      media.play().catch(() => {})
    })

    /** 因「切片不存在」重放过几次（每个播放器实例独立）。 */
    let hlsRetry = 0

    /*
     * 解析出错一律走 xgplayer 的 error 事件（插件会把内核错误代理上来）；
     * 原生 HLS 场景没有插件，只能听 media 自己的 error。
     *
     * <p>预热只能减少 404，不能完全消除（比如播放中 HLS 被重启、切片被清理）。
     * 遇到这种错误直接重放：{@code core.replay()} 会丢弃旧列表、重新拉 m3u8，
     * 那时指向的都是真实存在的切片，比原地重试同一个已消失的 URL 有用。
     */
    p.on('error', (err) => {
      if (disposed) return
      if (hlsRetry < 2 && p.plugins?.hls?.core) {
        hlsRetry++
        state.value = `HLS 重连中（${hlsRetry}/2）…`
        const t = setTimeout(() => {
          if (disposed) return
          try {
            p.plugins.hls.core.replay()
          } catch (e) {
            stop()
          }
        }, 1500)
        waitTimers.push(t)
        return
      }
      state.value = '播放异常：' + ((err && (err.message || err.errorType)) || 'HLS 加载失败')
      playing.value = false
    })

    if (hlsEvent && HlsPlugin) {
      // 元信息（编码/分辨率）：预览状态栏要显示
      p.on('core_event', (e) => {
        if (disposed || !e || e.eventName !== hlsEvent.METADATA_PARSED) return
        if (e.type !== 'video' || !e.meta) return
        const info = { codec: e.meta.codec, width: e.meta.width, height: e.meta.height }
        videoInfo.value = info
        stats.value = info
        if (state.value === '连接中…') state.value = '缓冲中…'
      })
    } else {
      // 原生 HLS：没有插件就不会自动拉起播放，需要手动 play 一次
      media.addEventListener('loadedmetadata', () => {
        if (disposed) return
        media.play().catch(() => {})
      }, { once: true })
    }
  }

  /**
   * WebRTC 通路（低延迟备用）。
   *
   * <p><b>同样用 xgplayer</b>，UI 与 ws-flv 通路完全一致（控制栏、音量、
   * 全屏按钮都是同一套）。区别只在媒体源怎么喂：
   * ws-flv 走 MediaSource 的 blob URL，WebRTC 的流是 {@code MediaStream}，
   * 必须设到 {@code video.srcObject} 上。
   *
   * <p><b>为什么不能把 MediaStream 当 url 传给 xgplayer</b>：xgplayer 的
   * {@code url} 只接受字符串（内部走 {@code media.src = url}），而
   * {@code URL.createObjectURL(MediaStream)} 早已被浏览器移除。
   *
   * <p><b>为什么先协商再建播放器</b>：xgplayer 创建时会立刻拿 url 去 load。
   * 如果这时 url 是空串，浏览器会报 {@code MEDIA_ERR_SRC_NOT_SUPPORTED}，
   * xgplayer 随即显示「不支持的音频/视频格式 请试试刷新」——
   * 而其实 WebRTC 还在协商，流根本还没到。所以这里先把 SDP 换完、
   * {@code ontrack} 拿到流，再创建播放器并立刻挂 {@code srcObject}，
   * 用户不会看到那个误导性的错误画面。
   *
   * <h3>音频：必须主动把编码限制成 PCMA / PCMU</h3>
   *
   * <p><b>不限制的话 WebRTC 永远没有声音</b>（这是之前一直没声音的真正原因）：
   * 不指定编码时，Chrome 的 offer 音频段是
   * {@code 111 63 9 0 8 13 …}（opus 排第一），而 <b>ZLM 只会挑 opus 回</b>：</p>
   * <pre>
   *   offer : m=audio 9 … 111 63 9 0 8 13 110 126
   *   answer: m=audio 48000 … 111            ← 只有 opus
   * </pre>
   * <p>国标设备推的是 G.711（PCMA/PCMU），而 ZLM 要把它转成 opus 就得有
   * ffmpeg —— 嵌入式 ZLM 没有，于是答复里写着 opus、却一个音频包都不发。
   * 实测：{@code audioMuted=true}、{@code inbound-rtp} 一条都没有。</p>
   *
   * <p>改用 {@code setCodecPreferences} 只留 PCMA/PCMU 后，ZLM 直接回
   * {@code m=audio … 8}（PCMA）并<b>原样透传</b>，实测 8 秒收到
   * <b>47040 字节 / 294 个包</b>，音轨 {@code live}。浏览器原生就支持
   * PCMU/PCMA（{@code RTCRtpReceiver.getCapabilities('audio')} 里有），
   * 所以不需要任何 WASM 解码器。</p>
   *
   * <p><b>仍有限制</b>：如果设备的音频是 <b>AAC</b>（实测部分下级平台通道是 AAC），
   * WebRTC 这条路依然没声音 —— 浏览器不支持 AAC-in-WebRTC，
   * ZLM 也转不了。这类通道需要声音时切 FLV（FLV 的 AAC 由 MSE 原生解码）。</p>
   */
  async function playWebrtc(streamId) {
    const el = createHost()
    if (!el) return false
    state.value = '协商中…'

    try {
      const conn = new RTCPeerConnection({ iceServers: [] })
      pc = conn

      /*
       * 显式声明双向的收发器，并把音频编码限制成 G.711（PCMA/PCMU）。
       *
       * <p>不能用 {@code createOffer({offerToReceiveAudio:true})} 代替：
       * 那种写法拿不到 transceiver 引用，也就无法调 setCodecPreferences，
       * 而“不限制音频编码”正是之前 WebRTC 没声音的原因（见方法注释）。
       */
      conn.addTransceiver('video', { direction: 'recvonly' })
      const audioTx = conn.addTransceiver('audio', { direction: 'recvonly' })
      try {
        const caps = window.RTCRtpReceiver && RTCRtpReceiver.getCapabilities
          ? RTCRtpReceiver.getCapabilities('audio')
          : null
        if (caps && caps.codecs) {
          const g711 = caps.codecs.filter((c) => /PCMA|PCMU/i.test(c.mimeType || ''))
          if (g711.length) audioTx.setCodecPreferences(g711)
        }
      } catch (e) {
        // 老浏览器不支持 setCodecPreferences：保持默认（可能没有声音，但不影响画面）
      }

      /*
       * 所有轨道并入<b>同一个</b> MediaStream。
       *
       * <p>音视频的 ontrack 是分开触发的，且不保证顺序（实测音频比视频晚几毫秒）。
       * 若像原来那样「收到第一条轨就 resolve 并把 event.streams[0] 交出去」，
       * 后到的音频就会丢 —— 因为 video 元素只认 srcObject 那个对象，
       * 而后续轨道会被加进另一个（或没有）流里。
       *
       * <p>改成共用 remoteStream 后，即使音频晚到，只要 addTrack 到同一个对象，
       * 已经在播的 video 元素会自动开始出声（浏览器行为）。
       */
      const remoteStream = new MediaStream()
      const streamPromise = new Promise((resolve) => {
        conn.ontrack = (event) => {
          remoteStream.addTrack(event.track)
          // 有任何一条轨就 resolve：只等视频会在纯音频流上永久挂住
          resolve(remoteStream)
        }
      })

      conn.onconnectionstatechange = () => {
        if (disposed || !pc) return
        if (conn.connectionState === 'failed' || conn.connectionState === 'disconnected') {
          state.value = '连接中断'
          playing.value = false
        }
      }

      const offer = await conn.createOffer()
      await conn.setLocalDescription(offer)
      await waitIceGathering(conn)
      if (disposed) return
      const peerId = 'web-' + Math.random().toString(36).slice(2, 10)
      const data = await webrtcApi.play(streamId, peerId, conn.localDescription.sdp)
      if (disposed) return
      if (!data || !data.sdp) throw new Error('未获取到 SDP answer')
      await conn.setRemoteDescription({ type: 'answer', sdp: data.sdp })

      // 等轨道到达（最多 8 秒，超时按失败处理）
      const stream = await Promise.race([
        streamPromise,
        new Promise((_, reject) => setTimeout(() => reject(new Error('等待视频轨道超时')), 8000))
      ])
      if (disposed) return

      /*
       * 用「空的 MediaSource」作初始 url：它是<b>合法媒体源</b>，
       * xgplayer 初始化时不会报「不支持的格式」；随后立刻被 srcObject 接管，
       * 那个空的 MediaSource 不会再被用到。
       */
      const ms = new MediaSource()
      objectUrl = URL.createObjectURL(ms)

      player.value = new Player({
        el,
        url: objectUrl,
        isLive: true,
        autoplay: true,
        autoplayMuted: true,
        lang: 'zh-cn',
        width: '100%',
        height: '100%',
        ...baseUiConfig()
      })

      const p = player.value
      const media = p.media
      // 协议选择器放进控制栏（WebRTC 通路同样需要，否则切回 FLV 就无入口了）
      attachProtocolSwitch(p, currentProtoSwitch)

      // 媒体流挂到 xgplayer 内部的 video 上，控制栏/音量/全屏就都归它管了
      media.srcObject = stream
      media.play().catch(() => {})

      // 直播不允许暂停（与 ws-flv 通路一致）：任何原因导致的暂停都立即恢复。
      media.addEventListener('pause', () => {
        if (disposed || !media.srcObject) return
        media.play().catch(() => {})
      })

      // 直播不允许变速
      const resetRate = () => {
        if (media.playbackRate !== 1) media.playbackRate = 1
      }
      media.addEventListener('ratechange', resetRate)
      resetRate()

      p.on('playing', () => {
        if (disposed) return
        playing.value = true
        state.value = '播放中'
      })

      playing.value = true
      state.value = '播放中'
      return true
    } catch (e) {
      /*
       * {@code null} 而不是 {@code false}：已经 disposed 说明是外部主动停止
       * （切换通道、关闭弹窗），不是 WebRTC 本身的问题 —— 回退到 FLV
       * 会把用户已经关掉的画面重新拉起来，属于灵异行为。
       */
      if (disposed) return null
      state.value = '失败：' + (e.message || e)
      playing.value = false
      stop()
      return false
    }
  }

  /**
   * 切换通路（播放中自动重连）。
   *
   * <p><b>切换会重建播放器实例</b>（FLV 用 MSE 灌流、WebRTC 用 RTCPeerConnection，
   * 两种内核无法复用实例）。而重建必然移除旧 DOM，若此时正处于
   * <b>浏览器全屏</b>，全屏元素消失 → 浏览器自动退出全屏（实测每次切换都掉出全屏）。
   * 因此这里在重建前记录全屏归属，重建完成后重新申请一次。
   */
  async function switchProtocol(proto, urls) {
    if (proto === protocol.value && playing.value) return
    /*
     * 目标通路没有可用地址时<b>不要动现有播放器</b>。
     *
     * <p>原实现先 stop() 再 play()，一旦 urls 为空（比如流已被空闲巡检关闭、
     * 查询接口返回 400），播放器就被拆掉了却接不上新的源 ——
     * 用户看到的是「切个协议画面就没了，也不报错」。
     */
    const target = proto === 'webrtc'
      ? urls?.webrtc
      : proto === 'hls'
        ? urls?.hls
        : flvUrlOf(proto, urls)
    if (!target) {
      state.value = '缺少播放地址'
      return
    }
    protocol.value = proto
    const streamId = currentStream.value
    const wasPlaying = playing.value
    // 必须在 stop() 之前取：stop 会 remove 宿主节点，之后 fullscreenElement 就变 null 了
    const wasFullscreen = !!document.fullscreenElement
      && !!containerRef.value
      && containerRef.value.contains(document.fullscreenElement)
    stop()
    if (wasPlaying && streamId) {
      await play(streamId, urls, { proto })
      if (wasFullscreen) {
        await restoreFullscreen()
      }
    }
  }

  /**
   * 重建后恢复全屏。
   *
   * <p>切换协议会重建播放器实例，旧的全屏元素被移除后浏览器会自动退出全屏。
   * 恢复时<b>先用 CSS 全屏</b>而不是 {@code requestFullscreen}：后者要求用户激活态，
   * 而切换链路里夹着「查播放地址 + WebRTC 协商（最长 8 秒）」，
   * 实测早已超出激活窗口、调用被浏览器静默拒绝（表现为「切完就掉出全屏」）。
   * CSS 全屏是纯样式操作，同步生效、不依赖激活态，视觉与全屏一致。
   */
  async function restoreFullscreen() {
    const root = player.value?.root || player.value?.el
    if (!root) {
      return
    }
    // 先同步加上，保证切换后立刻仍是「满屏」观感
    root.classList.add('xgproto-css-fullscreen')
    /*
     * 再尝试真正的浏览器全屏：成功就撤掉 CSS 类（避免两套全屏叠加），
     * 失败就算了 —— CSS 全屏已经在生效。
     */
    try {
      await root.requestFullscreen()
      root.classList.remove('xgproto-css-fullscreen')
    } catch (e) {
      // 激活态已过期：保留 CSS 全屏
    }
  }

  /** 退出 CSS 全屏兜底（正常全屏不受影响）。 */
  function exitCssFullscreen() {
    const root = player.value?.root || player.value?.el
    if (root) {
      root.classList.remove('xgproto-css-fullscreen')
    }
  }

  /** 停止并释放资源。 */
  function stop() {
    disposed = true
    // 先标记「正在销毁」：销毁会触发 media 的 pause/ratechange，
    // 不标记就会被当成用户操作发 SIP 指令（见 applyVodControls 的说明）
    const prev = player.value
    if (prev) {
      prev.__closing = true
    }
    if (prev && typeof prev.__protoCleanup === 'function') {
      prev.__protoCleanup()
      prev.__protoCleanup = null
    }
    // 清掉回放参数：否则下一次播直播（不传 vod）会残留上一段的进度条配置
    currentVod = null
    if (stableTimer) {
      clearTimeout(stableTimer)
      stableTimer = null
    }
    for (const t of waitTimers) clearTimeout(t)
    waitTimers.length = 0
    if (stream) {
      stream.destroy()
      stream = null
    }
    if (player.value) {
      try {
        /*
         * WebRTC 通路的 MediaStream 必须显式断开：video 元素只要还持有
         * srcObject，就会一直挂着那路 track（摄像头/编码器不释放），
         * 切换协议或重新点播时会有多路残留。
         * xgplayer 的 destroy() 只清 src 属性，不管 srcObject。
         */
        const media = player.value.media
        if (media && media.srcObject) {
          media.srcObject = null
        }
        player.value.destroy()
      } catch (e) {
        // 实例可能已释放
      }
      player.value = null
    }
    if (pc) {
      try {
        pc.close()
      } catch (e) {
        // 忽略
      }
      pc = null
    }
    if (objectUrl) {
      URL.revokeObjectURL(objectUrl)
      objectUrl = null
    }
    if (host) {
      host.remove()
      host = null
    }
    const outer = containerRef.value
    if (outer) outer.innerHTML = ''

    playing.value = false
    stats.value = null
    audioInfo.value = null
    videoInfo.value = null
    position.value = 0
    if (state.value === '播放中') state.value = '已停止'
  }

  /** 新建宿主 div（每次播放独立，避免与旧实例的 DOM 回收竞争）。 */
  function createHost() {
    const outer = containerRef.value
    if (!outer) {
      state.value = '播放器容器未就绪'
      return null
    }
    outer.innerHTML = ''
    const el = document.createElement('div')
    el.style.cssText = 'position:relative;width:100%;height:100%;background:#000'
    outer.appendChild(el)
    host = el
    return el
  }

  return {
    playing,
    state,
    protocol,
    currentStream,
    stats,
    audioInfo,
    videoInfo,
    position,
    play,
    stop,
    switchProtocol,
    /** 退出「CSS 全屏」兜底（业务侧关闭弹窗时调用）。 */
    exitCssFullscreen
  }
}

/**
 * xgplayer 通用外观配置：去掉与业务无关的入口，保持界面干净。
 *
 * <p><b>直播</b>刻意<b>移除</b> {@code play}（播放/暂停按钮）与
 * {@code playbackRate}（倍速）：直播是持续流，暂停会让画面卡住却没有意义，
 * 倍速对直播同样无意义（LiveGBS 等主流平台也都不提供）。
 *
 * <p><b>回放</b>反过来要保留这两项 —— 它们会被 {@link applyVodControls}
 * 接成设备信令（PAUSE / PLAY / Scale），进度条同理。
 *
 * <h3>为什么必须关掉 {@code closeVideoClick} / {@code closeVideoDblclick}</h3>
 * <p>xgplayer 默认在 video 元素上接管单击与双击（单击暂停、双击全屏），
 * 而且它的处理函数里会调 {@code event.stopPropagation()}。这带来两个问题：
 * <ul>
 *   <li><b>外层容器的 click 收不到</b> —— 视频墙靠
 *       {@code .video-box @click} 切换「当前操作格」（active），
 *       事件被 xgplayer 吞掉后点画面就没反应了；</li>
 *   <li><b>点画面会暂停直播</b> —— 虽然有 pause 兜底拦截，
 *       但那一瞬间的暂停/恢复会让画面顿一下。</li>
 * </ul>
 * <p>关掉这两个开关后，xgplayer 直接短路返回，既不 preventDefault 也不
 * stopPropagation，事件正常冒泡到外层容器。双击全屏由业务层自己实现
 * （视频墙用 320ms 内的两次 click 判定），比播放器内置的更可控。
 *
 * @param {'live'|'vod'} mode 直播 / 录像回放
 */
function baseUiConfig(mode = 'live') {
  const ignores = ['download', 'testSpeed', 'definition']
  if (mode === 'live') {
    // 直播没有「暂停」「倍速」语义，连按钮一起去掉
    ignores.push('play', 'playbackRate')
    /*
     * 中央那个大播放按钮是 start 插件，与控制栏的 play 是两个组件。
     *
     * <p>必须一起去掉：直播不允许暂停（{@code closeVideoClick: true}
     * 已让点画面不暂停），所以这个按钮在本页<b>没有任何作用</b>。
     * 更重要的是 —— 直播的 {@code autoplay} 是 false（等 ws-flv 攒预缓冲），
     * 播放器初始处于「暂停」态，start 插件就会把大按钮画在正中央；
     * 预缓冲结束、业务层调 play() 之前的那一两秒里，用户看到的是
     * 「视频已经在播，中间却盖着一个播放图标」，纯属误导。
     */
    ignores.push('start')
  }
  return {
    ignores,
    // 点画面不暂停、也不拦截事件冒泡（详见上面的说明）
    closeVideoClick: true,
    // 双击全屏交给业务层判定，避免与播放器内置行为重复触发
    closeVideoDblclick: true
  }
}

/**
 * 给录像回放装上「伪点播」控件：进度条 / 暂停 / 倍速 全部走设备信令。
 *
 * <h3>为什么叫「伪点播」</h3>
 * 设备回放拿到的其实是<b>一路实时流</b>（ZLM 从设备收 PS 再转 FLV），
 * 浏览器眼里它没有时长、不能 seek。但用户要的操作体验是点播：
 * 有进度条、能暂停、能倍速、能拖动。
 *
 * <p>做法是把 UI 表现与数据通路拆开：
 * <ul>
 *   <li><b>进度条</b>：用 {@code player.config.customDuration} 告诉 xgplayer
 *       「这段有多长」，再用 {@code player.config.timeOffset} 把「媒体自身
 *       时间轴」平移成「录像段内的位置」。媒体时间轴从 0 开始自然推进，
 *       平移后显示的就是录像段内的真实位置。</li>
 *   <li><b>暂停 / 继续 / 倍速</b>：不靠本地缓冲，而是发 SIP {@code INFO}
 *       让<b>设备</b>停发/续发/加速（{@code PAUSE} / {@code PLAY npt=now-} /
 *       {@code Scale}）。本地缓冲做这些会因为「推流速度不变」而越积越多或卡死。</li>
 *   <li><b>拖动</b>：覆写 {@code player.seek}，换成发 SIP {@code PLAY npt=<秒>}，
 *       并重算 {@code timeOffset} 让进度条立刻落到目标位置。
 *       <b>绝不能</b>真去改 {@code media.currentTime} —— MSE 的 buffered
 *       范围之外根本 seek 不过去，会直接卡死在等待数据。</li>
 * </ul>
 *
 * <p>注意 {@code customDuration} / {@code timeOffset} 是 <b>player 顶层配置</b>
 * （xgplayer 的 progress 插件读的是 {@code this.playerConfig.xxx}，
 * 而 {@code playerConfig === player.config}），不是 {@code progress} 插件内部字段。
 */
function applyVodControls(p, media, vod, positionRef) {
  const duration = Math.max(1, Math.round(vod.duration || 0))
  /*
   * 进度条总长取整段时长；起始偏移取 vod.startOffset。
   *
   * <p>{@code startOffset} 用于「从段中间开始播」：拖动定位与暂停后继续都是
   * 停掉旧会话、带着新的 {@code t=} 起点重新 INVITE，此时媒体时间轴从 0 开始，
   * 但它在录像段里的真实位置是 startOffset。
   * 显示位置 = timeOffset + media.currentTime，总长仍是整段，
   * 所以进度条会从中间那个点开始走，而不是跳回开头。
   */
  const startOffset = Math.min(duration, Math.max(0, Math.round(Number(vod.startOffset) || 0)))
  p.config.customDuration = duration
  p.config.timeOffset = startOffset

  const clamp = (t) => {
    const n = Number(t)
    if (!Number.isFinite(n)) return 0
    return Math.min(Math.max(0, n), duration)
  }

  /*
   * 覆写 seek：进度条拖动最终都会走到 player.seek()（progress 插件在
   * updateWidth 里直接调它），在这里拦下来转成设备指令。
   */
  p.seek = (time) => {
    const target = clamp(time)
    // 立刻把显示位置挪到目标：timeOffset = 目标 - 媒体当前时间
    p.config.timeOffset = target - (media.currentTime || 0)
    vod.onSeek?.(target)
  }

  /*
   * 播放/暂停只在「用户已经开始看」之后才上报。
   * 起播时 autoplay 也会触发一次 play 事件，那时发 PLAY 给设备是多余的，
   * 还可能把设备刚建立的推流状态打乱。
   */
  let userReady = false
  p.on('playing', () => {
    userReady = true
  })

  /** 用户操作的时间窗（毫秒）：覆盖事件从 player API 冒泡到 media 的延迟。 */
  const USER_ACTION_WINDOW_MS = 600

  /*
   * 【关键一】用户暂停 / 继续 与「播放器内部事件」必须分开。
   *
   * <p>media 的 play/pause 事件<b>不代表用户操作</b>：MSE 欠载恢复、缓冲边界、
   * 播放器内部状态机都会触发它们。原实现只看 {@code userReady} 就往外发
   * SIP 指令，后果是灾难性的：一次内部 pause 就发 SIP PAUSE →
   * 设备真的停推 → ZLM 收流超时注销媒体源（实测约 20 秒）→ WebSocket 被关 →
   * 画面定格。用户看到的就是「播着播着就不动了，必须关掉重播」。
   *
   * <p><b>只拦 {@code playPause} 而不是 {@code play}/{@code pause}</b>：
   * 后两者是播放器内部也会调用的公共方法（自动起播、欠载恢复都走 {@code play()}），
   * 拿它当「用户动作」等于没拦。{@code playPause} 才是真正的用户入口 ——
   * xgplayer 的控制栏按钮与键盘空格键都只经过它。
   */
  let userActionAt = 0
  const playPauser = p.playPause
  if (typeof playPauser === 'function') {
    p.playPause = (...args) => {
      userActionAt = Date.now()
      return playPauser.apply(p, args)
    }
  }

  p.on('pause', () => {
    /*
     * 播放器被主动销毁时会触发一次 pause，那不是用户操作。
     * 不拦住就会向设备发 SIP PAUSE —— 设备真的停推，ZLM 收流超时后
     * 注销媒体源（实测 16~20 秒），紧接着切 WebRTC 就报 stream not found。
     * 切协议、关弹窗都会走这条销毁路径。
     */
    if (p.__closing) return
    if (!userReady) return
    if (Date.now() - userActionAt > USER_ACTION_WINDOW_MS) {
      /*
       * 【关键二】非用户暂停不要直接放弃，而要「续播」。
       *
       * <p>这类暂停几乎都是缓冲欠载造成的。此时若什么都不做，画面就永远
       * 停在那儿 —— 用户既没按暂停、也没有任何提示，只能关掉重开。
       * 自动拉回播放，等下一个数据包到了自然续上。
       */
      if (!media.ended) media.play().catch(() => {})
      return
    }
    vod.onPause?.()
  })
  p.on('play', () => {
    if (p.__closing) return
    if (!userReady) return
    // 内部起播/欠载恢复不是用户意图，不能反向发 SIP PLAY
    if (Date.now() - userActionAt > USER_ACTION_WINDOW_MS) return
    vod.onResume?.()
  })

  /*
   * 倍速：xgplayer 的 playbackRate 插件会改 media.playbackRate，
   * 这里监听 media 的 ratechange 转成设备侧 Scale。
   *
   * 为什么要同时改 media 和发指令：设备按 Scale 加速推流后，
   * 本地必须同倍速消耗，否则缓冲区会一直涨（延迟越拖越大）。
   */
  let lastRate = 1
  media.addEventListener('ratechange', () => {
    const rate = media.playbackRate
    if (p.__closing) return
    if (!Number.isFinite(rate) || rate === lastRate) return
    lastRate = rate
    vod.onRate?.(rate)
  })

  /*
   * 把当前位置报给业务层（显示墙钟时间用）。
   * 用 timeupdate 而不是定时器：它跟着媒体时钟走，暂停时自动停。
   */
  const reportTime = () => {
    const pos = (p.config.timeOffset || 0) + (media.currentTime || 0)
    positionRef.value = Math.min(duration, Math.max(0, pos))
  }
  p.on('timeupdate', reportTime)
  media.addEventListener('timeupdate', reportTime)
}

/**
 * 把「播放协议」选择器挂进播放器控制栏。
 *
 * <h3>为什么放在播放器里</h3>
 * 协议（WebSocket-FLV / WebRTC）是<b>观看方式</b>，不是页面级配置。
 * 早先它是个悬浮在播放器外面的下拉框：全屏时看不见、录像回放弹窗里
 * 还得先关掉才能改，和视频网站的「清晰度」完全不是一回事。
 * 现在挂到控制栏右侧，全屏下也可见可点。
 *
 * <h3>为什么自己建 DOM 而不是用 xgplayer 的 definition 插件</h3>
 * {@code definition} 插件是「同一路流的多档清晰度」，切换时只换 {@code src}、
 * 播放器实例不重建。而这里 FLV 与 WebRTC 是<b>两种完全不同的内核</b>
 * （MSE 灌流 vs {@code RTCPeerConnection}），必须重建播放器 ——
 * 语义对不上，强行套用会得到「切了但还在播老内核」的结果。
 *
 * @param {object} p     xgplayer 实例
 * @param {object} [cfg] `{ value, options: [{ value, label }], onChange(value) }`
 */
function attachProtocolSwitch(p, cfg) {
  if (!p || !cfg || !Array.isArray(cfg.options) || cfg.options.length < 2) {
    return
  }
  const host = p.root || p.el
  if (!host) {
    return
  }
  // 控制栏在 xgplayer 内部创建，等一帧再挂，避免拿到未渲染的容器
  const mount = () => {
    const controls = host.querySelector('.xgplayer-controls')
    if (!controls) {
      return false
    }
    /*
     * 菜单的定位祖先必须是<b>播放器真实根节点</b>（{@code .xgplayer}），
     * 不是外面那个宿主 div。
     *
     * <p>踩过的坑：用宿主容器当定位祖先时，它比播放器窄很多（实测仅约 120px），
     * 菜单右对齐到它就被推到播放器外面，界面上只剩左侧一截黑块。
     * 从控制栏向上找 {@code .xgplayer} 才能拿到与画面同尺寸的盒子。
     */
    const playerRoot = controls.closest('.xgplayer') || host
    if (getComputedStyle(playerRoot).position === 'static') {
      playerRoot.style.position = 'relative'
    }
    const box = document.createElement('div')
    box.className = 'xgproto'
    /*
     * 阻断事件冒泡到播放器根节点：xgplayer 在根容器上也有点击处理，
     * 点我们的菜单会连带触发播放/暂停，用户只是想换个协议却把回放暂停了。
     */
    for (const evt of ['pointerdown', 'mousedown', 'click', 'dblclick']) {
      box.addEventListener(evt, (e) => e.stopPropagation())
    }
    const btn = document.createElement('button')
    btn.type = 'button'
    btn.className = 'xgproto-btn'
    btn.title = '播放协议'
    const label = document.createElement('span')
    label.className = 'xgproto-label'
    btn.appendChild(label)
    box.appendChild(btn)

    /*
     * 菜单挂在<b>播放器根节点</b>而不是控制栏里。
     *
     * <p>踩过的坑：起初把菜单放在 {@code box} 内部（即控制栏里），
     * 而控制栏高度只有 40px 左右、且上层容器有裁剪，弹出的菜单被切掉一半 ——
     * 界面上只能看到「RTC」「FLV」的后半截，选项根本点不到。
     * 挂到根节点后用绝对定位，不受控制栏尺寸约束。
     */
    const menu = document.createElement('div')
    menu.className = 'xgproto-menu'
    for (const evt of ['pointerdown', 'mousedown', 'click', 'dblclick']) {
      menu.addEventListener(evt, (e) => e.stopPropagation())
    }
    playerRoot.appendChild(menu)

    const openMenu = () => {
      /*
       * 用按钮与控制栏的实际位置定位菜单，而不是写死 right/bottom：
       * 全屏、不同尺寸下控制栏的图标排布会变，写死会错位。
       */
      const rootRect = playerRoot.getBoundingClientRect()
      const btnRect = btn.getBoundingClientRect()
      // 菜单右边缘对齐按钮右边缘（不能超出播放器右边界）
      const right = Math.max(6, Math.round(rootRect.right - btnRect.right))
      menu.style.right = Math.min(right, Math.max(6, rootRect.width - 108)) + 'px'
      // 菜单底边贴在按钮上沿上方一点
      menu.style.bottom = Math.max(6, Math.round(rootRect.bottom - btnRect.top + 6)) + 'px'
      menu.classList.add('is-open')
    }
    const closeMenu = () => menu.classList.remove('is-open')

    const setValue = (v) => {
      const hit = cfg.options.find((o) => o.value === v) || cfg.options[0]
      // 控制栏按钮用短名（{@code short}），菜单项用完整名（{@code label}）：
      // 菜单里需要写清限制（如「HLS（无声音）」），按钮上放不下
      label.textContent = hit.short || hit.label
      for (const node of menu.children) {
        node.classList.toggle('is-active', node.dataset.value === hit.value)
      }
      p.__protoValue = hit.value
    }

    for (const opt of cfg.options) {
      const item = document.createElement('div')
      item.className = 'xgproto-item'
      item.dataset.value = opt.value
      item.textContent = opt.label
      item.addEventListener('click', (e) => {
        e.stopPropagation()
        closeMenu()
        if (opt.value === p.__protoValue) {
          return
        }
        setValue(opt.value)
        cfg.onChange?.(opt.value)
      })
      menu.appendChild(item)
    }

    btn.addEventListener('click', (e) => {
      e.stopPropagation()
      if (menu.classList.contains('is-open')) {
        closeMenu()
      } else {
        openMenu()
      }
    })
    // 点外部关闭菜单（capture 阶段，避免被播放器自身的点击处理吃掉）
    const onDocDown = (e) => {
      if (!box.contains(e.target) && !menu.contains(e.target)) {
        closeMenu()
      }
    }
    document.addEventListener('pointerdown', onDocDown, true)
    p.__protoCleanup = () => {
      document.removeEventListener('pointerdown', onDocDown, true)
      menu.remove()
    }

    setValue(cfg.value)
    /*
     * 插进右侧图标组的<b>第一个位置</b>（音量左侧）。
     *
     * <p>踩过的坑：之前 append 到 {@code .xg-inner-controls} 的末尾 ——
     * 那是 right-grid 的兄弟节点，而 right-grid 自身宽度由内容决定且不靠右，
     * 结果「FLV」孤零零跑到最右边，把音量/画中画/全屏挤成了中间一坨（截图可见）。
     * 放进 right-grid 后与原生图标同一行、同一边距，布局完全跟随播放器。
     */
    const grouper = controls.querySelector('.xg-right-grid')
    if (!grouper) {
      /*
       * 图标组还没渲染出来时先不挂：插到 controls 上会成为 right-grid 的
       * 兄弟节点，被挤出图标行（实测落在最右边、把其它图标挤成中间一坨）。
       * 返回 false 交给下面的重试机制，等图标组出现再挂。
       */
      return false
    }
    /*
     * 放在<b>音量图标左边</b>（视觉上）。
     *
     * <p><b>关键：{@code .xg-right-grid} 是 {@code flex-direction: row-reverse}</b>，
     * DOM 顺序与视觉顺序是<b>相反</b>的 ——
     * 实测 DOM 为 {@code [全屏, 画中画, 音量]}，视觉却是 {@code 音量 画中画 全屏}。
     * 因此「视觉上更靠左」= 「DOM 里更靠后」。
     * 早先用 {@code insertBefore(box, volume)} 把按钮插到音量<b>前面</b>，
     * 在 row-reverse 下反而显示在音量<b>右边</b>（正是用户看到的现象）。
     * 插到音量之后（DOM 末尾）才会显示在音量左侧。
     */
    const volume = grouper.querySelector('.xgplayer-volume')
    if (volume) {
      volume.insertAdjacentElement('afterend', box)
    } else {
      grouper.appendChild(box)
    }
    return true
  }
  if (!mount()) {
    /*
     * 控制栏由 xgplayer 异步渲染，首次建播放器时可能还没挂上。
     * 轮询重试（最多约 1 秒）：早先只重试一帧，实测 WebRTC 通路
     * 重建后就错过了，菜单直接不见了。
     */
    let tries = 0
    const retry = () => {
      if (mount() || ++tries > 20) {
        return
      }
      setTimeout(retry, 50)
    }
    setTimeout(retry, 50)
  }
}

/** 等待 ICE 收集完成（最多 2 秒，超时用当前候选继续）。 */
function waitIceGathering(pc) {
  return new Promise((resolve) => {
    if (pc.iceGatheringState === 'complete') {
      resolve()
      return
    }
    const timer = setTimeout(resolve, 2000)
    pc.addEventListener('icegatheringstatechange', () => {
      if (pc.iceGatheringState === 'complete') {
        clearTimeout(timer)
        resolve()
      }
    })
  })
}
