import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { menuApi, systemApi } from '@/api'
import { applyMenuRoutes } from '@/router'
import { buildSeedItems, MENU_SEED_VERSION } from '@/router/routes'

/** 本地记录已同步的种子版本，省掉每次刷新的 POST。 */
const SEED_KEY = 'gb_menu_seed_version'

/**
 * 登录态、当前用户与菜单结构。
 */
export const useAuthStore = defineStore('auth', () => {
  const token = ref(localStorage.getItem('gb_token') || '')
  const user = ref(null)
  const serverInfo = ref(null)
  /**
   * 菜单节点（gb_menu 全量行：D/M/B）。侧栏与路由都由它决定，库里就是全部真相，
   * 所以菜单管理页的新增/删除/停用才真的生效。
   */
  const menuRows = ref([])
  /** 动态路由是否已注册。守卫靠它判断「这次导航前要不要先 bootstrap」。 */
  const menuReady = ref(false)

  const loggedIn = computed(() => !!token.value)

  async function login(username, password, captchaId) {
    const data = await systemApi.login({ username, password, captchaId, urlTokenOnly: false })
    token.value = data.token
    localStorage.setItem('gb_token', data.token)
    menuReady.value = false
    await loadUser()
    return data
  }

  /** 拉用户信息与菜单结构（并行：两者都是渲染主界面必需的，串行会多一次往返）。 */
  async function loadUser() {
    const [userResult, menuResult] = await Promise.allSettled([
      systemApi.userinfo(),
      menuApi.config()
    ])
    user.value = userResult.status === 'fulfilled' ? userResult.value : null
    menuRows.value = menuResult.status === 'fulfilled' && menuResult.value?.items
      ? menuResult.value.items
      : []
    return user.value
  }

  /**
   * 补写本版本缺失的目录/菜单行。只在种子版本变化时真正写库，
   * 运维删掉的菜单不会因重启又冒出来。
   *
   * @returns {number} 新增行数
   */
  async function syncMenuSeed() {
    if (localStorage.getItem(SEED_KEY) === MENU_SEED_VERSION) {
      return 0
    }
    const res = await menuApi.sync({ version: MENU_SEED_VERSION, items: buildSeedItems() })
    localStorage.setItem(SEED_KEY, MENU_SEED_VERSION)
    return res?.deleted || 0
  }

  /**
   * 首屏初始化：用户信息 → 菜单种子 → 动态路由。
   * 失败也要把 menuReady 置真，否则守卫会一直重试 bootstrap（白屏 + 刷请求）。
   *
   * @returns {boolean} 是否拿到了用户信息
   */
  async function bootstrap() {
    try {
      await loadUser()
      const added = await syncMenuSeed()
      if (added > 0) {
        await loadMenuRows()
      }
    } catch (e) {
      console.warn('[auth] 初始化菜单失败', e)
    } finally {
      applyMenuRoutes(menuRows.value)
      menuReady.value = true
    }
    return !!user.value
  }

  /** 重新拉菜单结构并重建动态路由（「菜单管理」页保存后调用，侧栏立刻生效）。 */
  async function loadMenuRows() {
    try {
      menuRows.value = (await menuApi.config())?.items || []
    } catch (e) {
      // 保留旧菜单：一次刷新失败不该让侧栏空掉
    }
    applyMenuRoutes(menuRows.value)
    return menuRows.value
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
    menuRows.value = []
    menuReady.value = false
    localStorage.removeItem('gb_token')
  }

  /**
   * 是否管理员。后端返回 roles:["admin"] 数组，这里兼容单数 role 字段。
   * 不要用它控制业务按钮，用 can()：界面显隐取决于「有没有这项权限」，
   * 按身份判断会把「巡检员」这类自定义角色挡在功能之外。
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

  // 后端已把角色展开成具体编码（admin 拿到全部编码），直接 has 判断即可
  const permissionSet = computed(() => new Set(user.value?.permissions || []))

  /**
   * 是否具备某个权限点。只用于界面显隐：真正的边界永远在后端。
   *
   * @param {string} code 如 'device:write'
   * @returns {boolean} 没传 code 时返回 true（便于模板里的可选判断）
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
    // 除 :read 之外都算改动能力（含按钮级的 :sync / :reset / :mark 等）
    return !perms.some((p) => !String(p).endsWith(':read'))
  })

  return {
    token, user, serverInfo, loggedIn, permissions: permissionSet,
    menuRows, menuReady,
    login, logout, loadUser, loadMenuRows, loadServerInfo, bootstrap, syncMenuSeed,
    isAdmin, can, canAny, readonly
  }
})
