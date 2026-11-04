<template>
  <div class="play-page" :class="{ 'is-embed': embedMode }">
    <!-- 顶部标题栏对齐 CMS 的 .navbar（50px 通栏、标题居中），配色换成本平台主色。
         嵌入模式整条隐藏：宿主页面自己写了这是哪个摄像头，这里再画一条只白占高度。 -->
    <header v-if="!embedMode" class="play-header">
      <h1 class="play-title">{{ title }}</h1>
    </header>

    <main class="play-content">
      <!-- 播放区 16:9；宽屏用 max-width 限住，否则首屏看不到下方分享面板。 -->
      <div class="play-stage" :class="{ 'is-stretch': stretchMode }">
        <div ref="videoContainer" class="play-video" />
        <div v-if="!playing" class="play-placeholder">
          <template v-if="loading">加载中…</template>
          <template v-else-if="notice">{{ notice }}</template>
          <template v-else>No Video</template>
        </div>

        <!--
          云台悬浮块，1:1 复刻 LiveGBS play.html 的 .ptz-block：默认只露中心半透明圆，
          鼠标移入才展开六向；中心可拖动把整块挪到画面任意位置。
          尺寸写死 150×220（格子 50）是因为数字间是整除关系，改相对单位会出现 1px 缝。

          窄屏必须用 Teleport 投到下方卡片：竖屏播放区 ~366×206 比云台块还矮，
          浮在画面上会盖掉近半个画面。disabled 为真时就地渲染，一份模板两处落子。
        -->
        <Teleport :to="ptzMobileHost" :disabled="!isMobile || embedMode">
          <div
            v-show="ptzReady"
            ref="ptzBlock"
            class="ptz-block"
            :class="{ 'is-open': ptzOpen }"
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
            <!-- 中心是拖动手柄，不响应云台指令。触摸设备靠它展开/收起六向。 -->
            <div
              class="ptz-cell ptz-center"
              :title="ptzOpen ? '拖动可移动，点一下收起' : '点一下展开，拖动可移动'"
              @pointerdown.prevent="startDrag"
            >
              <svg class="ptz-ico" viewBox="0 0 24 24" aria-hidden="true">
                <path :d="PTZ_CENTER_PATH" fill="currentColor" />
              </svg>
            </div>
          </div>
        </Teleport>
      </div>

      <!--
        下半区卡片。宽屏只有「分享」：云台浮在画面上（看着画面转云台更顺手）。
        窄屏改 Tab 切换：播放区太矮浮不了，挤在卡片旁又只剩 240px 宽。
        嵌入模式整块不渲染（卡片至少 400px 高，会占满 640×360 的 iframe），
        但云台块保留 —— 它浮在画面上不占高度，而「能转云台」正是嵌入方要的。
      -->
      <div v-if="!embedMode" class="play-lower">
        <section class="share-card">
          <!-- 宽屏只有「分享」一项，看起来就是原来的标签页，不额外引入单项 Tab 条。 -->
          <div class="share-tabs" role="tablist">
            <button
              class="share-tab"
              :class="{ 'is-active': activeTab === 'share' }"
              type="button"
              role="tab"
              :aria-selected="activeTab === 'share'"
              @click="activeTab = 'share'"
            >
              <!-- 用手写 SVG 时一条 path 的包围盒到 y=18.4，而 viewBox 只有 16，底部被裁掉。
                   el-icon 已在 main.js 全量注册，不增加依赖。 -->
              <el-icon class="share-tab-icon"><Share /></el-icon>
              分享
            </button>
            <button
              v-if="isMobile && ptzReady"
              class="share-tab"
              :class="{ 'is-active': activeTab === 'ptz' }"
              type="button"
              role="tab"
              :aria-selected="activeTab === 'ptz'"
              @click="activeTab = 'ptz'"
            >
              <el-icon class="share-tab-icon"><VideoCamera /></el-icon>
              云台控制
            </button>
          </div>

          <!--
            分享面板。
            宽屏恒显示；窄屏仅在选中「分享」时显示。
            用 v-show 而不是 v-if：切回来时不必重建三个输入框与那张二维码图
            （<img> 重建会重新请求一次 PNG）。
          -->
          <div v-show="!isMobile || activeTab === 'share'" class="share-body">
          <!-- 分享链接 -->
          <div class="share-row">
            <div class="share-label">分享链接</div>
            <div class="share-field">
              <input class="share-input" :value="pageUrl" readonly aria-label="分享链接" @focus="$event.target.select()" />
              <button class="share-copy" title="复制链接" aria-label="复制链接" @click="copy(pageUrl)">
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
              <input class="share-input" :value="iframeCode" readonly aria-label="iframe 嵌入代码" @focus="$event.target.select()" />
              <button class="share-copy" title="复制嵌入代码" aria-label="复制嵌入代码" @click="copy(iframeCode)">
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
                  @error="onQrError"
                />
                <!--
                  失败态写在「生成中」之前判断：两者都表现为「没有图」。
                  但只有 {@code qrFailed} 为真才能断定真失败了 ——
                  把它放到 {@code v-else} 里的话，首帧（还没开始生成）
                  就会命中，页面一打开先闪一句「二维码生成失败」，
                  几十毫秒后才出图，看起来像报错后又自己好了。
                -->
                <div v-else-if="qrFailed" class="share-qr-state is-failed">
                  二维码生成失败<br />请直接复制上方链接
                </div>
                <div v-else class="share-qr-state">
                  <span class="share-qr-spinner" aria-hidden="true"></span>
                  <span>二维码生成中…</span>
                </div>
              </div>
            </div>
          </div>
          </div>

          <!--
            云台面板（窄屏 Tab「云台控制」的内容，宽屏整块 display:none）。

            <p><b>这个 div 必须始终渲染</b>：它是上面 Teleport 的目标，
            被 v-if 掉的话手机端首次切到这个面板时 Vue 会报
            {@code Invalid Teleport target}。所以用 v-show 控制可见性。

            <p>面板本身只是「宿主」，云台块的真实 DOM 由 Teleport 从播放区投进来，
            宽度由内部的 {@code .ptz-block} 决定（{@code 3 × 单元格边长}），
            换字号/单元格尺寸都不用改这里。
          -->
          <div
            v-show="isMobile && activeTab === 'ptz'"
            ref="ptzMobileHost"
            class="ptz-mobile-host"
            :class="{ 'is-ready': ptzReady }"
          >
            <div v-if="!ptzReady" class="ptz-mobile-empty">
              该通道不支持云台控制
            </div>
          </div>
        </section>
      </div>

      <footer v-if="!embedMode" class="play-foot">GB/T 28181—2022 视频监控平台</footer>
    </main>

    <!--
      复制结果提示。
      固定底部居中、pointer-events:none（不挡点击），1.8 秒自动消失。
      它是本页唯一的操作反馈 —— 「复制」按钮点完什么都不发生的话，
      用户只会反复点。
    -->
    <transition name="toast-fade">
      <div v-if="toast" class="play-toast">{{ toast }}</div>
    </transition>

    <!--
      播放协议菜单。

      <p>触发按钮是引擎用 DOM 直接插进 video.js 控制栏的（在控制栏里而不是
      页面某个角落 —— 协议是「这路画面用什么方式看」，与画面同域），
      Vue 拿不到它的组件实例，所以用 el-popover 的 virtual-triggering
      把它当锚点（{@code protoAnchorEl} 由 onControlButton 回传）。

      <p>放在 play-page 末尾而不是卡片里：它是 teleport 到 body 的浮层，
      位置无关；而放在这里，嵌入模式（没有卡片）下也能弹出来 ——
      嵌入方同样可能需要换协议（例如 H.265 通道在 WebRTC 下才播得动）。
    -->
    <el-popover
      v-model:visible="protoMenuVisible"
      :virtual-ref="protoAnchorEl"
      virtual-triggering
      trigger="click"
      placement="top-end"
      :width="150"
      popper-class="share-proto-menu"
    >
      <div class="proto-menu">
        <button
          v-for="o in protoOptions"
          :key="o.value"
          type="button"
          class="proto-menu-item"
          :class="{ 'is-current': o.value === proto }"
          @click="switchProtoTo(o.value)"
        >
          {{ o.label }}
        </button>
      </div>
    </el-popover>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useLivePlayer } from '@/composables/useLivePlayer'
