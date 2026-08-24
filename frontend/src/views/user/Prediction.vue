<template>
  <div class="prediction-page ff-page-shell">
    <AppTopNav
      title="ChenFootball"
      subtitle="比赛预测"
      :brand-icon="Football"
      active-path="/matches"
    />

    <el-main id="app-main" class="main-content" tabindex="-1">
      <div class="prediction-inner">
        <section class="report-heading ff-appear">
          <div class="report-heading-main">
            <span class="ff-kicker">{{ pageKicker }}</span>
            <h1 v-if="fixtureNotFound">未找到这场比赛</h1>
            <h1 v-else>{{ fixtureData?.teams?.home?.name || '主队' }} <em>vs</em> {{ fixtureData?.teams?.away?.name || '客队' }}</h1>
            <p v-if="fixtureNotFound">请返回比赛列表重新选择有效的比赛链接。</p>
            <p v-else>{{ fixtureData?.league?.name || '比赛分析' }} · {{ formatDate(fixtureData?.fixture?.date) || '时间待定' }}</p>
          </div>
          <div class="report-heading-meta">
            <el-tag :type="statusType" size="small">{{ fixtureStatusLabel }}</el-tag>
            <div class="report-confidence"><span>概率分离度</span><strong>{{ confidenceValue }}</strong><small>{{ confidenceLabel }}</small></div>
          </div>
        </section>

        <el-alert v-if="matchLoadError" :title="matchLoadError" type="warning" show-icon :closable="false" class="inline-status" />

        <div v-if="!predictionResult && predictionStatus !== 'LOADING'" class="prediction-state-panel" :class="`state-${String(predictionStatus).toLowerCase()}`">
          <div class="state-icon"><el-icon><TrendCharts /></el-icon></div>
          <div class="state-copy"><strong>{{ predictionStatusTitle }}</strong><span>{{ predictionStatusDescription }}</span></div>
          <el-button v-if="['ERROR', 'FAILED', 'TIMEOUT'].includes(predictionStatus)" size="small" plain @click="doPredict">重新检查</el-button>
          <el-button v-else-if="predictionStatus === 'UNAVAILABLE'" size="small" plain @click="backToMatches">查看其他比赛</el-button>
        </div>

        <el-card v-if="predictionResult" class="decision-card" shadow="never">
          <div class="decision-card-head">
            <div>
              <span class="section-eyebrow">{{ isFinished ? '赛后复盘' : '统一预测结论' }}</span>
              <h2>{{ resultLabelShort }}</h2>
              <p>{{ primaryDecisionText }}</p>
            </div>
            <div class="decision-actions">
              <el-button class="favorite-action" :loading="favoriteLoading" :icon="Star" :type="isFavorite ? 'success' : 'default'" plain @click="toggleFavorite">{{ isFavorite ? '已收藏' : '收藏比赛' }}</el-button>
              <el-dropdown trigger="click" @command="handleDecisionCommand">
                <el-button text class="more-action" aria-label="更多操作">更多</el-button>
                <template #dropdown>
                  <el-dropdown-menu>
                    <el-dropdown-item command="share"><el-icon><Share /></el-icon>分享报告</el-dropdown-item>
                    <el-dropdown-item command="agent"><el-icon><ChatLineSquare /></el-icon>让 Agent 解读</el-dropdown-item>
                    <el-dropdown-item v-if="!isFinished" command="reminder"><el-icon><Bell /></el-icon>{{ reminderSet ? '关闭开赛提醒' : '设置开赛提醒' }}</el-dropdown-item>
                  </el-dropdown-menu>
                </template>
              </el-dropdown>
            </div>
          </div>

          <div class="decision-lead" :class="resultClass">
            <div class="decision-lead-icon"><el-icon :size="28"><component :is="outcomeIcon" /></el-icon></div>
            <div><strong>{{ isFinished ? actualResultLabel || '比赛已结束' : `模型倾向：${resultLabelShort}` }}</strong><span>{{ confidenceLabel }} · {{ predictionGeneratedAt ? `生成于 ${formatDate(predictionGeneratedAt)}` : '统一预测快照' }}</span></div>
          </div>

          <PredictionProbabilityPanel
            :home-name="fixtureData?.teams?.home?.name || '主队'"
            :away-name="fixtureData?.teams?.away?.name || '客队'"
            :probabilities="normalizedProbabilities"
            :warning="predictionHasWarning"
            :home-color="homeColor"
            :draw-color="drawColor"
            :away-color="awayColor"
          />

          <div v-if="isFinished && actualResultLabel" class="review-summary" :class="predictionHit === true ? 'review-hit' : predictionHit === false ? 'review-miss' : ''">
            <div><span>实际结果</span><strong>{{ actualResultLabel }}</strong></div>
            <div><span>赛前预测</span><strong>{{ resultLabelShort }}</strong></div>
            <el-tag v-if="predictionHit !== null" :type="predictionHit ? 'success' : 'danger'" size="small">{{ predictionHit ? '预测命中' : '预测未命中' }}</el-tag>
          </div>

          <div class="decision-notice" :class="predictionHasWarning ? 'notice-caution' : 'notice-positive'">
            <span class="notice-dot"></span><strong>{{ predictionQualityTitle }}</strong><span>{{ riskSummary.join(' · ') }}</span>
          </div>

          <div v-if="prematchQuality" class="prematch-quality-panel" aria-label="赛前数据覆盖">
            <div class="quality-panel-head"><div><strong>赛前数据覆盖</strong><small>{{ prematchQuality.source || '本地赛前快照' }} · {{ prematchQuality.updatedAt || '更新时间未知' }}</small></div><el-tag size="small" :type="prematchQualityType">{{ prematchQualityLabel }}</el-tag></div>
            <div class="prematch-quality-grid"><div v-for="item in prematchQuality.items" :key="item.key"><span>{{ item.label }}</span><strong>{{ item.value }}</strong></div></div>
          </div>

          <el-collapse v-model="advancedPanels" class="advanced-panels">
            <el-collapse-item v-if="modelQualityVisible" name="quality" title="模型质量与适用范围">
              <PredictionModelQuality :quality="modelQuality" :visible="modelQualityVisible" :reliable="qualityIsReliable" />
            </el-collapse-item>
            <el-collapse-item v-if="featureContrib?.length" name="factors" title="影响较大的输入指标">
              <PredictionFeatureContribution :items="featureContrib" />
            </el-collapse-item>
          </el-collapse>
        </el-card>

        <!-- 比赛信息放在结论之后，避免用户先读技术细节。 -->
        <el-card v-if="!fixtureNotFound" class="match-info-card" shadow="hover" :class="{ 'is-loading': loading }">
          <template #header>
            <div class="card-header"><span>比赛信息</span><div class="header-actions"><el-tag v-if="loading" size="small" type="info">读取中…</el-tag><el-button size="small" text @click="showH2H"><el-icon><DataLine /></el-icon>查看交锋</el-button></div></div>
          </template>
          <div class="match-detail">
            <div class="team-block" role="button" tabindex="0" :aria-label="`查看${fixtureData?.teams?.home?.name || '主队'}阵容`" @click="goTeamSquad(homeId, 'home')" @keydown.enter="goTeamSquad(homeId, 'home')" @keydown.space.prevent="goTeamSquad(homeId, 'home')"><img v-if="fixtureData?.teams?.home?.logo" :src="fixtureData.teams.home.logo" :alt="`${fixtureData?.teams?.home?.name || '主队'}队徽`" class="big-logo" /><div v-else class="logo-placeholder">主</div><div class="team-name-lg">{{ fixtureData?.teams?.home?.name || '主队' }}</div><small>查看阵容</small></div>
            <div class="vs-block"><div v-if="hasScore" class="final-score">{{ fixtureData.goals.home }} - {{ fixtureData.goals.away }}</div><div class="vs-label">VS</div><div class="match-date">{{ formatDate(fixtureData?.fixture?.date) }}</div><div class="match-venue" v-if="fixtureData?.fixture?.venue?.name">{{ fixtureData.fixture.venue.name }}</div></div>
            <div class="team-block" role="button" tabindex="0" :aria-label="`查看${fixtureData?.teams?.away?.name || '客队'}阵容`" @click="goTeamSquad(awayId, 'away')" @keydown.enter="goTeamSquad(awayId, 'away')" @keydown.space.prevent="goTeamSquad(awayId, 'away')"><img v-if="fixtureData?.teams?.away?.logo" :src="fixtureData.teams.away.logo" :alt="`${fixtureData?.teams?.away?.name || '客队'}队徽`" class="big-logo" /><div v-else class="logo-placeholder">客</div><div class="team-name-lg">{{ fixtureData?.teams?.away?.name || '客队' }}</div><small>查看阵容</small></div>
          </div>
        </el-card>

          <!-- 历史交锋弹窗 -->
          <el-dialog v-model="h2hVisible" title="历史交锋记录" width="min(650px, 94vw)">
            <div v-if="h2hLoading" class="loading-state">
              <el-skeleton :rows="5" animated />
            </div>
            <div v-else-if="h2hData" class="h2h-content">
              <div class="h2h-summary">
                <div class="summary-team home">
                  <img v-if="fixtureData?.teams?.home?.logo" :src="fixtureData.teams.home.logo" :alt="`${fixtureData?.teams?.home?.name || '主队'}队徽`" class="summary-logo" />
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
                  <img v-if="fixtureData?.teams?.away?.logo" :src="fixtureData.teams.away.logo" :alt="`${fixtureData?.teams?.away?.name || '客队'}队徽`" class="summary-logo" />
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

          <!-- 页面底部操作：只保留一个主要动作，避免反复点击误解。 -->
          <div v-if="!fixtureNotFound" class="action-area">
            <div class="action-buttons">
              <el-button v-if="predictionResult" type="primary" size="large" :loading="predicting" class="predict-btn" @click="doPredict"><el-icon><RefreshRight /></el-icon>检查最新快照</el-button>
              <el-button v-else type="primary" size="large" :loading="predicting" class="predict-btn" @click="predictionStatus === 'UNAVAILABLE' ? backToMatches() : doPredict"><el-icon><TrendCharts /></el-icon>{{ statusActionLabel }}</el-button>
              <el-button size="large" plain @click="backToMatches">返回比赛列表</el-button>
            </div>
            <div class="login-tip">预测结果按比赛统一生成；{{ isLoggedIn ? '可收藏比赛并设置提醒。' : '登录后可收藏比赛并设置提醒。' }}</div>
          </div>

        </div>
    </el-main>
  </div>
