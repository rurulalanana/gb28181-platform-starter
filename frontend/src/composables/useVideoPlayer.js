import { ref, shallowRef } from 'vue'
import mpegts from 'mpegts.js'
import { webrtcApi } from '@/api'

/**
 * 统一视频播放器。
 *
 * 支持两条播放通路，按延迟与网络条件择优：
 *
 * - **ws-flv**：WebSocket 拉 FLV，延迟 1~2 秒，占用普通 HTTP 端口（媒体 HTTP 端口），
 *   适合跨网/公网访问，对 NAT 友好；
 * - **webrtc**：WebRTC 直连，延迟 < 500ms，画质最优，
 *   但需浏览器与平台网络可直连（无 ICE 服务时不能跨 NAT）。
 *
 * 播放前需先让平台侧完成点播（`POST /api/v1/stream/start`），
 * 流标识为 `<设备编码>/<通道编码>`。
 */
export function useVideoPlayer() {
  const videoEl = ref(null)
  const playing = ref(false)
  const state = ref('空闲')
  const protocol = ref('wsflv')
  const currentStream = ref('')
  const stats = ref(null)

  /** mpegts 播放器实例（ws-flv 通路）。 */
  const mpegtsPlayer = shallowRef(null)
  /** RTCPeerConnection 实例（WebRTC 通路）。 */
  const pc = shallowRef(null)
  /** 销毁标记：异步协商返回后若已停止，丢弃结果。 */
  let disposed = false
  /** 重连定时器。 */
  let retryTimer = null

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
      playWsFlv(urls?.wsFlv || urls?.httpFlv)
    }
  }

  /** ws-flv 通路：WebSocket 拉 FLV 交给 mpegts.js 解封装。 */
  function playWsFlv(url) {
    const video = videoEl.value
    if (!video) {
      state.value = '播放器未就绪'
      return
    }
    if (!url) {
      state.value = '缺少 ws-flv 地址'
      return
    }
    if (!mpegts.isSupported()) {
      state.value = '当前浏览器不支持 ws-flv，请改用 WebRTC'
      return
    }

    state.value = '连接中…'
    // 显式静音：浏览器只允许静音视频自动播放
    video.muted = true

    const player = mpegts.createPlayer(
      { type: 'flv', isLive: true, url, cors: true },
      {
        enableWorker: false,
        enableStashBuffer: false,
        stashInitialSize: 128,
        liveBufferLatencyChasing: true,
        liveBufferLatencyMaxLatency: 2.0,
        liveBufferLatencyMinRemain: 0.5,
        lazyLoad: false,
        autoCleanupSourceBuffer: true
      }
    )
    mpegtsPlayer.value = player

    player.on(mpegts.Events.ERROR, (type, detail) => {
      // 流未就绪（设备还没推流）是常见瞬时错误，短暂重试即可恢复
      if (disposed) return
      state.value = '连接中断，重试中…'
      playing.value = false
      scheduleRetry(url)
    })
    player.on(mpegts.Events.MEDIA_INFO, (info) => {
      stats.value = info
    })
    player.on(mpegts.Events.STATISTICS_INFO, (info) => {
      if (stats.value) {
        stats.value.speed = info.speed
      }
    })

    player.attachMediaElement(video)
    try {
      player.load()
      const p = player.play()
      if (p && typeof p.catch === 'function') {
        p.catch(() => {
          state.value = '请点击画面开始播放'
        })
      }
      playing.value = true
      state.value = '播放中'
    } catch (e) {
      state.value = '播放失败：' + (e.message || e)
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
      destroyMpegts()
      playWsFlv(url)
    }, 2500)
  }

  /** WebRTC 通路：浏览器 offer → 后端交换 answer。 */
  async function playWebrtc(stream) {
    try {
      state.value = '协商中…'
      const conn = new RTCPeerConnection({ iceServers: [] })
      pc.value = conn
      conn.ontrack = (event) => {
        if (disposed) return
        if (videoEl.value) {
          videoEl.value.srcObject = event.streams[0]
        }
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
    destroyMpegts()
    if (pc.value) {
      try {
        pc.value.close()
      } catch (e) {
        // 忽略关闭异常
      }
      pc.value = null
    }
    if (videoEl.value) {
      videoEl.value.srcObject = null
    }
    playing.value = false
    stats.value = null
    if (state.value === '播放中') {
      state.value = '已停止'
    }
  }

  function destroyMpegts() {
    const player = mpegtsPlayer.value
    if (!player) return
    try {
      player.pause()
      player.unload()
      player.detachMediaElement()
      player.destroy()
    } catch (e) {
      // 忽略销毁异常
    }
    mpegtsPlayer.value = null
  }

  return {
    videoEl,
    playing,
    state,
    protocol,
    currentStream,
    stats,
    play,
    stop,
    switchProtocol
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
 * （如平台部署在 123.57.109.223 但用户从内网 192.168.x.x 访问，
 * 或本机开发时用 localhost）。
 *
 * 因此这里把主机名替换为当前页面的主机名——媒体服务与平台同机部署时
 * 一定可达，端口沿用后端返回值。RTSP/RTMP 保持原样（给 VLC/ffplay 用）。
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
  // 浏览器播放通路：主机名改用当前页面主机（同机部署必然可达）
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
