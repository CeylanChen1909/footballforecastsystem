<template>
  <div class="layout">
    <el-container>
      <el-header class="header">
        <div class="header-left">
          <el-icon :size="28" color="#409EFF"><Football /></el-icon>
          <span class="title">ChenFootball</span>
        </div>
        <div class="header-right">
          <el-button text @click="$router.push('/matches')">
            <el-icon><ArrowLeft /></el-icon> 返回
          </el-button>
          <el-dropdown @command="handleCommand">
            <el-avatar :size="36" style="cursor:pointer;background:#409EFF">
              {{ userStore.username?.[0]?.toUpperCase() }}
            </el-avatar>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="matches"><el-icon><Calendar /></el-icon> 比赛列表</el-dropdown-item>
                <el-dropdown-item command="profile"><el-icon><User /></el-icon> 个人中心</el-dropdown-item>
                <el-dropdown-item command="history"><el-icon><Histogram /></el-icon> 预测历史</el-dropdown-item>
                <el-dropdown-item divided command="logout"><el-icon><SwitchButton /></el-icon> 退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </el-header>

      <el-main class="main-content">
        <div class="prediction-page">
          <el-alert
            v-if="predictionResult"
            :title="`预测已生成：${resultLabel}`"
            type="success"
            show-icon
            :closable="false"
            class="result-banner"
          />
          <!-- 比赛信息卡片 -->
          <el-card class="match-info-card" shadow="hover" v-loading="loadingMatch">
            <template #header>
              <div class="card-header">
                <span>{{ fixtureData?.league?.name || '比赛详情' }}</span>
                <div class="header-actions">
                  <el-tag :type="statusType" size="small">{{ fixtureData?.fixture?.status?.long || '未知' }}</el-tag>
                  <el-button size="small" text @click="showH2H">
                    <el-icon><DataLine /></el-icon> 交锋记录
                  </el-button>
                </div>
              </div>
            </template>
            <div class="match-detail">
              <div class="team-block" @click="goTeamDetail(homeId)">
                <img v-if="fixtureData?.teams?.home?.logo" :src="fixtureData.teams.home.logo" class="big-logo" />
                <div v-else class="logo-placeholder">主</div>
                <div class="team-name-lg">{{ fixtureData?.teams?.home?.name || '主队' }}</div>
              </div>
              <div class="vs-block">
                <div v-if="fixtureData?.goals" class="final-score">
                  {{ fixtureData.goals.home }} - {{ fixtureData.goals.away }}
                </div>
                <div class="vs-label">VS</div>
                <div class="match-date">{{ formatDate(fixtureData?.fixture?.date) }}</div>
                <div class="match-venue" v-if="fixtureData?.fixture?.venue?.name">
                  {{ fixtureData.fixture.venue.name }}
                </div>
              </div>
              <div class="team-block" @click="goTeamDetail(awayId)">
                <img v-if="fixtureData?.teams?.away?.logo" :src="fixtureData.teams.away.logo" class="big-logo" />
                <div v-else class="logo-placeholder">客</div>
                <div class="team-name-lg">{{ fixtureData?.teams?.away?.name || '客队' }}</div>
              </div>
            </div>
          </el-card>

          <!-- 预测结果卡片 -->
          <el-card v-if="predictionResult" class="result-card" shadow="never">
            <template #header>
              <div class="card-header">
                <span>正式 XGBoost 预测结果</span>
                <div class="header-actions">
                  <el-tag type="success" size="small">{{ predictionResult.modelVersion }}</el-tag>
                  <el-tag v-if="deepseekAnalysisState === 'ready'" type="info" size="small">DeepSeek 已补充</el-tag>
                  <el-tag v-else-if="deepseekAnalysisState === 'loading'" type="warning" size="small">DeepSeek 分析中</el-tag>
                </div>
              </div>
            </template>

            <div class="prob-section">
              <div class="prob-header">
                <span>预测概率分布</span>
                <span class="prob-hint">来自正式预测服务</span>
              </div>
              <div class="prob-bars">
                <div class="prob-item">
                  <div class="prob-label">
                    <span class="label-text">{{ fixtureData?.teams?.home?.name }} 胜</span>
                    <el-tag size="small" type="primary">主队</el-tag>
                  </div>
                  <div class="prob-bar-wrapper">
                    <el-progress :percentage="Math.round(displayProb(predictionResult.homeWinProb, predictionResult.homeWinProb) * 100)" :color="homeColor" :stroke-width="16" :show-text="false" />
                  </div>
                  <div class="prob-value">{{ (displayProb(predictionResult.homeWinProb, predictionResult.homeWinProb) * 100).toFixed(1) }}%</div>
                </div>
                <div class="prob-item">
                  <div class="prob-label">
                    <span class="label-text">平局</span>
                  </div>
                  <div class="prob-bar-wrapper">
                    <el-progress :percentage="Math.round(displayProb(predictionResult.drawProb, predictionResult.drawProb) * 100)" :color="drawColor" :stroke-width="16" :show-text="false" />
                  </div>
                  <div class="prob-value">{{ (displayProb(predictionResult.drawProb, predictionResult.drawProb) * 100).toFixed(1) }}%</div>
                </div>
                <div class="prob-item">
                  <div class="prob-label">
                    <span class="label-text">{{ fixtureData?.teams?.away?.name }} 胜</span>
                    <el-tag size="small" type="warning">客队</el-tag>
                  </div>
                  <div class="prob-bar-wrapper">
                    <el-progress :percentage="Math.round(displayProb(predictionResult.awayWinProb, predictionResult.awayWinProb) * 100)" :color="awayColor" :stroke-width="16" :show-text="false" />
                  </div>
                  <div class="prob-value">{{ (displayProb(predictionResult.awayWinProb, predictionResult.awayWinProb) * 100).toFixed(1) }}%</div>
                </div>
              </div>
            </div>

            <div class="result-badge" :class="resultClass">
              <el-icon :size="32"><CircleCheck /></el-icon>
              <span>{{ resultLabel }}</span>
            </div>

            <div class="explanation">
              <div class="explanation-header">
                <el-icon><ChatLineSquare /></el-icon>
                <span>预测分析</span>
              </div>
              <p>{{ predictionResult.explanation }}</p>
            </div>

            <div class="ai-analysis-card">
              <div class="explanation-header">
                <el-icon><MagicStick /></el-icon>
                <span>DeepSeek 文本补充分析</span>
              </div>
              <div v-if="deepseekAnalysisState === 'loading'" class="ai-analysis-loading">
                <el-skeleton :rows="4" animated />
              </div>
              <div v-else-if="deepseekAnalysisState === 'error'" class="ai-analysis-error">
                {{ deepseekAnalysisError || 'DeepSeek 分析暂不可用，已保留本地预测结果。' }}
              </div>
              <div v-else-if="deepseekAnalysisText" class="ai-analysis-content">
                <p>{{ deepseekAnalysisText }}</p>
              </div>
              <div v-else class="ai-analysis-empty">
                暂无补充分析，点击重新预测后系统会自动尝试生成。
              </div>
            </div>

            <div class="feature-contrib" v-if="featureContrib">
              <div class="contrib-header">
                <el-icon><TrendCharts /></el-icon>
                <span>参考指标</span>
              </div>
              <div class="contrib-list">
                <div v-for="item in featureContrib" :key="item.feature + item.label" class="contrib-item">
                  <div class="contrib-topline">
                    <div class="contrib-name-wrap">
                      <span class="contrib-name">{{ item.label }}</span>
                      <span class="contrib-feature">{{ item.feature }}</span>
                    </div>
                    <span class="contrib-value">{{ item.valueText }}</span>
                  </div>
                  <div class="contrib-detail">{{ item.detail }}</div>
                  <div class="contrib-bar">
                    <div class="contrib-fill" :style="{ width: item.percent + '%' }"></div>
                  </div>
                </div>
              </div>
            </div>
          </el-card>

          <!-- 历史交锋弹窗 -->
          <el-dialog v-model="h2hVisible" title="历史交锋记录" width="650px">
            <div v-if="h2hLoading" class="loading-state">
              <el-skeleton :rows="5" animated />
            </div>
            <div v-else-if="h2hData" class="h2h-content">
              <div class="h2h-summary">
                <div class="summary-team home">
                  <img v-if="fixtureData?.teams?.home?.logo" :src="fixtureData.teams.home.logo" class="summary-logo" />
                  <span>{{ fixtureData?.teams?.home?.name }}</span>
                </div>
                <div class="summary-stats">
                  <div class="stat-box">
                    <span class="stat-num">{{ h2hData.summary?.homeWins || 0 }}</span>
                    <span class="stat-desc">主队胜</span>
                  </div>
                  <div class="stat-box">
                    <span class="stat-num">{{ h2hData.summary?.draws || 0 }}</span>
                    <span class="stat-desc">平局</span>
                  </div>
                  <div class="stat-box">
                    <span class="stat-num">{{ h2hData.summary?.awayWins || 0 }}</span>
                    <span class="stat-desc">客队胜</span>
                  </div>
                </div>
                <div class="summary-team away">
                  <img v-if="fixtureData?.teams?.away?.logo" :src="fixtureData.teams.away.logo" class="summary-logo" />
                  <span>{{ fixtureData?.teams?.away?.name }}</span>
                </div>
              </div>
              <div class="h2h-matches">
                <div v-for="(match, idx) in h2hData.recentMatches" :key="idx" class="h2h-match">
                  <span class="h2h-date">{{ match.date }}</span>
                  <span class="h2h-teams">{{ match.homeTeam }} vs {{ match.awayTeam }}</span>
                  <span class="h2h-score">{{ match.homeScore }} - {{ match.awayScore }}</span>
                </div>
                <div v-if="!h2hData.recentMatches?.length" class="h2h-empty">
                  暂无历史交锋记录
                </div>
              </div>
            </div>
          </el-dialog>

          <!-- 预测按钮 / 加载状态 -->
          <div class="action-area">
            <el-button v-if="!predictionResult" type="primary" size="large" :loading="predicting"
              class="predict-btn" @click="doPredict">
              <el-icon><TrendCharts /></el-icon>
              {{ predicting ? '分析中...' : '开始 XGBoost 预测' }}
            </el-button>
            <el-button v-else type="primary" size="large" @click="doPredict" :loading="predicting">
              <el-icon><RefreshRight /></el-icon> 重新预测
            </el-button>
          </div>

        </div>
      </el-main>
    </el-container>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useUserStore } from '../stores/user'
