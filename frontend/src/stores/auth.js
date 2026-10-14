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

  function isAdmin() {
    return user.value && user.value.role === 'admin'
  }

  return { token, user, serverInfo, loggedIn, login, logout, loadUser, loadServerInfo, isAdmin }
})
