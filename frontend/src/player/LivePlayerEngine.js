/**
 * LivePlayer 引擎适配层（全局脚本模式）。
 *
 * <h2>技术栈</h2>
 *
 * `@liveqing/liveplayer-v3` 是 <b>video.js 9 + flvjs + Hls/Webrtc tech</b> 的
 * Vue 封装。按官方示例走全局脚本加载：
 * <ol>
 *   <li>{@code /js/liveplayer-lib.min.js}（video.js + flvjs，index.html 同步加载）；</li>
 *   <li>{@code /js/liveplayer-component.min.js}（Vue 组件）—— 它依赖全局
 *       {@code window.Vue}，由本模块在首次使用前注入。</li>
 * </ol>
 *
 * <p><b>为什么不能 npm import</b>：该包是 UMD + webpack 内联运行时，文件里既有
 * {@code require("vue")} 又有 {@code require("util")}（Node 内置模块），
 * 让 Vite 去优化它会报 {@code TypeError: isFunction is not a function}
 * （发生在 {@code defineComponent} 内），整个应用白屏。
 *
 * <h2>为什么用 createVNode + render</h2>
 *
 * 现有播放器架构是命令式的（自己建宿主 div、自己管生命周期），各视图只调
 * {@code player.play(...)}。用 {@code createVNode + render} 把组件挂到宿主
 * div 上，视图层零改动，电视墙的多实例管理也不受影响。
 *
 * @module LivePlayerEngine
 */

import * as Vue from 'vue'
import { createVNode, render as vueRender } from 'vue'

/** 组件脚本地址（构建后进 jar 的 static/js/）。 */
const COMPONENT_SRC = '/js/liveplayer-component.min.js'

/** 模块级加载 Promise：多实例共享同一次脚本加载。 */
let componentPromise = null

/** videojs 全局是否就绪（由 index.html 同步加载的 lib 提供）。 */
export function isLivePlayerAvailable() {
  return typeof window !== 'undefined' && typeof window.videojs === 'function'
}

/**
 * 从 UMD 导出里取出真正的组件对象。
 *
 * <p><b>必须解包</b>：webpack 打包的 UMD 把入口模块的导出整体赋给
 * {@code window.LivePlayer}，源文件用 {@code export default}，拿到的就是
 * 命名空间对象 {@code { default: Component }}。直接当组件用会报
 * {@code Component is missing template or render function}。
 */
function unwrapComponent(mod) {
  if (!mod) return null
  if (typeof mod === 'function' || mod.render || mod.setup || mod.template) return mod
  return mod.default || null
}

/**
 * 确保 LivePlayer 组件可用（幂等）。
 *
 * <p>顺序关键：<b>先</b>把 Vue 挂到 window，<b>再</b>注入组件脚本 ——
 * 组件的 UMD 包装器在自执行时就会读 {@code window.Vue}。
 *
 * @returns {Promise<any>} Vue 组件对象
 */