import { crawlerApi, predictionApi } from '../api'
import { ElMessage } from 'element-plus'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const fixtureId = parseInt(route.params.fixtureId)
const homeId = parseInt(route.query.homeId || route.query.home || 0)
const awayId = parseInt(route.query.awayId || route.query.away || 0)

// 从 URL 参数获取球队名称，如果没有则使用默认值
const homeName = decodeURIComponent(route.query.homeName || route.query.home || '主队')
const awayName = decodeURIComponent(route.query.awayName || route.query.away || '客队')

const fixtureData = ref({
  teams: {
    home: { id: homeId, name: homeName, logo: decodeURIComponent(route.query.homeLogo || '') },
    away: { id: awayId, name: awayName, logo: decodeURIComponent(route.query.awayLogo || '') }
  }
})
const predictionResult = ref(null)
const predicting = ref(false)
const loading = ref(false)

const deepseekAnalysisText = ref('')
const deepseekAnalysisState = ref('idle')
const deepseekAnalysisError = ref('')

// 历史交锋
const h2hVisible = ref(false)
const h2hLoading = ref(false)
const h2hData = ref(null)

const homeColor = '#409EFF'
const drawColor = '#909399'
const awayColor = '#E6A23C'

const statusType = computed(() => {
  const s = fixtureData.value?.fixture?.status?.short
  if (s === 'FT') return 'success'
  if (s === 'LIVE' || s === '1H' || s === '2H') return 'danger'
  return 'info'
})

