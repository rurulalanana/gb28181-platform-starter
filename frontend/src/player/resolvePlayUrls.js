/**
 * 从后端返回的流信息构造「浏览器可用」的播放地址。
 *
 * <p>后端返回的地址是按<b>设备可达地址</b>（{@code gb28181.media-ip}）拼的，
 * 那是给设备 / VLC 用的；浏览器访问时主机名可能不同
 * （平台部署在 160.202.254.65，用户却从内网 192.168.x.x 访问；
 * 或本机开发时用 localhost）。
 *
 * <p>两种处理方式：
 * <ol>
 *   <li><b>开发模式（vite dev）</b>：走同源相对路径，由 vite 的 {@code /rtp}
 *       代理转发到媒体服务。这样无论后端在本地还是远程测试服务器，
 *       浏览器都只访问 {@code localhost:5173}，不存在跨域与端口不可达问题。</li>
 *   <li><b>生产模式（jar 内置前端）</b>：前端与媒体服务同机部署，
 *       把主机名替换为当前页面主机、保留端口即可。</li>
 * </ol>
 *
 * <p>RTSP / RTMP 保持原样（给 VLC / ffplay 用，浏览器播不了）。
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
   * HTTPS 页面必须把媒体地址改成同源路径。
   *
   * <p><b>不这么做的后果</b>：媒体服务（ZLM）只监听 http/ws，不提供 TLS。
   * 页面是 https 时，浏览器会把 ws:// 与 http:// 的资源判定为**混合内容**
   * 并直接阻止 —— 表现为「实时预览一直转圈、控制台报
   * Mixed Content: The page at 'https://…' was loaded over HTTPS,
   * but attempted to connect to the insecure WebSocket endpoint」。
   * 而 HTTPS 本身是通的、登录也正常，很容易误判成流服务出问题。
   *
   * <p>做法：去掉协议与端口、只留路径（如 {@code /rtp/xxx.live.flv}），
   * 由 nginx 在 HTTPS 端口上把这些路径反代到 ZLM（见生成的 nginx 配置）。
   * 浏览器眼中它就变成同源请求，既没有混合内容问题，也不需要为媒体服务
   * 单独申请证书或开放额外端口。
   *
   * <p>HTTP 页面保持原样（直连媒体端口）：那条链路已经稳定运行，
   * 没有理由为了「统一」去动它、冒回归风险。
   */
  if (location.protocol === 'https:') {
    out.wsFlv = toSameOriginPath(out.wsFlv, 'ws')
    out.httpFlv = toSameOriginPath(out.httpFlv, 'http')
    out.hls = toSameOriginPath(out.hls, 'http')
  }
  return out
}

/**
 * 把媒体地址改成「同源、仅路径」形式，供 HTTPS 页面使用。
 *
 * <p>`ws://1.2.3.4:48089/rtp/x.live.flv` → `wss://当前主机/rtp/x.live.flv`
 * <br>`http://1.2.3.4:48089/rtp/x.live.flv` → `/rtp/x.live.flv`
 *
 * <p>已经是不带主机名的相对路径时原样返回 —— 幂等，重复调用不会出错。
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
 * 把媒体地址改写成同源代理路径（仅开发模式用）。
 *
 * <p>`ws://1.2.3.4:48089/rtp/x.live.flv` → `ws://localhost:5173/rtp/x.live.flv`
 * <br>`http://1.2.3.4:48089/rtp/x.live.flv` → `/rtp/x.live.flv`
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
 * 直播默认播放协议。
 *
 * <p><b>为什么是 WebRTC 而不是 FLV</b>：两者跑在不同传输层上，
 * 性能差异是协议决定的，不是代码质量能弥补的：
 * <ul>
 *   <li><b>WebRTC 走 UDP</b>，带 NACK / FEC / 抖动缓冲。丢包只是局部花屏，
 *       几百毫秒内自愈，不阻塞后续数据。</li>
 *   <li><b>FLV 走 TCP</b>（无论是 ws-flv 还是 http-flv）。
 *       TCP 是可靠传输 —— 一旦丢包，<b>所有后续数据都会等重传</b>
 *       （队头阻塞），表现为整个画面停住。国标设备经公网推流时
 *       丢失很常见，这就是「FLV 播两秒 loading 一下」的根本原因，
 *       换 flv.js / mpegts.js / jessibuca 都不会改变这一点。</li>
 * </ul>
 */
export const LIVE_DEFAULT_PROTOCOL = 'webrtc'

