<template>
  <div class="shell" :class="{ 'is-collapsed': collapsed }">
    <!-- ============ 侧边导航 ============ -->
    <aside class="sidebar">
      <div class="brand">
        <div class="brand-mark">
          <el-icon :size="18"><VideoCamera /></el-icon>
        </div>
        <transition name="fade">
          <div v-show="!collapsed" class="brand-text">
            <span class="brand-name">GB28181</span>
            <span class="brand-sub">视频平台</span>
          </div>
        </transition>
      </div>

      <nav class="nav dark-scroll">
        <template v-for="group in menuGroups" :key="group.label">
          <div v-if="!collapsed" class="nav-group-label">{{ group.label }}</div>
          <router-link
            v-for="item in group.items"
            :key="item.path"
            :to="item.path"
            class="nav-item"
            :class="{ 'is-active': route.path === item.path }"
            :title="collapsed ? item.title : ''"
          >
            <el-icon class="nav-icon"><component :is="item.icon" /></el-icon>
            <transition name="fade">
              <span v-show="!collapsed" class="nav-label">{{ item.title }}</span>
            </transition>
            <span v-if="item.badge && !collapsed" class="nav-badge">{{ item.badge }}</span>
          </router-link>
        </template>
      </nav>

      <!-- 侧栏底部：系统状态 -->
      <div class="sidebar-foot">
        <div class="health" :class="healthClass">
          <span class="health-dot" />
          <transition name="fade">
            <span v-show="!collapsed" class="health-text">{{ healthText }}</span>
          </transition>
        </div>
      </div>
    </aside>

    <!-- ============ 主区域 ============ -->
    <div class="body">
      <!-- 顶栏 -->
      <header class="topbar">
        <div class="topbar-left">
          <button class="icon-btn" :title="collapsed ? '展开侧栏' : '收起侧栏'" @click="collapsed = !collapsed">
            <el-icon :size="17"><Fold v-if="!collapsed" /><Expand v-else /></el-icon>
          </button>
          <div class="crumbs">
            <h1 class="crumb-title">{{ route.meta.title || '总览' }}</h1>
          </div>
        </div>

        <div class="topbar-right">
          <!-- 在线设备概览 -->
          <div class="pill" :title="`在线设备 ${online}/${total}`">
            <span class="pill-dot" :class="online > 0 ? 'is-live' : ''" />
            <span class="pill-text">设备</span>
            <b class="pill-value">{{ online }}<i>/{{ total }}</i></b>
          </div>

          <!-- 当前平台编码 -->
          <div class="pill pill-quiet mono" :title="`平台编码 ${auth.serverInfo?.platformId ?? '-'}`">
            {{ auth.serverInfo?.platformId ?? '—' }}
          </div>

          <el-tooltip content="刷新服务器信息" placement="bottom">
            <button class="icon-btn" :loading="refreshing" @click="onRefreshInfo">
              <el-icon :size="16"><Refresh /></el-icon>
            </button>
          </el-tooltip>

          <div class="divider" />

          <el-dropdown trigger="click" @command="onCommand">
            <div class="user">
              <div class="user-avatar">{{ initial }}</div>
              <div class="user-meta">
                <span class="user-name">{{ displayName }}</span>
                <span class="user-role">{{ isAdmin ? '管理员' : '普通用户' }}</span>
              </div>
              <el-icon class="user-caret" :size="12"><ArrowDown /></el-icon>
            </div>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="settings">
                  <el-icon><Setting /></el-icon> 系统设置
                </el-dropdown-item>
                <el-dropdown-item command="logout" divided>
                  <el-icon><SwitchButton /></el-icon> 退出登录
                </el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </header>

      <!-- 内容区 -->
      <main class="content">
        <router-view v-slot="{ Component }">
          <transition name="fade-slide" mode="out-in">
            <component :is="Component" />
          </transition>
        </router-view>
      </main>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()

const collapsed = ref(false)
const refreshing = ref(false)

// 断点以下自动收起侧栏，避免小屏被导航挤占
function syncViewport() {
  collapsed.value = window.innerWidth < 1024
}
window.addEventListener('resize', syncViewport)
syncViewport()

const allItems = [
  { path: '/dashboard', title: '总览', icon: 'Odometer', group: '监控' },
  { path: '/live', title: '实时预览', icon: 'VideoCamera', group: '监控' },
  { path: '/devices', title: '设备管理', icon: 'Monitor', group: '资源' },
  { path: '/channels', title: '通道管理', icon: 'List', group: '资源' },
  { path: '/playback', title: '设备录像', icon: 'Film', group: '录像' },
  { path: '/cloud', title: '云端录像', icon: 'Cloudy', group: '录像' },
  { path: '/alarms', title: '报警管理', icon: 'Bell', group: '运维' },
  { path: '/cascade', title: '级联配置', icon: 'Share', group: '运维' },
  { path: '/users', title: '用户管理', icon: 'User', group: '系统', admin: true },
  { path: '/settings', title: '系统设置', icon: 'Setting', group: '系统' }
]

