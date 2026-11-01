import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { menuApi, systemApi } from '@/api'
import { applyMenuRoutes } from '@/router'
import { buildSeedItems, MENU_SEED_VERSION } from '@/router/routes'

/** 本地记录「这一版种子已经同步过」，省掉每次刷新的那次 POST。 */
const SEED_KEY = 'gb_menu_seed_version'

/**
 * 登录态、当前用户与菜单结构。
 */
export const useAuthStore = defineStore('auth', () => {
  const token = ref(localStorage.getItem('gb_token') || '')
  const user = ref(null)
  const serverInfo = ref(null)
  /**
   * 菜单节点（`gb_menu` 全量行：目录 D / 菜单 M / 按钮 B）。
   *
   * <p>侧栏与路由都由它决定，不再有「代码默认值 ⊕ 库里覆盖值」的合并逻辑 ——
   * 库里就是全部真相，所以「菜单管理」页新增/删除/停用才真的生效。
   */
  const menuRows = ref([])
  /** 动态路由是否已注册。守卫靠它判断「这次导航前要不要先 bootstrap」。 */
  const menuReady = ref(false)

  const loggedIn = computed(() => !!token.value)

  async function login(username, password) {
    const data = await systemApi.login({ username, password, urlTokenOnly: false })
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
   * 补写本版本缺失的目录/菜单行。
   *
   * <p>只在种子版本变化时真正写库（版本记在 `gb_menu_seed` 表），
   * 平时是空操作。运维删掉的菜单不会因为重启又冒出来。
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
   *
   * <p>失败也要把 {@link menuReady} 置真：否则守卫会一直重试 bootstrap，
   * 表现为页面白屏且控制台刷满请求。
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
   * 是否管理员。
   *
   * <p>后端 {@code /userinfo} 返回的是 {@code roles: ["admin"]} 数组，
   * 这里同时兼容单数 {@code role} 字段，避免两种形态下判断结果不一致。
   *
   * <p><b>不要再用它控制业务按钮</b> —— 用 {@link can}。
   * 「是不是管理员」是身份判断，而界面该显示什么取决于「有没有这项权限」：
   * 管理员之外还有自定义角色（如「巡检员」只给云台），按身份判断会把这些人挡在功能之外。
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
