import axios from 'axios'
import { ElMessage } from 'element-plus'
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

/**
 * 把 axios / 后端错误转成能直接展示给用户的中文文案。
 *
 * <p>为什么必须转换：调用方统一写 `ElMessage.error(e.message || '保存失败')`，
 * 而 axios 抛出的 `Error.message` 是英文 ——「Request failed with status code 500」
 * 「timeout of 30000ms exceeded」「Network Error」。直接弹出去用户看不懂，
 * 还会误以为是前端崩了。这里统一兜底，调用方拿到的一定是中文。
 *
 * @param {any} error axios 错误或后端返回的业务错误
 * @returns {string} 中文提示文案
 */
function friendlyMessage(error) {
  const resp = error?.response
  if (resp) {
    const body = resp.data
    // 后端把原因写在 msg 里（如「配置值必须是正整数」），优先原样展示
    if (body && typeof body === 'object' && typeof body.msg === 'string' && body.msg.trim()) {
      return body.msg.trim()
    }
    const status = resp.status
    if (status === 400) return '请求参数有误'
    if (status === 401) return '登录已失效，请重新登录'
    if (status === 403) return '没有权限执行该操作'
    if (status === 404) return '接口不存在或已下线'
    if (status === 413) return '提交内容过大'
    if (status === 429) return '请求过于频繁，请稍后再试'
    if (status >= 500) return `服务端异常（HTTP ${status}）`
    return `请求失败（HTTP ${status}）`
  }
  const code = error?.code
  if (code === 'ECONNABORTED' || code === 'ETIMEDOUT') {
    return '请求超时，请稍后重试'
  }
  const raw = String(error?.message || '')
  if (/Network Error/i.test(raw)) {
    // 服务正在重启或网络断开时最常见
    return '网络连接失败，请检查网络或服务是否在运行'
  }
  if (/timeout/i.test(raw)) {
    return '请求超时，请稍后重试'
  }
  return raw || '请求失败'
}

/**
 * 包装成带中文 message 的新 Error，同时保留原始 response / status，
 * 调用方若要判断状态码仍可读 `e.status`。
 */
function toFriendlyError(error) {
  const wrapped = new Error(friendlyMessage(error))
  wrapped.cause = error
  wrapped.status = error?.response?.status
  wrapped.code = error?.code
  return wrapped
}

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
    return Promise.reject(toFriendlyError(error))
  }
)

function handleUnauthorized() {
  localStorage.removeItem('gb_token')
  if (!redirecting && router.currentRoute.value.path !== '/login') {
    redirecting = true
    // 静默跳登录页时用户只看到「点了没反应」，必须给出原因
    ElMessage.warning('登录已失效，请重新登录')
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
