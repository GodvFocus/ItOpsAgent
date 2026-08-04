export type WorkspaceRole = 'OWNER' | 'ADMIN' | 'EDITOR' | 'VIEWER'
export type MemberStatus = 'ACTIVE' | 'DISABLED'

export interface Workspace {
  id: string
  name: string
  ownerId: number
  status: string
  createdAt?: string | null
  updatedAt?: string | null
}

export interface WorkspaceSummary {
  id: string
  name: string
  status: string
  ownerId: number
  workspaceRole: WorkspaceRole | null
}

export interface WorkspaceMember {
  userId: number
  workspaceId: string
  role: WorkspaceRole
  status: MemberStatus
  joinedAt?: string | null
  username?: string | null
  nickname?: string | null
}

export interface WorkspaceSwitchResponse {
  currentWorkspace: WorkspaceSummary
  workspaceRole: WorkspaceRole
}
