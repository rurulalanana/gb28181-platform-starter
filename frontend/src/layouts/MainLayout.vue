<template>
  <el-container class="layout">
    <!-- 侧边导航 -->
    <el-aside :width="collapsed ? '64px' : '220px'" class="aside">
      <div class="logo">
        <el-icon :size="26" color="#409eff"><VideoCamera /></el-icon>
        <span v-show="!collapsed" class="logo-text">GB28181 平台</span>
      </div>

      <el-menu
        :default-active="route.path"
        :collapse="collapsed"
        :collapse-transition="false"
        router
        class="menu"
      >
        <el-menu-item v-for="item in menuItems" :key="item.path" :index="item.path">
          <el-icon><component :is="item.icon" /></el-icon>
          <template #title>{{ item.title }}</template>
        </el-menu-item>
      </el-menu>
    </el-aside>

    <el-container>
      <!-- 顶栏 -->
      <el-header class="header">
        <div class="header-left">
          <el-icon class="collapse-btn" :size="20" @click="collapsed = !collapsed">
            <Fold v-if="!collapsed" />
            <Expand v-else />
          </el-icon>
          <span class="page-title">{{ route.meta.title || 'GB28181 平台' }}</span>
        </div>

        <div class="header-right">
          <el-tag v-if="auth.serverInfo" type="info" effect="plain" size="small" class="mr-2">
            {{ auth.serverInfo.platformId }}
          </el-tag>
          <el-tag v-if="auth.serverInfo" type="success" effect="plain" size="small" class="mr-2">
            设备 {{ auth.serverInfo.deviceOnline ?? 0 }}/{{ auth.serverInfo.deviceTotal ?? 0 }} 在线
          </el-tag>

          <el-dropdown @command="onCommand">
            <span class="user">
              <el-avatar :size="28" class="user-avatar">
                <el-icon><UserFilled /></el-icon>
              </el-avatar>
              <span class="user-name">
                {{ auth.user ? auth.user.nickname || auth.user.username : '未登录' }}
              </span>
              <el-icon><ArrowDown /></el-icon>
            </span>
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
      </el-header>

      <!-- 内容区 -->
      <el-main class="main">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()

const collapsed = ref(false)

const allItems = [
  { path: '/dashboard', title: '总览', icon: 'Odometer' },
  { path: '/live', title: '实时预览', icon: 'VideoCamera' },
  { path: '/control', title: '设备控制', icon: 'Aim' },
  { path: '/devices', title: '设备管理', icon: 'Monitor' },
  { path: '/channels', title: '通道管理', icon: 'List' },
  { path: '/playback', title: '设备录像', icon: 'Film' },
  { path: '/cloud', title: '云端录像', icon: 'Cloudy' },
  { path: '/alarms', title: '报警管理', icon: 'Bell' },
  { path: '/cascade', title: '级联配置', icon: 'Share' },
  { path: '/users', title: '用户管理', icon: 'User', admin: true },
  { path: '/settings', title: '系统设置', icon: 'Setting' }
]

const menuItems = computed(() => allItems.filter((i) => !i.admin || auth.isAdmin()))

onMounted(async () => {
  if (auth.loggedIn) {
    await auth.loadUser()
    try {
      await auth.loadServerInfo()
    } catch (e) {
      // 服务器信息加载失败不影响使用
    }
  }
})

async function onCommand(command) {
  if (command === 'settings') {
    router.push('/settings')
  } else if (command === 'logout') {
    await auth.logout()
    router.push('/login')
  }
}
</script>

<style scoped>
.layout {
  height: 100vh;
}

.aside {
  background: #fff;
  border-right: 1px solid #e4e7ed;
  transition: width 0.2s;
  overflow-x: hidden;
}

.logo {
  height: 60px;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 0 20px;
  border-bottom: 1px solid #e4e7ed;
  white-space: nowrap;
  overflow: hidden;
}

.logo-text {
  font-size: 16px;
  font-weight: 600;
  color: #303133;
}

.menu {
  border-right: none;
  padding: 8px;
}

.menu :deep(.el-menu-item) {
  height: 46px;
  line-height: 46px;
  border-radius: 8px;
  margin-bottom: 4px;
}

.menu :deep(.el-menu-item.is-active) {
  background: #ecf5ff;
  font-weight: 600;
}

.header {
  background: #fff;
  border-bottom: 1px solid #e4e7ed;
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: 60px;
  padding: 0 24px;
}

.header-left {
  display: flex;
  align-items: center;
  gap: 14px;
}

.collapse-btn {
  cursor: pointer;
  color: #606266;
}

.collapse-btn:hover {
  color: #409eff;
}

.page-title {
  font-size: 16px;
  font-weight: 600;
}

.header-right {
  display: flex;
  align-items: center;
}

.user {
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
  outline: none;
  padding: 6px 10px;
  border-radius: 6px;
  transition: background 0.15s;
}

.user:hover {
  background: #f5f7fa;
}

.user-avatar {
  background: #409eff;
}

.user-name {
  font-size: 14px;
  color: #303133;
}

.main {
  padding: 22px 24px 32px;
  overflow-y: auto;
}
</style>
