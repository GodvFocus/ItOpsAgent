<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { storeToRefs } from 'pinia'
import { ElMessage, ElMessageBox } from 'element-plus'
import AppHeader from '@/components/AppHeader.vue'
import DrawerSidebar from '@/components/DrawerSidebar.vue'
import { useWorkspaceStore } from '@/store/workspace'
import * as workspaceApi from '@/api/workspace'
import { ApiError } from '@/api/http'
import type { WorkspaceMember, WorkspaceRole } from '@/types/workspace'

const router = useRouter()
const workspace = useWorkspaceStore()
const { currentWorkspace, workspaceRole, workspaceId } = storeToRefs(workspace)
const members = ref<WorkspaceMember[]>([])
const loading = ref(false)
const saving = ref(false)
const newUserId = ref<number | undefined>()
const newRole = ref<WorkspaceRole>('VIEWER')
const roleOptions: WorkspaceRole[] = ['ADMIN', 'EDITOR', 'VIEWER']

const canInvite = computed(() => workspace.canManage('MEMBER_INVITE'))
const canChangeRole = computed(() => workspace.canManage('MEMBER_ROLE_UPDATE'))
const canDisable = computed(() => workspace.canManage('MEMBER_REMOVE'))

async function loadMembers() {
  if (!workspaceId.value) return
  loading.value = true
  try {
    members.value = await workspaceApi.listMembers(workspaceId.value)
  } catch (error) {
    showError(error, '成员列表加载失败')
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  if (!workspace.initialized) {
    try { await workspace.load() } catch (error) { showError(error, 'Workspace 信息加载失败') }
  }
  await loadMembers()
})

watch(workspaceId, () => { members.value = []; void loadMembers() })

async function addMember() {
  if (!workspaceId.value || !newUserId.value || !canInvite.value) return
  saving.value = true
  try {
    await workspaceApi.addMember(workspaceId.value, newUserId.value, newRole.value)
    ElMessage.success('成员已添加')
    newUserId.value = undefined
    await loadMembers()
  } catch (error) {
    showError(error, '添加成员失败')
  } finally {
    saving.value = false
  }
}

async function changeRole(member: WorkspaceMember, role: WorkspaceRole) {
  if (!workspaceId.value || !canChangeRole.value || role === member.role) return
  try {
    await workspaceApi.changeMemberRole(workspaceId.value, member.userId, role)
    member.role = role
    ElMessage.success('成员角色已更新')
  } catch (error) {
    showError(error, '角色更新失败')
    await loadMembers()
  }
}

async function disableMember(member: WorkspaceMember) {
  if (!workspaceId.value || !canDisable.value) return
  try {
    await ElMessageBox.confirm('禁用后该成员将立即失去 Workspace 访问权限，是否继续？', '禁用成员', {
      type: 'warning', confirmButtonText: '禁用', cancelButtonText: '取消'
    })
    await workspaceApi.disableMember(workspaceId.value, member.userId)
    member.status = 'DISABLED'
    ElMessage.success('成员已禁用')
  } catch (error) {
    if (error === 'cancel' || error === 'close') return
    showError(error, '禁用成员失败')
  }
}

function memberName(member: WorkspaceMember) {
  return member.nickname?.trim() || member.username?.trim() || `用户 ${member.userId}`
}

function showError(error: unknown, fallback: string) {
  const message = error instanceof ApiError && error.status === 403
    ? `权限不足：${error.message}`
    : error instanceof Error ? error.message : fallback
  ElMessage.error(message)
}

function goChat() { void router.push('/chat') }
</script>

<template>
  <div class="workspace-settings-view">
    <AppHeader :show-back="true" @back="goChat" />
    <DrawerSidebar />
    <main class="settings-page">
      <section class="settings-card">
        <header class="settings-head">
          <div>
            <p class="eyebrow">Workspace 设置</p>
            <h1>{{ currentWorkspace?.name || '当前 Workspace' }}</h1>
            <span class="role-badge">当前角色：{{ workspaceRole || '-' }}</span>
          </div>
          <el-button :loading="loading" @click="loadMembers">刷新成员</el-button>
        </header>

        <section v-if="canInvite" class="invite-box">
          <el-input-number v-model="newUserId" :min="1" placeholder="用户 ID" controls-position="right" />
          <el-select v-model="newRole" style="width: 130px">
            <el-option v-for="role in roleOptions" :key="role" :label="role" :value="role" />
          </el-select>
          <el-button type="primary" :loading="saving" :disabled="!newUserId" @click="addMember">添加成员</el-button>
        </section>

        <el-table v-loading="loading" :data="members" stripe empty-text="暂无成员">
          <el-table-column label="成员" min-width="220">
            <template #default="{ row }">
              <strong>{{ memberName(row) }}</strong>
              <small class="member-id">#{{ row.userId }}</small>
            </template>
          </el-table-column>
          <el-table-column label="角色" width="170">
            <template #default="{ row }">
              <el-select
                :model-value="row.role"
                :disabled="!canChangeRole || row.role === 'OWNER' || row.status === 'DISABLED'"
                size="small"
                @change="(role: WorkspaceRole) => changeRole(row, role)"
              >
                <el-option v-for="role in roleOptions" :key="role" :label="role" :value="role" />
              </el-select>
              <el-tag v-if="row.role === 'OWNER'" size="small" type="warning">OWNER</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="status" label="状态" width="110" />
          <el-table-column prop="joinedAt" label="加入时间" min-width="170" />
          <el-table-column v-if="canDisable" label="操作" width="110">
            <template #default="{ row }">
              <el-button
                v-if="row.status === 'ACTIVE' && row.role !== 'OWNER'"
                type="danger"
                link
                @click="disableMember(row)"
              >禁用</el-button>
            </template>
          </el-table-column>
        </el-table>
        <p class="hint">成员禁用、OWNER 生命周期和跨 Workspace 访问均由后端最终判断；按钮隐藏仅用于减少无效操作。</p>
      </section>
    </main>
  </div>
</template>

<style scoped>
.settings-page { min-height: 100vh; padding: 80px 24px 32px; position: relative; z-index: 1; }
.settings-card { max-width: 960px; margin: 0 auto; padding: 28px; border: 1px solid var(--border); border-radius: var(--radius); background: var(--bg-elevated); box-shadow: var(--shadow-md); }
.settings-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 18px; margin-bottom: 24px; }
.eyebrow { margin: 0 0 5px; color: var(--text-secondary); font-size: 12px; }
h1 { margin: 0 0 10px; color: var(--text-primary); font-size: 24px; }
.role-badge { color: var(--accent); font-size: 13px; }
.invite-box { display: flex; gap: 10px; align-items: center; padding: 14px; margin-bottom: 18px; border: 1px solid var(--border); border-radius: var(--radius-md); background: var(--bg-hover); }
.member-id { margin-left: 8px; color: var(--text-muted); }
.hint { margin: 16px 0 0; color: var(--text-muted); font-size: 12px; line-height: 1.6; }
@media (max-width: 640px) { .settings-page { padding: 68px 12px 20px; } .settings-card { padding: 16px; } .invite-box { flex-wrap: wrap; } }
</style>
