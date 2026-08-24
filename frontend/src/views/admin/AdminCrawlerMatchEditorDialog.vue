<template>
  <el-dialog v-model="visible" :title="form.id ? '编辑爬虫比赛' : '新增爬虫比赛'" width="860px" class="admin-dialog">
    <el-form ref="formRef" :model="form" :rules="rules" label-width="110px" class="form-grid">
      <el-form-item label="Fixture ID" prop="fixtureId"><el-input v-model="form.fixtureId" disabled /></el-form-item>
      <el-form-item label="External ID" prop="externalMatchId"><el-input v-model="form.externalMatchId" placeholder="主爬虫事件 ID，用于详情和预测关联" /></el-form-item>
      <el-form-item label="数据来源" prop="source"><el-input v-model="form.source" placeholder="bbc-scores" /></el-form-item>
      <el-form-item label="比赛日期" prop="matchDate"><el-date-picker v-model="form.matchDate" type="date" value-format="YYYY-MM-DD" format="YYYY-MM-DD" placeholder="选择日期" style="width:100%" /></el-form-item>
      <el-form-item label="比赛时间" prop="matchTime"><el-time-picker v-model="form.matchTime" value-format="HH:mm:ss" format="HH:mm" placeholder="选择时间" style="width:100%" /></el-form-item>
      <el-form-item label="联赛" prop="leagueName"><el-input v-model="form.leagueName" /></el-form-item>
      <el-form-item label="联赛ID"><el-input v-model="form.leagueId" /></el-form-item>
      <el-form-item label="轮次"><el-input v-model="form.round" /></el-form-item>
      <el-form-item label="场馆"><el-input v-model="form.venue" /></el-form-item>
      <el-form-item label="主队" prop="homeTeamName"><el-input v-model="form.homeTeamName" /></el-form-item>
      <el-form-item label="主队ID"><el-input v-model="form.homeTeamId" /></el-form-item>
      <el-form-item label="主队Logo" prop="homeTeamLogo"><el-input v-model="form.homeTeamLogo" placeholder="https://..." /></el-form-item>
      <el-form-item label="客队" prop="awayTeamName"><el-input v-model="form.awayTeamName" /></el-form-item>
      <el-form-item label="客队ID"><el-input v-model="form.awayTeamId" /></el-form-item>
      <el-form-item label="客队Logo" prop="awayTeamLogo"><el-input v-model="form.awayTeamLogo" placeholder="https://..." /></el-form-item>
      <el-form-item label="状态" prop="status"><el-select v-model="form.status"><el-option label="SCHEDULED" value="NS" /><el-option label="LIVE" value="LIVE" /><el-option label="FINISHED" value="FT" /><el-option label="CANCELED" value="CANCEL" /></el-select></el-form-item>
      <el-form-item label="比分" prop="homeScore"><div class="score-row"><el-input v-model="form.homeScore" placeholder="主队" /><span>-</span><el-input v-model="form.awayScore" placeholder="客队" /></div></el-form-item>
      <el-form-item label="备注" class="span-2"><el-input v-model="form.note" type="textarea" :rows="4" /></el-form-item>
    </el-form>
    <template #footer><el-button @click="visible = false">取消</el-button><el-button type="primary" @click="submit">保存</el-button></template>
  </el-dialog>
</template>

<script setup>
import { computed, ref } from 'vue'

const props = defineProps({ modelValue: Boolean, form: { type: Object, required: true } })
const emit = defineEmits(['update:modelValue', 'save'])
const visible = computed({ get: () => props.modelValue, set: value => emit('update:modelValue', value) })
const formRef = ref(null)
const requiredText = (label) => [{ required: true, whitespace: true, message: `请输入${label}`, trigger: 'blur' }]
const urlRule = (_rule, value, callback) => {
  if (!value) return callback()
  try { const url = new URL(value); if (!['http:', 'https:'].includes(url.protocol)) throw new Error('unsupported'); callback() } catch { callback(new Error('请输入有效的 http(s) 地址')) }
}
const scoreRule = (_rule, value, callback) => {
  const values = [form.homeScore, form.awayScore]
  const filled = values.filter(item => item !== '' && item !== null && item !== undefined)
  if (filled.length === 1) return callback(new Error('请同时填写主队和客队比分'))
  if (filled.some(item => !/^\d+$/.test(String(item)))) return callback(new Error('比分必须为非负整数'))
  callback()
}
const rules = { matchDate: requiredText('比赛日期'), matchTime: requiredText('比赛时间'), leagueName: requiredText('联赛'), homeTeamName: requiredText('主队名称'), awayTeamName: requiredText('客队名称'), status: [{ required: true, message: '请选择状态', trigger: 'change' }], homeTeamLogo: [{ validator: urlRule, trigger: 'blur' }], awayTeamLogo: [{ validator: urlRule, trigger: 'blur' }], homeScore: [{ validator: scoreRule, trigger: 'blur' }] }
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