const resultClass = computed(() => {
  const r = predictionResult.value?.resultLabel
  if (r === 'HOME_WIN') return 'result-home'
  if (r === 'AWAY_WIN') return 'result-away'
  return 'result-draw'
})


const resultLabel = computed(() => {
  const r = predictionResult.value?.resultLabel
  if (r === 'HOME_WIN') return `预测：${fixtureData.value?.teams?.home?.name} 获胜`
  if (r === 'AWAY_WIN') return `预测：${fixtureData.value?.teams?.away?.name} 获胜`
  return '预测：双方势均力敌，可能平局'
})

const normalizeProb = (v) => {
  const n = Number(v)
  if (!Number.isFinite(n)) return null
  return n > 1 ? n / 100 : n
}
const displayProb = (primary, fallback) => {
  const p = normalizeProb(primary)
  if (p !== null) return p
  const f = normalizeProb(fallback)
  return f !== null ? f : 0
}

const featureLabelMap = {
  elo_diff: 'ELO差值',
  win_rate_diff: '近期胜率差',
  goal_diff: '场均进球差',
  h2h_balance: '历史交锋平衡',
  rest_days_diff: '休息天数差',
  home_elo: '主队ELO',
  away_elo: '客队ELO',
  home_win_rate: '主队胜率',
  away_win_rate: '客队胜率',
  home_avg_goals: '主队场均进球',
  away_avg_goals: '客队场均进球',
  home_avg_loss: '主队场均失球',
  away_avg_loss: '客队场均失球',
  home_days_rest: '主队休息天数',
  away_days_rest: '客队休息天数',
  h2h_home_wins: '历史交锋主胜',
  h2h_draws: '历史交锋平局',
  h2h_away_wins: '历史交锋客胜',
  homeWinProb: '主胜概率',
  drawProb: '平局概率',
  awayWinProb: '客胜概率',
  modelVersion: '模型版本'
}

