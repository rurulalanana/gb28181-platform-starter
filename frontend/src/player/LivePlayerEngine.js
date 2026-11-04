/**
 * LivePlayer 引擎适配层：全局脚本加载组件，createVNode + render 命令式挂载。
 *
 * 不能 npm import：UMD 里混有 require("vue") 与 require("util")，
 * Vite 优化后报 TypeError: isFunction is not a function，整个应用白屏。
 * 先挂 window.Vue 再注入组件脚本，UMD 自执行时就要读它。
 *
 * @module LivePlayerEngine
 */

import * as Vue from 'vue'
import { createVNode, render as vueRender } from 'vue'

/** 组件脚本地址（构建后进 jar 的 static/js/）。 */
const COMPONENT_SRC = '/js/liveplayer-component.min.js'

/** 铺满容器的样式 id（模块级只注入一次）。 */
const FILL_STYLE_ID = 'lp-fill-style'

/**
 * 注入「铺满容器」样式（幂等，模块级只注入一次）。
 *
 * aspect 默认按 16:9 算 padding-bottom，电视墙格子比例不同必然留黑边；
 * aspect="100%" 在 home/全屏切换重建结构后仍会被 video-js 的 height:auto 拉塌，
 * 所以用 CSS 把整条链路拉满。
 */
function ensureFillStyle() {
  if (typeof document === 'undefined') return
  if (document.getElementById(FILL_STYLE_ID)) return
  const style = document.createElement('style')
  style.id = FILL_STYLE_ID
  style.textContent = `
/*
 * 宿主必须严格等于父容器高度：引擎的 min-height:180px 兜底在 150px 的电视墙格子里
 * 会撑破父容器，控制栏被 .video-box 的 overflow:hidden 裁成「看得见点不动」。
 * height:100% 撑满、max-height:100% 禁止超出。
 */
.lp-fill {
  height: 100%;
  max-height: 100%;
  min-height: 0;
}
.lp-fill .player-wrapper,
.lp-fill .video-slot,
.lp-fill .video-wrapper {
  width: 100%;
  height: 100%;
  /* 覆盖组件按 aspect 算出的 padding-bottom 占位 */
  padding-bottom: 0 !important;
}
/*
 * .video-slot 排在控制栏之后，height:100% 会盖住控制栏吃掉点击。
 * 电视墙不往 slot 放内容，直接禁止命中；要放内容时给内部元素单独设 pointer-events。
 */
.lp-fill .video-slot {
  pointer-events: none;
}
.lp-fill .video-js,
.lp-fill .video-js.vjs-fluid {
  width: 100% !important;
  height: 100% !important;
  padding-top: 0 !important;
}
/* .video-slot/.video-water/.video-zoom 排在控制栏之后，给控制栏 z-index 保证点得动 */
.lp-fill .vjs-control-bar {
  margin-bottom: 0;
  z-index: 5;
}
/*
 * 等比显示：组件在 stretch_=true 时内联 object-fit:fill 会拉变形，
 * 这里固定 contain；用户点 STR 时组件内联 fill 仍可覆盖（内联优先）。
 */
.lp-fill video {
  width: 100%;
  height: 100%;
  object-fit: contain;
}
/*
 * 不可拖动的流隐藏进度条与时间。
 *
 * 直播与设备回放都是主动推上来的实时流，没有可 seek 的时间轴。video.js 在
 * seekable() 为空时自动 hide，控制栏宽度会在播放前后跳变；时间数字也不是真实进度。
 * 只对显式传 hideProgress 的实例生效，云端录像 MP4 保留进度条。
 */
.lp-no-seek .vjs-progress-control,
.lp-no-seek .vjs-current-time,
.lp-no-seek .vjs-duration,
.lp-no-seek .vjs-time-divider,
.lp-no-seek .vjs-remaining-time {
  display: none !important;
}
/* 进度条隐藏后由 spacer 占剩余空间，否则按钮全挤到左侧 */
.lp-no-seek .vjs-custom-control-spacer {
  display: block !important;
  flex: 1 1 auto;
}
/*
 * 窄容器（电视墙格子）：缩小按钮、隐去拉伸/截图/LIVE。
 *
 * video.js 按钮宽 4em（14px → 56px），5 个按钮需 280px；4/6/9/16 分屏的格子
 * 只有 180~250px，实测全屏按钮超出右边界 127px。容器窄于 420px 时按钮缩到
 * 2.8em（≈39px，5×39=195px 能放下）。用容器查询而非媒体查询：格子宽度取决于分屏数，
 * 与窗口宽度无关（1080p 下 16 分屏每格也才 250px）。
 */
.lp-fill {
  container-type: inline-size;
}

@container (max-width: 420px) {
  .lp-fill .vjs-control-bar .vjs-control {
    width: 2.8em;
  }
  .lp-fill .vjs-control-bar .vjs-snapshot-control,
  .lp-fill .vjs-control-bar .live-player-stretch-btn {
    display: none !important;
  }
  /* 窄容器放不下 "LIVE"/"Stream Type" 那串文字 */
  .lp-fill .vjs-control-bar .vjs-live-control {
    display: none !important;
  }
}
`
  document.head.appendChild(style)
}

