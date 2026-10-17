import { ref, shallowRef } from 'vue'
import { webrtcApi } from '@/api'

/**
 * 统一视频播放器（jessibuca）。
 *
 * ## 为什么不用 mpegts.js / flv.js
 *
 * 国标设备绝大多数用 **PS 封装 + G.711 音频**推流，ZLM 转成 FLV 后
 * 音频编码是 **PCMA（G.711 A-law）或 PCMU（G.711 μ-law）**。
 *
 * mpegts.js / flv.js 走浏览器 MSE（MediaSource），而 MSE 只认 AAC/MP3，
 * 遇到 PCMA 会直接抛：
 *
 * ```
 * DemuxException: type = CodecUnsupported, info = Flv: Unsupported audio codec idx: 7
 * ```
 *
 * （idx 7 = A-law，idx 8 = μ-law，见 FLV 规范 AudioTagHeader 的 SoundFormat 字段）
 *
 * jessibuca 用 **WASM 软解码**（内置 ffmpeg），原生支持 PCMA/PCMU，
 * 因此画面与声音都能正常出来，无需在服务端做音频转码。
 *
 * ## 渲染方式
 *
 * jessibuca 渲染到**自己创建的 canvas**（挂在 container 内），
 * 不是 `<video>` 元素 —— 所以调用方只需提供一个容器 div，
 * 由本模块负责创建/销毁播放器实例。
 *
 * ## 两个必须遵守的约束（踩过的坑）
 *
 * 1. **不要给传给 jessibuca 的 container 施加尺寸/缩放类样式**。
 *    官方文档明确警告过：播放器会按容器尺寸计算 canvas 的 inline
 *    `transform: scale(...)`，一旦外层 CSS 用 `width/height: 100% !important`
 *    之类改写 canvas 布局盒，缩放基准就错位，画面会缩成小块并偏移到容器外
 *    （表现为「画面跑在左上角」）。本模块因此每次播放都新建一个干净的
 *    `.jessibuca-host` 宿主 div，业务样式只作用于更外层容器。
 *
 * 2. **`controlAutoHide: true` 必须开**。默认情况下 jessibuca 会为底部控制条
 *    预留 38px（canvas 高度 = 容器高度 − 控制条高度），画面永远填不满容器。
 *    开启后控制条改为浮层，不占布局高度，canvas 才能 100% 铺满。
 *
 * ## 播放通路
 *
 * - **ws-flv / http-flv**：jessibuca 直接支持（`isFlv: true`），延迟 1~2 秒；
 * - **webrtc**：交给浏览器原生 RTCPeerConnection，延迟 < 500ms。
 *
 * @param containerRef 容器 div 的 ref（jessibuca 会在其中创建 canvas）
 */
/** jessibuca SDK 加载 Promise（模块级：多个播放器/预热共享同一次 script 加载）。 */
let sdkPromise = null

/**
 * 加载 jessibuca SDK（模块级，只发一次 script 请求）。
 *
 * 静态文件放在 `public/jessibuca/`，构建后是 `/jessibuca/jessibuca.js`。
 */
export function loadJessibucaSdk() {
  if (window.Jessibuca) {
    return Promise.resolve(window.Jessibuca)
  }
  if (sdkPromise) {
    return sdkPromise
  }
  sdkPromise = new Promise((resolve, reject) => {
    const script = document.createElement('script')
    script.src = '/jessibuca/jessibuca.js'
    script.async = true
    script.onload = () => {
      if (window.Jessibuca) {
        resolve(window.Jessibuca)
      } else {
        reject(new Error('jessibuca 加载后未暴露全局变量'))
      }
    }
    script.onerror = () => {
      sdkPromise = null
      reject(new Error('jessibuca.js 加载失败，请确认 /jessibuca/ 静态资源可访问'))
    }
    document.head.appendChild(script)
  })
  return sdkPromise
}

