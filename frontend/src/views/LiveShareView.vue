<template>
  <div class="play-page" :class="{ 'is-embed': embedMode }">
    <!--
      顶部标题栏：对齐 CMS play.html 的 .navbar —— 高 50px、通栏、
      标题居中、白色加粗。CMS 用 #556677，这里换成我们的品牌主色 --brand-600。

      <p>嵌入模式（{@code aspect=fullscreen} 或 被 iframe 嵌入）时整条隐藏：
      宿主页面已经把「这是哪个摄像头」写在它自己的标题上了，
      这里再画一条顶栏只是白白占高度。
    -->
    <header v-if="!embedMode" class="play-header">
      <h1 class="play-title">{{ title }}</h1>
    </header>

    <main class="play-content">
      <!--
        播放区：CMS 是 16:9 的居中黑块。这里同样按 16:9，但用 max-width 限制
        最大宽度 —— 超宽屏上铺满会让画面过大、首屏看不到下方的分享面板。

        <p>嵌入模式下把宽度上限与间距全部取消，画面吃满整个视口。
      -->
      <div class="play-stage" :class="{ 'is-stretch': stretchMode }">
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

          <h3>为什么用 Teleport</h3>
          <p>手机竖屏的播放区只有 ~366×206，比云台块（150×220）<b>还矮</b> ——
          浮在画面上会盖攩近半个画面。所以窄屏时用 {@code Teleport}
          把这一块投到下方分享卡片右侧的宿主容器里（`.ptz-mobile-host`）；
          宽屏保持就地渲染，即浮在画面右上角。
          {@code Teleport} 的 {@code disabled} 为真时就地渲染，
          刚好用来做这种「同一个组件两处落子」，不用把模板写两遍。
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
            <!--
              中心是拖动手柄，与方向键分开渲染：它不响应云台指令。
              触摸设备没有 hover，这个圆同时充当「展开/收起方向键」的开关
              （点一下切展开态，按住拖动则只移动面板，见 startDrag/endDrag）。
              窄屏投到分享卡片右侧后是常显静态面板，这个手柄无意义，已隐藏。
            -->
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
        下半区卡片：分享 / 云台控制 两个面板。

        <p><b>宽屏</b>只有一个「分享」面板 —— 云台浮在画面右上角（更符合
        监控操作的肌肉记忆：看着画面转云台）。

        <p><b>窄屏</b>（≤640px）播放区只有 ~366×206，比云台块（150×220）
        还矮 —— 浮在画面上会盖掉近半个画面，浮到卡片旁边又会把分享内容
        挤成 240px 宽（输入框只看得见半截链接）。所以窄屏改成
        <b>Tab 切换</b>：一次只展示一个面板，两个都拿到满宽。

        <p><b>嵌入模式整块不渲染</b>：宿主页面只会把 iframe 当画面用，
        分享链接 / 二维码 / 嵌入代码对它毫无意义；而那块卡片至少占 400px 高，
        在 640×360 的 iframe 里直接超出一整屏，画面被挤得只剩顶部一条。

        <p><b>云台块反而保留</b>：它是浮在画面上的（默认只露一个半透明圆点，
        鼠标移入才展开），不占布局高度，而「能转云台」正是嵌入方最想要的能力
        之一。若宿主想自己做一套，同名接口 {@code /api/v1/share/ptz} 也可直接用。
      -->
      <div v-if="!embedMode" class="play-lower">
        <section class="share-card">
          <!--
            Tab 条。宽屏时只有「分享」一项 —— 保持改造前那个标签页的观感
            （底线 + 品牌色），不额外引入一个只有单项的怪异 Tab 条。
          -->
          <div class="share-tabs" role="tablist">
            <button
              class="share-tab"
              :class="{ 'is-active': activeTab === 'share' }"
              type="button"
              role="tab"
              :aria-selected="activeTab === 'share'"
              @click="activeTab = 'share'"
            >
              <!--
                图标用 Element Plus 的 Share。

                <p>原来这里是手写的两条 SVG path，其中一条的包围盒到
                {@code y=18.4}，而 viewBox 只有 16 —— 底部被裁掉，
                渲染出来是两段对不上的钩子，根本不像「分享」。
                {@code @element-plus/icons-vue} 已在 main.js 全量注册，
                用 el-icon 不增加任何额外依赖。
              -->
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
import { resolvePlayUrls, LIVE_DEFAULT_PROTOCOL, availableProtocols } from '@/player/resolvePlayUrls'

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

