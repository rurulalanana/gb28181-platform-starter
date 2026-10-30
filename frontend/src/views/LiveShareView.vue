<template>
  <div class="play-page">
    <!--
      顶部标题栏：对齐 CMS play.html 的 .navbar —— 高 50px、通栏、
      标题居中、白色加粗。CMS 用 #556677，这里换成我们的品牌主色 --brand-600。
    -->
    <header class="play-header">
      <h1 class="play-title">{{ title }}</h1>
    </header>

    <main class="play-content">
      <!--
        播放区：CMS 是 16:9 的居中黑块。这里同样按 16:9，但用 max-width 限制
        最大宽度 —— 超宽屏上铺满会让画面过大、首屏看不到下方的分享面板。
      -->
      <div class="play-stage">
        <div ref="videoContainer" class="play-video" />
        <div v-if="!playing" class="play-placeholder">
          <template v-if="loading">加载中…</template>
          <template v-else-if="notice">{{ notice }}</template>
          <template v-else>No Video</template>
        </div>

        <!--
          云台悬浮块：1:1 复刻 LiveGBS play.html 的 .ptz-block。

          <h3>交互方式（CMS 的原设计，照搬不改）</h3>
          <ul>
            <li>默认<b>只显示中心那个半透明圆形</b>（四向箭头），不挡画面；</li>
            <li>鼠标移入整块区域才展开 8 个格子：4 个方向 + 2 个变倍（外加中心）；</li>
            <li>中心格子可<b>拖动</b>，把整个控制块挪到画面任意位置 ——
                默认贴右上角，但摄像头画面主体未必在中间，能挪是必要的。</li>
          </ul>

          <h3>尺寸为何写死</h3>
          150×220 是 CMS 的实测值，格子 50×50、中心圆 50 直径。
          用 rem/百分比都做不到「格子严丝合缝拼成一个十字」——
          这些数字之间是整除关系（3 格宽 = 150、4 格高 = 200 + 20 余量），
          改成相对单位反而会在某些缩放下出现 1px 缝。
        -->
        <div
          v-show="ptzReady"
          ref="ptzBlock"
          class="ptz-block"
          :style="ptzStyle"
        >
          <div
            v-for="c in PTZ_CELLS"
            :key="c.cmd"
            class="ptz-cell"
            :class="c.cls"
            :title="c.title"
            @mousedown="startPtz(c.cmd)"
            @mouseup="stopPtz()"
            @mouseleave="stopPtz()"
            @touchstart.prevent="startPtz(c.cmd)"
            @touchend.prevent="stopPtz()"
          >
            <!-- 图标用内联 SVG：CMS 引的是 Font Awesome，本项目没有也不该为 7 个图标引一整套字体 -->
            <svg class="ptz-ico" viewBox="0 0 24 24" aria-hidden="true">
              <path :d="c.path" fill="currentColor" />
            </svg>
          </div>
          <!-- 中心是拖动手柄，与方向键分开渲染：它不响应云台指令 -->
          <div
            class="ptz-cell ptz-center"
            title="拖动可移动云台面板"
            @mousedown.prevent="startDrag"
          >
            <svg class="ptz-ico" viewBox="0 0 24 24" aria-hidden="true">
              <path :d="PTZ_CENTER_PATH" fill="currentColor" />
            </svg>
          </div>
        </div>
      </div>

      <!--
        分享面板：对齐 CMS 的「↩ 分享」标签页 + 三行内容
        （分享链接 / iframe / 扫码直播）。CMS 用的是 bootstrap 的 nav-tabs，
        这里用我们的设计令牌重做，结构保持一致。

        <p><b>为什么把二维码画在本地而不是找后端要图片</b>：
        二维码内容就是这个页面的网址，前端完全知道；多一次请求只为拿一张图
        不划算，而且离线/内网环境下后端图床可能不可达。
      -->
      <section class="share-card">
        <div class="share-tab">
          <svg class="share-tab-icon" viewBox="0 0 16 16" aria-hidden="true">
            <path
              fill="currentColor"
              d="M11.5 1.5 9.8 3.2h.9a4.5 4.5 0 0 1 0 9H6v-1.8h4.7a2.7 2.7 0 0 0 0-5.4h-.9l1.7 1.7-1.3 1.3L6.9 4.7 10.2.2z"
            />
            <path fill="currentColor" d="M9.4 7.6v1.8H4.7a2.7 2.7 0 0 0 0 5.4h.9l-1.7-1.7 1.3-1.3 3.3 3.3-3.3 3.3-1.3-1.3 1.7-1.7h-.9a4.5 4.5 0 0 1 0-9z" />
          </svg>
          分享
        </div>

        <div class="share-body">
          <!-- 分享链接 -->
          <div class="share-row">
            <div class="share-label">分享链接</div>
            <div class="share-field">
              <input class="share-input" :value="pageUrl" readonly @focus="$event.target.select()" />
              <button class="share-copy" title="复制链接" @click="copy(pageUrl)">
                <svg viewBox="0 0 16 16" aria-hidden="true">
                  <path
                    fill="currentColor"
                    d="M10 1H3a1 1 0 0 0-1 1v9h1.5V2.5H10zM6 4h7a1 1 0 0 1 1 1v9a1 1 0 0 1-1 1H6a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1m.5 1.5v8h6v-8z"
                  />
                </svg>
              </button>
            </div>
          </div>

          <!-- iframe 嵌入代码 -->
          <div class="share-row">
            <div class="share-label">iframe</div>
            <div class="share-field">
              <input class="share-input" :value="iframeCode" readonly @focus="$event.target.select()" />
              <button class="share-copy" title="复制嵌入代码" @click="copy(iframeCode)">
                <svg viewBox="0 0 16 16" aria-hidden="true">
                  <path
                    fill="currentColor"
                    d="M10 1H3a1 1 0 0 0-1 1v9h1.5V2.5H10zM6 4h7a1 1 0 0 1 1 1v9a1 1 0 0 1-1 1H6a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1m.5 1.5v8h6v-8z"
                  />
                </svg>
              </button>
            </div>
          </div>

          <!-- 扫码直播 -->
          <div class="share-row">
            <div class="share-label">扫码直播</div>
            <div class="share-field">
              <div class="share-qr">
                <img
                  v-if="qrSrc && !qrFailed"
                  class="share-qr-img"
                  :src="qrSrc"
                  alt="扫码直播"
                  @error="qrFailed = true"
                />
                <div v-else class="share-qr-fallback">
                  二维码生成失败<br />请直接复制上方链接
                </div>
              </div>
            </div>
          </div>
        </div>
      </section>

      <footer class="play-foot">GB/T 28181—2022 视频监控平台</footer>
    </main>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useLivePlayer } from '@/composables/useLivePlayer'