/** 模块级加载 Promise：多实例共享同一次脚本加载。 */
let componentPromise = null

/** videojs 全局是否就绪（由 index.html 同步加载的 lib 提供）。 */
export function isLivePlayerAvailable() {
  return typeof window !== 'undefined' && typeof window.videojs === 'function'
}

/**
 * 从 UMD 导出里取组件：webpack 把入口导出赋给 window.LivePlayer，
 * 源文件是 export default，拿到的是 { default: Component }。
 * 直接当组件用会报 Component is missing template or render function。
 */
function unwrapComponent(mod) {
  if (!mod) return null
  if (typeof mod === 'function' || mod.render || mod.setup || mod.template) return mod
  return mod.default || null
}

/**
 * 给 flv 源处理器补 canPlayType。
 *
 * videojs-flvjs 只注册了 canHandleSource/handleSource，video.js 9 逐个调
 * canPlayType 时会抛 r[n].canPlayType is not a function，<source type="video/x-flv">
 * 被判成不支持的源，画面永远不出。已有 canPlayType 的处理器不动（幂等）。
 */
function patchFlvSourceHandler() {
  try {
    const vjs = window.videojs
    if (!vjs || typeof vjs.getTech !== 'function') return
    const tech = vjs.getTech('Html5')
    const list = (tech && tech.sourceHandlers) || []
    for (let i = 0; i < list.length; i++) {
      const h = list[i]
      // 原生处理器本来就有 canPlayType，这里只补缺的那一个（flvjs）
      if (!h || typeof h.canPlayType === 'function') continue
      h.canPlayType = (src) => {
        const type = typeof src === 'string' ? src : (src && src.type) || ''
        return /flv/i.test(type) ? 'probably' : ''
      }
    }
  } catch (e) {
    // 兼容层失败不该拖垮起播流程
  }
}

/**
 * 确保 LivePlayer 组件可用（幂等）。
 *
 * 顺序关键：先把 Vue 挂到 window，再注入组件脚本 —— UMD 自执行时就读 window.Vue。
 *
 * @returns {Promise<any>} Vue 组件对象
 */
