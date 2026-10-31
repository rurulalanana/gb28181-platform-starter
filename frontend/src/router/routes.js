/**
 * 路由与侧栏菜单的<b>唯一</b>数据源。
 *
 * <h3>为什么单独抽这个文件</h3>
 *
 * <p>改造前同一份菜单信息写了两遍：{@code router/index.js} 里写 {@code meta.perm}，
 * {@code MainLayout.vue} 里再手写一份 {@code allItems}（标题、图标、分组）。
 * 两处的图标体系还不一致（一处 mdi 字符串、一处 Element Plus 组件名），
 * 结果是加一个页面要改两个文件，漏一个就出现「菜单里有、地址打不开」
 * 或者「地址能打开、菜单里没有」。
 *
 * <p>现在 {@code appRoutes[].meta} 同时承担三件事：
 * <ol>
 *   <li>{@code title} 页面标题与菜单文字</li>
 *   <li>{@code icon} 菜单图标（Element Plus 图标组件名，见 {@code main.js} 的全局注册）</li>
 *   <li>{@code group} 菜单分组 —— <b>数组顺序即菜单顺序</b>，分组按首次出现排序</li>
 *   <li>{@code perm} 所需权限点，路由守卫与菜单过滤共用同一字段</li>
 * </ol>
 *
 * <h3>为什么不做成后端下发</h3>
 *
 * <p>菜单项与 {@code component: () => import(...)} 一一对应，而组件是构建产物的一部分。
 * 让后端下发菜单，等于把「前端有哪些页面」也搬到服务端维护，
 * 一旦代码回滚到旧版本而库里还是新菜单，就会出现点了 404 的项。
 * 权限是可配置的（{@code gb_role_permission}），菜单结构跟着代码走，这样边界最清楚。
 *
 * <h3>隐藏页</h3>
 *
 * <h3>隐藏页</h3>
 *
 * <p>{@code hidden: true} 的路由（云端录像详情/列表/计划）不进菜单，
 * 但保留 {@code perm}，所以直输 URL 仍然受路由守卫管辖。
 *
 * <h3>可配置部分归谁管</h3>
 *
 * <p>标题、图标、分组、排序、显隐可以被数据库覆盖（{@code gb_menu} /
 * {@code gb_menu_group}），运维在「菜单维护」页自己改，不用发版。
 * 但<b>权限判断始终用代码里的 {@code perm}</b> —— 覆盖配置只能改「怎么显示」，
 * 不能放宽「谁能看」，否则菜单维护就成了绕过 RBAC 的口子。
 *
 * <p>覆盖配置只存<b>差异</b>：库里多出来的未知 name 直接忽略，缺的项用默认值。
 * 这样代码回滚到旧版本也不会出现「点了 404 的菜单项」。
 */

/**
 * 分组默认标题（{@code 编码 → 标题}）。库里 {@code gb_menu} 的 D 行可覆盖。
 *
 * <p><b>编码统一加 {@code g-} 前缀</b>：分组编码与菜单项的路由 name 共用
 * {@code gb_menu.menu_key} 这个主键，而两者可能撞名 —— 最初用的 {@code cloud}
 * 既是「录像」分组的编码、又是云端录像页的路由 name，同一行无法同时表示
 * 两种含义，改分组名会把菜单项一起覆盖掉。加前缀后两个命名空间彻底错开，
 * 不需要复合主键，也不需要改表结构。
 *
 * <p>编码只用于存储关联，界面上一律显示标题，所以前缀不会暴露给使用者。
 */
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

/** 免登录的对外页面。 */
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
 * 主布局下的业务路由。
 *
 * <p><b>加页面只改这里</b>：菜单、路由、权限守卫三处自动跟上。
 * {@code path} 写相对段（不带前导 {@code /}），菜单里会拼成绝对路径。
 */