import { resolvePlayUrls, LIVE_DEFAULT_PROTOCOL, availableProtocols, isMobileClient } from '@/player/resolvePlayUrls'

/**
 * 直播分享页（免登录）。
 *
 * <p>对齐 LiveGBS 的 {@code play.html?serial=&code=}：只有标题栏 + 播放器 + 分享面板，
 * 不套主框架的侧栏与顶栏 —— 它是给外部人员看的，多一个导航栏只会让人以为还要登录。
 */
const route = useRoute()

const loading = ref(true)
const notice = ref('')
const info = ref(null)

const videoContainer = ref(null)
const player = useLivePlayer(videoContainer)
const playing = computed(() => player.playing.value)

/* ---------------- 播放协议 ---------------- */

/**
 * 当前播放协议。
 *
 * <p>分享页必须能切协议：外部人员遇到 H.265（FLV 播不了）或 UDP 被拦（WebRTC 连不上）时，
 * 除了换协议没有别的办法 —— 他们连后台都进不去。
 */
const proto = ref(LIVE_DEFAULT_PROTOCOL)

/** 当前协议显示名。从 protoOptions 查而不是另写映射表，两份名字一旦不同步就会错位。 */
const protoShort = computed(() => {
  const hit = protoOptions.value.find((o) => o.value === proto.value)
  return hit ? hit.label : proto.value
})

/** 可选协议：只列本浏览器真能播的。iPhone 无 MediaSource，摆着 FLV 只会点了黑屏。 */
const protoOptions = computed(() => availableProtocols())

/** 协议菜单是否展开（锚定在控制栏那个按钮上）。 */
const protoMenuVisible = ref(false)
/** 控制栏按钮元素（popover 虚拟锚点）。 */
const protoAnchorEl = ref(null)

/**
 * 控制栏按钮点击：只把锚点交给 popover，展开由它自己 toggle。
 *
 * <p>这里不能再写 {@code visible = true} —— 第二次点击会变成「它 toggle 成 false、我又设回 true」，
 * 菜单就关不掉了。只有「首次拿到锚点」那次例外（监听要等 popover 重渲染才挂上）。
 */
function onControlButton(id, el) {
  if (id !== 'proto') return
  if (protoAnchorEl.value === el) return
  protoAnchorEl.value = el
  protoMenuVisible.value = true
}

/** 把当前协议文案同步到控制栏按钮。 */
function syncProtoLabel() {
  player.setControlButtonText('proto', protoShort.value)
}

/** 协议文案变化时同步（自动兜底时也会走到）。 */
watch(protoShort, syncProtoLabel)

/**
 * 选择新协议并重连。
 *
 * <p>选中当前项时直接关菜单，避免白白断流重连一次。
 *
 * @param {string} next 目标协议
 */
async function switchProtoTo(next) {
  protoMenuVisible.value = false
  if (!next || next === proto.value) return
  proto.value = next
  await play()
}

const serial = computed(() => String(route.query.serial || '').trim())
const code = computed(() => String(route.query.code || '').trim())

/* ---------------- CMS 兼容参数 ---------------- */

/**
 * 是否被 iframe 嵌入。
 *
 * <p>两个判据都要用：访问 {@code window.top} 在跨域嵌入时会抛 SecurityError，
 * 而 {@code frameElement} 跨域时返回 null，单靠它分不清「跨域嵌入」与「根本没嵌入」。
 */
