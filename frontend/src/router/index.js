import { createRouter, createWebHistory } from 'vue-router'
import { publicRoutes, buildMenu, buildRoutes } from './routes'

/**
 * 路由装配。
 *
 * <p>业务路由<b>不在代码里写死</b>：它们在登录后按后端 {@code gb_menu} 表动态注册，
 * 这样「菜单管理」页里新增/删除/停用菜单才能真正生效。
 * 本文件只负责：公共路由、主布局壳、动态注册 {@link applyMenuRoutes} 与守卫。
 *
 * <p><b>为什么动态注册而不是一次性生成</b>：用户还没登录时拿不到菜单；
 * 而 {@code addRoute} 支持在任意时刻追加，正好对应「登录 → 拉菜单 → 注册」的时序。
 */
const router = createRouter({
  history: createWebHistory(),
  routes: [
    ...publicRoutes,
    {
      path: '/',
      name: 'main',
      component: () => import('@/layouts/MainLayout.vue'),
      /*
       * 刻意不写 redirect：默认首页应当是「当前账号真正能打开的第一个页面」。
       * 写死成 /dashboard 时，一旦这一页被停用或当前角色没有 device:read，
       * 就会落到 404（或又弹回 /dashboard 形成循环）。
       * 交给守卫按菜单顺序挑第一个可用页面。
       */
      children: []
    },
    {
      /*
       * 未匹配的地址（打错、或被停用的菜单）。
       *
       * <p>这里<b>不能写 redirect</b>：/dashboard 本身也可能被停用/删除，
       * 那时「/dashboard 未匹配 → 重定向到 /dashboard」会变成无限重定向。
       * 渲染一个 404 页最稳，也最诚实。
       */
      path: '/:pathMatch(.*)*',
      name: 'not-found',
      component: () => import('@/views/NotFoundView.vue')
    }
  ]
})

/** 已注册的动态路由名，重载菜单时先撤销，避免旧路由残留。 */
let registered = []

/**
 * 按菜单元数据重建业务路由。
 *
 * @param {Array<object>} items {@code gb_menu} 行
 */
export function applyMenuRoutes(items) {
  registered.forEach((name) => {
    if (router.hasRoute(name)) {
      router.removeRoute(name)
    }
  })
  registered = []
  buildRoutes(items).forEach((record) => {
    if (router.hasRoute(record.name)) {
      router.removeRoute(record.name)
    }
    router.addRoute('main', record)
    registered.push(record.name)
  })
}

router.beforeEach(async (to) => {
  const token = localStorage.getItem('gb_token')

  if (to.meta.public) {
    if (to.path === '/login' && token) {
      return { path: '/' }
    }
    return true
  }
  if (!token) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }

  /*
   * 动态 import 而不是顶层 import：
   * api/http.js 顶层 import 了 router，而 stores/auth.js 又依赖 api，
   * 三者静态互相引用就是循环依赖，守卫里拿到的是 undefined
   * （表现为「刷新后一直跳登录页」）。
   */
  const { useAuthStore } = await import('@/stores/auth')
  const auth = useAuthStore()

  /*
   * 首次进入（含硬刷新）：必须先拉用户信息与菜单结构、注册动态路由，
   * 否则当前这次导航匹配到的是 404 → 会被兜底重定向到别处。
   * 注册完重新解析一次原始地址。
   */
  if (!auth.menuReady) {
    const ok = await auth.bootstrap()
    if (!ok) {
      return { path: '/login' }
    }
    return { path: to.fullPath, replace: true }
  }

  if (!auth.user) {
    await auth.loadUser()
  }
  // 访问根路径：跳第一个当前账号能打开的页面
  if (to.name === 'main') {
    const home = fallbackPath(auth)
    return home === '/' ? false : { path: home, replace: true }
  }
  if (!to.meta.perm || auth.can(to.meta.perm)) {
    return true
  }
  const target = fallbackPath(auth)
  return target === to.path ? false : { path: target }
})

/**
 * 无权限或地址不存在时的兜底页：按菜单顺序找第一个真正有权限的页面。
 *
 * <p>不能一律回 /dashboard —— 一个只有「报警查看」权限的角色在 /dashboard 上
 * 会被再次拒绝，然后又被踢回 /dashboard，形成循环。
 * 直接复用 {@link buildMenu}，保证「菜单里看得见的」与「兜底能跳到的」是同一份数据。
 *
 * @param {object|null} auth auth store（null 表示尚未加载，返回默认页）
 */
function fallbackPath(auth) {
  if (auth) {
    const menu = buildMenu(auth, auth.menuRows)
    if (menu.length && menu[0].items.length) {
      return menu[0].items[0].path
    }
  }
  return '/'
}

export default router
