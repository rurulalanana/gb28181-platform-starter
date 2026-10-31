<template>
  <div class="frame-test">
    <header class="ft-head">
      <h1>iframe 嵌入测试</h1>
      <p class="ft-sub">
        对齐 CMS 的 <code>play.html?serial=&amp;code=&amp;aspect=&amp;stretch=</code>。
        左边是参数，右边是实时渲染的 iframe —— 它怎么显示，别人嵌到站里就怎么显示。
      </p>
    </header>

    <!-- ---------------- 参数区 ---------------- -->
    <section class="ft-panel">
      <div class="ft-row">
        <label class="ft-label">通道</label>
        <div class="ft-field">
          <el-select
            v-model="picked"
            filterable
            clearable
            placeholder="从已加载的通道里选一个（需登录）"
            style="width: 100%"
            @change="onPick"
          >
            <el-option
              v-for="c in channels"
              :key="c.deviceId + '/' + c.id"
              :label="`${c.name || c.id}（${c.id}）`"
              :value="c.deviceId + '/' + c.id"
            />
          </el-select>
          <div v-if="!channels.length" class="ft-hint">
            没读到通道列表（未登录或没有设备）。下面手填 serial / code 也能测。
          </div>
        </div>
      </div>

      <div class="ft-row">
        <label class="ft-label">serial</label>
        <div class="ft-field">
          <el-input v-model="serial" placeholder="设备编码，如 34020000002000000006" />
        </div>
      </div>

      <div class="ft-row">
        <label class="ft-label">code</label>
        <div class="ft-field">
          <el-input v-model="code" placeholder="通道编码，如 34020000001310000002" />
        </div>
      </div>

      <div class="ft-row">
        <label class="ft-label">aspect</label>
        <div class="ft-field">
          <el-radio-group v-model="aspect">
            <el-radio-button value="">默认（带标题栏与分享面板）</el-radio-button>
            <el-radio-button value="fullscreen">fullscreen（只留画面）</el-radio-button>
          </el-radio-group>
        </div>
      </div>

      <div class="ft-row">
        <label class="ft-label">stretch</label>
        <div class="ft-field">
          <el-radio-group v-model="stretch">
            <el-radio-button value="">no（等比，留黑边）</el-radio-button>
            <el-radio-button value="yes">yes（拉伸铺满）</el-radio-button>
          </el-radio-group>
        </div>
      </div>

      <div class="ft-row">
        <label class="ft-label">iframe 尺寸</label>
        <div class="ft-field ft-sizes">
          <el-input v-model="w" style="width: 110px" placeholder="宽">
            <template #append>px</template>
          </el-input>
          <span class="ft-times">×</span>
          <el-input v-model="h" style="width: 110px" placeholder="高">
            <template #append>px</template>
          </el-input>
          <el-radio-group v-model="sizePreset" size="small" @change="applyPreset">
            <el-radio-button value="640x360">640×360</el-radio-button>
            <el-radio-button value="960x540">960×540</el-radio-button>
            <el-radio-button value="400x400">400×400（方形）</el-radio-button>
          </el-radio-group>
        </div>
      </div>

      <div class="ft-row ft-actions">
        <el-button type="primary" :disabled="!url" @click="apply">
          <el-icon><Refresh /></el-icon> 重新加载
        </el-button>
        <el-button :disabled="!url" @click="openNewTab">
          <el-icon><TopRight /></el-icon> 新窗口打开
        </el-button>
        <el-button :disabled="!url" @click="copy(frameCode, '嵌入代码')">
          <el-icon><DocumentCopy /></el-icon> 复制 iframe
        </el-button>
        <el-button :disabled="!url" @click="copy(url, '地址')">
          <el-icon><Link /></el-icon> 复制地址
        </el-button>
      </div>
    </section>

    <!-- ---------------- 地址 / 代码 ---------------- -->
    <section class="ft-panel">
      <div class="ft-code-head">访问地址</div>
      <div class="ft-code">{{ url || '（填好 serial 与 code 后生成）' }}</div>

      <div class="ft-code-head ft-mt">iframe 嵌入代码</div>
      <div class="ft-code">{{ frameCode || '（同上）' }}</div>
    </section>

    <!-- ---------------- 实时渲染 ---------------- -->
    <section class="ft-panel">
      <div class="ft-code-head">
        实时渲染（{{ w }}×{{ h }}）
        <span class="ft-tip">这是一个真实的 &lt;iframe&gt;，不是模拟</span>
      </div>

      <div class="ft-preview">
        <iframe
          v-if="frameSrc"
          ref="frameEl"
          :src="frameSrc"
          :width="w"
          :height="h"
          allowfullscreen
          allow="autoplay; fullscreen; microphone; encrypted-media"
          style="border: 0"
        />
        <div v-else class="ft-empty">填好 serial 与 code 后这里会出现画面</div>
      </div>
    </section>

    <!-- ---------------- 说明 ---------------- -->
    <section class="ft-panel ft-notes">
      <div class="ft-code-head">自检清单</div>
      <ol>
        <li>
          <b>画面出得来吗</b>：出不来先看 iframe 里的提示文字。若是
          「缺少 serial 参数」，说明参数没拼上；若是「获取通道信息失败」，
          说明后端 <code>/api/v1/share/live</code> 拿不到该通道。
        </li>
        <li>
          <b>控制栏点得动吗</b>：浏览器要求 iframe 带
          <code>allow="autoplay"</code> 才能自动起播、带
          <code>allowfullscreen</code> 才能全屏。缺这两项时表现为
          「画面空白 / 点全屏没反应」。
        </li>
        <li>
          <b>跨域有问题吗</b>：本页与 iframe 同源，所以测不出跨域限制。
          真正嵌到别人的域名下时，flv.js 走 WebSocket 不受同源限制，
          但<b>摄像头推流的媒体端口（ZLM 8081）必须对方浏览器能直连</b> ——
          这是最常见的「本地好、嵌出去白屏」原因。
        </li>
        <li>
          <b>aspect=fullscreen 生效吗</b>：勾上后 iframe 里应当只剩画面，
          没有顶部标题栏、没有下方的分享卡片。
        </li>
        <li>
          <b>stretch=yes 生效吗</b>：把 iframe 改成方形（400×400）后对比 ——
          no 时画面上下留黑边，yes 时铺满但画面会被拉扁。
        </li>
      </ol>
    </section>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'

