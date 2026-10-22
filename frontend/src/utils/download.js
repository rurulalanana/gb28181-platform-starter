/**
 * 触发一次文件下载。
 *
 * <h3>为什么用原生 {@code <a download>} 而不是 fetch + blob</h3>
 * <p>早先的实现是 {@code fetch(url) → resp.blob() → URL.createObjectURL}。
 * 协议上没问题，但有两个致命缺点：
 * <ul>
 *   <li><b>整个文件读进内存</b>：一段 1 小时的云端录像是 136MB，
 *       点下载就吃掉 136MB+ 内存（blob 还要再复制一份），
 *       几个标签页同时下载能把浏览器拖垮；</li>
 *   <li><b>没有任何进度反馈</b>：{@code fetch} 完成前界面毫无反应，
 *       公网慢链路上用户以为按钮坏了，反复点。</li>
 * </ul>
 * <p>原生 {@code <a download>} 交给浏览器的下载管理器：有进度条、可暂停、
 * 可断点续传，且数据直接落盘不进内存。
 *
 * <h3>两种地址的区别</h3>
 * <ul>
 *   <li><b>同源相对路径</b>（{@code /record/...}）：{@code download} 属性
 *       生效，能指定保存的文件名 —— 走这条；</li>
 *   <li><b>跨域绝对地址</b>（对象存储的签名 URL）：{@code download} 属性
 *       被浏览器忽略（会变成导航），只能新开窗口由服务端决定响应头。</li>
 * </ul>
 *
 * @param {string} url      下载地址（同源相对路径，或对象存储的绝对 URL）
 * @param {string} [filename] 保存的文件名（仅同源时有效）
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
   * 必须挂进文档才可靠，但<b>不用 {@code display:none}</b>。
   *
   * <p>{@code display:none} 的元素不参与布局，部分浏览器内核
   * 对它的 {@code click()} 不一定触发导航/下载（这也是业界普遍
   * 改用「移出视口」的原因）。移出视口仍参与布局，行为最稳，
   * 用户同样看不到。
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
 * 给录像拼一个人类可读的文件名。
 *
 * <p>通道名 + 开始时间，例如 {@code 梅桥镇硖石水库球机_20261022181040.mp4}。
 * 浏览器默认会用 URL 末段当文件名（那串 {@code 2026-10-22-18-10-40-0.mp4}
 * 看不出是哪台设备），指定名字省得用户下一堆文件后自己改名。
 *
 * @param {object} record 录像记录（含 {@code name} / {@code startAt}）
 * @param {string} [channelName] 通道显示名（优先用）
 * @returns {string}
 */
export function recordFileName(record, channelName) {
  const who = clean(channelName) || clean(record?.name) || clean(record?.channelId) || '录像'
  const when = clean(record?.startAt)
  return when ? `${who}_${when}.mp4` : `${who}.mp4`
}

/**
 * 去掉文件名里不能用的字符。
 *
 * <p>Windows 禁用 {@code \ / : * ? " < > |}，通道名里出现斜杠并不罕见
 * （如「水泵房/东侧」），不清理会导致浏览器保存时被截断成怪名字。
 *
 * @param {string} s
 * @returns {string}
 */
function clean(s) {
  if (s == null) {
    return ''
  }
  return String(s)
    .replace(/[\\/:*?"<>|]/g, '_')
    .trim()
}