const parseFeatureString = (raw) => {
  if (!raw || typeof raw !== 'string') return null
  const text = raw.trim()
  const match = text.match(/^\{(.+)\}$/)
  if (!match) return null
  const body = match[1]
  const result = {}
  const pairs = body.split(/,\s*(?=[a-zA-Z_]+=)/)
  for (const pair of pairs) {
    const idx = pair.indexOf('=')
    if (idx === -1) continue
    const key = pair.slice(0, idx).trim()
    const value = pair.slice(idx + 1).trim()
    result[key] = value
  }
  return Object.keys(result).length ? result : null
}

const featureContrib = computed(() => {
  if (!predictionResult.value) return null
  const topFeatures = Array.isArray(predictionResult.value.topFeatures) ? predictionResult.value.topFeatures : []

  const parsedTopFeatures = topFeatures.map((item, index) => {
    const parsed = typeof item === 'string' ? parseFeatureString(item) : null
    if (parsed) {
      const feature = parsed.feature || `feature_${index + 1}`
      const label = parsed.label || featureLabelMap[feature] || feature
      const importance = Number(parsed.importance ?? parsed.value ?? 0)
      const valueText = parsed.value !== undefined ? `${parsed.value}${parsed.unit ? ` ${parsed.unit}` : ''}` : `${importance || Math.max(0, 100 - index * 18)}%`
      return {
        feature,
        label,
        detail: `特征名：${feature} · 重要性：${parsed.importance ?? '-'} · 单位：${parsed.unit ?? '-'}${parsed.value !== undefined ? ` · 原始值：${parsed.value}` : ''}`,
        valueText,
        percent: Math.max(8, Math.min(100, importance > 0 ? Math.round(Math.min(importance / 5, 100)) : Math.max(0, 100 - index * 18))),
      }
    }

    if (typeof item === 'string') {
      return {
        feature: item,
        label: featureLabelMap[item] || item,
        detail: item,
        valueText: `${Math.max(0, 100 - index * 18)}%`,
        percent: Math.max(8, 100 - index * 18),
      }
    }

    const feature = item?.feature || item?.name || item?.label || `feature_${index + 1}`
    const label = item?.label || featureLabelMap[feature] || feature
    const importance = Number(item?.importance ?? item?.value ?? 0)
    const unit = item?.unit ? String(item.unit) : ''
    const detailParts = [
      item?.feature ? `特征名：${item.feature}` : null,
      item?.importance !== undefined ? `重要性：${item.importance}` : null,
      item?.value !== undefined ? `数值：${item.value}` : null,
      unit ? `单位：${unit}` : null
    ].filter(Boolean)
    const percent = Math.max(8, Math.min(100, importance > 0 ? Math.round(Math.min(importance / 5, 100)) : Math.max(0, 100 - index * 18)))
    return {
      feature,
      label,
      detail: detailParts.join(' · ') || '暂无更多信息',
      valueText: item?.value !== undefined ? String(item.value) : `${percent}%`,
      percent,
    }
  })

  if (parsedTopFeatures.length) {
    return parsedTopFeatures
  }

  const homeProb = displayProb(predictionResult.value.homeWinProb, predictionResult.value.homeWinProb)
  const awayProb = displayProb(predictionResult.value.awayWinProb, predictionResult.value.awayWinProb)
  const drawProb = displayProb(predictionResult.value.drawProb, predictionResult.value.drawProb)

  return [
    { feature: 'homeWinProb', label: '主胜概率', detail: '预测输出概率', valueText: `${(homeProb * 100).toFixed(1)}%`, percent: Math.round(homeProb * 100) },
    { feature: 'drawProb', label: '平局概率', detail: '预测输出概率', valueText: `${(drawProb * 100).toFixed(1)}%`, percent: Math.round(drawProb * 100) },
    { feature: 'awayWinProb', label: '客胜概率', detail: '预测输出概率', valueText: `${(awayProb * 100).toFixed(1)}%`, percent: Math.round(awayProb * 100) },
  ]
})

