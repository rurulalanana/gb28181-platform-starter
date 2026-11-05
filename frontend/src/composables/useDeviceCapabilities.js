import { computed, ref, watch } from 'vue'
import { controlApi } from '@/api'

/**
 * 设备控制能力表（`GET /api/v1/device/capabilities`）。
 *
 * <h3>为什么需要</h3>
 * 后端已经把 32 个控制命令统一成一套接口，每种协议给一份实现。副作用是
 * **同一批按钮在不同设备上可用性不同**：大华 SDK 没有强制关键帧、没有看守位、
 * 没有拉框缩放；国标则全都有。
 *
 * <p>在接入本 composable 之前，前端只能「全部显示、点了再看 400」——
 * 用户点「强制关键帧」收到「该设备不支持」，只能反复试错。
 *
 * <h3>不支持的按钮要「可见但禁用」，不要隐藏</h3>
 * 隐藏会让用户以为是自己找错了地方，也看不到原因。
 * 因此后端返回的不只是支持列表，还有每一项的 {@code reason}，
 * 用 {@link reason} 取出来放进 tooltip。
 *
 * <h3>失败时一律当作「全支持」</h3>
 * 取不到能力表（老后端、网络抖动）时不能把整个云台面板禁用掉 ——
 * 那比「点了再报错」更糟。所以 {@link failed} 时所有 {@code supports()} 返回 true，
 * 退化回改造前的行为。
 *
 * @param {import('vue').Ref<string>|Function} serial 设备编码来源（ref 或 getter）
 * @param {object} [options]
 * @param {boolean} [options.immediate] 是否立即拉取，默认 true
 */
export function useDeviceCapabilities(serial, { immediate = true } = {}) {
  /** 支持的能力名集合。 */
  const supported = ref(new Set())
  /** 能力名 → 不支持原因。 */
  const reasons = ref({})
  /** 接入方式（{@code gb28181} / {@code dahua}），仅用于展示。 */
  const kind = ref('')
  const loading = ref(false)
  /** 是否已成功取到能力表。为 false 时一切按「支持」处理。 */
  const loaded = ref(false)
  /** 取失败的原因（调试用，不展示给用户）。 */
  const failed = ref('')

  function currentSerial() {
    const v = typeof serial === 'function' ? serial() : serial?.value
    return v || ''
  }

  /**
   * 是否支持某项能力。
   *
   * <p><b>未加载时返回 true</b>：宁可让用户点了收 400（旧行为），
   * 也不能因为一次接口失败就把整面板锁死。
   *
   * @param {string} capability 能力名，见后端 `DeviceCapability` 枚举
   */
  function supports(capability) {
    if (!loaded.value) return true
    return supported.value.has(capability)
  }

  /**
   * 不支持的原因；支持或未加载时返回空串。
   *
   * <p>直接拿去当 `el-tooltip` 的 content 或按钮的 `title`。
   */
  function reason(capability) {
    return reasons.value[capability] || ''
  }

  /**
   * 生成按钮的 `disabled` 值：本身已禁用，或该能力不支持。
   *
   * @param {string} capability 能力名
   * @param {boolean} [alreadyDisabled] 业务侧原有的禁用条件（如未上屏、正在运行）
   */
  function disabled(capability, alreadyDisabled = false) {
    return Boolean(alreadyDisabled) || !supports(capability)
  }

  /**
   * 生成按钮的提示文案：不支持时给出原因，支持时用调用方给的默认提示。
   *
   * @param {string} capability 能力名
   * @param {string} [normalTip] 正常情况下的提示
   */
  function tip(capability, normalTip = '') {
    const r = reason(capability)
    return r || normalTip
  }

  async function reload() {
    const id = currentSerial()
    if (!id) {
      supported.value = new Set()
      reasons.value = {}
      loaded.value = false
      return
    }
    loading.value = true
    try {
      const data = await controlApi.capabilities(id)
      supported.value = new Set(data?.supported || [])
      const map = {}
      for (const item of data?.unsupported || []) {
        map[item.capability] = item.reason || ''
      }
      reasons.value = map
      kind.value = data?.kind || ''
      loaded.value = true
      failed.value = ''
    } catch (e) {
      /*
       * 失败一律当「全支持」：旧后端没有这个端点，或网络抖动。
       * 锁死按钮比「点了再报错」更伤可用性。
       */
      supported.value = new Set()
      reasons.value = {}
      loaded.value = false
      failed.value = e?.message || String(e)
    } finally {
      loading.value = false
    }
  }

  watch(
    () => currentSerial(),
    () => {
      if (immediate) reload()
    },
    { immediate }
  )

  return {
    supported,
    reasons,
    kind,
    loading,
    loaded,
    failed,
    supports,
    reason,
    disabled,
    tip,
    reload,
    /** 当前是否为厂商 SDK 直连设备（部分面板据此切换 Tab）。 */
    isSdkDevice: computed(() => loaded.value && kind.value && kind.value !== 'gb28181')
  }
}
