<template>
  <div class="prediction-page ff-page-shell">
    <AppTopNav
      title="ChenFootball"
      :subtitle="t('prediction.page.subtitle')"
      :brand-icon="Football"
      active-path="/matches"
    />

    <el-main id="app-main" class="main-content" tabindex="-1">
      <div class="prediction-inner">
        <section class="report-heading ff-appear">
          <div class="report-heading-main">
            <span class="ff-kicker">{{ pageKicker }}</span>
            <h1 v-if="fixtureNotFound">{{ t('prediction.page.notFoundTitle') }}</h1>
            <h1 v-else>{{ homeTeamDisplayName }} <em>vs</em> {{ awayTeamDisplayName }}</h1>
            <p v-if="fixtureNotFound">{{ t('prediction.page.notFoundHint') }}</p>
            <p v-else>{{ leagueDisplayName || t('prediction.page.analysisFallback') }} · {{ formatDate(fixtureData?.fixture?.date) || t('prediction.page.timeTbd') }}</p>
          </div>
          <div class="report-heading-meta">
            <el-tag :type="statusType" size="small">{{ fixtureStatusLabel }}</el-tag>
            <div class="report-confidence">
              <span>{{ t('prediction.page.probabilityMargin') }}</span>
              <strong>{{ confidenceValue }}</strong>
              <small>{{ confidenceLabel }}</small>
              <el-tag v-if="coverageBadge" class="coverage-badge" size="small" :type="coverageBadge.type" effect="plain">{{ coverageBadge.text }}</el-tag>
              <small v-if="dataFreshnessLabel" class="freshness-label">{{ dataFreshnessLabel }}</small>
            </div>
          </div>
        </section>

        <PageState
          v-if="fixtureNotFound"
          :title="t('prediction.page.notFoundTitle')"
          :description="t('prediction.page.notFoundDescription')"
          :action-text="t('prediction.page.backToMatches')"
          @action="backToMatches"
        />
        <div v-else-if="loading && !predictionResult && !matchLoadError" class="prediction-skeleton" role="status" aria-live="polite" aria-busy="true" :aria-label="t('prediction.page.loadingAria')">
          <PageState type="loading" :title="t('prediction.page.loadingTitle')" :description="t('prediction.page.loadingDescription')" :size="32" />
          <div class="prediction-skeleton-card" aria-hidden="true">
            <i class="sk-line sk-wide"></i>
            <div class="sk-prob-grid"><i></i><i></i><i></i></div>
            <i class="sk-line sk-mid"></i>
          </div>
        </div>
        <PageState
          v-else-if="matchLoadError && !predictionResult"
          type="error"
          :title="t('prediction.page.loadFailedTitle')"
          :description="matchLoadError"
          :action-text="t('prediction.page.retry')"
          @action="loadMatch"
        />
        <PageState
          v-else-if="!predictionResult && predictionStatus === 'LOADING'"
          type="loading"
          :title="predictionStatusTitle"
          :description="predictionStatusDescription"
          :size="32"
        />
        <PageState
          v-else-if="!predictionResult && ['ERROR', 'FAILED', 'TIMEOUT'].includes(predictionStatus)"
          type="error"
          :title="predictionStatusTitle"
          :description="predictionStatusDescription"
          :action-text="t('prediction.page.recheck')"
          @action="doPredict"
        />
        <PageState
          v-else-if="!predictionResult && predictionStatus === 'UNAVAILABLE'"
          :title="t('prediction.page.noPredictionTitle')"
          :description="predictionStatusDescription"
          :action-text="t('prediction.page.viewOtherMatches')"
          @action="backToMatches"
        />
        <PageState
          v-else-if="!predictionResult && predictionStatus === 'PENDING'"
          type="loading"
          :title="predictionStatusTitle"
          :description="predictionStatusDescription"
          :size="32"
        />

        <el-card v-if="predictionResult" class="decision-card" shadow="never">
          <div class="decision-card-head">
            <div>
              <span class="section-eyebrow">{{ isFinished ? t('prediction.page.reviewKicker') : t('prediction.page.conclusionKicker') }}</span>
              <h2>{{ resultLabelShort }}</h2>
              <p>{{ primaryDecisionText }}</p>
            </div>
            <div class="decision-actions">
              <el-button class="favorite-action" :loading="favoriteLoading" :icon="Star" :type="isFavorite ? 'success' : 'default'" plain @click="toggleFavorite">{{ isFavorite ? t('prediction.page.favorited') : t('prediction.page.favorite') }}</el-button>
              <el-dropdown trigger="click" @command="handleDecisionCommand">
                <el-button text class="more-action" :aria-label="t('prediction.page.moreActions')">{{ t('prediction.page.more') }}</el-button>
                <template #dropdown>
                  <el-dropdown-menu>
                    <el-dropdown-item command="share"><el-icon><Share /></el-icon>{{ t('prediction.page.shareReport') }}</el-dropdown-item>
                    <el-dropdown-item command="agent"><el-icon><ChatLineSquare /></el-icon>{{ t('prediction.page.askAgent') }}</el-dropdown-item>
                    <el-dropdown-item v-if="!isFinished" command="reminder"><el-icon><Bell /></el-icon>{{ reminderSet ? t('prediction.page.disableReminder') : t('prediction.page.enableReminder') }}</el-dropdown-item>
                  </el-dropdown-menu>
                </template>
              </el-dropdown>
            </div>
          </div>

          <div class="decision-lead" :class="resultClass">
            <div class="decision-lead-icon"><el-icon :size="28"><component :is="outcomeIcon" /></el-icon></div>
            <div><strong>{{ isFinished ? actualResultLabel || t('prediction.page.matchEnded') : t('prediction.page.modelLean', { result: resultLabelShort }) }}</strong><span>{{ confidenceLabel }} · {{ predictionGeneratedAt ? t('prediction.page.generatedAt', { date: formatDate(predictionGeneratedAt) }) : t('prediction.page.unifiedSnapshot') }}</span></div>
          </div>

          <PredictionProbabilityPanel
            :home-name="homeTeamDisplayName"
            :away-name="awayTeamDisplayName"
            :probabilities="normalizedProbabilities"
            :warning="predictionHasWarning || dataInsufficient"
            :hint="probabilityHint"
            :home-color="homeColor"
            :draw-color="drawColor"
            :away-color="awayColor"
          />

          <div v-if="isFinished && actualResultLabel" class="review-summary" :class="predictionHit === true ? 'review-hit' : predictionHit === false ? 'review-miss' : ''">
            <div><span>{{ t('prediction.page.actualResult') }}</span><strong>{{ actualResultLabel }}</strong></div>
            <div><span>{{ t('prediction.page.prematchPrediction') }}</span><strong>{{ resultLabelShort }}</strong></div>
            <el-tag v-if="predictionHit !== null" :type="predictionHit ? 'success' : 'danger'" size="small">{{ predictionHit ? t('prediction.page.predictionHit') : t('prediction.page.predictionMiss') }}</el-tag>
          </div>

          <div class="decision-notice" :class="predictionHasWarning ? 'notice-caution' : 'notice-positive'">
            <span class="notice-dot"></span><strong>{{ predictionQualityTitle }}</strong><span>{{ riskSummary.join(' · ') }}</span>
          </div>

          <div v-if="prematchQuality" class="prematch-quality-panel" :aria-label="t('prediction.page.prematchCoverageAria')">
            <div class="quality-panel-head"><div><strong>{{ t('prediction.page.prematchCoverageTitle') }}</strong><small>{{ prematchQuality.source || t('prediction.prematch.sourceFallback') }} · {{ prematchQuality.updatedAt || t('prediction.prematch.updatedUnknown') }}</small></div><el-tag size="small" :type="prematchQualityType">{{ prematchQualityLabel }}</el-tag></div>
            <div class="prematch-quality-grid"><div v-for="item in prematchQuality.items" :key="item.key"><span>{{ item.label }}</span><strong>{{ item.value }}</strong></div></div>
          </div>

          <el-collapse v-model="advancedPanels" class="advanced-panels">
            <el-collapse-item v-if="modelQualityVisible" name="quality" :title="t('prediction.page.modelQualityTitle')">
              <PredictionModelQuality :quality="modelQuality" :visible="modelQualityVisible" :reliable="qualityIsReliable" />
            </el-collapse-item>
            <el-collapse-item v-if="featureContrib?.length" name="factors" :title="t('prediction.page.factorTitle')">
              <PredictionFeatureContribution :items="featureContrib" />
            </el-collapse-item>
          </el-collapse>
        </el-card>

        <!-- 比赛信息放在结论之后，避免用户先读技术细节。 -->
        <el-card v-if="!fixtureNotFound" class="match-info-card" shadow="hover" :class="{ 'is-loading': loading }">
          <template #header>
            <div class="card-header"><span>{{ t('prediction.page.matchInfo') }}</span><div class="header-actions"><el-tag v-if="loading" size="small" type="info">{{ t('prediction.page.reading') }}</el-tag><el-button size="small" text @click="showH2H"><el-icon><DataLine /></el-icon>{{ t('prediction.page.viewH2H') }}</el-button></div></div>
          </template>
          <div class="match-detail">
            <div class="team-block" role="button" tabindex="0" :aria-label="t('prediction.page.viewSquadAria', { team: homeTeamDisplayName })" @click="goTeamSquad(homeId, 'home')" @keydown.enter="goTeamSquad(homeId, 'home')" @keydown.space.prevent="goTeamSquad(homeId, 'home')"><img v-if="fixtureData?.teams?.home?.logo" :src="getMediaAssetUrl(fixtureData.teams.home.logo)" :alt="t('prediction.page.logoAlt', { team: homeTeamDisplayName })" class="big-logo" width="80" height="80" decoding="async" /><div v-else class="logo-placeholder">{{ homeTeamInitial }}</div><div class="team-name-lg">{{ homeTeamDisplayName }}</div><small>{{ t('prediction.page.viewSquad') }}</small></div>
            <div class="vs-block"><div v-if="hasScore" class="final-score">{{ fixtureData.goals.home }} - {{ fixtureData.goals.away }}</div><div class="vs-label">VS</div><div class="match-date">{{ formatDate(fixtureData?.fixture?.date) }}</div><div class="match-venue" v-if="fixtureData?.fixture?.venue?.name">{{ fixtureData.fixture.venue.name }}</div></div>
            <div class="team-block" role="button" tabindex="0" :aria-label="t('prediction.page.viewSquadAria', { team: awayTeamDisplayName })" @click="goTeamSquad(awayId, 'away')" @keydown.enter="goTeamSquad(awayId, 'away')" @keydown.space.prevent="goTeamSquad(awayId, 'away')"><img v-if="fixtureData?.teams?.away?.logo" :src="getMediaAssetUrl(fixtureData.teams.away.logo)" :alt="t('prediction.page.logoAlt', { team: awayTeamDisplayName })" class="big-logo" width="80" height="80" decoding="async" /><div v-else class="logo-placeholder">{{ awayTeamInitial }}</div><div class="team-name-lg">{{ awayTeamDisplayName }}</div><small>{{ t('prediction.page.viewSquad') }}</small></div>
          </div>
        </el-card>

          <!-- 历史交锋弹窗 -->
          <el-dialog v-model="h2hVisible" :title="t('prediction.page.h2hTitle')" width="min(650px, 94vw)">
            <div v-if="h2hLoading" class="loading-state">
              <el-skeleton :rows="5" animated />
            </div>
            <div v-else-if="h2hData" class="h2h-content">
              <div class="h2h-summary">
                <div class="summary-team home">
                  <img v-if="fixtureData?.teams?.home?.logo" :src="getMediaAssetUrl(fixtureData.teams.home.logo)" :alt="t('prediction.page.logoAlt', { team: homeTeamDisplayName })" class="summary-logo" />
                  <span>{{ homeTeamDisplayName }}</span>
                </div>
                <div class="summary-stats">
                  <div class="stat-box">
                    <span class="stat-num">{{ h2hData.summary?.homeWins || 0 }}</span>
                    <span class="stat-desc">{{ t('prediction.page.homeWins') }}</span>
                  </div>
                  <div class="stat-box">
                    <span class="stat-num">{{ h2hData.summary?.draws || 0 }}</span>
                    <span class="stat-desc">{{ t('prediction.page.draws') }}</span>
                  </div>
                  <div class="stat-box">
                    <span class="stat-num">{{ h2hData.summary?.awayWins || 0 }}</span>
                    <span class="stat-desc">{{ t('prediction.page.awayWins') }}</span>
                  </div>
                </div>
                <div class="summary-team away">
                  <img v-if="fixtureData?.teams?.away?.logo" :src="getMediaAssetUrl(fixtureData.teams.away.logo)" :alt="t('prediction.page.logoAlt', { team: awayTeamDisplayName })" class="summary-logo" />
                  <span>{{ awayTeamDisplayName }}</span>
                </div>
              </div>
              <div class="h2h-matches">
                <div v-for="(match, idx) in h2hData.recentMatches" :key="idx" class="h2h-match">
                  <span class="h2h-date">{{ match.date }}</span>
                  <span class="h2h-teams">{{ match.homeTeam }} vs {{ match.awayTeam }}</span>
                  <span class="h2h-score">{{ match.homeScore }} - {{ match.awayScore }}</span>
                </div>
                <div v-if="!h2hData.recentMatches?.length" class="h2h-empty">
                  {{ t('prediction.page.noH2H') }}
                </div>
              </div>
            </div>
          </el-dialog>

          <!-- 页面底部操作：只保留一个主要动作，避免反复点击误解。 -->
          <div v-if="!fixtureNotFound" class="action-area">
            <div class="action-buttons">
              <el-button v-if="predictionResult" type="primary" size="large" :loading="predicting" class="predict-btn" @click="doPredict"><el-icon><RefreshRight /></el-icon>{{ t('prediction.page.checkLatest') }}</el-button>
              <el-button v-else type="primary" size="large" :loading="predicting" class="predict-btn" @click="predictionStatus === 'UNAVAILABLE' ? backToMatches() : doPredict"><el-icon><TrendCharts /></el-icon>{{ statusActionLabel }}</el-button>
              <el-button size="large" plain @click="backToMatches">{{ t('prediction.page.backToMatches') }}</el-button>
            </div>
            <div class="login-tip">{{ t('prediction.page.loginTipPrefix') }}{{ isLoggedIn ? t('prediction.page.loginTipLoggedIn') : t('prediction.page.loginTipGuest') }}</div>
          </div>

        </div>
    </el-main>
      <AppFooter />
  </div>