const inIframe = computed(() => {
  if (typeof window === 'undefined') return false
  try {
    if (window.frameElement) return true
    return window.top !== window.self
  } catch (e) {
    // 跨域访问 top 被拦 —— 说明确实在 iframe 里
    return true
  }
})

/** 嵌入模式：只留画面。被 iframe 嵌入，或显式传 {@code aspect=fullscreen}（对齐 CMS）。 */
const embedMode = computed(() => {
  if (inIframe.value) return true
  const aspect = String(route.query.aspect || '').trim().toLowerCase()
  return aspect === 'fullscreen' || aspect === 'full'
})

/** 是否拉伸填满（CMS 的 {@code stretch=yes}）：电视墙格子和画面比例不一致时用，默认等比。 */
const stretchMode = computed(() => {
  const v = String(route.query.stretch || '').trim().toLowerCase()
  return v === 'yes' || v === '1' || v === 'true'
})

/** 标题：优先通道名，没有时退回编码（对齐 CMS 的 .channel-title）。 */
const title = computed(() => {
  const name = info.value?.name
  if (name) return name
  if (serial.value) return code.value ? `${serial.value}-${code.value}` : serial.value
  return '直播分享'
})

/** 本页对外地址。用 location.href 而不是自己拼参数，否则会把带进来的额外参数（如 proto=webrtc）丢掉。 */
const pageUrl = computed(() => {
  if (typeof window === 'undefined') return ''
  return window.location.href
})

/**
 * iframe 嵌入地址。必须强制带 {@code aspect=fullscreen}，否则 640×360 的 iframe 里
 * 会被 400px 高的分享卡片挤得只剩一条画面。
 *
 * <p>用 searchParams.set 而非字符串拼参数：地址里可能已经有 aspect=，
 * 拼出两个同名参数后取哪个由浏览器自定。set 是覆盖，天然幂等。
 */
const embedUrl = computed(() => {
  if (!pageUrl.value) return ''
  try {
    const u = new URL(pageUrl.value)
    u.searchParams.set('aspect', 'fullscreen')
    return u.toString()
  } catch (e) {
    // 极端情况下 URL 解析失败：退回原地址，至少不会比现在更差
    return pageUrl.value
  }
})

/** iframe 代码。{@code allow="autoplay; fullscreen"} 不能省 —— 缺了它嵌到别人页面里点全屏无反应。 */
const iframeCode = computed(() => {
  if (!embedUrl.value) return ''
  return `<iframe src="${embedUrl.value}" width="640" height="360" `
    + `title="视频直播" allowfullscreen `
    + `allow="autoplay; fullscreen; microphone;" style="border:0"></iframe>`
})

/* ---------------- 二维码 ---------------- */

/**
 * 二维码（浏览器本地生成）。
 *
 * <p>早先由后端 zxing 出 PNG，怕「撑大主包」—— 理由不成立：本页是懒加载路由，
 * 库只进 LiveShareView 这个 chunk。反而省了三个代价：免登录的图片编码端点、
 * 595KB 的 zxing 依赖、每次进页面的 HTTP 往返。
 *
 * <p>动态 import：嵌入模式下分享面板不渲染，没必要下载这个库。
 *
 * <p>参数与原后端版对齐：180px、margin 1、纠错 M、纯黑白。
 */
const qrSrc = ref('')
const qrFailed = ref(false)

async function buildQr() {
  // 重新生成时先复位失败标志：否则上一次失败留下的文案会盖住「生成中」
  qrFailed.value = false
  const text = pageUrl.value
  // 嵌入模式没有分享面板，不必生成
  if (!text || embedMode.value) {
    qrSrc.value = ''
    return
  }
  try {
    const { default: QRCode } = await import('qrcode')
    const url = await QRCode.toDataURL(text, {
      width: 180,
      margin: 1,
      errorCorrectionLevel: 'M',
      color: { dark: '#000000ff', light: '#ffffffff' }
    })
    // 编码期间地址变了就别用旧结果覆盖新结果
    if (pageUrl.value === text) qrSrc.value = url
  } catch (e) {
    // 同样是「地址变了」就丢弃：那次请求会有新的 buildQr 接手
    if (pageUrl.value !== text) return
    qrSrc.value = ''
    qrFailed.value = true
  }
}

/**
 * 图片加载失败（data URL 理论上不会，兼一层）。
 *
 * <p>必须清空 {@code qrSrc} 而不能只置 {@code qrFailed}：否则重新生成时复位失败标志，
 * 模板又会去加载那个坏地址。
 */
function onQrError() {
  qrSrc.value = ''
  qrFailed.value = true
}

/* ---------------- 复制 ---------------- */

/**
 * 复制到剪贴板并提示。
 *
 * <p>两段兜底而不是「有 clipboard 就用它」：该 API 存在 ≠ 能用 ——
 * 权限被拒（NotAllowedError）、文档未聚焦（iOS Safari / 部分 Chrome）都会抛错。
 * 早先的写法在 catch 里直接报失败、不试 execCommand，于是那些环境里点复制永远没反应。
 */
async function copy(text) {
  if (!text) return
  /* 第一选择：异步剪贴板 API（安全上下文 + 有权限时最可靠） */
  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(text)
      notice.value = ''
      flash('已复制')
      return
    }
  } catch (e) {
    // 落到下面的 execCommand 兜底，不在这里报错
  }
  /* 第二选择：textarea + execCommand（http 页面、权限被拒时的兜底） */
  try {
    const ta = document.createElement('textarea')
    ta.value = text
    /*
     * 定位到视口内但不可见：{@code opacity:0} + {@code position:fixed}
     * 在 iOS 上仍可能因「元素不可见」被拒绝，所以用 1px 尺寸 + 透明边框，
     * 这是 execCommand('copy') 兼容性最好的写法。
     */
    ta.style.cssText =
      'position:fixed;top:0;left:0;width:1px;height:1px;padding:0;border:0;opacity:0'
    ta.setAttribute('readonly', '')
    document.body.appendChild(ta)
    ta.select()
    ta.setSelectionRange(0, ta.value.length)
    const ok = document.execCommand('copy')
    ta.remove()
    if (ok) {
      notice.value = ''
      flash('已复制')
    } else {
      flash('复制失败，请手动选中复制')
    }
  } catch (e) {
    flash('复制失败，请手动选中复制')
  }
}