</template>

<script setup>
import { ref, computed, watch, onMounted, onUnmounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { analyticsApi, crawlerApi, favoriteApi, predictionApi } from '../../api'
import { useUserStore } from '../../stores/user'
import { normalizeProbability, normalizeProbabilities, parseFeatureString } from '../../utils/prediction'
import { ElMessage } from 'element-plus'
import AppTopNav from '../../components/layout/AppTopNav.vue'
import PredictionProbabilityPanel from '../../components/prediction/PredictionProbabilityPanel.vue'
import PredictionModelQuality from '../../components/prediction/PredictionModelQuality.vue'
import PredictionFeatureContribution from '../../components/prediction/PredictionFeatureContribution.vue'
import { Bell, ChatLineSquare, CircleCheck, DataLine, Football, RefreshRight, Share, Star, TrendCharts, WarningFilled } from '@element-plus/icons-vue'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
let fixtureId = 0
let homeId = ''
let awayId = ''

// 从 URL 参数获取球队名称，如果没有则使用默认值
const queryText = (value, fallback = '') => {
  const raw = Array.isArray(value) ? value[0] : value
  if (raw == null || raw === '') return fallback
  try { return decodeURIComponent(String(raw)) } catch { return String(raw) }
}
let homeName = '主队'
let awayName = '客队'

const syncRouteContext = () => {
  fixtureId = Number.parseInt(route.params.fixtureId, 10)
  homeId = String(route.query.homeId || route.query.home || '')
  awayId = String(route.query.awayId || route.query.away || '')
  homeName = queryText(route.query.homeName || route.query.home, '主队')
  awayName = queryText(route.query.awayName || route.query.away, '客队')
}

syncRouteContext()

const fixtureData = ref({
  teams: {
    home: { id: homeId, name: homeName, logo: queryText(route.query.homeLogo) },
    away: { id: awayId, name: awayName, logo: queryText(route.query.awayLogo) }
  }
})
const predictionResult = ref(null)
const predictionStatus = ref('LOADING')
const fixtureNotFound = ref(false)
const predictionGeneratedAt = ref('')
const predicting = ref(false)
const loading = ref(false)
const matchLoadError = ref('')
let predictionPollTimer = null
let predictionPollAttempts = 0
let predictionPollInFlight = false
let predictionAbortController = null
let matchAbortController = null
let pageMounted = false
let loadSequence = 0
let predictionRequestSequence = 0
const isFavorite = ref(false)
const favoriteLoading = ref(false)
const advancedPanels = ref([])
const reminderSet = ref(false)
const isLoggedIn = computed(() => Boolean(userStore.token))

const predictionStatusTitle = computed(() => ({
  LOADING: '正在读取比赛与预测状态',
  PENDING: '统一预测准备中',
  UNAVAILABLE: '本场暂不生成预测',
  ERROR: '暂时无法读取预测结果',
  FAILED: '预测快照生成失败',
  TIMEOUT: '预测准备时间较长'
}[predictionStatus.value] || '预测状态未知'))
const predictionStatusDescription = computed(() => {
  if (fixtureNotFound.value) return '这场比赛已经从当前赛程中移除，或链接中的比赛 ID 无效。请返回比赛列表重新选择。'
  if (predictionStatus.value === 'PENDING') return '系统会自动检查最新快照，不需要重复提交或连续点击。'
  if (predictionStatus.value === 'UNAVAILABLE') return matchLoadError.value || '可能是联赛未覆盖、历史样本不足或赛前数据尚未同步。可以先查看其他比赛。'
  if (predictionStatus.value === 'TIMEOUT') return '后台可能仍在准备数据；你可以稍后重新打开本场比赛。'
  if (predictionStatus.value === 'ERROR' || predictionStatus.value === 'FAILED') return matchLoadError.value || '请点击“重新检查”再次读取，或返回比赛列表查看其他场次。'
  return '请稍候，页面将自动加载结果。'
})

const predictionQualityTitle = computed(() => {
  if (!predictionResult.value) return ''
  if (isFinished.value && predictionHit.value !== null) {
    return predictionHit.value ? '赛前预测已命中，下面展示本场复盘' : '赛前预测未命中，下面展示本场复盘'
  }
  if (!normalizedProbabilities.value.valid) {
    return '接口概率已自动归一化：本场结果仅供参考'
  }
  if (predictionResult.value.featureComplete === false) {
    return predictionResult.value.featureStatus === 'DEFAULTED'
      ? '特征数据不足：本次结果仅为低可信度参考'
      : predictionResult.value.featureStatus === 'LIMITED'
        ? '历史样本有限：本场使用 ELO+Poisson 保守基线'
      : '部分特征缺失：本次结果仅供参考'
  }
  if (Number.isFinite(Number(predictionResult.value.decisionMargin)) && Number(predictionResult.value.decisionMargin) < 0.08) {
    return '三类概率接近：本场不做强结论，仅作为信息参考'
  }
  const accuracy = Number(predictionResult.value.featureMeta?.model_accuracy)
  if (Number.isFinite(accuracy) && accuracy < 0.55) {
    return `模型质量偏低：${resultLabelShort.value}（时间外测试准确率 ${(accuracy * 100).toFixed(1)}%，暂不建议据此做强结论）`
  }
  return `预测已生成：${resultLabelShort.value}`
})
const predictionHasWarning = computed(() => {
  if (!predictionResult.value) return false
  if (!normalizedProbabilities.value.valid) return true
  if (predictionResult.value.featureComplete === false) return true
  const accuracy = Number(predictionResult.value.featureMeta?.model_accuracy)
  const logLoss = Number(predictionResult.value.featureMeta?.model_log_loss)
  const baselineLogLoss = Number(predictionResult.value.featureMeta?.model_baseline_log_loss)
  return (Number.isFinite(accuracy) && accuracy < 0.55)
    || (Number.isFinite(logLoss) && Number.isFinite(baselineLogLoss) && logLoss >= baselineLogLoss)
    || (Number.isFinite(Number(predictionResult.value.decisionMargin)) && Number(predictionResult.value.decisionMargin) < 0.08)
})
const modelQuality = computed(() => predictionResult.value?.featureMeta || {})
const prematchQuality = computed(() => {
  const snapshot = modelQuality.value?.prematchSnapshot
  if (!snapshot || typeof snapshot !== 'object') return null
  const quality = snapshot.dataQuality && typeof snapshot.dataQuality === 'object' ? snapshot.dataQuality : {}
  const labels = { AVAILABLE: '已接入', EMPTY: '暂无数据', NOT_CONFIGURED: '未配置', UNSUPPORTED: '套餐不支持', REQUEST_FAILED: '请求失败' }
  const status = key => labels[String(quality[key] || 'NOT_CONFIGURED').toUpperCase()] || String(quality[key] || '未知')
  const fetchedAt = snapshot.dataFetchedAt || snapshot.fetchedAt
  const trackedKeys = ['injuries', 'lineups', 'xgShots']
  return {
    source: snapshot.source || quality.historySource,
    updatedAt: fetchedAt ? `更新于 ${formatDate(fetchedAt)}` : snapshot.cutoffTime ? `数据截止 ${formatDate(snapshot.cutoffTime)}` : '更新时间未知',
    status: trackedKeys.some(key => String(quality[key] || '').toUpperCase() === 'REQUEST_FAILED') ? 'REQUEST_FAILED' : 'AVAILABLE',
    items: [
      { key: 'injuries', label: '伤停', value: status('injuries') },
      { key: 'lineups', label: '首发', value: status('lineups') },
      { key: 'xgShots', label: 'xG / 射门', value: status('xgShots') }
    ]
  }
})
const prematchQualityLabel = computed(() => {
  if (!prematchQuality.value) return ''
  const available = prematchQuality.value.items.filter(item => item.value === '已接入').length
  return available === prematchQuality.value.items.length ? '数据完整' : `已接入 ${available}/${prematchQuality.value.items.length}`
})
const prematchQualityType = computed(() => prematchQuality.value?.status === 'REQUEST_FAILED' ? 'danger' : 'info')
const riskSummary = computed(() => {
  const risks = []
  const accuracy = Number(predictionResult.value?.featureMeta?.model_accuracy)
  const margin = Number(predictionResult.value?.decisionMargin)
  if (predictionResult.value?.featureComplete === false) risks.push(predictionResult.value?.featureStatus === 'LIMITED' ? '历史样本有限，使用保守基线' : '部分输入数据缺失')
  if (Number.isFinite(margin) && margin < 0.08) risks.push('三类概率接近')
  if (Number.isFinite(accuracy) && accuracy < 0.55) risks.push('模型近期测试表现偏弱')
  if (modelQuality.value?.model_quality_gate?.eligible === false) risks.push('模型尚未通过生产门槛')
  if (prematchQuality.value) {
    const available = prematchQuality.value.items.filter(item => item.value === '已接入').length
    if (available < prematchQuality.value.items.length) risks.push(`赛前数据已接入 ${available}/${prematchQuality.value.items.length}`)
  }
  return risks.length ? risks : ['当前没有检测到额外风险']
})
const qualityValue = (key) => {
  const value = Number(modelQuality.value?.[key])
  return Number.isFinite(value) ? value : null
}
const modelQualityVisible = computed(() => Object.keys(modelQuality.value || {}).some(key => key.startsWith('model_')))
const qualityIsReliable = computed(() => {
  if (modelQuality.value?.model_quality_gate?.eligible === false) return false
  if (modelQuality.value?.model_quality_gate?.eligible === true) return true
  const accuracy = qualityValue('model_accuracy')
  const logLoss = qualityValue('model_log_loss')
  const baseline = qualityValue('model_baseline_log_loss')
  const hasBaselineComparison = logLoss != null && baseline != null
  return accuracy != null && accuracy >= 0.55 && hasBaselineComparison && logLoss < baseline
})
// 历史交锋
const h2hVisible = ref(false)
const h2hLoading = ref(false)
const h2hData = ref(null)

const homeColor = '#0f6b4d'
const drawColor = '#909399'
const awayColor = '#b27a18'

const statusType = computed(() => {
  const s = fixtureData.value?.fixture?.status?.short
  if (s === 'FT') return 'success'
  if (s === 'LIVE' || s === '1H' || s === '2H') return 'danger'
  return 'info'
})

const matchStatus = computed(() => String(fixtureData.value?.fixture?.status?.short || 'NS').toUpperCase())
const isFinished = computed(() => ['FT', 'AET', 'PEN', 'FINISHED'].includes(matchStatus.value))
const hasScore = computed(() => {
  const home = fixtureData.value?.goals?.home
  const away = fixtureData.value?.goals?.away
  const valid = value => value !== null && value !== undefined && value !== '' && Number.isFinite(Number(value))
  return isFinished.value && valid(home) && valid(away)
})
const actualResultLabel = computed(() => {
  if (!hasScore.value) return ''
  const home = Number(fixtureData.value.goals.home)
  const away = Number(fixtureData.value.goals.away)
  return home > away ? '主胜' : home < away ? '客胜' : '平局'
})
const predictionHit = computed(() => {
  if (!actualResultLabel.value || !predictionResult.value?.resultLabel) return null
  const actual = actualResultLabel.value === '主胜' ? 'HOME_WIN' : actualResultLabel.value === '客胜' ? 'AWAY_WIN' : 'DRAW'
  return actual === predictionResult.value.resultLabel
})
const fixtureStatusLabel = computed(() => {
  const long = fixtureData.value?.fixture?.status?.long
  return long || statusText(matchStatus.value)
})
const pageKicker = computed(() => isFinished.value ? '赛后复盘' : '赛前预测')
const predictionCardTitle = computed(() => isFinished.value ? '赛前预测复盘' : `${modelDisplayName.value}结果`)
const modelDisplayName = computed(() => {
  const version = String(predictionResult.value?.modelVersion || '').toLowerCase()
  if (version.includes('elo-calibrated')) return '校准 ELO 模型'
  if (version.includes('hybrid')) return '混合模型'
  if (version.includes('xgboost')) return 'XGBoost 模型'
  if (version.includes('poisson')) return '进球分布模型'
  if (version) return '赛前预测模型'
  return '预测模型'
})

const normalizedProbabilities = computed(() => {
  return normalizeProbabilities([
    predictionResult.value?.homeWinProb,
    predictionResult.value?.drawProb,
    predictionResult.value?.awayWinProb
  ])
})

const resultClass = computed(() => {
  const r = predictionResult.value?.resultLabel
  if (r === 'HOME_WIN') return 'result-home'
  if (r === 'AWAY_WIN') return 'result-away'
  return 'result-draw'
})


const resultLabelShort = computed(() => {
  const r = predictionResult.value?.resultLabel
  if (r === 'HOME_WIN') return '主胜'
  if (r === 'AWAY_WIN') return '客胜'
  return '平局'
})
const resultLabel = computed(() => {
  const r = predictionResult.value?.resultLabel
  if (r === 'HOME_WIN') return `预测：${fixtureData.value?.teams?.home?.name} 获胜`
  if (r === 'AWAY_WIN') return `预测：${fixtureData.value?.teams?.away?.name} 获胜`
  return '预测：双方势均力敌，可能平局'
})
const confidenceValue = computed(() => {
  if (!predictionResult.value) return '待生成'
  const margin = Number(predictionResult.value.decisionMargin)
  if (Number.isFinite(margin)) return `${Math.round(Math.max(0, margin) * 100)}%`
  const values = [predictionResult.value.homeWinProb, predictionResult.value.drawProb, predictionResult.value.awayWinProb]
    .map(normalizeProbability).filter(value => value !== null).sort((a, b) => b - a)
  return values.length > 1 ? `${Math.round((values[0] - values[1]) * 100)}%` : '待计算'
})
const confidenceLabel = computed(() => {
  if (!predictionResult.value) return predictionStatus.value === 'LOADING' ? '正在读取统一快照' : '等待统一快照'
  const label = predictionResult.value?.confidenceLabel || '概率分离程度'
  return `${label}（未校准，不代表命中率）`
})
const statusActionLabel = computed(() => ({
  PENDING: '自动检查中',
  ERROR: '重新读取结果',
  FAILED: '重新读取结果',
  TIMEOUT: '重新读取结果',
  UNAVAILABLE: '查看其他比赛'
}[predictionStatus.value] || '查看生成状态'))
const primaryDecisionText = computed(() => {
  if (isFinished.value) {
    if (predictionHit.value === true) return `赛前判断与最终赛果一致，概率仅代表赛前信息下的倾向。`
    if (predictionHit.value === false) return `赛前判断与最终赛果不同，下面保留原始概率用于复盘。`
    return '比赛已经结束，以下内容保留赛前预测快照。'
  }
  return `模型更偏向${resultLabelShort.value}，当前概率分离度为 ${confidenceValue.value}；请结合下方风险提示阅读。`
})
const outcomeIcon = computed(() => {
  if (!isFinished.value) return TrendCharts
  return predictionHit.value === false ? WarningFilled : CircleCheck
})

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

const featureContrib = computed(() => {
  if (!predictionResult.value) return null
  const topFeatures = Array.isArray(predictionResult.value.topFeatures) ? predictionResult.value.topFeatures : []

  const parsedTopFeatures = topFeatures.map((item, index) => {
    const parsed = typeof item === 'string' ? parseFeatureString(item) : null
    if (parsed) {
      const feature = parsed.feature || `feature_${index + 1}`
      const label = parsed.label || featureLabelMap[feature] || feature
      const importance = Number(parsed.importance ?? 0)
      const valueText = parsed.value !== undefined ? `${parsed.value}${parsed.unit ? ` ${parsed.unit}` : ''}` : `${importance || Math.max(0, 100 - index * 18)}%`
      return {
        feature,
        label,
        importance: Number.isFinite(importance) && importance > 0 ? importance : null,
        detail: parsed.value !== undefined
          ? `${label}当前值：${parsed.value}${parsed.unit ? ` ${parsed.unit}` : ''} · 这是模型参考输入，不代表因果关系`
          : `${label}作为模型参考输入，仅展示相对排序，不代表因果关系`,
        valueText,
        percent: 0,
      }
    }

    if (typeof item === 'string') {
      return {
        feature: item,
        label: featureLabelMap[item] || item,
        importance: null,
        detail: `${featureLabelMap[item] || item}作为模型参考输入，仅展示相对排序，不代表因果关系`,
        valueText: `排名 ${index + 1}`,
        percent: 0,
      }
    }

    const feature = item?.feature || item?.name || item?.label || `feature_${index + 1}`
    const label = item?.label || featureLabelMap[feature] || feature
    const importance = Number(item?.importance ?? item?.value ?? 0)
    const unit = item?.unit ? String(item.unit) : ''
    return {
      feature,
      label,
      importance: Number.isFinite(importance) && importance > 0 ? importance : null,
      detail: item?.value !== undefined
        ? `${label}当前值：${item.value}${unit ? ` ${unit}` : ''} · 这是模型参考输入，不代表因果关系`
        : `${label}作为模型参考输入，仅展示相对排序，不代表因果关系`,
      valueText: item?.value !== undefined ? String(item.value) : (importance > 0 ? `${importance}` : `排名 ${index + 1}`),
      percent: 0,
    }
  })

  if (parsedTopFeatures.length) {
    const maxImportance = Math.max(...parsedTopFeatures.map(item => Number(item.importance) || 0), 0)
    return parsedTopFeatures.map((item, index) => ({
      ...item,
      // 有真实重要性时按同一批特征归一化；没有重要性时只展示排序，不伪造贡献比例。
      percent: maxImportance > 0 && item.importance
        ? Math.max(8, Math.round((item.importance / maxImportance) * 100))
        : Math.max(8, 100 - index * 18)
    }))
  }
  // 没有特征输入时不把预测输出概率伪装成“特征贡献”。
  return []
})

const statusText = (status) => ({
  NS: '未开赛', LIVE: '进行中', '1H': '上半场', HT: '中场', '2H': '下半场',
  FT: '已结束', CANCEL: '已取消', POSTP: '已推迟'
}[String(status || '').toUpperCase()] || '未开赛')

const normalizeMatchDetail = (payload) => {
  const raw = Array.isArray(payload)
    ? payload[0]
    : (payload?.response?.[0] || payload?.data?.response?.[0] || payload?.data || payload)
  if (!raw || typeof raw !== 'object') return null
  if (raw.fixture && raw.teams) return raw
  if (!raw.homeTeamName && !raw.awayTeamName && !raw.matchTime) return null
  const status = raw.status || 'NS'
  return {
    fixture: {
      id: raw.fixtureId || raw.externalMatchId || raw.id || fixtureId,
      status: { short: status, long: statusText(status) },
      venue: { name: raw.venue || '' },
      date: raw.matchTime || ''
    },
    league: { id: raw.leagueId || '', name: raw.leagueName || '', round: raw.round || '' },
    teams: {
      home: { id: raw.homeTeamId || '', name: raw.homeTeamName || '主队', logo: raw.homeTeamLogo || '' },
      away: { id: raw.awayTeamId || '', name: raw.awayTeamName || '客队', logo: raw.awayTeamLogo || '' }
    },
    goals: { home: raw.homeScore ?? null, away: raw.awayScore ?? null }
  }
}

const hydratePredictionFromQuery = () => {
  if (!route.path.endsWith('/detail') || !route.query.resultLabel) return
  const probabilities = [route.query.homeWinProb, route.query.drawProb, route.query.awayWinProb].map(value => Number(value))
  if (probabilities.some(value => !Number.isFinite(value) || value < 0) || probabilities.every(value => value === 0)) return
  predictionResult.value = {
    fixtureId,
    modelVersion: route.query.modelVersion || 'baseline-elo-v1',
    homeWinProb: Number(route.query.homeWinProb),
    drawProb: Number(route.query.drawProb),
    awayWinProb: Number(route.query.awayWinProb),
    resultLabel: route.query.resultLabel,
    explanation: route.query.explanation || '',
    featureComplete: route.query.featureComplete !== 'false',
    featureStatus: route.query.featureStatus || 'COMPLETE',
    fallbackReason: route.query.fallbackReason || '',
    decisionMargin: route.query.decisionMargin ? Number(route.query.decisionMargin) : null,
    confidenceLabel: route.query.confidenceLabel || '',
    featureMeta: route.query.modelAccuracy ? { model_accuracy: Number(route.query.modelAccuracy) } : {}
  }
}

const applyPredictionSnapshot = (snapshot) => {
  if (!snapshot || String(snapshot.status || '').toUpperCase() !== 'READY' || !snapshot.resultLabel) return false
  const probabilities = [snapshot.homeWinProb, snapshot.drawProb, snapshot.awayWinProb].map(value => Number(value))
  if (probabilities.some(value => !Number.isFinite(value) || value < 0) || probabilities.every(value => value === 0)) return false
  predictionResult.value = {
    ...snapshot,
    fixtureId,
    topFeatures: Array.isArray(snapshot.topFeatures) ? snapshot.topFeatures : [],
    featureMeta: snapshot.featureMeta && typeof snapshot.featureMeta === 'object' ? snapshot.featureMeta : {},
    featureComplete: snapshot.featureComplete !== false && Number(snapshot.featureComplete) !== 0
  }
  predictionGeneratedAt.value = snapshot.generatedAt || ''
  predictionStatus.value = 'READY'
  const home = snapshot.homeTeamName || snapshot.home_team_name
  const away = snapshot.awayTeamName || snapshot.away_team_name
  if (home || away || snapshot.matchTime || snapshot.homeTeamLogo || snapshot.awayTeamLogo) {
    fixtureData.value = {
      ...fixtureData.value,
      fixture: { ...fixtureData.value?.fixture, date: fixtureData.value?.fixture?.date || snapshot.matchTime },
      teams: {
        home: { ...fixtureData.value?.teams?.home, name: home || fixtureData.value?.teams?.home?.name, logo: snapshot.homeTeamLogo || fixtureData.value?.teams?.home?.logo },
        away: { ...fixtureData.value?.teams?.away, name: away || fixtureData.value?.teams?.away?.name, logo: snapshot.awayTeamLogo || fixtureData.value?.teams?.away?.logo }
      },
      league: { ...fixtureData.value?.league, name: snapshot.leagueName || fixtureData.value?.league?.name }
    }
  }
  return true
}

const stopPredictionPolling = () => {
  predictionRequestSequence += 1
  if (predictionPollTimer) {
    window.clearTimeout(predictionPollTimer)
    predictionPollTimer = null
  }
  predictionAbortController?.abort()
  predictionAbortController = null
  predictionPollInFlight = false
  predictionPollAttempts = 0
}

const queuePredictionPolling = () => {
  if (predictionStatus.value !== 'PENDING' || predictionPollTimer || predictionPollInFlight) return
  if (predictionPollAttempts >= 24) {
    predictionStatus.value = 'TIMEOUT'
    return
  }
  const delay = document.hidden ? 10000 : Math.min(15000, 2500 * (2 ** Math.floor(predictionPollAttempts / 4)))
  predictionPollTimer = window.setTimeout(async () => {
    predictionPollTimer = null
    predictionPollAttempts += 1
    await loadCachedPrediction({ silent: true })
    if (predictionStatus.value === 'PENDING') queuePredictionPolling()
  }, delay)
}

const loadCachedPrediction = async ({ silent = false } = {}) => {
  if (!Number.isFinite(fixtureId) || fixtureId <= 0) {
    predictionStatus.value = 'UNAVAILABLE'
    stopPredictionPolling()
    return
  }
  predictionPollInFlight = true
  predictionAbortController?.abort()
  const requestId = ++predictionRequestSequence
  const controller = new AbortController()
  predictionAbortController = controller
  try {
    const snapshot = await predictionApi.getCurrentByMatch(fixtureId, { signal: controller.signal })
    if (requestId !== predictionRequestSequence || controller.signal.aborted) return
    if (applyPredictionSnapshot(snapshot)) {
      stopPredictionPolling()
      analyticsApi.track('prediction_viewed', {
        page: route.fullPath,
        entityType: 'match',
        entityId: fixtureId,
        properties: { modelVersion: predictionResult.value?.modelVersion || '' }
      }).catch(() => {})
      return
    }
    predictionStatus.value = String(snapshot?.status || 'PENDING').toUpperCase()
    if (!predictionResult.value && predictionStatus.value === 'UNAVAILABLE') {
      const reason = snapshot?.fallbackReason || snapshot?.featureMeta?.gateReason
      matchLoadError.value = reason ? `数据不足，暂不预测：${reason}` : '本场暂时没有可用预测，请稍后重试。'
    }
    if (predictionStatus.value === 'PENDING') queuePredictionPolling()
    else if (predictionStatus.value !== 'LOADING') stopPredictionPolling()
  } catch (error) {
    if (requestId !== predictionRequestSequence) return
    if (error?.code === 'ERR_CANCELED' || error?.name === 'CanceledError') return
    predictionStatus.value = 'ERROR'
    if (!predictionResult.value) matchLoadError.value = '预测结果暂时不可用，请稍后刷新。'
    stopPredictionPolling()
  } finally {
    if (requestId === predictionRequestSequence) {
      if (predictionAbortController === controller) predictionAbortController = null
      predictionPollInFlight = false
      if (!silent && predictionStatus.value === 'PENDING') queuePredictionPolling()
    }
  }
}

const loadMatch = async () => {
  const sequence = ++loadSequence
  loading.value = true
  matchLoadError.value = ''
  fixtureNotFound.value = false
  try {
    const homeTeam = fixtureData.value?.teams?.home || {}
    const awayTeam = fixtureData.value?.teams?.away || {}
    const queryLeague = queryText(route.query.leagueName)
    const fallback = {
      ...fixtureData.value,
      fixture: {
        id: fixtureId,
        status: { short: 'NS', long: '未开赛' },
        venue: { name: '' },
        date: queryText(route.query.matchTime)
      },
      league: { id: queryText(route.query.leagueId), name: queryLeague, round: queryText(route.query.round) },
      teams: {
        home: { ...homeTeam, id: homeId || homeTeam.id, name: homeTeam.name || homeName, logo: homeTeam.logo || queryText(route.query.homeLogo) },
        away: { ...awayTeam, id: awayId || awayTeam.id, name: awayTeam.name || awayName, logo: awayTeam.logo || queryText(route.query.awayLogo) }
      },
      goals: { home: null, away: null }
    }
    fixtureData.value = fallback

    // Defaults such as “主队 / 客队” are presentation placeholders, not proof
    // that the deep link carries a real fixture context. Only preserve the
    // fallback card when the URL actually contains both teams and kickoff time.
    const hasQueryContext = Boolean(
      queryText(route.query.homeName || route.query.home) &&
      queryText(route.query.awayName || route.query.away) &&
      queryText(route.query.matchTime)
    )
    if (Number.isFinite(fixtureId) && fixtureId > 0) {
      matchAbortController?.abort()
      const controller = new AbortController()
      matchAbortController = controller
      const timeoutId = window.setTimeout(() => controller.abort(), 8000)
      try {
        const remote = normalizeMatchDetail(await crawlerApi.getMatchDetail(fixtureId, { signal: controller.signal }))
        if (sequence !== loadSequence) return
        if (remote) {
          fixtureData.value = {
            ...fallback,
            ...remote,
            fixture: { ...fallback.fixture, ...remote.fixture, status: { ...fallback.fixture.status, ...remote.fixture?.status }, venue: { ...fallback.fixture.venue, ...remote.fixture?.venue } },
            league: { ...fallback.league, ...remote.league },
            teams: { home: { ...fallback.teams.home, ...remote.teams?.home }, away: { ...fallback.teams.away, ...remote.teams?.away } },
            goals: { ...fallback.goals, ...remote.goals }
          }
        } else if (!hasQueryContext) {
          fixtureNotFound.value = true
          predictionStatus.value = 'UNAVAILABLE'
          matchLoadError.value = '未找到该场比赛的详细信息。'
        }
      } catch (error) {
        if (controller.signal.aborted && sequence !== loadSequence) return
        if (!hasQueryContext) {
          fixtureNotFound.value = true
          predictionStatus.value = 'UNAVAILABLE'
          matchLoadError.value = error?.name === 'AbortError' ? '比赛详情加载超时，无法确认这场比赛。' : '比赛详情加载失败，无法确认这场比赛。'
        }
      } finally {
        window.clearTimeout(timeoutId)
        if (matchAbortController === controller) matchAbortController = null
      }
    }
    if (sequence !== loadSequence) return
    hydratePredictionFromQuery()
    if (!fixtureNotFound.value) await loadCachedPrediction()
  } finally {
    if (sequence === loadSequence) loading.value = false
  }
}

const doPredict = async () => {
  predicting.value = true
  try {
    // 手动刷新是一次新的轮询周期，避免上一次超时计数阻止后续重试。
    stopPredictionPolling()
    await loadCachedPrediction()
    if (predictionStatus.value === 'READY') ElMessage.success('已加载本场统一预测结果')
    else if (predictionStatus.value === 'PENDING') ElMessage.info('预测正在生成，请稍后刷新')
  } catch (e) {
    predictionStatus.value = 'ERROR'
    ElMessage.warning('预测结果暂不可用，请稍后重试')
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

const goTeamSquad = (teamId, side) => {
  const team = fixtureData.value?.teams?.[side] || {}
  const target = team.name || (teamId ? String(teamId) : '')
  if (!target) return
  router.push({
    path: `/team/${encodeURIComponent(target)}/squad`,
    query: {
      name: target,
      teamId: String(team.id || teamId || ''),
      logo: team.logo || '',
      league: fixtureData.value?.league?.name || queryText(route.query.leagueName, ''),
      season: String(new Date().getFullYear())
    }
  })
}

const backToMatches = () => {
  const query = {}
  ;['returnDate', 'returnLeague', 'returnKeyword', 'returnFavorites', 'returnPage'].forEach(key => {
    if (route.query[key] != null && route.query[key] !== '') query[key] = String(route.query[key])
  })
  router.push(Object.keys(query).length ? { path: '/matches', query } : '/matches')
}

const readMatchReminders = () => {
  try { return JSON.parse(localStorage.getItem('football_match_reminders') || '[]') } catch { return [] }
}
const writeMatchReminders = (items) => localStorage.setItem('football_match_reminders', JSON.stringify(items.slice(-100)))
const syncReminderState = () => {
  reminderSet.value = readMatchReminders().some(item => String(item?.fixtureId || '') === String(fixtureId))
}
const toggleReminder = async () => {
  if (isFinished.value || !fixtureId) return
  const items = readMatchReminders()
  if (reminderSet.value) {
    writeMatchReminders(items.filter(item => String(item?.fixtureId || '') !== String(fixtureId)))
    reminderSet.value = false
    ElMessage.success('已关闭本场提醒')
    return
  }
  const matchTime = fixtureData.value?.fixture?.date || route.query.matchTime || ''
  if (!matchTime || !parseBusinessDate(matchTime)) {
    ElMessage.info('当前比赛时间尚未同步，暂时无法设置提醒')
    return
  }
  writeMatchReminders([...items.filter(item => String(item?.fixtureId || '') !== String(fixtureId)), {
    fixtureId: String(fixtureId),
    title: `${fixtureData.value?.teams?.home?.name || homeName} vs ${fixtureData.value?.teams?.away?.name || awayName}`,
    matchTime,
    notified: false
  }])
  reminderSet.value = true
  if (typeof Notification !== 'undefined' && Notification.permission === 'default') Notification.requestPermission().catch(() => {})
  ElMessage.success('已设置开赛提醒')
}
const handleDecisionCommand = (command) => {
  if (command === 'share') shareReport()
  if (command === 'agent') openAgent()
  if (command === 'reminder') toggleReminder()
}

const normalizeFavoriteList = (payload) => {
  const data = payload?.data ?? payload
  if (Array.isArray(data)) return data
  if (Array.isArray(data?.items)) return data.items
  if (Array.isArray(data?.records)) return data.records
  return []
}

const loadFavoriteState = async () => {
  if (!isLoggedIn.value || !fixtureId) {
    isFavorite.value = false
    return
  }
  try {
    const favorites = normalizeFavoriteList(await favoriteApi.listMatches())
    isFavorite.value = favorites.some(item => String(item?.matchId || item?.id || item?.fixtureId || '') === String(fixtureId))
  } catch {
    // 收藏状态失败不影响预测报告，保持未选中并允许用户重试。
    isFavorite.value = false
  }
}

const toggleFavorite = async () => {
  if (!isLoggedIn.value) {
    userStore.openAuthDialog(route.fullPath, 'login')
    return
  }
  if (!fixtureId || favoriteLoading.value) return
  favoriteLoading.value = true
  try {
    if (isFavorite.value) {
      await favoriteApi.removeMatch(fixtureId)
      isFavorite.value = false
      ElMessage.success('已取消收藏比赛')
    } else {
      const home = fixtureData.value?.teams?.home?.name || homeName
      const away = fixtureData.value?.teams?.away?.name || awayName
      await favoriteApi.addMatch(fixtureId, `${home} vs ${away}`, {
        leagueName: fixtureData.value?.league?.name || queryText(route.query.leagueName, ''),
        matchTime: fixtureData.value?.fixture?.date || queryText(route.query.matchTime, '')
      })
      isFavorite.value = true
      ElMessage.success('比赛收藏成功')
    }
  } catch (error) {
    ElMessage.error(error?.message || '收藏操作失败，请稍后重试')
  } finally {
    favoriteLoading.value = false
  }
}

const shareReport = async () => {
  const title = `${fixtureData.value?.teams?.home?.name || homeName} vs ${fixtureData.value?.teams?.away?.name || awayName} · 预测报告`
  const url = window.location.href
  try {
    if (navigator.share) {
      await navigator.share({ title, text: '查看这场比赛的统一预测与赛前数据覆盖', url })
    } else if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(url)
      ElMessage.success('报告链接已复制')
    } else {
      window.prompt('复制报告链接', url)
    }
    analyticsApi.track('prediction_shared', { page: route.fullPath, entityType: 'match', entityId: fixtureId }).catch(() => {})
  } catch (error) {
    if (error?.name !== 'AbortError') ElMessage.info('分享已取消')
  }
}

const handleVisibilityChange = () => {
  if (!document.hidden && predictionStatus.value === 'PENDING' && !predictionPollTimer && !predictionPollInFlight) {
    queuePredictionPolling()
  }
}

const openAgent = () => {
  const homeTeam = fixtureData.value?.teams?.home || {}
  const awayTeam = fixtureData.value?.teams?.away || {}
  router.push({
    path: '/agent',
    query: {
      fixtureId: String(fixtureId),
      homeTeamId: homeTeam.id || homeId || '',
      awayTeamId: awayTeam.id || awayId || '',
      homeName: homeTeam.name || homeName,
      awayName: awayTeam.name || awayName,
      leagueName: fixtureData.value?.league?.name || route.query.leagueName || '',
      matchTime: fixtureData.value?.fixture?.date || route.query.matchTime || '',
      resultLabel: predictionResult.value?.resultLabel || '',
      homeWinProb: predictionResult.value?.homeWinProb ?? '',
      drawProb: predictionResult.value?.drawProb ?? '',
      awayWinProb: predictionResult.value?.awayWinProb ?? '',
      explanation: predictionResult.value?.explanation || '',
      contextSummary: `${modelDisplayName.value}；${prematchQualityLabel.value || '赛前数据覆盖未知'}；${isFinished.value ? `实际结果${actualResultLabel.value || '未知'}` : '比赛尚未结束'}`
    }
  })
}

const parseBusinessDate = value => {
  if (!value) return null
  const text = String(value).trim().replace(' ', 'T')
  const normalized = /(?:Z|[+-]\d{2}:?\d{2})$/i.test(text) ? text : `${text}+08:00`
  const date = new Date(normalized)
  return Number.isNaN(date.getTime()) ? null : date
}
const formatDate = (dateStr) => {
  if (!dateStr) return ''
  const date = parseBusinessDate(dateStr)
  if (!date) return '时间待定'
  return new Intl.DateTimeFormat('zh-CN', {
    timeZone: 'Asia/Shanghai',
    year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit'
  }).format(date) + '（北京时间）'
}

onMounted(async () => {
  pageMounted = true
  document.addEventListener('visibilitychange', handleVisibilityChange)
  syncReminderState()
  await loadMatch()
  await loadFavoriteState()
  syncReminderState()
})

watch(() => route.fullPath, async () => {
  if (!pageMounted) return
  stopPredictionPolling()
  predictionResult.value = null
  predictionGeneratedAt.value = ''
  predictionStatus.value = 'LOADING'
  matchLoadError.value = ''
  syncRouteContext()
  isFavorite.value = false
  reminderSet.value = false
  fixtureData.value = {
    teams: {
      home: { id: homeId, name: homeName, logo: queryText(route.query.homeLogo) },
      away: { id: awayId, name: awayName, logo: queryText(route.query.awayLogo) }
    }
  }
  await loadMatch()
  await loadFavoriteState()
  syncReminderState()
})

onUnmounted(() => {
  pageMounted = false
  document.removeEventListener('visibilitychange', handleVisibilityChange)
  stopPredictionPolling()
  matchAbortController?.abort()
})
</script>

<style scoped>
.main-content { padding: 24px; max-width: 1240px; margin: 0 auto; }
.prediction-inner { display: flex; flex-direction: column; gap: 16px; }
.report-heading { display:flex; justify-content:space-between; align-items:flex-end; gap:24px; padding:22px 24px; border:1px solid var(--ff-border); border-radius:var(--ff-radius-lg); background:var(--ff-surface); }
.report-heading h1 { margin:8px 0 5px; color:var(--ff-ink); font-size:clamp(24px,3vw,36px); letter-spacing:-.045em; }
.report-heading h1 em { color:var(--ff-primary); font-style:normal; font-size:.7em; margin:0 7px; }
.report-heading p { margin:0; color:var(--ff-text-muted); }
.report-confidence { min-width:130px; padding-left:18px; border-left:1px solid var(--ff-border); display:flex; flex-direction:column; gap:3px; }
.report-confidence span,.report-confidence small { color:var(--ff-text-muted); font-size:12px; }
.report-confidence strong { color:var(--ff-primary); font:700 30px/1 var(--ff-mono); }
.back-btn { box-shadow: var(--ff-shadow-sm); }
.result-banner { border-radius: 8px; }
.prediction-state-panel { display:flex; align-items:center; gap:12px; padding:14px 16px; border:1px dashed var(--ff-border); border-radius:var(--ff-radius-md); background:var(--ff-surface-quiet); color:var(--ff-text-muted); }
.prediction-state-panel strong { color:var(--ff-text-strong); white-space:nowrap; }
.prediction-state-panel span { flex:1; line-height:1.5; }
.prediction-state-panel.state-pending { border-color:rgba(15,107,77,.28); }
.prediction-state-panel.state-unavailable,.prediction-state-panel.state-error,.prediction-state-panel.state-failed,.prediction-state-panel.state-timeout { border-color:rgba(178,122,24,.35); }

.card-header { display: flex; justify-content: space-between; align-items: center; }
.header-actions { display: flex; gap: 8px; align-items: center; }
.header-actions .el-button { min-height: 28px; }
.snapshot-time { color: var(--ff-text-muted); font-size: 11px; white-space: nowrap; }

.match-detail { display: flex; align-items: center; justify-content: space-around; padding: 24px 0; gap: 14px; }
.team-block {
  display: flex; flex-direction: column; align-items: center; gap: 10px;
  cursor: pointer; padding: 12px; border-radius: 8px; transition: background var(--ff-transition-fast);
}
.team-block:hover { background: var(--ff-surface-soft); }
.team-block:focus-visible { outline: 2px solid var(--ff-primary); outline-offset: 3px; }
.big-logo { width: 80px; height: 80px; object-fit: contain; }
.logo-placeholder {
  width: 80px; height: 80px; border-radius: 8px;
  background: var(--ff-bg-alt); border: 1px solid var(--ff-border); display: flex; align-items: center; justify-content: center;
  font-size: 28px; color: var(--ff-primary); font-family: var(--ff-mono);
}
.team-name-lg { font-size: 16px; font-weight: 600; color: var(--ff-text); text-align: center; max-width: 120px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.vs-block { text-align: center; }
.final-score { font-size: 36px; font-weight: 600; color: var(--ff-text-strong); font-family: var(--ff-mono); font-variant-numeric: tabular-nums; }
.vs-label { font-size: 20px; color: var(--ff-primary); margin: 6px 0; font-family: var(--ff-mono); font-weight: 600; }
.match-date { font-size: 13px; color: var(--ff-text-muted); font-family: var(--ff-mono); }
.match-venue { font-size: 12px; color: var(--ff-text-muted); margin-top: 4px; }

/* 概率展示 */
.prob-section { margin-bottom: 20px; }
.prob-header {
  display: flex; justify-content: space-between; align-items: center;
  margin-bottom: 16px; font-size: 14px; color: var(--ff-text-muted);
}
.prob-hint { color: var(--ff-text-muted); font-size: 12px; }
.prob-bars { display:grid; grid-template-columns:repeat(3,minmax(0,1fr)); gap:12px; }
.prob-item { display:flex; flex-direction:column; align-items:stretch; gap:10px; padding:14px; border:1px solid var(--ff-border); border-radius:var(--ff-radius-md); background:var(--ff-surface-quiet); }
.prob-label {
  width:auto; font-weight: 600; font-size: 14px; color: var(--ff-text);
  display: flex; align-items: center; gap: 6px;
}
.team-tag { display:inline-flex; align-items:center; padding:2px 7px; border-radius:999px; font-size:11px; font-weight:600; }
.home-tag { color:var(--ff-primary); background:var(--ff-primary-soft); }
.away-tag { color:#8b6117; background:#f5ead4; }
.label-text { flex: 1; }
.prob-bar-wrapper { flex: 1; }
.prob-value { width:auto; text-align:right; font-weight:600; font-size:20px; color:var(--ff-primary); font-family:var(--ff-mono); }

.result-summary {
  display: grid;
  grid-template-columns: 1.2fr 1fr;
  gap: 16px;
  padding: 16px;
  border-radius: 8px;
  background: #ffffff;
  border: 1px solid var(--ff-border);
  box-shadow: var(--ff-shadow-sm);
  margin-bottom: 16px;
}
.model-quality-panel { margin-top: 20px; padding: 16px 18px; border: 1px solid var(--ff-border); border-radius: 8px; background: var(--ff-bg-alt); }
.quality-panel-head { display:flex; align-items:flex-start; justify-content:space-between; gap:12px; }
.quality-scope-warning { display:block; margin-top:10px; color:var(--ff-text-faint); line-height:1.6; }
.quality-panel-head > div { display:flex; flex-direction:column; gap:4px; }
.quality-panel-head strong { color:var(--ff-text-strong); font-size:14px; }
.quality-panel-head small { color:var(--ff-text-muted); font-size:11px; }
.quality-metric-grid { display:grid; grid-template-columns:repeat(4,minmax(0,1fr)); gap:10px; margin-top:14px; }
.quality-metric-grid > div { padding:10px 12px; border-left:2px solid var(--ff-primary); background:var(--ff-surface); }
.quality-metric-grid span { display:block; color:var(--ff-text-muted); font-size:11px; }
.quality-metric-grid strong { display:block; margin-top:5px; color:var(--ff-text-strong); font-family:var(--ff-mono); font-size:16px; }
.result-main { display: flex; flex-direction: column; justify-content: center; gap: 6px; }
.result-title { font-size: 22px; font-weight: 600; color: var(--ff-text-strong); }
.result-subtitle { font-size: 14px; color: var(--ff-text); }
.result-meta { font-size: 12px; color: var(--ff-text-muted); }
.result-scoreboard { display: grid; grid-template-columns: repeat(3, 1fr); gap: 10px; }
.score-item { padding: 12px; border-radius: 8px; background: var(--ff-bg-alt); border: 1px solid var(--ff-border); text-align: center; }
.score-item.home { box-shadow: inset 0 0 0 1px rgba(15,107,77,0.08); }
.score-item.draw { box-shadow: inset 0 0 0 1px rgba(161,159,157,0.08); }
.score-item.away { box-shadow: inset 0 0 0 1px rgba(178,122,24,0.12); }
.score-label { display: block; font-size: 11px; font-weight: 600; color: var(--ff-text-muted); margin-bottom: 4px; }
.score-value { font-size: 18px; font-weight: 600; color: var(--ff-text-strong); font-family: var(--ff-mono); font-variant-numeric: tabular-nums; }
.result-badge { 
  display: flex; align-items: center; justify-content: center; gap: 12px;
  padding: 20px; border-radius: 12px; font-size: 20px; font-weight: 700; margin-bottom: 20px;
}
.review-summary { display:flex; align-items:center; gap:22px; padding:14px 16px; margin:-4px 0 18px; border:1px solid var(--ff-border); border-radius:var(--ff-radius-md); background:var(--ff-surface-quiet); }
.review-summary > div { display:flex; flex-direction:column; gap:3px; }
.review-summary span { color:var(--ff-text-muted); font-size:11px; }
.review-summary strong { color:var(--ff-text-strong); font-size:15px; }
.review-summary .el-tag { margin-left:auto; }
.review-hit { border-color:rgba(15,107,77,.32); background:var(--ff-primary-soft); }
.review-miss { border-color:rgba(178,122,24,.35); background:#f8f0e2; }
.prematch-quality-panel { margin:18px 0; padding:16px 18px; border:1px solid var(--ff-border); border-radius:var(--ff-radius-md); background:var(--ff-surface-quiet); }
.prematch-quality-panel .quality-panel-head { margin-bottom:12px; }
.prematch-quality-grid { display:grid; grid-template-columns:repeat(4,minmax(0,1fr)); gap:8px; }
.prematch-quality-grid > div { display:flex; flex-direction:column; gap:4px; padding:9px 10px; border:1px solid var(--ff-border); border-radius:8px; background:var(--ff-surface); }
.prematch-quality-grid span { color:var(--ff-text-muted); font-size:11px; }
.prematch-quality-grid strong { color:var(--ff-text-strong); font-size:13px; }
.result-home { background: var(--ff-primary-soft); color: var(--ff-primary); border: 1px solid rgba(15,107,77,.30); }
.result-away { background: #f5ead4; color: #8b6117; border: 1px solid rgba(178,122,24,.30); }
.result-draw { background: var(--ff-bg-alt); color: var(--ff-text-muted); border: 1px solid rgba(96,94,92,.22); }
@media (max-width: 700px) { .quality-metric-grid { grid-template-columns:repeat(2,minmax(0,1fr)); } .quality-panel-head { flex-direction:column; } }

.feature-contrib {
  background: #ffffff; border-radius: 8px; padding: 16px 20px;
  border: 1px solid var(--ff-border);
  box-shadow: var(--ff-shadow-sm);
}
.contrib-header {
  display: flex; align-items: center; gap: 8px;
  margin-bottom: 16px; font-weight: 700; color: var(--ff-text);
}
.contrib-list { display: flex; flex-direction: column; gap: 14px; }
.contrib-item {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 14px;
  border: 1px solid var(--ff-border);
  border-radius: 8px;
  background: var(--ff-bg-alt);
}
.contrib-topline {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}
.contrib-name-wrap { display: flex; flex-direction: column; gap: 4px; min-width: 0; }
.contrib-name { font-size: 14px; color: var(--ff-text-strong); font-weight: 700; line-height: 1.3; }
.contrib-feature { font-size: 12px; color: var(--ff-text-muted); word-break: break-all; font-family: var(--ff-mono); }
.contrib-detail { font-size: 12px; color: var(--ff-text-muted); line-height: 1.6; word-break: break-word; }
.contrib-bar { height: 8px; background: var(--ff-bg-alt); border-radius: 999px; overflow: hidden; }
.contrib-fill { height: 100%; background: var(--ff-primary); border-radius: 999px; transition: width 0.2s ease-out; }
.contrib-value { font-size: 13px; font-weight: 600; color: var(--ff-primary); white-space: nowrap; font-family: var(--ff-mono); }

.action-area { text-align: center; }
.predict-btn { width: 260px; height: 52px; font-size: 16px; }
.predict-progress {
  margin-top: 14px;
  padding: 14px 16px;
  background: var(--ff-bg-alt);
  border: 1px solid var(--ff-border);
  border-radius: 8px;
  box-shadow: var(--ff-shadow-sm);
}
.progress-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 10px;
  font-size: 13px;
  color: var(--ff-text-muted);
}

/* 历史交锋弹窗 */
.h2h-summary {
  display: flex; align-items: center; justify-content: space-between;
  padding: 20px; background: var(--ff-bg-alt); border: 1px solid var(--ff-border); border-radius: 8px; margin-bottom: 20px;
}
.summary-team {
  display: flex; flex-direction: column; align-items: center; gap: 8px;
}
.summary-team span { font-size: 13px; font-weight: 700; color: var(--ff-text); max-width: 100px; text-align: center; }
.summary-logo { width: 48px; height: 48px; border-radius: 8px; }
.summary-stats { display: flex; gap: 20px; }
.stat-box { text-align: center; }
.stat-num { display: block; font-size: 28px; font-weight: 600; color: var(--ff-primary); font-family: var(--ff-mono); }
.stat-desc { font-size: 12px; color: var(--ff-text-muted); }
.h2h-matches { display: flex; flex-direction: column; gap: 8px; }
.h2h-match {
  display: flex; align-items: center; gap: 16px;
  padding: 12px 16px; background: var(--ff-bg-alt); border: 1px solid var(--ff-border); border-radius: 6px;
}
.h2h-date { width: 80px; font-size: 12px; color: var(--ff-text-muted); font-family: var(--ff-mono); }
.h2h-teams { flex: 1; font-size: 14px; color: var(--ff-text); }
.h2h-score { font-size: 16px; font-weight: 600; color: var(--ff-text-strong); font-family: var(--ff-mono); }
.h2h-empty { text-align: center; padding: 40px; color: var(--ff-text-muted); }
.loading-state { padding: 20px; }

.login-tip { margin-top: 12px; font-size: 13px; color: var(--ff-warning); }
.login-tip a { color: var(--ff-primary); text-decoration: none; }

@media (max-width: 768px) {
  .main-content { padding: 12px; }
  .report-heading { align-items:flex-start; flex-direction:column; padding:18px; }
  .report-confidence { width:100%; padding:12px 0 0; border-left:none; border-top:1px solid var(--ff-border); }
  .prob-bars { grid-template-columns:1fr; }
  .match-detail { flex-direction: column; gap: 12px; }
  .team-block { justify-content: center; }
  .vs-block { order: -1; }
  .prob-bar-wrapper { min-width: 0; }
  .card-header { flex-wrap: wrap; gap: 8px; }
  .header-actions { width:100%; flex-wrap:wrap; justify-content:flex-start; }
  .header-actions .snapshot-time { order: 5; flex-basis:100%; }
  .prediction-state-panel { align-items:flex-start; flex-wrap:wrap; }
  .prediction-state-panel span { flex-basis:100%; order:3; }
  .review-summary { flex-wrap:wrap; gap:14px; }
  .review-summary .el-tag { margin-left:0; }
  .prematch-quality-grid { grid-template-columns:repeat(2,minmax(0,1fr)); }
}

/* ===== 预测决策页视觉层级 ===== */
.main-content { padding-top: 18px; }
.report-heading { align-items: center; background: var(--ff-surface); }
.report-heading-main { min-width: 0; }
.report-heading-meta { display:flex; align-items:center; gap:16px; }
.report-confidence { min-width:150px; }
.inline-status { border-radius:var(--ff-radius-md); }
.prediction-state-panel { min-height:58px; background:var(--ff-surface); box-shadow:var(--ff-shadow-sm); }
.state-icon { flex:0 0 30px; width:30px; height:30px; display:grid; place-items:center; border-radius:50%; color:var(--ff-primary); background:var(--ff-primary-soft); }
.state-copy { display:flex; min-width:0; flex:1; flex-direction:column; gap:4px; }
.state-copy strong { color:var(--ff-text-strong); }
.state-copy span { color:var(--ff-text-muted); line-height:1.5; }
.decision-card { border:1px solid var(--ff-border); border-radius:var(--ff-radius-lg); background:var(--ff-surface); }
.decision-card :deep(.el-card__body) { padding:22px 24px 20px; }
.decision-card-head { display:flex; align-items:flex-start; justify-content:space-between; gap:20px; margin-bottom:16px; }
.section-eyebrow { color:var(--ff-primary); font-size:11px; font-weight:700; letter-spacing:.08em; }
.decision-card-head h2 { margin:6px 0 5px; color:var(--ff-text-strong); font-size:clamp(28px,4vw,42px); letter-spacing:-.05em; }
.decision-card-head p { margin:0; color:var(--ff-text-muted); line-height:1.6; }
.decision-actions { display:flex; align-items:center; gap:8px; flex:0 0 auto; }
.favorite-action { border-color:var(--ff-primary); color:var(--ff-primary); }
.decision-lead { display:flex; align-items:center; gap:12px; padding:13px 15px; border-radius:var(--ff-radius-md); margin-bottom:18px; }
.decision-lead-icon { display:grid; place-items:center; width:42px; height:42px; border-radius:50%; background:rgba(255,255,255,.62); }
.decision-lead > div:last-child { display:flex; min-width:0; flex-direction:column; gap:3px; }
.decision-lead strong { color:inherit; font-size:16px; }
.decision-lead span { color:inherit; opacity:.78; font-size:12px; line-height:1.5; }
.decision-notice { display:flex; align-items:center; gap:8px; padding:11px 13px; margin:4px 0 16px; border-radius:var(--ff-radius-md); font-size:12px; line-height:1.5; }
.decision-notice strong { white-space:nowrap; }
.decision-notice > span:last-child { color:var(--ff-text-muted); }
.notice-dot { width:7px; height:7px; flex:0 0 7px; border-radius:50%; background:currentColor; }
.notice-positive { color:var(--ff-primary); background:var(--ff-primary-soft); }
.notice-caution { color:#8b6117; background:#f8f0e2; }
.advanced-panels { border-top:1px solid var(--ff-border); }
.advanced-panels :deep(.el-collapse-item__header) { color:var(--ff-text); font-weight:700; background:transparent; }
.advanced-panels :deep(.el-collapse-item__wrap) { background:transparent; }
.advanced-panels :deep(.el-collapse-item__content) { padding-bottom:8px; }
.advanced-panels .model-quality-panel { margin-top:0; box-shadow:none; }
.match-info-card { border-radius:var(--ff-radius-lg); }
.match-info-card.is-loading { border-color: var(--ff-border-strong); }
.match-info-card.is-loading .match-detail { opacity: .72; }
.match-info-card .team-block small { color:var(--ff-primary); font-size:11px; }
.action-area { padding:4px 0 12px; }
.action-buttons { display:flex; justify-content:center; align-items:center; gap:10px; }
.predict-btn { width:auto; min-width:220px; }
.login-tip { color:var(--ff-text-muted); }

@media (max-width: 768px) {
  .report-heading-meta { width:100%; align-items:flex-start; flex-direction:column; gap:10px; }
  .report-confidence { width:100%; min-width:0; }
  .state-copy span { flex-basis:auto; order:initial; }
  .decision-card :deep(.el-card__body) { padding:18px 14px 16px; }
  .decision-card-head { flex-direction:column; gap:14px; }
  .decision-actions { width:100%; }
  .favorite-action { flex:1; }
  .more-action { padding-inline:14px; }
  .decision-notice { align-items:flex-start; flex-wrap:wrap; }
  .decision-notice > span:last-child { flex-basis:100%; padding-left:15px; }
  .action-buttons { flex-direction:column; }
  .action-buttons .el-button, .predict-btn { width:100%; min-width:0; }
  .match-info-card .match-detail { padding:16px 0; }
}
</style>