import { resolvePlayUrls, LIVE_DEFAULT_PROTOCOL } from '@/player/resolvePlayUrls'

/**
 * 直播分享页（免登录）。
 *
 * <p>对齐 LiveGBS 的 {@code play.html?serial=&code=}：一个只有标题栏 +
 * 播放器 + 分享面板的独立页面，不套主框架的侧栏与顶栏 ——
 * 它是给外部人员看的，多一个导航栏只会让人以为还需要登录。
 *
 * <p>配色换成本平台的设计令牌（{@code --brand-*} / {@code --ink-*}），
 * 布局与信息层级保持与 CMS 一致。
 */
const route = useRoute()

const loading = ref(true)
const notice = ref('')
const info = ref(null)

const videoContainer = ref(null)
const player = useLivePlayer(videoContainer)
const playing = computed(() => player.playing.value)

const serial = computed(() => String(route.query.serial || '').trim())
const code = computed(() => String(route.query.code || '').trim())

/** 标题：优先通道名，没有时退回编码（对齐 CMS 的 .channel-title）。 */
const title = computed(() => {
  const name = info.value?.name
  if (name) return name
  if (serial.value) return code.value ? `${serial.value}-${code.value}` : serial.value
  return '直播分享'
})

/**
 * 本页的对外地址（分享链接）。
 *
 * <p>用 {@code location.origin + location.pathname + location.search} 而不是
 * 自己拼参数：用户可能通过带额外参数（如 {@code &proto=webrtc}）的链接进来，
 * 原样传下去才不会丢掉那些设置。
 */
const pageUrl = computed(() => {
  if (typeof window === 'undefined') return ''
  return window.location.href
})

