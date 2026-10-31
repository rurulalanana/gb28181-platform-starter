import { createRouter, createWebHistory } from 'vue-router'
import { appRoutes, publicRoutes, buildMenu } from './routes'

/**
 * 路由装配。
 *
 * <p><b>业务路由与侧栏菜单的定义全部在 {@link ./routes.js}</b>。
 * 本文件只负责装配实例 + 守卫；加页面请改 {@code routes.js}，
 * 不要在这里追加，否则菜单和路由又会变成两份。
 */
const routes = [
  ...publicRoutes,
  {
    path: '/',
    component: () => import('@/layouts/MainLayout.vue'),
    redirect: '/dashboard',
    children: appRoutes
  },
  { path: '/:pathMatch(.*)*', redirect: '/dashboard' }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

router.beforeEach(async (to) => {
  const token = localStorage.getItem('gb_token')
  if (!to.meta.public && !token) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }
  if (to.path === '/login' && token) {
    return { path: fallbackPath(null) }
  }
  if (!to.meta.perm) {
    return true
  }

  /*
   * 动态 import 而不是顶层 import：
   * eslint 之外还有个真实原因 —— api/http.js 顶层 import 了 router，
   * 而 stores/auth.js 又依赖 api，三者一旦静态互相引用就是循环依赖，
   * 在路由守卫里拿到的是 undefined（表现为「刷新后一直跳登录页」）。
   * 守卫执行时路由模块已经求值完毕，此时再导入是安全的。
   */
  const { useAuthStore } = await import('@/stores/auth')
  const auth = useAuthStore()
  // 硬刷新后 user 还是空的，必须先拉一次 /userinfo，否则所有带 perm 的路由都会被误判为无权限
  if (!auth.user) {
    await auth.loadUser()
  }
  if (auth.can(to.meta.perm)) {
    return true
  }
  const target = fallbackPath(auth)
  return target === to.path ? false : { path: target }
})

/**
 * 无权限时的兜底页：按菜单顺序找第一个真正有权限的页面。
 *
 * <p>不能一律回 /dashboard —— 一个只有「报警查看」权限的角色
 * 在 /dashboard 上会被再次拒绝，然后又被踢回 /dashboard，形成循环。
 * 直接复用 {@link buildMenu}，保证「菜单里看得见的」与「兜底能跳到的」
 * 是同一份数据，不会出现菜单改了而兜底没改的偏差。
 *
 * @param {object|null} auth auth store（null 表示尚未加载，返回登录后的默认页）
 */
function fallbackPath(auth) {
  if (auth) {
    const menu = buildMenu(auth)
    if (menu.length && menu[0].items.length) {
      return menu[0].items[0].path
    }
  }
  return '/dashboard'
}

export default router
