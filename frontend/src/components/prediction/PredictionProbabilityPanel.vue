<template>
  <section class="prob-section" aria-label="预测概率分布">
    <div class="prob-header">
      <span>预测概率分布</span>
      <span class="prob-hint">{{ warning ? '特征或模型质量不足，仅供参考' : '来自正式预测服务' }}</span>
    </div>
    <div class="prob-bars">
      <div v-for="item in items" :key="item.key" class="prob-item" :class="{ 'is-leading': item.isLeading }" :aria-label="`${item.label} ${item.text}`">
        <div class="prob-label">
          <span class="label-text">{{ item.label }}</span>
          <span v-if="item.tag" class="team-tag" :class="item.tagClass">{{ item.tag }}</span>
          <span v-if="item.isLeading" class="leading-tag">最高概率</span>
        </div>
        <div class="prob-bar-wrapper">
          <el-progress :percentage="item.percent" :color="item.color" :stroke-width="16" :show-text="false" />
        </div>
        <div class="prob-value">{{ item.text }}</div>
      </div>
    </div>
  </section>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  homeName: { type: String, default: '主队' },
  awayName: { type: String, default: '客队' },
  probabilities: { type: Object, default: () => ({ home: 0, draw: 0, away: 0 }) },
  warning: { type: Boolean, default: false },
  homeColor: { type: String, default: '#0f6b4d' },
  drawColor: { type: String, default: '#909399' },
  awayColor: { type: String, default: '#b27a18' }
})

const items = computed(() => [
  { key: 'home', label: `${props.homeName} 胜`, tag: '主队', tagClass: 'home-tag', color: props.homeColor },
  { key: 'draw', label: '平局', color: props.drawColor },
  { key: 'away', label: `${props.awayName} 胜`, tag: '客队', tagClass: 'away-tag', color: props.awayColor }
].map(item => {
  const value = Number(props.probabilities?.[item.key] || 0)
  return { ...item, percent: Math.round(value * 100), text: `${(value * 100).toFixed(1)}%` }
}).map((item, _, all) => ({ ...item, isLeading: item.percent > 0 && item.percent === Math.max(...all.map(entry => entry.percent)) })))
</script>

<style scoped>
.prob-section { margin-bottom:20px; }
.prob-header { display:flex; justify-content:space-between; align-items:center; margin-bottom:16px; font-size:14px; color:var(--ff-text-muted); }
.prob-hint { color:var(--ff-text-muted); font-size:12px; }
.prob-bars { display:grid; grid-template-columns:repeat(3,minmax(0,1fr)); gap:12px; }
.prob-item { display:flex; flex-direction:column; align-items:stretch; gap:10px; padding:14px; border:1px solid var(--ff-border); border-radius:var(--ff-radius-md); background:var(--ff-surface-quiet); transition:border-color .18s ease,background .18s ease,transform .18s ease; }
.prob-item.is-leading { border-color:var(--ff-primary); background:var(--ff-primary-soft); }
.prob-label { display:flex; align-items:center; gap:6px; font-weight:600; font-size:14px; color:var(--ff-text); }
.label-text { flex:1; min-width:0; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
.prob-bar-wrapper { flex:1; }
.prob-value { text-align:right; font-weight:600; font-size:20px; color:var(--ff-primary); font-family:var(--ff-mono); }
.team-tag { display:inline-flex; align-items:center; padding:2px 7px; border-radius:999px; font-size:11px; font-weight:600; }
.home-tag { color:var(--ff-primary); background:var(--ff-primary-soft); }
.away-tag { color:#8b6117; background:#f5ead4; }
.leading-tag { padding:2px 6px; border-radius:999px; color:var(--ff-primary); background:#fff; font-size:10px; font-weight:700; white-space:nowrap; }
@media (max-width:768px) { .prob-bars { grid-template-columns:1fr; } }
</style>
