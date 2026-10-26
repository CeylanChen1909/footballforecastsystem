<template>
  <el-card class="panel-card" shadow="never">
    <template #header>
      <div class="panel-header">
        <span>{{ t('admin.cardReports.title') }}</span>
        <div class="panel-actions">
          <el-select :model-value="status" style="width: 150px" @update:model-value="$emit('update:status', $event)" @change="$emit('reload')">
            <el-option :label="t('admin.cardReports.status.pending')" value="OPEN" />
            <el-option :label="t('admin.cardReports.status.reviewed')" value="REVIEWED" />
            <el-option :label="t('admin.cardReports.status.approved')" value="APPROVED" />
            <el-option :label="t('admin.cardReports.status.rejected')" value="REJECTED" />
            <el-option :label="t('admin.cardReports.status.actioned')" value="ACTIONED" />
            <el-option :label="t('admin.cardReports.status.all')" value="" />
          </el-select>
          <el-button :loading="loading" @click="$emit('reload')">{{ t('admin.common.refresh') }}</el-button>
        </div>
      </div>
    </template>
    <PageState v-if="error" type="error" :title="t('admin.cardReports.loadFailed')" :description="error" :action-text="t('admin.common.retry')" @action="$emit('reload')" />
    <el-table v-else :data="reports" stripe :empty-text="t('admin.cardReports.emptyReports')">
      <el-table-column prop="id" :label="t('admin.cardReports.col.id')" width="80" />
      <el-table-column prop="player_name" :label="t('admin.cardReports.col.card')" min-width="170" />
      <el-table-column prop="reason" :label="t('admin.cardReports.col.reason')" min-width="150" />
      <el-table-column prop="detail" :label="t('admin.cardReports.col.detail')" min-width="220" show-overflow-tooltip />
      <el-table-column prop="reporter_user_id" :label="t('admin.cardReports.col.reporter')" width="100" />
      <el-table-column prop="created_at" :label="t('admin.cardReports.col.submittedAt')" width="180" />
      <el-table-column :label="t('admin.cardReports.col.action')" width="230" fixed="right">
        <template #default="{ row }">
          <el-button size="small" type="success" plain @click="$emit('resolve', row, 'APPROVED')">{{ t('admin.cardReports.action.approve') }}</el-button>
          <el-button size="small" @click="$emit('resolve', row, 'REVIEWED')">{{ t('admin.cardReports.action.review') }}</el-button>
          <el-button size="small" type="danger" plain @click="$emit('resolve', row, 'ACTIONED')">{{ t('admin.cardReports.action.hide') }}</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-divider content-position="left">{{ t('admin.cardReports.pendingDivider') }}</el-divider>
    <el-alert v-if="pendingError" :title="pendingError" type="warning" :closable="false" show-icon />
    <el-table v-else :data="pendingCards" stripe :empty-text="t('admin.cardReports.emptyPending')">
      <el-table-column prop="id" :label="t('admin.cardReports.col.id')" width="80" />
      <el-table-column prop="player_name" :label="t('admin.cardReports.col.card')" min-width="170" />
      <el-table-column prop="archetype" :label="t('admin.cardReports.col.archetype')" width="120" />
      <el-table-column prop="owner_user_id" :label="t('admin.cardReports.col.owner')" width="100" />
      <el-table-column prop="updated_at" :label="t('admin.cardReports.col.submittedAt')" width="180" />
      <el-table-column :label="t('admin.cardReports.col.action')" width="190" fixed="right">
        <template #default="{ row }">
          <el-button size="small" type="success" plain @click="$emit('moderate', row, 'APPROVED')">{{ t('admin.cardReports.action.approve') }}</el-button>
          <el-button size="small" type="danger" plain @click="$emit('moderate', row, 'HIDDEN')">{{ t('admin.cardReports.action.rejectHide') }}</el-button>
        </template>
      </el-table-column>
    </el-table>
  </el-card>
</template>

<script setup>
import PageState from '../../components/layout/PageState.vue'
import { useI18n } from '../../i18n'

const { t } = useI18n()

defineProps({
  reports: { type: Array, default: () => [] },
  status: { type: String, default: 'OPEN' },
  loading: { type: Boolean, default: false },
  error: { type: String, default: '' }
  ,pendingCards: { type: Array, default: () => [] }
  ,pendingError: { type: String, default: '' }
})

defineEmits(['update:status', 'reload', 'resolve', 'moderate'])
</script>

<style scoped>
.panel-header { display: flex; justify-content: space-between; align-items: center; gap: 12px; }
.panel-actions { display: flex; gap: 10px; align-items: center; }
@media (max-width: 720px) { .panel-header { align-items: flex-start; flex-direction: column; } .panel-actions { width: 100%; } .panel-actions :deep(.el-select) { flex: 1; min-width: 0; } }
</style>