/**
 * iframe 嵌入测试页。
 *
 * <h2>为什么要有这一页</h2>
 *
 * <p>分享页的 iframe 嵌入代码是给<b>别人</b>用的，但「别人嵌进去长什么样」
 * 在自己的主框架里根本看不到 —— 主框架有侧栏、有底部留白，
 * 而 iframe 是一个固定尺寸的小窗口，两者的显示条件完全不同（尤其是
 * {@code aspect=fullscreen} 与 {@code stretch=yes} 这类只在小尺寸下才暴露问题
 * 的参数）。
 *
 * <p>所以这里用一个<b>真实</b>的 {@code <iframe>}（不是模拟、不是 div 套壳）
 * 按用户填的尺寸渲染同一份地址，看到什么就是别人看到的什么。
 *
 * <h2>参数对齐 CMS</h2>
 *
 * <p>CMS 的地址是
 * {@code play.html?serial=…&code=…&aspect=fullscreen&stretch=yes}，
 * 本页支持同名的四个参数，语义见 {@code LiveShareView} 里的注释。
 */
const channels = ref([])
const picked = ref('')

const serial = ref('')
const code = ref('')
const aspect = ref('fullscreen')
const stretch = ref('')

const w = ref(640)
const h = ref(360)
const sizePreset = ref('640x360')

/** 实际用于 iframe 的地址（只有点「重新加载」或初次进入才变）。 */
const frameSrc = ref('')

/**
 * 组装目标地址。
 *
 * <p>用 {@code location.origin} 而不是后端给的地址：iframe 要嵌的就是
 * <b>当前访问的这个入口</b> —— 平台可能同时有内网/公网两个域名，
 * 用后端拼的地址会把内网用户引到公网（或反之）。
 */
const url = computed(() => {
  if (typeof window === 'undefined') return ''
  const s = serial.value.trim()
  const c = code.value.trim()
  if (!s || !c) return ''
  const q = new URLSearchParams({ serial: s, code: c })
  if (aspect.value) q.set('aspect', aspect.value)
  if (stretch.value) q.set('stretch', stretch.value)
  return `${window.location.origin}/play?${q.toString()}`
})