const isAdmin = computed(() => auth.isAdmin())

/** 按 group 字段聚合成带分组标题的菜单 */
const menuGroups = computed(() => {
  const visible = allItems.filter((i) => !i.admin || isAdmin.value)
  const map = new Map()
  visible.forEach((item) => {
    if (!map.has(item.group)) map.set(item.group, [])
    map.get(item.group).push(item)
  })
  return [...map.entries()].map(([label, items]) => ({ label, items }))
})

const online = computed(() => auth.serverInfo?.deviceOnline ?? 0)
const total = computed(() => auth.serverInfo?.deviceTotal ?? 0)

const healthText = computed(() => {
  if (!auth.serverInfo) return '服务未就绪'
  if (online === 0) return '无设备在线'
  if (total > 0 && online < total) return `部分离线 ${online}/${total}`
  return '运行正常'
})

const healthClass = computed(() => {
  if (!auth.serverInfo) return 'is-idle'
  if (online === 0) return 'is-warn'
  if (total > 0 && online < total) return 'is-warn'
  return 'is-ok'
})

const displayName = computed(() => auth.user?.nickname || auth.user?.username || '未登录')
const initial = computed(() => displayName.value.charAt(0).toUpperCase())

async function loadServerInfo() {
  try {
    await auth.loadServerInfo()
  } catch {
    // 服务信息失败不影响主流程
  }
}

async function onRefreshInfo() {
  refreshing.value = true
  await loadServerInfo()
  refreshing.value = false
  ElMessage.success('服务器信息已刷新')
}

async function onCommand(command) {
  if (command === 'settings') {
    router.push('/settings')
  } else if (command === 'logout') {
    await auth.logout()
    router.push('/login')
  }
}

onMounted(async () => {
  if (auth.loggedIn) {
    await auth.loadUser()
    await loadServerInfo()
  }
})
</script>

<style scoped>
.shell {
  display: flex;
  height: 100vh;
  overflow: hidden;
  background: var(--bg-app);
}

/* ============ 侧栏 ============ */

.sidebar {
  width: var(--sidebar-w);
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  background: var(--bg-sidebar);
  transition: width 0.2s cubic-bezier(0.4, 0, 0.2, 1);
  overflow: hidden;
}

.is-collapsed .sidebar {
  width: var(--sidebar-w-collapsed);
}

/* 品牌区 */
.brand {
  height: var(--header-h);
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 0 14px;
  flex-shrink: 0;
  border-bottom: 1px solid rgba(255, 255, 255, 0.07);
}

.brand-mark {
  width: 30px;
  height: 30px;
  border-radius: 8px;
  background: linear-gradient(135deg, var(--brand-400), var(--brand-600));
  display: flex;
  align-items: center;
  justify-content: center;
  color: #fff;
  flex-shrink: 0;
  box-shadow: 0 2px 8px rgba(47, 109, 246, 0.4);
}

.brand-text {
  display: flex;
  flex-direction: column;
  line-height: 1.15;
  white-space: nowrap;
}

.brand-name {
  font-size: 14px;
  font-weight: 700;
  color: #fff;
  letter-spacing: 0.3px;
}

.brand-sub {
  font-size: 10.5px;
  color: rgba(255, 255, 255, 0.42);
  letter-spacing: 0.5px;
}

/* 导航 */
.nav {
  flex: 1;
  overflow-y: auto;
  overflow-x: hidden;
  padding: 10px 8px 16px;
}

.nav-group-label {
  font-size: 10.5px;
  font-weight: 600;
  letter-spacing: 1px;
  color: rgba(255, 255, 255, 0.3);
  padding: 14px 10px 6px;
  text-transform: uppercase;
  white-space: nowrap;
}

.nav-item {
  position: relative;
  display: flex;
  align-items: center;
  gap: 10px;
  height: 38px;
  padding: 0 10px;
  margin-bottom: 2px;
  border-radius: 8px;
  color: rgba(255, 255, 255, 0.68);
  text-decoration: none;
  font-size: 13px;
  white-space: nowrap;
  transition: background 0.14s ease, color 0.14s ease;
}

.nav-item:hover {
  background: var(--bg-sidebar-hover);
  color: #fff;
}

.nav-item.is-active {
  background: var(--bg-sidebar-active);
  color: #fff;
  font-weight: 500;
  box-shadow: 0 2px 10px rgba(30, 84, 212, 0.35);
}

/* 激活态左侧高亮条 */
.nav-item.is-active::before {
  content: '';
  position: absolute;
  left: -8px;
  top: 50%;
  transform: translateY(-50%);
  width: 3px;
  height: 18px;
  border-radius: 0 3px 3px 0;
  background: #fff;
}

.nav-icon {
  font-size: 16px;
  flex-shrink: 0;
}

.nav-label {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
}

