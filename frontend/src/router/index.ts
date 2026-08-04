import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '@/store/auth'

const LoginView = () => import('@/views/LoginView.vue')
const ChatView = () => import('@/views/ChatView.vue')
const KnowledgeView = () => import('@/views/KnowledgeView.vue')
const KnowledgeCardView = () => import('@/views/KnowledgeCardView.vue')
const EvalDashboardView = () => import('@/views/EvalDashboardView.vue')
const WorkspaceSettingsView = () => import('@/views/WorkspaceSettingsView.vue')

const API_BASE = (import.meta.env.VITE_API_BASE ?? '').replace(/\/$/, '')

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', name: 'login', component: LoginView, meta: { public: true } },
    { path: '/', redirect: '/chat' },
    { path: '/chat', name: 'chat', component: ChatView, meta: { keepAlive: true } },
    { path: '/knowledge', name: 'knowledge', component: KnowledgeView },
    { path: '/cards', name: 'cards', component: KnowledgeCardView, meta: { requiresAuth: true } },
    { path: '/eval', name: 'eval', component: EvalDashboardView, meta: { requiresAuth: true } },
    { path: '/settings/workspace', name: 'workspace-settings', component: WorkspaceSettingsView, meta: { requiresAuth: true } }
  ]
})

/**
 * 向后端验证 token 是否有效，避免 localStorage 残留过期令牌导致 401 闪退。
 * 网络异常或非 401 的服务端错误时放行（不让瞬断破坏本地会话）。
 */
async function verifyToken(token: string): Promise<boolean> {
  try {
    const res = await fetch(`${API_BASE}/api/auth/me`, {
      headers: { 'X-Auth-Token': token }
    })
    if (res.ok) {
      const me = await res.json()
      useAuthStore().applyProfile(me)
      return true
    }
    // 明确收到 401 才认为无效
    if (res.status === 401) return false
    return true
  } catch {
    return true // 网络异常时放行
  }
}

/**
 * 未登录禁止进入非公开页面；有 token 时向后端验证是否仍然有效。
 */
router.beforeEach(async (to, _from, next) => {
  const auth = useAuthStore()
  const token = auth.getToken()

  if (to.meta.public) {
    // 已登录用户访问登录页 → 验证 token 有效则直接跳转聊天页
    if (token && to.path === '/login') {
      if (await verifyToken(token)) {
        next('/chat')
        return
      }
      auth.clearSession()
    }
    next()
    return
  }

  if (!token) {
    next({ path: '/login', query: { redirect: to.fullPath } })
    return
  }

  if (!(await verifyToken(token))) {
    auth.clearSession()
    next({ path: '/login', query: { redirect: to.fullPath } })
    return
  }

  next()
})
