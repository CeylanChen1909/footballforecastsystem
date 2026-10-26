<template>
  <el-card class="panel-card" shadow="never">
    <template #header>
      <div class="panel-header">
        <div><strong>{{ t('admin.persona.title') }}</strong><small>{{ t('admin.persona.subtitle') }}</small></div>
        <div class="panel-actions"><el-select v-model="statusFilter" size="small" style="width: 120px" @change="reload"><el-option :label="t('admin.persona.status.all')" value="" /><el-option :label="t('admin.persona.status.draft')" value="DRAFT" /><el-option :label="t('admin.persona.status.published')" value="PUBLISHED" /><el-option :label="t('admin.persona.status.offline')" value="OFFLINE" /></el-select><el-button type="primary" size="small" @click="openCreate">{{ t('admin.persona.newRole') }}</el-button><el-button size="small" plain @click="openAudit">{{ t('admin.persona.auditLog') }}</el-button><el-button size="small" :loading="loading" @click="reload">{{ t('admin.persona.refresh') }}</el-button></div>
      </div>
    </template>
    <PageState v-if="error" type="error" :title="t('admin.persona.loadFailed')" :description="error" :action-text="t('admin.common.retry')" @action="reload" />
    <el-table v-else :data="items" stripe :empty-text="t('admin.persona.empty')">
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column :label="t('admin.persona.col.role')" min-width="190"><template #default="{ row }"><div class="catalog-name"><img v-if="row.photo_url" :src="row.photo_url" :alt="row.name" @error="row.photo_url = ''"><span v-else>{{ String(row.name || '?').slice(0, 1) }}</span><div><strong>{{ row.name }}</strong><small>{{ t('admin.persona.col.defaultPosition', { position: positionLabel(row.position || positions[0]) }) }}</small></div></div></template></el-table-column>
      <el-table-column prop="overall" :label="t('admin.persona.col.overall')" width="80" />
      <el-table-column :label="t('admin.persona.col.tags')" min-width="190"><template #default="{ row }"><div class="tag-preview"><el-tag v-for="tag in parseTags(row.tags_json).slice(0, 3)" :key="tag" size="small" effect="plain">{{ tag }}</el-tag><span v-if="parseTags(row.tags_json).length > 3">+{{ parseTags(row.tags_json).length - 3 }}</span><span v-if="!parseTags(row.tags_json).length" class="empty-tag">{{ t('admin.persona.notSet') }}</span></div></template></el-table-column>
      <el-table-column prop="rarity" :label="t('admin.persona.col.rarity')" width="80"><template #default="{ row }"><el-tag :type="rarityType(row.rarity)" effect="dark">{{ row.rarity || 'N' }}</el-tag></template></el-table-column>
      <el-table-column prop="price_points" :label="t('admin.persona.col.price')" width="100" />
      <el-table-column prop="status" :label="t('admin.persona.col.status')" width="100"><template #default="{ row }"><el-tag :type="row.status === 'PUBLISHED' ? 'success' : row.status === 'OFFLINE' ? 'info' : 'warning'">{{ statusLabel(row.status) }}</el-tag></template></el-table-column>
      <el-table-column prop="updated_at" :label="t('admin.persona.col.updatedAt')" width="180" />
      <el-table-column :label="t('admin.persona.col.actions')" width="250" fixed="right"><template #default="{ row }"><el-button size="small" @click="openEdit(row)">{{ t('admin.persona.action.edit') }}</el-button><el-button size="small" plain @click="viewVersions(row)">{{ t('admin.persona.action.versions') }}</el-button><el-button size="small" :type="row.status === 'PUBLISHED' ? 'warning' : 'success'" plain @click="toggleStatus(row)">{{ row.status === 'PUBLISHED' ? t('admin.persona.action.offline') : t('admin.persona.action.online') }}</el-button></template></el-table-column>
    </el-table>

    <el-dialog v-model="dialogVisible" :title="editing ? t('admin.persona.dialog.edit') : t('admin.persona.dialog.create')" width="min(720px, 94vw)">
      <el-form label-width="94px" @submit.prevent>
        <el-form-item :label="t('admin.persona.form.name')" required><el-input v-model="form.name" maxlength="160" :placeholder="t('admin.persona.form.namePlaceholder')" /></el-form-item>
        <el-form-item :label="t('admin.persona.form.description')"><el-input v-model="form.description" type="textarea" :rows="3" maxlength="2000" /></el-form-item>
        <el-form-item :label="t('admin.persona.form.sourceTitle')"><div class="wiki-source-row"><el-input v-model="form.sourceTitle" maxlength="255" :placeholder="t('admin.persona.form.sourceTitlePlaceholder')" @keyup.enter="previewFromWiki" /><el-button type="primary" plain :loading="wikiLoading" @click="previewFromWiki">{{ t('admin.persona.form.readWiki') }}</el-button></div><small class="wiki-hint">{{ t('admin.persona.form.wikiHint') }}</small><div v-if="wikiCandidates.length" class="wiki-candidates"><small>{{ t('admin.persona.form.wikiCandidates') }}</small><button v-for="candidate in wikiCandidates" :key="candidate.sourceTitle" type="button" @click="chooseWikiCandidate(candidate)"><strong>{{ candidate.name }}</strong><span>{{ candidate.sourceTitle }}</span></button></div><el-alert v-if="wikiError" class="wiki-error" type="warning" :closable="false" :title="wikiError" /></el-form-item>
        <el-form-item :label="t('admin.persona.form.photoUrl')"><el-input v-model="form.photoUrl" :placeholder="t('admin.persona.form.photoUrlPlaceholder')" /></el-form-item>
        <el-form-item :label="t('admin.persona.form.sourceUrl')"><el-input v-model="form.sourceUrl" :placeholder="t('admin.persona.form.sourceUrlPlaceholder')" /></el-form-item>
        <el-form-item :label="t('admin.persona.form.sourceLicense')"><el-input v-model="form.sourceLicense" :placeholder="t('admin.persona.form.sourceLicensePlaceholder')" /></el-form-item>
        <el-form-item :label="t('admin.persona.form.sourceAttribution')"><el-input v-model="form.sourceAttribution" /></el-form-item>
        <el-form-item :label="t('admin.persona.form.tags')"><el-input v-model="tagsText" type="textarea" :rows="2" maxlength="1000" :placeholder="t('admin.persona.form.tagsPlaceholder')" /><small class="wiki-hint">{{ t('admin.persona.form.tagsHint') }}</small></el-form-item>
        <el-form-item :label="t('admin.persona.form.position')"><el-select v-model="form.position"><el-option v-for="item in positions" :key="item" :label="positionLabel(item)" :value="item" /></el-select><small class="wiki-hint">{{ t('admin.persona.form.positionHint') }}</small></el-form-item>
        <el-form-item :label="t('admin.persona.form.price')"><el-input-number v-model="form.pricePoints" :min="0" :max="100000" /></el-form-item>
        <el-form-item :label="t('admin.persona.form.stats')"><div class="stat-fields"><label v-for="item in statKeys" :key="item.key">{{ item.label }}<el-input-number v-model="form.stats[item.key]" :min="1" :max="99" size="small" :disabled="item.key === 'overall'" @change="syncOverall" /></label></div><small class="wiki-hint">{{ t('admin.persona.form.statsHint') }}</small></el-form-item>
        <el-form-item :label="t('admin.persona.form.status')"><el-radio-group v-model="form.status"><el-radio-button label="DRAFT">{{ t('admin.persona.radio.draft') }}</el-radio-button><el-radio-button label="PUBLISHED">{{ t('admin.persona.radio.published') }}</el-radio-button><el-radio-button label="OFFLINE">{{ t('admin.persona.radio.offline') }}</el-radio-button></el-radio-group></el-form-item>
      </el-form>
      <template #footer><el-button @click="dialogVisible = false">{{ t('admin.common.cancel') }}</el-button><el-button type="primary" :loading="saving" @click="save">{{ t('admin.common.save') }}</el-button></template>
    </el-dialog>
    <el-dialog v-model="versionsVisible" :title="t('admin.persona.versionsTitle', { name: versionsCardName })" width="min(680px, 94vw)">
      <PageState v-if="versionsError" type="error" :title="t('admin.persona.versionsLoadFailed')" :description="versionsError" :action-text="t('admin.common.retry')" @action="reloadVersions" />
      <el-table v-else v-loading="versionsLoading" :data="versions" size="small" :empty-text="t('admin.persona.versionsEmpty')">
        <el-table-column prop="version" :label="t('admin.persona.col.version')" width="130" />
        <el-table-column prop="changeReason" :label="t('admin.persona.col.changeReason')" min-width="150" />
        <el-table-column prop="createdAt" :label="t('admin.persona.col.createdAt')" min-width="170" />
        <el-table-column prop="changedBy" :label="t('admin.persona.col.changedBy')" width="100" />
      </el-table>
    </el-dialog>
    <el-dialog v-model="auditVisible" :title="t('admin.persona.audit.title')" width="min(780px, 94vw)">
      <PageState v-if="auditError" type="error" :title="t('admin.persona.audit.loadFailed')" :description="auditError" :action-text="t('admin.common.retry')" @action="loadAudit" />
      <el-table v-else v-loading="auditLoading" :data="auditRows" size="small" :empty-text="t('admin.persona.audit.empty')">
        <el-table-column prop="createdAt" :label="t('admin.persona.audit.time')" width="170" />
        <el-table-column prop="action" :label="t('admin.persona.audit.action')" width="150" />
        <el-table-column prop="entityType" :label="t('admin.persona.audit.entity')" width="110" />
        <el-table-column prop="entityId" label="ID" width="80" />
        <el-table-column prop="operatorUserId" :label="t('admin.persona.audit.admin')" width="90" />
        <el-table-column prop="detail" :label="t('admin.persona.audit.detail')" min-width="220" show-overflow-tooltip />
      </el-table>
    </el-dialog>
  </el-card>
