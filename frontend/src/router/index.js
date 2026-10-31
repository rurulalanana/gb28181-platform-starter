import { createRouter, createWebHistory } from 'vue-router'

/**
 * 路由表。除登录页外全部要求登录态。
 */
const routes = [
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
  },
  {
    path: '/',
    component: () => import('@/layouts/MainLayout.vue'),
    redirect: '/dashboard',
    children: [
      {
        path: 'dashboard',
        name: 'dashboard',
        component: () => import('@/views/DashboardView.vue'),
        meta: { title: '总览', icon: 'mdi-view-dashboard' }
      },
      {
        path: 'live',
        name: 'live',
        component: () => import('@/views/LiveView.vue'),
        meta: { title: '实时预览', icon: 'mdi-video' }
      },
      {
        path: 'devices',
        name: 'devices',
        component: () => import('@/views/DeviceView.vue'),
        meta: { title: '设备管理', icon: 'mdi-cctv' }
      },
      {
        path: 'channels',
        name: 'channels',
        component: () => import('@/views/ChannelView.vue'),
        meta: { title: '通道管理', icon: 'mdi-view-list' }
      },
      {
        path: 'playback',
        name: 'playback',
        component: () => import('@/views/PlaybackView.vue'),
        meta: { title: '设备录像', icon: 'mdi-history' }
      },
      {
        path: 'cloud',
        name: 'cloud',
        component: () => import('@/views/CloudRecordChannelView.vue'),
        meta: { title: '云端录像', icon: 'mdi-cloud' }
      },
      {
        // 云端录像详情：某通道的时间轴视图（对齐 LiveGBS 的 timeview）
        path: 'cloud/detail',
        name: 'cloud-detail',
        component: () => import('@/views/CloudRecordDetailView.vue'),
        meta: { title: '云端录像详情', hidden: true }
      },
      {
        // 云端录像列表视图：独立表格页（对齐 LiveGBS 的 listview）
        path: 'cloud/detail/list',
        name: 'cloud-detail-list',
        component: () => import('@/views/CloudRecordListView.vue'),
        meta: { title: '云端录像列表', hidden: true }
      },
      {
        // 录像计划：定义哪些通道在什么时段录云端录像（对齐 LiveGBS）
        path: 'cloud/plans',
        name: 'cloud-plans',
        component: () => import('@/views/CloudRecordPlanView.vue'),
        meta: { title: '录像计划', hidden: true }
      },
      {
        path: 'alarms',
        name: 'alarms',
        component: () => import('@/views/AlarmView.vue'),
        meta: { title: '报警管理', icon: 'mdi-bell-alert' }
      },
      {
        path: 'cascade',
        name: 'cascade',
        component: () => import('@/views/CascadeView.vue'),
        meta: { title: '级联配置', icon: 'mdi-sitemap' }
      },
      {
        path: 'users',
        name: 'users',
        component: () => import('@/views/UserView.vue'),
        meta: { title: '用户管理', icon: 'mdi-account-group', admin: true }
      },
      {
        path: 'oplogs',
        name: 'oplogs',
        component: () => import('@/views/OpLogView.vue'),
        meta: { title: '操作日志', icon: 'mdi-clipboard-text-clock' }
      },
      {
        path: 'settings',
        name: 'settings',
        component: () => import('@/views/SettingsView.vue'),
        meta: { title: '系统设置', icon: 'mdi-cog' }
      }
    ]
  },
  { path: '/:pathMatch(.*)*', redirect: '/dashboard' }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

router.beforeEach((to) => {
  const token = localStorage.getItem('gb_token')
  if (!to.meta.public && !token) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }
  if (to.path === '/login' && token) {
    return { path: '/dashboard' }
  }
  return true
})

export default router
