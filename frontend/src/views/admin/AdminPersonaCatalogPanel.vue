<template>
  <el-card class="panel-card" shadow="never">
    <template #header>
      <div class="panel-header">
        <div><strong>虚拟角色目录</strong><small>管理员可从 Wikipedia 读取词条并生成初始卡面，调整数值后上架；用户只能使用点数兑换。</small></div>
        <div class="panel-actions"><el-select v-model="statusFilter" size="small" style="width: 120px" @change="reload"><el-option label="全部" value="" /><el-option label="草稿" value="DRAFT" /><el-option label="已上架" value="PUBLISHED" /><el-option label="已下架" value="OFFLINE" /></el-select><el-button type="primary" size="small" @click="openCreate">新建角色</el-button><el-button size="small" plain @click="openAudit">审计日志</el-button><el-button size="small" :loading="loading" @click="reload">刷新</el-button></div>
      </div>
    </template>
    <PageState v-if="error" type="error" title="目录加载失败" :description="error" action-text="重试" @action="reload" />
    <el-table v-else :data="items" stripe empty-text="暂无目录角色">
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column label="角色" min-width="190"><template #default="{ row }"><div class="catalog-name"><img v-if="row.photo_url" :src="row.photo_url" :alt="row.name" @error="row.photo_url = ''"><span v-else>{{ String(row.name || '?').slice(0, 1) }}</span><div><strong>{{ row.name }}</strong><small>默认位置：{{ row.position || '全能' }}</small></div></div></template></el-table-column>
      <el-table-column prop="overall" label="总评" width="80" />
      <el-table-column label="标签 / 特点" min-width="190"><template #default="{ row }"><div class="tag-preview"><el-tag v-for="tag in parseTags(row.tags_json).slice(0, 3)" :key="tag" size="small" effect="plain">{{ tag }}</el-tag><span v-if="parseTags(row.tags_json).length > 3">+{{ parseTags(row.tags_json).length - 3 }}</span><span v-if="!parseTags(row.tags_json).length" class="empty-tag">未设置</span></div></template></el-table-column>
      <el-table-column prop="rarity" label="品质" width="80"><template #default="{ row }"><el-tag :type="rarityType(row.rarity)" effect="dark">{{ row.rarity || 'N' }}</el-tag></template></el-table-column>
      <el-table-column prop="price_points" label="兑换点数" width="100" />
      <el-table-column prop="status" label="状态" width="100"><template #default="{ row }"><el-tag :type="row.status === 'PUBLISHED' ? 'success' : row.status === 'OFFLINE' ? 'info' : 'warning'">{{ statusLabel(row.status) }}</el-tag></template></el-table-column>
      <el-table-column prop="updated_at" label="更新时间" width="180" />
      <el-table-column label="操作" width="250" fixed="right"><template #default="{ row }"><el-button size="small" @click="openEdit(row)">编辑</el-button><el-button size="small" plain @click="viewVersions(row)">版本</el-button><el-button size="small" :type="row.status === 'PUBLISHED' ? 'warning' : 'success'" plain @click="toggleStatus(row)">{{ row.status === 'PUBLISHED' ? '下架' : '上架' }}</el-button></template></el-table-column>
    </el-table>

    <el-dialog v-model="dialogVisible" :title="editing ? '编辑目录角色' : '新建目录角色'" width="min(720px, 94vw)">
      <el-form label-width="94px" @submit.prevent>
        <el-form-item label="角色名称" required><el-input v-model="form.name" maxlength="160" placeholder="例如：孙悟空（西游记）" /></el-form-item>
        <el-form-item label="简介"><el-input v-model="form.description" type="textarea" :rows="3" maxlength="2000" /></el-form-item>
        <el-form-item label="来源条目"><div class="wiki-source-row"><el-input v-model="form.sourceTitle" maxlength="255" placeholder="输入 Wikipedia 词条，例如：孙悟空" @keyup.enter="previewFromWiki" /><el-button type="primary" plain :loading="wikiLoading" @click="previewFromWiki">读取 Wiki</el-button></div><small class="wiki-hint">读取摘要、头像和词条关键词，自动生成初始能力值；保存前仍可手动调整。</small><div v-if="wikiCandidates.length" class="wiki-candidates"><small>找到多个匹配，请选择具体词条：</small><button v-for="candidate in wikiCandidates" :key="candidate.sourceTitle" type="button" @click="chooseWikiCandidate(candidate)"><strong>{{ candidate.name }}</strong><span>{{ candidate.sourceTitle }}</span></button></div><el-alert v-if="wikiError" class="wiki-error" type="warning" :closable="false" :title="wikiError" /></el-form-item>
        <el-form-item label="头像 URL"><el-input v-model="form.photoUrl" placeholder="可选，建议使用已获授权的素材" /></el-form-item>
        <el-form-item label="来源 URL"><el-input v-model="form.sourceUrl" placeholder="上架必填，必须是 Wikipedia 页面 URL" /></el-form-item>
        <el-form-item label="来源许可"><el-input v-model="form.sourceLicense" placeholder="例如：CC BY-SA 4.0" /></el-form-item>
        <el-form-item label="来源署名"><el-input v-model="form.sourceAttribution" /></el-form-item>
        <el-form-item label="标签 / 特点"><el-input v-model="tagsText" type="textarea" :rows="2" maxlength="1000" placeholder="作品:银魂，特征:银白发，身份:武士" /><small class="wiki-hint">用逗号分隔；作品、阵营、身份、种族、特征等标签可激活阵容羁绊。读取 Wiki 后会自动预填，保存前可调整。</small></el-form-item>
        <el-form-item label="球员位置"><el-select v-model="form.position"><el-option v-for="item in positions" :key="item" :label="item" :value="item" /></el-select><small class="wiki-hint">读取 Wiki 后会根据角色性格、能力关键词自动推断默认位置；管理员可以在这里修正为门将、后卫、中场、前锋或细分位置。</small></el-form-item>
        <el-form-item label="兑换点数"><el-input-number v-model="form.pricePoints" :min="0" :max="100000" /></el-form-item>
        <el-form-item label="能力值"><div class="stat-fields"><label v-for="item in statKeys" :key="item.key">{{ item.label }}<el-input-number v-model="form.stats[item.key]" :min="1" :max="99" size="small" :disabled="item.key === 'overall'" @change="syncOverall" /></label></div><small class="wiki-hint">总评根据六项能力自动计算，不能单独修改。</small></el-form-item>
        <el-form-item label="状态"><el-radio-group v-model="form.status"><el-radio-button label="DRAFT">草稿</el-radio-button><el-radio-button label="PUBLISHED">上架</el-radio-button><el-radio-button label="OFFLINE">下架</el-radio-button></el-radio-group></el-form-item>
      </el-form>
      <template #footer><el-button @click="dialogVisible = false">取消</el-button><el-button type="primary" :loading="saving" @click="save">保存</el-button></template>
    </el-dialog>
    <el-dialog v-model="versionsVisible" :title="`${versionsCardName} · 版本记录`" width="min(680px, 94vw)">
      <PageState v-if="versionsError" type="error" title="版本记录加载失败" :description="versionsError" action-text="重试" @action="reloadVersions" />
      <el-table v-else v-loading="versionsLoading" :data="versions" size="small" empty-text="暂无版本记录">
        <el-table-column prop="version" label="版本" width="130" />
        <el-table-column prop="changeReason" label="变更原因" min-width="150" />
        <el-table-column prop="createdAt" label="创建时间" min-width="170" />
        <el-table-column prop="changedBy" label="操作人" width="100" />
      </el-table>
    </el-dialog>
    <el-dialog v-model="auditVisible" title="Card Lab 管理审计" width="min(780px, 94vw)">
      <PageState v-if="auditError" type="error" title="审计日志加载失败" :description="auditError" action-text="重试" @action="loadAudit" />
      <el-table v-else v-loading="auditLoading" :data="auditRows" size="small" empty-text="暂无操作记录">
        <el-table-column prop="createdAt" label="时间" width="170" />
        <el-table-column prop="action" label="操作" width="150" />
        <el-table-column prop="entityType" label="对象" width="110" />
        <el-table-column prop="entityId" label="ID" width="80" />
        <el-table-column prop="operatorUserId" label="管理员" width="90" />
        <el-table-column prop="detail" label="详情" min-width="220" show-overflow-tooltip />
      </el-table>
    </el-dialog>
  </el-card>
