<template>
  <section class="changelog-admin-panel">
    <el-card class="panel-card" shadow="never">
      <template #header>
        <div class="panel-header">
          <div>
            <strong>更新日志</strong>
            <small>管理比赛页顶部展示的版本变化、功能更新和维护说明</small>
          </div>
          <div class="panel-actions">
            <el-button :loading="loading" @click="load">刷新</el-button>
            <el-button type="primary" @click="openCreate">新建日志</el-button>
          </div>
        </div>
      </template>

      <div class="changelog-toolbar">
        <el-input v-model="keyword" clearable placeholder="搜索标题、摘要或标签" @keyup.enter="load" @clear="load" />
        <el-select v-model="status" clearable placeholder="全部状态" @change="load">
          <el-option v-for="item in statusOptions" :key="item.value" :label="item.label" :value="item.value" />
        </el-select>
        <el-button plain @click="resetFilters">重置</el-button>
      </div>

      <PageState v-if="error" type="error" title="更新日志加载失败" :description="error" action-text="重试" @action="load" />
      <el-table v-else v-loading="loading" :data="items" row-key="id" empty-text="暂无更新日志">
        <el-table-column label="日志" min-width="330">
          <template #default="{ row }">
            <div class="entry-title-cell">
              <strong>{{ row.title }}</strong>
              <span>{{ row.summary }}</span>
              <small v-if="row.versionLabel">{{ row.versionLabel }}</small>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="tag" label="标签" width="90" />
        <el-table-column label="状态" width="105">
          <template #default="{ row }"><el-tag size="small" :type="statusType(row.status)">{{ statusLabel(row.status) }}</el-tag></template>
        </el-table-column>
        <el-table-column label="发布时间" width="160">
          <template #default="{ row }">{{ row.publishAt || '未安排' }}</template>
        </el-table-column>
        <el-table-column label="最近修改" width="160">
          <template #default="{ row }">{{ row.updatedAt || '—' }}</template>
        </el-table-column>
        <el-table-column label="操作" width="250" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button link :type="row.status === 'PUBLISHED' ? 'warning' : 'success'" @click="toggleStatus(row)">{{ row.status === 'PUBLISHED' ? '下线' : '发布' }}</el-button>
            <el-button link type="danger" @click="remove(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="pagination-row">
        <span>共 {{ total }} 条</span>
        <el-pagination v-model:current-page="page" v-model:page-size="size" layout="prev, pager, next, sizes" :page-sizes="[10, 20, 50]" :total="total" @current-change="load" @size-change="page = 1; load()" />
      </div>
    </el-card>

    <el-dialog v-model="dialogVisible" :title="editingId ? '编辑更新日志' : '新建更新日志'" width="min(900px, 94vw)" destroy-on-close>
      <div class="editor-layout">
        <el-form ref="formRef" :model="form" :rules="rules" label-position="top" class="changelog-form">
          <div class="form-grid">
            <el-form-item label="标题" prop="title"><el-input v-model="form.title" maxlength="160" show-word-limit placeholder="例如：比赛焦点推荐上线" /></el-form-item>
            <el-form-item label="版本标识"><el-input v-model="form.versionLabel" maxlength="64" placeholder="例如 v1.8.0（可选）" /></el-form-item>
          </div>
          <el-form-item label="摘要" prop="summary"><el-input v-model="form.summary" type="textarea" :rows="2" maxlength="500" show-word-limit placeholder="一句话说明这次更新对用户有什么影响" /></el-form-item>
          <div class="form-grid form-grid-three">
            <el-form-item label="标签"><el-input v-model="form.tag" maxlength="32" placeholder="赛程 / 预测 / 账户" /></el-form-item>
            <el-form-item label="色彩语义"><el-select v-model="form.tone"><el-option v-for="item in toneOptions" :key="item.value" :label="item.label" :value="item.value" /></el-select></el-form-item>
            <el-form-item label="状态"><el-select v-model="form.status"><el-option v-for="item in statusOptions" :key="item.value" :label="item.label" :value="item.value" /></el-select></el-form-item>
          </div>
          <el-form-item label="发布时间"><el-date-picker v-model="form.publishAt" type="datetime" value-format="YYYY-MM-DD HH:mm" format="YYYY-MM-DD HH:mm" placeholder="立即发布可留空" /></el-form-item>
          <el-form-item label="详细更新（每行一条）"><el-input v-model="form.detailsText" type="textarea" :rows="7" maxlength="5000" show-word-limit placeholder="例如：\n焦点比赛根据联赛和球队热度计算\n支持中英文球队名切换" /></el-form-item>
        </el-form>

        <aside class="entry-preview" aria-label="更新日志预览">
          <span class="preview-kicker">PREVIEW</span>
          <div class="preview-meta"><time>{{ form.publishAt || '待发布' }}</time><span :class="`is-${form.tone}`">{{ form.tag || '标签' }}</span></div>
          <h3>{{ form.title || '更新日志标题' }}</h3>
          <p>{{ form.summary || '摘要会显示在这里。' }}</p>
          <ul v-if="previewDetails.length"><li v-for="item in previewDetails" :key="item">{{ item }}</li></ul>
        </aside>
      </div>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存日志</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { adminApi } from '../../api/admin'
import PageState from '../../components/layout/PageState.vue'

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

const statusOptions = [
  { value: 'DRAFT', label: '草稿' },
  { value: 'PUBLISHED', label: '已发布' },
  { value: 'HIDDEN', label: '已下线' },
  { value: 'ARCHIVED', label: '已归档' }
]
const toneOptions = [
  { value: 'account', label: '账户（紫色）' },
  { value: 'match', label: '赛程（绿色）' },
  { value: 'prediction', label: '预测（橙色）' },
  { value: 'system', label: '系统（灰色）' }
]
const rules = {
  title: [{ required: true, message: '请输入标题', trigger: 'blur' }],
  summary: [{ required: true, message: '请输入摘要', trigger: 'blur' }]
}
const previewDetails = computed(() => String(form.detailsText || '').split(/\r?\n/).map(item => item.trim()).filter(Boolean).slice(0, 8))

const statusLabel = value => statusOptions.find(item => item.value === value)?.label || value || '未知'
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
    error.value = err?.message || '更新日志加载失败'
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
    ElMessage.success(editingId.value ? '更新日志已保存' : '更新日志已创建')
    dialogVisible.value = false
    await load()
  } catch { /* interceptor 已提示错误 */ } finally { saving.value = false }
}
const toggleStatus = async row => {
  const next = row.status === 'PUBLISHED' ? 'HIDDEN' : 'PUBLISHED'
  try {
    await adminApi.setChangelogStatus(row.id, next)
    ElMessage.success(next === 'PUBLISHED' ? '日志已发布' : '日志已下线')
    await load()
  } catch { /* interceptor 已提示错误 */ }
}
const remove = async row => {
  try {
    await ElMessageBox.confirm(`确定删除“${row.title}”吗？删除后不可恢复。`, '删除更新日志', { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' })
    await adminApi.deleteChangelog(row.id)
    ElMessage.success('更新日志已删除')
    if (items.value.length === 1 && page.value > 1) page.value -= 1
    await load()
  } catch (err) {
    if (err !== 'cancel' && err !== 'close') ElMessage.error(err?.message || '删除失败')
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