.nav-badge {
  padding: 0 6px;
  height: 17px;
  line-height: 17px;
  border-radius: var(--r-full);
  background: var(--danger-500);
  color: #fff;
  font-size: 10.5px;
  font-weight: 600;
}

/* 侧栏底部状态 */
.sidebar-foot {
  flex-shrink: 0;
  padding: 10px 12px;
  border-top: 1px solid rgba(255, 255, 255, 0.07);
}

.health {
  display: flex;
  align-items: center;
  gap: 8px;
  height: 30px;
  padding: 0 10px;
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.04);
  font-size: 12px;
  white-space: nowrap;
  overflow: hidden;
}

.health-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  flex-shrink: 0;
}

.health.is-ok .health-dot { background: var(--success-500); box-shadow: 0 0 0 3px rgba(18, 161, 80, 0.18); }
.health.is-warn .health-dot { background: var(--warning-500); box-shadow: 0 0 0 3px rgba(224, 140, 0, 0.18); }
.health.is-idle .health-dot { background: var(--info-500); }

.health.is-ok .health-text { color: rgba(255, 255, 255, 0.7); }
.health.is-warn .health-text { color: #f0b357; }
.health.is-idle .health-text { color: rgba(255, 255, 255, 0.4); }

/* ============ 主体 ============ */

.body {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.topbar {
  height: var(--header-h);
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sp-4);
  padding: 0 var(--sp-5);
  background: var(--bg-surface);
  border-bottom: 1px solid var(--border-color);
}

.topbar-left {
  display: flex;
  align-items: center;
  gap: var(--sp-3);
  min-width: 0;
}

.topbar-right {
  display: flex;
  align-items: center;
  gap: 10px;
}

.crumb-title {
  margin: 0;
  font-size: 15.5px;
  font-weight: 650;
  color: var(--text-primary);
  letter-spacing: -0.2px;
  white-space: nowrap;
}

.icon-btn {
  width: 30px;
  height: 30px;
  display: flex;
  align-items: center;
  justify-content: center;
  border: none;
  border-radius: var(--r-md);
  background: transparent;
  color: var(--ink-500);
  cursor: pointer;
  transition: background 0.14s ease, color 0.14s ease;
}

.icon-btn:hover {
  background: var(--ink-100);
  color: var(--brand-600);
}

/* 状态胶囊 */
.pill {
  display: flex;
  align-items: center;
  gap: 6px;
  height: 28px;
  padding: 0 10px;
  border-radius: var(--r-full);
  background: var(--ink-50);
  border: 1px solid var(--ink-100);
  font-size: 12px;
  color: var(--ink-600);
  white-space: nowrap;
}

.pill-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--ink-300);
}

.pill-dot.is-live {
  background: var(--success-500);
  animation: pulse 2s ease-in-out infinite;
}

.pill-value {
  font-weight: 650;
  color: var(--text-primary);
  font-variant-numeric: tabular-nums;
}

.pill-value i {
  font-style: normal;
  font-weight: 400;
  color: var(--text-tertiary);
}

.pill-quiet {
  color: var(--text-secondary);
  max-width: 190px;
  overflow: hidden;
  text-overflow: ellipsis;
}

.divider {
  width: 1px;
  height: 20px;
  background: var(--border-color);
}

/* 用户菜单 */
.user {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 3px 8px 3px 3px;
  border-radius: var(--r-full);
  cursor: pointer;
  outline: none;
  transition: background 0.14s ease;
}

.user:hover {
  background: var(--ink-50);
}

.user-avatar {
  width: 28px;
  height: 28px;
  border-radius: 50%;
  background: linear-gradient(135deg, var(--brand-400), var(--brand-600));
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 13px;
  font-weight: 600;
  flex-shrink: 0;
}

.user-meta {
  display: flex;
  flex-direction: column;
  line-height: 1.2;
}

.user-name {
  font-size: 12.5px;
  font-weight: 550;
  color: var(--text-primary);
}

.user-role {
  font-size: 10.5px;
  color: var(--text-tertiary);
}

.user-caret {
  color: var(--ink-400);
}

/* 内容区 */
.content {
  flex: 1;
  overflow-y: auto;
  padding: var(--sp-5) var(--sp-6) var(--sp-8);
}

/* ============ 过渡 ============ */

.fade-enter-active,
.fade-leave-active {
  transition: opacity 0.15s ease;
}

.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}

.fade-slide-enter-active {
  transition: opacity 0.18s ease, transform 0.18s ease;
}

.fade-slide-leave-active {
  transition: opacity 0.1s ease;
}

.fade-slide-enter-from {
  opacity: 0;
  transform: translateY(6px);
}

.fade-slide-leave-to {
  opacity: 0;
}

/* ============ 响应式 ============ */

@media (max-width: 900px) {
  .content {
    padding: var(--sp-4) var(--sp-4) var(--sp-6);
  }
  .user-meta {
    display: none;
  }
  .pill-quiet {
    display: none;
  }
}
</style>
