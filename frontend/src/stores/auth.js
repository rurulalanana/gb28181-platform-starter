import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { systemApi } from '@/api'

/**
 * 登录态与当前用户。
 */
export const useAuthStore = defineStore('auth', () => {
  const token = ref(localStorage.getItem('gb_token') || '')
  const user = ref(null)
  const serverInfo = ref(null)

  const loggedIn = computed(() => !!token.value)

  async function login(username, password) {
    const data = await systemApi.login({ username, password, urlTokenOnly: false })
    token.value = data.token
    localStorage.setItem('gb_token', data.token)
    await loadUser()
    return data
  }

  async function loadUser() {
    try {
      user.value = await systemApi.userinfo()
    } catch (e) {
      user.value = null
    }
    return user.value
  }

  async function loadServerInfo() {
    serverInfo.value = await systemApi.serverinfo()
    return serverInfo.value
  }

  async function logout() {
    try {
      await systemApi.logout()
    } catch (e) {
      // 忽略登出异常
    }
    token.value = ''
    user.value = null
    localStorage.removeItem('gb_token')
  }

  /**
   * 是否管理员。
   *
   * <p>后端 {@code /userinfo} 返回的是 {@code roles: ["admin"]} 数组，
   * 这里同时兼容单数 {@code role} 字段，避免两种形态下判断结果不一致
   * （早期只认 {@code user.role}，导致管理员菜单永远不显示）。
   *
   * <p><b>不要再用它控制业务按钮</b> —— 用 {@link can}。
   * 「是不是管理员」是个身份判断，而界面该显示什么取决于「有没有这项权限」：
   * 管理员之外还有自定义角色（如「巡检员」只给云台），
   * 按身份判断会把这些人挡在功能之外。
   */
  function isAdmin() {
    const u = user.value
    if (!u) return false
    if (Array.isArray(u.roles)) {
      return u.roles.some((r) => String(r).toLowerCase() === 'admin')
    }
    if (u.role) {
      return String(u.role).toLowerCase() === 'admin'
    }
    return false
  }

  /**
   * 当前账号的权限点集合。
   *
   * <p>后端已把角色展开成具体编码（admin 拿到的是全部编码），
   * 所以这里直接用 {@code has} 判断，不需要处理通配符。
   */
  const permissionSet = computed(() => new Set(user.value?.permissions || []))

  /**
   * 是否具备某个权限点。
   *
   * <p>语义上等价于后端 {@code StpUtil.hasPermission}，但<b>只用于界面显隐</b>：
   * 前端隐藏按钮是为了不让人点了才看到 403，真正的边界永远在后端。
   *
   * @param {string} code 权限点编码，如 {@code 'device:write'}
   * @returns {boolean} 没有传 code 时返回 true（便于在模板里写可选判断）
   */
  function can(code) {
    if (!code) return true
    return permissionSet.value.has(code)
  }

  /** 具备其中任意一项即可。 */
  function canAny(...codes) {
    const flat = codes.flat()
    if (!flat.length) return true
    return flat.some((c) => can(c))
  }

  /** 是否只读账号（没有任何修改类权限）：用于整页禁用，而不是逐个按钮判断。 */
  const readonly = computed(() => {
    const perms = user.value?.permissions
    if (!perms) return false
    return !perms.some((p) => /:(write|delete|clear|ptz)$/.test(p))
  })

  return {
    token, user, serverInfo, loggedIn, permissions: permissionSet,
    login, logout, loadUser, loadServerInfo,
    isAdmin, can, canAny, readonly
  }
})