export function ensureLivePlayerComponent() {
  if (typeof window === 'undefined') return Promise.reject(new Error('非浏览器环境'))
  const cached = unwrapComponent(window.LivePlayer)
  if (cached) {
    patchFlvSourceHandler()
    return Promise.resolve(cached)
  }
  if (componentPromise) return componentPromise

  componentPromise = new Promise((resolve, reject) => {
    window.Vue = Vue
    if (!isLivePlayerAvailable()) {
      reject(new Error('缺少 /js/liveplayer-lib.min.js（videojs 未就绪）'))
      return
    }
    patchFlvSourceHandler()
    const s = document.createElement('script')
    s.src = COMPONENT_SRC
    s.async = true
    s.onload = () => {
      patchFlvSourceHandler()
      const comp = unwrapComponent(window.LivePlayer)
      if (comp) resolve(comp)
      else reject(new Error('组件脚本加载后未暴露可用的 LivePlayer 组件'))
    }
    s.onerror = () => reject(new Error(`加载 ${COMPONENT_SRC} 失败`))
    document.head.appendChild(s)
  }).catch((e) => {
    componentPromise = null
    throw e
  })

  return componentPromise
}

/**
 * LivePlayer 引擎实例。接口贴近 WsFlvStream（构造 + start + destroy）。
 *
 * FLV 通路 hasaudio 必须显式 false：flv.js 把 G.711/AAC 塞进 MSE 会被 Chrome 拒收
 * （G.711A: Unsupported audio codec idx: 7；AAC: code=4 ... kUnsupportedConfig），
 * 整路流播不出来。关掉后实测 1015ms 起播、211 帧丢 3 帧。
 * 声音交给 WebRTC（PCMA 透传）。
 */
export class LivePlayerEngine {
  /**
   * @param {object} opts
   * @param {string} opts.url 播放地址
   * @param {HTMLElement} opts.el 宿主元素
   * @param {boolean} [opts.hasAudio] 让播放器处理音频；FLV 通路必须保持默认 false
   * @param {boolean} [opts.hideProgress] 隐藏进度条（直播/设备回放必传）
   * @param {boolean} [opts.nativeHls] 直接用 <video src=m3u8>，不经 video.js
   * @param {Function} [opts.onError] 起播前失败回调
   * @param {Function} [opts.onReady] 成功起播回调
   * @param {Function} [opts.onStats] 媒体信息回调
   * @param {Function} [opts.onDebug] 诊断回调
   * @param {Array<{id,title,text}>} [opts.controlButtons] 控制栏按钮
   * @param {Function} [opts.onControlButton] 控制栏按钮点击
   */
  constructor({
    url,
    el,
    muted = true,
    hasAudio = false,
    webrtc = false,
    controls = true,
    hideProgress = false,
    nativeHls = false,
    debug = false,
    controlButtons = null,
    onControlButton,
    onError,
    onReady,
    onStats,
    vod = false,
    onDebug
  }) {
    this.url = url
    this.el = el
    this.muted = muted
    this.hasAudio = hasAudio
    this.webrtc = webrtc
    this.controls = controls
    this.hideProgress = hideProgress
    this.nativeHls = nativeHls
    this.debug = debug
    // 要插到控制栏的按钮定义
    this.controlButtons = controlButtons || []
    this.onControlButton = onControlButton || (() => {})
    // id → 已插入的 button 元素
    this._controlEls = {}
    this.onError = onError || (() => {})
    this.onReady = onReady || (() => {})
    this.onStats = onStats || (() => {})
    this.onDebug = onDebug || (() => {})
    this.vod = vod

    this.destroyed = false
    this.failed = false
    this.vnode = null
    this.videoEl = null
    this._readyFired = false
    /** 提示性信息（不致命）。 */
    this.lastNotice = null
    // 起播后遇到的错误（不触发退回）
    this.lastError = null
  }

  _dbg(stage, extra) {
    try {
      this.onDebug(stage, extra)
    } catch (e) {
      // 忽略诊断回调自身异常
    }
  }

