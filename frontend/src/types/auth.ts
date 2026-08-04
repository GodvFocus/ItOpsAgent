export interface LoginResponse {
  token: string | null
  userId: number
  workspaceId: string
  nickname: string
  role: string
  workspaceRole?: WorkspaceRole | null
}

import type { WorkspaceRole } from './workspace'
