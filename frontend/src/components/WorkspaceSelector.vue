<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { Setting } from '@element-plus/icons-vue'
import { storeToRefs } from 'pinia'
import { useRouter } from 'vue-router'
import { useWorkspaceStore } from '@/store/workspace'
import { ApiError } from '@/api/http'

const router = useRouter()
const workspace = useWorkspaceStore()
const { currentWorkspace, workspaceList, workspaceId, workspaceRole, loading, switching } = storeToRefs(workspace)
const selectedId = ref('')
const displayName = computed(() => currentWorkspace.value?.name || '选择 Workspace')

watch(workspaceId, (id) => { selectedId.value = id ?? '' }, { immediate: true })

onMounted(async () => {
  if (workspace.initialized) return
  try {
    await workspace.load()
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : 'Workspace 信息加载失败')
  }
})

async function handleChange(nextId: string) {
  const previousId = workspaceId.value ?? ''
  try {
    await workspace.switchTo(nextId)
    ElMessage.success(`已切换到 ${currentWorkspace.value?.name ?? nextId}`)
  } catch (error) {
    selectedId.value = previousId
    const message = error instanceof ApiError && error.status === 403
      ? `切换失败：${error.message}`
      : error instanceof Error ? error.message : 'Workspace 切换失败，已保留原 Workspace'
    ElMessage.error(message)
  }
}

function openSettings() { void router.push('/settings/workspace') }
</script>

<template>
  <div class="workspace-selector" :aria-busy="loading || switching">
    <el-select
      v-model="selectedId"
      class="workspace-select"
      size="small"
      :loading="loading || switching"
      :disabled="loading || switching || workspaceList.length < 2"
      :placeholder="displayName"
      :title="displayName"
      @change="handleChange"
    >
      <el-option v-for="item in workspaceList" :key="item.id" :label="item.name" :value="item.id">
        <span>{{ item.name }}</span>
        <small>{{ item.workspaceRole }}</small>
      </el-option>
    </el-select>
    <span v-if="workspaceRole" class="workspace-role">{{ workspaceRole }}</span>
    <button class="settings-btn" type="button" title="Workspace 设置" @click="openSettings">
      <el-icon><Setting /></el-icon>
    </button>
  </div>
</template>

<style scoped>
.workspace-selector { display: inline-flex; align-items: center; gap: 6px; min-width: 160px; }
.workspace-select { width: 150px; }
.workspace-role { color: var(--text-muted); font-size: 11px; white-space: nowrap; }
.settings-btn { width: 28px; height: 28px; border: 1px solid transparent; border-radius: var(--radius-sm); color: var(--text-secondary); background: transparent; cursor: pointer; }
.settings-btn:hover { color: var(--text-primary); background: var(--bg-hover); border-color: var(--border-bright); }
small { float: right; color: var(--text-muted); }
</style>
