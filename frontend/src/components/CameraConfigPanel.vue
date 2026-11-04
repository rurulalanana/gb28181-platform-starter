<template>
  <!--
    相机参数面板：完全由后端能力位驱动，前端不硬编码任何参数名。

    后端 /dahua/cameraconfig/capabilities 返回每一项的
    key / label / group / type / options / min / max / available / value，
    本组件按 group 分块、按 type 渲染滑块 / 开关 / 下拉。
    换型号或后端加参数时前端一行都不用改。
  -->
  <div class="cam-config">
    <!-- 加载中 -->
    <div v-if="loading" class="cam-hint">正在读取设备参数能力…</div>

    <!-- 读失败：明确说原因，不装作「没有参数」 -->
    <div v-else-if="loadError" class="cam-hint is-error">
      <span>{{ loadError }}</span>
      <el-button size="small" text @click="load()">重试</el-button>
    </div>

    <!-- 成功但一个可用项都没有：说明该型号这些字段都不在配置里 -->
    <el-empty
      v-else-if="!groups.length"
      :image-size="56"
      description="该型号没有可配置的图像参数（配置项里找不到任何已知字段）"
    />

    <template v-else>
      <!--
        二级分组：一级是 PtzPanel 的功能页（云台/预置位/设备/相机/高级），
        这里把「相机」这一页再按参数分组切开。

        为什么必须切：这台型号 10 组 41 项参数，平铺会把预览弹窗一路撑高，
        用户得滚整个弹窗才能摸到底部的「保存」。

        为什么不用 el-tabs：右栏实测只有 321px 宽，10 个两字标签排下来要 460px，
        溢出的部分既没有滚动箭头也点不到（Element Plus 在隐藏状态下挂载时
        测不到宽度，不进入可滚动模式）。所以这里用会换行的分段按钮，
        所有分组恒定可见，也不依赖库的溢出行为。
      -->
      <div class="cam-groups">
        <button
          v-for="g in groups"
          :key="g.name"
          type="button"
          class="cam-group-chip"
          :class="{ 'is-active': g.name === activeGroup }"
          @click="activeGroup = g.name"
        >
          {{ g.name }}
        </button>
        <button
          v-if="unsupported.length"
          type="button"
          class="cam-group-chip is-muted"
          :class="{ 'is-active': activeGroup === GROUP_UNSUPPORTED }"
          @click="activeGroup = GROUP_UNSUPPORTED"
        >
          不支持 {{ unsupported.length }}
        </button>
      </div>

      <!-- 内容区高度固定，超出在内部滚动；「保存」永远贴在底部可见 -->
      <div class="cam-pane" ref="paneRef">
        <template v-if="activeGroup === GROUP_UNSUPPORTED">
          <p class="cam-unsupported">{{ unsupported.join('、') }}</p>
        </template>
        <template v-else>
          <!--
            只渲染「当前该显示」的项：带 dependsOn 的参数要等上级取到指定值才出现。
            与设备 Web 界面一致 —— 曝光模式选手动才出现快门/增益/光圈，
            白平衡选手动才出现红蓝增益。不隐藏的话自动模式下摆着一堆无效输入框。
          -->
          <div class="cam-row" v-for="c in visibleItems" :key="c.key + ':' + formVersion">
            <label class="cam-label" :title="c.key">{{ c.label }}</label>

            <!-- 数值：滑块 + 数字，两者双向同步 -->
            <div v-if="c.type === 'NUMBER'" class="cam-control">
              <el-slider
                v-model="draft[c.key]"
                :min="c.min ?? 0"
                :max="c.max ?? 100"
                :step="1"
                :disabled="busy || disabled"
                size="small"
                class="cam-slider"
              />
              <span class="cam-value">{{ draft[c.key] }}</span>
            </div>

            <!-- 布尔：开关 -->
            <div v-else-if="c.type === 'BOOLEAN'" class="cam-control">
              <el-switch
                v-model="draft[c.key]"
                :disabled="busy || disabled"
                size="small"
              />
            </div>

            <!-- 范围：两个数字输入框（最小 - 最大），对应设备的「范围」控件 -->
            <div v-else-if="c.type === 'RANGE'" class="cam-control cam-range">
              <el-input-number
                v-model="draft[c.key][0]"
                :min="c.min ?? 0"
                :max="c.max ?? 999999"
                :controls="false"
                :disabled="busy || disabled"
                size="small"
                class="cam-range-input"
                @change="(v) => clampRangeLow(c, v)"
              />
              <span class="cam-range-sep">-</span>
              <el-input-number
                v-model="draft[c.key][1]"
                :min="c.min ?? 0"
                :max="c.max ?? 999999"
                :controls="false"
                :disabled="busy || disabled"
                size="small"
                class="cam-range-input"
                @change="(v) => clampRangeHigh(c, v)"
              />
              <span v-if="c.range" class="cam-range-hint">{{ c.range }}</span>
            </div>

            <!-- 枚举：有 options 用下拉；没有（如型号自定义取值）用输入框 -->
            <div v-else class="cam-control">
              <el-select
                v-if="c.options && c.options.length"
                v-model="draft[c.key]"
                :disabled="busy || disabled"
                size="small"
                class="cam-select"
              >
                <el-option
                  v-for="o in c.options"
                  :key="o.value"
                  :label="o.label"
                  :value="o.value"
                />
              </el-select>
              <el-input
                v-else
                v-model="draft[c.key]"
                :disabled="busy || disabled"
                size="small"
                class="cam-select"
                :placeholder="c.range || '型号自定义'"
              />
            </div>
          </div>

          <div v-if="!visibleItems.length" class="cam-hint">该分组没有可调参数</div>
        </template>
      </div>

      <div class="cam-footer">
        <el-button size="small" :disabled="busy || !dirty" @click="reset">还原</el-button>
        <el-button
          size="small"
          type="primary"
          :loading="busy"
          :disabled="!dirty"
          @click="save"
        >
          保存
        </el-button>
      </div>

      <!--
        上次保存被设备拒绝的参数。必须显式展示：
        大华写配置返回 true 不代表生效，不说清楚用户会以为保存成功了。
      -->
      <div v-if="rejected.length" class="cam-rejected">
        <div class="cam-rejected-head">以下参数已下发但设备没有应用：</div>
        <ul>
          <li v-for="r in rejected" :key="r.key">
            {{ r.label }}<span class="cam-rejected-val">（设备仍为 {{ r.value }}）</span>
          </li>
        </ul>
        <div class="cam-rejected-note">
          该型号可能把这些项做成了只读，需要在设备 Web 界面设置。
        </div>
      </div>
    </template>
  </div>
