<template>
  <el-card class="panel-card" shadow="never">
    <template #header>
      <div class="panel-header">
        <span>{{ t('admin.log.title') }}</span>
        <el-select :model-value="moduleFilter" :placeholder="t('admin.log.placeholder.module')" style="width: 130px" clearable @update:model-value="updateModule" @change="$emit('filter-change')">
          <el-option label="NEWS" value="NEWS" />
          <el-option label="CONFIG" value="CONFIG" />
        </el-select>
      </div>
    </template>
    <PageState v-if="error" type="error" :title="t('admin.log.loadFailed')" :description="error" :action-text="t('admin.common.retry')" @action="$emit('retry')" />
    <el-table v-else :data="logs" stripe height="520" @row-click="row => $emit('open-detail', row)">
      <el-table-column prop="createdAt" :label="t('admin.log.col.time')" width="180" />
      <el-table-column prop="operatorName" :label="t('admin.log.col.operator')" width="120" />
      <el-table-column prop="module" :label="t('admin.log.col.module')" width="100" />
      <el-table-column prop="action" :label="t('admin.log.col.action')" width="120" />
      <el-table-column prop="targetType" :label="t('admin.log.col.targetType')" width="120" />
      <el-table-column prop="content" :label="t('admin.log.col.content')" min-width="260" />
      <el-table-column prop="result" :label="t('admin.log.col.result')" width="100" />
      <el-table-column :label="t('admin.log.col.actions')" width="90" fixed="right">
        <template #default="{ row }"><el-button text type="primary" @click.stop="$emit('open-detail', row)">{{ t('admin.log.view') }}</el-button></template>
      </el-table-column>
    </el-table>
  </el-card>
</template>

<script setup>
import PageState from '../../components/layout/PageState.vue'
import { useI18n } from '../../i18n'

const { t } = useI18n()

defineProps({
  logs: { type: Array, default: () => [] },
  moduleFilter: { type: String, default: '' },
  error: { type: String, default: '' }
})
const emit = defineEmits(['update:moduleFilter', 'filter-change', 'retry', 'open-detail'])
const updateModule = value => emit('update:moduleFilter', value || '')
</script>

<style scoped>
.panel-header { display: flex; justify-content: space-between; align-items: center; gap: 12px; }
@media (max-width: 560px) { .panel-header { align-items: flex-start; flex-direction: column; } }
</style>
