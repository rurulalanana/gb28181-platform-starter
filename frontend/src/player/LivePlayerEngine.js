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

/** 铺满容器的样式 id（模块级只注入一次）。 */
const FILL_STYLE_ID = 'lp-fill-style'

/**
 * 注入「铺满容器」样式（幂等）。
 *
 * <h3>为什么必须要这段 CSS</h3>
 *
 * <p>LivePlayer 的 {@code aspect} prop 默认是字符串 {@code "16:9"}，组件据此给
 * {@code .video-wrapper} 算 {@code padding-bottom: 56.25%} 来撑高度。
 * 电视墙的每个格子比例各不相同（4 格更方、1 格更宽），按 16:9 算出来的高度
 * <b>必然填不满</b> —— 底部多出一块纯黑，控制条浮在黑块上方，
 * 表现为「播放器没有占满容器」。
 *
 * <p>{@code aspect="100%"} 能让组件走另一条分支（直接给
 * {@code width/height:100%}），但只靠 prop 不够稳：组件在 {@code home}
 * （水印）与 {@code fullscreen} 切换时会重建内层结构，{@code video-js}
 * 自身也带 {@code height:auto} 默认样式，一旦外层比例变化就会再次塌陷。
 * 所以这里用 CSS 把整条链路的高宽全部拉满。样式是模块级共享的（只注入一次），
 * 宿主 div 销毁后规则仍然留在 {@code <head>} 里，供后续实例复用。
 */
