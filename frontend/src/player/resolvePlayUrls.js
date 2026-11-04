/**
 * 从后端返回的流信息构造浏览器可用的播放地址。
 *
 * 后端按设备可达地址（gb28181.media-ip）拼地址，浏览器侧主机名可能不同：
 * 开发模式统一走 vite 代理的同源相对路径；生产模式替换为当前页面主机。
 * HTTPS 页面必须转同源路径，否则 ws/http 会被当混合内容拦掉。
 * RTSP/RTMP 保持原样（只给 VLC/ffplay）。
 *
 * @param {object} streamInfo 后端 play 接口返回的流信息
 * @param {number} [mediaHttpPort] 媒体 HTTP 端口（后端地址缺失时兜底拼 ws-flv）
 * @returns {object} `{ wsFlv, httpFlv, webrtc, hls, rtsp, rtmp }`
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

  /*
   * HTTPS 页面必须转同源路径：ZLM 只提供 http/ws，浏览器会把 ws://、http://
   * 判为混合内容直接阻断（控制台报 Mixed Content），而 HTTPS 与登录都正常，易误判成流服务故障。
   * 去掉协议与端口只留路径，由 nginx 在 443 上反代到 ZLM。
   * HTTP 页面保持直连——那条链路已稳定，不为统一而冒回归风险。
   */
  if (location.protocol === 'https:') {
    out.wsFlv = toSameOriginPath(out.wsFlv, 'ws')
    out.httpFlv = toSameOriginPath(out.httpFlv, 'http')
    out.hls = toSameOriginPath(out.hls, 'http')
  }
  return out
}

/**
 * 把媒体地址改成「同源、仅路径」形式（HTTPS 页面用）。
 *
 * ws://1.2.3.4:48089/rtp/x.live.flv → wss://当前主机/rtp/x.live.flv
 * 已是不带主机名的相对路径时原样返回（幂等）。
 */
function toSameOriginPath(url, scheme) {
  if (!url) return url
  if (url.startsWith('/')) {
    return scheme === 'ws'
      ? `${location.protocol === 'https:' ? 'wss:' : 'ws:'}//${location.host}${url}`
      : url
  }
  try {
    const u = new URL(url)
    const path = u.pathname + u.search
    if (scheme === 'ws') {
      return `wss://${location.host}${path}`
    }
    return path
  } catch (e) {
    return url
  }
}

/**
 * 把媒体地址改写成同源代理路径（仅开发模式）。
 *
 * ws://1.2.3.4:48089/rtp/x.live.flv → ws://localhost:5173/rtp/x.live.flv
 * http://1.2.3.4:48089/rtp/x.live.flv → /rtp/x.live.flv
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

/**
 * 直播默认协议。WebRTC 走 UDP（NACK/FEC、抖动缓冲），丢包只是局部花屏、几百毫秒自愈；
 * FLV 走 TCP，丢包后所有后续数据等重传（队头阻塞），换 flv.js/mpegts.js/jessibuca 也一样。
 */
export const LIVE_DEFAULT_PROTOCOL = 'webrtc'

/**
 * 直播可选协议（统一在这里定义，避免各页面各写一份）。
 *
 * WebRTC：≈0.3s、最流畅；G.711 有声音、H.265 可播（实测 2560×1440 25fps）；
 * AAC 无声（浏览器不支持 AAC-in-WebRTC）。
 * WS-FLV / FLV：无声音（flv.js 不认 G.711 idx 7 与 AAC），抗抖动好、能穿代理。
 * HLS：无声音、延迟约 3 个切片，极端弱网兜底。
 * 标签只写协议名；两个 FLV 用短名（WS-FLV / FLV），否则下拉宽度不齐。
 */
export const LIVE_PROTOCOLS = [
  { value: 'webrtc', label: 'WebRTC' },
  { value: 'wsflv', label: 'WS-FLV' },
  { value: 'httpflv', label: 'FLV' },
  { value: 'hls', label: 'HLS' }
]

/**
 * 该协议在地址集合里是否可用。
 * wsflv 与 httpflv 是同一路流的两种承载，互相兜底，全都没有才算缺地址。
 *
 * @param {string} proto 协议名
 * @param {object} urls resolvePlayUrls 的返回值
 */
export function hasPlayUrl(proto, urls) {
  if (!urls) return false
  const flv = !!(urls.wsFlv || urls.httpFlv)
  if (proto === 'webrtc') return !!urls.webrtc
  if (proto === 'hls') return !!urls.hls
  return flv
}

/* ==================== 浏览器能力检测 ==================== */

/**
 * 本浏览器能否播放 FLV。
 *
 * FLV 不是原生容器，必须靠 flv.js 解封装灌进 MediaSource。iOS 17.1 之前完全
 * 没有 MediaSource；17.1+ 的 ManagedMediaSource 会让 flvjs.isSupported() 返回 true，
 * 但 flv.js 自管 SourceBuffer 与托管缓冲对不上，实测 iPhone 微信内置浏览器
 * 选项可点、点后无画面。故 Apple WebKit 一律判不支持，让用户走 WebRTC / 原生 HLS。
 * 再用 avc1.42E01E 探一次 isTypeSupported：有 MediaSource 但拒绝所有 mime 的内核同样放不了。
 *
 * @returns {boolean}
 */