  /**
   * 开始播放。
   *
   * @returns {Promise<boolean>} true = 组件已挂载（起播与否由 onReady 通知）
   */
  async start() {
    if (this.destroyed) return false
    // 原生 HLS 独立分支：不加载 LivePlayer 组件，也不用 video.js
    if (this.nativeHls) return this._startNativeHls()
    let Component
    try {
      Component = await ensureLivePlayerComponent()
      this._dbg('component-ready', { type: typeof Component })
    } catch (e) {
      this._fail(String(e && e.message))
      return false
    }
    if (this.destroyed) return false
    if (!this.el) {
      this._fail('播放器容器未就绪')
      return false
    }

    /*
     * 容器必须有非零尺寸：0×0 时 video.js 不报错，但不会触发 playing。
     * 兜底高度取 min(180, 父容器高)——硬写 180px 会撑破 150px 的电视墙格子，
     * .video-box 的 overflow:hidden 会把控制栏裁到「看得见点不动」。
     */
    if (!this.el.clientWidth || !this.el.clientHeight) {
      const parentH = this.el.parentElement ? this.el.parentElement.clientHeight : 0
      this._dbg('container-zero-size', {
        w: this.el.clientWidth,
        h: this.el.clientHeight,
        parentH
      })
      this.el.style.width = this.el.style.width || '100%'
      this.el.style.height = this.el.style.height || '100%'
      // 父容器也为 0（布局未完成）时给 180，让 video.js 能初始化
      const minH = parentH > 0 ? Math.min(180, parentH) : 180
      if (!this.el.style.minHeight) this.el.style.minHeight = minH + 'px'
    }

    if (!this.el || this.destroyed) return false
    ensureFillStyle()
    // 在引擎层加 class：useLivePlayer 与旧调用方都走这里，样式必须一致
    this.el.classList.add('lp-fill')
    // 实时流没有可 seek 的时间轴，进度条只会让控制栏宽度跳变
    if (this.hideProgress) {
      this.el.classList.add('lp-no-seek')
    }

    const vnode = createVNode(Component, {
      videoUrl: this.url,
      raw: false,
      live: !this.vod,
      muted: this.muted,
      // 跳过按 16:9 算 padding-bottom 的分支，直接给内层 100%×100%
      aspect: '100%',
      /*
       * FLV 通路必须显式 false（原因见类注释）；
       * WebRTC 不能传 false：PCMA/PCMU 由浏览器原生解码，关掉就变成无声播放。
       */
      hasaudio: this.webrtc || this.hasAudio ? undefined : false,
      autoplay: true,
      debug: this.debug,
      /*
       * WebRTC 通路传 0：keepalive DataChannel 每 3 秒 ping，而 ZLM 应答不带 SCTP，
       * channel 必然 close，tech 抛 send channel closed 后整段重建播放器
       * （实测起播后 1~5 秒画面被掐断重来）。真断流由 liveInterval 与上层看门狗兜底。
       */
      reloadInterval: this.webrtc ? 0 : undefined,
      fluent: true,
      // 保持原始宽高比；stretch:true 等效 object-fit:fill，会拉变形
      stretch: false,
      // 国标回放关掉组件控制栏：FLV 没有 duration，进度条拖不动，控制条由页面提供
      controls: this.controls,
      onError: (e) => this._onErrorEvent(e),
      onMessage: (m) => {
        this.lastNotice = typeof m === 'string' ? m : JSON.stringify(m).slice(0, 120)
        this._dbg('component-message', { m: String(this.lastNotice).slice(0, 100) })
      }
    })
    vnode.appContext = null
    this.vnode = vnode
    try {
      vueRender(vnode, this.el)
      this._dbg('rendered', { htmlLen: this.el.innerHTML.length })
    } catch (e) {
      this._fail('渲染 LivePlayer 失败：' + (e && e.message))
      return false
    }
    this._bindVideoEvents(0)
    this._startReadyWatchdog()
    if (this.controlButtons.length) {
      this._ensureControlButtons(0)
      this._startControlBarObserver()
    }
    return true
  }

