import type { WorkspaceRole } from '@/types/workspace'

export type WorkspacePermission =
  | 'MEMBER_READ'
  | 'MEMBER_INVITE'
  | 'MEMBER_ROLE_UPDATE'
  | 'MEMBER_REMOVE'
  | 'DOCUMENT_UPLOAD'
  | 'DOCUMENT_DELETE'
  | 'OWNERSHIP_TRANSFER'

const rolePermissions: Record<WorkspaceRole, readonly WorkspacePermission[]> = {
  OWNER: ['MEMBER_READ', 'MEMBER_INVITE', 'MEMBER_ROLE_UPDATE', 'MEMBER_REMOVE', 'DOCUMENT_UPLOAD', 'DOCUMENT_DELETE', 'OWNERSHIP_TRANSFER'],
  ADMIN: ['MEMBER_READ', 'MEMBER_INVITE', 'MEMBER_ROLE_UPDATE', 'MEMBER_REMOVE', 'DOCUMENT_UPLOAD', 'DOCUMENT_DELETE'],
  EDITOR: ['DOCUMENT_UPLOAD', 'DOCUMENT_DELETE'],
  VIEWER: ['MEMBER_READ']
}

/** 前端只复用后端固定矩阵控制体验，真正的写权限仍由后端 PermissionEvaluator 决定。 */
export function canWorkspacePermission(role: WorkspaceRole | null | undefined, permission: WorkspacePermission): boolean {
  return role ? rolePermissions[role].includes(permission) : false
}