/**
 * iframe 嵌入代码。
 *
 * <p>{@code allow="autoplay; fullscreen"} 是必需的：浏览器默认禁止 iframe 内
 * 自动播放与全屏，不带这两项时嵌到别人页面里点全屏没反应。
 * 宽高取 16:9 的 640×360，与 CMS 一致。
 */
const iframeCode = computed(() => {
  if (!pageUrl.value) return ''
  return `<iframe src="${pageUrl.value}" width="640" height="360" allowfullscreen `
    + `allow="autoplay; fullscreen; microphone;" style="border:0"></iframe>`
})

/* ---------------- 二维码 ---------------- */

/**
 * 二维码图片地址（由后端生成 PNG）。
 *
 * <p><b>为什么不前端生成</b>：引一个 QR 编码库要多打包 20~30KB，
 * 而本项目主包已经很大；后端用 zxing 一次编码 + 浏览器缓存即可，
 * 前端只需一个 {@code <img>}。
 *
 * <p>用 {@code encodeURIComponent} 而不是手拼：分享地址里可能带 {@code &}，
 * 不编码会把 {@code text} 参数截断，扫出来的链接就不完整。
 */
const qrSrc = ref('')
const qrFailed = ref(false)

function buildQr() {
  qrFailed.value = false
  if (!pageUrl.value) {
    qrSrc.value = ''
    return
  }
  qrSrc.value = `/api/v1/share/qr?size=180&text=${encodeURIComponent(pageUrl.value)}`
}

/* ---------------- 复制 ---------------- */

async function copy(text) {
  if (!text) return
  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(text)
    } else {
      /*
       * 非安全上下文（http + 非 localhost）下 navigator.clipboard 不可用 ——
       * 分享页常被用 IP+http 打开，这是常态而非例外，必须留兜底。
       */
      const ta = document.createElement('textarea')
      ta.value = text
      ta.style.position = 'fixed'
      ta.style.opacity = '0'
      document.body.appendChild(ta)
      ta.select()
      document.execCommand('copy')
      ta.remove()
    }
    notice.value = ''
    flash('已复制')
  } catch (e) {
    flash('复制失败，请手动选中复制')
  }
}

/** 轻量提示：不引 ElMessage（分享页想尽量少带依赖，避免弹层样式受主框架影响）。 */
const toast = ref('')
let toastTimer = null
function flash(msg) {
  toast.value = msg
  if (toastTimer) clearTimeout(toastTimer)
  toastTimer = setTimeout(() => { toast.value = '' }, 1800)
}

/* ---------------- 加载并播放 ---------------- */

async function load() {
  if (!serial.value) {
    notice.value = '链接缺少 serial 参数'
    loading.value = false
    return
  }
  loading.value = true
  try {
    const q = new URLSearchParams({ serial: serial.value })
    if (code.value) q.set('code', code.value)
    const res = await fetch(`/api/v1/share/live?${q.toString()}`)
    const body = await res.json().catch(() => null)
    if (!body || body.code !== 0) {
      notice.value = body?.msg || '获取通道信息失败'
      return
    }
    info.value = body.data
    notice.value = body.data?.notice || ''
    await play()
  } catch (e) {
    notice.value = '网络异常，无法获取通道信息'
  } finally {
    loading.value = false
  }
}

async function play() {
  const data = info.value
  if (!data) return
  const urls = resolvePlayUrls(
    { flv: data.flv, wsFlv: data.wsFlv, hls: data.hls, rtmp: data.rtmp, rtsp: data.rtsp },
    0
  )
  if (!urls.wsFlv && !urls.httpFlv && !urls.hls) {
    notice.value = notice.value || '暂时拿不到播放地址，请稍后重试'
    return
  }
  notice.value = ''
  await player.play(`${data.deviceId}/${data.channelId}`, urls, {
    proto: LIVE_DEFAULT_PROTOCOL,
    onFatal: (msg) => { notice.value = msg || '播放失败' },
    onStall: () => { notice.value = '画面未就绪，请刷新页面重试' }
  })
}

/* ---------------- 云台 ---------------- */

/**
 * 云台格子的图标路径（24×24 viewBox 的 SVG path）。
 *
 * <p>换成内联 SVG 而不是引 Font Awesome：CMS 那边整站用 FA，
 * 我们只为 7 个图标引一整套字体（~900KB）不划算，而 SVG path 加起来不到 1KB。
 */