</template>

<script setup>
import { ref, computed, watch, onMounted, onUnmounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { analyticsApi, crawlerApi, favoriteApi, predictionApi } from '../../api'
import { useUserStore } from '../../stores/user'
import { normalizeProbability, normalizeProbabilities, parseFeatureString } from '../../utils/prediction'
import { getMediaAssetUrl } from '../../utils/mediaAsset'
import { ElMessage, ElMessageBox } from 'element-plus'
import AppFooter from '../../components/layout/AppFooter.vue'
import PageState from '../../components/layout/PageState.vue'
import AppTopNav from '../../components/layout/AppTopNav.vue'
import PredictionProbabilityPanel from '../../components/prediction/PredictionProbabilityPanel.vue'
import PredictionModelQuality from '../../components/prediction/PredictionModelQuality.vue'
import PredictionFeatureContribution from '../../components/prediction/PredictionFeatureContribution.vue'
import { Bell, ChatLineSquare, CircleCheck, DataLine, Football, RefreshRight, Share, Star, TrendCharts, WarningFilled } from '@element-plus/icons-vue'
import { displayLeagueName, displayTeamName } from '../../utils/teamNames'
import { useI18n } from '../../i18n'

const { t, locale } = useI18n()
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
    home: { id: homeId, name: queryText(route.query.homeName || route.query.home), logo: queryText(route.query.homeLogo), teamDisplayName: queryText(route.query.homeTeamDisplayName || route.query.homeDisplayName) },
    away: { id: awayId, name: queryText(route.query.awayName || route.query.away), logo: queryText(route.query.awayLogo), teamDisplayName: queryText(route.query.awayTeamDisplayName || route.query.awayDisplayName) }
  }
})
const teamDisplay = side => {
  const team = fixtureData.value?.teams?.[side] || {}
  return team.teamDisplayName || team.teamDisplay || team.displayName || team.display || fixtureData.value?.[`${side}TeamDisplayName`] || ''
}
const homeTeamDisplayName = computed(() => displayTeamName(fixtureData.value?.teams?.home?.name || t('prediction.common.homeTeam'), teamDisplay('home')))
const awayTeamDisplayName = computed(() => displayTeamName(fixtureData.value?.teams?.away?.name || t('prediction.common.awayTeam'), teamDisplay('away')))
const leagueDisplayName = computed(() => displayLeagueName(
  fixtureData.value?.league?.name || queryText(route.query.leagueName),
  fixtureData.value?.league?.leagueDisplay || fixtureData.value?.league?.leagueDisplayName || fixtureData.value?.league?.displayName || fixtureData.value?.leagueDisplay || queryText(route.query.leagueDisplay || route.query.leagueDisplayName)
))
const homeTeamInitial = computed(() => homeTeamDisplayName.value.trim().slice(0, 1).toUpperCase())
const awayTeamInitial = computed(() => awayTeamDisplayName.value.trim().slice(0, 1).toUpperCase())
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

