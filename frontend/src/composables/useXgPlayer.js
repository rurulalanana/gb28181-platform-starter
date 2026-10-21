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
   * 播放。
   *
   * @param {string} streamId 流标识 `<设备编码>/<通道编码>`
   * @param {object} urls     播放地址集合 `{ wsFlv, httpFlv, webrtc }`
   * @param {object} [opts]   `{ proto: 'wsflv'|'webrtc' }`
   * @param {function} [opts.onFatal] 播放彻底失败时的回调（解码错误重试耗尽后触发）
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

    if (proto === 'webrtc') {
      await playWebrtc(streamId)
    } else {
      await playFlv(urls?.wsFlv || urls?.httpFlv, opts.onFatal)
    }
  }

  /** 直播通路：自研 ws-flv。 */
  async function playFlv(url, onFatal) {
    if (!url) {
      state.value = '缺少播放地址'
      return
    }
    if (!containerRef.value) {
      state.value = '播放器容器未就绪'
      return
    }

    state.value = '连接中…'
    const uiConfig = baseUiConfig()

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
      isLive: true,
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
    media.addEventListener('pause', () => {
      if (disposed) return
      // 停止播放时（stop）会主动 pause，那时要放过
      if (!stream) return
      media.play().catch(() => {})
    })

    // 直播不允许变速：倍速按钮已移除，这里再兜底一次，
    // 防止外部代码或残留的 rate 状态影响直播速度。
    const resetRate = () => {
      if (media.playbackRate !== 1) media.playbackRate = 1
    }
    media.addEventListener('ratechange', resetRate)
    resetRate()

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
        play(currentStream.value, { wsFlv: url }, { proto: 'wsflv', onFatal, _rebuild: true })
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

  /** WebRTC 通路（低延迟备用）。 */
  async function playWebrtc(streamId) {
    const el = createHost()
    if (!el) return
    state.value = '协商中…'
    try {
      const video = document.createElement('video')
      video.autoplay = true
      video.playsInline = true
      video.muted = true
      video.controls = true
      video.style.cssText =
        'position:absolute;inset:0;width:100%;height:100%;object-fit:contain;background:#000'
      el.appendChild(video)

      // 直播不应被暂停：原生控制条的暂停键点了立即恢复（保留控制条以便调音量）
      video.addEventListener('pause', () => {
        if (disposed || document.visibilityState !== 'visible') return
        video.play().catch(() => {})
      })

      // 直播不应变速：强制固定 1 倍速
      const resetRate = () => {
        if (video.playbackRate !== 1) video.playbackRate = 1
      }
      video.addEventListener('ratechange', resetRate)
      resetRate()

      const conn = new RTCPeerConnection({ iceServers: [] })
      pc = conn
      conn.ontrack = (event) => {
        if (disposed) return
        video.srcObject = event.streams[0]
        playing.value = true
        state.value = '播放中'
      }
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
    for (const t of waitTimers) clearTimeout(t)
    waitTimers.length = 0
    if (stream) {
      stream.destroy()
      stream = null
    }
    if (player.value) {
      try {
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
    play,
    stop,
    switchProtocol
  }
}

/**
 * xgplayer 通用外观配置：去掉与业务无关的入口，保持界面干净。
 *
 * <p>直播场景刻意<b>移除</b> {@code play}（播放/暂停按钮）与
 * {@code playbackRate}（倍速）：直播是持续流，暂停会让画面卡住却没有意义，
 * 倍速对直播同样无意义（LiveGBS 等主流平台也都不提供）。
 *
 * <p>注意：移除按钮只是去掉 UI 入口，键盘空格（keyboard 插件的
 * {@code playPause} action）与浏览器媒体策略仍能触发暂停，
 * 所以还要在 media 上做兜底拦截（见 {@code playFlv}）。
 */
function baseUiConfig() {
  return {
    ignores: ['download', 'testSpeed', 'definition', 'play', 'playbackRate']
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
