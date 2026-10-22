/**
 * 录像播放器（xgplayer 外壳 + 容错 MSE 内核）。
 *
 * <h3>为什么要外壳</h3>
 * <p>早先录像页直接放一个原生 {@code <video controls>}，与实时预览的
 * xgplayer 是两套外观：控制栏样式、进度条手感、全屏行为全都不一样，
 * 同一个平台看着像两个产品。
 *
 * <h3>为什么外壳里还要自己喂 MSE</h3>
 * <p>ZLM 录出的 H.265 MP4 里夹着残缺帧（UDP 丢包 → PS 解封装失败），
 * 浏览器硬解不容错，原生播放直接 {@code PIPELINE_ERROR_DECODE}。
 * 这层容错逻辑在 {@link MseRecordPlayer} 里（按 GOP 重封装 + 跳过坏段），
 * 不能丢。两者怎么结合见下面的 {@link playMse}。
 *
 * <h3>两条通路</h3>
 * <ul>
 *   <li><b>H.264（或探测不出编码）</b>：把录像地址直接交给 xgplayer，
 *       浏览器原生解码。Range 由容器实现、首帧只下文件头，秒开，
 *       进度条拖动天然支持。</li>
 *   <li><b>H.265</b>：已知原生会失败，直接走容错通路。
 *       先建 MediaSource 并把它的 blob URL 交给 xgplayer 当媒体源 ——
 *       xgplayer 也就有了合法的 url，控制栏/进度条正常工作；
 *       再把同一个 MediaSource 实例交给 {@link MseRecordPlayer} 往里灌片段。</li>
 * </ul>
 *
 * <h3>关键约束</h3>
 * <p>xgplayer 构造时就会拿 {@code url} 去 load，所以 MediaSource 必须
 * <b>先创建、再建播放器</b>；反过来（先建播放器、后设 src）会触发
 * {@code MEDIA_ERR_SRC_NOT_SUPPORTED}，界面上会出现
 * 「不支持的音频/视频格式 请试试刷新」这种误导性提示。
 */

import { ref, shallowRef } from 'vue'
import Player from 'xgplayer'
import 'xgplayer/dist/index.min.css'
import { MseRecordPlayer } from '@/player/MseRecordPlayer'

/** 原生播放判成功的门槛：currentTime 至少推进这么多秒。 */
const NATIVE_OK_ADVANCE = 0.3

/** 原生播放的超时（毫秒）：超时还没出画面就切容错通路。 */
const NATIVE_TIMEOUT_MS = 10000

/**
 * 录像播放器的外观配置。
 *
 * <p>与直播的取舍<b>相反</b>：直播禁止暂停/倍速（那是无意义的操作），
 * 录像全部保留 —— 这正是用户看回放时最常用的东西。
 *
 * @returns {object}
 */
function recordUiConfig() {
  return {
    /*
     * 去掉的入口：
     * - definition：录像没有多码率可选；
     * - testSpeed ：测速是直播排障用的；
     * - download  ：xgplayer 的下载插件拉的是当前 url，
     *               MSE 通路下那是个 blob，存下来不能播；
     *               平台自己有「下载本段」按钮。
     */
    ignores: ['definition', 'testSpeed', 'download'],
    // 录像点画面暂停、双击全屏都是合理行为，保持默认开启
    closeVideoClick: false,
    closeVideoDblclick: false,
    // 等比缩放，不拉伸画面（对应 .xgplayer[data-xgfill=contain] video 的 object-fit）
    videoFillMode: 'contain'
  }
}

/**
 * 把登录 token 拼到地址上。
 *
 * <p>{@code /api/v1/...} 通路带不了自定义请求头，
 * 后端 Sa-Token 配了从 query 读（is-read-body）；
 * 静态 {@code /record/...} 通路不过拦截器，不需要 token。
 *
 * @param {string} url
 * @param {string} [token]
 * @returns {string}
 */
function withToken(url, token) {
  if (!token) return url
  try {
    const u = new URL(url, location.href)
    u.searchParams.set('Authorization', token)
    return u.toString()
  } catch (e) {
    return url
  }
}

