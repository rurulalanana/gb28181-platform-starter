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
 * <p>{@code hidden: true} 的路由（云端录像详情/列表/计划）不进菜单，
 * 但保留 {@code perm}，所以直输 URL 仍然受路由守卫管辖。
 */

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
    meta: { title: '总览', icon: 'Odometer', group: '监控', perm: 'device:read' }
  },
  {
    path: 'live',
    name: 'live',
    component: () => import('@/views/LiveView.vue'),
    meta: { title: '实时预览', icon: 'VideoCamera', group: '监控', perm: 'play:live' }
  },
  {
    path: 'devices',
    name: 'devices',
    component: () => import('@/views/DeviceView.vue'),
    meta: { title: '设备管理', icon: 'Monitor', group: '资源', perm: 'device:read' }
  },
  {
    path: 'channels',
    name: 'channels',
    component: () => import('@/views/ChannelView.vue'),
    meta: { title: '通道管理', icon: 'List', group: '资源', perm: 'channel:read' }
  },
  {
    path: 'playback',
    name: 'playback',
    component: () => import('@/views/PlaybackView.vue'),
    meta: { title: '设备录像', icon: 'Film', group: '录像', perm: 'play:record' }
  },
  {
    path: 'cloud',
    name: 'cloud',
    component: () => import('@/views/CloudRecordChannelView.vue'),
    meta: { title: '云端录像', icon: 'Cloudy', group: '录像', perm: 'cloud:read' }
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
    meta: { title: '报警管理', icon: 'Bell', group: '运维', perm: 'alarm:read' }
  },
  {
    path: 'cascade',
    name: 'cascade',
    component: () => import('@/views/CascadeView.vue'),
    meta: { title: '级联配置', icon: 'Share', group: '运维', perm: 'cascade:read' }
  },
  {
    path: 'users',
    name: 'users',
    component: () => import('@/views/UserView.vue'),
    meta: { title: '用户管理', icon: 'User', group: '系统', perm: 'user:read' }
  },
  {
    path: 'roles',
    name: 'roles',
    component: () => import('@/views/RoleView.vue'),
    meta: { title: '角色权限', icon: 'Key', group: '系统', perm: 'user:read' }
  },
  {
    path: 'oplogs',
    name: 'oplogs',
    component: () => import('@/views/OpLogView.vue'),
    meta: { title: '操作日志', icon: 'Document', group: '系统', perm: 'oplog:read' }
  },
  {
    path: 'settings',
    name: 'settings',
    component: () => import('@/views/SettingsView.vue'),
    meta: { title: '系统设置', icon: 'Setting', group: '系统', perm: 'system:read' }
  }
]

/**
 * 生成侧栏菜单（已过滤权限，按 group 聚合）。
 *
 * <p>放在这里而不是组件里，是为了让「菜单有哪些项」这件事可以在别处复用
 * （例如无权限时的兜底跳转要按菜单顺序找第一个可进页面）。
 *
 * @param {object} auth auth store（提供 {@code can(perm)}）
 * @returns {Array<{label: string, items: Array<{path: string, title: string, icon: string}>}>}
 */
export function buildMenu(auth) {
  const visible = appRoutes
    .filter((r) => !r.meta?.hidden)
    .filter((r) => auth.can(r.meta?.perm))
    .map((r) => ({
      path: '/' + r.path,
      title: r.meta.title,
      icon: r.meta.icon || 'Document',
      group: r.meta.group || '其它'
    }))

  const groups = new Map()
  visible.forEach((item) => {
    if (!groups.has(item.group)) groups.set(item.group, [])
    groups.get(item.group).push(item)
  })
  return [...groups.entries()].map(([label, items]) => ({ label, items }))
}
