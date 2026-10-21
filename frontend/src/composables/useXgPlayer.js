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
  /** 销毁标记：异步流程返回后若已停止，丢弃结果。 */
  let disposed = false

  /**
   * 播放。
   *
   * @param {string} streamId 流标识 `<设备编码>/<通道编码>`
   * @param {object} urls     播放地址集合 `{ wsFlv, httpFlv, webrtc }`
   * @param {object} [opts]   `{ proto: 'wsflv'|'webrtc' }`
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

    if (proto === 'webrtc') {
      await playWebrtc(streamId)
    } else {
      await playFlv(urls?.wsFlv || urls?.httpFlv)
    }
  }

  /** 直播通路：自研 ws-flv。 */
  async function playFlv(url) {
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
        }
        stats.value = info
      }
    })
    stream.start()

    // video 的音量/静音按钮由 xgplayer 操作，Web Audio 这条链路要跟着变
    const syncVolume = () => stream?.setVolume(media.muted ? 0 : media.volume)
    media.addEventListener('volumechange', syncVolume)
    syncVolume()

    p.on('playing', () => {
      if (disposed) return
      playing.value = true
      state.value = '播放中'
    })
    p.on('pause', () => {
      playing.value = false
      if (!disposed && state.value === '播放中') state.value = '已暂停'
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

/** xgplayer 通用外观配置：去掉与业务无关的入口，保持界面干净。 */
function baseUiConfig() {
  return {
    // 关掉测试网速/下载/画质切换等用不上的控件
    ignores: ['download', 'testSpeed', 'definition']
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