/**
 * 直播可选的播放协议（统一在这里定义，避免各页面各写一份、改一处漏一处）。
 *
 * <p>列在这里的都是<b>浏览器真能播</b>的通路：
 * <ul>
 *   <li>{@code webrtc} —— 延迟最低（≈ 0.3s）、<b>最流畅</b>（UDP）。
 *       媒体面完全交给 liveplayer 的 {@code Webrtc} tech，信令走本平台的
 *       {@code /api/v1/webrtc/srs} 适配端点。
 *       <b>G.711（PCMA/PCMU）通道有声音</b>（ZLM 原样透传，浏览器原生解码），
 *       <b>H.265 也能播</b>（实测 2560×1440 稳定 25fps、488 帧丢 1 帧）。
 *       <b>但 AAC 通道没声音</b>：浏览器不支持 AAC-in-WebRTC，
 *       嵌入式 ZLM 也没有 ffmpeg 可转码，这类通道要声音只能换设备侧编码。</li>
 *   <li>{@code wsflv} —— WebSocket-FLV，liveplayer（video.js + flv.js）。
 *       <b>没有声音</b>：flv.js 不认 ZLM 转出的 G.711（{@code audio codec idx 7}），
 *       AAC 也会在 MSE 初始化时失败，一并关掉后画面才稳。
 *       优势是缓冲能攒到 15 秒以上，弱网下比 WebRTC 抗抖动；</li>
 *   <li>{@code httpflv} —— HTTP-FLV，同一路流换传输层，特性同 ws-flv，
 *       能穿代理/防火墙；</li>
 *   <li>{@code hls} —— 纯 HTTP、无需长连接，延迟 ≈ 3 个切片（ZLM 配 2s×3）。
 *       <b>没有声音</b>，只在极端网络环境下当最后手段。</li>
 * </ul>
 * RTSP / RTMP 不在此列：浏览器没有原生支持，只给 VLC / ffplay 用。
 *
 * <p><b>标签只写协议名</b>：括号里的特性说明（有没有声音、缓冲深不深）会让
 * 下拉项过长、挤在一起读不清，且这些特性会随设备编码变化（同一路流，
 * H.264 与 H.265 的表现就不同），写死在标签上反而会误导。
 * 各协议的实测特性以上面的注释为准。
 *
 * <p><b>两个 FLV 用短名</b>：{@code WS-FLV} 与 {@code FLV}。
 * 原来写全称 {@code WebSocket-FLV} 时是 13 个字符，在下拉里比
 * {@code WebRTC} 宽一倍，一列排下来像没对齐；
 * 而 {@code HTTP-FLV} 直接去掉 "HTTP-" 叫 {@code FLV} ——
 * 浏览器场景下 HTTP-FLV 才是默认认知（ws-flv 是它的 WebSocket 变体），
 * 所以短名留给它、长一点的那个加 {@code WS-} 前缀区分。
 */
export const LIVE_PROTOCOLS = [
  { value: 'webrtc', label: 'WebRTC' },
  { value: 'wsflv', label: 'WS-FLV' },
  { value: 'httpflv', label: 'FLV' },
  { value: 'hls', label: 'HLS' }
]

/**
 * 该协议在给定地址集合里是否有可用地址。
 *
 * <p>FLV 的两种承载是同一路流，任一存在即可（见 {@code WsFlvStream}），
 * 所以 {@code wsflv} 与 {@code httpflv} 互相兜底 —— 但只有<b>全都没有</b>才算缺地址。
 *
 * @param {string} proto 协议名
 * @param {object} urls  {@link resolvePlayUrls} 的返回值
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
 * 本浏览器能否播放 FLV（ws-flv / http-flv）。
 *
 * <h3>为什么必须检测</h3>
 *
 * <p>FLV <b>不是浏览器原生容器</b> —— 没有任何浏览器能靠
 * {@code <video src="x.flv">} 播放它（原生只认 MP4/WebM/OGG，
 * Safari 额外认 HLS）。所以 FLV 一定要靠 JS 解封装后灌进
 * {@code MediaSource}（MSE），由浏览器的 MSE 管线解码。
 *
 * <h3>iOS 上为什么不行</h3>
 *
 * <ul>
 *   <li><b>iOS 17.1 之前 WebKit 完全没有 {@code MediaSource}</b>，
 *       这条路根本不存在；</li>
 *   <li>iOS 17.1 起提供了 {@code ManagedMediaSource}（另一套 API：
 *       缓冲由系统托管，而不是页面自己 append/清理）；</li>
 *   <li><b>iOS 上所有浏览器都是 WebKit</b>（苹果强制），
 *       Chrome / Edge / Firefox 一样没有 MSE —— 换浏览器解决不了。</li>
 * </ul>
 *
 * <h3>为什么我们连 {@code ManagedMediaSource} 也不认</h3>
 *
 * <p>注意：打包进来的 flv.js <b>是认它的</b>（{@code getMediaSource()} 里
 * 优先返回 {@code ManagedMediaSource}，见 {@code liveplayer-lib.min.js}），
 * 也就是说在 iOS 17.1+ 上 {@code flvjs.isSupported()} 很可能返回 true。
 * 但「API 存在」不等于「这路流能放」：
 * <ul>
 *   <li>ZLM 转出的 FLV 音频是 G.711（{@code audio codec idx 7}）或 AAC，
 *       MSE 不认 G.711 —— 这正是我们全程 {@code hasaudio=false} 的原因；</li>
 *   <li>ManagedMediaSource 要求缓冲由系统托管，而 flv.js 是「自己无脑 append
 *       + 自己清 SourceBuffer」的老模式，两者对不上，实测表现为
 *       append 被拒或缓冲涨到卡死；</li>
 *   <li>iOS 对 MSE 还有额外的播放策略限制（必须 muted + playsInline 且可见）。</li>
 * </ul>
 * 所以这里<b>刻意保守</b>：只认标准的 {@code MediaSource}，
 * iOS 上一律判为不支持，让用户走 WebRTC（最佳）或原生 HLS。
 * 这比「菜单里给一个点了必然失败的 FLV」好得多。
 *
 * <p>除了 MediaSource 是否存在，再探一次 {@code isTypeSupported}：
 * 某些精简内核有 MediaSource 对象但拒绝所有 mime，那种情况同样放不了。
 *
 * @returns {boolean} 可用返回 true
 */
