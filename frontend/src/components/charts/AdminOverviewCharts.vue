<template>
  <div class="overview-charts">
    <section class="chart-card chart-card--wide">
      <div class="chart-head">
        <div>
          <div class="chart-title">近 7 天资讯趋势</div>
          <div class="chart-subtitle">内容生产与发布节奏</div>
        </div>
        <el-tag type="success" effect="plain">News</el-tag>
      </div>
      <div ref="newsChartEl" class="chart-box"></div>
    </section>

    <section class="chart-card">
      <div class="chart-head">
        <div>
          <div class="chart-title">近 7 天审计趋势</div>
          <div class="chart-subtitle">后台操作与系统行为</div>
        </div>
        <el-tag type="warning" effect="plain">Logs</el-tag>
      </div>
      <div ref="logChartEl" class="chart-box"></div>
    </section>

    <section class="chart-card">
      <div class="chart-head">
        <div>
          <div class="chart-title">内容状态分布</div>
          <div class="chart-subtitle">已发布 / 草稿 / 隐藏 / 删除</div>
        </div>
        <el-tag type="primary" effect="plain">News Mix</el-tag>
      </div>
      <div ref="statusChartEl" class="chart-box"></div>
    </section>

    <section class="chart-card chart-card--wide">
      <div class="chart-head">
        <div>
          <div class="chart-title">热点雷达</div>
          <div class="chart-subtitle">热门联赛 / 球队 / 资讯标签 / 内容热度 / 赛事活跃度</div>
        </div>
        <el-tag type="danger" effect="plain">Radar</el-tag>
      </div>
      <div ref="radarChartEl" class="chart-box chart-box--radar"></div>
    </section>
  </div>
</template>

