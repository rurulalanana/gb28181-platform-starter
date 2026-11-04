/**
 * 视频播放器：全部走 LivePlayer（video.js + flv.js + hls.js + Webrtc tech）。
 *
 * 早期还有 xgplayer + 自研 FLV/MSE/G.711 三套内核（约 6000 行），长期与直播主链路
 * 行为不一致；现已全部移除，只保留这一套。本文件负责：整理播放地址、
 * 把官方事件/方法转成页面好用的形状、协议间自动兜底。
 * 官方文档：https://www.liveqing.com/docs/manuals/LivePlayer.html
 *
 * @module useLivePlayer
 */

import { ref } from 'vue'
import { LivePlayerEngine, isLivePlayerAvailable } from '@/player/LivePlayerEngine'
import { isProtoSupported, pickSupportedProto } from '@/player/resolvePlayUrls'
import { log } from '@/player/FlvDiag'

/**
 * 把登录 token 拼到地址上：<video src> 这类请求带不了自定义请求头。
 *
 * 录像静态地址 /record/... 走地址签名（?e=&t=），后端 playUrl 已带好，不要重复拼。
 *
 * @param {string} url
 * @param {string} [token]
 * @returns {string}
 */
function withToken(url, token) {
  if (!url || !token) return url
  try {
    const u = new URL(url, location.href)
    u.searchParams.set('Authorization', token)
    return u.toString()
  } catch (e) {
    return url
  }
}

/**
 * 创建播放器。
 *
 * @param {import('vue').Ref<HTMLElement>} containerRef 播放器容器
 * @param {object} [hooks] `{ onFatal, onStall, onStable, onProtoChange, onEnded }`
 */
