<template>
  <div class="shell" :class="{ 'is-collapsed': collapsed }">
    <a class="skip-link" href="#main-content">跳到主内容</a>
    <!-- ============ 侧边导航 ============ -->
    <aside class="sidebar">
      <div class="brand">
        <div class="brand-mark">
          <el-icon :size="18" aria-hidden="true"><VideoCamera /></el-icon>
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
            :key="item.key"
            :to="item.path"
            class="nav-item"
            :class="{ 'is-active': route.path === item.path }"
            :aria-current="route.path === item.path ? 'page' : undefined"
            :title="collapsed ? item.title : ''"
          >
            <el-icon class="nav-icon" aria-hidden="true"><component :is="item.icon" /></el-icon>
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
          <span class="health-dot" aria-hidden="true" />
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
          <button
            class="icon-btn"
            :aria-label="collapsed ? '展开侧栏' : '收起侧栏'"
            :aria-expanded="!collapsed"
            :title="collapsed ? '展开侧栏' : '收起侧栏'"
            @click="collapsed = !collapsed"
          >
            <el-icon :size="18" aria-hidden="true"><Fold v-if="!collapsed" /><Expand v-else /></el-icon>
          </button>

          <!-- RuoYi 顶部面包屑：首页 / 分组 / 当前页 -->
          <nav class="crumb" aria-label="面包屑">
            <span class="crumb-item">首页</span>
            <el-icon class="crumb-sep" aria-hidden="true"><ArrowRight /></el-icon>
            <template v-if="route.meta.group">
              <span class="crumb-item">{{ groupTitle }}</span>
              <el-icon class="crumb-sep" aria-hidden="true"><ArrowRight /></el-icon>
            </template>
            <span class="crumb-current">{{ route.meta.title || '总览' }}</span>
          </nav>
        </div>

        <div class="topbar-right">
          <!-- 状态读数：形如参考站的「接收 8.7 Mbps」，这里放设备/通道/推流 -->
          <div class="top-readout" :title="`在线设备 ${online} / 设备总数 ${total}`">
            <span>设备</span>
            <span class="val">{{ online }}<i>/{{ total }}</i></span>
          </div>
          <div class="top-readout" :title="`在线通道 ${channelOnline} / 通道总数 ${channelTotal}`">
            <span>通道</span>
            <span class="val">{{ channelOnline }}<i>/{{ channelTotal }}</i></span>
          </div>
          <div class="top-readout" :title="`当前推流 ${streamCount} 路`">
            <span>推流</span>
            <span class="val">{{ streamCount }}</span>
          </div>

          <el-tooltip content="刷新服务器信息" placement="bottom">
            <button
              class="icon-btn"
              :disabled="refreshing"
              aria-label="刷新服务器信息"
              :aria-busy="refreshing"
              @click="onRefreshInfo"
            >
              <el-icon :size="16" aria-hidden="true"><Refresh /></el-icon>
            </button>
          </el-tooltip>

          <!-- 后台下载任务：任何页面都能看进度、取已完成文件 -->
          <DownloadTasks />

          <div class="divider" />

          <el-dropdown trigger="click" @command="onCommand">
            <div class="user" aria-haspopup="menu">
              <span class="user-welcome">欢迎,</span>
              <span class="user-name">{{ displayName }}</span>
              <el-icon class="user-caret" :size="12" aria-hidden="true"><ArrowDown /></el-icon>
            </div>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item v-if="auth.can('system:read')" command="settings">
                  <el-icon aria-hidden="true"><Setting /></el-icon> 系统设置
                </el-dropdown-item>
                <el-dropdown-item command="logout" divided>
                  <el-icon aria-hidden="true"><SwitchButton /></el-icon> 退出登录
                </el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </header>

      <!-- 内容区 -->
      <main id="main-content" class="content" tabindex="-1">
        <router-view v-slot="{ Component }">
          <transition name="fade-slide" mode="out-in">
            <component :is="Component" />
          </transition>
        </router-view>

        <footer class="app-footer">
          GB/T 28181—2022 视频监控平台 ·
          <span class="mono">{{ auth.serverInfo?.platformId ?? '—' }}</span>
        </footer>
      </main>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '@/stores/auth'
import { buildMenu } from '@/router/routes'
import { useDownloadStore } from '@/stores/download'
import DownloadTasks from '@/components/DownloadTasks.vue'

const auth = useAuthStore()
const downloads = useDownloadStore()
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

