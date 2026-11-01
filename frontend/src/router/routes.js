/**
 * 菜单种子、组件注册表与侧栏/路由装配。
 *
 * <h3>菜单从哪来</h3>
 *
 * <p>整棵菜单树存在后端 {@code gb_menu} 表里（目录 D / 菜单 M / 按钮 B 三层），
 * 侧栏与前端路由都由它决定。本文件仍保留一份<b>种子</b>：首次部署或
 * {@link MENU_SEED_VERSION} 变化时，由 {@code /api/v1/menu/sync} 把缺失的
 * 目录/菜单行补写进库。之后一律以库为准 —— 运维在「菜单管理」页改名、挪分组、
 * 新增、删除都能生效，代码不会再覆盖回去。
 *
 * <p>为什么种子必须放前端：{@code component} 是构建产物路径
 * （{@code views/UserView.vue}），清单只有前端知道；后端内置一份必然漂移，
 * 表现为「库里有个页面、前端根本没这个组件」。
 *
 * <p>按钮行（权限标识）反过来由后端按 {@code Permissions} 常量补写 ——
 * 权限点是 Java 侧的唯一真相，前端不该重复定义。
 *
 * <h3>加一个页面要做什么</h3>
 *
 * <p>在 {@link SEED_PAGES} 里加一条，并把 {@link MENU_SEED_VERSION} 改成新值。
 * 这样已部署的环境会在下次打开页面时自动补上这一行（已有行不动）。
 * 只改标题/图标/排序 <b>不用改版本号</b>，直接在「菜单管理」页里改即可。
 */

/** 分组默认标题（{@code 编码 → 标题}）。库里 D 行的 title 可覆盖。 */
export const DEFAULT_GROUP_TITLES = {
  'g-monitor': '监控',
  'g-resource': '资源',
  'g-cloud': '录像',
  'g-ops': '运维',
  'g-system': '系统'
}

/** 可选的图标名（Element Plus 图标组件名，{@code main.js} 已全局注册）。 */
export const ICON_OPTIONS = [
  'Odometer', 'VideoCamera', 'Monitor', 'List', 'Film', 'Cloudy', 'Bell', 'Share',
  'User', 'Key', 'Document', 'Setting', 'Cpu', 'DataLine', 'Folder', 'Files',
  'Camera', 'Location', 'MapLocation', 'Lock', 'Menu', 'Grid', 'Histogram',
  'Picture', 'Postcard', 'Ticket', 'TrendCharts', 'View', 'Warning'
]

/** 免登录的对外页面（不进菜单表，也不参与权限过滤）。 */
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
    /*
     * 直播分享页：免登录，对齐 LiveGBS 的 play.html。
     *
     * <p>路径用 {@code /play} 而不是 {@code /share/live}：前者与 CMS 的
     * {@code play.html} 语义一致（「播放某个通道」），也不会和录像分享
     * {@code /share/:token} 的动态段混淆 —— 那会让 {@code /share/live}
     * 被当成「令牌为 live 的录像分享」。
     *
     * <p>参数：{@code ?serial=<设备编码>&code=<通道编码>}。
     */
    path: '/play',
    name: 'play',
    component: () => import('@/views/LiveShareView.vue'),
    meta: { public: true, title: '直播分享' }
  },
  {
    /*
     * iframe 嵌入测试页：免登录。
     *
     * <p>免登录是刻意的：这一页要在「别人的机器、别人的浏览器」上打开，
     * 用来判断「嵌到我站里能不能播」。要登录就等于多一道门槛，
     * 而它本身不含任何敏感数据 —— 通道编码由使用者自己填。
     */
    path: '/iframe-test',
    name: 'iframeTest',
    component: () => import('@/views/IframeTestView.vue'),
    meta: { public: true, title: 'iframe 嵌入测试' }
  }
]

/**
 * 页面清单种子。
 *
 * <p>字段对应 {@code gb_menu} 的 M 行：{@code name} → {@code menu_key}（也是路由 name），
 * {@code group} → {@code parent_key}，{@code perm} → {@code perms}。
 *
 * <p>{@code hidden: true} 的页面不进侧栏，但照常注册路由（直连 URL 可打开，仍受权限守卫管辖）。
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
 * 种子版本。
 *
 * <p><b>只有在 {@link SEED_PAGES} / {@link DEFAULT_GROUP_TITLES} 增删条目时才需要改</b>：
 * 版本一致时 {@code /menu/sync} 是空操作，不会把运维删掉的行种回来。
 * 改标题、图标、排序、分组请直接在「菜单管理」页操作，不要动这里。
 */
export const MENU_SEED_VERSION = '2026-11-01.2'

/**
 * 把种子摊平成 {@code gb_menu} 行（目录 + 菜单，不含按钮）。
 *
 * <p>{@code sortOrder} 按<b>同级</b>从 1 开始编号（目录 1..n，每个目录下的菜单各自 1..n）：
 * 界面上直接显示这个数字，用全局序号（0/10/100）会让人以为排序跳着用。
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

/** 组件注册表：{@code views/Xxx.vue → () => import(...)}（构建期静态分析仍然有效）。 */
const viewModules = import.meta.glob('../views/*.vue')

/** 「菜单管理」里可选的组件路径。 */
export const COMPONENT_OPTIONS = Object.keys(viewModules)
  .map((k) => k.replace('../', ''))
  .sort()

/** 组件路径 → 动态 import；找不到返回 {@code null}（调用方跳过该路由）。 */
export function resolveView(component) {
  if (!component) return null
  return viewModules['../' + component] || null
}

/** 同级排序：先按 sortOrder，再按主键保证稳定。 */
function bySort(a, b) {
  const d = (a.sortOrder ?? 0) - (b.sortOrder ?? 0)
  return d !== 0 ? d : String(a.menuKey).localeCompare(String(b.menuKey))
}

/** 是否可用（停用节点既不进侧栏也不注册路由）。 */
function enabled(row) {
  return Number(row?.status ?? 0) !== 1
}

/** 行数组 → {@code parentKey → 子行[]}。 */
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
 * 生成侧栏菜单（按当前账号权限过滤）。
 *
 * @param {object} auth auth store（提供 {@code can(perm)}）
 * @param {Array<object>} items {@code gb_menu} 行
 * @returns {Array<{label: string, items: Array<{key: string, path: string, title: string, icon: string}>}>}
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
 * 生成动态路由（供 {@code router.addRoute} 注册）。
 *
 * <p>只有「菜单行 + 正常状态 + 组件能解析」的节点才会注册；组件缺失时打日志跳过，
 * 而不是让整个应用因为一行脏数据白屏。
 *
 * @param {Array<object>} items {@code gb_menu} 行
 * @returns {Array<object>} vue-router 路由记录
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
