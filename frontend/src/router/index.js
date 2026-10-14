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
        path: 'control',
        name: 'control',
        component: () => import('@/views/ControlView.vue'),
        meta: { title: '设备控制', icon: 'mdi-gamepad-variant' }
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
        component: () => import('@/views/CloudRecordView.vue'),
        meta: { title: '云端录像', icon: 'mdi-cloud' }
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