/** 生成的嵌入代码（与分享页里「iframe」那一行同样的写法，含 title）。 */
const frameCode = computed(() => {
  if (!url.value) return ''
  return `<iframe src="${url.value}" width="${w.value}" height="${h.value}" `
    + `title="视频直播" allowfullscreen `
    + `allow="autoplay; fullscreen; microphone;" style="border:0"></iframe>`
})

/* ---------------- 交互 ---------------- */

/** 选中通道后回填设备/通道编码。 */
function onPick(v) {
  if (!v) return
  const [s, c] = v.split('/')
  serial.value = s || ''
  code.value = c || ''
}

/** 应用预设尺寸。 */
function applyPreset(v) {
  const [pw, ph] = String(v).split('x').map(Number)
  if (pw > 0 && ph > 0) {
    w.value = pw
    h.value = ph
  }
}

/** 预览区里的 iframe 元素（重载时要用）。 */
const frameEl = ref(null)

/**
 * 重新加载预览。
 *
 * <h3>尺寸变了怎么办</h3>
 * iframe 是普通元素，宽高是响应式的，改宽高会立即重排，<b>不需要重载</b>。
 *
 * <h3>参数变了：优先 contentWindow.reload()
 * </h3>
 * 先改 src 再让文档自己 reload —— 而不是用 {@code :key} 把整个 iframe
 * 销毁重建。两者看着一样，但实测差别很大：
 * 反复销毁 iframe 时，旧文档里的 {@code RTCPeerConnection} 是被浏览器
 * <b>直接丢弃</b>的（页面卸载不会走 Vue 的卸载钩子，没有 stop()），
 * ZLM 那侧要等 ICE 超时（约 30 秒）才释放这个读者；
 * 连着点几次「重新加载」，新旧对端就堆在一起，新对端拿得到轨道
 * （{@code tracks=[video:live]}）却收不到任何帧（{@code readyState=0}），
 * 12 秒后报「WebRTC 协商超时」—— 实测踩到。
 *
 * <p>同源时 {@code contentWindow.location.replace()} 会让旧文档走完整的
 * 卸载流程，连接干净释放。跨域时读不到 contentWindow，退回改 src。
 */
function apply() {
  const next = url.value
  const same = next === frameSrc.value
  if (same && frameEl.value) {
    // 地址没变：直接让文档重载（比换 src 更接近「刷新一下」）
    try {
      frameEl.value.contentWindow.location.reload()
      return
    } catch (e) {
      // 跨域读不到 contentWindow：走下面的 src 重设
    }
  }
  frameSrc.value = next
  /*
   * 地址变了但只想「换内容」不想「换元素」：直接改 src。
   * 保留 key 只在首次挂载时用 —— 之后不再 bump，避免销毁重建。
   */
  if (frameEl.value && next) {
    try {
      frameEl.value.contentWindow.location.replace(next)
    } catch (e) {
      frameEl.value.src = next
    }
  }
}

function openNewTab() {
  if (url.value) window.open(url.value, '_blank', 'noopener')
}

/** 复制文本（带非安全上下文兜底，http+IP 下 navigator.clipboard 不可用）。 */
async function copy(text, what) {
  if (!text) return
  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(text)
      ElMessage.success(`已复制${what}`)
      return
    }
  } catch (e) {
    // 权限被拒：走兜底
  }
  try {
    const ta = document.createElement('textarea')
    ta.value = text
    ta.style.position = 'fixed'
    ta.style.opacity = '0'
    document.body.appendChild(ta)
    ta.select()
    const ok = document.execCommand('copy')
    ta.remove()
    if (ok) ElMessage.success(`已复制${what}`)
    else ElMessage.warning('复制失败，请手动选中')
  } catch (e) {
    ElMessage.warning('复制失败，请手动选中')
  }
}

/* ---------------- 初始化 ---------------- */

onMounted(async () => {
  // 先给一组可用的默认值（生产环境上这两个通道实测可播）
  serial.value = '34020000002000000006'
  code.value = '34020000001310000002'
  apply()

  /*
   * 通道列表只是「省得手抄编码」的便利功能：本页可能未登录
   * （它是排查工具，常常在别人机器上打开），拿不到就静默降级。
   *
   * <p><b>必须用原生 fetch 而不是 {@code deviceApi.channelList}</b>：
   * 共享的 http 封装在收到 {@code code=401} 时会
   * {@code router.push('/login')} —— 未登录打开本页就会被踢去登录页，
   * 完全失去「免登录排查工具」的意义（实测踩到，本页反复跳登录）。
   * 用原生 fetch 自己判，失败就当作没有列表。
   */
  try {
    const res = await fetch('/api/v1/device/channellist?start=0&limit=200')
    const body = await res.json().catch(() => null)
    if (body && body.code === 0) {
      const d = body.data || {}
      channels.value = d.channelList || d.list || []
    }
  } catch (e) {
    channels.value = []
  }
})
</script>