/* ---------------- 播放协议 ---------------- */

/**
 * 当前播放协议。
 *
 * <p>分享页必须提供切换入口：外部人员通过这个页面看画面，
 * 遇到 H.265 通道（FLV 播不了）或 UDP 被拦（WebRTC 连不上）时，
 * 除了换协议没有任何别的办法 —— 他们连平台后台都进不去。
 */
const proto = ref(LIVE_DEFAULT_PROTOCOL)

/**
 * 当前协议的显示名（控制栏按钮上那一段文字）。
 *
 * <p>从 {@code protoOptions} 里查而不是写死一个映射表：
 * 两份名字一旦不同步，就会出「菜单写 WS-FLV、按钮写 FLV」的错位。
 */
const protoShort = computed(() => {
  const hit = protoOptions.value.find((o) => o.value === proto.value)
  return hit ? hit.label : proto.value
})

/**
 * 可选的协议：<b>只列本浏览器真能播的</b>。
 *
 * <p>iPhone / iPad Safari 没有 {@code MediaSource}，flv.js 起不来 ——
 * 菜单里还摆着 WS-FLV / FLV 的话，用户点了只会得到一片黑屏
 * （实测iPhone 上就是这个现象）。{@code availableProtocols()} 按能力过滤，
 * 手机上自然只剩 WebRTC 与 HLS。
 */
const protoOptions = computed(() => availableProtocols())

/** 协议菜单是否展开（锚定在控制栏那个按钮上）。 */
const protoMenuVisible = ref(false)
/** 控制栏按钮元素（popover 虚拟锚点）。 */
const protoAnchorEl = ref(null)

/**
 * 控制栏按钮点击。
 *
 * <p>只负责把锚点交给 popover，<b>是否展开由 el-popover 自己管</b>：
 * {@code virtual-triggering} 会把 click 监听挂到锚点元素上自行 toggle。
 * 这里若也写 {@code visible = true}，第二次点击会变成「它 toggle 成 false、
 * 我又设回 true」，菜单就关不掉了。
 *
 * <p>例外是「第一次拿到锚点」那次点击 —— 监听要等 popover 重新渲染才挂上，
 * 那一次它收不到，所以手动开一次。
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
 * <p><b>为什么用 {@code frameElement} 而不是 {@code window.top !== window.self}</b>：
 * 两者语义相同，但访问 {@code window.top} 在<b>跨域</b>嵌入时会抛
 * {@code SecurityError}（同源策略），必须包 try/catch；
 * {@code window.frameElement} 跨域时返回 {@code null} 而不抛错 —— 但那样就
 * 分不清「嵌入在不同源」与「根本没嵌入」。
 * 所以两者都用：{@code frameElement} 能读到就直接判定，抛错/为 null 时再看
 * {@code top !== self}（跨域下这个比较是允许的，只是不能读 top 的属性）。
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

/**
 * 嵌入模式：只留播放画面，标题栏与分享面板全部去掉。
 *
 * <p>两种触发方式：
 * <ul>
 *   <li>被 iframe 嵌入 —— 宿主页面自己管标题与控件，本页画蛇添足只会挤压画面；</li>
 *   <li>显式传 {@code aspect=fullscreen} —— 对齐 CMS 的
 *       {@code play.html?...&aspect=fullscreen}。用于「直接新窗口打开当播放器」，
 *       此时没人会去点分享面板。</li>
 * </ul>
 */
const embedMode = computed(() => {
  if (inIframe.value) return true
  const aspect = String(route.query.aspect || '').trim().toLowerCase()
  return aspect === 'fullscreen' || aspect === 'full'
})

