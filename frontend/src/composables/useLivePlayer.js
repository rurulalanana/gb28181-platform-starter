/**
 * 直播播放器（全 LivePlayer：WebRTC / FLV / HLS 都交给它）。
 *
 * <h2>为什么换掉原来的 xgplayer + 自研 MSE 引擎</h2>
 *
 * 原实现在 FLV 通路上「播两秒卡一下」，实测对照同一条 ws-flv 链路：
 *
 * <table border="1">
 *   <tr><th></th><th>LivePlayer（video.js + flv.js）</th><th>原自研引擎</th></tr>
 *   <tr><td>起播</td><td>1015 ms</td><td>2~3 s</td></tr>
 *   <tr><td>稳态缓冲</td><td><b>15.5 秒</b></td><td>2~3 秒</td></tr>
 *   <tr><td>丢帧</td><td>3 / 211</td><td>明显更多</td></tr>
 * </table>
 *
 * <p>缓冲深 5 倍是「观感更稳」的直接原因。所以直播整套交给 LivePlayer。
 *
 * <h2>协议分工</h2>
 *
 * <table border="1">
 *   <tr><th>协议</th><th>LivePlayer 通路</th><th>声音</th><th>实测</th></tr>
 *   <tr><td>{@code webrtc}</td><td>{@code Webrtc} tech（信令见
 *       {@link webrtcUrlOf}）</td><td><b>有</b>（G.711）</td>
 *       <td>H.265 2560×1440：起播 749 ms、488 帧丢 1 帧；
 *           H.264 1280×720：起播 1.0 s。<b>H.265 只能走这条</b>。</td></tr>
 *   <tr><td>{@code wsflv} / {@code httpflv} / {@code hls}</td>
 *       <td>flv.js / hls.js</td><td><b>无</b></td>
 *       <td>必须关音频（{@code hasaudio=false}），否则整路流播不出来：
 *           G.711A 报 {@code Unsupported audio codec idx: 7}，
 *           AAC 报 {@code code=4 audio decoder initialization failed}。
 *           关掉后缓冲能攒到 15 秒以上，画面很稳。</td></tr>
 * </table>
 *
 * <h2>WebRTC 的信令适配</h2>
 *
 * <p>LivePlayer 的 {@code Webrtc} tech 把信令写死成 SRS 那一套
 * （{@code webrtc://} → {@code http://} 同路径 POST、请求与应答都是 base64
 * 的 SDP），因此后端特意准备了同构端点
 * {@code POST /api/v1/webrtc/srs}（{@code WebRtcController#srsWhep}）。
 * 原来的 {@code /api/v1/webrtc/play}（JSON 包装、裸 SDP）保留给第三方/命令行
 * 调试用。
 *
 * @module useLivePlayer
 */

import { ref, shallowRef } from 'vue'
import { LivePlayerEngine, isLivePlayerAvailable } from '@/player/LivePlayerEngine'
import { log } from '@/player/FlvDiag'

/**
 * 创建直播播放器。
 *
 * <p>对外接口与原 {@code useXgPlayer} <b>完全兼容</b>
 * （{@code play / stop / switchProtocol / playing / state / protocol / exitCssFullscreen}），
 * 视图层不需要改动。
 *
 * @param {import('vue').Ref<HTMLElement>} containerRef 播放器容器
 * @param {object} [hooks] `{ onFatal, onStall, onStable, onProtoChange }`
 */
