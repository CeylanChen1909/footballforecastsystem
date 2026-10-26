<template>
  <div v-if="visible" class="model-quality-panel" :aria-label="t('prediction.modelQuality.aria')">
    <div class="quality-panel-head">
      <div><strong>{{ t('prediction.modelQuality.title') }}</strong><small>{{ t('prediction.modelQuality.subtitle') }}</small></div>
      <el-tag size="small" :type="reliable ? 'success' : 'warning'">{{ reliable ? t('prediction.modelQuality.reliable') : t('prediction.modelQuality.caution') }}</el-tag>
    </div>
    <div class="quality-metric-grid">
      <div v-if="value('model_accuracy') != null"><span>{{ t('prediction.modelQuality.accuracy') }}</span><strong>{{ percent(value('model_accuracy')) }}</strong></div>
      <div v-if="value('model_balanced_accuracy') != null"><span>{{ t('prediction.modelQuality.balancedAccuracy') }}</span><strong>{{ percent(value('model_balanced_accuracy')) }}</strong></div>
      <div v-if="value('model_log_loss') != null"><span>{{ t('prediction.modelQuality.logLoss') }}</span><strong>{{ number(value('model_log_loss')) }}</strong></div>
      <div v-if="value('model_baseline_log_loss') != null"><span>{{ t('prediction.modelQuality.baselineLogLoss') }}</span><strong>{{ number(value('model_baseline_log_loss')) }}</strong></div>
      <div v-if="value('model_test_size') != null"><span>{{ t('prediction.modelQuality.testSize') }}</span><strong>{{ value('model_test_size') }}</strong></div>
      <div v-if="quality.model_class_recall?.DRAW != null"><span>{{ t('prediction.modelQuality.drawRecall') }}</span><strong>{{ percent(quality.model_class_recall.DRAW) }}</strong></div>
    </div>
    <div v-if="quality.model_quality_gate" class="quality-gate-note" :class="quality.model_quality_gate.eligible ? 'gate-pass' : 'gate-hold'">
      <strong>{{ quality.model_quality_gate.eligible ? t('prediction.modelQuality.gatePassed') : t('prediction.modelQuality.gateFailed') }}</strong>
      <span>{{ quality.model_quality_gate.message || t('prediction.modelQuality.gateFallback') }}</span>
    </div>
    <small v-if="scopeText" class="quality-scope-warning">{{ scopeText }}</small>
    <small v-if="quality.model_scope?.warning" class="quality-scope-warning">{{ quality.model_scope.warning }}</small>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { useI18n } from '../../i18n'
const props = defineProps({
  quality: { type: Object, default: () => ({}) },
  visible: { type: Boolean, default: false },
  reliable: { type: Boolean, default: false }
})
const { t } = useI18n()
const value = key => {
  const number = Number(props.quality?.[key])
  return Number.isFinite(number) ? number : null
}
const percent = value => `${(Number(value) * 100).toFixed(1)}%`
const number = value => Number(value).toFixed(3)
const scopeText = computed(() => {
  const competitions = props.quality?.model_scope?.competitions
  return Array.isArray(competitions) && competitions.length
    ? t('prediction.modelQuality.scope', { competitions: competitions.join(t('prediction.common.listSeparator')) })
    : ''
})
</script>

<style scoped>
.model-quality-panel { margin-top:20px; padding:16px 18px; border:1px solid var(--ff-border); border-radius:8px; background:var(--ff-bg-alt); }
.quality-panel-head { display:flex; align-items:flex-start; justify-content:space-between; gap:12px; }
.quality-panel-head > div { display:flex; flex-direction:column; gap:4px; }
.quality-panel-head strong { color:var(--ff-text-strong); font-size:14px; }
.quality-panel-head small,.quality-scope-warning { color:var(--ff-text-muted); font-size:11px; line-height:1.6; }
.quality-scope-warning { display:block; margin-top:10px; }
.quality-gate-note { display:flex; flex-direction:column; gap:3px; margin-top:12px; padding:10px 12px; border-radius:6px; font-size:11px; line-height:1.5; }
.quality-gate-note span { color:var(--ff-text-muted); }
.gate-pass { background:rgba(15,107,77,.08); color:var(--ff-primary); }
.gate-hold { background:rgba(178,122,24,.10); color:#8a5d10; }
.quality-metric-grid { display:grid; grid-template-columns:repeat(3,minmax(0,1fr)); gap:10px; margin-top:14px; }
.quality-metric-grid > div { padding:10px 12px; border-left:2px solid var(--ff-primary); background:var(--ff-surface); }
.quality-metric-grid span { display:block; color:var(--ff-text-muted); font-size:11px; }
.quality-metric-grid strong { display:block; margin-top:5px; color:var(--ff-text-strong); font-family:var(--ff-mono); font-size:16px; }
@media (max-width:700px) { .quality-metric-grid { grid-template-columns:repeat(2,minmax(0,1fr)); } .quality-panel-head { flex-direction:column; } }
</style>