/**
 * 是否拉伸画面填满容器。
 *
 * <p>对齐 CMS 的 {@code stretch=yes}：监控现场常见「球机画面是 16:9、
 * 墙面电视墙格子却是方的」，等比显示会留黑边，值班室要求铺满。
 * 拉伸会让画面变形，所以只是<b>可选</b>，默认仍是等比（contain）。
 */
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
 * iframe 嵌入用的地址。
 *
 * <p><b>必须强制带上 {@code aspect=fullscreen}</b>：分享页默认形态是
 * 「标题栏 + 画面 + 一整块分享卡片」，那是给人用浏览器直接打开的；
 * 而 iframe 是固定尺寸的小窗口（通常 640×360），
 * 分享卡片本身就有 400 多像素高 —— 不加这个参数，别人嵌进去看到的
 * 是「顶部标题栏 + 一条画面」，下面全被截掉，等于不能用。
 *
 * <p>用 {@code URL} 对象改参数而不是字符串拼 {@code &aspect=fullscreen}：
 * 当前地址可能已经带了这个参数（在嵌入模式里再点复制），拼字符串会出现
 * 两个 {@code aspect=}，取哪个由各浏览器自行决定。{@code searchParams.set}
 * 是覆盖而不是追加，天然幂等。
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

/**
 * iframe 嵌入代码。
 *
 * <p>{@code allow="autoplay; fullscreen"} 是必需的：浏览器默认禁止 iframe 内
 * 自动播放与全屏，不带这两项时嵌到别人页面里点全屏没反应。
 * 宽高取 16:9 的 640×360，与 CMS 一致。
 *
 * <p>{@code title} 也给上：缺它时某些无障碍检查会报错，
 * 而且多标签页环境下浏览器需要它来区分框内容。
 */
const iframeCode = computed(() => {
  if (!embedUrl.value) return ''
  return `<iframe src="${embedUrl.value}" width="640" height="360" `
    + `title="视频直播" allowfullscreen `
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

/**
 * 复制到剪贴板，并给出提示。
 *
 * <h3>为什么要两段兜底，而不是「有 clipboard 就用它」</h3>
 *
 * <p>{@code navigator.clipboard} 存在 ≠ 能用。除了「非安全上下文（http+IP）
 * 下该 API 不存在」这个已知情况，还有两种更隐蔽的失败：
 * <ul>
 *   <li><b>权限被拒</b>：浏览器把它列为敏感权限，用户在站点设置里关掉后
 *       {@code writeText} 直接抛 {@code NotAllowedError}；</li>
 *   <li><b>文档未获得焦点</b>：iOS Safari 与部分 Chrome 版本要求调用时
 *       文档处于聚焦状态，从 iframe 或后台标签触发时必然失败。</li>
 * </ul>
 * 这两种情况下 API 是<b>存在</b>的，只是调用抛错 —— 早先的写法在
 * {@code catch} 里直接报「复制失败」，压根没试 {@code execCommand}，
 * 于是在这些环境里点复制永远失败（用户反馈「复制没有反馈」）。
 *
 * <p>现在：先试 clipboard，失败就试 execCommand，两个都失败才报错。
 *
 * @param {string} text 要复制的文本
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

/**
 * 轻量提示：不引 ElMessage（分享页想尽量少带依赖，避免弹层样式受主框架影响）。
 *
 * <p><b>必须渲染到模板里才有用</b> —— 这个 ref 之前定义了却没出现在
 * 模板中，于是 {@code copy()} 里的 {@code flash('已复制')} 只是改了个
 * 没人看的变量，用户点「复制」完全没有反馈。
 */
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
    proto: proto.value,
    /*
     * 控制栏里的「播放协议」按钮。分享页尤其需要它：
     * 外部人员进不了后台，换协议是他们唯一能自救的手段。
     */
    controlButtons: [{ id: 'proto', title: '播放协议', text: protoShort.value }],
    onControlButton,
    /*
     * 浏览器放不了所选协议时（手机上选 FLV），引擎会自动降级到能播的那条。
     * 这里把真实协议同步到菜单/按钮，并告诉用户发生了什么 ——
     * 否则他只会看到「画面出来了但按钮写着 FLV」，下次还会再选一遍。
     */
    onProtoChange: (used, wanted) => {
      proto.value = used
      if (wanted && wanted !== used) {
        const from = protoOptions.value.find((o) => o.value === used)
        flash(`当前浏览器不支持该协议，已自动切换到 ${from ? from.label : used}`)
      }
    },
    onFatal: (msg) => { notice.value = msg || '播放失败' },
    // 不提示「请刷新页面」：这里能换协议，换一条通路往往就好了
    onStall: () => { notice.value = '画面未就绪，可尝试切换播放协议' }
  })
  syncProtoLabel()
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