/** 轻量提示：不引 ElMessage（少带依赖，也避免弹层样式受主框架影响）。
 *  必须渲染到模板里 —— 这个 ref 曾经定义了却没出现在模板中，点「复制」完全没反馈。 */
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
  const phone = isMobileClient()
  await player.play(`${data.deviceId}/${data.channelId}`, urls, {
    // 手机固定 WebRTC：FLV 无可用 MSE，HLS 延迟 6s+ 且非 Safari 解不了 G.711，
    // 给三个选项只会让人点到必然失败的那个。详见 isMobileClient 的注释。
    proto: phone ? LIVE_DEFAULT_PROTOCOL : proto.value,
    // 协议切换按钮仅桌面端：手机上的可选项本身就不靠谱，按钮只会变成「点了就黑屏」的陷阱
    controlButtons: phone
      ? null
      : [{ id: 'proto', title: '播放协议', text: protoShort.value }],
    onControlButton,
    // 浏览器放不了所选协议时引擎会自动降级。手机上不会走到（只有 WebRTC），
    // 但桌面端带 &proto=hls 这类链接进来时仍可能命中。
    onProtoChange: (used, wanted) => {
      proto.value = used
      if (wanted && wanted !== used) {
        const from = protoOptions.value.find((o) => o.value === used)
        flash(`当前浏览器不支持该协议，已自动切换到 ${from ? from.label : used}`)
      }
    },
    onFatal: (msg) => { notice.value = msg || '播放失败' },
    // 文案按端区分：手机上不能换协议，让它去换只会白试
    onStall: () => {
      notice.value = phone
        ? '画面未就绪，请检查手机网络后刷新重试'
        : '画面未就绪，可尝试切换播放协议'
    }
  })
  syncProtoLabel()
}

/* ---------------- 云台 ---------------- */

/** 云台图标路径。用内联 SVG 而不是引 Font Awesome：为 7 个图标引一整套字体（~900KB）不划算。 */
const ARROW_UP = 'M12 6l6 7h-4v5h-4v-5H6z'
const ARROW_DOWN = 'M12 18l-6-7h4V6h4v5h4z'
const ARROW_LEFT = 'M6 12l7-6v4h5v4h-5v4z'
const ARROW_RIGHT = 'M18 12l-7 6v-4H6v-4h5V6z'
const ZOOM_IN = 'M12 4a8 8 0 1 0 0 16 8 8 0 0 0 0-16m0 2a6 6 0 1 1 0 12 6 6 0 0 1 0-12m-1 2v3H8v2h3v3h2v-3h3v-2h-3V8z'
const ZOOM_OUT = 'M12 4a8 8 0 1 0 0 16 8 8 0 0 0 0-16m0 2a6 6 0 1 1 0 12 6 6 0 0 1 0-12M8 11v2h8v-2z'
/** 中心拖动柄：四向箭头。 */
const PTZ_CENTER_PATH = 'M12 2l3 3h-2v4h4V7l3 3-3 3v-2h-4v4h2l-3 3-3-3h2v-4H7v2l-3-3 3-3v2h4V5H9z'

/** 六个云台格子（位置由 CSS 类定）。不含中心格 —— 那是拖动柄，不响应云台指令。 */
const PTZ_CELLS = [
  { cmd: 'up', cls: 'ptz-up', title: '上', path: ARROW_UP },
  { cmd: 'left', cls: 'ptz-left', title: '左', path: ARROW_LEFT },
  { cmd: 'right', cls: 'ptz-right', title: '右', path: ARROW_RIGHT },
  { cmd: 'down', cls: 'ptz-down', title: '下', path: ARROW_DOWN },
  { cmd: 'zoomin', cls: 'ptz-plus', title: '放大', path: ZOOM_IN },
  { cmd: 'zoomout', cls: 'ptz-minus', title: '缩小', path: ZOOM_OUT }
]

/** 该类型是否要显示云台面板：只有明确是固定枪机（3）才隐藏。
 *  判据必须与通道管理页的 supportsPtz 一致，否则会出现「列表说不可控、分享页却弹方向盘」。
 *  0 = 设备没报，不等于不能转：国标设备普遍不报，SDK 直连设备恒为 0。 */
function supportsPtz(type) {
  return type !== 3
}

/** 云台面板是否可用：拿到通道信息 + 设备在线 + 通道确实是球机/半球/遥控枪机。 */
const ptzReady = computed(() => {
  const d = info.value
  if (!d || d.deviceOnline !== true || d.notice) return false
  return supportsPtz(d.ptzType)
})

/** 面板位置。默认贴右上角，拖动后用 left/top 偏移（不用 right/bottom，拖动只需写两个数）。 */
const ptzPos = ref(null)
const ptzStyle = computed(() => {
  const p = ptzPos.value
  return p ? { left: p.x + 'px', top: p.y + 'px', right: 'auto' } : {}
})

const ptzBlock = ref(null)

/**
 * 云台块在窄屏下的目标容器（分享卡片右侧）。
 *
 * <p>只在 {@code isMobile} 为真时被 {@code Teleport} 用到；
 * 元素<b>始终渲染</b>，否则手机首次进入时 Teleport 的目标还不存在，
 * Vue 会直接报 {@code Invalid Teleport target}。
 */
const ptzMobileHost = ref(null)