/*
 * 菜单完全由后端 gb_menu 决定：目录行 → 分组，菜单行 → 页面，按钮行不进侧栏。
 * buildMenu 按当前账号权限过滤后分组。
 */
const menuGroups = computed(() => buildMenu(auth, auth.menuRows))

/** 面包屑里的分组名：动态路由已把目录标题写进 {@code meta.group}。 */
const groupTitle = computed(() => route.meta.group || '')

const online = computed(() => auth.serverInfo?.deviceOnline ?? 0)
const total = computed(() => auth.serverInfo?.deviceTotal ?? 0)
const channelOnline = computed(() => auth.serverInfo?.channelOnline ?? 0)
const channelTotal = computed(() => auth.serverInfo?.channelTotal ?? 0)
const streamCount = computed(() => auth.serverInfo?.streamCount ?? 0)

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
    // 下载任务是全局的，轮询挂在布局层，切页面也继续显示进度
    downloads.startPolling()
  }
})

onUnmounted(() => {
  // 退出登录 / 卸载布局时停掉轮询，避免未授权请求刷屏
  downloads.stopPolling()
})
</script>

<style scoped>
.shell {
  display: flex;
  gap: 12px;
  height: 100vh;
  padding: 12px;
  overflow: hidden;
  background: var(--bg-app);
}

/* 键盘用户的第一落点：平时不可见，聚焦后浮在左上角 */
.skip-link {
  position: absolute;
  top: 8px;
  left: 8px;
  z-index: 100;
  padding: 6px 12px;
  border-radius: var(--r-md);
  background: var(--bg-surface);
  color: var(--brand-700);
  font-size: var(--fs-sm);
  text-decoration: none;
  box-shadow: var(--sh-md);
  transform: translateY(-200%);
}

.skip-link:focus-visible {
  transform: translateY(0);
}

/* ============ 侧栏 ============ */

.sidebar {
  width: var(--sidebar-w);
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  /* RuoYi 侧栏：独立的深色圆角面板，不是贴边的整条 */
  background: var(--bg-sidebar);
  border-radius: var(--r-panel);
  box-shadow: 0 8px 24px rgba(15, 23, 42, 0.12);
  transition: width 0.28s cubic-bezier(0.4, 0, 0.2, 1);
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
  padding: 0 12px;
  flex-shrink: 0;
  border-bottom: 1px solid rgba(255, 255, 255, 0.07);
}

.brand-mark {
  width: 24px;
  height: 24px;
  border-radius: var(--r-md);
  background: var(--brand-500);
  display: flex;
  align-items: center;
  justify-content: center;
  color: #fff;
  flex-shrink: 0;
}

.brand-text {
  display: flex;
  align-items: baseline;
  gap: 5px;
  line-height: 1;
  white-space: nowrap;
  overflow: hidden;
}

.brand-name {
  font-family: var(--mono);
  font-size: var(--fs-md);
  font-weight: 700;
  color: #fff;
  letter-spacing: -0.2px;
  flex-shrink: 0;
}

.brand-sub {
  font-size: var(--fs-xs);
  color: rgba(255, 255, 255, 0.4);
  white-space: nowrap;
}

/* 导航 */
.nav {
  flex: 1;
  overflow-y: auto;
  overflow-x: hidden;
  padding: 12px 8px 16px;
}

.nav-group-label {
  font-size: var(--fs-xs);
  font-weight: 500;
  color: rgba(255, 255, 255, 0.32);
  padding: 15px 10px 5px;
  white-space: nowrap;
  overflow: hidden;
}

.nav-item {
  position: relative;
  display: flex;
  align-items: center;
  gap: 10px;
  height: 40px;
  padding: 0 12px;
  margin: 3px 0;
  border-radius: var(--r-md);
  color: #e5edf8;
  text-decoration: none;
  font-size: var(--fs-md);
  white-space: nowrap;
  transition: background 0.2s ease, color 0.2s ease, box-shadow 0.2s ease;
}

.nav-item:hover {
  background: var(--bg-sidebar-hover);
  color: #fff;
}

.nav-item.is-active {
  background: var(--bg-sidebar-active);
  color: #fff;
  font-weight: 500;
  /* RuoYi：左侧 3px 实心亮条 + 一圈极淡描边 */
  box-shadow: inset 3px 0 0 var(--brand-500), inset 0 0 0 1px rgba(96, 165, 250, 0.24);
}

.nav-icon {
  font-size: 18px;
  flex-shrink: 0;
  color: rgba(255, 255, 255, 0.72);
}