</template>

<script setup>
import { reactive, ref } from 'vue'
import PageState from '../../components/layout/PageState.vue'
import { adminApi } from '../../api/admin'

const props = defineProps({ items: { type: Array, default: () => [] }, loading: Boolean, error: { type: String, default: '' } })
const emit = defineEmits(['reload', 'create', 'update'])
const statusFilter = ref('')
const dialogVisible = ref(false); const editing = ref(false); const saving = ref(false)
const versionsVisible = ref(false); const versionsLoading = ref(false); const versionsError = ref(''); const versions = ref([]); const versionsCardId = ref(null); const versionsCardName = ref('')
const auditVisible = ref(false); const auditLoading = ref(false); const auditError = ref(''); const auditRows = ref([])
const wikiLoading = ref(false); const wikiError = ref(''); const wikiCandidates = ref([])
const positions = ['全能', '门将', '后卫', '中场', '前锋', '边锋', '前腰', '后腰', '中锋', '中后卫', '边后卫']
const statKeys = [{ key: 'pace', label: '速度' }, { key: 'shooting', label: '射门' }, { key: 'passing', label: '传球' }, { key: 'dribbling', label: '盘带' }, { key: 'defending', label: '防守' }, { key: 'physical', label: '身体' }, { key: 'overall', label: '总评' }]
const blankForm = () => ({ id: null, name: '', description: '', sourceTitle: '', sourcePageId: '', sourceRevision: '', contentHash: '', sourceLanguage: 'zh', photoUrl: '', sourceUrl: '', sourceAttribution: 'Wikipedia · CC BY-SA 4.0 · 管理员策展', sourceLicense: 'CC BY-SA 4.0', archetype: '全能', position: '全能', pricePoints: 10, status: 'DRAFT', stats: { pace: 60, shooting: 60, passing: 60, dribbling: 60, defending: 60, physical: 60, overall: 60 } })
const form = reactive(blankForm())
const tagsText = ref('')
const statusLabel = status => ({ DRAFT: '草稿', PUBLISHED: '已上架', OFFLINE: '已下架' }[status] || status)
const rarityType = rarity => ({ UR: 'warning', SSR: 'danger', SR: 'primary', R: 'success', N: 'info' }[rarity] || 'info')
const parseTags = value => { if (Array.isArray(value)) return value; try { const parsed = JSON.parse(value || '[]'); return Array.isArray(parsed) ? parsed : [] } catch { return String(value || '').split(/[,，、]/).map(item => item.trim()).filter(Boolean) } }
const resetForm = row => { const next = row ? { id: row.id, name: row.name || '', description: row.description || '', sourceTitle: row.source_title || '', sourcePageId: row.source_page_id || '', sourceRevision: row.source_revision || '', contentHash: row.content_hash || '', sourceLanguage: row.source_language || 'zh', photoUrl: row.photo_url || '', sourceUrl: row.source_url || '', sourceAttribution: row.source_attribution || 'Wikipedia · CC BY-SA 4.0 · 管理员策展', sourceLicense: row.source_license || 'CC BY-SA 4.0', archetype: row.archetype || '全能', position: row.position || '全能', pricePoints: Number(row.price_points || 0), status: row.status || 'DRAFT', stats: Object.fromEntries(statKeys.map(item => [item.key, Number(row[item.key] || 60)])) } : blankForm(); Object.assign(form, next); tagsText.value = row ? parseTags(row.tags_json).join(', ') : '' }
const syncOverall = () => { const keys = ['pace', 'shooting', 'passing', 'dribbling', 'defending', 'physical']; form.stats.overall = Math.round(keys.reduce((sum, key) => sum + Number(form.stats[key] || 0), 0) / keys.length) }
const openCreate = () => { editing.value = false; resetForm(); wikiError.value = ''; wikiCandidates.value = []; dialogVisible.value = true }
const openEdit = row => { editing.value = true; resetForm(row); wikiError.value = ''; wikiCandidates.value = []; dialogVisible.value = true }
const applyWikiPreview = preview => { if (!preview || preview.selectionRequired) return; form.name = preview.name || form.name; form.sourceTitle = preview.sourceTitle || form.sourceTitle; form.sourcePageId = preview.sourcePageId || form.sourcePageId; form.sourceRevision = preview.sourceRevision || form.sourceRevision; form.contentHash = preview.contentHash || form.contentHash; form.sourceLanguage = preview.sourceLanguage || form.sourceLanguage; form.description = preview.summary || preview.description || form.description; form.photoUrl = preview.photoUrl || form.photoUrl; form.sourceUrl = preview.sourceUrl || form.sourceUrl; form.sourceAttribution = preview.sourceAttribution || form.sourceAttribution; form.sourceLicense = preview.sourceLicense || form.sourceLicense; form.archetype = preview.archetype || form.archetype; form.position = preview.position || form.position; tagsText.value = (preview.tags || []).join(', '); const stats = preview.stats || {}; Object.keys(form.stats).forEach(key => { if (stats[key] != null) form.stats[key] = Number(stats[key]) }) }
const previewFromWiki = async () => { const sourceTitle = form.sourceTitle.trim() || form.name.trim(); if (!sourceTitle) return; wikiLoading.value = true; wikiError.value = ''; wikiCandidates.value = []; try { const preview = await adminApi.previewPersonaCatalog({ sourceTitle, archetype: form.archetype, position: form.position }); if (preview?.selectionRequired) { wikiCandidates.value = preview.candidates || []; return } applyWikiPreview(preview) } catch (error) { wikiError.value = error?.message || 'Wiki 词条读取失败，请检查词条名或稍后重试' } finally { wikiLoading.value = false } }
const chooseWikiCandidate = async candidate => { form.sourceTitle = candidate.sourceTitle || candidate.name; wikiCandidates.value = []; await previewFromWiki() }
const payload = () => ({ name: form.name.trim(), description: form.description, sourceTitle: form.sourceTitle, sourcePageId: form.sourcePageId, sourceRevision: form.sourceRevision, contentHash: form.contentHash, sourceLanguage: form.sourceLanguage, photoUrl: form.photoUrl, sourceUrl: form.sourceUrl, sourceAttribution: form.sourceAttribution, sourceLicense: form.sourceLicense, archetype: form.archetype, position: form.position, tags: tagsText.value.split(/[,，、]/).map(item => item.trim()).filter(Boolean), pricePoints: form.pricePoints, status: form.status, stats: form.stats })
const save = async () => { if (!form.name.trim()) return; syncOverall(); saving.value = true; try { emit('update', editing.value ? { id: form.id, payload: payload() } : { id: null, payload: payload() }); dialogVisible.value = false } finally { saving.value = false } }
const toggleStatus = row => emit('update', { id: row.id, payload: { ...row, sourceTitle: row.source_title, photoUrl: row.photo_url, sourceUrl: row.source_url, sourceAttribution: row.source_attribution, sourceLicense: row.source_license, tags: parseTags(row.tags_json), pricePoints: row.price_points, stats: Object.fromEntries(statKeys.map(item => [item.key, row[item.key]])), status: row.status === 'PUBLISHED' ? 'OFFLINE' : 'PUBLISHED' } })
const reload = () => emit('reload', statusFilter.value)
const viewVersions = async row => { versionsCardId.value = row?.id; versionsCardName.value = row?.name || '角色'; versionsVisible.value = true; await reloadVersions() }
const reloadVersions = async () => { if (!versionsCardId.value) return; versionsLoading.value = true; versionsError.value = ''; try { versions.value = await adminApi.listPersonaCatalogVersions(versionsCardId.value) } catch (error) { versions.value = []; versionsError.value = error?.message || '版本记录加载失败' } finally { versionsLoading.value = false } }
const openAudit = async () => { auditVisible.value = true; await loadAudit() }
const loadAudit = async () => { auditLoading.value = true; auditError.value = ''; try { const result = await adminApi.listCardLabAudit(80); auditRows.value = Array.isArray(result) ? result : (result?.items || []) } catch (error) { auditRows.value = []; auditError.value = error?.message || '审计日志加载失败' } finally { auditLoading.value = false } }
</script>

