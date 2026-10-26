<template>
  <section class="changelog-admin-panel">
    <el-card class="panel-card" shadow="never">
      <template #header>
        <div class="panel-header">
          <div>
            <strong>{{ t('admin.changelog.title') }}</strong>
            <small>{{ t('admin.changelog.subtitle') }}</small>
          </div>
          <div class="panel-actions">
            <el-button :loading="loading" @click="load">{{ t('admin.common.refresh') }}</el-button>
            <el-button type="primary" @click="openCreate">{{ t('admin.changelog.newEntry') }}</el-button>
          </div>
        </div>
      </template>

      <div class="changelog-toolbar">
        <el-input v-model="keyword" clearable :placeholder="t('admin.changelog.searchPlaceholder')" @keyup.enter="load" @clear="load" />
        <el-select v-model="status" clearable :placeholder="t('admin.changelog.allStatuses')" @change="load">
          <el-option v-for="item in statusOptions" :key="item.value" :label="item.label" :value="item.value" />
        </el-select>
        <el-button plain @click="resetFilters">{{ t('admin.changelog.reset') }}</el-button>
      </div>

      <PageState v-if="error" type="error" :title="t('admin.changelog.loadFailed')" :description="error" :action-text="t('admin.common.retry')" @action="load" />
      <el-table v-else v-loading="loading" :data="items" row-key="id" :empty-text="t('admin.changelog.empty')">
        <el-table-column :label="t('admin.changelog.col.entry')" min-width="330">
          <template #default="{ row }">
            <div class="entry-title-cell">
              <strong>{{ row.title }}</strong>
              <span>{{ row.summary }}</span>
              <small v-if="row.versionLabel">{{ row.versionLabel }}</small>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="tag" :label="t('admin.changelog.col.tag')" width="90" />
        <el-table-column :label="t('admin.common.status')" width="105">
          <template #default="{ row }"><el-tag size="small" :type="statusType(row.status)">{{ statusLabel(row.status) }}</el-tag></template>
        </el-table-column>
        <el-table-column :label="t('admin.changelog.col.publishAt')" width="160">
          <template #default="{ row }">{{ row.publishAt || t('admin.changelog.unscheduled') }}</template>
        </el-table-column>
        <el-table-column :label="t('admin.changelog.col.updatedAt')" width="160">
          <template #default="{ row }">{{ row.updatedAt || '—' }}</template>
        </el-table-column>
        <el-table-column :label="t('admin.changelog.col.actions')" width="250" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openEdit(row)">{{ t('admin.common.edit') }}</el-button>
            <el-button link :type="row.status === 'PUBLISHED' ? 'warning' : 'success'" @click="toggleStatus(row)">{{ row.status === 'PUBLISHED' ? t('admin.changelog.action.unpublish') : t('admin.changelog.action.publish') }}</el-button>
            <el-button link type="danger" @click="remove(row)">{{ t('admin.common.delete') }}</el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="pagination-row">
        <span>{{ t('admin.common.totalRecords', { total }) }}</span>
        <el-pagination v-model:current-page="page" v-model:page-size="size" layout="prev, pager, next, sizes" :page-sizes="[10, 20, 50]" :total="total" @current-change="load" @size-change="page = 1; load()" />
      </div>
    </el-card>

    <el-dialog v-model="dialogVisible" :title="editingId ? t('admin.changelog.dialog.edit') : t('admin.changelog.dialog.create')" width="min(900px, 94vw)" destroy-on-close>
      <div class="editor-layout">
        <el-form ref="formRef" :model="form" :rules="rules" label-position="top" class="changelog-form">
          <div class="form-grid">
            <el-form-item :label="t('admin.changelog.form.title')" prop="title"><el-input v-model="form.title" maxlength="160" show-word-limit :placeholder="t('admin.changelog.form.titlePlaceholder')" /></el-form-item>
            <el-form-item :label="t('admin.changelog.form.versionLabel')"><el-input v-model="form.versionLabel" maxlength="64" :placeholder="t('admin.changelog.form.versionPlaceholder')" /></el-form-item>
          </div>
          <el-form-item :label="t('admin.changelog.form.summary')" prop="summary"><el-input v-model="form.summary" type="textarea" :rows="2" maxlength="500" show-word-limit :placeholder="t('admin.changelog.form.summaryPlaceholder')" /></el-form-item>
          <div class="form-grid form-grid-three">
            <el-form-item :label="t('admin.changelog.form.tag')"><el-input v-model="form.tag" maxlength="32" :placeholder="t('admin.changelog.form.tagPlaceholder')" /></el-form-item>
            <el-form-item :label="t('admin.changelog.form.tone')"><el-select v-model="form.tone"><el-option v-for="item in toneOptions" :key="item.value" :label="item.label" :value="item.value" /></el-select></el-form-item>
            <el-form-item :label="t('admin.changelog.form.status')"><el-select v-model="form.status"><el-option v-for="item in statusOptions" :key="item.value" :label="item.label" :value="item.value" /></el-select></el-form-item>
          </div>
          <el-form-item :label="t('admin.changelog.form.publishAt')"><el-date-picker v-model="form.publishAt" type="datetime" value-format="YYYY-MM-DD HH:mm" format="YYYY-MM-DD HH:mm" :placeholder="t('admin.changelog.form.publishPlaceholder')" /></el-form-item>
          <el-form-item :label="t('admin.changelog.form.details')"><el-input v-model="form.detailsText" type="textarea" :rows="7" maxlength="5000" show-word-limit :placeholder="t('admin.changelog.form.detailsPlaceholder')" /></el-form-item>
        </el-form>

        <aside class="entry-preview" :aria-label="t('admin.changelog.previewAria')">
          <span class="preview-kicker">PREVIEW</span>
          <div class="preview-meta"><time>{{ form.publishAt || t('admin.changelog.previewPending') }}</time><span :class="`is-${form.tone}`">{{ form.tag || t('admin.changelog.previewTag') }}</span></div>
          <h3>{{ form.title || t('admin.changelog.previewTitle') }}</h3>
          <p>{{ form.summary || t('admin.changelog.previewSummary') }}</p>
          <ul v-if="previewDetails.length"><li v-for="item in previewDetails" :key="item">{{ item }}</li></ul>
        </aside>
      </div>
      <template #footer>
        <el-button @click="dialogVisible = false">{{ t('admin.common.cancel') }}</el-button>
        <el-button type="primary" :loading="saving" @click="save">{{ t('admin.changelog.saveEntry') }}</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { adminApi } from '../../api/admin'