/**
 * 是否窄屏（≤640px）。
 *
 * <p>这里必须用 JS 判断而不能只靠 CSS：云台块要<b>换一个父节点</b>
 * （从播放区里挪到下方卡片的面板里），不是纯样式能做的。
 *
 * <p>初始值给 {@code false}：首帧时 {@code ptzMobileHost} 还没挂载，
 * 这时若就是 {@code true}，Teleport 会拿 {@code null} 当目标而报错。
 * {@code onMounted} 里会立刻纠正，用户看不到差别。
 */
const isMobile = ref(false)

/**
 * 当前 Tab：{@code 'share'} | {@code 'ptz'}。
 *
 * <p>宽屏没有 Tab 可言（云台浮在画面上），这个值只在窄屏生效。
 * 切到窄屏时若停在 {@code 'ptz'} 但通道不支持云台，会自动回落。
 */
const activeTab = ref('share')

/** 窄屏媒体查询句柄（解除监听用）。 */
let mobileMql = null

function onMobileChange(e) {
  isMobile.value = e.matches
  // 从窄屏切回宽屏时把 Tab 复位，否则再切回窄屏会莫名其妙停在云台页
  if (!e.matches) activeTab.value = 'share'
}

/**
 * 按住方向键的续发间隔。
 *
 * <p>国标云台是「开始转 / 停止转」两条指令，部分设备有 3~5 秒内部超时，
 * 不续发会中途停住。续发是行业惯例（CMS 也这么做）。
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
 * 松开 / 移出：必须发 {@code stop}，不发设备会一直转下去。
 *
 * <p>除 mouseup/mouseleave 外还在 window 上兜了一层（见 onMounted）：
 * 用户按住后把鼠标拖出窗口再松开，元素上的 mouseup 收不到。
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

/* ---------------- 面板拖动 / 展开 ---------------- */

/**
 * 方向键是否展开。桌面端靠 :hover，触摸设备没有 hover 必须给显式开关，
 * 否则要么点不到、要么整块十字常显（比竖屏播放区还高，会盖掉近半个画面）。
 */
const ptzOpen = ref(false)

/** 拖动状态：按下时记录「指针相对块左上角」的偏移。 */
let dragOffset = null
/** 按下时的指针坐标，用来区分「点一下」与「拖动」。 */
let dragStartPt = null
/** 本次按下是否真的移动过。 */
let dragMoved = false

/** 判定为拖动的最小位移（px）。小于它就当成一次点击。 */
const DRAG_THRESHOLD = 6

function startDrag(ev) {
  // 窄屏下云台块是卡片旁的静态面板，父节点已变成宿主 div，夹紧会把面板锁死，直接不启用
  if (isMobile.value) return
  const el = ptzBlock.value
  const stage = el?.parentElement
  if (!el || !stage) return
  const elRect = el.getBoundingClientRect()
  const stageRect = stage.getBoundingClientRect()
  // 记录指针在块内的相对位置：若用「块相对容器位置 + 位移」，块贴右时首次拖动会跳一下
  dragOffset = {
    x: ev.clientX - elRect.left,
    y: ev.clientY - elRect.top,
    stageRect
  }
  dragStartPt = { x: ev.clientX, y: ev.clientY }
  dragMoved = false
  // 用 Pointer Events 而非 mouse：一套代码覆盖鼠标与触摸。触摸下滚动/长按会打断
  // 模拟 mouse 事件，松手可能收不到 mouseup，面板就「粘」在指针上。
  window.addEventListener('pointermove', onDragMove)
  window.addEventListener('pointerup', endDrag)
  window.addEventListener('pointercancel', endDrag)
}

function onDragMove(ev) {
  if (!dragOffset) return
  // 位移超过阈值才算拖动；否则手指轻微抖动会把「点击」误判成拖动
  if (!dragMoved) {
    const d = Math.hypot(ev.clientX - dragStartPt.x, ev.clientY - dragStartPt.y)
    if (d < DRAG_THRESHOLD) return
    dragMoved = true
  }
  const { x, y, stageRect } = dragOffset
  const el = ptzBlock.value
  if (!el) return
  const w = el.offsetWidth
  const h = el.offsetHeight
  // 夹在容器内，否则可以拖到画面外再也找不回来（它是唯一的拖动入口）
  const nx = Math.min(Math.max(0, ev.clientX - stageRect.left - x), Math.max(0, stageRect.width - w))
  const ny = Math.min(Math.max(0, ev.clientY - stageRect.top - y), Math.max(0, stageRect.height - h))
  ptzPos.value = { x: Math.round(nx), y: Math.round(ny) }
}

function endDrag() {
  // 没有位移 = 点了一下中心圆 → 切换展开态。只在真按下过时判定，避免卸载时空调用翻转状态
  if (dragOffset && !dragMoved) ptzOpen.value = !ptzOpen.value
  dragOffset = null
  dragStartPt = null
  dragMoved = false
  window.removeEventListener('pointermove', onDragMove)
  window.removeEventListener('pointerup', endDrag)
  window.removeEventListener('pointercancel', endDrag)
}

/* ---------------- 生命周期 ---------------- */

onMounted(async () => {
  // 云台兜底：在 window 上监听 mouseup。用户按住后拖出窗口再松开，元素上的 mouseup
  // 收不到，设备会一直转 —— 这是「控件失灵就是机械损坏」的场景，宁可多挂一个监听。
  window.addEventListener('mouseup', stopPtz)
  window.addEventListener('blur', stopPtz)
  // 窄屏判断：初始 false（否则首帧 Teleport 目标还没挂载），这里立即纠正
  mobileMql = window.matchMedia('(max-width: 640px)')
  isMobile.value = mobileMql.matches
  mobileMql.addEventListener('change', onMobileChange)
  await load()
  buildQr()
})