/**
 * 录像播放器组合式函数。
 *
 * @param {import('vue').Ref<HTMLElement|null>} containerRef 播放器挂载容器
 * @param {object} [hooks]
 * @param {function} [hooks.onFatal] 彻底失败回调（参数为原因文案）
 * @param {function} [hooks.onSkip]  跳过坏片段回调 (gopIndex, 原因)
 * @param {function} [hooks.onEnded] 播放到结尾回调（用于自动连播下一段）
 */
export function useRecordPlayer(containerRef, hooks = {}) {
  const playing = ref(false)
  const state = ref('空闲')
  const player = shallowRef(null)

  /** 容错内核（MSE 通路才有）。 */
  let core = null
  /** xgplayer 宿主 div（每次播放新建，销毁时整块移除）。 */
  let host = null
  /** 容错通路里 MediaSource 的 blob URL，销毁时必须 revoke。 */
  let objectUrl = ''
  /** 销毁标记：异步流程返回后若已停止，丢弃结果。 */
  let disposed = false

  /** 新建宿主 div（每次播放独立，避免与旧实例的 DOM 回收打架）。 */
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

  /** 拆掉当前播放器与所有资源（内部用，不改状态文案）。 */
  function teardown() {
    if (core) {
      try {
        core.destroy()
      } catch (e) {
        // 实例可能已释放
      }
      core = null
    }
    if (player.value) {
      try {
        player.value.destroy()
      } catch (e) {
        // 实例可能已释放
      }
      player.value = null
    }
    if (objectUrl) {
      URL.revokeObjectURL(objectUrl)
      objectUrl = ''
    }
    if (host) {
      host.remove()
      host = null
    }
    const outer = containerRef.value
    if (outer) outer.innerHTML = ''
  }

  /** 停止并释放资源。 */
  function stop() {
    disposed = true
    teardown()
    playing.value = false
    if (state.value !== '空闲') state.value = '已停止'
  }

  /**
   * 播放一段录像。
   *
   * @param {string} url  录像地址（静态 {@code /record/...} 或受保护接口）
   * @param {object} [opts]
   * @param {string} [opts.token]  需要鉴权时带上登录 token
   * @param {boolean} [opts.useMse] 是否直接走容错通路（H.265 传 true）
   * @returns {Promise<boolean>} 是否成功接管播放
   */
  async function play(url, opts = {}) {
    stop()
    disposed = false
    if (!url) {
      state.value = '缺少播放地址'
      return false
    }
    const src = withToken(url, opts.token)
    state.value = '加载中…'

    if (!opts.useMse) {
      const ok = await playNative(src)
      if (disposed) return false
      if (ok) return true
      state.value = '原生播放失败，改用容错通路…'
    }
    return playMse(src)
  }

  /**
   * 原生通路：地址直接交给 xgplayer。
   *
   * <p><b>判成功的标准是「画面真的动起来」</b>，不是「元数据到了」：
   * {@code loadedmetadata} 只说明文件头/索引读到了（几十毫秒），
   * 坏码流要等解码器真正出帧才暴露。
   *
   * @param {string} url
   * @returns {Promise<boolean>}
   */
  function playNative(url) {
    const el = createHost()
    if (!el) return Promise.resolve(false)

    let p
    try {
      p = new Player({
        el,
        url,
        isLive: false,
        autoplay: true,
        lang: 'zh-cn',
        width: '100%',
        height: '100%',
        ...recordUiConfig()
      })
    } catch (e) {
      state.value = '播放器初始化失败：' + (e.message || e)
      return Promise.resolve(false)
    }
    player.value = p
    const media = p.media

    return new Promise((resolve) => {
      let settled = false
      let baseTime = 0

      const cleanup = () => {
        media.removeEventListener('loadedmetadata', onMeta)
        media.removeEventListener('timeupdate', onTime)
        media.removeEventListener('error', onError)
      }
      const finish = (ok) => {
        if (settled) return
        settled = true
        clearTimeout(timer)
        cleanup()
        if (ok) {
          playing.value = true
          state.value = '播放中'
        }
        resolve(ok)
      }
      const onMeta = () => {
        baseTime = media.currentTime
        media.play().catch(() => { /* 自动播放被拦时由用户点一下 */ })
      }
      // 画面推进了才算真能播
      const onTime = () => {
        if (media.currentTime - baseTime >= NATIVE_OK_ADVANCE) {
          finish(true)
        }
      }
      const onError = () => finish(false)
      const timer = setTimeout(() => finish(false), NATIVE_TIMEOUT_MS)

      media.addEventListener('loadedmetadata', onMeta, { once: true })
      media.addEventListener('timeupdate', onTime)
      media.addEventListener('error', onError)

      // 状态同步走 xgplayer 的事件，避免各处重复挂原生监听
      p.on('playing', () => {
        if (disposed) return
        playing.value = true
        state.value = '播放中'
      })
      p.on('pause', () => {
        if (disposed) return
        playing.value = false
      })
      p.on('ended', () => {
        if (disposed) return
        playing.value = false
        state.value = '播放结束'
        hooks.onEnded?.()
      })
    })
  }

  /**
   * 容错通路：xgplayer 外壳 + 自研 MSE 内核。
   *
   * @param {string} url
   * @returns {Promise<boolean>}
   */
  async function playMse(url) {
    // 原生通路可能留下一个半死状态的播放器，先彻底拆掉
    teardown()
    if (disposed) return false
    if (typeof window === 'undefined' || !window.MediaSource) {
      state.value = '当前浏览器不支持 MSE，无法播放该录像'
      return false
    }
    const el = createHost()
    if (!el) return false

    state.value = '加载中…'
    /*
     * MediaSource 必须先建好：xgplayer 构造时立刻拿 url 去 load，
     * blob URL 是它唯一能接受的"合法媒体源"（见文件头说明）。
     */
    const ms = new MediaSource()
    objectUrl = URL.createObjectURL(ms)

    const p = new Player({
      el,
      url: objectUrl,
      isLive: false,
      autoplay: true,
      lang: 'zh-cn',
      width: '100%',
      height: '100%',
      ...recordUiConfig()
    })
    player.value = p
    const media = p.media

    p.on('playing', () => {
      if (disposed) return
      playing.value = true
      state.value = '播放中'
    })
    p.on('pause', () => {
      if (disposed) return
      playing.value = false
    })
    p.on('ended', () => {
      if (disposed) return
      playing.value = false
      state.value = '播放结束'
      hooks.onEnded?.()
    })

    core = new MseRecordPlayer(media, {
      // 把外壳建好的 MediaSource 交给内核，避免它再改写 video.src
      mediaSource: ms,
      /*
       * 开发模式下把内核挂到 window，便于在控制台查内部状态
       * （_seekTarget / nextIndex / _feeding 等），排查拖动卡死用。
       * 生产构建会被 Vite 静态剔除，不留全局变量。
       */
      debugGlobal: import.meta.env.DEV ? 'mseRecordCore' : '',
      onState: (s) => {
        if (disposed) return
        state.value = s === '容错重建' ? '容错重建…' : s
      },
      onSkip: (idx, reason) => {
        console.warn(`[录像] 跳过异常片段 #${idx}（${reason}）`)
        hooks.onSkip?.(idx, reason)
      },
      onFatal: (msg) => {
        if (disposed) return
        playing.value = false
        state.value = '播放异常'
        hooks.onFatal?.(msg)
      }
    })

    /*
     * 拖动进度条的处理<b>不在这里做</b>。
     *
     * <p>早期是包装 {@code player.seek()} + 监听 {@code user_action}，
     * 实测<b>都拿不到落点</b>：progress 插件点击时并没有走 {@code player.seek()}，
     * 那两个钩子形同虚设，内核只能退化成拿被浏览器钳回的 currentTime 去比对，
     * 于是判定「目标已在缓冲内」→ 什么都不做 → <b>永远转圈</b>。
     *
     * <p>现在由内核直接劫持 {@code media.currentTime} 的 setter 捕获落点
     * （见 {@code MseRecordPlayer#_installCurrentTimeHook}）：所有 seek 路径
     * （点击进度条、拖动、方向键、播放器内部调用）最终都必经它，一个都不漏。
     */

    try {
      await core.start(url, '')
    } catch (e) {
      if (disposed) return false
      playing.value = false
      state.value = '播放失败'
      hooks.onFatal?.(e.message || String(e))
      return false
    }
    if (disposed) return false
    media.play().catch(() => { /* 自动播放被拦时由用户点一下 */ })
    return true
  }

  return {
    playing,
    state,
    player,
    play,
    stop
  }
}
