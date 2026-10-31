/**
 * 视频播放器（全部走 LivePlayer 官方能力）。
 *
 * <h2>为什么只有这一个播放器</h2>
 *
 * <p>本平台早期有三套播放器内核：xgplayer 外壳 + 自研 FLV 解析（{@code WsFlvStream}）、
 * 自研 MSE 重封装（{@code MseRecordPlayer} + {@code Mp4Remuxer}）、自研 G.711 解码。
 * 三套内核合计约 6000 行，维护成本极高，且**长期与直播主链路的行为不一致**
 * （直播早改用 LivePlayer，回放还留在自研内核里，导致同一路流两种体验）。
 *
 * <p>现在统一为 LivePlayer（video.js + flv.js + hls.js + Webrtc tech），
 * 本文件只做三件事：
 * <ol>
 *   <li>把播放地址整理成 LivePlayer 能识别的形式；</li>
 *   <li>把 LivePlayer 的官方事件/方法转发成页面好用的形状；</li>
 *   <li>在协议之间做<b>自动兜底</b>（WebRTC ↔ FLV），避免用户对着黑屏干瞪眼。</li>
 * </ol>
 *
 * <h2>LivePlayer 官方能力对照</h2>
 *
 * <p>官方文档：https://www.liveqing.com/docs/manuals/LivePlayer.html
 * <table border="1">
 *   <tr><th>能力</th><th>官方方式</th><th>本文件封装</th></tr>
 *   <tr><td>播放</td><td>{@code play()}</td><td>{@link useLivePlayer} 的 {@code play()}</td></tr>
 *   <tr><td>暂停</td><td>{@code pause()}</td><td>同上</td></tr>
 *   <tr><td>位置</td><td>{@code getCurrentTime()}</td><td>{@code position} ref</td></tr>
 *   <tr><td>定位</td><td>{@code setCurrentTime(t)}</td><td>vod 的 {@code onSeek}</td></tr>
 *   <tr><td>时长</td><td>{@code getDuration()}</td><td>点播（mp4）才有值</td></tr>
 *   <tr><td>事件</td><td>{@code timeupdate/play/pause/ended/canplay}</td>
 *       <td>{@code position}、{@code playing}、{@code onEnded}</td></tr>
 *   <tr><td>点播</td><td>{@code live=false} 显示进度条</td><td>{@code playFile()}</td></tr>
 * </table>
 *
 * <h2>协议与自动兜底</h2>
 *
 * <table border="1">
 *   <tr><th>协议</th><th>LivePlayer 通路</th><th>声音</th><th>说明</th></tr>
 *   <tr><td>{@code webrtc}</td><td>{@code Webrtc} tech（信令见 {@link webrtcUrlOf}）</td>
 *       <td><b>有</b>（G.711）</td>
 *       <td>H.265 只能走这条（浏览器 MSE 不支持 HEVC，flv.js/hls.js 都过不去）。</td></tr>
 *   <tr><td>{@code wsflv} / {@code httpflv}</td><td>flv.js</td><td><b>无</b></td>
 *       <td>必须关音频（{@code hasaudio=false}），否则整路流播不出来：
 *           G.711A 报 {@code Unsupported audio codec idx: 7}，
 *           AAC 报 {@code code=4 audio decoder initialization failed}。</td></tr>
 *   <tr><td>{@code hls}</td><td>hls.js</td><td><b>无</b></td><td>延迟高，只在弱网时兜底。</td></tr>
 * </table>
 *
 * <p><b>自动兜底</b>：任一非 WebRTC 协议解码失败时，自动切到 WebRTC 并同步页面上的
 * 协议选择器；反过来 WebRTC 失败（设备只有 H.265 且浏览器不支持、或 UDP 被拦）
 * 也退回 FLV。这样无论用户选了什么，都不会停在黑屏上。
 *
 * @module useLivePlayer
 */

import { ref } from 'vue'
import { LivePlayerEngine, isLivePlayerAvailable } from '@/player/LivePlayerEngine'
import { isProtoSupported, pickSupportedProto } from '@/player/resolvePlayUrls'
import { log } from '@/player/FlvDiag'

