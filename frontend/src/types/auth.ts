export interface LoginResponse {
  token: string | null
  userId: number
  workspaceId: string
  nickname: string
  role: string
}
