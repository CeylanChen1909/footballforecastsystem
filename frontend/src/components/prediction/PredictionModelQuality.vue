<template>
  <div v-if="visible" class="model-quality-panel" aria-label="模型质量说明">
    <div class="quality-panel-head">
      <div><strong>模型可靠性</strong><small>基于时间外测试集，不代表单场结果保证</small></div>
      <el-tag size="small" :type="reliable ? 'success' : 'warning'">{{ reliable ? '测试表现较稳健' : '建议谨慎参考' }}</el-tag>
    </div>
    <div class="quality-metric-grid">
      <div v-if="value('model_accuracy') != null"><span>时间外准确率</span><strong>{{ percent(value('model_accuracy')) }}</strong></div>
      <div v-if="value('model_balanced_accuracy') != null"><span>综合类别准确率</span><strong>{{ percent(value('model_balanced_accuracy')) }}</strong></div>
      <div v-if="value('model_log_loss') != null"><span>概率误差 Log loss</span><strong>{{ number(value('model_log_loss')) }}</strong></div>
      <div v-if="value('model_baseline_log_loss') != null"><span>基线概率误差</span><strong>{{ number(value('model_baseline_log_loss')) }}</strong></div>
      <div v-if="value('model_test_size') != null"><span>时间外测试场次</span><strong>{{ value('model_test_size') }}</strong></div>
      <div v-if="quality.model_class_recall?.DRAW != null"><span>平局识别率</span><strong>{{ percent(quality.model_class_recall.DRAW) }}</strong></div>
    </div>
    <div v-if="quality.model_quality_gate" class="quality-gate-note" :class="quality.model_quality_gate.eligible ? 'gate-pass' : 'gate-hold'">
      <strong>{{ quality.model_quality_gate.eligible ? '已通过生产门槛' : '当前模型未通过生产门槛' }}</strong>
      <span>{{ quality.model_quality_gate.message || '模型结果仅作为实验性参考' }}</span>
    </div>
    <small v-if="scopeText" class="quality-scope-warning">{{ scopeText }}</small>
    <small v-if="quality.model_scope?.warning" class="quality-scope-warning">{{ quality.model_scope.warning }}</small>
  </div>
</template>

<script setup>
import { computed } from 'vue'
const props = defineProps({
  quality: { type: Object, default: () => ({}) },
  visible: { type: Boolean, default: false },
  reliable: { type: Boolean, default: false }
})
const value = key => {
  const number = Number(props.quality?.[key])
  return Number.isFinite(number) ? number : null
}
const percent = value => `${(Number(value) * 100).toFixed(1)}%`
const number = value => Number(value).toFixed(3)
const scopeText = computed(() => {
  const competitions = props.quality?.model_scope?.competitions
  return Array.isArray(competitions) && competitions.length ? `当前模型覆盖：${competitions.join('、')}` : ''
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