const loadDeepSeekAnalysis = async () => {
  if (!predictionResult.value) return
  deepseekAnalysisState.value = 'loading'
  deepseekAnalysisError.value = ''
  deepseekAnalysisText.value = ''
  try {
    const res = await crawlerApi.getPredictionAnalysis({
      fixtureId,
      homeTeam: fixtureData.value?.teams?.home?.name || homeName,
      awayTeam: fixtureData.value?.teams?.away?.name || awayName,
      leagueName: fixtureData.value?.league?.name || route.query.leagueName || '',
      homeWinProb: predictionResult.value?.homeWinProb,
      drawProb: predictionResult.value?.drawProb,
      awayWinProb: predictionResult.value?.awayWinProb,
      resultLabel: predictionResult.value?.resultLabel,
      explanation: predictionResult.value?.explanation
    })
    const rawText = res?.content || res?.summary || res?.data?.content || res?.data?.summary
    if (rawText && String(rawText).trim()) {
      deepseekAnalysisText.value = String(rawText).trim()
      deepseekAnalysisState.value = 'ready'
      return
    }
    deepseekAnalysisState.value = 'error'
    deepseekAnalysisError.value = '后端已返回分析接口，但未拿到可展示文本。'
  } catch (e) {
    deepseekAnalysisState.value = 'error'
    deepseekAnalysisError.value = 'DeepSeek 文本补充分析暂时不可用，已保留预测结果。'
  }
}

const loadMatch = async () => {
  loading.value = true
  try {
    const homeTeam = fixtureData.value?.teams?.home || {}
    const awayTeam = fixtureData.value?.teams?.away || {}
    const queryLeague = route.query.leagueName || ''
    fixtureData.value = {
      ...fixtureData.value,
      fixture: {
        id: fixtureId,
        status: { short: 'NS', long: '未开赛' },
        venue: { name: '' },
        date: route.query.matchTime || ''
      },
      league: { id: route.query.leagueId || '', name: queryLeague, round: route.query.round || '' },
      teams: {
        home: { ...homeTeam, id: homeId || homeTeam.id, name: homeTeam.name || homeName, logo: homeTeam.logo || decodeURIComponent(route.query.homeLogo || '') },
        away: { ...awayTeam, id: awayId || awayTeam.id, name: awayTeam.name || awayName, logo: awayTeam.logo || decodeURIComponent(route.query.awayLogo || '') }
      },
      goals: { home: null, away: null }
    }
  } finally {
    loading.value = false
  }
}