<style scoped>
.panel-header{display:flex;align-items:center;justify-content:space-between;gap:14px}.panel-header>div:first-child{display:flex;flex-direction:column;gap:4px}.panel-header small{color:var(--ff-text-muted);font-size:12px}.panel-actions{display:flex;align-items:center;gap:8px}.catalog-name{display:flex;align-items:center;gap:10px}.catalog-name>img,.catalog-name>span{width:36px;height:36px;border-radius:50%;object-fit:cover;background:var(--ff-bg-alt);display:grid;place-items:center;color:var(--ff-primary);font-weight:800}.catalog-name div{display:flex;flex-direction:column;gap:3px}.catalog-name small{color:var(--ff-text-muted);font-size:11px}.inline-fields{display:flex;gap:10px}.inline-fields .el-select{width:160px}.stat-fields{display:grid;grid-template-columns:repeat(4,1fr);gap:8px;width:100%}.stat-fields label{display:flex;align-items:center;gap:5px;color:var(--ff-text-muted);font-size:11px}.stat-fields :deep(.el-input-number){width:112px}.wiki-source-row{display:flex;align-items:center;gap:8px;width:100%}.wiki-source-row .el-input{flex:1}.wiki-hint{display:block;margin-top:6px;color:var(--ff-text-muted);font-size:11px;line-height:1.5}.wiki-candidates{display:flex;flex-direction:column;gap:6px;margin-top:10px;padding:9px;border:1px solid var(--ff-border);border-radius:6px;background:var(--ff-bg-alt)}.wiki-candidates>small{color:var(--ff-text-muted)}.wiki-candidates button{display:flex;align-items:center;justify-content:space-between;gap:10px;padding:7px 9px;border:1px solid transparent;border-radius:5px;background:var(--ff-bg);color:var(--ff-text);text-align:left;cursor:pointer}.wiki-candidates button:hover{border-color:var(--ff-primary)}.wiki-candidates button span{color:var(--ff-text-muted);font-size:11px}.wiki-error{margin-top:8px}@media(max-width:720px){.panel-header{align-items:flex-start;flex-direction:column}.panel-actions{width:100%;flex-wrap:wrap}.inline-fields,.stat-fields{grid-template-columns:1fr;flex-direction:column}.inline-fields .el-select{width:100%}.stat-fields{display:grid;grid-template-columns:repeat(2,1fr)}.wiki-source-row{align-items:stretch;flex-direction:column}}
.tag-preview{display:flex;align-items:center;gap:4px;flex-wrap:wrap}.tag-preview>span{color:var(--ff-text-muted);font-size:11px}.tag-preview .empty-tag{font-style:italic}
</style>