<script setup>
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { init, use } from 'echarts/core'
import { LineChart, PieChart, RadarChart } from 'echarts/charts'
import { GridComponent, LegendComponent, RadarComponent, TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'

use([LineChart, PieChart, RadarChart, GridComponent, TooltipComponent, LegendComponent, RadarComponent, CanvasRenderer])

const props = defineProps({
  newsSeries: { type: Array, default: () => [] },
  logSeries: { type: Array, default: () => [] },
  xAxis: { type: Array, default: () => [] },
  statusSeries: { type: Array, default: () => [] },
  radarData: { type: Array, default: () => [] }
})

const newsChartEl = ref(null)
const logChartEl = ref(null)
const statusChartEl = ref(null)
const radarChartEl = ref(null)
let newsChart = null
let logChart = null
let statusChart = null
let radarChart = null

const buildLineOption = (series, color) => ({
  grid: { left: 34, right: 18, top: 28, bottom: 28 },
  tooltip: { trigger: 'axis' },
  xAxis: { type: 'category', data: props.xAxis, boundaryGap: false, axisLine: { lineStyle: { color: '#c8c6c4' } } },
  yAxis: { type: 'value', axisLine: { show: false }, splitLine: { lineStyle: { color: '#e1e1e1' } } },
  series: [{ data: series, type: 'line', smooth: true, areaStyle: { opacity: 0.18 }, symbol: 'circle', symbolSize: 8, lineStyle: { width: 3, color }, itemStyle: { color } }]
})

const buildPieOption = () => ({
  tooltip: { trigger: 'item' },
  legend: { bottom: 0, left: 'center', icon: 'circle' },
  series: [{
    type: 'pie',
    radius: ['42%', '68%'],
    center: ['50%', '45%'],
    avoidLabelOverlap: true,
    itemStyle: { borderRadius: 12, borderColor: '#fff', borderWidth: 3 },
    label: { formatter: '{b}\n{d}%' },
    data: props.statusSeries
  }]
})

const buildRadarOption = () => ({
  tooltip: {},
  radar: {
    radius: '68%',
    splitNumber: 4,
    axisName: { color: '#475569', fontSize: 12 },
    indicator: [
      { name: '热门联赛', max: 100 },
      { name: '热门球队', max: 100 },
      { name: '热门标签', max: 100 },
      { name: '内容热度', max: 100 },
      { name: '赛事活跃', max: 100 }
    ],
    splitLine: { lineStyle: { color: ['#e1e1e1'] } },
    splitArea: { areaStyle: { color: ['rgba(15,107,77,0.03)', 'rgba(15,107,77,0.07)'] } },
    axisLine: { lineStyle: { color: '#dbe3ef' } }
  },
  series: [{
    type: 'radar',
    symbol: 'circle',
    symbolSize: 6,
    data: [{
      value: props.radarData,
      name: 'Hotspot',
      areaStyle: { opacity: 0.24 }
    }]
  }]
})

const resizeCharts = () => {
  newsChart?.resize()
  logChart?.resize()
  statusChart?.resize()
  radarChart?.resize()
}

onMounted(async () => {
  if (!newsChartEl.value) return
  newsChart = init(newsChartEl.value)
  logChart = init(logChartEl.value)
  statusChart = init(statusChartEl.value)
  radarChart = init(radarChartEl.value)
  newsChart.setOption(buildLineOption(props.newsSeries, '#0f6b4d'))
  logChart.setOption(buildLineOption(props.logSeries, '#ffb900'))
  statusChart.setOption(buildPieOption())
  radarChart.setOption(buildRadarOption())
  window.addEventListener('resize', resizeCharts)
})

watch(() => [props.newsSeries, props.logSeries, props.xAxis, props.statusSeries, props.radarData], () => {
  newsChart?.setOption(buildLineOption(props.newsSeries, '#0f6b4d'))
  logChart?.setOption(buildLineOption(props.logSeries, '#ffb900'))
  statusChart?.setOption(buildPieOption())
  radarChart?.setOption(buildRadarOption())
}, { deep: true })

onBeforeUnmount(() => {
  window.removeEventListener('resize', resizeCharts)
  newsChart?.dispose()
  logChart?.dispose()
  statusChart?.dispose()
  radarChart?.dispose()
})
</script>

<style scoped>
.overview-charts {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px;
}

.chart-card {
  position: relative;
  overflow: hidden;
  background: #ffffff;
  border: 1px solid var(--ff-border);
  border-radius: 8px;
  box-shadow: none;
  padding: 18px;
  transition: border-color .2s ease;
}

.chart-card:hover {
  border-color: rgba(15, 107, 77, 0.30);
}

.chart-card--wide {
  grid-column: 1 / -1;
}

.hero-card {
  min-height: 132px;
  background: var(--ff-surface-soft);
  color: var(--ff-text-strong);
  border: 1px solid var(--ff-border);
}

.hero-overlay {
  position: absolute;
  inset: 0;
  background: none;
  pointer-events: none;
}

.hero-content {
  position: relative;
  z-index: 1;
  display: flex;
  justify-content: space-between;
  gap: 20px;
  align-items: flex-end;
  min-height: 96px;
}

.hero-kicker {
  font-size: 12px;
  letter-spacing: .18em;
  text-transform: uppercase;
  opacity: .82;
  margin-bottom: 10px;
}

.hero-title {
  font-size: 22px;
  font-weight: 700;
  line-height: 1.05;
}

.hero-desc {
  margin-top: 12px;
  max-width: 560px;
  color: var(--ff-text-muted);
  line-height: 1.8;
}

.hero-pills { display:flex; gap:10px; flex-wrap:wrap; }

.chart-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  margin-bottom: 10px;
}

.chart-title {
  font-size: 16px;
  font-weight: 800;
  color: var(--ff-text-strong);
}

.chart-subtitle {
  margin-top: 4px;
  font-size: 12px;
  color: var(--ff-text-muted);
}

.chart-box {
  width: 100%;
  height: 220px;
}

.chart-box--radar {
  height: 250px;
}

@media (max-width: 1200px) {
  .overview-charts { grid-template-columns: 1fr; }
}
</style>
