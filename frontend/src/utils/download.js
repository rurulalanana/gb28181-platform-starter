/**
 * 触发一次文件下载。
 *
 * 用原生 <a download> 而非 fetch + blob：后者把整个文件读进内存（1 小时录像 136MB，
 * blob 还要再复制一份），且完成前没有任何进度；原生交给下载管理器，有进度、可暂停/续传，
 * 数据直接落盘。同源相对路径的 download 属性可指定文件名；跨域签名 URL 会被浏览器忽略，
 * 只能新开窗口由服务端 Content-Disposition 决定。
 *
 * @param {string} url 下载地址
 * @param {string} [filename] 保存文件名（仅同源时有效）
 * @returns {boolean} 是否已触发
 */
export function downloadFile(url, filename) {
  if (!url) {
    return false
  }
  const absolute = /^https?:\/\//i.test(url)
  // 跨域：download 属性无效，新开窗口，由 OSS 的 Content-Disposition 决定
  if (absolute && !sameOrigin(url)) {
    window.open(url, '_blank', 'noopener')
    return true
  }
  const a = document.createElement('a')
  a.href = url
  if (filename) {
    a.download = filename
  }
  /*
   * 必须挂进文档才可靠，但不用 display:none：不参与布局的元素在部分内核里 click()
   * 不一定触发下载，移出视口仍参与布局，行为最稳。
   */
  a.style.cssText = 'position:absolute;left:-9999px;top:0;width:1px;height:1px;opacity:0'
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  return true
}

/**
 * 判断地址是否与当前页面同源。
 *
 * @param {string} url 绝对地址
 * @returns {boolean}
 */
function sameOrigin(url) {
  try {
    return new URL(url, location.href).origin === location.origin
  } catch (e) {
    return false
  }
}

/**
 * 给录像拼一个人类可读的文件名（通道名 + 开始时间）。浏览器默认拿 URL 末段
 * （2026-10-22-18-10-40-0.mp4 看不出是哪台设备），指定名字省得下载后逐个改。
 *
 * @param {object} record 录像记录（name / startAt）
 * @param {string} [channelName] 通道显示名（优先）
 */
export function recordFileName(record, channelName) {
  const who = clean(channelName) || clean(record?.name) || clean(record?.channelId) || '录像'
  const when = clean(record?.startAt)
  return when ? `${who}_${when}.mp4` : `${who}.mp4`
}

/**
 * 去掉文件名里不能用的字符。Windows 禁用 \ / : * ? " < > |，通道名里出现斜杠并不罕见
 * （如「水泵房/东侧」），不清理会被浏览器截断成怪名字。
 *
 * @param {string} s
 */
function clean(s) {
  if (s == null) {
    return ''
  }
  return String(s)
    .replace(/[\\/:*?"<>|]/g, '_')
    .trim()
}