const ARROW_UP = 'M12 6l6 7h-4v5h-4v-5H6z'
const ARROW_DOWN = 'M12 18l-6-7h4V6h4v5h4z'
const ARROW_LEFT = 'M6 12l7-6v4h5v4h-5v4z'
const ARROW_RIGHT = 'M18 12l-7 6v-4H6v-4h5V6z'
const ZOOM_IN = 'M12 4a8 8 0 1 0 0 16 8 8 0 0 0 0-16m0 2a6 6 0 1 1 0 12 6 6 0 0 1 0-12m-1 2v3H8v2h3v3h2v-3h3v-2h-3V8z'
const ZOOM_OUT = 'M12 4a8 8 0 1 0 0 16 8 8 0 0 0 0-16m0 2a6 6 0 1 1 0 12 6 6 0 0 1 0-12M8 11v2h8v-2z'
/** 中心拖动柄：四向箭头。 */
const PTZ_CENTER_PATH = 'M12 2l3 3h-2v4h4V7l3 3-3 3v-2h-4v4h2l-3 3-3-3h2v-4H7v2l-3-3 3-3v2h4V5H9z'

/**
 * 六个云台格子：位置由 CSS 类定（与 CMS 的 {@code .ptz-up{top:10px;left:50px}} 一致）。
 *
 * <p>不含中心格 —— 那是拖动柄，不响应云台指令，单独渲染。
 */
const PTZ_CELLS = [
  { cmd: 'up', cls: 'ptz-up', title: '上', path: ARROW_UP },
  { cmd: 'left', cls: 'ptz-left', title: '左', path: ARROW_LEFT },
  { cmd: 'right', cls: 'ptz-right', title: '右', path: ARROW_RIGHT },
  { cmd: 'down', cls: 'ptz-down', title: '下', path: ARROW_DOWN },
  { cmd: 'zoomin', cls: 'ptz-plus', title: '放大', path: ZOOM_IN },
  { cmd: 'zoomout', cls: 'ptz-minus', title: '缩小', path: ZOOM_OUT }
]

/** 云台面板是否可用：通道信息拿到、设备在线、且是球机才有意义。 */
const ptzReady = computed(() => {
  const d = info.value
  return !!d && d.deviceOnline === true && !d.notice
})

/**
 * 面板位置。
 *
 * <p>默认贴右上角（与 CMS 相同的 {@code right:20px; top:20px}），
 * 用户拖动后改用自己的偏移量。用 {@code left/top} 而不是 {@code right/bottom}：
 * 拖动只需要写两个数，不用在「贴右」和「贴左」之间换算容器的宽度。
 */
const ptzPos = ref(null)
const ptzStyle = computed(() => {
  const p = ptzPos.value
  return p ? { left: p.x + 'px', top: p.y + 'px', right: 'auto' } : {}
})

const ptzBlock = ref(null)

/**
 * 按下方向键：立刻发一次指令，并在按住期间每 {@link PTZ_REPEAT_MS} 续发。
 *
 * <h3>为什么要续发</h3>
 * GB/T 28181 的云台指令是<b>「开始转」和「停止转」两条</b>，设备收到后持续转，
 * 直到收到 {@code stop}。但部分设备/下级平台有内部超时（3~5 秒），
 * 不续发会中途停住。续发是行业惯例（CMS 也是这么做的）。
 */
const PTZ_REPEAT_MS = 150

/** 当前按住的动作与续发定时器。 */
let ptzHeld = ''
let ptzTimer = null

function startPtz(cmd) {
  if (!ptzReady.value || !serial.value) return
  // 已有另一个方向按住：先停旧的，避免两个方向同时生效
  if (ptzHeld && ptzHeld !== cmd) stopPtz()
  ptzHeld = cmd
  sendPtz(cmd)
  if (ptzTimer) clearInterval(ptzTimer)
  ptzTimer = setInterval(() => sendPtz(cmd), PTZ_REPEAT_MS)
}