export function ensureLivePlayerComponent() {
  if (typeof window === 'undefined') return Promise.reject(new Error('非浏览器环境'))
  const cached = unwrapComponent(window.LivePlayer)
  if (cached) return Promise.resolve(cached)
  if (componentPromise) return componentPromise

  componentPromise = new Promise((resolve, reject) => {
    window.Vue = Vue
    if (!isLivePlayerAvailable()) {
      reject(new Error('缺少 /js/liveplayer-lib.min.js（videojs 未就绪）'))
      return
    }
    const s = document.createElement('script')
    s.src = COMPONENT_SRC
    s.async = true
    s.onload = () => {
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
 * LivePlayer 引擎实例。
 *
 * <p>接口贴近 {@code WsFlvStream}（构造 + start + destroy），
 * 让 {@code useXgPlayer} 能用同一套流程驱动。
 *
 * <h3>音频必须显式关掉</h3>
 *
 * <p>{@code hasaudio} 传 {@code true} 时，flv.js 会把音频一起塞进 MSE，
 * 而 ZLM 转出的 G.711 / AAC 音频描述符会被 Chrome 拒收：
 * <ul>
 *   <li>G.711A 流：{@code CodecUnsupported: Flv: Unsupported audio codec idx: 7}</li>
 *   <li>AAC 流：{@code code=4 audio decoder initialization failed (kUnsupportedConfig)}</li>
 * </ul>
 * 两种情况都<b>整路流播不出来</b>。传 {@code false} 后变纯视频：
 * 同一路流实测 1015ms 起播、缓冲攒到 15.5 秒、211 帧只丢 3 帧。
 *
 * <p>声音交给 {@code WsFlvStream}（Web Audio 解 G.711）或 WebRTC（PCMA 透传）。
 */
export class LivePlayerEngine {
  /**
   * @param {object} opts
   * @param {string} opts.url 播放地址
   * @param {HTMLElement} opts.el 宿主元素
   * @param {boolean} [opts.muted] 静音（默认 true）
   * @param {boolean} [opts.hasAudio] 是否让播放器处理音频（默认 false，见类注释）
   * @param {(msg:string)=>void} [opts.onError] 起播前失败回调
   * @param {()=>void} [opts.onReady] 成功起播回调
   * @param {(info:object)=>void} [opts.onStats] 媒体信息回调
   * @param {boolean} [opts.vod] 是否点播
   * @param {boolean} [opts.webrtc] 是否走组件内置的 Webrtc tech
   * @param {boolean} [opts.debug] 打开组件自身的调试日志（Webrtc tech 的日志只能这么开）
   * @param {(stage:string, extra?:object)=>void} [opts.onDebug] 诊断回调
   */
  constructor({
    url,
    el,
    muted = true,
    hasAudio = false,
    webrtc = false,
    debug = false,
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
    this.debug = debug
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
    /** 起播后遇到的错误（不触发退回）。 */
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
     * 关键：容器必须有实际尺寸。
     *
     * <p>video.js 初始化时会读容器的 clientWidth/clientHeight 来建控制栏与
     * 计算画面比例。若容器是 0×0（电视墙里 grid cell 尚未布局完成、
     * 或父级高度为 auto），它虽然不报错，但<b>不会触发 playing</b> ——
     * 表现为「组件挂上了、video 元素也在、就是不播」，极难定位。
     * 所以这里给一个兜底尺寸。
     */
    if (!this.el.clientWidth || !this.el.clientHeight) {
      this._dbg('container-zero-size', {
        w: this.el.clientWidth,
        h: this.el.clientHeight,
        parentH: this.el.parentElement ? this.el.parentElement.clientHeight : null
      })
      this.el.style.width = this.el.style.width || '100%'
      this.el.style.height = this.el.style.height || '100%'
      this.el.style.minHeight = this.el.style.minHeight || '180px'
    }

    const vnode = createVNode(Component, {
      videoUrl: this.url,
      raw: false,
      live: !this.vod,
      muted: this.muted,
      /*
       * hasaudio 只在 FLV 通路必须显式传 false（见类注释）。
       *
       * WebRTC 通路<b>不能</b>传 false：那时音频是 PCMA/PCMU 原生解码，
       * 浏览器完全支持，关掉就成了无声播放（白白丢功能）。
       */
      hasaudio: this.webrtc || this.hasAudio ? undefined : false,
      autoplay: true,
      debug: this.debug,
      /*
       * WebRTC 通路关闭「错误即时重连」。
       *
       * <p>LivePlayer 的 Webrtc tech 会自己开一条名为 keepalive 的
       * DataChannel 每 3 秒 ping。ZLMediaKit 的 WebRTC 应答不带 SCTP，
       * 这条 channel 必然 close —— tech 随即抛
       * {@code error{name:'send channel closed'}}，组件的
       * {@code rtcError()} 看到 {@code reloadInterval>0} 就整段重建播放器。
       * 实测每次起播后 1~5 秒画面会被掐断重来一次（表现为「刚出画面就转圈」）。
       * 传 0 让组件不因这个假错误重建；真正的断流由 {@code liveInterval}
       * 与上层看门狗兜底。
       */
      reloadInterval: this.webrtc ? 0 : undefined,
      fluent: true,
      stretch: true,
      controls: true,
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
    return true
  }

  /**
   * 绑定底层 video 事件。
   *
   * <p>组件自身的 error 事件粒度不够（某些编码问题它<b>静默不动</b>，
   * 不发任何事件），直接听底层 video 的 error / playing 更可靠。
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
       * 已经播过一段（currentTime > 0.5）就不算「引擎选错了」。
       *
       * <p>直播流偶发 error 很常见（ZLM 侧短暂断流、切 GOP 丢帧），
       * 组件自己会重连。若在这里判成致命，反而把「本来能继续播」的画面打断。
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
   * 周期性巡检：<b>重新定位</b> video 元素并按需重绑。
   *
   * <p><b>为什么必须有</b>：liveplayer 组件在某些通路（实测 Webrtc tech）会在
   * 初始化失败/重连时 <b>整体重建 video 元素</b>。一次性绑定只会挂在那个已被
   * 移除的旧节点上，结果是「画面其实在播、{@code onReady} 永远不来」——
   * 上层看门狗 12 秒后报超时并回退，用户看到的是「明明在播却提示失败」。
   *
   * <p>顺带把「已经播过一段」也当作就绪：有些通路不发 {@code playing}
   * （video.js 接管后事件被吞），只能靠 {@code currentTime} 前进判断。
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