const doPredict = async () => {
  predicting.value = true
  try {
    const homeTeam = fixtureData.value?.teams?.home || {}
    const awayTeam = fixtureData.value?.teams?.away || {}
    const homeTeamName = homeTeam.name || homeName
    const awayTeamName = awayTeam.name || awayName
    const leagueName = fixtureData.value?.league?.name || route.query.leagueName || ''
    const leagueId = route.query.leagueId ? Number(route.query.leagueId) : null

    const res = await predictionApi.saveMatchResult({
      fixtureId,
      homeTeamId: homeTeam?.id || homeId || null,
      awayTeamId: awayTeam?.id || awayId || null,
      homeTeamName,
      awayTeamName,
      leagueName,
      leagueId,
      userId: userStore.userId || null
    })

    predictionResult.value = res || null
    if (!predictionResult.value) {
      predictionResult.value = {
        fixtureId,
        modelVersion: 'baseline-elo-v1',
        homeWinProb: 0.34,
        drawProb: 0.33,
        awayWinProb: 0.33,
        resultLabel: 'DRAW',
        explanation: '当前比赛缺少足够特征，已使用兜底预测。'
      }
    }

    await loadDeepSeekAnalysis()

    console.debug('[Prediction payload]', { fixtureId, homeTeamName, awayTeamName, leagueName, response: predictionResult.value })
    ElMessage.success('已调用正式预测模型')
    router.push({
      path: `/prediction/${fixtureId}/detail`,
      query: {
        homeId: homeTeam?.id || homeId || '',
        awayId: awayTeam?.id || awayId || '',
        homeName: homeTeamName,
        awayName: awayTeamName,
        leagueName,
        modelVersion: predictionResult.value?.modelVersion || '',
        homeWinProb: predictionResult.value?.homeWinProb ?? '',
        drawProb: predictionResult.value?.drawProb ?? '',
        awayWinProb: predictionResult.value?.awayWinProb ?? '',
        resultLabel: predictionResult.value?.resultLabel || '',
        explanation: predictionResult.value?.explanation || ''
      }
    })
  } catch (e) {
    predictionResult.value = {
      fixtureId,
      modelVersion: 'baseline-elo-v1',
      homeWinProb: 0.34,
      drawProb: 0.33,
      awayWinProb: 0.33,
      resultLabel: 'DRAW',
      explanation: '预测服务暂不可用，已使用兜底预测结果。'
    }
    deepseekAnalysisState.value = 'error'
    deepseekAnalysisError.value = '预测服务暂不可用，DeepSeek 补充分析未生成。'
    console.warn('[Prediction failed]', e)
    ElMessage.warning('预测服务不可用，已展示兜底预测结果')
  } finally {
    predicting.value = false
  }
}

const showH2H = async () => {
  const homeTeamName = fixtureData.value?.teams?.home?.name || homeName
  const awayTeamName = fixtureData.value?.teams?.away?.name || awayName
  if (!homeTeamName || !awayTeamName) return
  h2hVisible.value = true
  h2hLoading.value = true
  
  try {
    const res = await crawlerApi.getProxyH2H(homeTeamName, awayTeamName, 10)
    h2hData.value = res?.data || res
  } catch (e) {
    h2hData.value = {
      recentMatches: [],
      summary: { homeWins: 0, draws: 0, awayWins: 0 },
      homeTeamName,
      awayTeamName,
      source: 'fallback'
    }
  } finally {
    h2hLoading.value = false
  }
}

const goTeamDetail = (teamId) => {
  if (teamId) {
    router.push(`/team/${teamId}`)
  }
}

const formatDate = (dateStr) => {
  if (!dateStr) return ''
  return new Date(dateStr).toLocaleString('zh-CN')
}

const handleCommand = (cmd) => {
  if (cmd === 'logout') { userStore.logout(); router.push('/login') }
  else if (cmd === 'profile') router.push('/profile')
  else if (cmd === 'history') router.push('/profile?tab=history')
  else if (cmd === 'matches') router.push('/matches')
}

onMounted(loadMatch)
</script>

