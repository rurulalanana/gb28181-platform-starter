/**
 * 统一播放器（xgplayer）：直播走自研 ws-flv 引擎，回放直接播 MP4 文件。
 *
 * <h3>为什么分两条数据通路</h3>
 * <ul>
 *   <li><b>直播 / 实时预览</b>：国标流是 PS + G.711，ZLM 转 FLV 后音频仍是
 *       PCMA/PCMU，MSE 不认。因此用 {@link WsFlvStream} 把视频交给 MSE、
 *       音频交给 Web Audio（见该文件注释）。</li>
 *   <li><b>录像回放</b>：后端提供的是 MP4 文件，直接交给 xgplayer 原生播放。
 *       浏览器靠 HTTP Range 按需拉流，秒开、可任意拖动，进度条是 xgplayer 自带的。</li>
 * </ul>
 * 两条通路共用同一套 UI 与交互，对外只有一个 {@code play/stop}。
 *
 * <h3>容器约定</h3>
 * xgplayer 会在传入的容器里自建 {@code <video>} 与控件；本 composable 每次播放
 * 都新建一个宿主 div，销毁时整块移除，避免与上一轮实例的 DOM 回收打架。
 */

import { ref, shallowRef } from 'vue'
import Player from 'xgplayer'
import 'xgplayer/dist/index.min.css'
import WsFlvStream from '@/player/WsFlvStream'
import { webrtcApi } from '@/api'

export function useXgPlayer(containerRef) {
  const playing = ref(false)
  const state = ref('空闲')
  const protocol = ref('wsflv')
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
  /** 销毁标记：异步流程返回后若已停止，丢弃结果。 */
  let disposed = false
  /**
   * 当前回放参数（录像段的 duration 与信令回调）。
   *
   * <p>存在闭包里是为了 <b>跨解码重试重建</b>保留：重建会重新走 playFlv，
   * 若每次都要调用方重新传，重建后的进度条就没了。
   */
  let currentVod = null

  /**
   * 播放。
   *
   * @param {string} streamId 流标识 `<设备编码>/<通道编码>`
   * @param {object} urls     播放地址集合 `{ wsFlv, httpFlv, webrtc }`
   * @param {object} [opts]   `{ proto: 'wsflv'|'webrtc', vod: {...} }`
   * @param {function} [opts.onFatal] 播放彻底失败时的回调（解码错误重试耗尽后触发）
   * @param {object} [opts.vod] 录像回放参数，给了就切到「伪点播」模式：
   *   `{ duration, onSeek, onPause, onResume, onRate }`。
   *   见 {@link applyVodControls} 的说明。
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

    if (proto === 'webrtc') {
      await playWebrtc(streamId)
    } else {
      await playFlv(urls?.wsFlv || urls?.httpFlv, opts.onFatal, currentVod)
    }
  }

  /**
   * 直播通路：自研 ws-flv。
   *
   * @param {object|null} vod 录像回放参数；非空时进入「伪点播」模式
   */
  async function playFlv(url, onFatal, vod) {
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
      autoplay: true,
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

    stream = new WsFlvStream({
      url,
      mediaSource,
      media,
      onError: (msg) => {
        if (disposed) return
        state.value = '播放异常：' + msg
        playing.value = false
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

    // video 的音量/静音按钮由 xgplayer 操作，Web Audio 这条链路要跟着变
    const syncVolume = () => stream?.setVolume(media.muted ? 0 : media.volume)
    media.addEventListener('volumechange', syncVolume)
    syncVolume()

    p.on('playing', () => {
      if (disposed) return
      playing.value = true
      state.value = '播放中'
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
      const resetRate = () => {
        if (media.playbackRate !== 1) media.playbackRate = 1
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
    const MAX_DECODE_RETRY = 3
    media.addEventListener('error', () => {
      if (disposed) return
      // code 3 = MEDIA_ERR_DECODE；其它错误（网络中断等）交给流层处理
      if (media.error?.code !== 3) return
      if (decodeRetryCount >= MAX_DECODE_RETRY) {
        state.value = `播放异常：解码失败（已重试 ${MAX_DECODE_RETRY} 次）`
        playing.value = false
        if (onFatal) onFatal()
        return
      }
      decodeRetryCount++
      state.value = `解码失败，重试中（${decodeRetryCount}/${MAX_DECODE_RETRY}）…`
      playing.value = false
      // 退避一下再重建：立刻重建大概率拿到同一批坏数据
      const timer = setTimeout(() => {
        if (disposed) return
        stop()
        // vod 必须显式回传：stop() 会清空 currentVod，不传就变成直播模式了
        play(currentStream.value, { wsFlv: url },
          { proto: 'wsflv', onFatal, _rebuild: true, vod })
      }, 1200 * decodeRetryCount)
      waitTimers.push(timer)
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
   */
  async function playWebrtc(streamId) {
    const el = createHost()
    if (!el) return
    state.value = '协商中…'

    try {
      const conn = new RTCPeerConnection({ iceServers: [] })
      pc = conn

      // ontrack 在 setRemoteDescription 之后由浏览器回调；用 Promise 等它，
      // 这样下面创建播放器时流一定已经到手。
      const streamPromise = new Promise((resolve) => {
        conn.ontrack = (event) => {
          resolve(event.streams[0] || new MediaStream([event.track]))
        }
      })

      conn.onconnectionstatechange = () => {
        if (disposed || !pc) return
        if (conn.connectionState === 'failed' || conn.connectionState === 'disconnected') {
          state.value = '连接中断'
          playing.value = false
        }
      }

      const offer = await conn.createOffer({
        offerToReceiveVideo: true,
        offerToReceiveAudio: true
      })
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
    } catch (e) {
      if (disposed) return
      state.value = '失败：' + (e.message || e)
      playing.value = false
      stop()
    }
  }

  /** 切换通路（播放中自动重连）。 */
  async function switchProtocol(proto, urls) {
    if (proto === protocol.value && playing.value) return
    /*
     * 目标通路没有可用地址时<b>不要动现有播放器</b>。
     *
     * <p>原实现先 stop() 再 play()，一旦 urls 为空（比如流已被空闲巡检关闭、
     * 查询接口返回 400），播放器就被拆掉了却接不上新的源 ——
     * 用户看到的是「切个协议画面就没了，也不报错」。
     */
    const target = proto === 'webrtc' ? urls?.webrtc : (urls?.wsFlv || urls?.httpFlv)
    if (!target) {
      state.value = '缺少播放地址'
      return
    }
    protocol.value = proto
    const streamId = currentStream.value
    const wasPlaying = playing.value
    stop()
    if (wasPlaying && streamId) {
      await play(streamId, urls, { proto })
    }
  }

  /** 停止并释放资源。 */
  function stop() {
    disposed = true
    // 清掉回放参数：否则下一次播直播（不传 vod）会残留上一段的进度条配置
    currentVod = null
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
    switchProtocol
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
  // 进度条总长；同时把偏移归零，让首帧显示在 0
  p.config.customDuration = duration
  p.config.timeOffset = 0

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
  p.on('pause', () => {
    if (userReady) vod.onPause?.()
  })
  p.on('play', () => {
    if (userReady) vod.onResume?.()
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