</template>

<script setup>
import { computed, reactive, ref } from 'vue'
import PageState from '../../components/layout/PageState.vue'
import { adminApi } from '../../api/admin'
import { useI18n } from '../../i18n'

const { t } = useI18n()

const props = defineProps({ items: { type: Array, default: () => [] }, loading: Boolean, error: { type: String, default: '' } })
const emit = defineEmits(['reload', 'create', 'update'])
const statusFilter = ref('')
const dialogVisible = ref(false); const editing = ref(false); const saving = ref(false)
const versionsVisible = ref(false); const versionsLoading = ref(false); const versionsError = ref(''); const versions = ref([]); const versionsCardId = ref(null); const versionsCardName = ref('')
const auditVisible = ref(false); const auditLoading = ref(false); const auditError = ref(''); const auditRows = ref([])
const wikiLoading = ref(false); const wikiError = ref(''); const wikiCandidates = ref([])
// Persisted data values (sent to and read from the API) stay in Chinese; only the
// labels shown in the UI are translated through positionLabel().
const positions = ['全能', '门将', '后卫', '中场', '前锋', '边锋', '前腰', '后腰', '中锋', '中后卫', '边后卫']
const positionLabelKeys = {
  '全能': 'admin.persona.position.allRound',
  '门将': 'admin.persona.position.goalkeeper',
  '后卫': 'admin.persona.position.defender',
  '中场': 'admin.persona.position.midfielder',
  '前锋': 'admin.persona.position.forward',
  '边锋': 'admin.persona.position.winger',
  '前腰': 'admin.persona.position.attackingMidfielder',
  '后腰': 'admin.persona.position.defensiveMidfielder',
  '中锋': 'admin.persona.position.centerForward',
  '中后卫': 'admin.persona.position.centerBack',
  '边后卫': 'admin.persona.position.fullBack'
}
const positionLabel = value => positionLabelKeys[value] ? t(positionLabelKeys[value]) : value
const statKeys = computed(() => [{ key: 'pace', label: t('admin.persona.stat.pace') }, { key: 'shooting', label: t('admin.persona.stat.shooting') }, { key: 'passing', label: t('admin.persona.stat.passing') }, { key: 'dribbling', label: t('admin.persona.stat.dribbling') }, { key: 'defending', label: t('admin.persona.stat.defending') }, { key: 'physical', label: t('admin.persona.stat.physical') }, { key: 'overall', label: t('admin.persona.stat.overall') }])
const blankForm = () => ({ id: null, name: '', description: '', sourceTitle: '', sourcePageId: '', sourceRevision: '', contentHash: '', sourceLanguage: 'zh', photoUrl: '', sourceUrl: '', sourceAttribution: 'Wikipedia · CC BY-SA 4.0 · 管理员策展', sourceLicense: 'CC BY-SA 4.0', archetype: '全能', position: '全能', pricePoints: 10, status: 'DRAFT', stats: { pace: 60, shooting: 60, passing: 60, dribbling: 60, defending: 60, physical: 60, overall: 60 } })
const form = reactive(blankForm())
const tagsText = ref('')
const statusLabel = status => ({ DRAFT: t('admin.persona.status.draft'), PUBLISHED: t('admin.persona.status.published'), OFFLINE: t('admin.persona.status.offline') }[status] || status)
const rarityType = rarity => ({ UR: 'warning', SSR: 'danger', SR: 'primary', R: 'success', N: 'info' }[rarity] || 'info')
const parseTags = value => { if (Array.isArray(value)) return value; try { const parsed = JSON.parse(value || '[]'); return Array.isArray(parsed) ? parsed : [] } catch { return String(value || '').split(/[,，、]/).map(item => item.trim()).filter(Boolean) } }
const resetForm = row => { const next = row ? { id: row.id, name: row.name || '', description: row.description || '', sourceTitle: row.source_title || '', sourcePageId: row.source_page_id || '', sourceRevision: row.source_revision || '', contentHash: row.content_hash || '', sourceLanguage: row.source_language || 'zh', photoUrl: row.photo_url || '', sourceUrl: row.source_url || '', sourceAttribution: row.source_attribution || 'Wikipedia · CC BY-SA 4.0 · 管理员策展', sourceLicense: row.source_license || 'CC BY-SA 4.0', archetype: row.archetype || '全能', position: row.position || '全能', pricePoints: Number(row.price_points || 0), status: row.status || 'DRAFT', stats: Object.fromEntries(statKeys.value.map(item => [item.key, Number(row[item.key] || 60)])) } : blankForm(); Object.assign(form, next); tagsText.value = row ? parseTags(row.tags_json).join(', ') : '' }
const syncOverall = () => { const keys = ['pace', 'shooting', 'passing', 'dribbling', 'defending', 'physical']; form.stats.overall = Math.round(keys.reduce((sum, key) => sum + Number(form.stats[key] || 0), 0) / keys.length) }
const openCreate = () => { editing.value = false; resetForm(); wikiError.value = ''; wikiCandidates.value = []; dialogVisible.value = true }
const openEdit = row => { editing.value = true; resetForm(row); wikiError.value = ''; wikiCandidates.value = []; dialogVisible.value = true }
const applyWikiPreview = preview => { if (!preview || preview.selectionRequired) return; form.name = preview.name || form.name; form.sourceTitle = preview.sourceTitle || form.sourceTitle; form.sourcePageId = preview.sourcePageId || form.sourcePageId; form.sourceRevision = preview.sourceRevision || form.sourceRevision; form.contentHash = preview.contentHash || form.contentHash; form.sourceLanguage = preview.sourceLanguage || form.sourceLanguage; form.description = preview.summary || preview.description || form.description; form.photoUrl = preview.photoUrl || form.photoUrl; form.sourceUrl = preview.sourceUrl || form.sourceUrl; form.sourceAttribution = preview.sourceAttribution || form.sourceAttribution; form.sourceLicense = preview.sourceLicense || form.sourceLicense; form.archetype = preview.archetype || form.archetype; form.position = preview.position || form.position; tagsText.value = (preview.tags || []).join(', '); const stats = preview.stats || {}; Object.keys(form.stats).forEach(key => { if (stats[key] != null) form.stats[key] = Number(stats[key]) }) }
const previewFromWiki = async () => { const sourceTitle = form.sourceTitle.trim() || form.name.trim(); if (!sourceTitle) return; wikiLoading.value = true; wikiError.value = ''; wikiCandidates.value = []; try { const preview = await adminApi.previewPersonaCatalog({ sourceTitle, archetype: form.archetype, position: form.position }); if (preview?.selectionRequired) { wikiCandidates.value = preview.candidates || []; return } applyWikiPreview(preview) } catch (error) { wikiError.value = error?.message || t('admin.persona.err.wiki') } finally { wikiLoading.value = false } }
const chooseWikiCandidate = async candidate => { form.sourceTitle = candidate.sourceTitle || candidate.name; wikiCandidates.value = []; await previewFromWiki() }
const payload = () => ({ name: form.name.trim(), description: form.description, sourceTitle: form.sourceTitle, sourcePageId: form.sourcePageId, sourceRevision: form.sourceRevision, contentHash: form.contentHash, sourceLanguage: form.sourceLanguage, photoUrl: form.photoUrl, sourceUrl: form.sourceUrl, sourceAttribution: form.sourceAttribution, sourceLicense: form.sourceLicense, archetype: form.archetype, position: form.position, tags: tagsText.value.split(/[,，、]/).map(item => item.trim()).filter(Boolean), pricePoints: form.pricePoints, status: form.status, stats: form.stats })
const save = async () => { if (!form.name.trim()) return; syncOverall(); saving.value = true; try { emit('update', editing.value ? { id: form.id, payload: payload() } : { id: null, payload: payload() }); dialogVisible.value = false } finally { saving.value = false } }
const toggleStatus = row => emit('update', { id: row.id, payload: { ...row, sourceTitle: row.source_title, photoUrl: row.photo_url, sourceUrl: row.source_url, sourceAttribution: row.source_attribution, sourceLicense: row.source_license, tags: parseTags(row.tags_json), pricePoints: row.price_points, stats: Object.fromEntries(statKeys.value.map(item => [item.key, row[item.key]])), status: row.status === 'PUBLISHED' ? 'OFFLINE' : 'PUBLISHED' } })
const reload = () => emit('reload', statusFilter.value)
const viewVersions = async row => { versionsCardId.value = row?.id; versionsCardName.value = row?.name || t('admin.persona.defaultRole'); versionsVisible.value = true; await reloadVersions() }
const reloadVersions = async () => { if (!versionsCardId.value) return; versionsLoading.value = true; versionsError.value = ''; try { versions.value = await adminApi.listPersonaCatalogVersions(versionsCardId.value) } catch (error) { versions.value = []; versionsError.value = error?.message || t('admin.persona.versionsLoadFailed') } finally { versionsLoading.value = false } }
const openAudit = async () => { auditVisible.value = true; await loadAudit() }
const loadAudit = async () => { auditLoading.value = true; auditError.value = ''; try { const result = await adminApi.listCardLabAudit(80); auditRows.value = Array.isArray(result) ? result : (result?.items || []) } catch (error) { auditRows.value = []; auditError.value = error?.message || t('admin.persona.audit.loadFailed') } finally { auditLoading.value = false } }
</script>

