<template>
  <el-card class="panel-card" shadow="never">
    <template #header>
      <div class="panel-header">
        <span>{{ t('admin.workbench.title') }}</span>
        <div class="panel-actions">
          <el-date-picker
            :model-value="date"
            type="date"
            value-format="YYYY-MM-DD"
            format="YYYY-MM-DD"
            :placeholder="t('admin.workbench.placeholder.date')"
            clearable
            style="width: 150px"
            @update:model-value="$emit('update:date', $event)"
            @change="$emit('reload', 1)"
          />
          <el-input
            :model-value="keyword"
            :placeholder="t('admin.workbench.placeholder.search')"
            clearable
            style="width: 200px"
            @update:model-value="$emit('update:keyword', $event)"
            @change="$emit('reload', 1)"
          />
          <el-select
            :model-value="status"
            :placeholder="t('admin.workbench.placeholder.status')"
            clearable
            style="width: 140px"
            @update:model-value="$emit('update:status', $event)"
            @change="$emit('reload', 1)"
          >
            <el-option :label="t('admin.workbench.status.ns')" value="NS" />
            <el-option :label="t('admin.workbench.status.live')" value="LIVE" />
            <el-option :label="t('admin.workbench.status.ft')" value="FT" />
            <el-option :label="t('admin.workbench.status.cancel')" value="CANCEL" />
          </el-select>
          <el-button type="primary" @click="$emit('create')">{{ t('admin.workbench.create') }}</el-button>
        </div>
      </div>
    </template>

    <div class="workbench-summary">
      <div><span>{{ t('admin.workbench.summary.current') }}</span><strong>{{ matches.length }}</strong></div>
      <div><span>{{ t('admin.workbench.summary.live') }}</span><strong class="is-live">{{ liveCount }}</strong></div>
      <div><span>{{ t('admin.workbench.summary.upcoming') }}</span><strong class="is-upcoming">{{ upcomingCount }}</strong></div>
      <div class="summary-note">{{ t('admin.workbench.summary.note') }}</div>
    </div>

    <PageState v-if="error" type="error" :title="t('admin.workbench.loadFailed')" :description="error" :action-text="t('admin.common.retry')" @action="$emit('reload', page)" />
    <el-table v-else :data="matches" stripe>
      <el-table-column prop="fixtureId" label="Fixture ID" width="110" />
      <el-table-column prop="externalMatchId" label="External ID" width="150" show-overflow-tooltip />
      <el-table-column :label="t('admin.workbench.col.date')" width="130">
        <template #default="{ row }">{{ row.matchDate || '-' }}</template>
      </el-table-column>
      <el-table-column :label="t('admin.workbench.col.homeLogo')" width="80">
        <template #default="{ row }">
          <el-avatar :size="32" :src="getMediaAssetUrl(row.homeTeamLogo)">{{ row.homeTeamName?.slice(0, 1) || 'H' }}</el-avatar>
        </template>
      </el-table-column>
      <el-table-column prop="homeTeamName" :label="t('admin.workbench.col.homeTeam')" min-width="150" />
      <el-table-column :label="t('admin.workbench.col.awayLogo')" width="80">
        <template #default="{ row }">
          <el-avatar :size="32" :src="getMediaAssetUrl(row.awayTeamLogo)">{{ row.awayTeamName?.slice(0, 1) || 'A' }}</el-avatar>
        </template>
      </el-table-column>
      <el-table-column prop="awayTeamName" :label="t('admin.workbench.col.awayTeam')" min-width="150" />
      <el-table-column prop="homeScore" :label="t('admin.workbench.col.homeScore')" width="90" />
      <el-table-column prop="awayScore" :label="t('admin.workbench.col.awayScore')" width="90" />
      <el-table-column prop="status" :label="t('admin.workbench.col.status')" width="100">
        <template #default="{ row }">
          <el-tag :type="matchStatusTag(row.status)">{{ row.status || t('admin.common.unknown') }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column :label="t('admin.workbench.col.actions')" width="220" fixed="right">
        <template #default="{ row }">
          <el-button size="small" @click="$emit('edit', row)">{{ t('admin.workbench.edit') }}</el-button>
          <el-button size="small" type="danger" @click="$emit('delete', row)">{{ t('admin.workbench.delete') }}</el-button>
        </template>
      </el-table-column>
    </el-table>

    <div class="pager-bar">
      <span>{{ t('admin.common.totalRecords', { total }) }}</span>
      <el-pagination
        background
        layout="total, prev, pager, next, sizes, jumper"
        :total="total"
        :page-size="pageSize"
        :current-page="page"
        :page-sizes="[10, 20, 50, 100]"
        @current-change="$emit('reload', $event)"
        @size-change="$emit('size-change', $event)"
      />
    </div>
  </el-card>
</template>

<script setup>
import { computed } from 'vue'
import PageState from '../../components/layout/PageState.vue'
import { getMediaAssetUrl } from '../../utils/mediaAsset'
import { useI18n } from '../../i18n'

const { t } = useI18n()

const props = defineProps({
  matches: { type: Array, default: () => [] },
  total: { type: Number, default: 0 },
  page: { type: Number, default: 1 },
  pageSize: { type: Number, default: 20 },
  keyword: { type: String, default: '' },
  status: { type: String, default: '' },
  date: { type: String, default: '' },
  error: { type: String, default: '' },
})

defineEmits(['update:keyword', 'update:status', 'update:date', 'reload', 'size-change', 'create', 'edit', 'delete'])

const matchStatusTag = (status) => ({ LIVE: 'danger', FT: 'success', CANCEL: 'info', NS: 'warning' }[status] || 'info')
const liveCount = computed(() => props.matches.filter(item => ['LIVE', '1H', '2H', 'HT'].includes(item.status)).length)
const upcomingCount = computed(() => props.matches.filter(item => item.status === 'NS').length)
</script>

<style scoped>
.panel-card { border: none; border-radius: 8px; }
.panel-header { display: flex; justify-content: space-between; align-items: center; width: 100%; }
.panel-actions { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.workbench-summary { display:flex; align-items:center; gap:20px; padding:12px 14px; margin-bottom:12px; border:1px solid var(--ff-border); border-radius:var(--ff-radius-md); background:var(--ff-surface-quiet); }
.workbench-summary div:not(.summary-note) { display:flex; align-items:baseline; gap:7px; }
.workbench-summary span { color:var(--ff-text-muted); font-size:12px; }
.workbench-summary strong { color:var(--ff-text-strong); font:700 18px/1 var(--ff-mono); }
.workbench-summary .is-live { color:var(--ff-danger); }
.workbench-summary .is-upcoming { color:var(--ff-warning); }
.summary-note { margin-left:auto; color:var(--ff-text-faint); font-size:12px; }
.pager-bar { margin-top: 12px; display: flex; justify-content: space-between; align-items: center; padding: 10px 12px; background: var(--ff-bg-alt); border: 1px solid var(--ff-border); border-radius: 8px; gap: 10px; flex-wrap: wrap; }
@media (max-width: 760px) {
  .panel-header { align-items: flex-start; flex-direction: column; }
  .panel-actions { width: 100%; }
  .panel-actions :deep(.el-input), .panel-actions :deep(.el-select), .panel-actions :deep(.el-date-editor) { flex: 1; min-width: 140px; }
  .pager-bar { align-items: flex-start; flex-direction: column; }
  .workbench-summary { gap:12px; flex-wrap:wrap; }
  .summary-note { width:100%; margin-left:0; }
}
</style>