export const appRoutes = [
  {
    path: 'dashboard',
    name: 'dashboard',
    component: () => import('@/views/DashboardView.vue'),
    meta: { title: '总览', icon: 'Odometer', group: 'g-monitor', perm: 'device:read' }
  },
  {
    path: 'live',
    name: 'live',
    component: () => import('@/views/LiveView.vue'),
    meta: { title: '实时预览', icon: 'VideoCamera', group: 'g-monitor', perm: 'play:live' }
  },
  {
    path: 'devices',
    name: 'devices',
    component: () => import('@/views/DeviceView.vue'),
    meta: { title: '设备管理', icon: 'Monitor', group: 'g-resource', perm: 'device:read' }
  },
  {
    path: 'channels',
    name: 'channels',
    component: () => import('@/views/ChannelView.vue'),
    meta: { title: '通道管理', icon: 'List', group: 'g-resource', perm: 'channel:read' }
  },
  {
    path: 'playback',
    name: 'playback',
    component: () => import('@/views/PlaybackView.vue'),
    meta: { title: '设备录像', icon: 'Film', group: 'g-cloud', perm: 'play:record' }
  },
  {
    path: 'cloud',
    name: 'cloud',
    component: () => import('@/views/CloudRecordChannelView.vue'),
    meta: { title: '云端录像', icon: 'Cloudy', group: 'g-cloud', perm: 'cloud:read' }
  },
  {
    // 云端录像详情：某通道的时间轴视图（对齐 LiveGBS 的 timeview）
    path: 'cloud/detail',
    name: 'cloud-detail',
    component: () => import('@/views/CloudRecordDetailView.vue'),
    meta: { title: '云端录像详情', hidden: true, perm: 'cloud:read' }
  },
  {
    // 云端录像列表视图：独立表格页（对齐 LiveGBS 的 listview）
    path: 'cloud/detail/list',
    name: 'cloud-detail-list',
    component: () => import('@/views/CloudRecordListView.vue'),
    meta: { title: '云端录像列表', hidden: true, perm: 'cloud:read' }
  },
  {
    // 录像计划：定义哪些通道在什么时段录云端录像（对齐 LiveGBS）
    path: 'cloud/plans',
    name: 'cloud-plans',
    component: () => import('@/views/CloudRecordPlanView.vue'),
    meta: { title: '录像计划', hidden: true, perm: 'cloud:read' }
  },
  {
    path: 'alarms',
    name: 'alarms',
    component: () => import('@/views/AlarmView.vue'),
    meta: { title: '报警管理', icon: 'Bell', group: 'g-ops', perm: 'alarm:read' }
  },
  {
    path: 'cascade',
    name: 'cascade',
    component: () => import('@/views/CascadeView.vue'),
    meta: { title: '级联配置', icon: 'Share', group: 'g-ops', perm: 'cascade:read' }
  },
  {
    path: 'users',
    name: 'users',
    component: () => import('@/views/UserView.vue'),
    meta: { title: '用户管理', icon: 'User', group: 'g-system', perm: 'user:read' }
  },
  {
    path: 'roles',
    name: 'roles',
    component: () => import('@/views/RoleView.vue'),
    meta: { title: '角色权限', icon: 'Key', group: 'g-system', perm: 'user:read' }
  },
  {
    path: 'menus',
    name: 'menus',
    component: () => import('@/views/MenuView.vue'),
    // 菜单维护改的是全站导航，与系统配置同级；只改「怎么叫」，不涉及权限
    meta: { title: '菜单维护', icon: 'Menu', group: 'g-system', perm: 'system:read' }
  },
  {
    path: 'oplogs',
    name: 'oplogs',
    component: () => import('@/views/OpLogView.vue'),
    meta: { title: '操作日志', icon: 'Document', group: 'g-system', perm: 'oplog:read' }
  },
  {
    path: 'settings',
    name: 'settings',
    component: () => import('@/views/SettingsView.vue'),
    meta: { title: '系统设置', icon: 'Setting', group: 'g-system', perm: 'system:read' }
  }
]

/**
 * 覆盖值索引：目录行（{@code menuType='D'}）与菜单行（{@code 'M'}）分开。
 *
 * <p>后端只有一张 {@code gb_menu} 表，目录与菜单项混在同一个数组里，
 * 所以这里按类型分拣一次。未知 key、未知类型一律忽略 ——
 * 代码回滚后库里可能残留已删掉的菜单项，静默丢弃比报错安全。
 */
function indexOverrides(config) {
  const dirs = new Map()
  const menus = new Map()
  ;(config?.items || []).forEach((o) => {
    if (!o || !o.menuKey) return
    const key = String(o.menuKey).toLowerCase()
    if (o.menuType === 'D') {
      dirs.set(key, o)
    } else if (o.menuType === 'M' || !o.menuType) {
      menus.set(key, o)
    }
  })
  return { dirs, menus }
}