function ensureFillStyle() {
  if (typeof document === 'undefined') return
  if (document.getElementById(FILL_STYLE_ID)) return
  const style = document.createElement('style')
  style.id = FILL_STYLE_ID
  style.textContent = `
/*
 * 宿主 div 自身必须严格等于父容器高度。
 *
 * 【为什么必须显式约束】引擎会给宿主设 min-height:180px 兜底
 * （video.js 需要非零尺寸才能建控制栏）。但电视墙的小格子高度可能只有
 * 150px 左右，180px 会撑破父容器：控制栏按 180 定位、bottom:0 落在
 * 父容器之外，被 .video-box 的 overflow:hidden 裁掉大半 ——
 * 表现为「工具栏看得见却点不动」（实际可点区域只剩露出来的那一条）。
 *
 * 用 height:100% + max-height:100% 双保险：前者撑满，后者禁止超出。
 * min-height 交给下面的 @media 只在容器确实太矮时才生效。
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
 * .video-slot 是组件内部留给「自定义内容」的空槽，DOM 里排在
 * .vjs-control-bar 之后。给它 height:100% 会让它撑满整个播放器区域，
 * 从而<b>盖住控制栏</b>——控制栏看得见、点击却全被这个空 div 吃掉。
 *
 * 电视墙不往 slot 里放东西，所以直接让它不参与命中测试。
 * 需要往 slot 塞内容时，改成给里面的实际元素单独设 pointer-events。
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
/*
 * 控制栏贴底并置顶。
 *
 * <p>video.js 默认 bottom:0 相对 .video-js 定位，只要上面各层高度一致就已经正确。
 * 但组件内部的 .video-slot / .video-water / .video-zoom 都排在控制栏之后，
 * 一旦它们有实际尺寸就会盖住控制栏。显式给控制栏 z-index 是双保险，
 * 保证「工具栏看得见就一定点得动」。
 */
.lp-fill .vjs-control-bar {
  margin-bottom: 0;
  z-index: 5;
}
/*
 * video 元素等比显示，不拉伸。
 *
 * <p>组件在 {@code stretch_=true} 时会内联 {@code object-fit:fill}
 * （见 liveplayer-component 里拼接 style 的那段），画面被强行拉满而变形。
 * 我们默认传 {@code stretch:false}，组件就不加 object-fit；
 * 这里显式写 {@code contain} 是把默认行为固定下来 ——
 * 即便将来组件换了默认值，也不会突然变成拉伸。
 *
 * <p>用户点控制栏的「STR」按钮时，组件会自行改成 {@code fill}，
 * 内联样式优先级高于本样式表，所以手动切换仍然有效。
 */
.lp-fill video {
  width: 100%;
  height: 100%;
  object-fit: contain;
}
/*
 * 不可拖动的流：隐藏进度条与时间显示。
 *
 * <h3>为什么直播和设备回放都不该有进度条</h3>
 * 这两条通路拿到的都是<b>主动推上来的实时流</b>，没有可 seek 的时间轴：
 * <ul>
 *   <li>直播 —— 本来就没有「跳到后面」这回事；</li>
 *   <li>设备录像 —— 是「按段重新 INVITE」的伪点播，定位靠 SIP INFO 让设备跳，
 *       不是靠改 {@code currentTime}。时间轴页面本身已提供定位入口。</li>
 * </ul>
 *
 * <h3>不隐藏会怎样（线上实际表现）</h3>
 * video.js 的 {@code ProgressControl.update()} 在
 * {@code player.seekable()} 为空时会调 {@code hide()}：
 * <pre>起播前（seekable 有值）→ 进度条出现
 *开始播放（seekable 变空）→ 进度条 display:none</pre>
 * 于是控制栏在播放前后<b>宽度分配完全不同</b> —— 按钮从分散变成挤在左边，
 * 整条控制栏看起来「跳了一下」，用户会以为页面布局坏了。
 * 时间数字（{@code 10:31 / 29:59}）同理，且它也不是真实进度。
 *
 * <p>隐藏后控制栏只剩 播放/暂停、音量、拉伸、截图、全屏，
 * 与 CMS（LiveGBS）的控制栏一致，且<strong>播放前后完全稳定</strong>。
 *
 * <p>只对显式传 {@code hideProgress} 的实例生效 —— 云端录像的 MP4 文件
 * 是真正可 seek 的点播，进度条必须保留（见 {@code useLivePlayer#playFile}）。
 */
.lp-no-seek .vjs-progress-control,
.lp-no-seek .vjs-current-time,
.lp-no-seek .vjs-duration,
.lp-no-seek .vjs-time-divider,
.lp-no-seek .vjs-remaining-time {
  display: none !important;
}
/*
 * 隐藏进度条后，靠 spacer 把右侧按钮组（拉伸/截图/全屏）推到最右。
 *
 * <p>video.js 默认 {@code .vjs-custom-control-spacer} 是 {@code display:none}，
 * 它平时由进度条的 flex 占据剩余空间来「顺便」把全屏挤到右边。
 * 进度条一没了，剩下的按钮就会全部挤到左侧、右边留一大片空白。
 */
.lp-no-seek .vjs-custom-control-spacer {
  display: block !important;
  flex: 1 1 auto;
}
/*
 * 窄容器（电视墙小格子）：缩小按钮、隐去次要按钮。
 *
 * <p>video.js 的按钮宽度是 {@code 4em}（14px 字号 → 56px）。
 * 电视墙 4/6/9/16 分屏时单个格子可能只有 180~250px 宽，
 * 而控制栏要放 播放+音量+拉伸+截图+全屏 共 5 个 → 需 280px，
 * <b>必然溢出</b>：多出来的按钮会跑到容器外（实测全屏按钮超出右边界 127px），
 * 表现为「控制栏按钮错位、部分点不到」。
 *
 * <p>处理：容器窄于 420px 时按钮缩到 {@code 2.8em}（≈39px），
 * 并隐去拉伸与截图 —— 这两个在小格子里既看不清也不常用，
 * 而播放/音量/全屏是任何尺寸都需要的。5 × 39 = 195px，能放下。
 *
 * <p>用容器查询而不是媒体查询：格子宽度取决于分屏数（1/4/6/9/16），
 * 与窗口宽度无直接关系 —— 1080p 窗口下 16 分屏每格也才 250px。
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
   * @param {boolean} [opts.hideProgress] 隐藏进度条与时间显示（直播/设备回放必传，见 CSS 注释）
   * @param {boolean} [opts.debug] 打开组件自身的调试日志（Webrtc tech 的日志只能这么开）
   * @param {Array<{id:string,title:string,text:string}>} [opts.controlButtons]
   *        要插到控制栏的按钮（见 {@link LivePlayerEngine#_ensureControlButtons}）
   * @param {(id:string, el:HTMLElement)=>void} [opts.onControlButton] 控制栏按钮点击
   * @param {(stage:string, extra?:object)=>void} [opts.onDebug] 诊断回调
   */
  constructor({
    url,
    el,
    muted = true,
    hasAudio = false,
    webrtc = false,
    controls = true,
    hideProgress = false,
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
    this.debug = debug
    /** 要插到控制栏的按钮定义。 */
    this.controlButtons = controlButtons || []
    this.onControlButton = onControlButton || (() => {})
    /** id → 已插入的 button 元素。 */
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
     *
     * <p><b>兜底高度不能超过父容器</b>：电视墙小格可能只有 150px 高，
     * 若这里硬写 min-height:180px，宿主会撑破父容器，
     * 而 .video-box 是 overflow:hidden —— 控制栏被裁掉大半，
     * 表现为「工具栏看得见却点不动」。所以取 min(180, 父容器高度)。
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
      /*
       * 只有当父容器确实够高时才加 min-height。
       * 父容器也为 0（布局尚未完成）时仍给 180，让 video.js 能初始化；
       * 一旦父容器量出真实高度，100% 会自然接管。
       */
      const minH = parentH > 0 ? Math.min(180, parentH) : 180
      if (!this.el.style.minHeight) this.el.style.minHeight = minH + 'px'
    }

    if (!this.el || this.destroyed) return false
    ensureFillStyle()
    /*
     * 在引擎层加 class 而不是让调用方加：{@code useLivePlayer} 与
     * {@code useXgPlayer} 都走这里，样式覆盖必须对两者一致。
     */
    this.el.classList.add('lp-fill')
    /*
     * 实时流（直播 / 设备回放）没有可 seek 的时间轴，进度条既无意义、
     * 又会在起播瞬间被 video.js 自动隐藏导致控制栏宽度跳变。
     * 详见 {@link ensureFillStyle} 里 {@code .lp-no-seek} 的说明。
     */
    if (this.hideProgress) {
      this.el.classList.add('lp-no-seek')
    }

    const vnode = createVNode(Component, {
      videoUrl: this.url,
      raw: false,
      live: !this.vod,
      muted: this.muted,
      /*
       * aspect="100%" 让组件跳过「按 16:9 算 padding-bottom」的分支，
       * 直接给内层 100%×100%（详见 {@link ensureFillStyle}）。
       */
      aspect: '100%',
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
      /*
       * stretch: false —— 保持画面原始宽高比，不拉伸。
       *
       * <p>{@code stretch:true} 会让 video 撑满容器（等效 object-fit:fill），
       * 16:9 的码流放进非 16:9 的格子里会被横向或纵向拉变形，
       * 人像和球机云台的画面尤其明显。默认按标准比例显示（letterbox 留黑边），
       * 需要铺满时由用户点控制栏的「STR」按钮自行切换。
       */
      stretch: false,
      /*
       * 是否显示组件自带的控制栏。
       *
       * <p>国标回放要关掉：它的进度条画不出长度（FLV 里没有 duration），
       * 拖动也是无效 seek（伪点播没有可 seek 的位置）。留着只会让用户拖一根
       * 没有反应的进度条。回放的控制条改由页面提供（见 PlaybackView）。
       */
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
   * 盯住控制栏的 DOM 变动，按钮被重建掉就重插。
   *
   * <p><b>为什么不用定时轮询</b>：控制栏只在「组件重建播放器」时才会换掉，
   * 那是低频事件；而轮询得一直跑（重建可能发生在起播几十秒之后的重连里）。
   * MutationObserver 只在真发生变动时触发，代价接近 0。
   *
   * <p>自身插入也会触发回调，但 {@link LivePlayerEngine#_ensureControlButtons}
   * 里「已经在这个 bar 里就跳过」的判断让它自然幂等，不会死循环。
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
   * <h3>为什么不用组件的 {@code customButtons} prop</h3>
   *
   * <p>liveplayer 确实提供了 {@code customButtons="标题:图标类"}，但它有两个
   * 硬限制：
   * <ul>
   *   <li>按钮内容只能是<b>图标 class</b>（{@code <i class="xxx">}）或纯文本，
   *       要动态改文字得去改内联 HTML，很脏；</li>
   *   <li>点击只能回传 {@code title} 字符串，而 {@code title} 又兼作按钮标识 ——
   *       想让它显示成「播放协议」就没法稳定识别是哪个按钮。</li>
   * </ul>
   * 直接往 {@code .vjs-control-bar} 插一个自己建的按钮更干净：
   * 文字随时能改、点击回调能拿到元素（可以拿它当锚点弹菜单）。
   *
   * <h3>为什么要轮询</h3>
   *
   * <p>控制栏是 video.js 在 {@code ready} 之后才建的，比组件挂载晚；
   * 而组件在某些通路（实测 Webrtc tech）还会<b>整个重建</b>播放器，
   * 那时控制栏也会换一个新的 DOM —— 所以插完还要能被重新插回来
   * （见 {@link LivePlayerEngine#_ensureControlButtons} 的幂等判断）。
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
      /*
       * 已经插过且还在这个 bar 里就跳过。
       *
       * <p>控制栏被重建后旧元素已经不在文档里，{@code bar.contains} 为假，
       * 于是会自动重插 —— 不需要额外的「重建」事件。
       */
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
        /*
         * 阻止冒泡：控制栏容器上挂有 video.js 的「用户活动」监听，
         * 不拦的话点完会把控制栏当作待隐藏状态，某些布局下会直接收起。
         */
        ev.preventDefault()
        ev.stopPropagation()
        try {
          this.onControlButton(def.id, btn)
        } catch (e) {
          // 忽略业务回调自身异常，不影响播放
        }
      })
      /*
       * 插在「全屏」之前：协议切换与拉伸/截图/全屏同属显示类开关，
       * 放一起比放最左（播放/音量区）更合语义。
       */
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

  /**
   * 拿到底层 video.js 实例。
   *
   * <p><b>为什么需要它</b>：录像回放要把 LivePlayer 的<b>官方事件</b>
   * （{@code seeking} / {@code pause} / {@code play} / {@code ratechange} /
   * {@code timeupdate}）转成国标 SIP 指令，必须拿到 Player 实例才能订阅。
   *
   * <p><b>怎么找到它</b>：video.js 的注册表 {@code videojs.players} 的键是
   * <b>Player 的 id</b>（如 {@code vjs_video_2}），而 video 元素自己的 id 是
   * <b>tech 的 id</b>（如 {@code vjs_video_2_webrtc_api}）。
     * 直接 {@code videojs.getPlayer(videoEl.id)} 会命中另一个对象（只有 {@code on}
   * 的 Component），拿不到 Player 的方法 —— 所以这里先用 Player 的根元素
   * （{@code .video-js}）的 id 去取。
   *
   * @returns {object|null} video.js Player 实例
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
   * 订阅播放器事件。
   *
   * <p>优先用 video.js 实例（事件语义与它自己的控制栏一致），
   * 拿不到时退回 video 元素的原生事件。
   *
   * @param {string} event 事件名（'timeupdate' / 'play' / 'pause' / 'ratechange' / 'ended'）
   * @param {Function} handler 回调
   * @returns {Function} 取消订阅的函数
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

  /* ==================== LivePlayer 官方方法转发 ====================
   *
   * 官方方法清单见 https://www.liveqing.com/docs/manuals/LivePlayer.html：
   *   play / pause / paused / getCurrentTime / setCurrentTime / snap
   *   getMuted / setMuted / isFullscreen / requestFullscreen / exitFullscreen
   *   toggleFullscreen / getVolume / setVolume / getDuration
   *
   * <p>这里统一转发到底层 video 元素（video.js 的这些方法最终也是操作它），
   * 业务层不必关心组件内部结构。
   */

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
   * 设置播放进度（官方 {@code setCurrentTime}）。
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

  /**
   * 总时长（官方 {@code getDuration}，秒）。
   *
   * <p>直播 FLV 没有时长，返回 {@code 0}。录像点播（mp4）才有值。
   */
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