/**
 * 松开 / 移出：发 {@code stop}。
 *
 * <p><b>必须发</b>：不发设备会一直转下去，是这一页最危险的失败模式。
 * 因此除了 mouseup/mouseleave，还在 {@code window} 上兜了一层
 * （见 {@link onMounted}）—— 用户按下后把鼠标拖出窗口再松开时，
 * 元素上的 mouseup 收不到。
 */
function stopPtz() {
  if (ptzTimer) {
    clearInterval(ptzTimer)
    ptzTimer = null
  }
  if (!ptzHeld) return
  ptzHeld = ''
  sendPtz('stop')
}

/** 发一条云台指令。失败只记录，不打断观看。 */
async function sendPtz(command) {
  if (!serial.value) return
  try {
    const q = new URLSearchParams({ serial: serial.value, command })
    if (code.value) q.set('code', code.value)
    await fetch(`/api/v1/share/ptz?${q.toString()}`, { method: 'POST' })
  } catch (e) {
    // 网络抖动时不弹提示：按住方向键会连续发，弹窗会盖满屏幕
  }
}

/* ---------------- 面板拖动 ---------------- */

/** 拖动状态：按下时记录「指针相对块左上角」的偏移。 */
let dragOffset = null

function startDrag(ev) {
  const el = ptzBlock.value
  const stage = el?.parentElement
  if (!el || !stage) return
  const elRect = el.getBoundingClientRect()
  const stageRect = stage.getBoundingClientRect()
  /*
   * 记录指针在块内的相对位置，而不是「块相对容器的位置 + 指针位移」——
   * 后者在块贴右（right:20px）时会算出一个负的 left，第一次拖动就会跳一下。
   */
  dragOffset = {
    x: ev.clientX - elRect.left,
    y: ev.clientY - elRect.top,
    stageRect
  }
  window.addEventListener('mousemove', onDragMove)
  window.addEventListener('mouseup', endDrag)
}

function onDragMove(ev) {
  if (!dragOffset) return
  const { x, y, stageRect } = dragOffset
  const el = ptzBlock.value
  if (!el) return
  const w = el.offsetWidth
  const h = el.offsetHeight
  /*
   * 夹在容器内：不夹的话可以把面板拖到画面外，之后再也找不回来
   * （它自己是唯一的拖动入口）。
   */
  const nx = Math.min(Math.max(0, ev.clientX - stageRect.left - x), Math.max(0, stageRect.width - w))
  const ny = Math.min(Math.max(0, ev.clientY - stageRect.top - y), Math.max(0, stageRect.height - h))
  ptzPos.value = { x: Math.round(nx), y: Math.round(ny) }
}

function endDrag() {
  dragOffset = null
  window.removeEventListener('mousemove', onDragMove)
  window.removeEventListener('mouseup', endDrag)
}

/* ---------------- 生命周期 ---------------- */

onMounted(async () => {
  /*
   * 云台兜底：在窗口层面监听 mouseup 强制停止转向。
   *
   * <p>用户按住方向键后把鼠标拖出窗口再松开，元素上的 mouseup 收不到，
   * 设备就会一直转 —— 必须在 window 上补一层。这是「控件一旦失灵就是
   * 机械损坏」的场景，宁可多挂一个监听。
   */
  window.addEventListener('mouseup', stopPtz)
  window.addEventListener('blur', stopPtz)
  await load()
  buildQr()
})

onUnmounted(() => {
  if (toastTimer) clearTimeout(toastTimer)
  // 先停云台：卸载时设备还在转是最糟的情况
  stopPtz()
  window.removeEventListener('mouseup', stopPtz)
  window.removeEventListener('blur', stopPtz)
  endDrag()
  player.stop()
})

// 地址变化（例如在页面内切换通道）时重算二维码
watch(pageUrl, buildQr)
</script>

<style scoped>
/*
 * 独立页面：不套 MainLayout，所以自己占满视口。
 * 底色用 --bg-app，与主框架内容区一致，分享页看起来仍是同一个产品。
 */
.play-page {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
  background: var(--bg-app);
  color: var(--text-primary);
  font-size: var(--fs-md);
}

/* ---- 顶部标题栏：对齐 CMS 的 .navbar（50px 通栏，标题居中白色加粗） ---- */
.play-header {
  flex: none;
  height: 50px;
  display: flex;
  align-items: center;
  justify-content: center;
  /*
   * CMS 用 #556677 的灰蓝。这里用品牌主色 --brand-600：
   * 「颜色之类的风格要我们系统的风格」，而顶栏是整页最显眼的品牌位。
   */
  background: var(--brand-600);
  box-shadow: 0 1px 3px rgba(15, 22, 33, 0.16);
}