</template>

<script setup>
import { computed, nextTick, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { dahuaApi } from '@/api'

const props = defineProps({
  /** 设备编码 */
  serial: { type: String, default: '' },
  /** 通道编码 */
  code: { type: String, default: '' },
  /** 外部禁用（未播放 / 无权限） */
  disabled: { type: Boolean, default: false }
})

/** 能力位原始列表（后端返回的完整数组，含不支持项）。 */
const caps = ref([])
const loading = ref(false)
const busy = ref(false)
const loadError = ref('')
/** 表单草稿：key → 当前编辑值。 */
const draft = reactive({})
/** 上次从设备读到的值，用于「还原」与 dirty 判断。 */
const baseline = reactive({})
/**
 * 表单版本号：每次「重读」或「还原」后自增，用作行的 key。
 *
 * <p><b>为什么需要</b>：{@code el-input-number} 把当前值缓存在组件内部，
 * 靠监听 {@code modelValue} 同步。实测只改数据时它并不总能跟上 ——
 * 点「还原」后输入框还显示旧值（数据已经变回去了），
 * 用户以为没生效，再按保存就把旧值又发一遍。
 *
 * <p>换 key 会强制重新挂载，控件从当前数据初始化，显示必然正确。
 * 这两个动作部是低频操作，重建开销可以忽略。
 */
const formVersion = ref(0)
/** 上次保存被设备拒绝的项。 */
const rejected = ref([])
/** 「不支持」那一页的保留名（不会与后端分组名冲突）。 */
const GROUP_UNSUPPORTED = '__unsupported'
/** 当前二级分组页（= 后端给的 group 名）。 */
const activeGroup = ref('')
/** 滚动容器（重读后要还原滚动位置，见 reloadKeepingScroll）。 */
const paneRef = ref(null)

/**
 * 按 group 分块，只保留 available 的项。
 *
 * <p>分组顺序按后端 {@code DahuaCameraConfig.GROUPS} 的声明顺序，
 * 不按返回数组顺序 —— 返回顺序是参数表顺序，分组顺序是设计意图，两者不一定一致。
 *
 * <p>{@code hidden} 的项跳过：它们是组合参数的成员字段（如 flip/mirror/rotate90），
 * 由「视角」下拉代表，单独再摆三个开关既重复又允许配出矛盾状态。
 */
const groups = computed(() => {
  const order = []
  const map = new Map()
  for (const c of caps.value) {
    if (!c.available || c.hidden) continue
    const name = c.group || '其他'
    if (!map.has(name)) {
      map.set(name, [])
      order.push(name)
    }
    map.get(name).push(c)
  }
  return order.map((name) => ({ name, items: map.get(name) }))
})

/** 不支持的参数名（只列 label，用于告诉用户「这台没有」）。 */
const unsupported = computed(() =>
  caps.value.filter((c) => !c.available && !c.hidden).map((c) => c.label)
)

/** 当前分组下的参数项。 */
const activeItems = computed(() => {
  const g = groups.value.find((x) => x.name === activeGroup.value)
  return g ? g.items : []
})

/**
 * 上级参数的当前值是否满足依赖条件。
 *
 * <p>{@code dependsValues} 为空表示「只看上级是否开启」——用于开关类上级
 * （2D/3D 降噪关掉时隐藏等级滑块）；非空则要求值在列表里
 * （曝光模式=快门优先/手动 才显示快门）。
 *
 * <p><b>会递归检查上级自己是否可见</b>：链路可以是三层
 * （曝光模式 → 快门 → 快门范围）。只看直接上级的话，
 * 用户先把快门设成「自定义」、再把曝光模式切回「自动」，
 * 快门下拉被隐藏了，但它下面的毫秒范围框仍会留在页面上。
 */
function dependencyMet(c, depth = 0) {
  if (!c.dependsOn) return true
  // 依赖链正常只有两三层，加个上限防止配置写错时无限递归
  if (depth > 5) return true
  const parent = caps.value.find((x) => x.key === c.dependsOn)
  if (!parent) return false
  // 上级自己都因为条件不满足被隐藏了，下级自然也不该出现
  if (!dependencyMet(parent, depth + 1)) return false
  const cur = draft[c.dependsOn]
  if (cur === null || cur === undefined) return false
  const wanted = Array.isArray(c.dependsValues) ? c.dependsValues : []
  if (!wanted.length) {
    // 开关类上级：打开才显示
    return cur === true || cur === 'true' || cur === 1 || cur === '1'
  }
  return wanted.some((v) => String(v) === String(cur))
}

/**
 * 当前分组里<b>该显示</b>的项。
 *
 * <p>与设备 Web 界面一致：曝光模式选「手动」才出现快门/增益/光圈，
 * 白平衡选「手动」才出现红蓝增益，日夜模式选「自动」才出现灵敏度与延迟。
 * 不隐藏的话自动模式下摆着一堆无效输入框，用户改了不生效还以为是平台的 bug。
 */
const visibleItems = computed(() => activeItems.value.filter(dependencyMet))

/*
 * 保证当前页有效：换通道 / 换型号后分组可能变少，停在已经不存在的页上
 * 会渲染成空白，用户以为读不到参数。默认落在第一组（图像）。
 */
watch(
  groups,
  (list) => {
    if (!list.length) return
    if (list.some((g) => g.name === activeGroup.value)) return
    activeGroup.value = list[0].name
  },
  { immediate: true }
)

/** 是否有未保存的改动。 */
const dirty = computed(() => {
  for (const c of caps.value) {
    if (!c.available) continue
    const a = draft[c.key]
    const b = baseline[c.key]
    if (!sameValue(a, b)) return true
  }
  return false
})

/**
 * 值比较：后端可能把数字读成 {@code 1} 或 {@code 1.0}，
 * 枚举可能是字符串也可能是数字，范围参数是数组。
 * 直接 {@code ===} 会误判成「有改动」。
 */
function sameValue(a, b) {
  if (a === b) return true
  if (a === null || a === undefined || b === null || b === undefined) {
    // 读不到（null）与 false/0 是不同的：null 表示设备没这个字段
    return a === b
  }
  // 范围参数：[10,50] vs [10,50]
  if (Array.isArray(a) || Array.isArray(b)) {
    const x = Array.isArray(a) ? a : []
    const y = Array.isArray(b) ? b : []
    if (x.length !== y.length) return false
    return x.every((v, i) => sameValue(v, y[i]))
  }
  if (typeof a === 'number' || typeof b === 'number') {
    const x = Number(a)
    const y = Number(b)
    return Number.isFinite(x) && Number.isFinite(y) && x === y
  }
  return String(a) === String(b)
}

/**
 * 把后端值填进草稿与基线。
 *
 * <h3>为什么不能「先清空再填」</h3>
 * 早期写法是 <code>for (k of Object.keys(draft)) delete draft[k]</code> 再逐个赋新值。
 * 那会让所有控件的 {@code v-model} 在同一个 tick 里先变成 {@code undefined}：
 * 滑块瞬间跳到 0、开关闪成关、下拉清空，下一帧才回填 —— 肉眼就是整片面板「鬼畜」了一下。
 * 现在改为「先写新值，再删多余键」，中间不存在空档。
 *
 * <h3>基线必须用拷贝</h3>
 * 范围参数的值是数组。若 {@code draft[k] = v} 与 {@code baseline[k] = v} 指向同一个数组，
 * 用户改下限时会把基线一起改掉 —— {@code dirty} 永远是 {@code false}，
 * 「保存」按钮一直是灰的，填了值也发不出去。标量按值赋值没这个问题，只有数组会。
 */
function fill(items) {
  const next = {}
  for (const c of items) {
    if (!c.available) continue
    next[c.key] = normalize(c, c.value)
  }
  for (const k of Object.keys(next)) {
    setDraftValue(draft, k, next[k])
    baseline[k] = cloneValue(next[k])
  }
  for (const k of Object.keys(draft)) {
    if (!(k in next)) delete draft[k]
  }
  for (const k of Object.keys(baseline)) {
    if (!(k in next)) delete baseline[k]
  }
  // 重读后强制控件重挂载，避免残留旧值
  formVersion.value++
}

/**
 * 把值写进目标对象，<b>数组要原地改元素而不是整个换掉</b>。
 *
 * <p>{@code el-input-number} 把自己的当前值缓存在组件内部，靠监听
 * {@code modelValue} 这个<b>基本类型</b>来同步。当父层把 {@code draft[k]} 整个换成
 * 一个新数组时，两个输入框拿到的 {@code modelValue} 前后是不同数组的同一位置，
 * 组件没有可靠地重新同步 —— 表现为点「还原」后输入框纹丝不动，
 * 实际后端值已经变了，用户再按保存就会把错误的值发下去。
 *
 * <p>原地修改下标会让 {@code draft[k][0]}/{@code [1]} 这两个依赖正常触发更新，
 * 组件的 watch 也能拿到新的基本值。
 */
function setDraftValue(target, k, v) {
  const cur = target[k]
  if (Array.isArray(cur) && Array.isArray(v)) {
    cur.length = v.length
    for (let i = 0; i < v.length; i++) cur[i] = v[i]
    return
  }
  target[k] = cloneValue(v)
}

/**
 * 拷一份值给基线用。
 *
 * <p>只有数组需要拷：标量赋值天然独立，而数组是引用类型，
 * 两个变量指向同一个数组时改谁都会牵连另一个。
 * 这里的值只可能是标量或数字数组，不需要深度递归。
 */
function cloneValue(v) {
  return Array.isArray(v) ? v.slice() : v
}

/** 按类型归一取值：NUMBER 转数字、BOOLEAN 转布尔、RANGE 转双元素数组，其余原样。 */
function normalize(c, v) {
  if (c.type === 'RANGE') {
    // 范围参数必须是 [min, max] 两元数组，否则两个输入框会绑到同一个值上
    const arr = Array.isArray(v) ? v : []
    return [Number(arr[0]) || 0, Number(arr[1]) || 0]
  }
  if (v === null || v === undefined) {
    // 设备读不到值但字段存在：给一个安全默认，避免滑块显示 NaN
    if (c.type === 'NUMBER') return c.min ?? 0
    if (c.type === 'BOOLEAN') return false
    return ''
  }
  if (c.type === 'NUMBER') {
    const n = Number(v)
    return Number.isFinite(n) ? n : (c.min ?? 0)
  }
  if (c.type === 'BOOLEAN') return !!v
  return v
}

/*
 * 范围的两个框要满足「下限 ≤ 上限」。
 *
 * 设备不接受 min > max（实测写 [80, 20] 被静默忽略，回读保持原值），
 * 所以界面上就把它拦住，而不是让用户提交后看到一句「设备没应用」。
 *
 * 方向跟着用户刚改的那个框走：改下限就把上限顶上去，改上限就把下限压下来。
 * 两边都夹在 [c.min, c.max] 内 —— el-input-number 的 min/max 已经保证了这一点。
 */
function clampRangeLow(c, v) {
  const row = draft[c.key]
  if (!row || !Number.isFinite(Number(v))) return
  const lo = Number(v)
  if (lo > row[1]) row[1] = lo
}

function clampRangeHigh(c, v) {
  const row = draft[c.key]
  if (!row || !Number.isFinite(Number(v))) return
  const hi = Number(v)
  if (hi < row[0]) row[0] = hi
}

/** 拉能力位。 */
async function load() {
  if (!props.serial) return
  loading.value = true
  loadError.value = ''
  rejected.value = []
  try {
    const list = await dahuaApi.cameraCapabilities({
      serial: props.serial,
      code: props.code
    })
    caps.value = Array.isArray(list) ? list : []
    fill(caps.value)
  } catch (e) {
    caps.value = []
    loadError.value = e.message || '读取设备参数能力失败'
  } finally {
    loading.value = false
  }
}

/**
 * 还原到上次读到的值。
 *
 * <h3>为什么先卸载控件再写值</h3>
 * 范围参数用 {@code el-input-number}，它把用户输入暂存在组件的 {@code userInput} 里，
 * 只在失焦时提交。若先改数据（触发重渲染）再让它卸载，那个待提交的旧值会在
 * 重渲染过程中<b>再发一次</b>，把刚还原的值又覆盖回去 ——
 * 实测表现是「点还原没反应，第二次点才回到上一半」。
 *
 * <p>所以顺序必须是：先自增版本号把旧输入框卸载（连带丢掉它们的待提交值），
 * 等 DOM 更新完，再把基线值写回草稿。新挂载的控件直接读草稿，显示必然正确。
 */
async function reset() {
  formVersion.value++
  await nextTick()
  for (const c of caps.value) {
    if (c.available) setDraftValue(draft, c.key, baseline[c.key])
  }
  rejected.value = []
}

/**
 * 保存改动的项。只发改过的，避免无谓地整段重写配置。
 *
 * <h3>成功的写入不再重读能力表</h3>
 * 早期不论成败都重新拉一遍 {@code /cameraconfig/capabilities}。
 * 那个接口要把 12 个配置项整段读回来（实测 1~3 秒），
 * 期间界面上仍是旧值，读完再把整块面板重填一遍 ——
 * 滚动位置回顶、滑块成片跳动，看起来就是「一点保存界面就鬼畜」。
 * 后端对每次写入都做了回读校验（{@code saved:true} 表示设备已确认生效），
 * 所以成功时直接把当前值记为基线即可；只有被拒时才需要重读，因为那时必须显示设备的真实值。
 */
async function save() {
  if (busy.value || !dirty.value) return
  const payload = {}
  for (const c of caps.value) {
    if (!c.available) continue
    if (!sameValue(draft[c.key], baseline[c.key])) {
      payload[c.key] = draft[c.key]
    }
  }
  if (!Object.keys(payload).length) return

  busy.value = true
  rejected.value = []
  try {
    const r = await dahuaApi.cameraWrite(
      { serial: props.serial, code: props.code },
      payload
    )
    // 后端返回 {saved, needRestart, rejected?, note?}
    if (r && r.saved === false) {
      const map = r.rejected || {}
      rejected.value = Object.keys(map).map((k) => {
        const c = caps.value.find((x) => x.key === k)
        return { key: k, label: c ? c.label : k, value: map[k] }
      })
      ElMessage.warning(r.note || '部分参数设备没有应用')
      // 被拒不等于失败：已被接受的项照样要记为基线，否则用户再点保存会重复下发
      for (const k of Object.keys(payload)) {
        if (!(k in map)) baseline[k] = cloneValue(draft[k])
      }
      // 被拒的项必须以设备实际值为准，否则界面显示的是用户以为生效的值
      await reloadKeepingScroll()
    } else {
      // 基线要用拷贝：与 draft 共享数组的话，下一次改范围又会判成「无改动」
      for (const k of Object.keys(payload)) baseline[k] = cloneValue(draft[k])
      ElMessage.success(r?.note || '参数已保存')
    }
  } catch (e) {
    ElMessage.error(e.message || '保存失败')
  } finally {
    busy.value = false
  }
}

/**
 * 重读能力表，但保持当前分组与滚动位置。
 *
 * <p>被拒时不可避免要重读（必须显示设备真实值），
 * 而重读会让内容区重新渲染。不还原滚动位置的话，
 * 用户正在看的第 6 行会突然跳回顶部，像是面板被打乱了。
 */
async function reloadKeepingScroll() {
  const pane = paneRef.value
  const top = pane ? pane.scrollTop : 0
  const fresh = await dahuaApi.cameraCapabilities({
    serial: props.serial,
    code: props.code
  })
  caps.value = Array.isArray(fresh) ? fresh : caps.value
  fill(caps.value)
  await nextTick()
  if (pane) pane.scrollTop = top
}

// 换通道就重读：不同通道的参数能力可能不同（NVR 下挂不同型号）
watch(
  () => [props.serial, props.code],
  () => {
    caps.value = []
    rejected.value = []
    load()
  },
  { immediate: true }
)
</script>

<style scoped>
.cam-config {
  display: flex;
  flex-direction: column;
  gap: 10px;
  /*
   * 撑满 PtzPanel 的 tab 内容区：参数只有两三行时也不在底部留白，
   * 参数多时由 .cam-pane 内部滚动，分组按钮与保存按钮位置不变。
   *
   * 用 height:100% 而不是 flex:1 —— 本组件的父级是 .el-tab-pane，
   * 它只有 height:100%、不是 flex 容器，flex:1 在那里不生效，
   * 实测会导致内容区按内容高度撑到 415px、把保存按钮挤出可视范围的 260px。
   */
  height: 100%;
  min-height: 0;
}

.cam-hint {
  padding: 12px 4px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  text-align: center;
}

.cam-hint.is-error {
  color: var(--el-color-danger);
  display: flex;
  flex-direction: column;
  gap: 6px;
  align-items: center;
}

.cam-unsupported {
  margin: 0;
  padding: 4px 0;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 1.8;
}

/*
 * 二级分组按钮：会换行，10 个分组恒定全部可见（右栏只有 321px 宽，
 * 用 el-tabs 会溢出且没有滚动箭头，见模板里的说明）。
 * 两行约 56px，加上内容区 150px 与底栏 30px，整体约 250px，
 * 与 PtzPanel 其它页的高度基本齐平，切页时弹窗不跳高。
 */
.cam-groups {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}

.cam-group-chip {
  padding: 0 8px;
  height: 24px;
  line-height: 22px;
  font-size: 12px;
  color: var(--el-text-color-regular);
  background: var(--el-fill-color-light);
  border: 1px solid transparent;
  border-radius: 4px;
  cursor: pointer;
  transition: color 0.15s, background-color 0.15s, border-color 0.15s;
}

.cam-group-chip:hover {
  color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
}

.cam-group-chip.is-active {
  color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
  border-color: var(--el-color-primary-light-5);
  font-weight: 600;
}

/* 「不支持 N」只是告知，不该抢视觉重心 */
.cam-group-chip.is-muted {
  color: var(--el-text-color-secondary);
}

/*
 * 内容区吃掉剩余高度并在内部滚动。
 *
 * 早期这里写死 150px，是为了让「保存」按钮位置固定；代价是参数少的页
 * 下方留一条白，参数多的页又只能看到三四行。改成 flex:1 后两者兼得：
 * 高度跟着容器走，保存按钮仍然固定在最下面。
 */
.cam-pane {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding-right: 4px;
  box-sizing: border-box;
}

.cam-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 5px 0;
}

