import { useAuthStore } from '@/store/auth'
import { router } from '@/router'

const API_BASE = (import.meta.env.VITE_API_BASE ?? '').replace(/\/$/, '')

export class ApiError extends Error {
  constructor(public readonly status: number, message: string, public readonly code?: string) {
    super(message)
    this.name = 'ApiError'
  }
}

export function apiUrl(path: string): string {
  return `${API_BASE}${path}`
}

/**
 * 带认证的 fetch 封装。
 * 自动附加 X-Auth-Token，并在收到 401 时清除登录态并跳转到登录页。
 */
export async function authFetch(path: string, init?: RequestInit): Promise<Response> {
  const auth = useAuthStore()
  const token = auth.getToken()

  const headers = new Headers(init?.headers)
  if (token) {
    headers.set('X-Auth-Token', token)
  }

  let resp: Response
  try {
    resp = await fetch(apiUrl(path), { ...init, headers })
  } catch {
    throw new ApiError(0, '网络连接失败，请检查网络后重试', 'NETWORK_ERROR')
  }

  if (resp.status === 401 && router.currentRoute.value.path !== '/login') {
    auth.clearSession()
    await router.replace('/login')
  }

  return resp
}

/** 将后端统一错误响应转换为带状态码的异常，供页面明确提示 401/403/404。 */
export async function readApiError(response: Response): Promise<ApiError> {
  const data = await response.json().catch(() => ({})) as { message?: string; code?: string; error?: string }
  const message = data.message?.trim() || defaultErrorMessage(response.status)
  return new ApiError(response.status, message, data.code ?? data.error)
}

function defaultErrorMessage(status: number): string {
  if (status === 401) return '登录已失效，请重新登录'
  if (status === 403) return '当前 Workspace 权限不足'
  if (status === 404) return '资源不存在或当前无权访问'
  return `请求失败（HTTP ${status}）`
}