export function useLivePlayer(containerRef, hooks = {}) {
  const state = ref('未开始')
  const playing = ref(false)
  const protocol = ref('webrtc')
  const currentStream = ref('')
  const videoInfo = ref(null)
  const position = ref(0)
  const currentUrl = ref('')

  let engine = null
  let host = null
  // 已销毁：异步流程返回后据此丢弃结果
  let disposed = false
  const timers = []
  // 事件订阅的取消函数（重建播放器时要先解绑）
  const unsubs = []
  /**
   * 本次 play() 的回调。自动兜底发生在 play() 返回之后，那时拿不到原 opts；
   * 不存下来，兜底后的协议变化与失败就通知不到视图层。
   */
  let playOpts = {}

  function clearTimers() {
    for (const t of timers) clearTimeout(t)
    timers.length = 0
  }

  function clearUnsubs() {
    for (const u of unsubs) {
      try {
        u()
      } catch (e) {
        // 忽略
      }
    }
    unsubs.length = 0
  }

  /**
   * 建立宿主 div。每次都新建：复用旧宿主会和上一轮 video.js 实例的 DOM 回收打架
   * （新画面不出现、旧控制条还在）。
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
     * 尺寸必须给足但不能超过父容器：0×0 时 video.js 不报错，但 currentTime 恒为 0。
     * 不用固定 min-height:180px——电视墙小格只有 150px 时宿主会撑破父容器，
     * .video-box 的 overflow:hidden 把控制栏裁成「看得见却点不动」。
     * 高度严格跟随父容器，0×0 兜底交给引擎按父容器高度动态补。
     */
    el.style.cssText =
      'position:relative;width:100%;height:100%;max-height:100%;background:#000'
    outer.appendChild(el)
    host = el
    return el
  }

  /**
   * 播放。直播与设备回放都走这里（两者都是「设备主动推上来的实时流」）。
   *
   * @param {string} streamId 流标识 `<设备编码>/<通道编码>`
   * @param {object} urls     播放地址集合 `{ wsFlv, httpFlv, webrtc, hls }`
   * @param {object} [opts]   `{ proto, vod, onStall, onStable, onProtoChange }`
   */
  async function play(streamId, urls, opts = {}) {
    stop()
    disposed = false
    currentStream.value = streamId
    currentUrl.value = ''
    /*
     * 实时流一律隐藏进度条：直播与设备回放都是主动推上来的码流，没有可 seek 的时间轴。
     * video.js 按 seekable() 显隐进度条，播放过程中宽度反复变化、按钮忽左忽右。
     * 注入 playOpts 让 WebRTC / FLV 两条通路行为一致；playFile（MP4）不走这里。
     */
    playOpts = { ...opts, _hideProgress: true }
    let proto = opts.proto || protocol.value
    protocol.value = proto
    if (!streamId) {
      state.value = '未指定流'
      return
    }

    /*
     * 先剔除浏览器根本放不了的协议：iPhone/iPad Safari 没有 MediaSource，
     * flv.js 静默不动，12 秒后看门狗会误报「编码不支持（多为 H.265）」。
     * 降级而非报错：分享链接可能带 &proto=flv（PC 复制、手机打开），
     * 经 onProtoChange 告知真实协议，菜单与按钮文字跟着改。
     */
    if (!isProtoSupported(proto)) {
      const wanted = proto
      const fallback = pickSupportedProto(proto)
      if (!fallback) {
        state.value = '当前浏览器不支持任何可用的播放协议'
        notifyFatal(state.value)
        return
      }
      log('lp', { stage: 'proto-unsupported', want: wanted, use: fallback })
      proto = fallback
      protocol.value = fallback
      // 把「原本想要的」也传出去，业务层才能提示「已自动切到 WebRTC」
      notifyProtoChange(fallback, wanted)
    }

    if (proto === 'webrtc') {
      /*
       * WebRTC 失败如实报错，不自动换协议：兜底会把「这条流起不来」的真实原因
       * 掩盖成「换了个协议还是不行」，用户只看到黑屏，无从排查。
       */
      const ok = await playWebrtc(streamId)
      if (ok === false) reportFatal()
      return
    }

    const ok = await playLivePlayer(urlOf(proto, urls), playOpts, proto)
    if (!ok) reportFatal()
  }

  /**
   * 播放点播文件（云端录像 mp4）：live=false 时组件显示进度条，
   * 支持 setCurrentTime 定位与 getDuration 取总时长。
   *
   * @param {string} url 媒体地址
   * @param {object} [opts] `{ onEnded, onFatal, token }`
   */
  async function playFile(url, opts = {}) {
    stop()
    disposed = false
    currentStream.value = ''
    playOpts = opts
    if (!url) {
      state.value = '缺少播放地址'
      return
    }
    const src = withToken(url, opts.token)
    currentUrl.value = src
    const ok = await playLivePlayer(src, { ...opts, _vod: true })
    if (!ok) reportFatal()
  }

  /**
   * 取该协议对应的播放地址。
   */
  function urlOf(proto, urls) {
    if (!urls) return ''
    if (proto === 'hls') return urls.hls || ''
    if (proto === 'httpflv') return urls.httpFlv || urls.wsFlv || ''
    return urls.wsFlv || urls.httpFlv || ''
  }

  // FLV 地址（ws / http 是同一路流，互相兜底）
  function flvUrlOf(proto, urls) {
    return urlOf(proto, urls)
  }

  /**
   * 通知调用方协议已变（自动降级时）。
   *
   * @param {string} p 实际使用的协议
   * @param {string} [wanted] 用户原本想要、但本机放不了的协议
   */
  function notifyProtoChange(p, wanted) {
    const cb = playOpts.onProtoChange || hooks.onProtoChange
    if (typeof cb === 'function') cb(p, wanted)
  }

  /** 通知调用方彻底失败。 */
  function notifyFatal(msg) {
    const cb = playOpts.onFatal || hooks.onFatal
    if (typeof cb === 'function') cb(msg)
  }

  // 本次播放的致命错误（延迟上报用）
  let fatalMsg = ''

  function reportFatal() {
    if (!fatalMsg) return
    const msg = fatalMsg
    fatalMsg = ''
    notifyFatal(msg)
  }

  /**
   * 用 LivePlayer 播放。
   *
   * @param {string} url 播放地址
   * @param {object} [opts] 透传参数
   * @returns {Promise<boolean>} 是否成功起播
   */
  async function playLivePlayer(url, opts = {}, proto = '') {
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
    /** 是否点播（点播时组件显示进度条）。 */
    const isVod = opts._vod === true
    /**
     * 原生 HLS 不经 video.js 是必需的：video.js 会把 m3u8 交给残缺的 contrib-hls
     * handler 改走 MSE，iOS 没有 MSE，明明能播的 HLS 也会失败。
     */
    const nativeHls = proto === 'hls'

    state.value = isWebrtc
      ? '协商中…'
      : (nativeHls ? '加载中…' : (isVod ? '加载中…' : '连接中…'))
    playing.value = false
    fatalMsg = ''

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
        nativeHls,
        /*
         * 一律静音起播：浏览器只允许静音媒体自动播放，非静音会被拦成「永远不 playing」。
         * WebRTC 音轨真实存在（PCMA/PCMU 原生解码），用户点音量按钮即可出声。
         */
        muted: true,
        // FLV 通路必须关音频（G.711/AAC 描述符会让整路流播不出来），WebRTC 放开
        hasAudio: isWebrtc,
        webrtc: isWebrtc,
        vod: isVod,
        // 只有真正可 seek 的云端录像 MP4 才保留进度条（由 play() 统一注入 _hideProgress）
        hideProgress: opts._hideProgress === true,
        // 控制栏按钮由视图透传：电视墙不需要协议切换
        controlButtons: opts.controlButtons || null,
        onControlButton: opts.onControlButton,
        onDebug: (stage, extra) => log('lp', { stage, ...(extra || {}) }),
        onReady: () => {
          if (disposed) return finish(true)
          playing.value = true
          state.value = '播放中'
          userReady = true
          bindMediaEvents(opts)
          bindVodControls(opts.vod)
          armStableTimer(opts)
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
          fatalMsg = msg
          if (!opts._deferFatal) notifyFatal(msg)
          finish(false)
        }
      })

      /*
       * 起播看门狗 12 秒：flv.js 遇到不支持的编码（H.265）静默不动，
       * readyState 恒 0、连 error 都不发，没看门狗就是永久黑屏无提示。
       */
      timer = setTimeout(() => {
        if (settled || disposed) return
        if (engine && engine._readyFired) return
        const msg = isWebrtc
          ? 'WebRTC 协商超时，请确认 UDP 48000 未被防火墙拦截'
          : (nativeHls
            ? 'HLS 加载超时，请确认媒体端口 8081 可从手机直连'
            : '该通道的视频编码 LivePlayer 不支持（多为 H.265），请切换协议为 WebRTC')
        log('err', {
          where: isWebrtc ? 'webrtc-timeout' : 'liveplayer-timeout',
          ct: engine ? engine.currentTime() : null,
          hasVideoEl: !!(engine && engine.videoEl)
        })
        state.value = msg
        fatalMsg = msg
        /*
         * 有 onStall 交给业务层（停会话 + 重新点播：服务端媒体源可能已丢视频轨，
         * 播放器自己救不回来）；没有才当致命错误上报。
         */
        const onStall = playOpts.onStall || hooks.onStall
        if (typeof onStall === 'function') {
          onStall(msg)
        } else if (!opts._deferFatal) {
          notifyFatal(msg)
        }
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
   * 稳定播放 10 秒后通知业务层把「重建流」预算还回去，否则几次互不相干的
   * 起播故障就会耗光预算，之后真出问题也不重建。
   *
   * @param {object} opts 本次播放参数
   */
  function armStableTimer(opts) {
    const cb = playOpts.onStable || opts.onStable || hooks.onStable
    if (typeof cb !== 'function') return
    const t = setTimeout(() => {
      if (disposed) return
      if (!playing.value) return
      cb()
    }, 10000)
    timers.push(t)
  }

  /**
   * 绑定媒体事件（位置上报、结束回调），用官方 timeupdate / ended。
   *
   * @param {object} opts 本次播放参数
   */
  function bindMediaEvents(opts) {
    clearUnsubs()
    if (!engine) return
    /** 用于识别媒体时间轴的突变（设备 npt 跳转后 PTS 会整段前移）。 */
    let lastPos = null
    const syncPosition = () => {
      if (disposed) return
      /*
       * 优先读原生 media 的 currentTime：WebRTC 的 tech 不是 Html5，
       * player.currentTime() 恒返回 0，而绑了 MediaStream 的 <video> 时间在走。
       */
      const media = engine.videoEl
      const cur = media && Number.isFinite(media.currentTime)
        ? media.currentTime
        : engine.currentTime()
      const prev = lastPos
      lastPos = cur
      /* 时间轴突变（设备 npt 跳转后 PTS 整段前移）要重新对齐，否则段内位置会算两次 */
      if (prev !== null && Number.isFinite(cur) && Math.abs(cur - prev) > 3) {
        if (opts.vod && typeof opts.vod.onJump === 'function') opts.vod.onJump(cur - prev)
      }
      position.value = cur
    }
    unsubs.push(engine.on('timeupdate', syncPosition))
    /*
     * 轮询兜底：WebRTC 通路不派发 timeupdate。实测 media.currentTime 从 45.5 走到 49.6，
     * video.js 的 timeupdate 一次都不来，进度游标会永远停在起播值。
     */
    const posTimer = setInterval(syncPosition, 500)
    unsubs.push(() => clearInterval(posTimer))
    unsubs.push(
      engine.on('ended', () => {
        if (disposed) return
        playing.value = false
        state.value = '播放结束'
        const cb = playOpts.onEnded || hooks.onEnded
        if (typeof cb === 'function') cb()
      })
    )
  }

  /**
   * 国标回放控制层：把官方控件的 seeking/pause/play/ratechange 转成 SIP 指令
   * （INFO / PLAY / PAUSE / Scale）。
   *
   * 伪点播的流里没有总时长、也没有可 seek 的位置，改 currentTime 会落到 buffered
   * 之外并永久转圈；定位要换算成段内秒数，由业务层发指令让设备自己跳。
   *
   * @param {object} vod `{ duration, onSeek, onPause, onResume, onRate }`
   */
  function bindVodControls(vod) {
    if (!engine || !vod) return
    /*
     * 监听官方 seeking：video.js 9 已移除 player.seek()。落点交给业务层换算成
     * 段内秒数再发国标指令，真改 currentTime 会落到 buffered 外永久转圈。
     */
    let seekTarget = null
    /** 抑制窗口：本文件改 currentTime 也会触发 seeking，不拦会「自己拖自己」死循环。 */
    let suppressUntil = 0
    unsubs.push(
      engine.on('seeking', () => {
        if (disposed) return
        if (Date.now() < suppressUntil) return
        const p = engine.vjsPlayer()
        const t = p && typeof p.currentTime === 'function' ? p.currentTime() : 0
        const target = Math.max(0, Math.min(Number(vod.duration) || 0, Number(t) || 0))
        seekTarget = target
        if (typeof vod.onSeek === 'function') vod.onSeek(target)
      })
    )
    // seek 失败时 video.js 会把 currentTime 弹回原处，清掉待确认的落点即可
    unsubs.push(
      engine.on('seeked', () => {
        if (disposed || seekTarget === null) return
        seekTarget = null
      })
    )

    /*
     * 跨过定位造成的缓冲空洞：设备 npt 跳转后 PTS 落到另一处，MSE 里断成两段，
     * 播放点停在旧数据末尾会一直 waiting（看起来就是拖完卡死、进而被判重连）。
     * 停滞时把播放点挪到下一段已缓冲数据的起点，不重建 WebSocket 与媒体源。
     */
    const GAP_TOLERANCE = 0.4
    const skipBufferGap = () => {
      const media = engine && engine.videoEl
      if (!media || disposed) return false
      const b = media.buffered
      if (!b || b.length === 0) return false
      const ct = media.currentTime
      for (let i = 0; i < b.length; i++) {
        const start = b.start(i)
        if (ct + GAP_TOLERANCE < start) {
          suppressUntil = Date.now() + 200
          try {
            media.currentTime = start + 0.05
          } catch (e) {
            return false
          }
          return true
        }
        if (ct <= b.end(i) + GAP_TOLERANCE) return false
      }
      return false
    }
    const gapTimer = setInterval(skipBufferGap, 500)
    unsubs.push(() => clearInterval(gapTimer))
    unsubs.push(
      engine.on('waiting', () => {
        if (!disposed) skipBufferGap()
      })
    )

    /*
     * 只认用户操作：MSE 欠载恢复、缓冲边界也会触发 pause/play，当成意图会反复给
     * 设备发 PAUSE，设备停推后媒体源被注销，画面永久定格。
     */
    const USER_ACTION_WINDOW_MS = 600
    let userActionAt = 0
    const p = engine.vjsPlayer()
    if (p) {
      const origPlay = typeof p.play === 'function' ? p.play.bind(p) : null
      const origPause = typeof p.pause === 'function' ? p.pause.bind(p) : null
      if (origPlay) {
        p.play = (...args) => {
          userActionAt = Date.now()
          return origPlay(...args)
        }
      }
      if (origPause) {
        p.pause = (...args) => {
          userActionAt = Date.now()
          return origPause(...args)
        }
      }
    }
    unsubs.push(
      engine.on('pause', () => {
        if (disposed || !userReady) return
        // 非用户触发的暂停（欠载恢复等）不通知设备，交给播放器自己续播
        if (Date.now() - userActionAt > USER_ACTION_WINDOW_MS) {
          engine.play()
          return
        }
        if (typeof vod.onPause === 'function') vod.onPause()
      })
    )
    unsubs.push(
      engine.on('play', () => {
        if (disposed || !userReady) return
        if (Date.now() - userActionAt > USER_ACTION_WINDOW_MS) return
        if (typeof vod.onResume === 'function') vod.onResume()
      })
    )

    // 倍速：ratechange → 国标 Scale（设备侧变速推流）；本地也必须同倍速消耗，否则缓冲一直涨
    let lastRate = 1
    unsubs.push(
      engine.on('ratechange', () => {
        if (disposed) return
        const media = engine.videoEl
        const rate = media ? media.playbackRate : 1
        if (!Number.isFinite(rate) || rate === lastRate) return
        lastRate = rate
        if (typeof vod.onRate === 'function') vod.onRate(rate)
      })
    )
  }

  /**
   * 调整本地播放速率（回放倍速的客户端那一半）。
   *
   * 国标倍速是设备侧 Scale，客户端不跟着加速的话缓冲会一路涨（延迟越来越大），
   * 而不是快放。设 playbackRate 会触发 ratechange，由 bindVodControls 转成 SIP Scale。
   *
   * @param {number} rate 1 为正常
   * @returns {boolean} 播放器未就绪时返回 false
   */
  function setPlaybackRate(rate) {
    const media = engine && engine.videoEl
    if (!media) return false
    try {
      media.playbackRate = rate
      return true
    } catch (e) {
      return false
    }
  }

  /**
   * 已缓冲数据的末尾（秒）——设备真实推送速率的唯一可靠判据：
   * position 在缓冲富余时总按本地速率走，只有缓冲末尾只随收到的数据量前进。
   *
   * @returns {number} 无缓冲时返回 0
   */
  function bufferEnd() {
    const media = engine && engine.videoEl
    const b = media && media.buffered
    return b && b.length ? b.end(b.length - 1) : 0
  }

  /** 用户是否已经开始看（起播后才有资格上报暂停/继续）。 */
  let userReady = false

  /**
   * 用内置 Webrtc tech 播放。
   *
   * @param {string} streamId 流标识
   * @returns {Promise<boolean|null>} true 成功 / false 真失败（该兜底）；
   *          null 已被外部停止（不兜底，否则会把用户关掉的画面拉起来）
   */
  async function playWebrtc(streamId) {
    const url = webrtcUrlOf(streamId)
    if (!url) {
      state.value = '缺少 WebRTC 地址'
      return false
    }
    return playLivePlayer(url, { ...playOpts, _webrtc: true, proto: 'webrtc' }, 'webrtc')
  }

  /**
   * 生成内置 Webrtc tech 能识别的地址。
   *
   * 该 tech 的信令写死成 SRS 那套：把 url 里的 webrtc 换成 http 后 POST base64 SDP、
   * 读 base64 应答，不会碰 ZLM 的 /index/api/webrtc。所以指向后端同构端点
   * /api/v1/webrtc/srs（WebRtcController#srsWhep），协议头换完恰好命中。
   * HTTPS 页面必须用 webrtcs://，否则替换后是 http:// 会被混合内容拦掉。
   *
   * @param {string} streamId `<设备编码>/<通道编码>`
   * @returns {string} 非浏览器环境返回空串
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
   * 切换协议（停掉再重新 play）。
   *
   * 必须带上上次 play() 的选项：只传 proto 会丢掉 controlButtons 等设置
   * （实测切一次协议「播放协议」按钮就不见了），暂停/重连回调也会失效。
   *
   * @param {string} proto 新协议
   * @param {object} urls 播放地址集合
   */
  async function switchProtocol(proto, urls) {
    const sid = currentStream.value
    if (!sid) return
    await play(sid, urls, { ...playOpts, proto })
  }

  /** 退出 CSS 全屏兜底（业务侧关闭弹窗时调用）。 */
  function exitCssFullscreen() {
    // LivePlayer 用原生全屏，无需额外处理；保留接口兼容
  }

  /** 更新控制栏按钮文字（如播放协议），引擎不知道显示名，由业务层传入。 */
  function setControlButtonText(id, text) {
    if (engine && typeof engine.setControlButtonText === 'function') {
      engine.setControlButtonText(id, text)
    }
  }

  /** 停止并释放全部资源。 */
  function stop() {
    disposed = true
    clearTimers()
    clearUnsubs()
    if (engine) {
      try {
        engine.destroy()
      } catch (e) {
        // 忽略
      }
      engine = null
    }
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
    userReady = false
    if (state.value === '播放中') state.value = '已停止'
  }

  return {
    playing,
    state,
    protocol,
    currentStream,
    currentUrl,
    videoInfo,
    position,
    setPlaybackRate,
    bufferEnd,
    play,
    playFile,
    stop,
    switchProtocol,
    setControlButtonText,
    exitCssFullscreen
  }
}