.cam-row + .cam-row {
  border-top: 1px dashed var(--el-border-color-lighter);
}

.cam-label {
  flex: 0 0 88px;
  font-size: 12px;
  color: var(--el-text-color-regular);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.cam-control {
  flex: 1;
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.cam-slider {
  flex: 1;
  min-width: 0;
}

/*
 * 范围控件：两个数字框 + 中间的横线，与设备界面的「最小 - 最大」一致。
 * 用 el-input-number 的 :controls="false"（不显示上下箭头），
 * 在 321px 宽的侧栏里才放得下两个框。
 */
.cam-range {
  gap: 4px;
}

.cam-range-input {
  flex: 1;
  min-width: 0;
}

.cam-range-input :deep(.el-input__inner) {
  text-align: center;
  font-size: 12px;
}

.cam-range-sep {
  flex: 0 0 auto;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

/* 取值范围角标，与设备界面的「(0-100)」一致 */
.cam-range-hint {
  flex: 0 0 auto;
  font-size: 11px;
  color: var(--el-text-color-placeholder);
  white-space: nowrap;
}

.cam-value {
  flex: 0 0 30px;
  font-size: 12px;
  text-align: right;
  color: var(--el-text-color-secondary);
  font-variant-numeric: tabular-nums;
}

.cam-select {
  width: 100%;
}

.cam-unsupported {
  margin: 0;
  padding: 8px 10px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 1.6;
}

.cam-footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}

.cam-rejected {
  padding: 8px 10px;
  border-radius: 6px;
  background: var(--el-color-warning-light-9);
  border: 1px solid var(--el-color-warning-light-7);
  font-size: 12px;
  color: var(--el-text-color-regular);
}

.cam-rejected-head {
  font-weight: 600;
  margin-bottom: 4px;
}

.cam-rejected ul {
  margin: 0;
  padding-left: 18px;
}

.cam-rejected-val {
  color: var(--el-text-color-secondary);
}

.cam-rejected-note {
  margin-top: 4px;
  color: var(--el-text-color-secondary);
}
</style>