  /**
   * 盯住控制栏 DOM，按钮被重建掉就重插。
   *
   * 不用定时轮询：控制栏只在重建播放器时换掉，重建又可能发生在起播几十秒之后；
   * MutationObserver 只在真变动时触发。自身插入也会触发回调，
   * 但 _ensureControlButtons 的「已经在 bar 里就跳过」让它自然幂等。
   */
  _startControlBarObserver() {
    if (typeof MutationObserver === 'undefined' || this._ctrlObserver || !this.el) return
    this._ctrlObserver = new MutationObserver(() => {
      if (this.destroyed) return
      this._ensureControlButtons(0)
    })
    this._ctrlObserver.observe(this.el, { childList: true, subtree: true })
  }

  /**
   * 把自定义按钮插进 video.js 控制栏。
   *
   * 不用组件的 customButtons prop：按钮内容只能是图标 class 或纯文本，
   * 点击只回传 title，而 title 又兼作标识，没法显示「播放协议」这类动态文字。
   * 控制栏在 ready 之后才建、Webrtc tech 还会整个重建播放器，故由调用方补插。
   *
   * @param {number} tries 已重试次数
   */
  _ensureControlButtons(tries) {
    if (this.destroyed || !this.controlButtons.length) return
    const bar = this.el && this.el.querySelector('.vjs-control-bar')
    if (!bar) {
      if (tries < 40) setTimeout(() => this._ensureControlButtons(tries + 1), 250)
      return
    }
    for (const def of this.controlButtons) {
      // 已在这个 bar 里就跳过；控制栏重建后 bar.contains 为假，会自动重插
      const existing = this._controlEls[def.id]
      if (existing && bar.contains(existing)) continue

      const btn = document.createElement('button')
      btn.type = 'button'
      btn.className = 'vjs-button lp-ctrl-btn'
      btn.title = def.title || def.id
      btn.setAttribute('aria-label', def.title || def.id)
      btn.dataset.lpBtn = def.id
      const span = document.createElement('span')
      span.className = 'lp-ctrl-text'
      span.textContent = def.text || ''
      btn.appendChild(span)
      btn.addEventListener('click', (ev) => {
        // 阻止冒泡：控制栏容器挂有 video.js 的用户活动监听，不拦会直接收起控制栏
        ev.preventDefault()
        ev.stopPropagation()
        try {
          this.onControlButton(def.id, btn)
        } catch (e) {
          // 忽略业务回调自身异常，不影响播放
        }
      })
      // 插在全屏之前：协议切换与拉伸/截图/全屏同属显示类开关
      const anchor = bar.querySelector('.vjs-fullscreen-control')
      if (anchor) bar.insertBefore(btn, anchor)
      else bar.appendChild(btn)
      this._controlEls[def.id] = btn
      this._dbg('control-button-added', { id: def.id, text: def.text })
    }
  }

  /**
   * 更新控制栏按钮文字（协议切换后调用）。
   *
   * @param {string} id 按钮 id
   * @param {string} text 新文字
   */
  setControlButtonText(id, text) {
    const btn = this._controlEls[id]
    if (!btn) return
    const span = btn.querySelector('.lp-ctrl-text')
    if (span) span.textContent = text
    else btn.textContent = text
  }

  /** 取控制栏按钮元素（当作弹层锚点用）。 */
  controlButtonEl(id) {
    return this._controlEls[id] || null
  }