/**
 * 把登录 token 拼到地址上。
 *
 * <p>{@code <video src>} 这类请求带不了自定义请求头，只能把 token 拼在 URL 上
 * （后端 Sa-Token 配了从 query 读）。
 *
 * <p><b>注意</b>：录像静态地址 {@code /record/...} 由 nginx 直接 sendfile，
 * 它的鉴权走的是<b>地址签名</b>（{@code ?e=过期时间&t=签名}），
 * 后端 {@code querydaily} 返回的 {@code playUrl} 已经带好了签名，
 * 不需要再拼 token。只有退回受保护接口 {@code /api/v1/...} 时才需要。
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
  /** 播放位置（秒）。 */
  const position = ref(0)
  /** 当前媒体地址（点播文件模式下是文件地址）。 */
  const currentUrl = ref('')

  /** 当前 LivePlayer 引擎实例。 */
  let engine = null
  /** 当前宿主 div。 */
  let host = null
  /** 已销毁标记：异步流程返回后据此丢弃结果。 */
  let disposed = false
  /** 异步流程计时器。 */
  const timers = []
  /** 事件订阅的取消函数（重建播放器时要先解绑）。 */
  const unsubs = []
  /**
   * 本次 play() 传入的回调。
   *
   * <p>存在闭包里是因为自动兜底（WebRTC 失败→FLV）发生在 play() 返回之后，
   * 那时拿不到原来的 opts 了 —— 不存下来的话，兜底后的协议变化与失败
   * 都无法通知给视图层（表现为下拉写着 WebRTC、实际在放 FLV）。
   */
  let playOpts = {}

  /** 清空所有计时器。 */
  function clearTimers() {
    for (const t of timers) clearTimeout(t)
    timers.length = 0
  }

  /** 解绑所有事件订阅。 */
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
     * 尺寸必须给足，但**不能超过父容器**。
     *
     * <p>video.js 初始化时读宿主的 clientWidth/clientHeight 计算布局；
     * 容器为 0×0 时它不报错，但 video 元素也不会推进播放
     * （实测 currentTime 恒为 0）。电视墙的 grid cell 在挂载瞬间可能尚未
     * 完成布局，所以需要一个兜底高度。
     *
     * <p><b>为什么不用固定的 min-height:180px</b>：电视墙小格高度可能只有
     * 150px 左右。宿主被撑到 180px 后，video.js 按 180 计算控制栏位置，
     * {@code bottom:0} 就落在容器之外 —— 而 {@code .video-box} 是
     * {@code overflow:hidden}，控制栏被裁掉大半，表现为
     * <b>「工具栏看得见却点不动」</b>（实际可点区域只剩露出来的那一条）。
     *
     * <p>改用 {@code min-height:0} + {@code max-height:100%}：
     * 高度严格跟随父容器；真正的「0×0 兜底」交给引擎层
     * （{@code LivePlayerEngine#start} 会按父容器实际高度动态补 min-height）。
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
     * 实时流一律隐藏进度条。
     *
     * <p><b>为什么在入口统一注入</b>：直播与设备回放拿到的都是设备主动推上来的
     * 码流，没有可 seek 的时间轴。而 video.js 的进度条显隐取决于
     * {@code player.seekable()}：起播前为空（隐藏）、缓冲够了又有值（显示）、
     * 断流再归零 —— 于是控制栏的宽度分配<b>在播放过程中反复变化</b>，
     * 按钮忽左忽右，用户看到的就是「布局跳了、乱了」。
     *
     * <p>注入到 {@code playOpts} 而不是只传给 FLV 分支：WebRTC 通路的
     * {@code playWebrtc} 会展开 {@code ...playOpts}，这样两条通路行为一致。
     * 早先只加在 FLV 分支上，默认走 WebRTC 的实时预览页就漏掉了。
     *
     * <p>{@code playFile}（云端录像 MP4）<b>不</b>走这里，它的进度条保留 ——
     * 那是真正可 seek 的文件点播，进度条是主要控件。
     */
    playOpts = { ...opts, _hideProgress: true }
    let proto = opts.proto || protocol.value
    protocol.value = proto
    if (!streamId) {
      state.value = '未指定流'
      return
    }

    /*
     * 先剔除「这个浏览器根本放不了」的协议。
     *
     * <p><b>为什么必须在播放前拦</b>：iPhone / iPad Safari 没有
     * {@code MediaSource}，flv.js 起不来，而它的失败表现是<b>静默不动</b> ——
     * 12 秒后看门狗报「该通道的视频编码不支持（多为 H.265）」，
     * 把「浏览器不支持这个容器」说成了「编码不支持」，排查方向完全跑偏
     * （用户实测反馈「手机上的 FLV 播不出来」）。
     *
     * <p>降级而不是报错：分享链接里可能带着 {@code &proto=flv}
     * （在 PC 上复制、发到手机上打开），那是完全正常的使用路径，
     * 直接白屏对它没有帮助。降级后通过 {@code onProtoChange} 把
     * 真实协议告诉业务层，菜单与按钮文字都能跟着改对。
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
       * WebRTC 失败就如实报错，<b>不自动换协议</b>。
       *
       * <p><b>为什么不兜底</b>：兜底会把「这条流起不来」这个真实原因掩盖成
       * 「换了个协议还是起不来」，用户看到的只是一块黑屏加一句含糊的提示，
       * 排查时完全无从下手。失败就明说，让用户知道是编码不支持、还是网络不通、
       * 还是设备没推流。
       */
      const ok = await playWebrtc(streamId)
      if (ok === false) reportFatal()
      return
    }

    const ok = await playLivePlayer(urlOf(proto, urls), playOpts)
    if (!ok) reportFatal()
  }

  /**
   * 播放一个点播文件（云端录像 mp4）。
   *
   * <p>走 LivePlayer 的官方点播能力：{@code live=false} 时组件会显示进度条，
   * 并支持 {@code setCurrentTime} 定位、{@code getDuration} 取总时长。
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

  /** 本次播放的致命错误（延迟上报用）。 */
  let fatalMsg = ''

  /** 上报致命错误（若已产生）。 */
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
    /** 是否点播（点播时组件显示进度条）。 */
    const isVod = opts._vod === true

    state.value = isWebrtc ? '协商中…' : (isVod ? '加载中…' : '连接中…')
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
        vod: isVod,
        /*
         * 进度条：只有真正可 seek 的点播（云端录像 MP4）才保留。
         *
         * <p>直播与设备回放拿到的都是主动推上来的实时流，
         * {@code player.seekable()} 在播放过程中<b>时有时无</b> ——
         * video.js 的 {@code ProgressControl.update()} 据此 show/hide，
         * 于是控制栏在播放前后宽度分配完全不同，看起来像「布局跳了一下」。
         * 直接从一开始就不显示，界面才稳定。
         *
         * <p>由 {@code play()} 统一注入（它只管实时流）；
         * {@code playFile}（云端录像 MP4）不经过那里，进度条保留。
         */
        hideProgress: opts._hideProgress === true,
        /*
         * 控制栏自定义按钮（例如「播放协议」）。
         *
         * <p>从 {@code opts} 透传而不是写死：不同视图要的按钮不一样，
         * 电视墙就不需要协议切换（那是全局选项）。
         */
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
        fatalMsg = msg
        /*
         * 有 onStall 就交给业务层处理（通常用「停会话 + 重新点播」——
         * 服务端媒体源可能已经丢了视频轨，播放器自己救不回来）；
         * 没有才当致命错误上报。
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
   * 稳定播放计时器：起播后 10 秒仍正常 → 通知业务层「这一路是好的」。
   *
   * <p>业务层用它把「重建流」的预算还回去（见 PlaybackView 的 {@code stallRetry}）。
   * 不还的话，一次观看过程中的几次互不相干的起播故障就会把预算耗光，
   * 之后真出问题也不重建了。
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
   * 绑定媒体事件（位置上报、结束回调）。
   *
   * <p>用 LivePlayer 的官方事件：{@code timeupdate} / {@code ended}。
   *
   * @param {object} opts 本次播放参数
   */
  function bindMediaEvents(opts) {
    clearUnsubs()
    if (!engine) return
    unsubs.push(
      engine.on('timeupdate', () => {
        if (disposed) return
        position.value = engine.currentTime()
      })
    )
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
   * 国标回放控制层：把 LivePlayer 的<b>官方</b>进度条/播放控制接到国标 SIP 指令。
   *
   * <h3>为什么需要这一层</h3>
   *
   * <p>国标回放是「伪点播」—— 平台收到的是设备持续推来的 FLV 流，
   * 流里没有总时长、也没有可 seek 的位置。但整段录像的起止时间是业务侧知道的
   * （来自录像检索应答），而定位/暂停/续推/倍速在国标里都是<b>设备指令</b>
   * （{@code INFO} / {@code PLAY} / {@code PAUSE} / {@code Scale}）。
   *
   * <p>所以：进度条、播放按钮、倍速<b>全部用 LivePlayer 官方控件</b>，
   * <p>这里只做一件事：把官方控件的动作转成国标指令 ——
   * 暂停/继续 → {@code onPause}/{@code onResume}，变速 → {@code onRate}。
   *
   * <p><b>不再监听 {@code seeking}</b>：进度条已被隐藏（实时流没有可 seek 的时间轴，
   * 见 {@code play()} 里 {@code _hideProgress} 的说明），用户无法拖动，
   * 定位入口改为页面自己的时间轴（点击刻度→ SIP INFO）。
   * 保留一个收不到事件的监听只会让人误以为「拖进度条能定位」。
   *
   * @param {object} vod `{ duration, onSeek, onPause, onResume, onRate }`
   */
  function bindVodControls(vod) {
    if (!engine || !vod) return
    /*
     * 拖动定位：监听 video.js 的<b>官方</b> {@code seeking} 事件。
     *
     * <p><b>为什么不能真去 seek</b>：FLV 流里没有可 seek 的位置，
     * 改 {@code media.currentTime} 会落到 buffered 范围之外，
     * video.js 会卡在等待数据（实测「进度条拖动后永远转圈」）。
     * 正确做法是把落点换算成段内秒数，由业务层发国标指令让设备自己跳。
     *
     * <p>video.js 9 已经移除了 {@code player.seek()}，官方路径只剩
     * {@code seeking} 事件 —— 所以监听事件，而不是覆写方法。
     */
    let seekTarget = null
    unsubs.push(
      engine.on('seeking', () => {
        if (disposed) return
        const p = engine.vjsPlayer()
        const t = p && typeof p.currentTime === 'function' ? p.currentTime() : 0
        const target = Math.max(0, Math.min(Number(vod.duration) || 0, Number(t) || 0))
        seekTarget = target
        if (typeof vod.onSeek === 'function') vod.onSeek(target)
      })
    )
    /*
     * seek 失败时 video.js 会把 currentTime 弹回原处；这里主动复位，
     * 让进度条回到设备实际推到的位置（下一段录像开始推流后会自动更新）。
     */
    unsubs.push(
      engine.on('seeked', () => {
        if (disposed || seekTarget === null) return
        seekTarget = null
      })
    )

    /*
     * 暂停 / 继续：官方 {@code pause} / {@code play} 事件 → 国标 PAUSE / PLAY。
     *
     * <p><b>只认用户操作</b>：MSE 欠载恢复、缓冲边界也会触发这两个事件，
     * 把它们当成用户意图就会在正常播放时反复给设备发 PAUSE ——
     * 设备真的停推，媒体源随即被注销，画面永久定格。
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

    /*
     * 倍速：官方 {@code ratechange} 事件 → 国标 Scale（设备侧变速推流）。
     *
     * <p>本地也要同倍速消耗，否则缓冲会一直涨（延迟越拖越大）。
     */
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

  /** 用户是否已经开始看（起播后才有资格上报暂停/继续）。 */
  let userReady = false

  /**
   * 用 LivePlayer 内置的 {@code Webrtc} tech 播放。
   *
   * @param {string} streamId 流标识
   * @returns {Promise<boolean|null>} `true` 成功 / `false` 真失败（该兜底）/
   *          `null` 已被外部停止（不兜底，否则会把用户关掉的画面拉起来）
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
   * xhr.send(btoa(pc.localDescription.sdp));              // base64 的 offer
   * pc.setRemoteDescription({ type:'answer', sdp: atob(xhr.responseText) });
   * </pre>
   * 也就是说它只做「把协议头 {@code webrtc} 换成 {@code http}，然后往同一路径
   * POST base64 的 SDP、读 base64 应答」，<b>不会</b>去碰 ZLM 的
   * {@code /index/api/webrtc}（嵌入式 ZLM 也没注册那个路由）。
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
   * 切换协议（播放中直接重连）。
   *
   * <p><b>必须带上上次 {@code play()} 的选项</b>：切换协议实际是
   * 「停掉再重新 play 一次」，只传 {@code proto} 会把其余设置全部丢掉 ——
   * 例如控制栏自定义按钮就消失了（实测切一次协议「播放协议」按钮不见了），
   * 暂停/继续、卡顿重建等一系列回调也会一起失效。
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

  /**
   * 更新控制栏按钮文字。
   *
   * <p>用于「按钮上显示当前状态」的场景（如播放协议）：切换协议后
   * 引擎自身不知道叫什么名字好，得由业务层把新文案传进去。
   *
   * @param {string} id 按钮 id（{@code play()} 的 {@code controlButtons} 里定义的）
   * @param {string} text 新文字
   */
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
    play,
    playFile,
    stop,
    switchProtocol,
    setControlButtonText,
    exitCssFullscreen
  }
}
