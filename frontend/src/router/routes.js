/**
 * 菜单种子、组件注册表与侧栏/路由装配。
 *
 * 菜单树存在后端 gb_menu（D/M/B 三层），侧栏与前端路由都由它决定。本文件的种子只在
 * 首次部署或 MENU_SEED_VERSION 变化时由 /api/v1/menu/sync 补写缺失行，之后以库为准。
 * 种子必须放前端：component 是构建产物路径，只有前端知道，后端内置一份必然漂移。
 * 按钮权限点反过来由后端按 Permissions 常量补写。
 *
 * 加页面：在 SEED_PAGES 加一条并把 MENU_SEED_VERSION 改成新值；只改标题/图标/排序
 * 不用改版本号，直接在「菜单管理」页改。
 */

export const DEFAULT_GROUP_TITLES = {
  'g-monitor': '监控',
  'g-resource': '资源',
  'g-cloud': '录像',
  'g-ops': '运维',
  'g-system': '系统'
}

export const ICON_OPTIONS = [
  'Odometer', 'VideoCamera', 'Monitor', 'List', 'Film', 'Cloudy', 'Bell', 'Share',
  'User', 'Key', 'Document', 'Setting', 'Cpu', 'DataLine', 'Folder', 'Files',
  'Camera', 'Location', 'MapLocation', 'Lock', 'Menu', 'Grid', 'Histogram',
  'Picture', 'Postcard', 'Ticket', 'TrendCharts', 'View', 'Warning'
]

/* 免登录的对外页面（不进菜单表，也不参与权限过滤）。 */
export const publicRoutes = [
  {
    path: '/login',
    name: 'login',
    component: () => import('@/views/LoginView.vue'),
    meta: { public: true, title: '登录' }
  },
  {
    // 录像分享页：免登录，凭签名令牌观看某段录像
    path: '/share/:token',
    name: 'share',
    component: () => import('@/views/ShareView.vue'),
    meta: { public: true, title: '录像分享' }
  },
  {
    // 直播分享页：免登录，路径对齐 CMS 的 play.html（避免与 /share/:token 动态段混淆）
    path: '/play',
    name: 'play',
    component: () => import('@/views/LiveShareView.vue'),
    meta: { public: true, title: '直播分享' }
  },
  {
    // iframe 嵌入测试页：免登录是刻意的，它要在别人的机器上判断「嵌到我站里能不能播」，
    // 且不含敏感数据（通道编码由使用者自己填）
    path: '/iframe-test',
    name: 'iframeTest',
    component: () => import('@/views/IframeTestView.vue'),
    meta: { public: true, title: 'iframe 嵌入测试' }
  }
]

/**
 * 页面清单种子。字段对应 gb_menu 的 M 行：name → menu_key（也是路由 name）、
 * group → parent_key、perm → perms。hidden 的页面不进侧栏，但照常注册路由。
 */
export const SEED_PAGES = [
  { name: 'dashboard', path: '/dashboard', component: 'views/DashboardView.vue', title: '总览', icon: 'Odometer', group: 'g-monitor', perm: 'device:read' },
  { name: 'live', path: '/live', component: 'views/LiveView.vue', title: '实时预览', icon: 'VideoCamera', group: 'g-monitor', perm: 'play:live' },
  { name: 'devices', path: '/devices', component: 'views/DeviceView.vue', title: '设备管理', icon: 'Monitor', group: 'g-resource', perm: 'device:read' },
  { name: 'channels', path: '/channels', component: 'views/ChannelView.vue', title: '通道管理', icon: 'List', group: 'g-resource', perm: 'channel:read' },
  { name: 'playback', path: '/playback', component: 'views/PlaybackView.vue', title: '设备录像', icon: 'Film', group: 'g-cloud', perm: 'play:record' },
  { name: 'cloud', path: '/cloud', component: 'views/CloudRecordChannelView.vue', title: '云端录像', icon: 'Cloudy', group: 'g-cloud', perm: 'cloud:read' },
  { name: 'cloud-detail', path: '/cloud/detail', component: 'views/CloudRecordDetailView.vue', title: '云端录像详情', group: 'g-cloud', perm: 'cloud:read', hidden: true },
  { name: 'cloud-detail-list', path: '/cloud/detail/list', component: 'views/CloudRecordListView.vue', title: '云端录像列表', group: 'g-cloud', perm: 'cloud:read', hidden: true },
  { name: 'cloud-plans', path: '/cloud/plans', component: 'views/CloudRecordPlanView.vue', title: '录像计划', group: 'g-cloud', perm: 'cloud:read', hidden: true },
  { name: 'alarms', path: '/alarms', component: 'views/AlarmView.vue', title: '报警管理', icon: 'Bell', group: 'g-ops', perm: 'alarm:read' },
  { name: 'cascade', path: '/cascade', component: 'views/CascadeView.vue', title: '级联配置', icon: 'Share', group: 'g-ops', perm: 'cascade:read' },
  { name: 'users', path: '/users', component: 'views/UserView.vue', title: '用户管理', icon: 'User', group: 'g-system', perm: 'user:read' },
  { name: 'roles', path: '/roles', component: 'views/RoleView.vue', title: '角色权限', icon: 'Key', group: 'g-system', perm: 'user:read' },
  { name: 'menus', path: '/menus', component: 'views/MenuView.vue', title: '菜单管理', icon: 'Menu', group: 'g-system', perm: 'system:read' },
  { name: 'oplogs', path: '/oplogs', component: 'views/OpLogView.vue', title: '操作日志', icon: 'Document', group: 'g-system', perm: 'oplog:read' },
  { name: 'settings', path: '/settings', component: 'views/SettingsView.vue', title: '系统设置', icon: 'Setting', group: 'g-system', perm: 'system:read' }
]

