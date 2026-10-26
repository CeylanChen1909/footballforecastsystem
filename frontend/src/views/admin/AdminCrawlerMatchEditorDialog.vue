<template>
  <el-dialog v-model="visible" :title="form.id ? t('admin.crawlerEditor.title.edit') : t('admin.crawlerEditor.title.create')" width="860px" class="admin-dialog">
    <el-form ref="formRef" :model="form" :rules="rules" label-width="110px" class="form-grid">
      <el-form-item label="Fixture ID" prop="fixtureId"><el-input v-model="form.fixtureId" disabled /></el-form-item>
      <el-form-item label="External ID" prop="externalMatchId"><el-input v-model="form.externalMatchId" :placeholder="t('admin.crawlerEditor.placeholder.external')" /></el-form-item>
      <el-form-item :label="t('admin.crawlerEditor.label.source')" prop="source"><el-input v-model="form.source" placeholder="bbc-scores" /></el-form-item>
      <el-form-item :label="t('admin.crawlerEditor.label.matchDate')" prop="matchDate"><el-date-picker v-model="form.matchDate" type="date" value-format="YYYY-MM-DD" format="YYYY-MM-DD" :placeholder="t('admin.crawlerEditor.placeholder.date')" style="width:100%" /></el-form-item>
      <el-form-item :label="t('admin.crawlerEditor.label.matchTime')" prop="matchTime"><el-time-picker v-model="form.matchTime" value-format="HH:mm:ss" format="HH:mm" :placeholder="t('admin.crawlerEditor.placeholder.time')" style="width:100%" /></el-form-item>
      <el-form-item :label="t('admin.crawlerEditor.label.league')" prop="leagueName"><el-input v-model="form.leagueName" /></el-form-item>
      <el-form-item :label="t('admin.crawlerEditor.label.leagueId')"><el-input v-model="form.leagueId" /></el-form-item>
      <el-form-item :label="t('admin.crawlerEditor.label.round')"><el-input v-model="form.round" /></el-form-item>
      <el-form-item :label="t('admin.crawlerEditor.label.venue')"><el-input v-model="form.venue" /></el-form-item>
      <el-form-item :label="t('admin.crawlerEditor.label.homeTeam')" prop="homeTeamName"><el-input v-model="form.homeTeamName" /></el-form-item>
      <el-form-item :label="t('admin.crawlerEditor.label.homeTeamId')"><el-input v-model="form.homeTeamId" /></el-form-item>
      <el-form-item :label="t('admin.crawlerEditor.label.homeTeamLogo')" prop="homeTeamLogo"><el-input v-model="form.homeTeamLogo" placeholder="https://..." /></el-form-item>
      <el-form-item :label="t('admin.crawlerEditor.label.awayTeam')" prop="awayTeamName"><el-input v-model="form.awayTeamName" /></el-form-item>
      <el-form-item :label="t('admin.crawlerEditor.label.awayTeamId')"><el-input v-model="form.awayTeamId" /></el-form-item>
      <el-form-item :label="t('admin.crawlerEditor.label.awayTeamLogo')" prop="awayTeamLogo"><el-input v-model="form.awayTeamLogo" placeholder="https://..." /></el-form-item>
      <el-form-item :label="t('admin.crawlerEditor.label.status')" prop="status"><el-select v-model="form.status"><el-option label="SCHEDULED" value="NS" /><el-option label="LIVE" value="LIVE" /><el-option label="FINISHED" value="FT" /><el-option label="CANCELED" value="CANCEL" /></el-select></el-form-item>
      <el-form-item :label="t('admin.crawlerEditor.label.score')" prop="homeScore"><div class="score-row"><el-input v-model="form.homeScore" :placeholder="t('admin.crawlerEditor.placeholder.homeTeam')" /><span>-</span><el-input v-model="form.awayScore" :placeholder="t('admin.crawlerEditor.placeholder.awayTeam')" /></div></el-form-item>
      <el-form-item :label="t('admin.crawlerEditor.label.note')" class="span-2"><el-input v-model="form.note" type="textarea" :rows="4" /></el-form-item>
    </el-form>
    <template #footer><el-button @click="visible = false">{{ t('admin.common.cancel') }}</el-button><el-button type="primary" @click="submit">{{ t('admin.common.save') }}</el-button></template>
  </el-dialog>
</template>

<script setup>
import { computed, ref } from 'vue'
import { useI18n } from '../../i18n'

const { t } = useI18n()

const props = defineProps({ modelValue: Boolean, form: { type: Object, required: true } })
const emit = defineEmits(['update:modelValue', 'save'])
const visible = computed({ get: () => props.modelValue, set: value => emit('update:modelValue', value) })
const formRef = ref(null)
const requiredText = (label) => [{ required: true, whitespace: true, message: t('admin.crawlerEditor.validation.enterField', { field: label }), trigger: 'blur' }]
const urlRule = (_rule, value, callback) => {
  if (!value) return callback()
  try { const url = new URL(value); if (!['http:', 'https:'].includes(url.protocol)) throw new Error('unsupported'); callback() } catch { callback(new Error(t('admin.crawlerEditor.validation.url'))) }
}
const scoreRule = (_rule, value, callback) => {
  const values = [form.homeScore, form.awayScore]
  const filled = values.filter(item => item !== '' && item !== null && item !== undefined)
  if (filled.length === 1) return callback(new Error(t('admin.crawlerEditor.validation.bothScores')))
  if (filled.some(item => !/^\d+$/.test(String(item)))) return callback(new Error(t('admin.crawlerEditor.validation.scoreInteger')))
  callback()
}
const rules = computed(() => ({
  matchDate: requiredText(t('admin.crawlerEditor.field.matchDate')),
  matchTime: requiredText(t('admin.crawlerEditor.field.matchTime')),
  leagueName: requiredText(t('admin.crawlerEditor.field.league')),
  homeTeamName: requiredText(t('admin.crawlerEditor.field.homeTeamName')),
  awayTeamName: requiredText(t('admin.crawlerEditor.field.awayTeamName')),
  status: [{ required: true, message: t('admin.crawlerEditor.validation.selectStatus'), trigger: 'change' }],
  homeTeamLogo: [{ validator: urlRule, trigger: 'blur' }],
  awayTeamLogo: [{ validator: urlRule, trigger: 'blur' }],
  homeScore: [{ validator: scoreRule, trigger: 'blur' }]
}))
const submit = async () => { const valid = await formRef.value?.validate().catch(() => false); if (valid) emit('save') }
</script>

<style scoped>
.form-grid { display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); gap:6px 20px; }
.form-grid :deep(.el-form-item) { margin-bottom:12px; }
.span-2 { grid-column:span 2; }
.score-row { display:flex; align-items:center; gap:10px; }
.score-row :deep(.el-input) { flex:1; }
@media (max-width:760px) { .form-grid { grid-template-columns:1fr; } .span-2 { grid-column:auto; } }
</style>