onUnmounted(() => {
  if (toastTimer) clearTimeout(toastTimer)
  // 先停云台：卸载时设备还在转是最糟的情况
  stopPtz()
  window.removeEventListener('mouseup', stopPtz)
  window.removeEventListener('blur', stopPtz)
  if (mobileMql) {
    mobileMql.removeEventListener('change', onMobileChange)
    mobileMql = null
  }
  endDrag()
  player.stop()
})

// 地址变化（例如在页面内切换通道）时重算二维码
watch(pageUrl, buildQr)

/*
 * 通道信息（异步）到达后才知道能不能云台。
 * 若用户已经把 Tab 切到「云台控制」（例如在加载完成前手快点了），
 * 而这个通道其实不支持，就退回分享页 —— 否则会停在一个空白面板上。
 */
watch(ptzReady, (ok) => {
  if (!ok && activeTab.value === 'ptz') activeTab.value = 'share'
})
</script>

<style scoped>
/*
 * 独立页面：不套 MainLayout，所以自己占满视口。
 * 底色用 --bg-app，与主框架内容区一致，分享页看起来仍是同一个产品。
 */
.play-page {
  /*
   * 100vh 在移动浏览器上是「含地址栏】的高度，地址栏收起/展开时会露出
   * 下方空白或溢出。dvh 是动态视口，跟着地址栏变，是现在的正确写法；
   * 旧内核不认 dvh 时回退到上一行的 vh。
   */
  min-height: 100vh;
  min-height: 100dvh;
  display: flex;
  flex-direction: column;
  background: var(--bg-app);
  color: var(--text-primary);
  font-size: var(--fs-md);
}

/*
 * 嵌入模式（iframe 或 aspect=fullscreen）：只留画面。
 *
 * <p>三件事：
 * <ul>
 *   <li>去掉内容区四周的内边距与栏间距 —— 那里每 1px 都是画面的损失；</li>
 *   <li>视口铺满用 {@code 100dvh} 且不允许滚动，避免 iframe 里出现
 *       一条纵向滚动条（640×360 的 iframe 里那条滚动条会吃掉 15px 宽）；</li>
 *   <li>底色改黑 —— 若视口比 16:9 更「胖」，多出来的那一截也会是黑的，
 *       与画面边框融为一体，而不是突兀的一条浅灰。</li>
 * </ul>
 */
.play-page.is-embed {
  background: #000;
  height: 100vh;
  height: 100dvh;
  overflow: hidden;
}

.play-page.is-embed .play-content {
  padding: 0;
  gap: 0;
  /* 单行单列：让 .play-stage 直接吃满剩余空间 */
  display: block;
}

/* 嵌入模式下播放区不再锁 16:9，改为铺满可用区域（画面本身由 object-fit 控制） */
.play-page.is-embed .play-stage {
  width: 100%;
  height: 100%;
  max-width: none;
  aspect-ratio: auto;
  border-radius: 0;
}

/*
 * 拉伸模式（CMS 的 {@code stretch=yes}）：让画面填满容器。
 *
 * <p>用 {@code !important} 压过 {@code .lp-fill video} 的 {@code object-fit:contain}
 * —— 那条是引擎注入的全局样式，权重不高但顺序在后，不加重会被盖掉。
 * 只在面板上生效，不影响主平台（那边的画面必须等比）。
 */
