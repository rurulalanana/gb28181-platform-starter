import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { systemApi } from '@/api'

/**
 * 登录态与当前用户。
 */
export const useAuthStore = defineStore('auth', () => {
  const token = ref(localStorage.getItem('gb_token') || '')
  const user = ref(null)
  const serverInfo = ref(null)

  const loggedIn = computed(() => !!token.value)

  async function login(username, password) {
    const data = await systemApi.login({ username, password, urlTokenOnly: false })
    token.value = data.token
    localStorage.setItem('gb_token', data.token)
    await loadUser()
    return data
  }

  async function loadUser() {
    try {
      user.value = await systemApi.userinfo()
    } catch (e) {
      user.value = null
    }
    return user.value
  }

  async function loadServerInfo() {
    serverInfo.value = await systemApi.serverinfo()
    return serverInfo.value
  }

  async function logout() {
    try {
      await systemApi.logout()
    } catch (e) {
      // 忽略登出异常
    }
    token.value = ''
    user.value = null
    localStorage.removeItem('gb_token')
  }

  /**
   * 是否管理员。
   *
   * <p>后端 {@code /userinfo} 返回的是 {@code roles: ["admin"]} 数组，
   * 这里同时兼容单数 {@code role} 字段，避免两种形态下判断结果不一致
   * （早期只认 {@code user.role}，导致管理员菜单永远不显示）。
   */
  function isAdmin() {
    const u = user.value
    if (!u) return false
    if (Array.isArray(u.roles)) {
      return u.roles.some((r) => String(r).toLowerCase() === 'admin')
    }
    if (u.role) {
      return String(u.role).toLowerCase() === 'admin'
    }
    return false
  }

  return { token, user, serverInfo, loggedIn, login, logout, loadUser, loadServerInfo, isAdmin }
})
