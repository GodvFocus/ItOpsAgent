import { authFetch, readApiError } from './http'
import type { WorkspaceMember, WorkspaceSummary, WorkspaceSwitchResponse, WorkspaceRole } from '@/types/workspace'

async function ensureOk(response: Response): Promise<void> {
  if (!response.ok) throw await readApiError(response)
}

export async function listWorkspaces(): Promise<WorkspaceSummary[]> {
  const response = await authFetch('/api/auth/workspaces')
  await ensureOk(response)
  return await response.json() as WorkspaceSummary[]
}

export async function getCurrentWorkspace(): Promise<WorkspaceSwitchResponse> {
  const response = await authFetch('/api/auth/workspaces/current')
  await ensureOk(response)
  return await response.json() as WorkspaceSwitchResponse
}

/** 切换请求只携带目标路径参数，userId、role 等身份信息由服务端 Session 和数据库推导。 */
export async function switchWorkspace(workspaceId: string): Promise<WorkspaceSwitchResponse> {
  const response = await authFetch(`/api/auth/workspaces/${encodeURIComponent(workspaceId)}/switch`, {
    method: 'POST'
  })
  await ensureOk(response)
  return await response.json() as WorkspaceSwitchResponse
}

export async function listMembers(workspaceId: string): Promise<WorkspaceMember[]> {
  const response = await authFetch(`/api/workspaces/${encodeURIComponent(workspaceId)}/members`)
  await ensureOk(response)
  return await response.json() as WorkspaceMember[]
}

export async function addMember(workspaceId: string, userId: number, role: WorkspaceRole): Promise<WorkspaceMember> {
  const response = await authFetch(`/api/workspaces/${encodeURIComponent(workspaceId)}/members`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ userId, role })
  })
  await ensureOk(response)
  return await response.json() as WorkspaceMember
}

export async function changeMemberRole(workspaceId: string, userId: number, role: WorkspaceRole): Promise<void> {
  const response = await authFetch(
    `/api/workspaces/${encodeURIComponent(workspaceId)}/members/${encodeURIComponent(String(userId))}/role`,
    { method: 'PATCH', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ role }) }
  )
  await ensureOk(response)
}

/** 后端现有 remove 接口执行 DISABLED，前端以禁用语义展示，避免误导为物理删除。 */
export async function disableMember(workspaceId: string, userId: number): Promise<void> {
  const response = await authFetch(
    `/api/workspaces/${encodeURIComponent(workspaceId)}/members/${encodeURIComponent(String(userId))}`,
    { method: 'DELETE' }
  )
  await ensureOk(response)
}