.play-title {
  margin: 0;
  padding: 0 15px;
  font-size: 20px;
  font-weight: 700;
  line-height: 50px;
  color: #fff;
  text-align: center;
  /* 通道名可能很长，超宽时省略而不是把布局撑破 */
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* ---- 内容区：CMS 是 15px 内边距 + 浅灰底 ---- */
.play-content {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: var(--sp-4);
  gap: var(--sp-4);
}

/*
 * 播放区：16:9。CMS 是写死 747×420 居中，这里用 max-width + aspect-ratio，
 * 窄屏自动收缩、宽屏不无限放大（放大后首屏看不到下方分享面板）。
 */
.play-stage {
  position: relative;
  width: 100%;
  max-width: 960px;
  aspect-ratio: 16 / 9;
  background: #000;
  border-radius: var(--radius-sm, 6px);
  overflow: hidden;
}

.play-video {
  position: absolute;
  inset: 0;
}

/* 无流/加载/失败时的占位：黑底白字居中，与 CMS 的「无信号」同位置 */
.play-placeholder {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 0 24px;
  color: #fff;
  font-size: var(--fs-md);
  text-align: center;
  pointer-events: none;
}

/* ============================================================
   云台悬浮块：数值全部取自 LiveGBS play.html 的实测样式
   ------------------------------------------------------------
     .ptz-block  150×220、right:20 top:20、圆角 16、默认透明、hover 半透明
     .ptz-cell   50×50、绝对定位、默认 display:none、hover 整块才显示
     .ptz-center 50×50、圆角 25（正圆）、始终显示、cursor:move
     位置        上(50,10) 左(0,60) 右(100,60) 下(50,110) 大(15,160) 小(85,160)
   ============================================================ */
.ptz-block {
  position: absolute;
  z-index: 20;
  top: 20px;
  right: 20px;
  width: 150px;
  height: 220px;
  text-align: center;
  color: #333;
  background: rgba(237, 237, 237, 0);
  border-radius: 16px;
  overflow: hidden;
  /* 默认不吃鼠标事件，避免挡住画面点击；hover 到它上面时才接管 */
  transition: background-color 0.15s;
}

/*
 * CMS 是 hover 整块变半透明灰。这里用品牌色的极淡版本，
 * 既提示「这块可以操作」，又不会在深色画面上显脏。
 */
.ptz-block:hover {
  background: rgba(237, 237, 237, 0.6);
}

/*
 * 关键的一条：所有格子默认隐藏，让出画面。
 * 只要鼠标不在块内，画面上就只有一个半透明的中心圆。
 */
.ptz-cell {
  position: absolute;
  width: 50px;
  height: 50px;
  line-height: 50px;
  cursor: pointer;
  display: none;
  color: #333;
  user-select: none;
}

.ptz-block:hover .ptz-cell {
  display: block;
}

/* 按下反馈：CMS 用 active 类变白，这里用缩放更明显（画面可能是暗的） */
.ptz-cell:active {
  color: #fff;
}

.ptz-cell:active .ptz-ico {
  transform: scale(1.16);
}

.ptz-ico {
  width: 26px;
  height: 26px;
  vertical-align: middle;
  transition: transform 0.1s;
  pointer-events: none;
}

/* 中心：始终显示，作为拖动柄 */
.ptz-center {
  top: 60px;
  left: 50px;
  display: block;
  border-radius: 25px;
  background: rgba(204, 204, 204, 0.2);
  cursor: move;
}

.ptz-block:hover .ptz-center {
  background: rgba(204, 204, 204, 0.45);
}

/* 中心图标用浅灰：它只是「提示可以拖」，不是可点的按钮 */
.ptz-center .ptz-ico {
  color: rgba(0, 0, 0, 0.3);
  width: 22px;
  height: 22px;
}

/* 六个格子的位置 —— 与 CMS 逐像素一致 */
.ptz-up {
  top: 10px;
  left: 50px;
}

.ptz-left {
  top: 60px;
  left: 0;
}

.ptz-right {
  top: 60px;
  left: 100px;
}

.ptz-down {
  top: 110px;
  left: 50px;
}

.ptz-plus {
  top: 160px;
  left: 15px;
}

.ptz-minus {
  top: 160px;
  left: 85px;
}

/* 触摸设备没有 hover：直接常显，否则手机上永远点不到云台 */
@media (hover: none) {
  .ptz-cell {
    display: block;
  }

  .ptz-block {
    background: rgba(237, 237, 237, 0.35);
  }
}

/* ---- 分享卡片 ---- */
.share-card {
  width: 100%;
  max-width: 960px;
  background: var(--bg-surface);
  border-radius: var(--radius-sm, 6px);
  box-shadow: 0 1px 2px rgba(15, 22, 33, 0.08);
  overflow: hidden;
}

/*
 * 标签页头。CMS 是 bootstrap nav-tabs：一条底线、当前项上方有主色横条、
 * 文字用主色。这里保持同一视觉语言，颜色换成品牌色。
 */
.share-tab {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 42px;
  padding: 0 18px;
  font-size: var(--fs-md);
  color: var(--brand-600);
  background: var(--bg-sunken);
  border-bottom: 2px solid var(--brand-500);
  cursor: default;
}

.share-tab-icon {
  width: 15px;
  height: 15px;
}

.share-body {
  padding: var(--sp-5) var(--sp-6) var(--sp-6);
  display: flex;
  flex-direction: column;
  gap: var(--sp-4);
}

/*
 * 一行：label 固定宽 + 右侧内容。
 * 对齐 CMS 的三行（分享链接 / iframe / 扫码直播），label 右对齐。
 */
.share-row {
  display: flex;
  align-items: flex-start;
  gap: var(--sp-4);
}

.share-label {
  flex: none;
  width: 84px;
  padding-top: 7px;
  font-size: var(--fs-md);
  color: var(--brand-600);
  text-align: right;
}

.share-field {
  flex: 1;
  min-width: 0;
  display: flex;
  align-items: center;
  gap: 0;
}

.share-input {
  flex: 1;
  min-width: 0;
  height: 34px;
  padding: 0 10px;
  font-size: var(--fs-sm);
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  color: var(--text-secondary);
  background: var(--bg-sunken);
  border: 1px solid var(--ink-200);
  border-right: 0;
  border-radius: var(--radius-sm, 6px) 0 0 var(--radius-sm, 6px);
  outline: none;
}

.share-input:focus {
  border-color: var(--brand-400);
  background: var(--bg-surface);
}

/* 复制按钮：与输入框拼成一个控件，所以只有右侧圆角 */
.share-copy {
  flex: none;
  width: 38px;
  height: 34px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  color: var(--text-secondary);
  background: var(--bg-sunken);
  border: 1px solid var(--ink-200);
  border-radius: 0 var(--radius-sm, 6px) var(--radius-sm, 6px) 0;
  cursor: pointer;
  transition: color 0.12s, background-color 0.12s;
}

.share-copy:hover {
  color: var(--brand-600);
  background: var(--brand-50);
}

.share-copy svg {
  width: 15px;
  height: 15px;
}

/* 二维码：白底细边框，放在 label 右侧的起始位置 */
.share-qr {
  position: relative;
  width: 180px;
  height: 180px;
  padding: 6px;
  background: #fff;
  border: 1px solid var(--ink-200);
  border-radius: var(--radius-sm, 6px);
}

.share-qr-img {
  display: block;
  width: 100%;
  height: 100%;
  object-fit: contain;
}

.share-qr-fallback {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 12px;
  font-size: var(--fs-xs);
  color: var(--text-tertiary);
  text-align: center;
}

/* ---- 页脚 ---- */
.play-foot {
  padding: var(--sp-3) 0 var(--sp-5);
  font-size: var(--fs-xs);
  color: var(--text-tertiary);
  text-align: center;
}

/* 窄屏：label 换到上方，避免右侧内容被压得太窄 */
@media (max-width: 640px) {
  .share-row {
    flex-direction: column;
    gap: var(--sp-2);
  }

  .share-label {
    width: auto;
    padding-top: 0;
    text-align: left;
  }

  .share-body {
    padding: var(--sp-4);
  }
}
</style>