/**
 * 种子版本。只在 SEED_PAGES / DEFAULT_GROUP_TITLES 增删条目时改：版本一致时
 * /menu/sync 是空操作，不会把运维删掉的行种回来。改标题/图标/排序请在菜单管理页操作。
 */
export const MENU_SEED_VERSION = '2026-11-01.2'

/**
 * 把种子摊平成 gb_menu 行（目录 + 菜单，不含按钮）。
 * sortOrder 按同级从 1 编号：界面直接显示这个数字，全局序号会让人以为排序跳着用。
 */
export function buildSeedItems() {
  const dirs = Object.entries(DEFAULT_GROUP_TITLES).map(([code, title], idx) => ({
    menuKey: code,
    parentKey: null,
    menuType: 'D',
    title,
    sortOrder: idx + 1
  }))
  const counters = new Map()
  const pages = SEED_PAGES.map((p) => {
    const n = (counters.get(p.group) || 0) + 1
    counters.set(p.group, n)
    return {
      menuKey: p.name,
      parentKey: p.group,
      menuType: 'M',
      title: p.title,
      icon: p.icon || null,
      sortOrder: n,
      path: p.path,
      component: p.component,
      perms: p.perm || null,
      hidden: !!p.hidden
    }
  })
  return [...dirs, ...pages]
}

const viewModules = import.meta.glob('../views/*.vue')

export const COMPONENT_OPTIONS = Object.keys(viewModules)
  .map((k) => k.replace('../', ''))
  .sort()

/** 组件路径 → 动态 import；找不到返回 null（调用方跳过该路由）。 */
export function resolveView(component) {
  if (!component) return null
  return viewModules['../' + component] || null
}

function bySort(a, b) {
  const d = (a.sortOrder ?? 0) - (b.sortOrder ?? 0)
  return d !== 0 ? d : String(a.menuKey).localeCompare(String(b.menuKey))
}

function enabled(row) {
  return Number(row?.status ?? 0) !== 1
}

// 行数组 → parentKey → 子行[]
function childrenOf(rows) {
  const map = new Map()
  rows.forEach((r) => {
    const k = r.parentKey || ''
    if (!map.has(k)) map.set(k, [])
    map.get(k).push(r)
  })
  return map
}

/**
 * 生成侧栏菜单（按权限过滤）。
 *
 * @param {object} auth auth store（提供 can(perm)）
 * @param {Array<object>} items gb_menu 行
 */
export function buildMenu(auth, items) {
  const rows = items || []
  const dirs = rows.filter((r) => r.menuType === 'D' && enabled(r)).sort(bySort)
  const menus = rows.filter((r) => r.menuType === 'M' && enabled(r) && !r.hidden && auth.can(r.perms))
  const byParent = childrenOf(menus)
  return dirs
    .map((d) => ({
      key: d.menuKey,
      label: d.title || d.menuKey,
      items: (byParent.get(d.menuKey) || []).slice().sort(bySort).map((r) => ({
        key: r.menuKey,
        path: r.path,
        title: r.title || r.path,
        icon: r.icon || 'Document'
      }))
    }))
    .filter((g) => g.items.length)
}

/**
 * 生成动态路由（供 router.addRoute 注册）。组件缺失时打日志跳过，
 * 不让一行脏数据把整个应用弄白屏。
 *
 * @param {Array<object>} items gb_menu 行
 */
export function buildRoutes(items) {
  const rows = items || []
  const dirTitle = new Map(rows.filter((r) => r.menuType === 'D').map((r) => [r.menuKey, r.title]))
  return rows
    .filter((r) => r.menuType === 'M' && enabled(r) && r.path && r.component)
    .map((r) => {
      const component = resolveView(r.component)
      if (!component) {
        console.warn(`[menu] 组件不存在，已跳过路由 ${r.menuKey}: ${r.component}`)
        return null
      }
      return {
        path: r.path,
        name: r.menuKey,
        component,
        meta: {
          title: r.title || r.menuKey,
          icon: r.icon || 'Document',
          perm: r.perms || null,
          hidden: !!r.hidden,
          group: dirTitle.get(r.parentKey) || ''
        }
      }
    })
    .filter(Boolean)
}