export function supportsFlv() {
  if (typeof window === 'undefined') return false
  const MS = window.MediaSource || window.WebKitMediaSource
  if (!MS || typeof MS.isTypeSupported !== 'function') return false
  try {
    /*
     * 用 H.264 的 baseline profile 试探 —— ZLM 默认转出的就是它。
     * HEVC（hvc1）= 部分安卓/桌面能过，但手机端普遍不行，
     * 拿它当判据会把「能放 H.264」的设备误判成能放 H.265。
     */
    return MS.isTypeSupported('video/mp4; codecs="avc1.42E01E"')
  } catch (e) {
    // 探针本身报错：保守当不支持，FLV 走不通还有 WebRTC 兜着
    return false
  }
}

/**
 * 本浏览器能否播放 HLS。
 *
 * <p>两条通路，任一可用即可：
 * <ul>
 *   <li>原生 HLS（Safari / iOS）：{@code video.canPlayType('application/vnd.apple.mpegurl')}
 *       返回 {@code maybe}/{@code probably}。liveplayer 的 Hls source handler
 *       检测到原生支持时会主动让路，交给 {@code <video>} 自己解；</li>
 *   <li>hls.js（其余浏览器）：它同样依赖 MSE，判据与 {@link supportsFlv} 一致。</li>
 * </ul>
 */
export function supportsHls() {
  if (typeof window === 'undefined') return false
  try {
    const v = document.createElement('video')
    const mime = 'application/vnd.apple.mpegurl'
    if (v.canPlayType(mime) === 'maybe' || v.canPlayType(mime) === 'probably') return true
  } catch (e) {
    // 落到下面的 MSE 判断
  }
  return supportsFlv()
}

/**
 * 本浏览器能否播放 WebRTC（LivePlayer 内置的 {@code Webrtc} tech）。
 *
 * <p>判据就是 {@code RTCPeerConnection} 是否存在 —— 与 tech 自己的
 * {@code isSupported} 完全一致（见 {@code liveplayer-lib.min.js}）。
 */
export function supportsWebrtc() {
  return typeof window !== 'undefined' && typeof window.RTCPeerConnection === 'function'
}

/**
 * 当前浏览器<b>实际能播</b>的协议列表。
 *
 * <p>用途：协议菜单只列这些，避免给用户一个点了必然失败的选项。
 * 顺序沿用 {@link LIVE_PROTOCOLS}（WebRTC 优先）。
 *
 * <p>典型差异：桌面 Chrome 四项全有；iPhone Safari 只有 WebRTC + HLS。
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
 * 从候选协议里挑一个当前浏览器能播的。
 *
 * <p>用于「用户选了一个本机放不了的协议」时的降级：
 * 优先 WebRTC（延迟最低、H.265 也能放），其次按菜单顺序。
 *
 * @param {string} [preferred] 首选协议（能播就用它）
 * @returns {string|null} 可用协议名；一个都没有时返回 {@code null}
 */
export function pickSupportedProto(preferred) {
  if (preferred && isProtoSupported(preferred)) return preferred
  const list = availableProtocols()
  if (!list.length) return null
  const webrtc = list.find((o) => o.value === 'webrtc')
  return webrtc ? webrtc.value : list[0].value
}
