<template>
  <div class="overview-charts">
    <section class="chart-card chart-card--wide hero-card">
      <div class="hero-overlay"></div>
      <div class="hero-content">
        <div>
          <div class="hero-kicker">Football Admin · Overview</div>
          <div class="hero-title">运营态势总览</div>
          <div class="hero-desc">从资讯生产、比赛维护到审计行为，全局掌握后台经营状态。</div>
        </div>
        <div class="hero-pills">
          <el-tag effect="dark" type="primary">SaaS Ready</el-tag>
          <el-tag effect="dark" type="success">Live Data</el-tag>
        </div>
      </div>
    </section>

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
import * as echarts from 'echarts'

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
  xAxis: { type: 'category', data: props.xAxis, boundaryGap: false, axisLine: { lineStyle: { color: '#cbd5e1' } } },
  yAxis: { type: 'value', axisLine: { show: false }, splitLine: { lineStyle: { color: '#e2e8f0' } } },
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
    splitLine: { lineStyle: { color: ['#e2e8f0'] } },
    splitArea: { areaStyle: { color: ['rgba(37,99,235,0.03)', 'rgba(37,99,235,0.07)'] } },
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

onMounted(() => {
  newsChart = echarts.init(newsChartEl.value)
  logChart = echarts.init(logChartEl.value)
  statusChart = echarts.init(statusChartEl.value)
  radarChart = echarts.init(radarChartEl.value)
  newsChart.setOption(buildLineOption(props.newsSeries, '#2563eb'))
  logChart.setOption(buildLineOption(props.logSeries, '#f59e0b'))
  statusChart.setOption(buildPieOption())
  radarChart.setOption(buildRadarOption())
  window.addEventListener('resize', resizeCharts)
})

watch(() => [props.newsSeries, props.logSeries, props.xAxis, props.statusSeries, props.radarData], () => {
  newsChart?.setOption(buildLineOption(props.newsSeries, '#2563eb'))
  logChart?.setOption(buildLineOption(props.logSeries, '#f59e0b'))
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
  background: linear-gradient(180deg, #ffffff 0%, #fbfdff 100%);
  border: 1px solid rgba(226, 232, 240, 0.9);
  border-radius: 22px;
  box-shadow: 0 18px 40px rgba(15, 23, 42, 0.06);
  padding: 18px;
  transition: transform .24s ease, box-shadow .24s ease, border-color .24s ease;
}

.chart-card:hover {
  transform: translateY(-4px);
  box-shadow: 0 22px 50px rgba(15, 23, 42, 0.12);
  border-color: rgba(37, 99, 235, 0.18);
}

.chart-card--wide {
  grid-column: 1 / -1;
}

.hero-card {
  min-height: 190px;
  background: linear-gradient(135deg, #0f172a 0%, #1d4ed8 48%, #0ea5e9 100%);
  color: #fff;
  border: none;
}

.hero-overlay {
  position: absolute;
  inset: 0;
  background: radial-gradient(circle at top right, rgba(255,255,255,0.18), transparent 38%), radial-gradient(circle at bottom left, rgba(255,255,255,0.10), transparent 34%);
  pointer-events: none;
}

.hero-content {
  position: relative;
  z-index: 1;
  display: flex;
  justify-content: space-between;
  gap: 20px;
  align-items: flex-end;
  min-height: 154px;
}

.hero-kicker {
  font-size: 12px;
  letter-spacing: .18em;
  text-transform: uppercase;
  opacity: .82;
  margin-bottom: 10px;
}

.hero-title {
  font-size: 30px;
  font-weight: 900;
  line-height: 1.05;
}

.hero-desc {
  margin-top: 12px;
  max-width: 560px;
  color: rgba(255, 255, 255, 0.84);
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
  color: #0f172a;
}

.chart-subtitle {
  margin-top: 4px;
  font-size: 12px;
  color: #64748b;
}

.chart-box {
  width: 100%;
  height: 300px;
}

.chart-box--radar {
  height: 340px;
}

@media (max-width: 1200px) {
  .overview-charts { grid-template-columns: 1fr; }
}
</style>