<style scoped>
.layout { min-height: 100vh; background: #f0f2f5; }
.header {
  background: #fff; box-shadow: 0 2px 8px rgba(0,0,0,0.08);
  display: flex; align-items: center; justify-content: space-between;
  padding: 0 24px; height: 60px; position: sticky; top: 0; z-index: 100;
}
.header-left { display: flex; align-items: center; gap: 10px; }
.title { font-size: 18px; font-weight: 700; color: #1a1a2e; }
.header-right { display: flex; align-items: center; gap: 12px; }
.main-content { padding: 24px; max-width: 800px; margin: 0 auto; }
.prediction-page { display: flex; flex-direction: column; gap: 16px; }
.result-banner { border-radius: 12px; }

.card-header { display: flex; justify-content: space-between; align-items: center; }
.header-actions { display: flex; gap: 8px; align-items: center; }

.match-detail { display: flex; align-items: center; justify-content: space-around; padding: 20px 0; }
.team-block {
  display: flex; flex-direction: column; align-items: center; gap: 10px;
  cursor: pointer; padding: 10px; border-radius: 8px; transition: background 0.2s;
}
.team-block:hover { background: #f5f7fa; }
.big-logo { width: 80px; height: 80px; object-fit: contain; }
.logo-placeholder {
  width: 80px; height: 80px; border-radius: 50%;
  background: #eee; display: flex; align-items: center; justify-content: center;
  font-size: 28px; color: #999;
}
.team-name-lg { font-size: 16px; font-weight: 600; color: #333; text-align: center; max-width: 120px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.vs-block { text-align: center; }
.final-score { font-size: 36px; font-weight: 700; color: #1a1a2e; }
.vs-label { font-size: 20px; color: #999; margin: 6px 0; }
.match-date { font-size: 13px; color: #aaa; }
.match-venue { font-size: 12px; color: #ccc; margin-top: 4px; }

/* 概率展示 */
.prob-section { margin-bottom: 20px; }
.prob-header {
  display: flex; justify-content: space-between; align-items: center;
  margin-bottom: 16px; font-size: 14px; color: #666;
}
.prob-hint { color: #999; font-size: 12px; }
.prob-bars { display: flex; flex-direction: column; gap: 16px; }
.prob-item { display: flex; align-items: center; gap: 12px; }
.prob-label {
  width: 120px; font-weight: 600; font-size: 14px; color: #555;
  display: flex; align-items: center; gap: 6px;
}
.label-text { flex: 1; }
.prob-bar-wrapper { flex: 1; }
.prob-value { width: 60px; text-align: right; font-weight: 700; font-size: 18px; }

.result-summary {
  display: grid;
  grid-template-columns: 1.2fr 1fr;
  gap: 16px;
  padding: 16px;
  border-radius: 14px;
  background: linear-gradient(180deg, #fbfdff, #f7faff);
  border: 1px solid #e8eef7;
  margin-bottom: 16px;
}
.result-main { display: flex; flex-direction: column; justify-content: center; gap: 6px; }
.result-title { font-size: 22px; font-weight: 800; color: #1f2d3d; }
.result-subtitle { font-size: 14px; color: #606266; }
.result-meta { font-size: 12px; color: #909399; }
.result-scoreboard { display: grid; grid-template-columns: repeat(3, 1fr); gap: 10px; }
.score-item { padding: 12px; border-radius: 12px; background: #fff; border: 1px solid #edf1f7; text-align: center; }
.score-item.home { box-shadow: inset 0 0 0 1px rgba(64, 158, 255, 0.08); }
.score-item.draw { box-shadow: inset 0 0 0 1px rgba(144, 147, 153, 0.08); }
.score-item.away { box-shadow: inset 0 0 0 1px rgba(230, 162, 60, 0.08); }
.score-label { display: block; font-size: 12px; color: #909399; margin-bottom: 4px; }
.score-value { font-size: 18px; font-weight: 800; color: #303133; }
.result-badge {
  display: flex; align-items: center; justify-content: center; gap: 12px;
  padding: 20px; border-radius: 12px; font-size: 20px; font-weight: 700; margin-bottom: 20px;
}
.result-home { background: linear-gradient(135deg, #ecf5ff, #d9ecff); color: #409EFF; }
.result-away { background: linear-gradient(135deg, #fdf6ec, #f9e7c4); color: #E6A23C; }
.result-draw { background: linear-gradient(135deg, #f4f4f5, #e3e3e4); color: #909399; }

.explanation {
  background: #f9f9f9; border-radius: 12px; padding: 16px 20px;
  margin-bottom: 16px; border: 1px solid #eee;
}
.explanation-header {
  display: flex; align-items: center; gap: 8px;
  margin-bottom: 10px; font-weight: 600; color: #666;
}
.explanation p { font-size: 14px; color: #333; line-height: 1.8; margin: 0; }

.ai-analysis-card {
  background: linear-gradient(180deg, #ffffff, #f9fbff); border-radius: 12px; padding: 16px 20px;
  border: 1px solid #e6edf7;
}
.ai-analysis-content p,
.ai-analysis-empty,
.ai-analysis-error {
  font-size: 14px;
  color: #333;
  line-height: 1.8;
  margin: 0;
}
.ai-analysis-loading { padding: 8px 0; }
.ai-analysis-empty { color: #909399; }
.ai-analysis-error { color: #f56c6c; }

.feature-contrib {
  background: #fff; border-radius: 12px; padding: 16px 20px;
  border: 1px solid #eee;
}
.contrib-header {
  display: flex; align-items: center; gap: 8px;
  margin-bottom: 16px; font-weight: 600; color: #666;
}
.contrib-list { display: flex; flex-direction: column; gap: 14px; }
.contrib-item {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 14px;
  border: 1px solid #edf1f7;
  border-radius: 12px;
  background: linear-gradient(180deg, #ffffff, #fbfcfe);
}
.contrib-topline {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}
.contrib-name-wrap { display: flex; flex-direction: column; gap: 4px; min-width: 0; }
.contrib-name { font-size: 14px; color: #303133; font-weight: 700; line-height: 1.3; }
.contrib-feature { font-size: 12px; color: #909399; word-break: break-all; font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, 'Courier New', monospace; }
.contrib-detail { font-size: 12px; color: #606266; line-height: 1.6; word-break: break-word; }
.contrib-bar { height: 8px; background: #eef2f7; border-radius: 999px; overflow: hidden; }
.contrib-fill { height: 100%; background: linear-gradient(90deg, #409EFF, #66b1ff); border-radius: 999px; transition: width 0.5s; }
.contrib-value { font-size: 13px; font-weight: 700; color: #409EFF; white-space: nowrap; }

.action-area { text-align: center; }
.predict-btn { width: 260px; height: 52px; font-size: 16px; }
.predict-progress {
  margin-top: 14px;
  padding: 14px 16px;
  background: #fff;
  border: 1px solid #e6e8eb;
  border-radius: 12px;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.04);
}
.progress-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 10px;
  font-size: 13px;
  color: #666;
}

/* 历史交锋弹窗 */
.h2h-summary {
  display: flex; align-items: center; justify-content: space-between;
  padding: 20px; background: #f5f7fa; border-radius: 12px; margin-bottom: 20px;
}
.summary-team {
  display: flex; flex-direction: column; align-items: center; gap: 8px;
}
.summary-team span { font-size: 13px; font-weight: 600; color: #333; max-width: 100px; text-align: center; }
.summary-logo { width: 48px; height: 48px; border-radius: 8px; }
.summary-stats { display: flex; gap: 20px; }
.stat-box { text-align: center; }
.stat-num { display: block; font-size: 28px; font-weight: 700; color: #409EFF; }
.stat-desc { font-size: 12px; color: #999; }
.h2h-matches { display: flex; flex-direction: column; gap: 8px; }
.h2h-match {
  display: flex; align-items: center; gap: 16px;
  padding: 12px 16px; background: #fafafa; border-radius: 8px;
}
.h2h-date { width: 80px; font-size: 12px; color: #999; }
.h2h-teams { flex: 1; font-size: 14px; color: #333; }
.h2h-score { font-size: 16px; font-weight: 700; color: #333; }
.h2h-empty { text-align: center; padding: 40px; color: #999; }
.loading-state { padding: 20px; }
</style>