/** 菜单项（已合并覆盖值，但未过滤权限）—— 供「菜单维护」页展示。 */
function resolveItems(config) {
  const { menus } = indexOverrides(config)
  const out = []
  appRoutes.forEach((r, idx) => {
    if (r.meta?.hidden) return
    const ov = menus.get(r.name) || {}
    out.push({
      name: r.name,
      path: '/' + r.path,
      perm: r.meta.perm,
      /** 默认值：页面上做占位符、也给「恢复默认」用。 */
      defaultTitle: r.meta.title,
      defaultIcon: r.meta.icon || 'Document',
      defaultGroup: r.meta.group,
      defaultSort: idx,
      /** 生效值。 */
      title: ov.title || r.meta.title,
      icon: ov.icon || r.meta.icon || 'Document',
      groupCode: ov.parentKey || r.meta.group,
      sortOrder: ov.sortOrder == null ? idx : ov.sortOrder,
      hidden: ov.hidden === true,
      customized: !!(ov.title || ov.icon || ov.parentKey || ov.sortOrder != null || ov.hidden != null)
    })
  })
  return out
}

/**
 * 分组（已合并覆盖值）。
 *
 * <p>分组就是 {@code gb_menu} 里 {@code menu_type='D'} 的行 ——
 * 与若依 {@code sys_menu} 的目录层同一个思路，不需要第二张表。
 * 代码里没有的目录编码但库里有的，也认（允许现场自建分组）。
 */
function resolveGroups(config) {
  const { dirs } = indexOverrides(config)
  const defaults = new Map()
  let order = 0
  appRoutes.forEach((r) => {
    const g = r.meta?.group
    if (g && !defaults.has(g)) {
      defaults.set(g, { code: g, title: DEFAULT_GROUP_TITLES[g] || g, defaultSort: order++ })
    }
  })
  // 库里可能出现代码里没有的目录（用户自建分组），也要能显示
  dirs.forEach((ov, code) => {
    if (!defaults.has(code)) {
      defaults.set(code, { code, title: ov.title || code, defaultSort: order++ })
    }
  })
  return [...defaults.values()].map((g) => {
    const ov = dirs.get(g.code) || {}
    return {
      code: g.code,
      defaultTitle: g.title,
      defaultSort: g.defaultSort,
      title: ov.title || g.title,
      sortOrder: ov.sortOrder == null ? g.defaultSort : ov.sortOrder,
      customized: !!(ov.title || ov.sortOrder != null)
    }
  })
}

/**
 * 「菜单维护」页的数据：全部菜单项 + 分组（含默认值与生效值）。
 *
 * @param {object} config 后端返回的覆盖配置 {@code {groups, items}}
 */
export function menuCatalog(config) {
  const items = resolveItems(config)
  const groups = resolveGroups(config).sort((a, b) => a.sortOrder - b.sortOrder)
  return {
    groups,
    items,
    /** 按分组挂好 children，直接喂给 el-table 的树形行。 */
    tree: groups.map((g) => ({
      ...g,
      rowKey: 'g:' + g.code,
      title: g.title,
      isGroup: true,
      children: items
        .filter((i) => i.groupCode === g.code)
        .sort((a, b) => a.sortOrder - b.sortOrder)
        .map((i) => ({ ...i, rowKey: 'i:' + i.name, isGroup: false }))
    }))
  }
}

/**
 * 生成侧栏菜单（已合并覆盖值，并过滤权限）。
 *
 * <p>权限判断始终用代码里的 {@code perm}：覆盖配置只能改「怎么显示」，
 * <b>不能放宽谁能看</b>。否则「菜单维护」就成了绕过 RBAC 的口子。
 *
 * @param {object} auth auth store（提供 {@code can(perm)}）
 * @param {object} [config] 后端返回的覆盖配置 {@code {groups, items}}
 * @returns {Array<{label: string, items: Array<{key: string, path: string, title: string, icon: string}>}>}
 */
export function buildMenu(auth, config) {
  const items = resolveItems(config).filter((i) => !i.hidden && auth.can(i.perm))
  const groupsById = new Map(resolveGroups(config).map((g) => [g.code, g]))

  const buckets = new Map()
  items.forEach((i) => {
    if (!buckets.has(i.groupCode)) buckets.set(i.groupCode, [])
    buckets.get(i.groupCode).push(i)
  })

  const used = [...buckets.keys()].map((code) => {
    const g = groupsById.get(code)
    return { code, title: g ? g.title : code, order: g ? g.sortOrder : 9999 }
  })
  used.sort((a, b) => a.order - b.order)

  return used.map((g) => ({
    label: g.title,
    items: buckets
      .get(g.code)
      .sort((a, b) => a.sortOrder - b.sortOrder)
      .map((i) => ({ key: i.name, path: i.path, title: i.title, icon: i.icon }))
  }))
}