<style scoped>
.panel-header{display:flex;align-items:center;justify-content:space-between;gap:14px}.panel-header>div:first-child{display:flex;flex-direction:column;gap:4px}.panel-header small{color:var(--ff-text-muted);font-size:12px}.panel-actions{display:flex;align-items:center;gap:8px}.catalog-name{display:flex;align-items:center;gap:10px}.catalog-name>img,.catalog-name>span{width:36px;height:36px;border-radius:50%;object-fit:cover;background:var(--ff-bg-alt);display:grid;place-items:center;color:var(--ff-primary);font-weight:800}.catalog-name div{display:flex;flex-direction:column;gap:3px}.catalog-name small{color:var(--ff-text-muted);font-size:11px}.inline-fields{display:flex;gap:10px}.inline-fields .el-select{width:160px}.stat-fields{display:grid;grid-template-columns:repeat(4,1fr);gap:8px;width:100%}.stat-fields label{display:flex;align-items:center;gap:5px;color:var(--ff-text-muted);font-size:11px}.stat-fields :deep(.el-input-number){width:112px}.wiki-source-row{display:flex;align-items:center;gap:8px;width:100%}.wiki-source-row .el-input{flex:1}.wiki-hint{display:block;margin-top:6px;color:var(--ff-text-muted);font-size:11px;line-height:1.5}.wiki-candidates{display:flex;flex-direction:column;gap:6px;margin-top:10px;padding:9px;border:1px solid var(--ff-border);border-radius:6px;background:var(--ff-bg-alt)}.wiki-candidates>small{color:var(--ff-text-muted)}.wiki-candidates button{display:flex;align-items:center;justify-content:space-between;gap:10px;padding:7px 9px;border:1px solid transparent;border-radius:5px;background:var(--ff-bg);color:var(--ff-text);text-align:left;cursor:pointer}.wiki-candidates button:hover{border-color:var(--ff-primary)}.wiki-candidates button span{color:var(--ff-text-muted);font-size:11px}.wiki-error{margin-top:8px}@media(max-width:720px){.panel-header{align-items:flex-start;flex-direction:column}.panel-actions{width:100%;flex-wrap:wrap}.inline-fields,.stat-fields{grid-template-columns:1fr;flex-direction:column}.inline-fields .el-select{width:100%}.stat-fields{display:grid;grid-template-columns:repeat(2,1fr)}.wiki-source-row{align-items:stretch;flex-direction:column}}
.tag-preview{display:flex;align-items:center;gap:4px;flex-wrap:wrap}.tag-preview>span{color:var(--ff-text-muted);font-size:11px}.tag-preview .empty-tag{font-style:italic}
</style>