export function supportsFlv() {
  if (typeof window === 'undefined') return false
  // Apple WebKit 一律不可用：ManagedMediaSource 存在也不等于这路流能放（见函数注释）
  if (isAppleWebkit()) return false
  const MS = window.MediaSource || window.WebKitMediaSource
  if (!MS || typeof MS.isTypeSupported !== 'function') return false
  try {
    // 用 H.264 baseline 探针（ZLM 默认转出）；HEVC 会误判能放 H.264 的设备
    return MS.isTypeSupported('video/mp4; codecs="avc1.42E01E"')
  } catch (e) {
    // 探针报错：保守当不支持，FLV 走不通还有 WebRTC 兜着
    return false
  }
}

/**
 * 本浏览器能否播放 HLS。
 *
 * 只认 Apple WebKit 的原生 HLS：liveplayer 打包的 VHS 缺失，contrib-hls handler
 * 会把 m3u8 抢去 MSE，实测桌面 Chromium readyState 恒 0、报
 * code=4 PipelineStatus::DECODER_ERROR；根因是 ZLM 分片为 H.264 + G.711，
 * Chromium 无 G.711 解码器。Safari 原生能解，所以这条只在 Apple 上通。
 */
export function supportsHls() {
  if (typeof window === 'undefined' || typeof navigator === 'undefined') return false
  // 只要经过 video.js，handler 仍会抢去 MSE（iOS 没有 MSE 必挂），故必须配 nativeHls
  if (!isAppleWebkit()) return false
  try {
    const v = document.createElement('video')
    const r = v.canPlayType('application/vnd.apple.mpegurl')
    return r === 'maybe' || r === 'probably'
  } catch (e) {
    return false
  }
}

/**
 * 是否 Apple WebKit 内核。
 *
 * 按平台判而非 UA 里的浏览器名：iOS 上所有浏览器（Chrome、微信/QQ 内置）都是 WebKit，
 * 同样没有可用 MSE、同样有原生 HLS。iPadOS 13+ 发桌面 UA，用 maxTouchPoints > 1 兜住。
 */
export function isAppleWebkit() {
  if (typeof navigator === 'undefined') return false
  const ua = navigator.userAgent || ''
  if (/iPad|iPhone|iPod/.test(ua)) return true
  if (navigator.platform === 'MacIntel' && (navigator.maxTouchPoints || 0) > 1) return true
  // macOS 上的 Safari：vendor 是 Apple，且不是 Chromium 换皮
  const vendor = navigator.vendor || ''
  return vendor === 'Apple Computer, Inc.' && !/Chrome|Chromium|Edg|OPR|Electron/.test(ua)
}

/** 能否播放 WebRTC：判据与 tech 自己的 isSupported 一致（RTCPeerConnection 存在）。 */
export function supportsWebrtc() {
  return typeof window !== 'undefined' && typeof window.RTCPeerConnection === 'function'
}

/**
 * 手机端判定（决定要不要给「播放协议」入口）。
 *
 * 手机上可选的协议本就剩不下几个：FLV 缺 MSE、HLS 延迟 6s+ 且 G.711 解不了，
 * WebRTC 是唯一低延迟且有声音、支持 H.265 的通路。摆出必失败的选项只会引来
 * 「为什么播不了」。默认 WebRTC，失败就如实报错。
 * 判据：Apple 移动设备 / Android·HarmonyOS·Mobile UA / 视口 < 820px / 粗指针。
 */
export function isMobileClient() {
  if (typeof window === 'undefined' || typeof navigator === 'undefined') return false
  const ua = navigator.userAgent || ''
  // Apple 全系移动设备（横竖屏都算）
  if (/iPad|iPhone|iPod/.test(ua)) return true
  if (navigator.platform === 'MacIntel' && (navigator.maxTouchPoints || 0) > 1) return true
  // Android/鸿蒙手机：UA 里有 Mobile（平板常没有，靠下面的粗指针兜底）
  if (/Android|HarmonyOS|Mobile/i.test(ua)) return true
  try {
    // 兜底：窄视口或粗指针（触屏笔记本少一个按钮无伤大雅，漏判手机代价更大）
    return window.matchMedia('(max-width: 820px)').matches
      || window.matchMedia('(pointer: coarse)').matches
  } catch (e) {
    return false
  }
}

/**
 * 当前浏览器实际能播的协议，顺序沿用 LIVE_PROTOCOLS（WebRTC 优先）。
 * 桌面 Chrome 四项全有；iPhone Safari 只有 WebRTC + HLS。
 */
export function availableProtocols() {
  return LIVE_PROTOCOLS.filter((o) => {
    if (o.value === 'webrtc') return supportsWebrtc()
    if (o.value === 'hls') return supportsHls()
    return supportsFlv()
  })
}

/**
 * 某个协议在当前浏览器是否可用。
 *
 * @param {string} proto 协议名
 */
export function isProtoSupported(proto) {
  if (proto === 'webrtc') return supportsWebrtc()
  if (proto === 'hls') return supportsHls()
  return supportsFlv()
}

/**
 * 从候选协议里挑一个能播的：优先 WebRTC（延迟最低、H.265 也能放），其次按菜单顺序。
 *
 * @param {string} [preferred] 首选协议（能播就用它）
 * @returns {string|null} 一个都没有时返回 null
 */
export function pickSupportedProto(preferred) {
  if (preferred && isProtoSupported(preferred)) return preferred
  const list = availableProtocols()
  if (!list.length) return null
  const webrtc = list.find((o) => o.value === 'webrtc')
  return webrtc ? webrtc.value : list[0].value
}
