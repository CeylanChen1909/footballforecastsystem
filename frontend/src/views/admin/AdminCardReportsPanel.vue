<template>
  <el-card class="panel-card" shadow="never">
    <template #header>
      <div class="panel-header">
        <span>虚拟角色卡审核</span>
        <div class="panel-actions">
          <el-select :model-value="status" style="width: 150px" @update:model-value="$emit('update:status', $event)" @change="$emit('reload')">
            <el-option label="待处理" value="OPEN" />
            <el-option label="已复核" value="REVIEWED" />
            <el-option label="已通过" value="APPROVED" />
            <el-option label="已驳回" value="REJECTED" />
            <el-option label="已处置" value="ACTIONED" />
            <el-option label="全部" value="" />
          </el-select>
          <el-button :loading="loading" @click="$emit('reload')">刷新</el-button>
        </div>
      </div>
    </template>
    <PageState v-if="error" type="error" title="审核队列加载失败" :description="error" action-text="重试" @action="$emit('reload')" />
    <el-table v-else :data="reports" stripe empty-text="暂无举报记录">
      <el-table-column prop="id" label="编号" width="80" />
      <el-table-column prop="player_name" label="角色卡" min-width="170" />
      <el-table-column prop="reason" label="原因" min-width="150" />
      <el-table-column prop="detail" label="补充说明" min-width="220" show-overflow-tooltip />
      <el-table-column prop="reporter_user_id" label="举报用户" width="100" />
      <el-table-column prop="created_at" label="提交时间" width="180" />
      <el-table-column label="处理" width="230" fixed="right">
        <template #default="{ row }">
          <el-button size="small" type="success" plain @click="$emit('resolve', row, 'APPROVED')">通过公开</el-button>
          <el-button size="small" @click="$emit('resolve', row, 'REVIEWED')">标记复核</el-button>
          <el-button size="small" type="danger" plain @click="$emit('resolve', row, 'ACTIONED')">隐藏卡牌</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-divider content-position="left">待公开审核</el-divider>
    <el-alert v-if="pendingError" :title="pendingError" type="warning" :closable="false" show-icon />
    <el-table v-else :data="pendingCards" stripe empty-text="暂无待审核角色卡">
      <el-table-column prop="id" label="编号" width="80" />
      <el-table-column prop="player_name" label="角色卡" min-width="170" />
      <el-table-column prop="archetype" label="定位风格" width="120" />
      <el-table-column prop="owner_user_id" label="创建者" width="100" />
      <el-table-column prop="updated_at" label="提交时间" width="180" />
      <el-table-column label="处理" width="190" fixed="right">
        <template #default="{ row }">
          <el-button size="small" type="success" plain @click="$emit('moderate', row, 'APPROVED')">通过公开</el-button>
          <el-button size="small" type="danger" plain @click="$emit('moderate', row, 'HIDDEN')">驳回隐藏</el-button>
        </template>
      </el-table-column>
    </el-table>
  </el-card>
</template>

<script setup>
import PageState from '../../components/layout/PageState.vue'

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