  /**
   * 原生 HLS：用 <video src=m3u8> 直接播，完全绕开 video.js。
   *
   * video.js 会把 application/x-mpegURL 交给残缺的 contrib-hls handler，
   * 改 src 为 blob: 走 MSE —— iOS 没有 MediaSource，能播的地址也被判死。
   * 实测桌面 Chromium 同样 tech=Html5、src=blob:、readyState=0、
   * code=4 MEDIA_ERR_SRC_NOT_SUPPORTED（curl 拉 m3u8/ts 均 200）。
   * 代价是没有 video.js 控制栏，用系统原生控件；HLS 只是兜底通路。
   *
   * @returns {boolean} 是否已挂载（起播与否由 onReady 通知）
   */
  _startNativeHls() {
    if (!this.el) {
      this._fail('播放器容器未就绪')
      return false
    }
    try {
      const v = document.createElement('video')
      // playsInline 三个属性都要写：iOS 少了它会跳进系统全屏，页面容器变空
      v.playsInline = true
      v.setAttribute('playsinline', '')
      v.setAttribute('webkit-playsinline', '')
      v.muted = this.muted
      v.autoplay = true
      v.controls = this.controls
      v.preload = 'auto'
      v.style.cssText =
        'width:100%;height:100%;object-fit:contain;background:#000;display:block'
      v.src = this.url
      this.el.appendChild(v)
      this.videoEl = v
      this._dbg('native-hls-mounted', { url: String(this.url).slice(0, 80) })

      v.addEventListener('loadedmetadata', () => {
        this._dbg('native-hls-metadata', { w: v.videoWidth, h: v.videoHeight })
        this.onStats({
          codec: '',
          width: v.videoWidth,
          height: v.videoHeight,
          hasAudio: !this.muted
        })
      })
      v.addEventListener('playing', () => this._fireReady())
      v.addEventListener('timeupdate', () => {
        if (v.currentTime > 0.2) this._fireReady()
      })
      v.addEventListener('error', () => {
        const err = v.error
        this._onErrorEvent({
          code: err && err.code,
          message: (err && err.message) || 'HLS 播放失败'
        })
      })
      const p = v.play()
      if (p && typeof p.catch === 'function') p.catch(() => {})
      return true
    } catch (e) {
      this._fail('原生 HLS 挂载失败：' + (e && e.message))
      return false
    }
  }

  /**
   * 绑定底层 video 事件：组件的 error 粒度不够，某些编码问题它静默不动、
   * 不发任何事件，直接听 video 的 error / playing 更可靠。
   *
   * @param {number} tries 已重试次数（组件挂载是异步的两段式）
   */
  _bindVideoEvents(tries) {
    if (this.destroyed || this.failed) return
    const v = this.el && this.el.querySelector('video')
    if (!v) {
      if (tries < 40) {
        setTimeout(() => this._bindVideoEvents(tries + 1), 250)
      } else {
        this._dbg('no-video-el', { htmlLen: this.el ? this.el.innerHTML.length : -1 })
      }
      return
    }
    if (v.__lpBound) return
    v.__lpBound = true
    this.videoEl = v
    this._dbg('video-el-found', { tag: v.tagName, cls: v.className })

    v.addEventListener('error', () => {
      const err = v.error
      /*
       * currentTime > 0.5 的错误不算致命：直播偶发断流、切 GOP 丢帧很常见，
       * 组件自己会重连，判死反而打断本可继续的播放。
       */
      if (v.currentTime > 0.5) {
        this.lastError = { code: err && err.code, msg: '起播后错误（已忽略，交给组件重连）' }
        this._fireReady()
        return
      }
      this._onErrorEvent({
        code: err && err.code,
        message: (err && err.message) || 'video error'
      })
    })
    v.addEventListener('playing', () => {
      this._dbg('video-playing', { ct: +v.currentTime.toFixed(2) })
      this._fireReady()
    })
    v.addEventListener('timeupdate', () => {
      // 任何一个时间点播过，就说明这路流它能放通
      if (v.currentTime > 0.2) this._fireReady()
    })
    v.addEventListener('loadedmetadata', () => {
      this._dbg('video-metadata', { w: v.videoWidth, h: v.videoHeight })
      this.onStats({
        codec: '',
        width: v.videoWidth,
        height: v.videoHeight,
        hasAudio: this.hasAudio
      })
    })
    // 主动拉一次播放：容器尺寸兜底后浏览器可能错过了最初的 autoplay 时机
    if (v.paused) {
      const p = v.play()
      if (p && typeof p.catch === 'function') p.catch(() => {})
    }
  }