<style scoped>
.frame-test {
  max-width: 1180px;
  margin: 0 auto;
  padding: var(--sp-5) var(--sp-4) var(--sp-8);
}

.ft-head h1 {
  margin: 0 0 6px;
  font-size: 20px;
  font-weight: 700;
}

.ft-sub {
  margin: 0 0 var(--sp-4);
  font-size: var(--fs-sm);
  color: var(--text-secondary);
  line-height: 1.6;
}

.ft-sub code,
.ft-notes code {
  padding: 1px 4px;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 12px;
  background: var(--bg-sunken);
  border-radius: 3px;
}

.ft-panel {
  padding: var(--sp-4) var(--sp-5);
  margin-bottom: var(--sp-4);
  background: var(--bg-surface);
  border-radius: var(--radius-sm, 6px);
  box-shadow: 0 1px 2px rgba(15, 22, 33, 0.08);
}

.ft-row {
  display: flex;
  align-items: flex-start;
  gap: var(--sp-4);
  padding: 6px 0;
}

.ft-label {
  flex: none;
  width: 84px;
  padding-top: 7px;
  font-size: var(--fs-sm);
  color: var(--text-secondary);
  text-align: right;
}

.ft-field {
  flex: 1;
  min-width: 0;
}

.ft-hint {
  margin-top: 4px;
  font-size: var(--fs-xs);
  color: var(--text-tertiary);
}

.ft-sizes {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}

.ft-times {
  color: var(--text-tertiary);
}

.ft-actions {
  gap: 8px;
  padding-top: var(--sp-2);
  /* 与上面的 label 列对齐 */
  padding-left: calc(84px + var(--sp-4));
}

.ft-code-head {
  display: flex;
  align-items: baseline;
  gap: 8px;
  margin-bottom: 6px;
  font-size: var(--fs-sm);
  font-weight: 600;
  color: var(--text-secondary);
}

.ft-tip {
  font-weight: 400;
  color: var(--text-tertiary);
}

.ft-mt {
  margin-top: var(--sp-4);
}

/*
 * 地址与代码块要能一键选中：用 pre + 可横向滚动，
 * 而不是 input —— 长 URL 在 input 里只能看到尾巴，复制前核对不了。
 */
.ft-code {
  padding: 10px 12px;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 12px;
  line-height: 1.7;
  color: var(--text-secondary);
  background: var(--bg-sunken);
  border-radius: 4px;
  overflow-x: auto;
  white-space: pre-wrap;
  word-break: break-all;
}

/*
 * 预览区：给个深色底 + 虚线框，让 iframe 的边界一眼可见 ——
 * 排查「画面没铺满」时必须能分清「是 iframe 里的黑边」还是「iframe 本身小」。
 */
.ft-preview {
  display: flex;
  justify-content: center;
  padding: var(--sp-4);
  background: repeating-conic-gradient(#f2f4f7 0% 25%, #ffffff 0% 50%) 50% / 16px 16px;
  border: 1px dashed var(--ink-300);
  border-radius: 4px;
  overflow: auto;
}

.ft-preview iframe {
  display: block;
  background: #000;
  box-shadow: 0 2px 10px rgba(15, 22, 33, 0.18);
}

.ft-empty {
  padding: var(--sp-6);
  font-size: var(--fs-sm);
  color: var(--text-tertiary);
}

.ft-notes ol {
  margin: 0;
  padding-left: 20px;
  font-size: var(--fs-sm);
  line-height: 1.9;
  color: var(--text-secondary);
}

@media (max-width: 720px) {
  .ft-label {
    width: auto;
    padding-top: 0;
    text-align: left;
  }

  .ft-row {
    flex-direction: column;
    align-items: stretch;
    gap: 6px;
  }

  .ft-actions {
    padding-left: 0;
  }
}
</style>