/**
 * 预热播放器（进入实时预览页时调用）。
 *
 * 点播时才按需加载 SDK 会让首次点播多等 2~5 秒（公网下载 jessibuca.js
 * 220KB + decoder.wasm 1MB）。提前把脚本拉进内存、wasm 拉进 HTTP 缓存，
 * 点播时几乎零等待。失败静默（点播时仍会重试）。
 */
export function preloadJessibuca() {
  loadJessibucaSdk().catch(() => {})
  try {
    fetch('/jessibuca/decoder.wasm', { cache: 'force-cache' }).catch(() => {})
  } catch (e) {
    /* 忽略：预热失败不影响点播 */
  }
}

export function useVideoPlayer(containerRef) {
  const playing = ref(false)
  const state = ref('空闲')
  const protocol = ref('wsflv')
  const currentStream = ref('')
  const stats = ref(null)
  /** 音频编码信息（jessibuca 的 audioInfo 事件），便于确认 PCMA 是否被正确解码。 */
  const audioInfo = ref(null)
  /** 视频编码信息。 */
  const videoInfo = ref(null)

  /** jessibuca 实例。 */
  const player = shallowRef(null)
  /** RTCPeerConnection 实例（WebRTC 通路）。 */
  const pc = shallowRef(null)
  /** WebRTC 通路的 video 元素（jessibuca 用 canvas，两者渲染方式不同）。 */
  let webrtcVideoEl = null
  /** 销毁标记：异步协商返回后若已停止，丢弃结果。 */
  let disposed = false
  /** 重连定时器。 */
  let retryTimer = null
  /** 尺寸监听：布局切换（单屏/四分屏…）时通知播放器重新计算缩放。 */
  let resizeObserver = null
  /** 当前播放器宿主 div（每次播放新建，销毁时整块移除）。 */
  let host = null

  /**
   * 按需加载 jessibuca SDK。
   *
   * 实际加载逻辑在模块级 {@link loadJessibucaSdk}（多个实例与预热共享）。
   */
  function loadSdk() {
    return loadJessibucaSdk()
  }

  /**
   * 创建播放器宿主 div。
   *
   * jessibuca 官方明确建议：**不要在传给它的 container 上施加尺寸/缩放类样式**，
   * 否则播放器内部按容器尺寸计算缩放时基准错乱，画面会缩成小方块并偏移到容器外。
   *
   * 同时它会在容器内自建 canvas、控制条等节点，且 `destroy()` 是**异步**的
   * （内部按自己的节奏 removeChild）。若销毁时同步清空容器，会与它的清理逻辑
   * 竞争，抛 `removeChild NotFoundError`；而若等它异步清完再清，又会误删新播放器的画布。
   *
   * 因此这里每次播放都新建一个独立宿主 div：业务样式只作用在外层容器，
   * 播放器的 DOM 全部隔离在宿主内，销毁时整块移除，两个问题一并解决。
   */
  function createHost() {
    const outer = containerRef.value
    if (!outer) return null
    // 清掉上一轮残留（含旧宿主、WebRTC 的 video 等）
    outer.innerHTML = ''
    const el = document.createElement('div')
    el.className = 'jessibuca-host'
    el.style.cssText = 'position:relative;width:100%;height:100%;overflow:hidden'
    outer.appendChild(el)
    host = el
    return el
  }

  /** 监听外层容器尺寸变化，通知播放器重算缩放。 */
  function watchResize() {
    stopResizeWatch()
    const outer = containerRef.value
    if (!outer || typeof ResizeObserver === 'undefined') return
    resizeObserver = new ResizeObserver(() => {
      const p = player.value
      if (p && typeof p.resize === 'function') {
        try {
          p.resize()
        } catch (e) {
          // 播放器可能正在销毁，忽略
        }
      }
    })
    resizeObserver.observe(outer)
  }

  function stopResizeWatch() {
    if (resizeObserver) {
      resizeObserver.disconnect()
      resizeObserver = null
    }
  }

  /**
   * 播放。
   *
   * @param stream 流标识 `<设备编码>/<通道编码>`
   * @param urls   播放地址集合 `{ wsFlv, httpFlv, webrtc }`
   * @param opts   可选：`{ proto: 'wsflv'|'webrtc' }` 指定通路
   */
  async function play(stream, urls, opts = {}) {
    if (!stream) {
      state.value = '未指定流'
      return
    }
    stop()
    disposed = false
    currentStream.value = stream
    const proto = opts.proto || protocol.value
    protocol.value = proto

    if (proto === 'webrtc') {
      await playWebrtc(stream)
    } else {
      await playFlv(urls?.wsFlv || urls?.httpFlv)
    }
  }

  /** FLV 通路：jessibuca 直接拉 ws-flv / http-flv 并软解。 */
  async function playFlv(url) {
    if (!url) {
      state.value = '缺少播放地址'
      return
    }
    if (!containerRef.value) {
      state.value = '播放器容器未就绪'
      return
    }

    state.value = '加载播放器…'
    let Jessibuca
    try {
      Jessibuca = await loadSdk()
    } catch (e) {
      state.value = '播放器加载失败'
      return
    }
    if (disposed) return

    destroyPlayer()
    const container = createHost()
    if (!container) {
      state.value = '播放器容器未就绪'
      return
    }
    state.value = '连接中…'

    const instance = new Jessibuca({
      container,
      // wasm 与 decoder.js 必须同目录
      decoder: '/jessibuca/decoder.js',
      // 国标流走 ws 时地址不一定以 .flv 结尾，强制按 flv 解析
      isFlv: true,
      // 有音频（PCMA/PCMU），让 jessibuca 解码
      hasAudio: true,
      // 优先 WebCodecs 硬解（H264/H265）：
      // 本项目设备常见 2560×1440 H265，纯 WASM 软解（单线程 ffmpeg）只有个位数
      // 帧率，体感「播放很慢/卡死」；走 WebCodecs 后由 GPU 硬解，帧率正常。
      // 浏览器/显卡不支持对应编码时 jessibuca 会自动回退到 WASM 软解。
      useWCS: true,
      // 直播流：断流自动重连（-1 = 无限重试）
      heartTimeoutReplay: true,
      heartTimeoutReplayTimes: -1,
      loadingTimeoutReplay: true,
      loadingTimeoutReplayTimes: -1,
      // 缓冲 1 秒，兼顾流畅与延迟
      videoBuffer: 1,
      // 等比缩放，保留黑边而不拉伸画面
      isResize: true,
      // 静音起播：浏览器只允许静音视频自动播放；
      // 用户点击底部音量按钮即可恢复声音
      isNotMute: false,
      // 显示全屏/截图/播放/音量按钮，便于用户手动开声音
      operateBtns: { fullscreen: true, screenshot: true, play: true, audio: true },
      // 控制条改为浮层：jessibuca 默认会为底部控制条预留 38px 高度
      // （canvas 高度 = 容器高度 − 控制条高度），导致画面永远填不满容器。
      // 开启自动隐藏后控制条浮在画面上，不占用布局高度。
      controlAutoHide: true,
      // 支持双击全屏
      supportDblclickFullscreen: true,
      // 关闭调试打印（需要排查时改 true）
      debug: false
    })
    player.value = instance
    watchResize()

    instance.on('videoInfo', (info) => {
      videoInfo.value = info
    })
    instance.on('audioInfo', (info) => {
      // encTypeCode: 7 = A-law(PCMA)，8 = μ-law(PCMU)，10 = AAC
      audioInfo.value = info
    })
    instance.on('stats', (s) => {
      stats.value = s
    })
    instance.on('play', () => {
      playing.value = true
      state.value = '播放中'
    })
    instance.on('error', (err) => {
      if (disposed) return
      state.value = '播放异常：' + formatError(err)
      playing.value = false
      // 流可能仍在建立中，短暂后重试
      scheduleRetry(url)
    })
    instance.on('timeout', () => {
      if (disposed) return
      state.value = '连接超时，重试中…'
      playing.value = false
      scheduleRetry(url)
    })

    try {
      await instance.play(url)
      playing.value = true
      if (state.value === '连接中…') {
        state.value = '播放中'
      }
    } catch (e) {
      state.value = '播放失败：' + (e?.message || e)
      playing.value = false
      scheduleRetry(url)
    }
  }

  /** 短暂延迟后重连（流可能仍在建立中）。 */
  function scheduleRetry(url) {
    if (retryTimer) return
    retryTimer = setTimeout(() => {
      retryTimer = null
      if (disposed) return
      destroyPlayer()
      playFlv(url)
    }, 2500)
  }

  /** WebRTC 通路：浏览器 offer → 后端交换 answer。 */
  async function playWebrtc(stream) {
    try {
      state.value = '协商中…'
      if (!containerRef.value) {
        state.value = '播放器容器未就绪'
        return
      }
      destroyPlayer()

      // WebRTC 走原生 video 元素渲染（jessibuca 是 canvas 渲染）
      const hostEl = createHost()
      if (!hostEl) {
        state.value = '播放器容器未就绪'
        return
      }
      const video = document.createElement('video')
      video.autoplay = true
      video.playsInline = true
      video.muted = true
      video.controls = true
      video.style.cssText =
        'position:absolute;inset:0;width:100%;height:100%;object-fit:contain;background:#000'
      hostEl.appendChild(video)
      webrtcVideoEl = video

      const conn = new RTCPeerConnection({ iceServers: [] })
      pc.value = conn
      conn.ontrack = (event) => {
        if (disposed) return
        video.srcObject = event.streams[0]
        playing.value = true
        state.value = '播放中'
      }
      conn.onconnectionstatechange = () => {
        if (disposed || !pc.value) return
        if (conn.connectionState === 'failed' || conn.connectionState === 'disconnected') {
          state.value = '连接中断'
          playing.value = false
        }
      }
      const offer = await conn.createOffer({ offerToReceiveVideo: true, offerToReceiveAudio: true })
      await conn.setLocalDescription(offer)
      await waitIceGathering(conn)
      if (disposed) return
      const peerId = 'web-' + Math.random().toString(36).slice(2, 10)
      const data = await webrtcApi.play(stream, peerId, conn.localDescription.sdp)
      if (disposed) return
      if (!data || !data.sdp) {
        throw new Error('未获取到 SDP answer')
      }
      await conn.setRemoteDescription({ type: 'answer', sdp: data.sdp })
      state.value = '播放中'
    } catch (e) {
      if (disposed) return
      state.value = '失败：' + (e.message || e)
      playing.value = false
      stop()
    }
  }

  /** 切换播放通路（播放中则自动重连）。 */
  async function switchProtocol(proto, urls) {
    if (proto === protocol.value && playing.value) return
    protocol.value = proto
    const stream = currentStream.value
    const wasPlaying = playing.value
    stop()
    if (wasPlaying && stream) {
      await play(stream, urls, { proto })
    }
  }

  /** 停止播放并释放资源。 */
  function stop() {
    disposed = true
    if (retryTimer) {
      clearTimeout(retryTimer)
      retryTimer = null
    }
    destroyPlayer()
    if (pc.value) {
      try {
        pc.value.close()
      } catch (e) {
        // 忽略关闭异常
      }
      pc.value = null
    }
    if (webrtcVideoEl) {
      try {
        webrtcVideoEl.srcObject = null
        webrtcVideoEl.remove()
      } catch (e) {
        // 忽略移除异常
      }
      webrtcVideoEl = null
    }
    playing.value = false
    stats.value = null
    audioInfo.value = null
    videoInfo.value = null
    if (state.value === '播放中') {
      state.value = '已停止'
    }
  }

  /** 销毁 jessibuca 实例并移除宿主 div。 */
  function destroyPlayer() {
    stopResizeWatch()
    const p = player.value
    player.value = null
    if (p) {
      try {
        // destroy 返回 Promise，内部异步移除自己创建的 DOM；
        // 宿主 div 会在下面整块删除，无需等它完成。
        p.destroy()
      } catch (e) {
        // 实例可能已释放，忽略
      }
    }
    // 整块移除宿主，播放器的 canvas/控制条/worker 一并回收
    if (host) {
      host.remove()
      host = null
    }
    // 兜底：清掉可能残留的其他子节点（如上一次 WebRTC 的 video）
    const outer = containerRef.value
    if (outer) {
      outer.innerHTML = ''
    }
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

/** 把 jessibuca 的错误码转成可读文案。 */
function formatError(err) {
  if (!err) return '未知错误'
  if (typeof err === 'string') return err
  const code = err.code || err.type
  switch (code) {
    case 1:
      return '播放地址为空'
    case 2:
      return 'HTTP 请求失败'
    case 3:
      return 'WebSocket 连接失败'
    default:
      return err.message || String(code || err)
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

/**
 * 从流信息构造浏览器可用的播放地址。
 *
 * 后端返回的地址是按「设备可达地址」（`gb28181.media-ip`）拼的，
 * 那是给设备/VLC 用的；**浏览器访问时主机名可能不同**
 * （如平台部署在 160.202.254.65 但用户从内网 192.168.x.x 访问，
 * 或本机开发时用 localhost）。
 *
 * 两种处理方式：
 *
 * 1. **开发模式（vite dev）**：走同源相对路径，由 vite 的 `/rtp` 代理转发到
 *    媒体服务。这样无论后端在本地还是远程测试服务器，浏览器都只访问
 *    `localhost:5173`，不存在跨域与端口不可达问题。
 * 2. **生产模式（jar 内置前端）**：前端与媒体服务同机部署，
 *    把主机名替换为当前页面主机、保留端口即可。
 *
 * RTSP/RTMP 保持原样（给 VLC/ffplay 用，浏览器播不了）。
 */
export function resolvePlayUrls(streamInfo, mediaHttpPort) {
  if (!streamInfo) return {}
  const out = {
    wsFlv: streamInfo.wsFlv,
    httpFlv: streamInfo.flv,
    webrtc: streamInfo.webrtc,
    hls: streamInfo.hls,
    rtsp: streamInfo.rtsp,
    rtmp: streamInfo.rtmp
  }

  // 开发模式：统一改写成同源相对路径，交给 vite 代理
  if (import.meta.env.DEV) {
    out.wsFlv = toProxyPath(out.wsFlv, 'ws')
    out.httpFlv = toProxyPath(out.httpFlv, 'http')
    out.hls = toProxyPath(out.hls, 'http')
    return out
  }

  // 生产模式：主机名改用当前页面主机（同机部署必然可达）
  out.wsFlv = rewriteHost(out.wsFlv)
  out.httpFlv = rewriteHost(out.httpFlv)
  out.hls = rewriteHost(out.hls)

  if (!out.wsFlv && out.httpFlv) {
    out.wsFlv = out.httpFlv.replace(/^http/, 'ws')
  }
  if (!out.wsFlv && mediaHttpPort && streamInfo.deviceId && streamInfo.channelId) {
    const name = `${streamInfo.deviceId}_${streamInfo.channelId}`
    out.wsFlv = `ws://${location.hostname}:${mediaHttpPort}/rtp/${name}.live.flv`
  }
  return out
}

/**
 * 把媒体地址改写成同源代理路径（仅开发模式用）。
 *
 * `ws://1.2.3.4:48089/rtp/xxx.live.flv` → `ws://localhost:5173/rtp/xxx.live.flv`
 * `http://1.2.3.4:48089/rtp/xxx.live.flv` → `/rtp/xxx.live.flv`
 */
function toProxyPath(url, scheme) {
  if (!url) return url
  try {
    const u = new URL(url)
    const path = u.pathname + u.search
    if (scheme === 'ws') {
      const proto = location.protocol === 'https:' ? 'wss:' : 'ws:'
      return `${proto}//${location.host}${path}`
    }
    return path
  } catch (e) {
    return url
  }
}

/** 把 URL 的主机名替换为当前页面主机名（保留协议与端口）。 */
function rewriteHost(url) {
  if (!url) return url
  try {
    const u = new URL(url)
    if (u.hostname === location.hostname) return url
    u.hostname = location.hostname
    return u.toString()
  } catch (e) {
    return url
  }
}