  /**
   * 周期巡检：重新定位 video 元素并按需重绑。
   *
   * Webrtc tech 在初始化失败/重连时会整体重建 video，一次性绑定会挂在已移除的
   * 旧节点上，表现为「画面在播、onReady 永远不来」，上层看门狗 12 秒后误报失败。
   * 顺带用 currentTime > 0.2 兜住不发 playing 的通路。
   */
  _startReadyWatchdog() {
    if (this._readyTimer || this.destroyed) return
    this._readyTimer = setInterval(() => {
      if (this.destroyed) {
        this._stopReadyWatchdog()
        return
      }
      if (this._readyFired) {
        this._stopReadyWatchdog()
        return
      }
      const v = this.el && this.el.querySelector('video')
      if (!v) return
      // 节点被换掉了就重绑
      if (v !== this.videoEl) {
        v.__lpBound = false
        this._bindVideoEvents(0)
      }
      if (v.currentTime > 0.2) this._fireReady()
    }, 500)
  }

  /** 停止就绪巡检。 */
  _stopReadyWatchdog() {
    if (this._readyTimer) {
      clearInterval(this._readyTimer)
      this._readyTimer = null
    }
  }

  /** 统一错误处理。 */
  _onErrorEvent(e) {
    if (this.destroyed || this.failed) return
    const code = e && (e.code !== undefined ? e.code : e.detail && e.detail.code)
    const msg =
      (e && (e.message || e.msg)) ||
      (e && e.detail && (e.detail.message || e.detail.msg)) ||
      ''
    /*
     * 只把「带具体信息的错误」当作致命错误。
     *
     * <p>组件的 error 事件有时只传一个裸 Event（无 code / message），
     * 实测在正常起播过程中也会发一次；据此判定失败会白白多付一次起播延迟。
     * 真正的判据由底层 video 的 error 与上层超时看门狗负责。
     */
    if (!code && !msg) {
      this.lastNotice = this.lastNotice || '无信息的 error 事件（已忽略）'
      this._dbg('component-error-bare', {})
      return
    }
    this._dbg('component-error', { code, msg: String(msg).slice(0, 100) })
    if (!this._readyFired) {
      this._fail(`LivePlayer 无法播放该流（code=${code || '?'}）：${String(msg).slice(0, 120)}`)
    } else {
      this.lastError = { code, msg: String(msg).slice(0, 160) }
    }
  }

  _fireReady() {
    if (this._readyFired || this.destroyed) return
    this._readyFired = true
    this.onReady()
  }

  _fail(msg) {
    if (this.failed || this.destroyed) return
    this.failed = true
    this.lastError = { message: msg }
    this._dbg('fail', { msg })
    this.onError(msg)
  }

  /** 当前播放位置（秒）。 */
  currentTime() {
    return this.videoEl ? this.videoEl.currentTime : 0
  }

  /**
   * 取 video.js Player 实例（回放要把 seeking/pause/play 转成 SIP 指令）。
   *
   * videojs.players 的键是 Player id，video 元素 id 是 tech id，
   * 直接用 videoEl.id 会拿到只有 on 的 Component，故走 .video-js 根元素 id。
   *
   * @returns {object|null}
   */
  vjsPlayer() {
    if (!this.videoEl) return null
    const vjs = typeof window !== 'undefined' ? window.videojs : null
    if (!vjs) return null
    // Player 的根元素包着 tech 元素，从它拿 id 最准
    const root = this.videoEl.closest ? this.videoEl.closest('.video-js') : null
    if (root && root.id) {
      try {
        const p = typeof vjs.getPlayer === 'function' ? vjs.getPlayer(root.id) : null
        if (p && typeof p.on === 'function') return p
      } catch (e) {
        // 忽略：换下一条路径
      }
    }
    // 兜底：遍历注册表，找 tech 元素就是当前 video 的那个
    try {
      const reg = vjs.players || {}
      for (const k of Object.keys(reg)) {
        const p = reg[k]
        const el = p && p.tech_ && typeof p.tech_.el === 'function' ? p.tech_.el() : null
        if (el === this.videoEl) return p
      }
    } catch (e) {
      // 忽略
    }
    return null
  }

