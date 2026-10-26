<template>
  <el-card class="panel-card" shadow="never">
    <template #header>
      <div class="panel-header">
        <span>{{ t('admin.users.title') }}</span>
        <div class="panel-actions">
          <el-select :model-value="roleFilter" :placeholder="t('admin.users.placeholder.role')" style="width: 130px" clearable @update:model-value="updateRole" @change="$emit('filter-change')">
            <el-option label="USER" value="USER" />
            <el-option label="ADMIN" value="ADMIN" />
          </el-select>
          <el-select :model-value="statusFilter" :placeholder="t('admin.users.placeholder.status')" style="width: 130px" clearable @update:model-value="updateStatus" @change="$emit('filter-change')">
            <el-option label="ACTIVE" value="ACTIVE" />
            <el-option label="DISABLED" value="DISABLED" />
          </el-select>
        </div>
      </div>
    </template>
    <PageState v-if="error" type="error" :title="t('admin.users.loadFailed')" :description="error" :action-text="t('admin.common.retry')" @action="$emit('retry')" />
    <el-table v-else :data="users" stripe>
      <el-table-column prop="id" label="ID" width="80" />
      <el-table-column prop="username" :label="t('admin.users.col.username')" min-width="180" />
      <el-table-column prop="role" :label="t('admin.users.col.role')" width="120">
        <template #default="{ row }"><el-tag :type="row.role === 'ADMIN' ? 'danger' : 'info'">{{ row.role }}</el-tag></template>
      </el-table-column>
      <el-table-column prop="status" :label="t('admin.users.col.status')" width="120">
        <template #default="{ row }"><el-tag :type="row.status === 'ACTIVE' ? 'success' : 'warning'">{{ row.status }}</el-tag></template>
      </el-table-column>
      <el-table-column :label="t('admin.users.col.actions')" width="260" fixed="right">
        <template #default="{ row }">
          <el-dropdown @command="command => $emit('user-action', command, row)">
            <el-button size="small">{{ t('admin.users.more') }} <el-icon class="el-icon--right"><ArrowDown /></el-icon></el-button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="toggleRole">{{ t('admin.users.toggleRole') }}</el-dropdown-item>
                <el-dropdown-item command="toggleStatus">{{ t('admin.users.toggleStatus') }}</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </template>
      </el-table-column>
    </el-table>
  </el-card>
</template>

<script setup>
import { ArrowDown } from '@element-plus/icons-vue'
import PageState from '../../components/layout/PageState.vue'
import { useI18n } from '../../i18n'

const { t } = useI18n()

defineProps({
  users: { type: Array, default: () => [] },
  roleFilter: { type: String, default: '' },
  statusFilter: { type: String, default: '' },
  error: { type: String, default: '' }
})

const emit = defineEmits(['update:roleFilter', 'update:statusFilter', 'filter-change', 'retry', 'user-action'])
const updateRole = value => emit('update:roleFilter', value || '')
const updateStatus = value => emit('update:statusFilter', value || '')
</script>

<style scoped>
.panel-header { display: flex; justify-content: space-between; align-items: center; gap: 12px; }
.panel-actions { display: flex; gap: 10px; flex-wrap: wrap; }
@media (max-width: 720px) { .panel-header { align-items: flex-start; flex-direction: column; } .panel-actions { width: 100%; } .panel-actions :deep(.el-select) { flex: 1; min-width: 0; } }
</style>