import PageState from '../../components/layout/PageState.vue'
import { useI18n } from '../../i18n'

const { t } = useI18n()

const items = ref([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const keyword = ref('')
const status = ref('')
const loading = ref(false)
const saving = ref(false)
const error = ref('')
const dialogVisible = ref(false)
const editingId = ref(null)
const formRef = ref(null)
const form = reactive({ title: '', summary: '', detailsText: '', tag: '赛程', tone: 'match', versionLabel: '', status: 'DRAFT', publishAt: '' })

const statusOptions = computed(() => [
  { value: 'DRAFT', label: t('admin.changelog.status.draft') },
  { value: 'PUBLISHED', label: t('admin.changelog.status.published') },
  { value: 'HIDDEN', label: t('admin.changelog.status.hidden') },
  { value: 'ARCHIVED', label: t('admin.changelog.status.archived') }
])
const toneOptions = computed(() => [
  { value: 'account', label: t('admin.changelog.tone.account') },
  { value: 'match', label: t('admin.changelog.tone.match') },
  { value: 'prediction', label: t('admin.changelog.tone.prediction') },
  { value: 'system', label: t('admin.changelog.tone.system') }
])
const rules = computed(() => ({
  title: [{ required: true, message: t('admin.changelog.validation.title'), trigger: 'blur' }],
  summary: [{ required: true, message: t('admin.changelog.validation.summary'), trigger: 'blur' }]
}))
const previewDetails = computed(() => String(form.detailsText || '').split(/\r?\n/).map(item => item.trim()).filter(Boolean).slice(0, 8))

const statusLabel = value => statusOptions.value.find(item => item.value === value)?.label || value || t('admin.common.unknown')
const statusType = value => ({ PUBLISHED: 'success', DRAFT: 'info', HIDDEN: 'warning', ARCHIVED: '' }[value] || 'info')
const load = async () => {
  loading.value = true
  error.value = ''
  try {
    const result = await adminApi.listChangelog({ keyword: keyword.value.trim() || undefined, status: status.value || undefined, page: page.value, size: size.value })
    items.value = Array.isArray(result?.items) ? result.items : []
    total.value = Number(result?.total || 0)
  } catch (err) {
    items.value = []
    total.value = 0
    error.value = err?.message || t('admin.changelog.loadFailed')
  } finally { loading.value = false }
}
const resetFilters = () => { keyword.value = ''; status.value = ''; page.value = 1; load() }
const resetForm = () => Object.assign(form, { title: '', summary: '', detailsText: '', tag: '赛程', tone: 'match', versionLabel: '', status: 'DRAFT', publishAt: '' })
const openCreate = () => { editingId.value = null; resetForm(); dialogVisible.value = true }
const openEdit = row => {
  editingId.value = row.id
  Object.assign(form, { title: row.title || '', summary: row.summary || '', detailsText: row.detailsText || (row.details || []).join('\n'), tag: row.tag || '赛程', tone: row.tone || 'match', versionLabel: row.versionLabel || '', status: row.status || 'DRAFT', publishAt: row.publishAt || '' })
  dialogVisible.value = true
}
const save = async () => {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return
  saving.value = true
  try {
    await adminApi.saveChangelog({ ...form, id: editingId.value || undefined })
    ElMessage.success(editingId.value ? t('admin.changelog.saved') : t('admin.changelog.created'))
    dialogVisible.value = false
    await load()
  } catch { /* interceptor 已提示错误 */ } finally { saving.value = false }
}
const toggleStatus = async row => {
  const next = row.status === 'PUBLISHED' ? 'HIDDEN' : 'PUBLISHED'
  try {
    await adminApi.setChangelogStatus(row.id, next)
    ElMessage.success(next === 'PUBLISHED' ? t('admin.changelog.published') : t('admin.changelog.unpublished'))
    await load()
  } catch { /* interceptor 已提示错误 */ }
}
const remove = async row => {
  try {
    await ElMessageBox.confirm(t('admin.changelog.deleteConfirm', { title: row.title }), t('admin.changelog.deleteTitle'), { type: 'warning', confirmButtonText: t('admin.common.delete'), cancelButtonText: t('admin.common.cancel') })
    await adminApi.deleteChangelog(row.id)
    ElMessage.success(t('admin.changelog.deleted'))
    if (items.value.length === 1 && page.value > 1) page.value -= 1
    await load()
  } catch (err) {
    if (err !== 'cancel' && err !== 'close') ElMessage.error(err?.message || t('admin.changelog.deleteFailed'))
  }
}

onMounted(load)
</script>

<style scoped>
.panel-header,.panel-actions,.changelog-toolbar,.pagination-row,.preview-meta { display:flex; align-items:center; gap:10px; }
.panel-header { justify-content:space-between; width:100%; }
.panel-header>div:first-child { display:flex; flex-direction:column; gap:4px; }
.panel-header small { color:var(--ff-text-muted); font-size:12px; font-weight:400; }
.changelog-toolbar { margin-bottom:16px; }
.changelog-toolbar .el-input { max-width:340px; }
.changelog-toolbar .el-select { width:150px; }
.entry-title-cell { display:flex; flex-direction:column; gap:5px; min-width:0; }
.entry-title-cell strong { color:var(--ff-text-strong); font-size:13px; }
.entry-title-cell span { overflow:hidden; color:var(--ff-text-muted); font-size:12px; text-overflow:ellipsis; white-space:nowrap; }
.entry-title-cell small { color:var(--ff-text-faint); font:11px var(--ff-mono); }
.pagination-row { justify-content:space-between; margin-top:14px; color:var(--ff-text-muted); font-size:12px; }
.editor-layout { display:grid; grid-template-columns:minmax(0,1.35fr) minmax(250px,.65fr); gap:18px; }
.form-grid { display:grid; grid-template-columns:minmax(0,1fr) minmax(160px,.5fr); gap:12px; }
.form-grid-three { grid-template-columns:1.1fr 1fr 1fr; }
.changelog-form :deep(.el-date-editor),.changelog-form :deep(.el-select) { width:100%; }
.entry-preview { align-self:start; min-height:250px; padding:18px; border:1px solid var(--ff-border); border-radius:10px; background:var(--ff-surface-quiet); }
.preview-kicker { color:var(--ff-primary); font:700 10px var(--ff-mono); letter-spacing:.14em; }
.preview-meta { justify-content:space-between; margin-top:22px; color:var(--ff-text-faint); font:11px var(--ff-mono); }
.preview-meta span { padding:3px 7px; border-radius:99px; background:var(--ff-primary-soft); color:var(--ff-primary); font:11px var(--ff-sans); }
.preview-meta .is-account { background:rgba(124,92,255,.12); color:#6a4de0; }.preview-meta .is-prediction { background:rgba(196,123,24,.14); color:#9a5c0c; }.preview-meta .is-system { background:var(--ff-bg-alt); color:var(--ff-text-muted); }
.entry-preview h3 { margin:16px 0 7px; color:var(--ff-text-strong); font-size:17px; line-height:1.4; }
.entry-preview p { margin:0; color:var(--ff-text-muted); font-size:12px; line-height:1.65; }
.entry-preview ul { display:flex; flex-direction:column; gap:6px; margin:14px 0 0; padding:0; list-style:none; }.entry-preview li { position:relative; padding-left:13px; color:var(--ff-text-faint); font-size:11px; line-height:1.5; }.entry-preview li::before { content:''; position:absolute; left:0; top:.58em; width:4px; height:4px; border-radius:50%; background:var(--ff-border-strong); }
@media (max-width:760px) { .panel-header,.changelog-toolbar,.pagination-row { align-items:stretch; flex-direction:column; }.panel-actions,.changelog-toolbar { width:100%; flex-wrap:wrap; }.changelog-toolbar .el-input,.changelog-toolbar .el-select { max-width:none; width:100%; }.editor-layout { grid-template-columns:1fr; }.form-grid,.form-grid-three { grid-template-columns:1fr; }.pagination-row :deep(.el-pagination) { justify-content:center; } }
</style>