const predictionStatusTitle = computed(() => t({
  LOADING: 'prediction.status.loading',
  PENDING: 'prediction.status.pending',
  UNAVAILABLE: 'prediction.status.unavailable',
  EMPTY: 'prediction.status.empty',
  ERROR: 'prediction.status.error',
  FAILED: 'prediction.status.failed',
  TIMEOUT: 'prediction.status.timeout'
}[predictionStatus.value] || 'prediction.status.unknown'))
const predictionStatusDescription = computed(() => {
  if (fixtureNotFound.value) return t('prediction.page.notFoundDescription')
  if (predictionStatus.value === 'PENDING') return t('prediction.statusDesc.pending')
  if (predictionStatus.value === 'UNAVAILABLE' || predictionStatus.value === 'EMPTY') return matchLoadError.value || t('prediction.statusDesc.unavailable')
  if (predictionStatus.value === 'TIMEOUT') return t('prediction.statusDesc.timeout')
  if (predictionStatus.value === 'ERROR' || predictionStatus.value === 'FAILED') return matchLoadError.value || t('prediction.statusDesc.error')
  return t('prediction.statusDesc.loading')
})

const sampleCoverage = computed(() => {
  const meta = predictionResult.value?.featureMeta || {}
  const home = Number(meta.homeSampleSize)
  const away = Number(meta.awaySampleSize)
  return {
    home: Number.isFinite(home) ? home : null,
    away: Number.isFinite(away) ? away : null
  }
})
const prematchUnconfigured = computed(() => {
  const snapshot = predictionResult.value?.featureMeta?.prematchSnapshot
  const quality = snapshot?.dataQuality && typeof snapshot.dataQuality === 'object' ? snapshot.dataQuality : null
  if (!quality) return false
  return ['injuries', 'lineups', 'xgShots'].some(key => String(quality[key] || '').toUpperCase() === 'NOT_CONFIGURED')
})
const dataInsufficient = computed(() => {
  if (!predictionResult.value) return false
  const status = String(predictionResult.value.featureStatus || '').toUpperCase()
  if (status === 'DEFAULTED' || predictionResult.value.featureComplete === false && status !== 'LIMITED') return true
  const { home, away } = sampleCoverage.value
  if (home != null && away != null && (home + away) <= 3) return true
  if ((home != null && home === 0) || (away != null && away === 0)) return true
  if (prematchUnconfigured.value && (home == null || home < 3 || away == null || away < 3)) return true
  return false
})
const coverageBadge = computed(() => {
  if (!predictionResult.value) return null
  const { home, away } = sampleCoverage.value
  if (home != null && away != null) {
    const total = home + away
    if (total <= 3 || home === 0 || away === 0) {
      return { type: 'warning', text: t('prediction.coverage.samplesInsufficient', { home, away }) }
    }
    if (home < 3 || away < 3 || String(predictionResult.value.featureStatus || '').toUpperCase() === 'LIMITED') {
      return { type: 'info', text: t('prediction.coverage.samplesLimited', { home, away }) }
    }
    return { type: 'success', text: t('prediction.coverage.samples', { home, away }) }
  }
  if (prematchUnconfigured.value) return { type: 'warning', text: t('prediction.coverage.prematchUnconfigured') }
  if (predictionResult.value.featureComplete === false) return { type: 'warning', text: t('prediction.coverage.featureIncomplete') }
  return null
})
const dataFreshnessLabel = computed(() => {
  const meta = predictionResult.value?.featureMeta || {}
  const snapshot = meta.prematchSnapshot || {}
  const stamp = snapshot.dataFetchedAt || snapshot.fetchedAt || meta.featureAsOf || predictionGeneratedAt.value
  if (!stamp) return ''
  return t('prediction.freshness', { date: formatDate(stamp) })
})
const probabilityHint = computed(() => {
  if (dataInsufficient.value) return t('prediction.probabilityHint.insufficient')
  if (predictionHasWarning.value) return t('prediction.probabilityHint.warning')
  return t('prediction.probabilityHint.official')
})
const predictionQualityTitle = computed(() => {
  if (!predictionResult.value) return ''
  if (isFinished.value && predictionHit.value !== null) {
    return predictionHit.value ? t('prediction.qualityTitle.hit') : t('prediction.qualityTitle.miss')
  }
  if (dataInsufficient.value) {
    return t('prediction.qualityTitle.insufficient')
  }
  if (!normalizedProbabilities.value.valid) {
    return t('prediction.qualityTitle.normalized')
  }
  if (predictionResult.value.featureComplete === false) {
    return predictionResult.value.featureStatus === 'DEFAULTED'
      ? t('prediction.qualityTitle.defaulted')
      : predictionResult.value.featureStatus === 'LIMITED'
        ? t('prediction.qualityTitle.limited')
      : t('prediction.qualityTitle.partial')
  }
  const margin = Number(predictionResult.value.decisionMargin ?? predictionResult.value.featureMeta?.decisionMargin)
  if (Number.isFinite(margin) && margin < 0.08) {
    return t('prediction.qualityTitle.close')
  }
  const accuracy = Number(predictionResult.value.featureMeta?.model_accuracy)
  if (Number.isFinite(accuracy) && accuracy < 0.55) {
    return t('prediction.qualityTitle.lowAccuracy', { result: resultLabelShort.value, accuracy: (accuracy * 100).toFixed(1) })
  }
  return t('prediction.qualityTitle.generated', { result: resultLabelShort.value })
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
const qualityStatusKeys = {
  AVAILABLE: 'prediction.qualityStatus.available',
  EMPTY: 'prediction.qualityStatus.empty',
  NOT_CONFIGURED: 'prediction.qualityStatus.notConfigured',
  UNSUPPORTED: 'prediction.qualityStatus.unsupported',
  REQUEST_FAILED: 'prediction.qualityStatus.requestFailed'
}
const qualityStatusText = code => qualityStatusKeys[code]
  ? t(qualityStatusKeys[code])
  : t('prediction.qualityStatus.unknown')
const prematchQuality = computed(() => {
  const snapshot = modelQuality.value?.prematchSnapshot
  if (!snapshot || typeof snapshot !== 'object') return null
  const quality = snapshot.dataQuality && typeof snapshot.dataQuality === 'object' ? snapshot.dataQuality : {}
  const qualityStatusCode = key => String(quality[key] || 'NOT_CONFIGURED').toUpperCase()
  const fetchedAt = snapshot.dataFetchedAt || snapshot.fetchedAt
  const trackedKeys = ['injuries', 'lineups', 'xgShots']
  return {
    source: snapshot.source || quality.historySource,
    updatedAt: fetchedAt
      ? t('prediction.prematch.updatedAt', { date: formatDate(fetchedAt) })
      : snapshot.cutoffTime
        ? t('prediction.prematch.cutoffTime', { date: formatDate(snapshot.cutoffTime) })
        : t('prediction.prematch.updatedUnknown'),
    status: trackedKeys.some(key => String(quality[key] || '').toUpperCase() === 'REQUEST_FAILED') ? 'REQUEST_FAILED' : 'AVAILABLE',
    items: [
      { key: 'injuries', label: t('prediction.prematch.injuries'), status: qualityStatusCode('injuries'), value: qualityStatusText(qualityStatusCode('injuries')) },
      { key: 'lineups', label: t('prediction.prematch.lineups'), status: qualityStatusCode('lineups'), value: qualityStatusText(qualityStatusCode('lineups')) },
      { key: 'xgShots', label: t('prediction.prematch.xgShots'), status: qualityStatusCode('xgShots'), value: qualityStatusText(qualityStatusCode('xgShots')) }
    ]
  }
})
const prematchQualityLabel = computed(() => {
  if (!prematchQuality.value) return ''
  const available = prematchQuality.value.items.filter(item => item.status === 'AVAILABLE').length
  return available === prematchQuality.value.items.length
    ? t('prediction.prematch.complete')
    : t('prediction.prematch.available', { available, total: prematchQuality.value.items.length })
})
const prematchQualityType = computed(() => prematchQuality.value?.status === 'REQUEST_FAILED' ? 'danger' : 'info')
const riskSummary = computed(() => {
  const risks = []
  const accuracy = Number(predictionResult.value?.featureMeta?.model_accuracy)
  const margin = Number(predictionResult.value?.decisionMargin)
  if (predictionResult.value?.featureComplete === false) risks.push(predictionResult.value?.featureStatus === 'LIMITED' ? t('prediction.risk.limited') : t('prediction.risk.partial'))
  if (Number.isFinite(margin) && margin < 0.08) risks.push(t('prediction.risk.close'))
  if (Number.isFinite(accuracy) && accuracy < 0.55) risks.push(t('prediction.risk.weak'))
  if (modelQuality.value?.model_quality_gate?.eligible === false) risks.push(t('prediction.risk.gate'))
  if (prematchQuality.value) {
    const available = prematchQuality.value.items.filter(item => item.status === 'AVAILABLE').length
    if (available < prematchQuality.value.items.length) risks.push(t('prediction.risk.coverage', { available, total: prematchQuality.value.items.length }))
  }
  return risks.length ? risks : [t('prediction.risk.none')]
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
const actualResultCode = computed(() => {
  if (!hasScore.value) return ''
  const home = Number(fixtureData.value.goals.home)
  const away = Number(fixtureData.value.goals.away)
  return home > away ? 'HOME_WIN' : home < away ? 'AWAY_WIN' : 'DRAW'
})
const actualResultLabel = computed(() => {
  if (!actualResultCode.value) return ''
  if (actualResultCode.value === 'HOME_WIN') return t('prediction.result.homeWin')
  if (actualResultCode.value === 'AWAY_WIN') return t('prediction.result.awayWin')
  return t('prediction.result.draw')
})
const predictionHit = computed(() => {
  if (!actualResultCode.value || !predictionResult.value?.resultLabel) return null
  return actualResultCode.value === predictionResult.value.resultLabel
})
const fixtureStatusLabel = computed(() => {
  const long = fixtureData.value?.fixture?.status?.long
  return statusText(matchStatus.value, long)
})
const pageKicker = computed(() => isFinished.value ? t('prediction.page.reviewKicker') : t('prediction.page.conclusionKicker'))
const predictionCardTitle = computed(() => isFinished.value
  ? t('prediction.model.reviewTitle')
  : t('prediction.model.resultTitle', { model: modelDisplayName.value }))
const modelDisplayName = computed(() => {
  const version = String(predictionResult.value?.modelVersion || '').toLowerCase()
  if (version.includes('elo-calibrated')) return t('prediction.model.eloCalibrated')
  if (version.includes('hybrid')) return t('prediction.model.hybrid')
  if (version.includes('xgboost')) return t('prediction.model.xgboost')
  if (version.includes('poisson')) return t('prediction.model.poisson')
  if (version) return t('prediction.model.prematch')
  return t('prediction.model.default')
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
  if (r === 'HOME_WIN') return t('prediction.result.homeWin')
  if (r === 'AWAY_WIN') return t('prediction.result.awayWin')
  return t('prediction.result.draw')
})
const resultLabel = computed(() => {
  const r = predictionResult.value?.resultLabel
  if (r === 'HOME_WIN') return t('prediction.result.homeWinFull', { team: fixtureData.value?.teams?.home?.name })
  if (r === 'AWAY_WIN') return t('prediction.result.awayWinFull', { team: fixtureData.value?.teams?.away?.name })
  return t('prediction.result.drawFull')
})
const confidenceValue = computed(() => {
  if (!predictionResult.value) return 'N/A'
  if (dataInsufficient.value) return 'N/A'
  const margin = Number(predictionResult.value.decisionMargin ?? predictionResult.value.featureMeta?.decisionMargin)
  if (Number.isFinite(margin)) return `${Math.round(Math.max(0, margin) * 100)}%`
  const values = [predictionResult.value.homeWinProb, predictionResult.value.drawProb, predictionResult.value.awayWinProb]
    .map(normalizeProbability).filter(value => value !== null).sort((a, b) => b - a)
  return values.length > 1 ? `${Math.round((values[0] - values[1]) * 100)}%` : 'N/A'
})
const confidenceLabel = computed(() => {
  if (!predictionResult.value) return predictionStatus.value === 'LOADING'
    ? t('prediction.confidence.loading')
    : t('prediction.confidence.waiting')
  if (dataInsufficient.value) return t('prediction.confidence.insufficient')
  const label = predictionResult.value?.confidenceLabel || predictionResult.value?.featureMeta?.confidenceLabel || t('prediction.confidence.defaultLabel')
  return t('prediction.confidence.suffix', { label })
})
const statusActionLabel = computed(() => t({
  PENDING: 'prediction.action.pending',
  ERROR: 'prediction.action.reload',
  FAILED: 'prediction.action.reload',
  TIMEOUT: 'prediction.action.reload',
  UNAVAILABLE: 'prediction.action.viewOtherMatches'
}[predictionStatus.value] || 'prediction.action.viewStatus'))
const primaryDecisionText = computed(() => {
  if (isFinished.value) {
    if (predictionHit.value === true) return t('prediction.decision.hit')
    if (predictionHit.value === false) return t('prediction.decision.miss')
    return t('prediction.decision.ended')
  }
  if (dataInsufficient.value) return t('prediction.decision.insufficient')
  return t('prediction.decision.lean', { result: resultLabelShort.value, margin: confidenceValue.value })
})
const outcomeIcon = computed(() => {
  if (!isFinished.value) return TrendCharts
  return predictionHit.value === false ? WarningFilled : CircleCheck
})

const featureLabelKeys = {
  elo_diff: 'prediction.feature.eloDiff',
  win_rate_diff: 'prediction.feature.winRateDiff',
  goal_diff: 'prediction.feature.goalDiff',
  h2h_balance: 'prediction.feature.h2hBalance',
  rest_days_diff: 'prediction.feature.restDaysDiff',
  home_elo: 'prediction.feature.homeElo',
  away_elo: 'prediction.feature.awayElo',
  home_win_rate: 'prediction.feature.homeWinRate',
  away_win_rate: 'prediction.feature.awayWinRate',
  home_avg_goals: 'prediction.feature.homeAvgGoals',
  away_avg_goals: 'prediction.feature.awayAvgGoals',
  home_avg_loss: 'prediction.feature.homeAvgLoss',
  away_avg_loss: 'prediction.feature.awayAvgLoss',
  home_days_rest: 'prediction.feature.homeDaysRest',
  away_days_rest: 'prediction.feature.awayDaysRest',
  h2h_home_wins: 'prediction.feature.h2hHomeWins',
  h2h_draws: 'prediction.feature.h2hDraws',
  h2h_away_wins: 'prediction.feature.h2hAwayWins',
  homeWinProb: 'prediction.feature.homeWinProb',
  drawProb: 'prediction.feature.drawProb',
  awayWinProb: 'prediction.feature.awayWinProb',
  modelVersion: 'prediction.feature.modelVersion'
}
const featureLabel = key => featureLabelKeys[key] ? t(featureLabelKeys[key]) : key

const featureContrib = computed(() => {
  if (!predictionResult.value) return null
  const topFeatures = Array.isArray(predictionResult.value.topFeatures) ? predictionResult.value.topFeatures : []

  const parsedTopFeatures = topFeatures.map((item, index) => {
    const parsed = typeof item === 'string' ? parseFeatureString(item) : null
    if (parsed) {
      const feature = parsed.feature || `feature_${index + 1}`
      const label = featureLabelKeys[feature] ? featureLabel(feature) : (parsed.label || feature)
      const importance = Number(parsed.importance ?? 0)
      const valueText = parsed.value !== undefined ? `${parsed.value}${parsed.unit ? ` ${parsed.unit}` : ''}` : `${importance || Math.max(0, 100 - index * 18)}%`
      return {
        feature,
        label,
        importance: Number.isFinite(importance) && importance > 0 ? importance : null,
        detail: parsed.value !== undefined
          ? t('prediction.feature.currentValue', { label, value: `${parsed.value}${parsed.unit ? ` ${parsed.unit}` : ''}` })
          : t('prediction.feature.rankOnly', { label }),
        valueText,
        percent: 0,
      }
    }

    if (typeof item === 'string') {
      const label = featureLabelKeys[item] ? featureLabel(item) : item
      return {
        feature: item,
        label,
        importance: null,
        detail: t('prediction.feature.rankOnly', { label }),
        valueText: t('prediction.feature.rank', { rank: index + 1 }),
        percent: 0,
      }
    }

    const feature = item?.feature || item?.name || item?.label || `feature_${index + 1}`
    const label = featureLabelKeys[feature] ? featureLabel(feature) : (item?.label || feature)
    const importance = Number(item?.importance ?? item?.value ?? 0)
    const unit = item?.unit ? String(item.unit) : ''
    return {
      feature,
      label,
      importance: Number.isFinite(importance) && importance > 0 ? importance : null,
      detail: item?.value !== undefined
        ? t('prediction.feature.currentValue', { label, value: `${item.value}${unit ? ` ${unit}` : ''}` })
        : t('prediction.feature.rankOnly', { label }),
      valueText: item?.value !== undefined ? String(item.value) : (importance > 0 ? `${importance}` : t('prediction.feature.rank', { rank: index + 1 })),
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

const statusText = (status, fallback = '') => {
  const key = {
    NS: 'prediction.status.notStarted',
    LIVE: 'prediction.status.live',
    '1H': 'prediction.status.firstHalf',
    HT: 'prediction.status.halfTime',
    '2H': 'prediction.status.secondHalf',
    FT: 'prediction.status.finished',
    CANCEL: 'prediction.status.canceled',
    POSTP: 'prediction.status.postponed'
  }[String(status || '').toUpperCase()]
  return key ? t(key) : (fallback || t('prediction.status.notStarted'))
}

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
    league: {
      id: raw.leagueId || '',
      name: raw.leagueName || raw.league?.name || '',
      round: raw.round || '',
      leagueDisplay: raw.leagueDisplay || raw.leagueDisplayName || raw.league?.leagueDisplay || raw.league?.displayName || '',
      displayName: raw.leagueDisplay || raw.leagueDisplayName || raw.league?.displayName || ''
    },
    teams: {
      home: {
        id: raw.homeTeamId || '',
        name: raw.homeTeamName || raw.teams?.home?.name || t('prediction.common.homeTeam'),
        logo: raw.homeTeamLogo || raw.teams?.home?.logo || '',
        teamDisplayName: raw.homeTeamDisplayName || raw.homeTeamDisplay || raw.teams?.home?.teamDisplayName || raw.teams?.home?.displayName || ''
      },
      away: {
        id: raw.awayTeamId || '',
        name: raw.awayTeamName || raw.teams?.away?.name || t('prediction.common.awayTeam'),
        logo: raw.awayTeamLogo || raw.teams?.away?.logo || '',
        teamDisplayName: raw.awayTeamDisplayName || raw.awayTeamDisplay || raw.teams?.away?.teamDisplayName || raw.teams?.away?.displayName || ''
      }
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
        home: {
          ...fixtureData.value?.teams?.home,
          name: home || fixtureData.value?.teams?.home?.name,
          logo: snapshot.homeTeamLogo || fixtureData.value?.teams?.home?.logo,
          teamDisplayName: snapshot.homeTeamDisplayName || snapshot.homeTeamDisplay || fixtureData.value?.teams?.home?.teamDisplayName
        },
        away: {
          ...fixtureData.value?.teams?.away,
          name: away || fixtureData.value?.teams?.away?.name,
          logo: snapshot.awayTeamLogo || fixtureData.value?.teams?.away?.logo,
          teamDisplayName: snapshot.awayTeamDisplayName || snapshot.awayTeamDisplay || fixtureData.value?.teams?.away?.teamDisplayName
        }
      },
      league: {
        ...fixtureData.value?.league,
        name: snapshot.leagueName || fixtureData.value?.league?.name,
        leagueDisplay: snapshot.leagueDisplay || snapshot.leagueDisplayName || fixtureData.value?.league?.leagueDisplay,
        displayName: snapshot.leagueDisplay || snapshot.leagueDisplayName || fixtureData.value?.league?.displayName
      }
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
      matchLoadError.value = reason ? t('prediction.page.insufficientReason', { reason }) : t('prediction.page.noPredictionRetry')
    }
    if (predictionStatus.value === 'PENDING') queuePredictionPolling()
    else if (predictionStatus.value !== 'LOADING') stopPredictionPolling()
  } catch (error) {
    if (requestId !== predictionRequestSequence) return
    if (error?.code === 'ERR_CANCELED' || error?.name === 'CanceledError') return
    predictionStatus.value = 'ERROR'
    if (!predictionResult.value) matchLoadError.value = t('prediction.page.predictionUnavailable')
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
        status: { short: 'NS', long: statusText('NS') },
        venue: { name: '' },
        date: queryText(route.query.matchTime)
      },
      league: {
        id: queryText(route.query.leagueId),
        name: queryLeague,
        round: queryText(route.query.round),
        leagueDisplay: queryText(route.query.leagueDisplay || route.query.leagueDisplayName),
        displayName: queryText(route.query.leagueDisplay || route.query.leagueDisplayName)
      },
      teams: {
        home: {
          ...homeTeam,
          id: homeId || homeTeam.id,
          name: homeTeam.name || '',
          logo: homeTeam.logo || queryText(route.query.homeLogo),
          teamDisplayName: homeTeam.teamDisplayName || queryText(route.query.homeTeamDisplayName || route.query.homeDisplayName)
        },
        away: {
          ...awayTeam,
          id: awayId || awayTeam.id,
          name: awayTeam.name || '',
          logo: awayTeam.logo || queryText(route.query.awayLogo),
          teamDisplayName: awayTeam.teamDisplayName || queryText(route.query.awayTeamDisplayName || route.query.awayDisplayName)
        }
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
          matchLoadError.value = t('prediction.page.matchDetailNotFound')
        }
      } catch (error) {
        if (controller.signal.aborted && sequence !== loadSequence) return
        if (!hasQueryContext) {
          fixtureNotFound.value = true
          predictionStatus.value = 'UNAVAILABLE'
          matchLoadError.value = error?.name === 'AbortError' ? t('prediction.page.matchDetailTimeout') : t('prediction.page.matchDetailFailed')
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
    if (predictionStatus.value === 'READY') ElMessage.success(t('prediction.page.loadedSuccess'))
    else if (predictionStatus.value === 'PENDING') ElMessage.info(t('prediction.page.generating'))
  } catch (e) {
    predictionStatus.value = 'ERROR'
    ElMessage.warning(t('prediction.page.unavailableRetry'))
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
      teamDisplayName: team.teamDisplayName || team.displayName || '',
      teamId: String(team.id || teamId || ''),
      logo: team.logo || '',
      league: fixtureData.value?.league?.name || queryText(route.query.leagueName, ''),
      leagueDisplay: fixtureData.value?.league?.leagueDisplay || fixtureData.value?.league?.displayName || leagueDisplayName.value,
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
    ElMessage.success(t('prediction.page.reminderClosed'))
    return
  }
  const matchTime = fixtureData.value?.fixture?.date || route.query.matchTime || ''
  if (!matchTime || !parseBusinessDate(matchTime)) {
    ElMessage.info(t('prediction.page.matchTimeMissing'))
    return
  }
  writeMatchReminders([...items.filter(item => String(item?.fixtureId || '') !== String(fixtureId)), {
    fixtureId: String(fixtureId),
    title: `${homeTeamDisplayName.value} vs ${awayTeamDisplayName.value}`,
    matchTime,
    notified: false
  }])
  reminderSet.value = true
  if (typeof Notification !== 'undefined' && Notification.permission === 'default') Notification.requestPermission().catch(() => {})
  ElMessage.success(t('prediction.page.reminderSet'))
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

const BROWSER_NOTIFY_DISMISS_KEY = 'football_browser_notify_dismissed'
const maybeAskBrowserNotifyAfterFavorite = async () => {
  if (typeof Notification === 'undefined') return
  if (Notification.permission !== 'default') return
  if (localStorage.getItem(BROWSER_NOTIFY_DISMISS_KEY) === '1') return
  try {
    await ElMessageBox.confirm(
      t('prediction.page.browserNotifyConfirm'),
      t('prediction.page.browserNotifyTitle'),
      {
        confirmButtonText: t('prediction.page.browserNotifyConfirmBtn'),
        cancelButtonText: t('prediction.page.browserNotifyCancelBtn'),
        type: 'info',
        distinguishCancelAndClose: true
      }
    )
    await Notification.requestPermission()
  } catch (error) {
    if (error === 'cancel') localStorage.setItem(BROWSER_NOTIFY_DISMISS_KEY, '1')
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
      ElMessage.success(t('prediction.page.favoriteRemoved'))
    } else {
      const home = homeTeamDisplayName.value
      const away = awayTeamDisplayName.value
      await favoriteApi.addMatch(fixtureId, `${home} vs ${away}`, {
        leagueName: fixtureData.value?.league?.name || queryText(route.query.leagueName, ''),
        matchTime: fixtureData.value?.fixture?.date || queryText(route.query.matchTime, '')
      })
      isFavorite.value = true
      ElMessage.success(t('prediction.page.favoriteAdded'))
      maybeAskBrowserNotifyAfterFavorite()
    }
  } catch (error) {
    ElMessage.error(error?.message || t('prediction.page.favoriteFailed'))
  } finally {
    favoriteLoading.value = false
  }
}

const shareReport = async () => {
  const title = t('prediction.page.shareReportTitle', { home: homeTeamDisplayName.value, away: awayTeamDisplayName.value })
  const url = window.location.href
  try {
    if (navigator.share) {
      await navigator.share({ title, text: t('prediction.page.shareText'), url })
    } else if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(url)
      ElMessage.success(t('prediction.page.reportLinkCopied'))
    } else {
      window.prompt(t('prediction.page.copyReportLink'), url)
    }
    analyticsApi.track('prediction_shared', { page: route.fullPath, entityType: 'match', entityId: fixtureId }).catch(() => {})
  } catch (error) {
    if (error?.name !== 'AbortError') ElMessage.info(t('prediction.page.shareCanceled'))
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
      contextSummary: t('prediction.page.contextSummary', {
        model: modelDisplayName.value,
        coverage: prematchQualityLabel.value || t('prediction.page.contextCoverageUnknown'),
        result: isFinished.value
          ? t('prediction.page.contextActualResult', { result: actualResultLabel.value || t('prediction.common.unknown') })
          : t('prediction.page.contextMatchNotFinished')
      })
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
  if (!date) return t('prediction.page.timeTbd')
  return new Intl.DateTimeFormat(locale.value === 'zh' ? 'zh-CN' : 'en-US', {
    timeZone: 'Asia/Shanghai',
    year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit'
  }).format(date) + t('prediction.page.beijingTime')
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
      home: {
        id: homeId,
        name: queryText(route.query.homeName || route.query.home),
        logo: queryText(route.query.homeLogo),
        teamDisplayName: queryText(route.query.homeTeamDisplayName || route.query.homeDisplayName)
      },
      away: {
        id: awayId,
        name: queryText(route.query.awayName || route.query.away),
        logo: queryText(route.query.awayLogo),
        teamDisplayName: queryText(route.query.awayTeamDisplayName || route.query.awayDisplayName)
      }
    }
  }
  await loadMatch()
  await loadFavoriteState()
  syncReminderState()
})

// Re-fetch the fixture and current prediction snapshot so server-localised
// display copy is refreshed after the language switch.
watch(locale, async () => {
  if (!pageMounted) return
  await loadMatch()
})

onUnmounted(() => {
  pageMounted = false
  document.removeEventListener('visibilitychange', handleVisibilityChange)
  stopPredictionPolling()
  matchAbortController?.abort()
})
</script>

<style scoped>
.prediction-page { min-height: 100vh; max-width: 100%; overflow-x: clip; }
.main-content { padding: 24px; max-width: 1240px; margin: 0 auto; width: 100%; min-width: 0; }
.prediction-inner { display: flex; flex-direction: column; gap: 16px; }
.report-heading { display:flex; justify-content:space-between; align-items:flex-end; gap:24px; padding:22px 24px; border:1px solid var(--ff-border); border-radius:var(--ff-radius-lg); background:var(--ff-surface); }
.report-heading h1 { margin:8px 0 5px; color:var(--ff-ink); font-size:clamp(24px,3vw,36px); letter-spacing:-.045em; }
.report-heading h1 em { color:var(--ff-primary); font-style:normal; font-size:.7em; margin:0 7px; }
.report-heading p { margin:0; color:var(--ff-text-muted); }
.report-confidence { min-width:130px; padding-left:18px; border-left:1px solid var(--ff-border); display:flex; flex-direction:column; gap:3px; }
.report-confidence span,.report-confidence small { color:var(--ff-text-muted); font-size:12px; }
.report-confidence strong { color:var(--ff-primary); font:700 30px/1 var(--ff-mono); }
.coverage-badge { margin-top:6px; width:fit-content; }
.freshness-label { display:block; margin-top:2px; }
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

.inline-status-row { display:flex; align-items:center; gap:10px; flex-wrap:wrap; }
.inline-status-row .inline-status { flex:1; min-width:220px; margin:0; }

@media (max-width: 480px) {
  .main-content { padding: 14px 12px; }
  .big-logo, .logo-placeholder { width: 56px; height: 56px; }
  .team-name-lg { max-width: 88px; font-size: 13px; }
  .final-score { font-size: 28px; }
  .match-detail { padding: 12px 0; gap: 8px; }
  .prediction-inner :deep(.state-shell) { padding: 14px 12px; }
}

.prediction-skeleton { display:flex; flex-direction:column; gap:12px; }
.prediction-skeleton-card { padding:16px; border:1px solid var(--ff-border); border-radius:var(--ff-radius-lg); background:var(--ff-surface); }
.prediction-skeleton-card .sk-line { display:block; height:14px; width:70%; margin:0 0 12px; border-radius:8px; background:linear-gradient(90deg,#e8ece9 25%,#f3f5f3 37%,#e8ece9 63%); background-size:400% 100%; animation:predSk 1.2s ease-in-out infinite; }
.prediction-skeleton-card .sk-wide { width:86%; height:20px; }
.prediction-skeleton-card .sk-mid { width:46%; }
.sk-prob-grid { display:grid; grid-template-columns:repeat(3,minmax(0,1fr)); gap:10px; margin:4px 0 10px; }
.sk-prob-grid i { display:block; height:64px; border-radius:10px; background:linear-gradient(90deg,#e8ece9 25%,#f3f5f3 37%,#e8ece9 63%); background-size:400% 100%; animation:predSk 1.2s ease-in-out infinite; }
@keyframes predSk { from { background-position:100% 0; } to { background-position:-100% 0; } }
@media (prefers-reduced-motion:reduce) {
  .prediction-skeleton-card .sk-line, .sk-prob-grid i { animation:none; }
}
@media (max-width:680px) { .sk-prob-grid { grid-template-columns:1fr; } }

</style>