.play-stage.is-stretch :deep(video) {
  object-fit: fill !important;
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

   <p>所有尺寸都由 {@code --ptz-cell}（单元格边长）推导：默认 50px 时
   算出来与 CMS 逐像素一致；窄屏改一个变量就能整体缩小，
   不用把 8 个坐标改一遍（也无法用 transform:scale —— 那会让
   拖动时 {@code offsetWidth} 与实际包围盒不一致，拖到不了右边）。

   <p>{@code --ptz-gap} 是格子间距。默认 0 —— 桌面浮层保持 CMS 那种
   「无缝拼成十字」的外观；窄屏要画成一粒粒独立的圆角按键，
   那里会把它改成正数。位置全部写成「{@code cell + gap} 的整数倍」，
   所以 gap=0 时算出来与原公式完全一致。
   ============================================================ */
.ptz-block {
  /* 单元格边长与间距：窄屏只需改这两个参数 */
  --ptz-cell: 50px;
  --ptz-gap: 0px;
  /* 行距 = 单元格 + 间隙，位置公式里反复用到 */
  --ptz-step: calc(var(--ptz-cell) + var(--ptz-gap));
  position: absolute;
  z-index: 20;
  top: 20px;
  right: 20px;
  width: calc(var(--ptz-step) * 3 - var(--ptz-gap));
  /* 上方 0.2 格 + 四行格子三处间隙 + 下方 0.2 格余量 */
  height: calc(var(--ptz-step) * 4 - var(--ptz-gap) + var(--ptz-cell) * 0.4);
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
  width: var(--ptz-cell);
  height: var(--ptz-cell);
  line-height: var(--ptz-cell);
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
  width: calc(var(--ptz-cell) * 0.52);
  height: calc(var(--ptz-cell) * 0.52);
  vertical-align: middle;
  transition: transform 0.1s;
  pointer-events: none;
}

/* 中心：始终显示，作为拖动柄 */
.ptz-center {
  top: var(--ptz-step);
  left: var(--ptz-step);
  display: block;
  border-radius: calc(var(--ptz-cell) / 2);
  background: rgba(204, 204, 204, 0.2);
  cursor: move;
}

.ptz-block:hover .ptz-center {
  background: rgba(204, 204, 204, 0.45);
}

/* 中心图标用浅灰：它只是「提示可以拖」，不是可点的按钮 */
.ptz-center .ptz-ico {
  color: rgba(0, 0, 0, 0.3);
  width: calc(var(--ptz-cell) * 0.44);
  height: calc(var(--ptz-cell) * 0.44);
}

/* 六个格子的位置 —— gap=0 时与 CMS 逐像素一致 */
.ptz-up {
  top: calc(var(--ptz-cell) * 0.2);
  left: var(--ptz-step);
}

.ptz-left {
  top: calc(var(--ptz-step) + var(--ptz-cell) * 0.2);
  left: 0;
}

.ptz-right {
  top: calc(var(--ptz-step) + var(--ptz-cell) * 0.2);
  left: calc(var(--ptz-step) * 2);
}

.ptz-down {
  top: calc(var(--ptz-step) * 2 + var(--ptz-cell) * 0.2);
  left: var(--ptz-step);
}

.ptz-plus {
  top: calc(var(--ptz-step) * 3 + var(--ptz-cell) * 0.2);
  left: calc(var(--ptz-cell) * 0.3);
}

.ptz-minus {
  top: calc(var(--ptz-step) * 3 + var(--ptz-cell) * 0.2);
  /* 2 格宽 − 0.3 格左边距：gap=0 时正好 1.7 格，与 plus 左右对称 */
  left: calc(var(--ptz-step) * 2 - var(--ptz-cell) * 0.3);
}

/*
 * 触摸设备没有 hover：方向键改为「点中心圆展开/收起」。
 *
 * <p>原来这里写的是「一律常显」，在手机上不可用 —— 竖屏播放区只有
 * ~358×201，而云台块是 150×220（比画面还高），常显直接盖掉近半个画面。
 * 另外手机浏览器点一下也会置 {@code :hover}，上一条 hover 规则会把格子
 * 又显示出来，所以这里要把它一并压掉。
 */
@media (hover: none) {
  .ptz-block:hover .ptz-cell,
  .ptz-cell {
    display: none;
  }

  .ptz-block .ptz-center,
  .ptz-block.is-open .ptz-cell {
    display: block;
  }

  .ptz-block.is-open {
    background: rgba(237, 237, 237, 0.35);
  }
}

/* ---- 分享卡片 ---- */
.play-lower {
  width: 100%;
  max-width: 960px;
}

/*
 * 云台面板的宿主。
 *
 * <p>宽屏不显示（那时云台浮在画面右上角）。窄屏的具体样式见文件末尾的
 * {@code @media (max-width: 640px)}。这里给 {@code display:none} 是
 * 「默认隐藏」的语义，窄屏规则会覆盖它。
 */
.ptz-mobile-host {
  display: none;
}

.ptz-mobile-empty {
  padding: var(--sp-6) var(--sp-4);
  font-size: var(--fs-sm);
  color: var(--text-tertiary);
  text-align: center;
}

.share-card {
  width: 100%;
  max-width: 960px;
  background: var(--bg-surface);
  border-radius: var(--radius-sm, 6px);
  box-shadow: 0 1px 2px rgba(15, 22, 33, 0.08);
  overflow: hidden;
}

/*
 * Tab 条。CMS 是 bootstrap nav-tabs：一条底线、当前项上方有主色横条、
 * 文字用主色。这里保持同一视觉语言，颜色换成品牌色。
 *
 * <p>宽屏时 bar 里只有一项（分享），看起来就像原来的「标签页」；
 * 窄屏会多出「云台控制」，成为真正的可切换 Tab。
 */
.share-tabs {
  display: flex;
  align-items: stretch;
  gap: 2px;
  padding: 0 6px;
  background: var(--bg-sunken);
  border-bottom: 1px solid var(--ink-200);
}

.share-tab {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 42px;
  padding: 0 14px;
  font-size: var(--fs-md);
  font-family: inherit;
  color: var(--text-secondary);
  background: transparent;
  /* 用透明边框占位：切换时不会因为多出 2px 边框而跳动 */
  border: 0;
  border-bottom: 2px solid transparent;
  cursor: pointer;
  transition: color 0.12s, border-color 0.12s;
}

.share-tab:hover {
  color: var(--brand-600);
}

.share-tab.is-active {
  color: var(--brand-600);
  border-bottom-color: var(--brand-500);
  font-weight: 600;
}

.share-tab-icon {
  /* el-icon 用 font-size 定尺寸（内部 svg 是 1em × 1em） */
  font-size: 15px;
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
  box-shadow: 0 0 0 2px var(--brand-100);
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

/* 二维码占位（生成中 / 失败共用几何属性，只有颜色与图标不同）。 */
.share-qr-state {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 12px;
  font-size: var(--fs-xs);
  line-height: 1.5;
  color: var(--text-tertiary);
  text-align: center;
}

/* 失败态用 secondary 而非 tertiary：它需要被读到 */
.share-qr-state.is-failed {
  color: var(--text-secondary);
}

/* 本地编码通常几十毫秒，转圈多半一闪而过 —— 但不能省：没有它时用户看到空框，
   分不清「在算」与「坏了」。 */
.share-qr-spinner {
  width: 18px;
  height: 18px;
  border: 2px solid var(--ink-200);
  border-top-color: var(--brand-500);
  border-radius: 50%;
  animation: share-qr-spin 0.7s linear infinite;
}

@keyframes share-qr-spin {
  to {
    transform: rotate(360deg);
  }
}

/* 开启「减少动态效果」时不转，改成一个静态圆环（不删，否则又变成空框） */
@media (prefers-reduced-motion: reduce) {
  .share-qr-spinner {
    animation: none;
    border-top-color: var(--ink-200);
  }
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
  .play-content {
    padding: var(--sp-3);
    gap: var(--sp-3);
  }

  .share-row {
    flex-direction: column;
    /* 必须显式 stretch：column 下沿用的是基类 flex-start，交叉轴上子项会按内容宽收缩，
       .share-field 只剩 185px，链接只看得见 http://IP。 */
    align-items: stretch;
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

  /* pointer-events:none 是为了绕 iOS Safari 的坑：可聚焦元素字号 <16px 时聚焦会放大整页
     且失焦不缩回。提到 16px 又太笨（一行放不下半条链接），所以改成不可聚焦。 */
  .share-input {
    font-size: var(--fs-sm);
    pointer-events: none;
  }

  /* ---------- 窄屏：云台是独立 Tab，占满整张卡片 ---------- */
  .ptz-mobile-host {
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    padding: var(--sp-5) var(--sp-3);
  }

  /* 静态面板必须 position:relative —— 内部格子是 absolute，没有定位祖先会飞到页面左上角。
     尺寸 46~60px（高于 HIG 的 44pt），靠 --ptz-gap 拉开间距防误触 —— 是间距而不是无限放大。
     宽高由基类公式推导，这里只改定位方式与两个尺寸变量。 */
  .ptz-mobile-host .ptz-block {
    --ptz-cell: clamp(46px, 14vw, 60px);
    --ptz-gap: calc(var(--ptz-cell) * 0.14);
    position: relative;
    top: auto;
    right: auto;
    border-radius: 0;
  }

  /* 去掉灰底：手机的 :hover 一点就置上且不再取消，整个面板会永远挂着一块灰。
     选择器要比基类多一层，否则同权重下会被后加载的规则盖掉。 */
  .ptz-mobile-host .ptz-block,
  .ptz-mobile-host .ptz-block:hover {
    background: transparent;
  }

  /* 静态面板里方向键常显（无需 hover / 展开） */
  .ptz-mobile-host .ptz-cell {
    display: block;
  }

  /* 中心拖动柄在静态面板里无意义，必须隐藏。
     选择器要写成 .ptz-block .ptz-center —— 基类 .ptz-block:hover .ptz-cell 是 0,3,0，
     只写 .ptz-mobile-host .ptz-center（0,2,0）压不住，手机上一点就露出那个灰圆。 */
  .ptz-mobile-host .ptz-block .ptz-center,
  .ptz-mobile-host .ptz-block:hover .ptz-center,
  .ptz-mobile-host .ptz-block.is-open .ptz-center {
    display: none;
  }

  /* 浅灰底 + 内在描边，圆角按格子比例（粗边框 + 高饱和图标显得土） */
  .ptz-mobile-host .ptz-cell:not(.ptz-center) {
    display: flex;
    align-items: center;
    justify-content: center;
    color: var(--text-secondary);
    background: var(--bg-sunken);
    /* 用 inset 阴影当边框：不占盒尺寸，格子尺寸与位置计算严格对齐 */
    box-shadow: inset 0 0 0 1px rgba(15, 22, 33, 0.06);
    border-radius: calc(var(--ptz-cell) * 0.28);
    -webkit-tap-highlight-color: transparent;
  }

  /* 按下反馈：手机上 :active 是唯一能给的即时反馈，必须看得见 */
  .ptz-mobile-host .ptz-cell:not(.ptz-center):active {
    color: #fff;
    background: var(--brand-500);
    box-shadow: none;
  }

  .ptz-mobile-host .ptz-cell:not(.ptz-center):active .ptz-ico {
    transform: scale(1.1);
  }

  /* 图标跟着格子走（54px → 22px），比 0.5 倍更克制 */
  .ptz-mobile-host .ptz-ico {
    width: calc(var(--ptz-cell) * 0.42);
    height: calc(var(--ptz-cell) * 0.42);
  }
}

/*
 * 复制 / 降级 提示条。
 *
 * 固定底部居中、位于所有内容之上（含弹层不冲突：popover 的 z-index 是
 * 2000+，而这条提示通常出现在没有弹层的时候）。
 * pointer-events: none 保证它不挡任何点击 —— 连点两次「复制」不会因为
 * 提示条正好盖住按钮而失效。
 */
.play-toast {
  position: fixed;
  left: 50%;
  bottom: 32px;
  transform: translateX(-50%);
  z-index: 3000;
  max-width: min(90vw, 420px);
  padding: 9px 16px;
  font-size: var(--fs-sm);
  line-height: 1.5;
  color: #fff;
  text-align: center;
  background: rgba(8, 13, 20, 0.86);
  border-radius: 999px;
  box-shadow: 0 4px 16px rgba(15, 22, 33, 0.24);
  pointer-events: none;
}

/* 淡入淡出：1800ms 后消失，动画只占 180ms，不拖慢「已复制」的即时感 */
.toast-fade-enter-active,
.toast-fade-leave-active {
  transition: opacity 0.18s ease, transform 0.18s ease;
}

.toast-fade-enter-from,
.toast-fade-leave-to {
  opacity: 0;
  /* 入场时从下往上浮一点，出场时轻微下沉 */
  transform: translateX(-50%) translateY(6px);
}
</style>

<!--
  协议菜单的样式必须放在非 scoped 块里。

  el-popover 的 popper 是 teleport 到 <body> 的，元素上没有本组件的
  data-v 标记 —— scoped 里的选择器压根匹配不到（写了也不生效）。
  带 .share-proto-menu 前缀（见模板的 popper-class）限定作用域，
  不影响平台上其它弹层。
-->
<style>
.share-proto-menu .proto-menu {
  display: flex;
  flex-direction: column;
  gap: 2px;
  /* 抵消 popover 自带内边距，让每一行都铺满、点击区域更大 */
  margin: -4px -8px;
}

.share-proto-menu .proto-menu-item {
  display: block;
  width: 100%;
  padding: 7px 12px;
  font-family: inherit;
  font-size: 13px;
  color: var(--text-primary);
  text-align: left;
  background: transparent;
  border: 0;
  border-radius: 4px;
  cursor: pointer;
  transition: background-color 0.12s, color 0.12s;
}

.share-proto-menu .proto-menu-item:hover {
  color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
}

/* 当前项：加粗 + 品牌色，一眼看出现在用的是哪一档 */
.share-proto-menu .proto-menu-item.is-current {
  color: var(--el-color-primary);
  font-weight: 600;
}
</style>
