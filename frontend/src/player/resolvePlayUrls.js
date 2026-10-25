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