  /**
   * 订阅播放器事件：优先用 video.js 实例，拿不到时退回 video 元素原生事件。
   *
   * @param {string} event 事件名
   * @param {Function} handler 回调
   * @returns {Function} 取消订阅
   */
  on(event, handler) {
    const p = this.vjsPlayer()
    if (p && typeof p.on === 'function') {
      p.on(event, handler)
      return () => {
        try {
          p.off(event, handler)
        } catch (e) {
          // 忽略
        }
      }
    }
    if (!this.videoEl) return () => {}
    this.videoEl.addEventListener(event, handler)
    return () => this.videoEl.removeEventListener(event, handler)
  }

  /* LivePlayer 官方方法（play/pause/seek/snap/volume/全屏）统一转到底层 video 元素。 */

  /** 播放。 */
  play() {
    const p = this.vjsPlayer()
    if (p && typeof p.play === 'function') {
      try {
        const r = p.play()
        if (r && typeof r.catch === 'function') r.catch(() => {})
      } catch (e) {
        // 忽略
      }
      return
    }
    if (this.videoEl) {
      const r = this.videoEl.play()
      if (r && typeof r.catch === 'function') r.catch(() => {})
    }
  }

  /** 暂停。 */
  pause() {
    const p = this.vjsPlayer()
    if (p && typeof p.pause === 'function') {
      try {
        p.pause()
      } catch (e) {
        // 忽略
      }
      return
    }
    if (this.videoEl) this.videoEl.pause()
  }

  /** 是否处于暂停态。 */
  paused() {
    return this.videoEl ? this.videoEl.paused : true
  }

  /**
   * 设置播放进度。
   *
   * @param {number} sec 目标秒数
   */
  setCurrentTime(sec) {
    const p = this.vjsPlayer()
    if (p && typeof p.currentTime === 'function') {
      try {
        p.currentTime(sec)
        return
      } catch (e) {
        // 退回 video 元素
      }
    }
    if (this.videoEl) {
      try {
        this.videoEl.currentTime = sec
      } catch (e) {
        // 忽略
      }
    }
  }

  /** 总时长（秒）；直播 FLV 没有时长，返回 0。 */
  getDuration() {
    const p = this.vjsPlayer()
    if (p && typeof p.duration === 'function') {
      try {
        const d = p.duration()
        if (Number.isFinite(d) && d > 0) return d
      } catch (e) {
        // 忽略
      }
    }
    const d = this.videoEl ? this.videoEl.duration : NaN
    return Number.isFinite(d) && d > 0 ? d : 0
  }

  /** 是否静音（供外部同步音量按钮）。 */
  isMuted() {
    return this.videoEl ? this.videoEl.muted : true
  }

  /** 设置静音。 */
  setMuted(m) {
    if (this.videoEl) this.videoEl.muted = !!m
  }

  /** 销毁。 */
  destroy() {
    this.destroyed = true
    this._stopReadyWatchdog()
    if (this._ctrlObserver) {
      try {
        this._ctrlObserver.disconnect()
      } catch (e) {
        // 忽略
      }
      this._ctrlObserver = null
    }
    this._controlEls = {}
    if (this.vnode) {
      try {
        // render(null, el) 触发 unmounted，组件内部会 destroy videojs 实例
        vueRender(null, this.el)
      } catch (e) {
        // 忽略
      }
      this.vnode = null
    }
    try {
      // 兜底：清掉可能残留的 video.js 节点
      if (this.el) this.el.innerHTML = ''
    } catch (e) {
      // 忽略
    }
    this.videoEl = null
  }
}
