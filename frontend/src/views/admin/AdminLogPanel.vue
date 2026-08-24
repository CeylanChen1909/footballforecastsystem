<template>
  <el-card class="panel-card" shadow="never">
    <template #header>
      <div class="panel-header">
        <span>审计日志</span>
        <el-select :model-value="moduleFilter" placeholder="模块" style="width: 130px" clearable @update:model-value="updateModule" @change="$emit('filter-change')">
          <el-option label="NEWS" value="NEWS" />
          <el-option label="CONFIG" value="CONFIG" />
        </el-select>
      </div>
    </template>
    <PageState v-if="error" type="error" title="审计日志加载失败" :description="error" action-text="重试" @action="$emit('retry')" />
    <el-table v-else :data="logs" stripe height="520" @row-click="row => $emit('open-detail', row)">
      <el-table-column prop="createdAt" label="时间" width="180" />
      <el-table-column prop="operatorName" label="操作人" width="120" />
      <el-table-column prop="module" label="模块" width="100" />
      <el-table-column prop="action" label="动作" width="120" />
      <el-table-column prop="targetType" label="对象类型" width="120" />
      <el-table-column prop="content" label="内容" min-width="260" />
      <el-table-column prop="result" label="结果" width="100" />
      <el-table-column label="操作" width="90" fixed="right">
        <template #default="{ row }"><el-button text type="primary" @click.stop="$emit('open-detail', row)">查看</el-button></template>
      </el-table-column>
    </el-table>
  </el-card>
</template>

<script setup>
import PageState from '../../components/layout/PageState.vue'

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
