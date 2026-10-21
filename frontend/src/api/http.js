import axios from 'axios'
import router from '@/router'

/**
 * 平台 API 客户端。
 *
 * 后端约定：
 * - 统一响应体 { code, msg, data }，HTTP 状态码恒为 200
 * - code=0 成功；401 未登录；403 无权限；400 参数错误；500 服务端异常
 * - 登录态用请求头 Authorization: <token>
 */
const http = axios.create({
  baseURL: '/api/v1',
  timeout: 30000
})

/** 登录态失效时避免重复弹窗。 */
let redirecting = false

http.interceptors.request.use((config) => {
  const token = localStorage.getItem('gb_token')
  if (token) {
    config.headers.Authorization = token
  }
  return config
})

http.interceptors.response.use(
  (response) => {
    const body = response.data
    // 二进制流（录像文件下载）直接返回
    if (!body || typeof body !== 'object' || body.code === undefined) {
      return body
    }
    if (body.code === 0) {
      return body.data
    }
    if (body.code === 401) {
      handleUnauthorized()
    }
    return Promise.reject(new Error(body.msg || '请求失败'))
  },
  (error) => {
    if (error.response && error.response.status === 401) {
      handleUnauthorized()
    }
    return Promise.reject(error)
  }
)

function handleUnauthorized() {
  localStorage.removeItem('gb_token')
  if (!redirecting && router.currentRoute.value.path !== '/login') {
    redirecting = true
    router.push('/login').finally(() => {
      redirecting = false
    })
  }
}

export default http

/** 通用请求方法（GET/POST）。 */
export function get(url, params) {
  return http.get(url, { params })
}

export function post(url, params, data, timeout) {
  return http.post(url, data, { params, timeout })
}