.nav-item:hover .nav-icon,
.nav-item.is-active .nav-icon {
  color: inherit;
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

/*
 * 侧栏底部状态：3 种态（正常/部分离线/无设备）由 healthClass 决定，
 * 文字与圆点颜色同步。
 */
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
  font-size: var(--fs-xs);
  white-space: nowrap;
  overflow: hidden;
}

.health-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  flex-shrink: 0;
}

.health.is-ok .health-dot { background: var(--online-500); }
.health.is-warn .health-dot { background: var(--warning-500); }
.health.is-idle .health-dot { background: var(--info-500); }

.health.is-ok .health-text { color: rgba(255, 255, 255, 0.75); }
.health.is-warn .health-text { color: #f0b357; }
.health.is-idle .health-text { color: rgba(255, 255, 255, 0.4); }

/* ============ 主体 ============ */

.body {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 12px;
  min-width: 0;
}

.topbar {
  height: 50px;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sp-4);
  padding: 0 var(--sp-4);
  /* RuoYi 顶栏：白色圆角条 + 1px 描边 + 极轻投影 */
  background: var(--bg-surface);
  border: 1px solid var(--line);
  border-radius: var(--r-panel);
  box-shadow: var(--sh-panel);
}

.topbar-left {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  min-width: 0;
}

.crumb {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
  font-size: var(--fs-sm);
  color: var(--text-secondary);
  white-space: nowrap;
}

.crumb-item {
  color: var(--text-secondary);
}

.crumb-sep {
  font-size: 10px;
  color: var(--ink-300);
}

.crumb-current {
  color: var(--text-primary);
  font-weight: 500;
  overflow: hidden;
  text-overflow: ellipsis;
}

.topbar-right {
  display: flex;
  align-items: center;
  gap: 10px;
}

.crumb-title {
  margin: 0;
  font-size: var(--fs-lg);
  font-weight: 600;
  color: var(--text-primary);
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
  background: var(--ink-50);
  color: var(--accent-ink);
}

/* 顶栏读数标签：发丝线方角，不是圆润胶囊 */
.pill {
  display: flex;
  align-items: center;
  gap: 6px;
  height: 26px;
  padding: 0 9px;
  border-radius: var(--r-md);
  background: var(--white);
  border: 1px solid var(--line);
  font-size: var(--fs-xs);
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
  background: var(--online-500);
  animation: pulse 2s ease-in-out infinite;
}

.pill-value {
  font-family: var(--mono);
  font-weight: 600;
  font-size: var(--fs-sm);
  letter-spacing: -0.3px;
  color: var(--text-primary);
  font-variant-numeric: tabular-nums;
}

.pill-value i {
  font-style: normal;
  font-weight: 400;
  color: var(--text-tertiary);
}

.pill-quiet {
  color: var(--ink-500);
  font-size: var(--fs-xs);
  max-width: 200px;
  overflow: hidden;
  text-overflow: ellipsis;
}

.divider {
  width: 1px;
  height: 20px;
  background: var(--line);
}

/* 用户菜单 */
.user {
  display: flex;
  align-items: center;
  gap: 8px;
  height: 30px;
  padding: 0 8px 0 3px;
  border-radius: var(--r-md);
  cursor: pointer;
  transition: background 0.14s ease;
}

.user:hover {
  background: var(--ink-50);
}

.user-welcome {
  font-size: var(--fs-sm);
  color: var(--text-secondary);
}

.user-avatar {
  width: 24px;
  height: 24px;
  border-radius: var(--r-md);
  background: var(--brand-600);
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: var(--fs-xs);
  font-weight: 600;
  flex-shrink: 0;
}

.user-meta {
  display: flex;
  flex-direction: column;
  line-height: 1.2;
}

.user-name {
  font-size: var(--fs-sm);
  font-weight: 600;
  color: var(--text-primary);
}

.user-caret {
  color: var(--ink-400);
}

/* 内容区 */
.content {
  flex: 1;
  /* 关键：没有 min-height:0，flex 子项的默认 min-height:auto 会让
     内容撑破容器，视频墙这类「要吃满剩余高度」的页面就失效了 */
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow-y: auto;
  /* 外层已经有 12px 间隙，这里只留一点点呼吸位，页面自己用 .page 控制节奏 */
  padding: 2px 2px var(--sp-2);
}

/* router-view 的过渡组件需要能撑满 */
.content > * {
  flex-shrink: 0;
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
