import { computed, ref, watch } from 'vue'
import { defineStore } from 'pinia'
import { useAuthStore } from './auth'
import { useChatStore } from './chat'
import * as workspaceApi from '@/api/workspace'
import type { WorkspaceRole, WorkspaceSummary } from '@/types/workspace'
import { canWorkspacePermission, type WorkspacePermission } from '@/utils/workspacePermissions'

/** Workspace 全局状态中心；切换失败时保留旧状态，成功后才提交新状态并清理作用域缓存。 */
export const useWorkspaceStore = defineStore('workspace', () => {
  const auth = useAuthStore()
  const currentWorkspace = ref<WorkspaceSummary | null>(null)
  const workspaceList = ref<WorkspaceSummary[]>([])
  const workspaceRole = ref<WorkspaceRole | null>(auth.workspaceRole)
  const loading = ref(false)
  const switching = ref(false)
  const initialized = ref(false)
  const version = ref(0)

  watch(() => auth.workspaceRole, (role) => {
    if (role && !currentWorkspace.value) workspaceRole.value = role
  })

  const workspaceId = computed(() => currentWorkspace.value?.id ?? auth.workspaceId)

  async function load(): Promise<void> {
    if (!auth.getToken()) return
    loading.value = true
    try {
      const [list, current] = await Promise.all([
        workspaceApi.listWorkspaces(),
        workspaceApi.getCurrentWorkspace()
      ])
      workspaceList.value = list
      commit(current.currentWorkspace, current.workspaceRole)
      initialized.value = true
    } finally {
      loading.value = false
    }
  }

  async function switchTo(targetId: string): Promise<void> {
    if (!targetId || switching.value || targetId === workspaceId.value) return
    switching.value = true
    try {
      const result = await workspaceApi.switchWorkspace(targetId)
      // 只有服务端确认 Session 已更新后才改变本地 Workspace，避免 UI 假切换。
      commit(result.currentWorkspace, result.workspaceRole)
      const chat = useChatStore()
      chat.resetWorkspaceScope()
      void chat.refreshSessions()
      version.value += 1
    } finally {
      switching.value = false
    }
  }

  function commit(summary: WorkspaceSummary, role: WorkspaceRole): void {
    currentWorkspace.value = summary
    workspaceRole.value = role
    workspaceList.value = workspaceList.value.map((item) =>
      item.id === summary.id ? { ...item, ...summary, workspaceRole: role } : item
    )
    if (!workspaceList.value.some((item) => item.id === summary.id)) {
      workspaceList.value = [summary, ...workspaceList.value]
    }
    auth.setWorkspaceContext(summary.id, role)
  }

  function canManage(permission: WorkspacePermission): boolean {
    return canWorkspacePermission(workspaceRole.value, permission)
  }

  return {
    currentWorkspace, workspaceId, workspaceRole, workspaceList, loading, switching, initialized, version,
    load, switchTo, canManage
  }
})