/**
 * 该类型是否支持云台控制。
 *
 * <p>0（未知）与 3（固定枪机）不支持：前者设备没报，后者物理上就不能转。
 * 与通道管理页的 {@code supportsPtz} 保持同一套判据 ——
 * 两处不一致会出现「列表里显示不可控、分享页却弹出方向盘」。
 */
function supportsPtz(type) {
  return type === 1 || type === 2 || type === 4
}

/**
 * 云台面板是否可用。
 *
 * <p>三个条件缺一不可：
 * <ul>
 *   <li>拿到了通道信息，且没有「设备离线 / 通道不存在」这类提示；</li>
 *   <li>设备在线 —— 离线时指令发不出去，方向盘摆着只会让人白点；</li>
 *   <li><b>通道确实是球机/半球/遥控枪机</b>。这一条之前漏了：判据只看
 *       「在线 + 无提示」，于是固定枪机的分享页也弹出了方向盘
 *       （用户反馈「非球机也出来了控制」）。</li>
 * </ul>
 */
const ptzReady = computed(() => {
  const d = info.value
  if (!d || d.deviceOnline !== true || d.notice) return false
  return supportsPtz(d.ptzType)
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

/* ---------------- 面板拖动 / 展开 ---------------- */

/**
 * 方向键是否展开。
 *
 * <p>桌面端靠 {@code :hover} 展开，这个状态没用；触摸设备没有 hover，
 * 必须有一个显式开关，否则要么永远点不到云台、要么整块十字常显
 * （150×220 比竖屏里的 16:9 播放区还高，会盖掉近半个画面）。
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
  /*
   * 窄屏下云台块是「分享卡片旁边的静态面板」，不在画面上浮动，
   * 没有可拖动的位置 —— 而且它的父节点已变成宿主 div，
   * 用那里的矩形做边界夹紧会把面板锁在原地。直接不启用。
   */
  if (isMobile.value) return
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
  dragStartPt = { x: ev.clientX, y: ev.clientY }
  dragMoved = false
  /*
   * 用 Pointer Events 而不是 mouse 事件：一套代码同时覆盖鼠标与触摸。
   * 触摸下浏览器会把 touch 转成模拟 mouse 事件，但滚动/长按会打断它，
   * 拖动时松手可能收不到 mouseup，面板就「粘」在指针上。
   */
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
  /*
   * 夹在容器内：不夹的话可以把面板拖到画面外，之后再也找不回来
   * （它自己是唯一的拖动入口）。
   */
  const nx = Math.min(Math.max(0, ev.clientX - stageRect.left - x), Math.max(0, stageRect.width - w))
  const ny = Math.min(Math.max(0, ev.clientY - stageRect.top - y), Math.max(0, stageRect.height - h))
  ptzPos.value = { x: Math.round(nx), y: Math.round(ny) }
}

function endDrag() {
  /*
   * 没有位移 = 点了一下中心圆 → 切换方向键展开态。
   * 只有在真的按下过（dragOffset 非空）时才判定，避免卸载时的空调用翻转状态。
   */
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
  /*
   * 云台兜底：在窗口层面监听 mouseup 强制停止转向。
   *
   * <p>用户按住方向键后把鼠标拖出窗口再松开，元素上的 mouseup 收不到，
   * 设备就会一直转 —— 必须在 window 上补一层。这是「控件一旦失灵就是
   * 机械损坏」的场景，宁可多挂一个监听。
   */
  window.addEventListener('mouseup', stopPtz)
  window.addEventListener('blur', stopPtz)
  /*
   * 窄屏判断：初始 false（见 isMobile 声明处的注释），这里立即纠正，
   * 并监听后续的横竖屏切换/窗口缩放。
   */
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
  .play-content {
    padding: var(--sp-3);
    gap: var(--sp-3);
  }

  .share-row {
    flex-direction: column;
    /*
     * 关键：flex-direction:column 下 align-items 仍是基类的 flex-start，
     * 交叉轴（水平）上子项会按内容宽收缩 —— .share-field 于是只剩 185px，
     * 分享链接只看得见「http://IP」，看起来像样式坏了。
     */
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

  /*
   * 分享链接的字号。
   *
   * <p><b>为什么要配 {@code pointer-events: none}</b>：iOS Safari 在
   * 「可聚焦」表单元素的字号 < 16px 时，聚焦瞬间会<b>自动放大整页</b>，
   * 而且失焦后不缩回去。上一版为了绕这个坑把字号提到 16px，
   * 结果链接看起来又大又笨（一行只能放下半条）。
   *
   * <p>这里反过来解：字号降到 13px（与桌面一致），同时让它
   * <b>不可聚焦</b> —— 不聚焦就不会触发缩放。代价是不能长按选中，
   * 但右侧就有一键复制按钮，分享链接也从来不需要只挑其中几个字。
   */
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

  /*
   * 静态面板：{@code position: relative} 而不是 static —— 内部格子是
   * position:absolute，没有定位祖先会飞到页面左上角。
   *
   * <h3>尺寸</h3>
   * 上一版取 64~104px（390px 屏上 86px），按下去确实准，
   * 但六个大圆角方块堆在一起显得笨重。这里收到 46~60px
   * （390px 屏 → 54px），仍高于 Apple HIG 的 44pt 最小可点尺寸，
   * 同时靠 {@code --ptz-gap} 拉开间距 —— <b>防误触靠的是间距，不是无限放大</b>：
   * 两个 54px 的格子中间隔着 7px，指腹落在哪一格很清楚；
   * 而没有间距的 86px 方格，反而容易按到边界上。
   */
  .ptz-mobile-host .ptz-block {
    --ptz-cell: clamp(46px, 14vw, 60px);
    --ptz-gap: calc(var(--ptz-cell) * 0.14);
    /*
     * 只改「定位方式 + 两个尺寸变量」。宽高由基类的公式推导
     * （--ptz-gap 已参与计算），这里再写一遍只会写出不一致的值。
     */
    position: relative;
    top: auto;
    right: auto;
    border-radius: 0;
  }

  /*
   * 去掉整块的灰底。
   *
   * <p>基类的 {@code .ptz-block:hover} 是给<b>画面上的浮层</b>用的：
   * 鼠标移入时把整块染灰，提示「这块可以操作」。
   * 但窄屏下这里是卡片里的静态面板，而且手机浏览器点一下就会置上
   * {@code :hover} 且不再取消 —— 结果整个面板永远挂着一块灰底，
   * 看起来像「选中了」或者「样式坏了」。
   *
   * <p>选择器要写成 {@code .ptz-mobile-host .ptz-block:hover}（比基类多一层），
   * 否则同权重下会被基类后加载的规则盖掉。
   */
  .ptz-mobile-host .ptz-block,
  .ptz-mobile-host .ptz-block:hover {
    background: transparent;
  }

  /* 静态面板里方向键常显（无需 hover / 展开） */
  .ptz-mobile-host .ptz-cell {
    display: block;
  }

  /*
   * 中心拖动柄在这里没有意义（面板是静态的，不需要挪位置），要藏掉。
   *
   * <p>选择器必须写成 {@code .ptz-block .ptz-center} 这一级 ——
   * 基类的 {@code .ptz-block:hover .ptz-cell} 是 0,3,0，
   * 只写 {@code .ptz-mobile-host .ptz-center}（0,2,0）压不住它，
   * 手机上一点就露出那个灰圆（正是用户反馈的那块灰）。
   */
  .ptz-mobile-host .ptz-block .ptz-center,
  .ptz-mobile-host .ptz-block:hover .ptz-center,
  .ptz-mobile-host .ptz-block.is-open .ptz-center {
    display: none;
  }

  /*
   * 按键外观：不要粗边框 + 高饱和图标（上一版就是这两样显得土）。
   * 换成「浅灰底 + 一层内在描边」，圆角按格子比例走，看着更像一个控件。
   */
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