export function useLivePlayer(containerRef, hooks = {}) {
  /** 播放状态文案（直接显示给用户）。 */
  const state = ref('未开始')
  /** 是否正在播放。 */
  const playing = ref(false)
  /** 当前协议。 */
  const protocol = ref('webrtc')
  /** 当前流标识。 */
  const currentStream = ref('')
  /** 媒体信息（分辨率等），供界面展示。 */
  const videoInfo = ref(null)
  /** 播放位置（直播恒为 0，仅为接口兼容）。 */
  const position = ref(0)

  /** 当前 LivePlayer 引擎实例。 */
  let engine = null
  /** 当前 WebRTC 连接。 */
  let pc = null
  /** 当前宿主 div。 */
  let host = null
  /** 已销毁标记：异步流程返回后据此丢弃结果。 */
  let disposed = false
  /** 异步流程计时器。 */
  const timers = []
  /**
   * 本次 play() 传入的回调。
   *
   * <p>存在闭包里是因为自动回退（WebRTC 失败→FLV）发生在 play() 返回之后，
   * 那时拿不到原来的 opts 了 —— 不存下来的话，回退后的协议变化与失败
   * 都无法通知给视图层（表现为下拉写着 WebRTC、实际在放 FLV）。
   */
  let playOpts = {}

  /** 清空所有计时器。 */
  function clearTimers() {
    for (const t of timers) clearTimeout(t)
    timers.length = 0
  }

  /**
   * 建立宿主 div。
   *
   * <p><b>为什么每次都新建</b>：LivePlayer 会在宿主内自建
   * {@code .video-js} 结构并绑定 video.js 实例；复用旧宿主会和上一轮实例的
   * DOM 回收打架（表现为「新画面不出现，旧控制条还在」）。
   */
  function createHost() {
    const outer = containerRef.value
    if (!outer) {
      state.value = '播放器容器未就绪'
      return null
    }
    outer.innerHTML = ''
    const el = document.createElement('div')
    /*
     * 尺寸必须给足。
     *
     * <p>video.js 初始化时读宿主的 clientWidth/clientHeight 计算布局；
     * 容器为 0×0 时它不报错，但 video 元素也不会推进播放
     * （实测 currentTime 恒为 0）。电视墙的 grid cell 在挂载瞬间可能尚未
     * 完成布局，所以这里显式兜底一个最小高度。
     */
    el.style.cssText =
      'position:relative;width:100%;height:100%;min-height:180px;background:#000'
    outer.appendChild(el)
    host = el
    return el
  }

  /**
   * 播放。
   *
   * @param {string} streamId 流标识 `<设备编码>/<通道编码>`
   * @param {object} urls     播放地址集合 `{ wsFlv, httpFlv, webrtc, hls }`
   * @param {object} [opts]   `{ proto }`
   */
  async function play(streamId, urls, opts = {}) {
    stop()
    disposed = false
    currentStream.value = streamId
    playOpts = opts
    const proto = opts.proto || protocol.value
    protocol.value = proto
    if (!streamId) {
      state.value = '未指定流'
      return
    }

    if (proto === 'webrtc') {
      const ok = await playWebrtc(streamId)
      if (ok === false && !opts._fallback) {
        /*
         * WebRTC 失败退回 FLV（LivePlayer）。
         *
         * <p>典型原因：设备是 H.265 且浏览器/系统没有 HEVC 解码能力，
         * SDP 协商不出可用编码；或 UDP 48000 被 NAT/防火墙拦。
         * FLV 走 TCP，这两种情况都能兜住（只是没声音）。
         */
        protocol.value = 'wsflv'
        notifyProtoChange('wsflv')
        await playLivePlayer(flvUrlOf('wsflv', urls), { ...opts, _fallback: true, proto: 'wsflv' })
      }
      return
    }
    await playLivePlayer(urlOf(proto, urls), opts)
  }

  /**
   * 取该协议对应的播放地址。
   *
   * @param {string} proto 协议名
   * @param {object} urls 地址集合
   */
  function urlOf(proto, urls) {
    if (!urls) return ''
    if (proto === 'hls') return urls.hls || ''
    if (proto === 'httpflv') return urls.httpFlv || urls.wsFlv || ''
    return urls.wsFlv || urls.httpFlv || ''
  }

  /** FLV 地址（ws / http 互相兜底，它们是同一路流）。 */
  function flvUrlOf(proto, urls) {
    return urlOf(proto, urls)
  }

  /** 通知调用方协议已变（自动回退时）。 */
  function notifyProtoChange(p) {
    const cb = playOpts.onProtoChange || hooks.onProtoChange
    if (typeof cb === 'function') cb(p)
  }

  /** 通知调用方彻底失败。 */
  function notifyFatal(msg) {
    const cb = playOpts.onFatal || hooks.onFatal
    if (typeof cb === 'function') cb(msg)
  }

  /**
   * 用 LivePlayer 播放 FLV / HLS。
   *
   * @param {string} url 播放地址
   * @param {object} [opts] 透传参数
   * @returns {Promise<boolean>} 是否成功起播
   */
  async function playLivePlayer(url, opts = {}) {
    if (!url) {
      state.value = '缺少播放地址'
      return false
    }
    if (!isLivePlayerAvailable()) {
      state.value = '播放器未就绪（缺 /js/liveplayer-lib.min.js）'
      return false
    }
    const el = createHost()
    if (!el) return false

    /** 是否走组件内置的 Webrtc tech。 */
    const isWebrtc = opts._webrtc === true

    state.value = isWebrtc ? '协商中…' : '连接中…'
    playing.value = false

    return new Promise((resolve) => {
      let settled = false
      let timer = null
      const finish = (ok) => {
        if (settled) return
        settled = true
        if (timer) clearTimeout(timer)
        resolve(ok)
      }

      engine = new LivePlayerEngine({
        url,
        el,
        /*
         * 一律静音起播。
         *
         * <p>浏览器只允许静音媒体自动播放（电视墙是无人操作的自动起播），
         * 非静音会被拦成「永远不 playing」。WebRTC 通路的音轨是真实存在的
         * （PCMA/PCMU 原生解码），用户点播放器自带的音量按钮即可出声。
         */
        muted: true,
        /*
         * hasAudio 只在 WebRTC 通路放开。
         *
         * <p>FLV 通路必须关音频，否则 G.711 / AAC 的音频描述符会让整路流
         * 播不出来（见 LivePlayerEngine 类注释）。
         */
        hasAudio: isWebrtc,
        webrtc: isWebrtc,
        onDebug: (stage, extra) => log('lp', { stage, ...(extra || {}) }),
        onReady: () => {
          if (disposed) return finish(true)
          playing.value = true
          state.value = '播放中'
          finish(true)
        },
        onStats: (info) => {
          if (disposed) return
          videoInfo.value = info
        },
        onError: (msg) => {
          if (disposed) return finish(false)
          log('err', { where: 'liveplayer', msg })
          state.value = '播放失败：' + msg
          notifyFatal(msg)
          finish(false)
        }
      })

      /*
       * 起播看门狗：12 秒还没有画面就判失败。
       *
       * <p>flv.js 遇到不支持的视频编码（H.265）时会<b>静默不动</b> ——
       * readyState 一直 0、连 error 事件都不发。没有这个看门狗，
       * 用户面对的就是永久黑屏且没有任何提示。
       */
      timer = setTimeout(() => {
        if (settled || disposed) return
        if (engine && engine._readyFired) return
        const msg = isWebrtc
          ? 'WebRTC 协商超时，请确认 UDP 48000 未被防火墙拦截'
          : '该通道的视频编码 LivePlayer 不支持（多为 H.265），请切换协议为 WebRTC'
        log('err', {
          where: isWebrtc ? 'webrtc-timeout' : 'liveplayer-timeout',
          ct: engine ? engine.currentTime() : null,
          hasVideoEl: !!(engine && engine.videoEl)
        })
        state.value = msg
        notifyFatal(msg)
        finish(false)
      }, 12000)
      timers.push(timer)

      engine
        .start()
        .then((mounted) => {
          if (!mounted && !settled) finish(false)
        })
        .catch(() => {
          if (!settled) finish(false)
        })
    })
  }

  /**
   * 用原生 RTCPeerConnection 播放 WebRTC。
   *
   * <p>这是<b>唯一既有声音又最流畅</b>的通路（详见模块注释）。
   *
   * @param {string} streamId 流标识
   * @returns {Promise<boolean|null>} `true` 成功 / `false` 真失败（该回退）/
   *          `null` 已被外部停止（不回退，否则会把用户关掉的画面拉起来）
   */
  async function playWebrtc(streamId) {
    const url = webrtcUrlOf(streamId)
    if (!url) {
      state.value = '缺少 WebRTC 地址'
      return false
    }
    return playLivePlayer(url, { ...playOpts, _webrtc: true, proto: 'webrtc' })
  }

  /**
   * 生成 LivePlayer 内置 {@code Webrtc} tech 能识别的地址。
   *
   * <h3>为什么不是后端返回的那个 rtc:// 地址</h3>
   *
   * <p>LivePlayer 的 {@code Webrtc} tech（{@code liveplayer-lib.min.js}）把
   * 信令写死成了 SRS 那一套：
   * <pre>
   * const xhr = new XMLHttpRequest();
   * xhr.open('POST', url.replace('webrtc', 'http'), true);
   * xhr.send(offerSdp);                                  // 裸 SDP 作请求体
   * pc.setRemoteDescription({ type:'answer',
   *                           sdp: atob(xhr.responseText) });  // base64 应答
   * </pre>
   * 也就是说它只做「把协议头 {@code webrtc} 换成 {@code http}，然后往同一路径
   * POST 裸 SDP、读 base64 应答」，<b>不会</b>去碰 ZLM 的 {@code /index/api/webrtc}
   * （嵌入式 ZLM 也没注册那个路由）。
   *
   * <p>所以这里指向后端特意准备的同构端点 {@code /api/v1/webrtc/srs}
   * （见 {@code WebRtcController#srsWhep}），协议头换完恰好命中。
   *
   * <p><b>HTTPS 页面要用 {@code webrtcs://}</b>：替换后得到 {@code https://}。
   * 写死 {@code webrtc://} 在 https 站点会退化成 {@code http://}，被浏览器按
   * 混合内容直接拦掉。
   *
   * @param {string} streamId 流标识 `<设备编码>/<通道编码>`
   * @returns {string} 播放器地址；不在浏览器环境时返回空串
   */
  function webrtcUrlOf(streamId) {
    if (typeof window === 'undefined' || !streamId) return ''
    const scheme = window.location.protocol === 'https:' ? 'webrtcs' : 'webrtc'
    const peerId = 'lp-' + Math.random().toString(36).slice(2, 10)
    return (
      `${scheme}://${window.location.host}/api/v1/webrtc/srs` +
      `?stream=${encodeURIComponent(streamId)}&peerId=${encodeURIComponent(peerId)}`
    )
  }

  /**
   * 等待 ICE 候选收集完成（最多 3 秒）。
   *
   * <p>当前 WebRTC 走 LivePlayer，不需要自己等候选；保留给「原生 WebRTC
   * 兜底」参考（原生实现是自己 createOffer/setLocalDescription 的那一套）。
   */
  function waitIceGathering(conn) {
    return new Promise((resolve) => {
      if (conn.iceGatheringState === 'complete') return resolve()
      const done = () => {
        conn.removeEventListener('icegatheringstatechange', check)
        resolve()
      }
      const check = () => {
        if (conn.iceGatheringState === 'complete') done()
      }
      conn.addEventListener('icegatheringstatechange', check)
      setTimeout(done, 3000)
    })
  }

  /** 释放 WebRTC 资源。 */
  function cleanupWebrtc() {
    if (pc) {
      try {
        pc.close()
      } catch (e) {
        // 忽略
      }
      pc = null
    }
    if (host) {
      const v = host.querySelector('video')
      if (v && v.srcObject) {
        /*
         * 必须显式断开 MediaStream：video 只要还持有 srcObject，
         * 就会一直挂着那路 track（编码器不释放），切换协议时会残留。
         */
        try {
          v.srcObject = null
        } catch (e) {
          // 忽略
        }
      }
    }
  }

  /**
   * 切换协议（播放中直接重连）。
   *
   * @param {string} proto 新协议
   * @param {object} urls 播放地址集合
   */
  async function switchProtocol(proto, urls) {
    const sid = currentStream.value
    if (!sid) return
    await play(sid, urls, { proto })
  }

  /** 退出 CSS 全屏兜底（业务侧关闭弹窗时调用）。 */
  function exitCssFullscreen() {
    // LivePlayer 用原生全屏，无需额外处理；保留接口兼容
  }

  /** 停止并释放全部资源。 */
  function stop() {
    disposed = true
    clearTimers()
    if (engine) {
      try {
        engine.destroy()
      } catch (e) {
        // 忽略
      }
      engine = null
    }
    cleanupWebrtc()
    if (host) {
      try {
        host.remove()
      } catch (e) {
        // 忽略
      }
      host = null
    }
    if (containerRef.value) {
      try {
        containerRef.value.innerHTML = ''
      } catch (e) {
        // 忽略
      }
    }
    playing.value = false
    position.value = 0
    if (state.value === '播放中') state.value = '已停止'
  }

  return {
    playing,
    state,
    protocol,
    currentStream,
    videoInfo,
    position,
    play,
    stop,
    switchProtocol,
    exitCssFullscreen
  }
}
