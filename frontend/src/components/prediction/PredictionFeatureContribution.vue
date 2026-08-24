<template>
  <div v-if="items?.length" class="feature-contrib">
    <div class="contrib-header"><el-icon><TrendCharts /></el-icon><span>参考指标</span><small>展示影响较大的输入，不等同于因果解释</small></div>
    <div class="contrib-list">
      <div v-for="item in items" :key="item.feature + item.label" class="contrib-item">
        <div class="contrib-topline">
          <div class="contrib-name-wrap"><span class="contrib-name">{{ item.label }}</span><span class="contrib-feature">模型参考输入</span></div>
          <span class="contrib-value">{{ item.valueText }}</span>
        </div>
        <div class="contrib-detail">{{ item.detail }}</div>
        <div class="contrib-bar"><div class="contrib-fill" :style="{ width: item.percent + '%' }"></div></div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { TrendCharts } from '@element-plus/icons-vue'
defineProps({ items: { type: Array, default: () => [] } })
</script>

<style scoped>
.feature-contrib { background:#fff; border-radius:8px; padding:16px 20px; border:1px solid var(--ff-border); box-shadow:var(--ff-shadow-sm); }
.contrib-header { display:flex; align-items:center; gap:8px; margin-bottom:16px; font-weight:700; color:var(--ff-text); }
.contrib-header small { margin-left:auto; color:var(--ff-text-faint); font-size:11px; font-weight:400; }
.contrib-list { display:flex; flex-direction:column; gap:14px; }
.contrib-item { display:flex; flex-direction:column; gap:10px; padding:14px; border:1px solid var(--ff-border); border-radius:8px; background:var(--ff-bg-alt); }
.contrib-topline { display:flex; align-items:flex-start; justify-content:space-between; gap:12px; }
.contrib-name-wrap { display:flex; flex-direction:column; gap:4px; min-width:0; }
.contrib-name { font-size:14px; color:var(--ff-text-strong); font-weight:700; line-height:1.3; }
.contrib-feature { font-size:12px; color:var(--ff-text-muted); word-break:break-all; font-family:var(--ff-mono); }
.contrib-detail { font-size:12px; color:var(--ff-text-muted); line-height:1.6; word-break:break-word; }
.contrib-bar { height:8px; background:var(--ff-bg-alt); border-radius:999px; overflow:hidden; }
.contrib-fill { height:100%; background:var(--ff-primary); border-radius:999px; }
.contrib-value { font-size:13px; font-weight:600; color:var(--ff-primary); white-space:nowrap; font-family:var(--ff-mono); }
@media (max-width:600px) { .contrib-header { align-items:flex-start; flex-wrap:wrap; } .contrib-header small { flex-basis:100%; margin-left:0; } }
</style>
