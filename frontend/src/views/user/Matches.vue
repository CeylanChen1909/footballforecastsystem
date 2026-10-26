<template>
  <div class="matches-page ff-page-shell">
    <AppTopNav
      title="ChenFootball"
      subtitle=""
      :brand-icon="Football"
      active-path="/matches"
    >
      <template #actions>
        <ChangelogButton />
      </template>
    </AppTopNav>

    <div v-if="onboardingVisible && (loading || matchCount > 0)" class="matches-onboarding" role="region" :aria-label="t('matches.onboardingAria')">
      <p class="matches-onboarding-copy">{{ t('matches.onboardingCopy') }}</p>
      <button type="button" class="matches-onboarding-dismiss" :aria-label="t('matches.onboardingDismissAria')" @click="dismissOnboarding">{{ t('matches.onboardingDismiss') }}</button>
    </div>

    <el-container class="matches-layout">
      <el-main id="app-main" class="main-content" tabindex="-1">
        <nav class="date-rail" :aria-label="t('matches.dateNavAria', { heading: matchesHeading })">
          <button type="button" class="date-rail-arrow" :disabled="loading" :aria-label="t('matches.previousDay')" :title="t('matches.previousDay')" @click="shiftDate(-1)">
            <el-icon><ArrowLeft /></el-icon>
          </button>
          <button v-for="item in dateRail" :key="item.date" type="button" class="date-rail-item" :class="{ active: item.date === (selectedDate || todayDate) }" :disabled="loading" @click="selectRailDate(item.date)">
            <span>{{ item.label }}</span><strong>{{ item.day }}</strong>
            <small class="rail-meta">{{ item.weekday }}<template v-if="countsReady"> · {{ item.count }}<span class="rail-unit">{{ ' ' + t('matches.matchCountUnit') }}</span></template><template v-else> · …</template></small>
          </button>
          <button type="button" class="date-rail-arrow" :disabled="loading" :aria-label="t('matches.nextDay')" :title="t('matches.nextDay')" @click="shiftDate(1)">
            <el-icon><ArrowRight /></el-icon>
          </button>
        </nav>

        <div class="matches-workspace">
          <!-- 比赛列表 owns the page. Focus is a mark on the row, not a second bay. -->
          <PageSection class="match-list-panel" variant="compact">
            <template #actions>
              <div class="match-list-actions sport-filters" :class="{ 'is-open': moreFilters }">
                <select v-model="selectedLeague" class="league-filter native-filter-select" :aria-label="t('matches.filterByLeague')">
                  <option v-for="option in leagueOptions" :key="option.value" :value="option.value">{{ option.label }}</option>
                </select>
                <input v-model="teamKeyword" class="team-filter native-filter-input" type="search" enterkeyhint="search" :placeholder="t('matches.searchTeamsPlaceholder')" :aria-label="t('matches.filterByTeam')" />
                <button type="button" class="filter-chip team-language-button" :aria-pressed="teamNameMode === 'en' ? 'true' : 'false'" :aria-label="teamNameMode === 'zh' ? t('matches.switchToEnglishNames') : t('matches.switchToChineseNames')" @click="toggleTeamNameMode">
                  {{ teamNameMode === 'zh' ? t('matches.teamNameEnglish') : t('matches.teamNameChinese') }}
                </button>
                <button type="button" class="filter-chip favorites-chip" :class="{ active: onlyFavorites }" :aria-pressed="onlyFavorites ? 'true' : 'false'" @click="onlyFavorites = !onlyFavorites">{{ t('matches.onlyFavorites') }}</button>
                <button type="button" class="filter-chip reminder-button" :class="{ active: remindersEnabled }" :aria-pressed="remindersEnabled ? 'true' : 'false'" :disabled="remindersChanging" @click="toggleMatchReminders">
                  <el-icon><Bell /></el-icon><span class="reminder-label">{{ remindersEnabled ? t('matches.remindersOn') : t('matches.reminders') }}</span>
                </button>
                <button type="button" class="filters-more" :aria-expanded="moreFilters ? 'true' : 'false'" @click="moreFilters = !moreFilters">{{ moreFilters ? t('matches.collapseFilters') : t('matches.filters') }}</button>
                <div class="match-count-tag" :aria-busy="loading ? 'true' : 'false'">
                  <span class="match-count-num">{{ loading ? '…' : matchCount }}</span>
                  <span class="match-count-unit">{{ t('matches.matchCountUnit') }}</span>
                </div>
              </div>
            </template>

            <div v-if="loading" class="matches-skeleton" role="status" aria-live="polite" aria-busy="true" :aria-label="t('matches.loadingMatchesAria')">
              <MatchCardSkeleton v-for="n in 6" :key="n" />
            </div>
            <PageState v-else-if="errorMsg" type="error" :title="errorMsg" :action-text="t('matches.retry')" @action="loadCurrentView" />
            <template v-else>
              <div v-if="filteredMatches.length > 0" class="date-group-list">
                <div v-for="group in groupedMatches" :key="group.date" class="date-group reveal">
                  <div v-for="lg in group.leagueGroups" :key="lg.name" class="league-group">
                    <div class="league-group-header">
                      <span class="league-name">{{ leagueLabel(lg.name, lg.displayName) }}</span>
                      <span class="league-count">{{ t('matches.matchCount', { count: lg.items.length }) }}</span>
                    </div>
                    <div class="matches-grid">
                      <MatchCard
                        v-for="m in lg.items"
                        :key="getMatchId(m)"
                        :match="m"
                        :team-name-mode="teamNameMode"
                        :favorited="isFavoritedMatch(getMatchId(m))"
                        :focused="isFocusMatch(m)"
                        @predict="goPredict"
                        @teamClick="goTeamSquad"
                        @h2h="showH2H"
                        @agent="openAgent"
                        @details="openMatchDetails"
                        @favorite-match="toggleMatchFavorite"
                      />
                    </div>
                  </div>
                </div>
              </div>
              <PageState v-else type="empty" :title="emptyStateTitle" :description="emptyStateDescription" :action-text="emptyStateActionText" @action="onEmptyStateAction" />
            </template>
          </PageSection>

        </div>
      </el-main>
    </el-container>

    <!-- 历史交锋弹窗 -->
    <el-dialog v-model="h2hVisible" :title="t('matches.h2hTitle')" width="min(600px, 94vw)">
      <div v-if="h2hLoading" class="loading-state">
        <el-skeleton :rows="5" animated />
      </div>
      <div v-else-if="h2hData" class="h2h-content">
        <div class="h2h-summary">
          <div class="summary-item">
            <span class="summary-value">{{ h2hData.summary?.homeWins || 0 }}</span>
            <span class="summary-label">{{ t('matches.homeWins') }}</span>
          </div>
          <div class="summary-item">
            <span class="summary-value">{{ h2hData.summary?.draws || 0 }}</span>
            <span class="summary-label">{{ t('matches.draws') }}</span>
          </div>
          <div class="summary-item">
            <span class="summary-value">{{ h2hData.summary?.awayWins || 0 }}</span>
            <span class="summary-label">{{ t('matches.awayWins') }}</span>
          </div>
        </div>
        <div class="h2h-matches">
          <div v-for="(match, idx) in h2hData.recentMatches" :key="idx" class="h2h-match">
            <span class="h2h-date">{{ match.date }}</span>
            <span class="h2h-score">{{ match.homeScore }} - {{ match.awayScore }}</span>
          </div>
          <div v-if="!h2hData.recentMatches?.length" class="h2h-empty">{{ t('matches.noH2H') }}</div>
        </div>
      </div>
    </el-dialog>

    <el-dialog v-model="contentVisible" :title="contentDialogTitle" width="min(820px, 94vw)">
      <div class="match-detail-tabs">
          <PageState v-if="detailsLoading" type="loading" :title="t('matches.loadingDetails')" :size="36" />
          <div v-else class="match-detail-content">
            <div class="detail-source-line">
              <span>{{ t('matches.dataSource', { source: matchDetails?.source || t('matches.notConnected') }) }}</span>
              <el-tag size="small" :type="detailStatusType">{{ detailStatusLabel }}</el-tag>
              <el-tag v-if="matchDetails?.fallback?.status === 'NORMAL'" size="small" type="info">{{ t('matches.fallbackSchedule') }}</el-tag>
              <el-button v-if="matchDetails?.refreshable" size="small" text :loading="detailsRefreshing" @click="refreshMatchDetails">{{ t('matches.refreshDetails') }}</el-button>
            </div>
            <el-alert v-if="detailStatus !== 'NORMAL'" :title="detailStatusText" type="warning" :closable="false" show-icon />
            <div v-if="detailEvents.length" class="event-timeline">
              <div v-for="(event, index) in detailEvents" :key="'event-' + index" class="event-row">
                <span class="event-minute">{{ formatEventMinute(event) }}</span>
                <span class="event-team">{{ displayTeamLabel(event.team) || t('matches.matchEvent') }}</span>
                <span class="event-icon">{{ eventIcon(event) }}</span>
                <span class="event-main">{{ event.player?.name || event.type || t('matches.event') }}</span>
                <small>{{ event.detail || event.assist?.name || '' }}</small>
              </div>
            </div>
            <div v-else class="detail-empty">{{ t('matches.noEvents') }}</div>

            <div v-if="detailStats.length" class="detail-section-block">
              <div class="detail-section-title">{{ t('matches.statistics') }}</div>
              <div class="detail-stat-grid">
                <div v-for="(row, index) in detailStats" :key="'stat-' + index" class="detail-stat-row">
                  <span>{{ row.label }}</span><strong>{{ row.home }}</strong><small>{{ row.type }}</small><strong>{{ row.away }}</strong>
                </div>
              </div>
            </div>

            <div class="detail-section-block prematch-info-block">
              <div class="detail-section-title">{{ t('matches.prematchInfo') }}</div>
              <div class="prematch-info-grid">
                <div><span>{{ t('matches.injuries') }}</span><strong>{{ prematchInjurySummary }}</strong></div>
                <div><span>{{ t('matches.lineup') }}</span><strong>{{ prematchLineupSummary }}</strong></div>
                <div><span>{{ t('matches.xgShots') }}</span><strong>{{ prematchStatsSummary }}</strong></div>
              </div>
              <small class="prematch-info-note">{{ t('matches.prematchNote') }}</small>
            </div>

            <div v-if="detailLineups.length" class="detail-section-block">
              <div class="detail-section-title">{{ t('matches.lineups') }}</div>
              <div class="lineup-grid">
                <div v-for="(lineup, index) in detailLineups" :key="'lineup-' + index" class="lineup-card">
                  <strong>{{ displayTeamLabel(lineup.team) || t('matches.team') }}</strong>
                  <span>{{ t('matches.formation', { formation: lineup.formation || t('matches.toBeAnnounced') }) }}</span>
                  <small>{{ t('matches.lineupSummary', { starters: lineup.startXI?.length || 0, subs: lineup.substitutes?.length || 0 }) }}</small>
                </div>
              </div>
            </div>
          </div>
      </div>
    </el-dialog>
      <AppFooter />
  </div>
</template>

<script setup>
import { ref, computed, nextTick, onMounted, onBeforeUnmount, watch, defineAsyncComponent } from 'vue'
import gsap from 'gsap'
import { useRoute, useRouter } from 'vue-router'
import { useUserStore } from '../../stores/user'
import { analyticsApi, crawlerApi, favoriteApi, matchApi, userApi } from '../../api'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowLeft, ArrowRight, Bell, Football } from '@element-plus/icons-vue'
import AppFooter from '../../components/layout/AppFooter.vue'
import AppTopNav from '../../components/layout/AppTopNav.vue'
import PageSection from '../../components/layout/PageSection.vue'
import PageState from '../../components/layout/PageState.vue'
import MatchCardSkeleton from '../../components/matches/MatchCardSkeleton.vue'
import MatchCard from '../../components/MatchCard.vue'
import { getBusinessDate, getLeagueDisplay, getTeamDisplay } from '../../utils/match'
import { displayLeagueName, displayTeamName, getTeamSearchTokens, normalizeTeamSearch, getLeagueDisplayName } from '../../utils/teamNames'
import { useMatchRecommendations } from '../../composables/useMatchRecommendations'
import { useI18n } from '../../i18n'

// Defer focus rail + changelog off the matches critical path (LCP: date rail + list/skeleton).
const MatchFocusRail = defineAsyncComponent(() => import('../../components/matches/MatchFocusRail.vue'))
const PredictionDiscovery = defineAsyncComponent(() => import('../../components/matches/PredictionDiscovery.vue'))
const ChangelogButton = defineAsyncComponent(() => import('../../components/matches/ChangelogButton.vue'))
void MatchFocusRail
void PredictionDiscovery

const router = useRouter()
const route = useRoute()
const userStore = useUserStore()
const { t, locale } = useI18n()
const loading = ref(false)
const errorMsg = ref('')
const dataQuality = ref({ status: 'UNKNOWN', statusText: '', message: '' })
const rawMatches = ref([])
const dateCountsCache = ref(new Map())
const { items: hotMatches, meta: hotMeta, loading: hotLoading, error: hotError, stale: hotStale, load: loadHotMatches } = useMatchRecommendations({ limit: 6 })
const selectedDate = ref('')
const currentPage = ref(1)
const matchFavorites = ref([])
const selectedLeague = ref('all')
const teamKeyword = ref('')
const TEAM_NAME_MODE_KEY = 'ff_team_name_mode'
// Fallbacks forwarded as route/API values when a provider omits a team name.
// These are request parameters, not display copy, so their values stay stable.
const ROUTE_HOME_FALLBACK = '主队'
const ROUTE_AWAY_FALLBACK = '客队'
// New key so old football_team_name_mode=en no longer wins; default zh. Do not migrate en.
const teamNameMode = ref(localStorage.getItem(TEAM_NAME_MODE_KEY) === 'en' ? 'en' : 'zh')
const ONBOARDING_KEY = 'football_matches_onboarding_dismissed_v1'
const FOCUS_COLLAPSE_KEY = 'football_matches_focus_collapsed'
const onboardingVisible = ref(false)
const focusCollapsed = ref(false)
const moreFilters = ref(false)
const countsReady = ref(false)
const dismissOnboarding = () => {
  onboardingVisible.value = false
  localStorage.setItem(ONBOARDING_KEY, '1')
}
const toggleFocusCollapsed = () => {
  focusCollapsed.value = !focusCollapsed.value
  localStorage.setItem(FOCUS_COLLAPSE_KEY, focusCollapsed.value ? '1' : '0')
}
const onlyFavorites = ref(false)
const remindersEnabled = ref(localStorage.getItem('football_match_reminders_enabled') === '1')
const remindersChanging = ref(false)
let reminderTimer = null
let focusTimer = null

// 历史交锋
const h2hVisible = ref(false)
const h2hLoading = ref(false)
const h2hData = ref(null)

const contentVisible = ref(false)
const contentMatch = ref(null)
const detailsLoading = ref(false)
const detailsRefreshing = ref(false)
const matchDetails = ref(null)
const contentDialogTitle = computed(() => {
  const match = contentMatch.value
  if (!match) return t('matches.matchDataTitle')
  return t('matches.matchDataTitleWithTeams', {
    home: matchTeamLabel(match, 'home') || t('matches.homeTeam'),
    away: matchTeamLabel(match, 'away') || t('matches.awayTeam')
  })
})
const detailEvents = computed(() => Array.isArray(matchDetails.value?.details?.events)
  ? matchDetails.value.details.events : [])
const detailLineups = computed(() => Array.isArray(matchDetails.value?.details?.lineups)
  ? matchDetails.value.details.lineups : [])
const detailStats = computed(() => {
  const rows = Array.isArray(matchDetails.value?.details?.statistics)
    ? matchDetails.value.details.statistics : []
  const byType = new Map()
  rows.forEach(teamBlock => {
    const team = teamBlock?.team?.name || t('matches.team')
    ;(teamBlock?.statistics || []).forEach(stat => {
      const type = stat?.type || t('matches.statisticType')
      const row = byType.get(type) || { type, label: type, home: '-', away: '-', assigned: 0 }
      if (row.assigned === 0) row.home = stat?.value ?? '-'
      else if (row.assigned === 1) row.away = stat?.value ?? '-'
      row.assigned += 1
      row.team = team
      byType.set(type, row)
    })
  })
  return [...byType.values()]
})
const prematchOddsSummary = computed(() => {
  const rows = Array.isArray(matchDetails.value?.details?.odds) ? matchDetails.value.details.odds : []
  const values = []
  rows.forEach(row => {
    const bookmakers = Array.isArray(row?.bookmakers) ? row.bookmakers : []
    bookmakers.forEach(bookmaker => (bookmaker?.bets || []).forEach(bet => (bet?.values || []).forEach(value => {
      const label = String(value?.value || '').toLowerCase()
      const odd = Number(value?.odd)
      if (odd > 1 && (label === 'home' || label === 'draw' || label === 'away' || label === '1' || label === 'x' || label === '2')) {
        values.push({ label, odd })
      }
    })))
  })
  const pick = key => values.find(item => item.label === key) || values.find(item => ({ home: '1', draw: 'x', away: '2' }[key]) === item.label)
  const home = pick('home')?.odd
  const draw = pick('draw')?.odd
  const away = pick('away')?.odd
  return home && draw && away ? `${home.toFixed(2)} / ${draw.toFixed(2)} / ${away.toFixed(2)}` : t('matches.none')
})
const prematchInjurySummary = computed(() => {
  const rows = Array.isArray(matchDetails.value?.details?.injuries) ? matchDetails.value.details.injuries : []
  return rows.length ? t('matches.injuryCount', { count: rows.length }) : (matchDetails.value?.statuses?.injuries === 'EMPTY' ? t('matches.noInjuries') : t('matches.toBeConfirmed'))
})
const prematchLineupSummary = computed(() => {
  if (!detailLineups.value.length) return matchDetails.value?.statuses?.lineups === 'EMPTY' ? t('matches.toBeAnnounced') : t('matches.toBeConfirmed')
  const starters = detailLineups.value.reduce((sum, lineup) => sum + (Array.isArray(lineup?.startXI) ? lineup.startXI.length : 0), 0)
  return t('matches.startingCount', { count: starters })
})
const prematchStatsSummary = computed(() => {
  const xg = detailStats.value.find(row => String(row.type).toLowerCase().includes('expected goals') || String(row.type).toLowerCase() === 'xg')
  const shots = detailStats.value.find(row => String(row.type).toLowerCase() === 'total shots')
  if (xg) return `${xg.home} / ${xg.away}`
  if (shots) return t('matches.shots', { home: shots.home, away: shots.away })
  return matchDetails.value?.statuses?.statistics === 'UNSUPPORTED' ? t('matches.planNotIncluded') : t('matches.none')
})
const detailStatus = computed(() => matchDetails.value?.dataStatus || 'UNKNOWN')
const detailStatusLabel = computed(() => {
  const status = detailStatus.value
  const key = `matches.dataStatus.${status}`
  const translated = t(key)
  if (translated !== key) return translated
  return matchDetails.value?.dataStatusText || t('matches.notChecked')
})
const detailStatusText = computed(() => {
  const key = {
    QUOTA_LIMITED: 'matches.statusQuotaLimitedText',
    NOT_CONFIGURED: 'matches.statusNotConfiguredText',
    PARTIAL: 'matches.statusPartialText',
    UNSUPPORTED: 'matches.statusUnsupportedText',
    EMPTY: 'matches.statusEmptyText'
  }[detailStatus.value] || 'matches.statusUnavailableText'
  return t(key)
})
const detailStatusType = computed(() => detailStatus.value === 'NORMAL' ? 'success' : detailStatus.value === 'EMPTY' || detailStatus.value === 'UNSUPPORTED' ? 'info' : 'warning')

const getMatchDate = (match) => match?.matchDate || String(match?.fixture?.date || '').slice(0, 10)
const formatDateLabel = (date) => {
  if (!date) return t('matches.dateUnknown')
  const today = formatLocalDate(new Date())
  const tomorrow = formatLocalDate(new Date(Date.now() + 86400000))
  const yesterday = formatLocalDate(new Date(Date.now() - 86400000))
  if (date === today) return t('matches.dateToday', { date })
  if (date === tomorrow) return t('matches.dateTomorrow', { date })
  if (date === yesterday) return t('matches.dateYesterday', { date })
  return date
}
const weekdayLabel = (date) => {
  if (!date) return '-'
  return new Intl.DateTimeFormat(locale.value === 'zh' ? 'zh-CN' : 'en-US', { timeZone: 'Asia/Shanghai', weekday: 'long' }).format(new Date(`${date}T00:00:00+08:00`))
}
const getLeagueName = (match) => getLeagueDisplayName(match?.league?.name || match?.leagueName || match?.leagueDisplayName || match?.league?.nameEn || t('matches.leagueOther'))
const displayTeamLabel = (team) => displayTeamName(
  team?.name || team?.teamName || '',
  team?.teamDisplayName || team?.displayName || team?.nameEn
)
const matchTeamLabel = (match, side) => displayTeamName(
  match?.[`${side}TeamName`] || match?.teams?.[side]?.name || '',
  getTeamDisplay(match, side)
)
// All new actions use the stable local matchId. fixture.id remains a provider
// compatibility field for legacy clients and must not be used as a route key.
const getMatchId = (match) => match?.matchId || match?.id || match?.fixture?.id || match?.fixtureId || match?.externalMatchId
// Fixed production list (match CompetitionHub) so 欧冠/欧国联 always appear
// even when the current date window only has UNL (or other) fixtures.
const PRODUCTION_LEAGUES = ['英超','西甲','意甲','德甲','法甲','荷甲','葡超','英冠','欧冠','欧国联']
// Chinese league names are canonical filter values and must stay unchanged;
// only their visible labels are translated.
const LEAGUE_LABEL_KEYS = {
  英超: 'matches.league.premierLeague',
  西甲: 'matches.league.laLiga',
  意甲: 'matches.league.serieA',
  德甲: 'matches.league.bundesliga',
  法甲: 'matches.league.ligue1',
  荷甲: 'matches.league.eredivisie',
  葡超: 'matches.league.primeiraLiga',
  英冠: 'matches.league.championship',
  欧冠: 'matches.league.championsLeague',
  欧国联: 'matches.league.nationsLeague'
}
const leagueLabel = (name, displayName = '') => {
  if (name) return displayLeagueName(name, displayName)
  const key = LEAGUE_LABEL_KEYS[name]
  return key ? t(key) : name
}
const leagueOptions = computed(() => [
  { label: t('matches.allLeagues'), value: 'all' },
  ...PRODUCTION_LEAGUES.map(name => ({ label: leagueLabel(name), value: name }))
])
const filteredMatches = computed(() => {
  const matches = rawMatches.value || []
  const keyword = normalizeTeamSearch(teamKeyword.value)
  const matchesTeamSearch = match => {
    if (!keyword) return true
    const names = [match?.teams?.home?.name || match?.homeTeamName, match?.teams?.away?.name || match?.awayTeamName]
    return names.flatMap(getTeamSearchTokens).some(name => normalizeTeamSearch(name).includes(keyword))
  }
  return matches.filter(match => {
    const leagueOk = selectedLeague.value === 'all' || getLeagueName(match) === selectedLeague.value
    const fixtureId = String(getMatchId(match) || '')
    const favoriteOk = !onlyFavorites.value || matchFavorites.value.some(item => String(item.fixtureId || item.id || item.matchId || '') === fixtureId)
    return leagueOk && favoriteOk && matchesTeamSearch(match)
  })
})
const matchCount = computed(() => filteredMatches.value.length)
const isOpsEmptyStatus = computed(() => ['SOURCE_LIMITED', 'SYNC_FAILED'].includes(dataQuality.value?.status))
/** Never surface ops-speak in fan UI (strict review FAIL). */
const isOpsSpeakMessage = (msg) => {
  const s = String(msg || '')
  return !s ? false : (s.includes('主数据源') || s.includes('完成本次检查') || s.includes('已返回') || s.includes('部分赛事数据'))
}
const fanEmptyDescription = computed(() => selectedLeague.value === 'all'
  ? t('matches.fanEmptyAll')
  : t('matches.fanEmptyLeague'))
const emptyStateTitle = computed(() => {
  // Favorites filter empty must never contradict 「0场」with ops 「已返回N场」
  if (onlyFavorites.value) {
    return matchFavorites.value.length ? t('matches.favoritesEmptyDay') : t('matches.favoritesEmpty')
  }
  if (isOpsEmptyStatus.value) {
    if (dataQuality.value?.status === 'SOURCE_LIMITED') return t('matches.sourceLimited')
    if (dataQuality.value?.status === 'SYNC_FAILED') return t('matches.syncFailed')
  }
  return selectedLeague.value === 'all' ? t('matches.emptyDay') : t('matches.emptyLeagueDay')
})
const emptyStateDescription = computed(() => {
  if (onlyFavorites.value) {
    return matchFavorites.value.length
      ? t('matches.favoritesEmptyDayDesc')
      : t('matches.favoritesEmptyDesc')
  }
  if (isOpsEmptyStatus.value) {
    const raw = dataQuality.value?.message || ''
    if (!isOpsSpeakMessage(raw) && raw) return raw
    return dataQuality.value?.status === 'SOURCE_LIMITED'
      ? t('matches.sourceLimitedDesc')
      : t('matches.syncFailedDesc')
  }
  // Fan-facing empty day — never surface ops-speak like 「主数据源已完成本次检查」
  return fanEmptyDescription.value
})
const emptyStateActionText = computed(() => {
  if (onlyFavorites.value) return matchFavorites.value.length ? t('matches.emptyActionAll') : t('matches.emptyActionBrowse')
  if (isOpsEmptyStatus.value) return t('matches.retry')
  return selectedLeague.value === 'all' ? t('matches.emptyActionYesterday') : t('matches.emptyActionAllLeagues')
})
const onEmptyStateAction = () => {
  if (onlyFavorites.value) {
    onlyFavorites.value = false
    return
  }
  if (isOpsEmptyStatus.value) return loadCurrentView()
  if (selectedLeague.value === 'all') return shiftDate(-1)
  selectedLeague.value = 'all'
}
const focusKeys = computed(() => {
  const ids = new Set()
  const keys = new Set()
  for (const item of hotMatches.value || []) {
    const id = String(getMatchId(item) || '')
    if (id) ids.add(id)
    keys.add(matchIdentity(item))
  }
  return { ids, keys }
})
const isFocusMatch = (match) => {
  const id = String(getMatchId(match) || '')
  return Boolean((id && focusKeys.value.ids.has(id)) || focusKeys.value.keys.has(matchIdentity(match)))
}

const dateCounts = computed(() => {
  const counts = new Map(dateCountsCache.value)
  // `rawMatches` is the currently selected day's result.  That date is
  // already written into `dateCountsCache` by loadMatchesByDate(), so adding
  // it again here used to turn 6 matches into 12 after clicking the rail.
  // Only hydrate dates that are not present in the cache (for example when a
  // selected date falls outside the initial 7-day window).
  const currentDateCounts = new Map()
  ;(rawMatches.value || []).forEach(match => {
    const date = getMatchDate(match)
    if (date) currentDateCounts.set(date, (currentDateCounts.get(date) || 0) + 1)
  })
  currentDateCounts.forEach((count, date) => {
    if (!counts.has(date)) counts.set(date, count)
  })
  return counts
})
const dateRail = computed(() => {
  const base = selectedDate.value || todayDate.value
  const today = todayDate.value
  const tomorrow = formatLocalDate(new Date(Date.now() + 86400000))
  return [0, 1, 2, 3, 4, 5, 6].map(offset => {
    const date = new Date(`${base}T00:00:00`)
    date.setDate(date.getDate() + offset)
    const iso = formatLocalDate(date)
    const label = iso === today
      ? t('matches.today')
      : iso === tomorrow
        ? t('matches.tomorrow')
        : new Intl.DateTimeFormat(locale.value === 'zh' ? 'zh-CN' : 'en-US', { month: 'short', timeZone: 'Asia/Shanghai' }).format(new Date(`${iso}T00:00:00+08:00`))
    return {
      date: iso,
      label,
      day: iso.slice(-2),
      weekday: new Intl.DateTimeFormat(locale.value === 'zh' ? 'zh-CN' : 'en-US', { weekday: 'short' }).format(date),
      count: dateCounts.value.get(iso) || 0
    }
  })
})
const selectRailDate = async (date) => {
  selectedDate.value = date
  await loadMatchesByDate(date)
}
const shiftDate = async (delta) => {
  const base = selectedDate.value || todayDate.value
  const date = new Date(`${base}T00:00:00`)
  date.setDate(date.getDate() + delta)
  await selectRailDate(formatLocalDate(date))
}
const groupedMatches = computed(() => {
  const map = new Map()
  ;(filteredMatches.value || []).forEach(match => {
    const date = getMatchDate(match) || 'unknown'
    if (!map.has(date)) map.set(date, [])
    map.get(date).push(match)
  })
  return Array.from(map.entries()).sort(([a], [b]) => a.localeCompare(b)).map(([date, items]) => {
    // 按联赛二次分组（联赛名已在后端统一）
    const leagueMap = new Map()
    items.forEach(m => {
      const lg = getLeagueName(m)
      if (!leagueMap.has(lg)) leagueMap.set(lg, { name: lg, displayName: getLeagueDisplay(m), items: [] })
      leagueMap.get(lg).items.push(m)
    })
    const leagueGroups = Array.from(leagueMap.values()).map(group => ({
      ...group,
      items: group.items.sort((a, b) => String(a?.fixture?.date || '').localeCompare(String(b?.fixture?.date || '')))
    }))
    return {
      date,
      label: formatDateLabel(date),
      weekday: weekdayLabel(date),
      items,
      leagueGroups
    }
  })
})
const formatLocalDate = d => getBusinessDate(d)
const todayDate = computed(() => formatLocalDate(new Date()))
// This describes the selected date for assistive technology and testable
// context; it is intentionally not rendered as a duplicate visual page title.
const matchesHeading = computed(() => {
  const date = selectedDate.value || todayDate.value
  if (date === todayDate.value) return t('matches.headingToday')
  const tomorrow = formatLocalDate(new Date(Date.now() + 86400000))
  if (date === tomorrow) return t('matches.headingTomorrow')
  return t('matches.headingDate', { date })
})
const favoritesCount = computed(() => matchFavorites.value?.length || 0)
const toggleTeamNameMode = () => {
  teamNameMode.value = teamNameMode.value === 'zh' ? 'en' : 'zh'
  localStorage.setItem(TEAM_NAME_MODE_KEY, teamNameMode.value)
}

const loadFavorites = async () => {
  try {
    const res = await favoriteApi.listMatches()
    matchFavorites.value = Array.isArray(res) ? res : (res?.items || [])
  } catch {
    matchFavorites.value = []
  }
}

const normalizeMatchList = (res) => {
  if (Array.isArray(res)) return res
  if (Array.isArray(res?.response)) return res.response
  if (Array.isArray(res?.items)) return res.items
  if (Array.isArray(res?.records)) return res.records
  return []
}

// Provider IDs are different for the same fixture and names may differ only
// by accents (Alaves / Alavés). Keep one canonical card and prefer the
// configured BBC primary-source row when legacy rows are still in the DB.
const normalizeIdentityPart = value => String(value || '')
  .normalize('NFKD')
  .replace(/[\u0300-\u036f]/g, '')
  .toLowerCase()
  .replace(/\b(fc|afc|sc|cf|club)\b/g, '')
  .replace(/[^a-z0-9\u4e00-\u9fff]+/g, '')
const matchIdentity = match => [
  normalizeIdentityPart(getLeagueName(match)),
  normalizeIdentityPart(match?.teams?.home?.name || match?.homeTeamName),
  normalizeIdentityPart(match?.teams?.away?.name || match?.awayTeamName),
  getMatchDate(match),
].join('|')
const sourcePriority = match => String(match?.source || '').toLowerCase() === 'bbc-scores' ? 0 : 1
const deduplicateMatches = matches => {
  const result = new Map()
  ;(matches || []).forEach(match => {
    const key = matchIdentity(match)
    if (!key || key === '|||') return
    const previous = result.get(key)
    if (!previous || sourcePriority(match) < sourcePriority(previous)) result.set(key, match)
  })
  return Array.from(result.values())
}

const scrubDataQuality = (dq) => {
  if (!dq || typeof dq !== 'object') return dq
  const message = String(dq.message || '')
  if (isOpsSpeakMessage(message)) return { ...dq, message: '' }
  return dq
}
const loadCurrentView = async () => {
  if (selectedDate.value) {
    await loadMatchesByDate(selectedDate.value)
  } else {
    await loadMatches()
  }
}
const loadMatches = async () => {
  loading.value = true
  errorMsg.value = ''
  
  try {
    const res = await crawlerApi.getMatchesWindow(1, 300)
    dataQuality.value = scrubDataQuality(res?.dataQuality) || dataQuality.value
    rawMatches.value = deduplicateMatches(normalizeMatchList(res).filter(match => getMatchDate(match) >= todayDate.value))
    // Empty days use PageState fan copy only — no ElMessage toast/banner (looks like product empty copy to reviewers).
  } catch (e) {
    errorMsg.value = e.message || t('matches.loadFailed')
  } finally {
    loading.value = false
  }
}

const loadMatchesByDate = async (date) => {
  loading.value = true
  errorMsg.value = ''
  try {
    const firstPage = await crawlerApi.getMatchesPage(1, 100, date, { preserveMeta: true })
    dataQuality.value = scrubDataQuality(firstPage?.dataQuality) || dataQuality.value
    const total = Number(firstPage?.total) || normalizeMatchList(firstPage).length
    const pages = Math.max(1, Math.ceil(total / 100))
    const restPages = pages > 1
      ? await Promise.all(Array.from({ length: pages - 1 }, (_, index) => crawlerApi.getMatchesPage(index + 2, 100, date, { preserveMeta: true })))
      : []
    rawMatches.value = deduplicateMatches([firstPage, ...restPages].flatMap(normalizeMatchList))
    const nextCounts = new Map(dateCountsCache.value)
    nextCounts.set(date, rawMatches.value.length)
    dateCountsCache.value = nextCounts
    // Empty days: PageState only — silence ElMessage.info toast.
  } catch (e) {
    errorMsg.value = e.message || t('matches.loadFailed')
  } finally {
    loading.value = false
  }
}

const loadDateCounts = async () => {
  try {
    const result = await crawlerApi.getMatchesWindow(1, 300)
    const counts = new Map()
    deduplicateMatches(normalizeMatchList(result)).forEach(match => {
      const date = getMatchDate(match)
      if (date) counts.set(date, (counts.get(date) || 0) + 1)
    })
    dateCountsCache.value = counts
    countsReady.value = true
  } catch { /* 当前日期数据仍可独立展示 */ countsReady.value = true }
}

const showH2H = async (fixtureId, homeTeamId, awayTeamId, homeTeamName, awayTeamName) => {
  const safeHomeName = homeTeamName || ''
  const safeAwayName = awayTeamName || ''
  if (!safeHomeName || !safeAwayName) return
  h2hVisible.value = true
  h2hLoading.value = true
  try {
    const res = await crawlerApi.getHeadToHead(safeHomeName, safeAwayName)
    const data = res?.data || res || {}
    h2hData.value = { ...data, homeTeamName: safeHomeName, awayTeamName: safeAwayName, fixtureId, homeTeamId, awayTeamId }
  } catch (e) {
    h2hData.value = { recentMatches: [], summary: { homeWins: 0, draws: 0, awayWins: 0 }, homeTeamName: safeHomeName, awayTeamName: safeAwayName, fixtureId, homeTeamId, awayTeamId }
  } finally {
    h2hLoading.value = false
  }
}

const openMatchDetails = async (match) => {
  const fixtureId = getMatchId(match)
  if (!fixtureId) {
    ElMessage.info(t('matches.missingMatchId'))
    return
  }
  analyticsApi.track('match_opened', { page: '/matches', entityType: 'match', entityId: fixtureId, properties: { league: match?.league?.name || match?.leagueName } }).catch(() => {})
  const homeTeamName = match?.teams?.home?.name || match?.homeTeamName || ''
  const awayTeamName = match?.teams?.away?.name || match?.awayTeamName || ''
  contentMatch.value = { ...match, homeTeamName, awayTeamName, fixtureId, fixture: match?.fixture || { id: fixtureId } }
  matchDetails.value = null
  contentVisible.value = true
  detailsLoading.value = true
  try {
    const result = await matchApi.getDetails(fixtureId)
    matchDetails.value = result?.data || result
  } catch {
    ElMessage.warning(t('matches.detailsUnavailable'))
  } finally {
    detailsLoading.value = false
  }
}


const discoverScrolled = ref(false)
const scrollToPredictionDiscovery = async () => {
  if (discoverScrolled.value) return
  const first = (hotMatches.value || [])[0]
  if (!first) return
  discoverScrolled.value = true
  await openFocusMatch(first)
}

const scrollToMatchList = () => {
  document.querySelector('.match-list-panel')?.scrollIntoView({ behavior: 'smooth', block: 'start' })
}

const focusMatchCard = async (match) => {
  const id = String(getMatchId(match) || '')
  if (!id) return
  await nextTick()
  const target = Array.from(document.querySelectorAll('[data-match-id]')).find(node => node.dataset.matchId === id)
  if (!target) return
  target.scrollIntoView({ behavior: 'smooth', block: 'center' })
  target.classList.remove('focus-target')
  window.requestAnimationFrame(() => target.classList.add('focus-target'))
  window.setTimeout(() => target.classList.remove('focus-target'), 1800)
}

const openFocusMatch = async (match) => {
  const id = getMatchId(match)
  const current = rawMatches.value.find(item => String(getMatchId(item)) === String(id) || matchIdentity(item) === matchIdentity(match))
  if (current) {
    await focusMatchCard(current)
    return
  }
  const date = getMatchDate(match)
  if (date && date !== (selectedDate.value || todayDate.value)) {
    await selectRailDate(date)
    await focusMatchCard(match)
    return
  }
  openMatchDetails(match)
}

const refreshMatchDetails = async () => {
  const fixtureId = getMatchId(contentMatch.value)
  if (!fixtureId) return
  detailsRefreshing.value = true
  try {
    const res = await matchApi.refreshDetails(fixtureId)
    matchDetails.value = res?.data || res
    ElMessage.success(t('matches.detailsRefreshed'))
  } catch {
    ElMessage.warning(t('matches.detailsRefreshFailed'))
  } finally {
    detailsRefreshing.value = false
  }
}

const formatEventMinute = (event) => {
  const elapsed = event?.time?.elapsed
  if (elapsed == null) return '—'
  return `${elapsed}${event?.time?.extra ? '+' + event.time.extra : ''}'`
}
const eventIcon = (event) => {
  const type = String(event?.type || '').toLowerCase()
  const detail = String(event?.detail || '').toLowerCase()
  if (type.includes('goal')) return '⚽'
  if (type.includes('card') || detail.includes('card')) return '▰'
  if (type.includes('subst')) return '↕'
  return '•'
}
const goPredict = (matchOrFixtureId, homeId, awayId, homeTeamNameArg, awayTeamNameArg) => {
  let fixtureId, hId, aId, homeTeamName, awayTeamName, leagueName, leagueId, round, matchTime, homeLogo, awayLogo
  let homeTeamDisplayName = '', awayTeamDisplayName = '', leagueDisplay = ''

  if (typeof matchOrFixtureId === 'object' && matchOrFixtureId !== null) {
    const match = matchOrFixtureId
    fixtureId = getMatchId(match)
    hId = match.teams?.home?.id || 0
    aId = match.teams?.away?.id || 0
    homeTeamName = match.teams?.home?.name || ROUTE_HOME_FALLBACK
    awayTeamName = match.teams?.away?.name || ROUTE_AWAY_FALLBACK
    homeLogo = match.teams?.home?.logo || ''
    awayLogo = match.teams?.away?.logo || ''
    leagueName = match.league?.name || ''
    leagueId = match.league?.id || ''
    round = match.league?.round || ''
    matchTime = match.fixture?.date || ''
    homeTeamDisplayName = getTeamDisplay(match, 'home')
    awayTeamDisplayName = getTeamDisplay(match, 'away')
    leagueDisplay = getLeagueDisplay(match)
  } else {
      fixtureId = matchOrFixtureId
    hId = homeId || 0
    aId = awayId || 0
    homeTeamName = homeTeamNameArg || ROUTE_HOME_FALLBACK
    awayTeamName = awayTeamNameArg || ROUTE_AWAY_FALLBACK
    homeLogo = ''
    awayLogo = ''
    leagueName = ''
    leagueId = ''
    round = ''
    matchTime = ''
  }

  if (fixtureId) {
    analyticsApi.track('prediction_opened', { page: '/matches', entityType: 'match', entityId: fixtureId, properties: { league: leagueName } }).catch(() => {})
    router.push({
      path: `/prediction/${fixtureId}`,
      query: {
        home: homeTeamName,
        away: awayTeamName,
        homeId: hId,
        awayId: aId,
        homeName: homeTeamName,
        awayName: awayTeamName,
        homeTeamDisplayName,
        awayTeamDisplayName,
        homeLogo,
        awayLogo,
        leagueName,
        leagueDisplay,
        leagueId,
        round,
        matchTime,
        returnDate: selectedDate.value || '',
        returnLeague: selectedLeague.value || 'all',
        returnKeyword: teamKeyword.value || '',
        returnFavorites: onlyFavorites.value ? '1' : '',
        returnPage: String(currentPage.value || 1),
      }
    })
  }
}

const openAgent = (match) => {
  const matchId = getMatchId(match)
  if (!matchId) return
  router.push({
    path: '/agent',
    query: {
      fixtureId: String(matchId),
      homeTeamId: match.teams?.home?.id || '',
      awayTeamId: match.teams?.away?.id || '',
      homeName: match.teams?.home?.name || ROUTE_HOME_FALLBACK,
      awayName: match.teams?.away?.name || ROUTE_AWAY_FALLBACK,
      leagueName: match.league?.name || '',
      matchTime: match.fixture?.date || ''
    }
  })
}

const goTeamSquad = (teamName, side, match) => {
  const selectedTeam = match?.teams?.[side] || {}
  const target = selectedTeam.name || teamName
  if (!target) return
  router.push({
    path: `/team/${encodeURIComponent(target)}/squad`,
    query: {
      name: target,
      teamDisplayName: getTeamDisplay(match, side),
      teamId: String(selectedTeam.id || ''),
      logo: selectedTeam.logo || '',
      league: match?.league?.name || match?.leagueName || '',
      leagueDisplay: getLeagueDisplay(match),
      season: String(new Date().getFullYear())
    }
  })
}

const toggleMatchFavorite = async (match) => {
  const fixtureId = getMatchId(match)
  if (!fixtureId) return
  const exists = isFavoritedMatch(fixtureId)
  try {
    if (exists) {
      await favoriteApi.removeMatch(fixtureId)
      removeMatchReminder(fixtureId)
      ElMessage.success(t('matches.unfavorited'))
    } else {
      const homeName = match?.teams?.home?.name || ROUTE_HOME_FALLBACK
      const awayName = match?.teams?.away?.name || ROUTE_AWAY_FALLBACK
      await favoriteApi.addMatch(fixtureId, `${homeName} vs ${awayName}`, {
        leagueName: match?.league?.name || match?.leagueName || '',
        matchTime: match?.fixture?.date || match?.matchTime || ''
      })
      saveMatchReminder({ fixtureId: String(fixtureId), title: `${homeName} vs ${awayName}`, matchTime: match?.fixture?.date || match?.matchTime || '', notified: false })
      ElMessage.success(t('matches.favorited'))
      maybeAskBrowserNotifyAfterFavorite()
    }
    await loadFavorites()
  } catch (e) {
    const msg = String(e?.message || e?.response?.data?.message || '')
    if (msg.toLowerCase().includes('unauthorized')) {
      ElMessage.error(t('matches.sessionExpired'))
    } else {
      ElMessage.error(t('matches.favoriteFailed'))
    }
  }
}

const readMatchReminders = () => {
  try { return JSON.parse(localStorage.getItem('football_match_reminders') || '[]') } catch { return [] }
}
const writeMatchReminders = (items) => {
  const normalized = items.slice(-50)
  localStorage.setItem('football_match_reminders', JSON.stringify(normalized))
  if (userStore.token) {
    userApi.updatePreferences({ matchRemindersEnabled: remindersEnabled.value, matchReminders: normalized }).catch(() => {})
  }
}
const saveMatchReminder = (item) => writeMatchReminders([...readMatchReminders().filter(existing => String(existing.fixtureId) !== String(item.fixtureId)), item])
const removeMatchReminder = (fixtureId) => writeMatchReminders(readMatchReminders().filter(item => String(item.fixtureId) !== String(fixtureId)))
const checkMatchReminders = () => {
  if (!remindersEnabled.value || typeof Notification === 'undefined' || Notification.permission !== 'granted') return
  const now = Date.now()
  const items = readMatchReminders()
  let changed = false
  items.forEach(item => {
    const raw = String(item.matchTime || '').replace(' ', 'T')
    const start = new Date(raw && /(?:Z|[+-]\d{2}:?\d{2})$/i.test(raw) ? raw : `${raw}+08:00`).getTime()
    if (!Number.isFinite(start) || start <= now || start - now > 30 * 60 * 1000 || item.notified) return
    new Notification(t('matches.reminderNotificationTitle'), { body: t('matches.reminderNotificationBody', { title: item.title }), tag: `football-${item.fixtureId}` })
    item.notified = true
    changed = true
  })
  if (changed) writeMatchReminders(items)
}
const BROWSER_NOTIFY_DISMISS_KEY = 'football_browser_notify_dismissed'
const maybeAskBrowserNotifyAfterFavorite = async () => {
  if (typeof Notification === 'undefined') return
  if (Notification.permission !== 'default') return
  if (localStorage.getItem(BROWSER_NOTIFY_DISMISS_KEY) === '1') return
  try {
    await ElMessageBox.confirm(
      t('matches.browserNotifyPrompt'),
      t('matches.browserNotifyTitle'),
      { confirmButtonText: t('matches.browserNotifyConfirm'), cancelButtonText: t('matches.browserNotifyCancel'), type: 'info', distinguishCancelAndClose: true }
    )
    const permission = await Notification.requestPermission()
    if (permission === 'granted' && !remindersEnabled.value) {
      remindersEnabled.value = true
      localStorage.setItem('football_match_reminders_enabled', '1')
    }
  } catch (error) {
    if (error === 'cancel') localStorage.setItem(BROWSER_NOTIFY_DISMISS_KEY, '1')
  }
}

const enableMatchReminders = async () => {
  if (typeof Notification === 'undefined') return ElMessage.info(t('matches.browserUnsupported'))
  const permission = Notification.permission === 'default' ? await Notification.requestPermission() : Notification.permission
  if (permission !== 'granted') return ElMessage.warning(t('matches.notificationsBlocked'))
  remindersEnabled.value = true
  localStorage.setItem('football_match_reminders_enabled', '1')
  if (userStore.token) userApi.updatePreferences({ matchRemindersEnabled: true }).catch(() => {})
  ElMessage.success(t('matches.remindersEnabled'))
  checkMatchReminders()
}
const disableMatchReminders = () => {
  remindersEnabled.value = false
  localStorage.setItem('football_match_reminders_enabled', '0')
  if (userStore.token) userApi.updatePreferences({ matchRemindersEnabled: false }).catch(() => {})
  ElMessage.success(t('matches.remindersDisabled'))
}
const toggleMatchReminders = async () => {
  if (remindersChanging.value) return
  remindersChanging.value = true
  try {
    if (remindersEnabled.value) disableMatchReminders()
    else await enableMatchReminders()
  } finally {
    remindersChanging.value = false
  }
}

const isFavoritedMatch = (fixtureId) => {
  const id = String(fixtureId)
  return matchFavorites.value.some(item => String(item.fixtureId || item.id || item.matchId || '') === id)
}


const prefersReducedMotion = () => typeof window !== 'undefined' && window.matchMedia('(prefers-reduced-motion: reduce)').matches
const revealLeagueGroups = async () => {
  await nextTick()
  if (prefersReducedMotion()) return
  const nodes = document.querySelectorAll('.date-group-list .league-group')
  if (!nodes.length) return
  gsap.fromTo(nodes, { opacity: 0, y: 10 }, { opacity: 1, y: 0, duration: 0.32, stagger: 0.045, ease: 'power2.out', clearProps: 'transform,opacity' })
}
watch([filteredMatches, loading], ([list, isLoading]) => {
  if (!isLoading && Array.isArray(list) && list.length) revealLeagueGroups()
})

onMounted(async () => {
  onboardingVisible.value = localStorage.getItem(ONBOARDING_KEY) !== '1'
  const preferCollapse = localStorage.getItem(FOCUS_COLLAPSE_KEY)
  if (preferCollapse === '1' || preferCollapse === '0') {
    focusCollapsed.value = preferCollapse === '1'
  } else if (typeof window !== 'undefined' && window.matchMedia('(max-width: 768px)').matches) {
    focusCollapsed.value = true
  }
  selectedDate.value = String(route.query.returnDate || todayDate.value)
  selectedLeague.value = String(route.query.returnLeague || 'all')
  teamKeyword.value = String(route.query.team || route.query.returnKeyword || '')
  if (route.query.league) selectedLeague.value = String(route.query.league)
  onlyFavorites.value = route.query.returnFavorites === '1'
  currentPage.value = Number(route.query.returnPage) || 1
  await Promise.all([loadMatchesByDate(selectedDate.value), loadDateCounts()])
  loadHotMatches()
  focusTimer = window.setInterval(() => loadHotMatches('', { silent: true }), 60 * 1000)
  loadFavorites()
  if (userStore.token) {
    userApi.getPreferences().then(res => {
      const raw = res?.preferences || res?.data?.preferences
      try {
        const prefs = typeof raw === 'string' ? JSON.parse(raw) : raw
        if (prefs?.matchRemindersEnabled === true) remindersEnabled.value = true
        if (Array.isArray(prefs?.matchReminders)) {
          // Keep browser notification state local, but sync the reminder list
          // from the account so a second device does not start empty.
          localStorage.setItem('football_match_reminders', JSON.stringify(prefs.matchReminders.slice(-50)))
        }
      } catch { /* ignore malformed preference */ }
    }).catch(() => {})
  }
  reminderTimer = window.setInterval(checkMatchReminders, 60 * 1000)
})


watch(() => route.query.discover, (value) => {
  if (value === 'predict') scrollToPredictionDiscovery()
}, { immediate: true })
watch(hotMatches, () => {
  if (route.query.discover === 'predict') scrollToPredictionDiscovery()
})

// Locale changes must refresh server-provided display fields (league/team
// names, data-quality copy) instead of only re-rendering local translations.
watch(locale, async () => {
  await Promise.allSettled([loadCurrentView(), loadDateCounts(), loadFavorites()])
  loadHotMatches('', { silent: true })
})


onBeforeUnmount(() => {
  if (reminderTimer) window.clearInterval(reminderTimer)
  if (focusTimer) window.clearInterval(focusTimer)
})

// 比赛页固定展示今天前后 7 天窗口。
</script>

<style scoped>
.matches-page { min-height: 100vh; max-width: 100%; overflow-x: clip; }
.matches-layout { min-height: calc(100vh - 64px); width: 100%; max-width: 100%; min-width: 0; }
.main-content { padding: 18px 22px; overflow-x: hidden; width: 100%; max-width: 100%; min-width: 0; }
.reminder-button { flex:none; }
.date-rail {
  display:flex;
  gap:8px;
  align-items:stretch;
  overflow-x:auto;
  overscroll-behavior-x:contain;
  -webkit-overflow-scrolling:touch;
  padding:2px 0 16px;
  width:100%;
  max-width:100%;
  min-width:0;
  scrollbar-width: none; /* Firefox — hide thick bar on mobile rail */
  -ms-overflow-style: none;
}
.date-rail::-webkit-scrollbar { display: none; height: 0; }
.date-rail-arrow { flex:0 0 38px; display:flex; align-items:center; justify-content:center; border:1px solid var(--ff-border); border-radius:var(--ff-radius-md); background:var(--ff-surface-quiet); color:var(--ff-text-muted); cursor:pointer; transition:border-color var(--ff-transition-fast), color var(--ff-transition-fast), background var(--ff-transition-fast); }
.date-rail-arrow:hover:not(:disabled), .date-rail-arrow:focus-visible { border-color:var(--ff-primary); color:var(--ff-primary); background:var(--ff-primary-soft); outline:none; }
.date-rail-arrow:disabled { cursor:wait; opacity:.55; }
.date-rail-item { flex:1 1 0; min-width:0; padding:9px 12px; border:1px solid var(--ff-border); border-radius:var(--ff-radius-md); background:var(--ff-surface); color:var(--ff-text-muted); cursor:pointer; text-align:left; transition:border-color var(--ff-transition-fast),background var(--ff-transition-fast),color var(--ff-transition-fast); }
.date-rail-item:disabled { cursor:wait; opacity:.7; }
.date-rail-item:hover { border-color:var(--ff-primary); color:var(--ff-primary); }
.date-rail-item.active { background:var(--ff-primary); border-color:var(--ff-primary); color:#fff; }
.date-rail-item span,.date-rail-item small { display:block; font-size:11px; }
.date-rail-item strong { display:block; margin:4px 0; font:700 20px/1 var(--ff-mono); }

/* ===== 比赛列表 ===== */
.matches-workspace { display:grid; grid-template-columns:minmax(280px,340px) minmax(0,1fr); gap:18px; align-items:start; width:100%; max-width:100%; min-width:0; }
.match-list-panel { min-width: 0; max-width: 100%; }
.matches-focus-sidebar { min-width:0; max-width:100%; position:sticky; top:82px; }
.matches-focus-sidebar :deep(.focus-rail) { margin:0; }
.matches-focus-sidebar :deep(.focus-list) { grid-template-columns:1fr; }
.match-list-actions { display:flex; align-items:center; gap:10px; min-width:0; max-width:100%; }
.league-filter { width: 168px; }
.team-filter { width: 108px; }
.team-language-button { flex:none; }
.native-filter-select,
.native-filter-input,
.filter-chip,
.filters-more,
.sport-filters .native-filter-select,
.sport-filters .native-filter-input,
.sport-filters .filter-chip {
  box-sizing: border-box;
  height: 40px;
  min-height: 40px;
  line-height: 1.2;
}
.native-filter-select,
.native-filter-input {
  border: 1px solid var(--ff-border);
  border-radius: 999px;
  background: #fff;
  color: var(--ff-text);
  font-size: 12px;
}
.native-filter-select {
  padding: 0 28px 0 12px;
  appearance: none;
  background-image: linear-gradient(45deg, transparent 50%, var(--ff-text-muted) 50%), linear-gradient(135deg, var(--ff-text-muted) 50%, transparent 50%);
  background-position: calc(100% - 14px) 55%, calc(100% - 10px) 55%;
  background-size: 4px 4px, 4px 4px;
  background-repeat: no-repeat;
  cursor: pointer;
}
.native-filter-input { padding: 0 12px; min-width: 0; }
.native-filter-input::-webkit-search-cancel-button { cursor: pointer; }
.native-filter-select:hover,
.native-filter-input:hover { border-color: var(--ff-primary); color: var(--ff-text-strong); }
.native-filter-select:focus,
.native-filter-input:focus { outline: none; border-color: var(--ff-primary); box-shadow: 0 0 0 2px var(--ff-primary-soft); }

/* 场数标签：荧光渐变胶囊 */
.match-count-tag {
  display: flex;
  align-items: baseline;
  gap: 5px;
  padding: 5px 12px;
  border-radius: 6px;
  background: var(--ff-primary);
  color: #ffffff;
  box-shadow: var(--ff-shadow-sm);
  position: relative;
  overflow: hidden;
}

.match-count-num {
  font-size: 16px;
  font-weight: 600;
  line-height: 1;
  font-family: var(--ff-mono);
  font-variant-numeric: tabular-nums;
}
.match-count-unit { font-size: 12px; font-weight: 400; }

/* ===== 日期分组 ===== */
.date-group-list { display: flex; flex-direction: column; gap: 20px; }
.date-group {
  background: #ffffff;
  border: 1px solid var(--ff-border);
  border-radius: 8px;
  padding: 18px;
  min-width: 0;
  max-width: 100%;
  transition: border-color var(--ff-transition), background var(--ff-transition);
}
.date-group:hover {
  border-color: var(--ff-border-strong);
  background: var(--ff-surface-soft);
}
.date-group-heading { display:flex; align-items:center; justify-content:space-between; gap:16px; padding-bottom:14px; margin-bottom:16px; border-bottom:1px solid var(--ff-border); color:var(--ff-text-muted); }
.date-group-heading > div { display:flex; align-items:baseline; gap:10px; }
.date-group-heading .ff-kicker { color:var(--ff-primary); }
.date-group-heading strong { color:var(--ff-ink); font-size:18px; letter-spacing:-.02em; }
.reveal { animation: none; }

/* ===== 联赛分组小节 ===== */
.league-group { margin-bottom: 20px; }
.league-group:last-child { margin-bottom: 0; }
.league-group-header {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 12px;
  position: relative;
}

.league-group-header::after {
  content: '';
  flex: 1;
  height: 1px;
  background: var(--ff-border);
}
.league-group-header .league-name {
  font-size: 14px;
  font-weight: 600;
  letter-spacing: -0.01em;
  color: var(--ff-text-strong);
  white-space: nowrap;
}

.matches-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(min(100%, 280px), 1fr)); gap: 14px; width: 100%; min-width: 0; }

/* ===== 历史交锋弹窗 ===== */
.loading-state { padding: 20px 0; }
.h2h-content {}
.h2h-summary {
  display: flex;
  justify-content: space-around;
  padding: 20px;
  background: var(--ff-bg-alt);
  border-radius: 8px;
  margin-bottom: 20px;
  border: 1px solid var(--ff-border);
}
.summary-item { text-align: center; }
.summary-value {
  display: block;
  font-size: 30px;
  font-weight: 800;
  color: var(--ff-text-strong);
  font-family: var(--ff-mono);
  font-variant-numeric: tabular-nums;
}
.summary-label { font-size: 12px; color: var(--ff-text-muted); }
.h2h-matches { display: flex; flex-direction: column; gap: 8px; }
.h2h-match {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 10px 16px;
  background: var(--ff-bg-alt);
  border-radius: 6px;
  border: 1px solid var(--ff-border);
}
.h2h-date { font-size: 12px; color: var(--ff-text-muted); font-family: var(--ff-mono); }
.h2h-score {
  font-size: 16px;
  font-weight: 700;
  color: var(--ff-text);
  font-family: var(--ff-mono);
  font-variant-numeric: tabular-nums;
}
.h2h-empty { text-align: center; color: var(--ff-text-muted); font-size: 13px; padding: 24px 0; }
.match-content-list { display:flex; flex-direction:column; gap:12px; }
.match-content-item { padding:14px 16px; border:1px solid var(--ff-border); border-radius:var(--ff-radius-md); background:var(--ff-surface-quiet); cursor:pointer; transition:border-color var(--ff-transition-fast), background var(--ff-transition-fast); }
.match-content-item:hover, .match-content-item:focus-visible { border-color:var(--ff-primary); background:var(--ff-surface); outline:none; }
.match-content-type { display:flex; align-items:center; gap:8px; color:var(--ff-text-faint); font-size:12px; }
.match-content-item h3 { margin:9px 0 5px; color:var(--ff-text-strong); font-size:15px; line-height:1.4; }
.match-content-item p { margin:0 0 8px; color:var(--ff-text-muted); font-size:13px; line-height:1.6; display:-webkit-box; -webkit-box-orient:vertical; -webkit-line-clamp:2; overflow:hidden; }
.match-content-item small { color:var(--ff-text-faint); font-size:11px; }
.match-detail-tabs :deep(.el-tabs__header) { margin-bottom: 16px; }
.detail-source-line { display:flex; align-items:center; gap:10px; margin-bottom:14px; color:var(--ff-text-muted); font-size:12px; }
.detail-source-line span { margin-right:auto; font-family:var(--ff-mono); }
.event-timeline { display:flex; flex-direction:column; gap:8px; max-height:300px; overflow:auto; padding:4px 0; }
.event-row { display:grid; grid-template-columns:48px 110px 26px minmax(100px,1fr) auto; align-items:center; gap:8px; padding:9px 10px; border:1px solid var(--ff-border); border-radius:8px; background:var(--ff-surface-quiet); }
.event-minute { color:var(--ff-primary); font:700 13px var(--ff-mono); }
.event-team,.event-main { color:var(--ff-text-strong); font-size:13px; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
.event-icon { text-align:center; }
.event-row small { color:var(--ff-text-faint); font-size:11px; white-space:nowrap; }
.detail-empty { padding:24px 12px; text-align:center; color:var(--ff-text-muted); font-size:13px; border:1px dashed var(--ff-border); border-radius:8px; }
.detail-section-block { margin-top:18px; }
.detail-section-title { margin-bottom:10px; color:var(--ff-text-strong); font-weight:700; }
.detail-stat-grid { display:flex; flex-direction:column; gap:6px; }
.detail-stat-row { display:grid; grid-template-columns:1fr 70px 100px 70px; gap:8px; align-items:center; padding:8px 10px; border-bottom:1px solid var(--ff-border); font-size:12px; }
.detail-stat-row strong { text-align:center; color:var(--ff-text-strong); font-family:var(--ff-mono); }
.detail-stat-row small { text-align:center; color:var(--ff-text-faint); }
.prematch-info-grid { display:grid; grid-template-columns:repeat(4,minmax(0,1fr)); gap:8px; }
.prematch-info-grid > div { display:flex; flex-direction:column; gap:5px; padding:10px; border:1px solid var(--ff-border); border-radius:8px; background:var(--ff-surface-quiet); }
.prematch-info-grid span { color:var(--ff-text-faint); font-size:11px; }
.prematch-info-grid strong { color:var(--ff-text-strong); font:600 13px var(--ff-mono); }
.prematch-info-note { display:block; margin-top:9px; color:var(--ff-text-faint); font-size:11px; }
.lineup-grid { display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); gap:10px; }
.lineup-card { display:flex; flex-direction:column; gap:5px; padding:12px; border:1px solid var(--ff-border); border-radius:8px; background:var(--ff-surface-quiet); }
.lineup-card strong { color:var(--ff-text-strong); }
.lineup-card span,.lineup-card small { color:var(--ff-text-muted); font-size:12px; }

/* ===== 响应式 ===== */
@media (max-width: 1080px) {
  .matches-workspace { grid-template-columns:1fr; }
  .matches-focus-sidebar { position:static; }
  .matches-focus-sidebar :deep(.focus-list) { grid-template-columns:repeat(3,minmax(0,1fr)); }
}
@media (max-width: 768px) {
  .matches-grid { grid-template-columns: 1fr; }
  .main-content { padding: 12px; }
  .date-rail {
    scroll-snap-type: x mandatory;
    gap: 6px;
    padding: 2px 0 14px;
  }
  .date-rail-arrow { display: none; }
  .date-rail-item {
    flex: 0 0 88px;
    min-width: 0;
    padding: 11px 12px;
    scroll-snap-align: start;
  }
  .match-list-panel :deep(.section-head) { flex-direction:column; align-items:stretch; gap:10px; }
  .match-list-panel :deep(.section-actions) { width:100%; }
  .match-list-actions { width:100%; justify-content:space-between; align-items:stretch; flex-wrap:wrap; }
  .prematch-info-grid { grid-template-columns:repeat(2,minmax(0,1fr)); }
  .league-filter { flex:1; min-width:0; }
  .team-filter { width: 86px; }
  .match-list-actions .league-filter { flex:1 1 140px; }
  .match-list-actions .team-filter { flex:1 1 100px; width:auto; }
  .match-list-actions .favorites-chip { flex:1 1 100%; }
  .match-count-tag { margin-left:auto; }
  .date-group { padding: 14px; }
  .event-row { grid-template-columns:42px 1fr 22px; }
  .event-row small { grid-column:2 / -1; }
  .lineup-grid { grid-template-columns:1fr; }
  .detail-stat-row { grid-template-columns:1fr 54px 70px 54px; }
  .matches-focus-sidebar :deep(.focus-list) { grid-template-columns:1fr; }
}
.date-rail-item small { white-space: nowrap; }
.match-list-panel :deep(.section-head) { position: sticky; top: 0; z-index: 2; background: var(--ff-surface); }

.matches-onboarding {
  display:flex; align-items:center; justify-content:space-between; gap:12px;
  margin:0 clamp(12px, 2vw, 24px); padding:10px 14px;
  border:1px solid color-mix(in srgb, var(--ff-primary) 28%, var(--ff-border));
  border-radius:12px; background:var(--ff-primary-soft); color:var(--ff-text);
}
.matches-onboarding-copy { display:flex; flex-wrap:wrap; align-items:center; gap:10px 16px; min-width:0; }
.matches-onboarding-copy strong { color:var(--ff-primary); font-size:13px; }
.matches-onboarding-copy ol { display:flex; flex-wrap:wrap; gap:8px 14px; margin:0; padding:0; list-style:none; font-size:12px; color:var(--ff-text-muted); }
.matches-onboarding-copy li { position:relative; padding-left:14px; }
.matches-onboarding-copy li::before { content:counter(step); counter-increment:step; position:absolute; left:0; color:var(--ff-primary); font-weight:700; }
.matches-onboarding-copy ol { counter-reset:step; }
.matches-onboarding-copy li::before { content: counter(step) "."; }
.matches-onboarding-dismiss {
  flex:none; border:0; border-radius:8px; padding:7px 12px;
  background:var(--ff-primary); color:#fff; font-size:12px; cursor:pointer;
}
.focus-collapse-bar {
  display:flex; align-items:center; justify-content:space-between; gap:8px;
  margin-bottom:8px; color:var(--ff-text-muted); font-size:12px;
}
.focus-collapse-btn {
  border:1px solid var(--ff-border); border-radius:999px; padding:4px 10px;
  background:var(--ff-surface); color:var(--ff-primary); font-size:11px; cursor:pointer;
}
.matches-focus-sidebar.is-collapsed .focus-collapse-bar { margin-bottom:0; }
@media (min-width: 769px) {
  .focus-collapse-bar { display:none; }
  .matches-focus-sidebar.is-collapsed .focus-collapse-bar { display:flex; }
}

.matches-skeleton {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(min(100%, 280px), 1fr));
  gap: 14px;
  width: 100%;
  min-width: 0;
  padding: 4px 0 8px;
}
@media (max-width: 620px) {
  .matches-skeleton { grid-template-columns: 1fr; }
}


/* r9 fixture board: list density, contained date rail */
.matches-grid {
  display: flex;
  flex-direction: column;
  gap: 0;
  border: 1px solid var(--ff-border);
  border-radius: 6px;
  overflow: hidden;
  background: #fff;
}
.matches-skeleton {
  display: flex;
  flex-direction: column;
  gap: 0;
  border: 1px solid var(--ff-border);
  border-radius: 6px;
  overflow: hidden;
  background: #fff;
  padding: 0;
}
.date-group {
  background: transparent;
  border: 0;
  border-radius: 0;
  padding: 0 0 10px;
}
.date-group:hover { background: transparent; border-color: transparent; }
.date-group-heading { padding: 0 0 8px; margin-bottom: 8px; }
.date-group-heading strong { font-size: 15px; font-weight: 700; letter-spacing: 0; }
.league-group { margin-bottom: 12px; }
.league-group-header {
  margin-bottom: 0;
  padding: 6px 10px;
  background: #f3f6f4;
  border: 1px solid var(--ff-border);
  border-bottom: 0;
  border-radius: 6px 6px 0 0;
}
.league-group-header::after { display: none; }
.league-name { font-size: 12px; font-weight: 700; letter-spacing: 0; }
.league-group .matches-grid { border-radius: 0 0 6px 6px; }
.match-count-tag {
  background: transparent;
  color: var(--ff-text-muted);
  box-shadow: none;
  padding: 0 2px;
  border-radius: 0;
}
.match-count-num { color: var(--ff-text-strong); font-size: 15px; font-weight: 700; }
.match-count-unit { font-size: 12px; }
.matches-onboarding {
  margin: 10px clamp(12px, 2vw, 22px) 8px;
  padding: 8px 12px;
  border-radius: 6px;
  border: 1px solid var(--ff-border);
  background: #fff;
}
.matches-onboarding-copy {
  display: block;
  margin: 0;
  font-size: 13px;
  line-height: 1.45;
  color: var(--ff-text);
}
.matches-page,
.matches-layout,
.main-content { overflow-x: clip; }
.matches-page :deep(.el-container),
.matches-page :deep(.el-main) { min-width: 0; max-width: 100%; overflow-x: clip; }
.date-rail-item { overflow: visible; }
.date-rail-item small { white-space: normal; line-height: 1.25; overflow: visible; text-overflow: clip; }
@media (max-width: 768px) {
  .date-rail {
    overflow-x: auto;
    overflow-y: hidden;
    overscroll-behavior-x: contain;
    scrollbar-width: none;
    -ms-overflow-style: none;
  }
  .date-rail::-webkit-scrollbar { display: none; width: 0; height: 0; }
  .date-rail-arrow { display: flex; flex: 0 0 40px; width: 40px; min-width: 40px; min-height: 44px; }
  .date-rail-item { flex: 0 0 84px; min-width: 84px; max-width: 84px; min-height: 64px; padding: 8px 8px; }
  .date-group { padding: 0; }
  .main-content { padding: 10px; overflow-x: clip; }
}


/* r10: fixture list owns the page */
.matches-workspace { display: block; }
.matches-focus-sidebar { display: none; }
.match-list-panel :deep(.section-head) {
  margin-bottom: 8px;
  padding-bottom: 0;
  border-bottom: 0;
  position: static;
}
.match-list-panel :deep(.section-head > div:first-child) { display: none; }
.match-list-actions {
  flex-wrap: nowrap;
  overflow-x: auto;
  max-width: 100%;
  width: 100%;
  gap: 6px;
  align-items: center;
}
.filters-more {
  display: none;
  flex: none;
  align-items: center;
  justify-content: center;
  height: 40px;
  min-height: 40px;
  box-sizing: border-box;
  padding: 0 10px;
  border: 1px solid var(--ff-border);
  border-radius: 999px;
  background: #fff;
  color: var(--ff-text);
  font-size: 12px;
  line-height: 1.2;
  cursor: pointer;
}
@media (max-width: 768px) {
  .match-list-panel :deep(.section-head) { flex-direction: row; align-items: center; }
  .match-list-actions { flex-wrap: nowrap; align-items: center; }
  .match-list-actions .favorites-chip { flex: 0 0 auto; }
  .match-list-actions:not(.is-open) .team-filter,
  .match-list-actions:not(.is-open) .team-language-button,
  .match-list-actions:not(.is-open) .reminder-button,
  .match-list-actions:not(.is-open) .favorites-chip { display: none; }
  .filters-more { display: inline-flex; align-items: center; }
  .league-filter { flex: 0 0 132px; width: 132px; }
  .date-group-heading { display: none; }
}

/* r11: sports product filter — quieter than Element admin form */
.sport-filters {
  gap: 6px;
  padding: 2px 0;
}
.sport-filters .league-filter,
.sport-filters .team-filter { flex: none; }
.sport-filters .native-filter-select,
.sport-filters .native-filter-input {
  height: 40px;
  min-height: 40px;
  box-sizing: border-box;
  border-radius: 999px;
  border: 1px solid var(--ff-border);
  background: #fff;
  font-size: 12px;
  color: var(--ff-text);
}
.sport-filters .native-filter-select:focus,
.sport-filters .native-filter-input:focus {
  border-color: var(--ff-primary);
  box-shadow: 0 0 0 2px var(--ff-primary-soft);
}
.filter-chip {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 4px;
  flex: none;
  height: 40px;
  min-height: 40px;
  box-sizing: border-box;
  padding: 0 12px;
  border: 1px solid var(--ff-border);
  border-radius: 999px;
  background: #fff;
  color: var(--ff-text-muted);
  font: 500 12px/1.2 'Segoe UI', 'PingFang SC', sans-serif;
  cursor: pointer;
  transition: border-color var(--ff-transition-fast), color var(--ff-transition-fast), background var(--ff-transition-fast);
}
.filter-chip:hover,
.filter-chip:focus-visible {
  border-color: var(--ff-primary);
  color: var(--ff-primary);
  outline: none;
}
.filter-chip.active,
.filter-chip[aria-pressed="true"] {
  border-color: color-mix(in srgb, var(--ff-primary) 45%, var(--ff-border));
  background: var(--ff-primary-soft, rgba(15, 107, 77, 0.08));
  color: var(--ff-primary);
}
.filter-chip:disabled { opacity: 0.55; cursor: wait; }
.sport-filters .reminder-button .el-icon { font-size: 14px; }
.sport-filters .filters-more {
  border-radius: 999px;
  border-color: var(--ff-border);
  background: #fff;
  color: var(--ff-text-muted);
  font-weight: 500;
}
.sport-filters .match-count-tag {
  margin-left: 2px;
  padding: 0 4px;
  color: var(--ff-text-muted);
}
.sport-filters .match-count-num { font-size: 14px; font-weight: 700; color: var(--ff-text-strong); }
.sport-filters .match-count-unit { font-size: 12px; }




/* r13: mobile hard bugs — date-rail / filter overflow + onboarding under menu overlay */
.matches-page,
.main-content,
.matches-workspace,
.match-list-panel {
  max-width: 100%;
  min-width: 0;
  overflow-x: clip;
}
.match-list-actions {
  max-width: 100%;
  min-width: 0;
  /* internal horizontal scroll ok when collapsed; never grow page */
  scrollbar-width: none;
  -ms-overflow-style: none;
}
.match-list-actions::-webkit-scrollbar { display: none; width: 0; height: 0; }
.date-rail {
  width: 100%;
  max-width: 100%;
  min-width: 0;
  overflow-x: auto;
  overflow-y: hidden;
  overscroll-behavior-x: contain;
  scrollbar-width: none;
  -ms-overflow-style: none;
  box-sizing: border-box;
  /* padding-bottom must not expand page width */
  padding-left: 0;
  padding-right: 0;
}
.date-rail::-webkit-scrollbar { display: none; width: 0; height: 0; }
.matches-onboarding {
  position: relative;
  z-index: 1;
}
@media (max-width: 768px) {
  .matches-page,
  .matches-layout,
  .main-content,
  .matches-workspace,
  .match-list-panel {
    max-width: 100%;
    min-width: 0;
    overflow-x: clip;
  }
  .date-rail {
    width: 100%;
    max-width: 100%;
    min-width: 0;
    overflow-x: auto;
    overflow-y: hidden;
    overscroll-behavior-x: contain;
    scrollbar-width: none;
    -ms-overflow-style: none;
    -webkit-overflow-scrolling: touch;
  }
  .date-rail::-webkit-scrollbar { display: none; width: 0; height: 0; }
  .date-rail-item {
    flex: 0 0 84px;
    width: 84px;
    min-width: 84px;
    max-width: 84px;
    box-sizing: border-box;
  }
  /* collapsed filter row: scroll inside row only, hide bar */
  .match-list-actions,
  .sport-filters {
    max-width: 100%;
    min-width: 0;
    overflow-x: auto;
    overflow-y: hidden;
    scrollbar-width: none;
    -ms-overflow-style: none;
  }
  .match-list-actions::-webkit-scrollbar,
  .sport-filters::-webkit-scrollbar { display: none; width: 0; height: 0; }
  /* expanded filters: stack vertically — no sideways overflow */
  .match-list-actions.is-open,
  .sport-filters.is-open {
    flex-wrap: wrap !important;
    overflow-x: clip;
    overflow-y: visible;
    align-items: stretch;
  }
  .match-list-actions.is-open > *,
  .sport-filters.is-open > * {
    max-width: 100%;
    min-width: 0;
    box-sizing: border-box;
  }
  .match-list-actions.is-open .league-filter,
  .match-list-actions.is-open .team-filter,
  .match-list-actions.is-open .native-filter-select,
  .match-list-actions.is-open .native-filter-input,
  .sport-filters.is-open .league-filter,
  .sport-filters.is-open .team-filter,
  .sport-filters.is-open .native-filter-select,
  .sport-filters.is-open .native-filter-input {
    flex: 1 1 100% !important;
    width: 100% !important;
    max-width: 100% !important;
    min-width: 0 !important;
  }
  .match-list-actions.is-open .filter-chip,
  .match-list-actions.is-open .filters-more,
  .match-list-actions.is-open .favorites-chip,
  .match-list-actions.is-open .team-language-button,
  .match-list-actions.is-open .reminder-button,
  .sport-filters.is-open .filter-chip,
  .sport-filters.is-open .filters-more {
    flex: 1 1 auto;
    max-width: 100%;
    min-width: 0;
  }
  .match-list-actions.is-open .match-count-tag,
  .sport-filters.is-open .match-count-tag {
    flex: 1 1 100%;
    margin-left: 0;
  }
}

/* ============================================================
   r14 visual pass — matches page
   One centred column, a single date-rail control, a toolbar that
   reads as a toolbar, and a flat list panel whose rows carry the
   hierarchy (league strip > row > hairline).
   ============================================================ */

/* Narrower than the viewport on purpose: a fixture row reads best when the
   two team columns stay close to the score. Specificity note — the page shell
   sets `.matches-page .el-main { max-width:100% }`, so this needs the same
   three class-level weights to win. */
.matches-page .main-content {
  width: 100%;
  max-width: 1160px;
  margin: 0 auto;
  padding: 24px 28px 64px;
}

/* ---- date rail: one control instead of seven loose cards ---- */
.date-rail {
  gap: 6px;
  padding: 10px;
  margin: 0 0 16px;
  border: 1px solid var(--ff-border);
  border-radius: 14px;
  background: var(--ff-surface);
  box-shadow: var(--ff-shadow-sm);
}
.date-rail-arrow {
  flex: 0 0 34px;
  border: 0;
  border-radius: 10px;
  background: transparent;
  color: var(--ff-text-faint);
}
.date-rail-arrow:hover:not(:disabled),
.date-rail-arrow:focus-visible {
  border-color: transparent;
  background: var(--ff-surface-quiet);
  color: var(--ff-text-strong);
}
.date-rail-item {
  padding: 10px 14px 11px;
  border: 1px solid transparent;
  border-radius: 10px;
  background: transparent;
  color: var(--ff-text);
}
.date-rail-item:hover {
  border-color: transparent;
  background: var(--ff-surface-quiet);
  color: var(--ff-text-strong);
}
.date-rail-item.active {
  border-color: transparent;
  background: linear-gradient(180deg, #13795a 0%, #0f6048 100%);
  color: #fff;
  box-shadow: 0 8px 18px rgba(12, 90, 66, 0.22);
}
.date-rail-item span {
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.01em;
  opacity: 0.62;
}
.date-rail-item strong {
  margin: 6px 0 7px;
  font-family: var(--ff-sans);
  font-size: 23px;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
  letter-spacing: -0.03em;
  line-height: 1;
}
.date-rail-item small {
  font-size: 11px;
  font-weight: 500;
  letter-spacing: 0;
  opacity: 0.58;
}
.date-rail-item.active span,
.date-rail-item.active small { opacity: 0.84; }
/* The unit word wraps onto a second line in narrow rail cells; hide it there
   and keep the bare count, which reads fine next to the weekday. */
.date-rail-item .rail-unit,
.date-rail-item .rail-meta { display: inline; }
.date-rail-item small.rail-meta { white-space: nowrap; }
@media (max-width: 860px) {
  .date-rail-item .rail-unit { display: none; }
}

/* ---- panel + toolbar ---- */
.matches-workspace { display: block; }
/* PageSection's `.page-section.is-compact` padding has to be out-weighted so
   the league strips and rows run edge to edge inside the rounded panel. */
.matches-workspace .match-list-panel {
  padding: 0;
  border: 1px solid var(--ff-border);
  border-radius: 14px;
  background: var(--ff-surface);
  box-shadow: var(--ff-shadow-sm);
  overflow: hidden;
}
.matches-workspace .match-list-panel:hover { border-color: var(--ff-border); }
.match-list-panel :deep(.section-head) {
  margin: 0;
  padding: 12px 16px;
  border-bottom: 1px solid var(--ff-border);
  background: var(--ff-surface);
  gap: 10px;
}
.match-list-panel :deep(.section-actions) { width: 100%; }

.match-list-actions { gap: 8px; width: 100%; }
.native-filter-select,
.native-filter-input,
.filter-chip,
.filters-more {
  height: 34px;
  min-height: 34px;
  border: 1px solid var(--ff-border);
  border-radius: 8px;
  background: var(--ff-surface);
  color: var(--ff-text-muted);
  font-size: 12.5px;
  font-weight: 500;
  transition: border-color 120ms ease, background-color 120ms ease, color 120ms ease;
}
.native-filter-select:hover,
.native-filter-input:hover,
.filter-chip:hover,
.filters-more:hover {
  border-color: var(--ff-border-strong);
  background: var(--ff-surface);
  color: var(--ff-text-strong);
}
.native-filter-select:focus,
.native-filter-input:focus {
  outline: none;
  border-color: var(--ff-primary);
  box-shadow: 0 0 0 3px rgba(16, 105, 78, 0.12);
}
.league-filter { width: 176px; padding: 0 28px 0 12px; }
.team-filter,
.match-list-actions .team-filter {
  width: 200px;
  padding: 0 12px 0 32px;
  background-image: url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24' fill='none' stroke='%2397a29b' stroke-width='2' stroke-linecap='round'%3E%3Ccircle cx='11' cy='11' r='7'/%3E%3Cpath d='M20 20l-3.6-3.6'/%3E%3C/svg%3E");
  background-repeat: no-repeat;
  background-position: 10px 50%;
  background-size: 14px 14px;
}
.filter-chip { padding: 0 12px; }
.filter-chip[aria-pressed='true'],
.filter-chip.active {
  border-color: transparent;
  background: var(--ff-primary-soft);
  color: var(--ff-primary);
  font-weight: 600;
}
.filter-chip .el-icon { font-size: 13px; }
.match-count-tag {
  margin-left: auto;
  padding: 0;
  gap: 5px;
  border-radius: 0;
  background: transparent;
  color: var(--ff-text-faint);
  box-shadow: none;
}
.match-count-num {
  font-family: var(--ff-sans);
  font-size: 13px;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
  color: var(--ff-text-strong);
}
.match-count-unit { font-size: 12px; color: var(--ff-text-faint); }

@media (min-width: 769px) {
  .filters-more { display: none; }
}

/* ---- list ---- */
.date-group-list { gap: 0; }
.date-group,
.date-group:hover {
  padding: 0;
  border: 0;
  border-radius: 0;
  background: transparent;
}
.league-group { margin: 0; }
.league-group-header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0;
  padding: 9px 18px;
  background: var(--ff-surface-soft);
  border-top: 1px solid var(--ff-border);
  border-bottom: 1px solid var(--ff-border);
}
.league-group:first-child .league-group-header { border-top: 0; }
.league-group-header::before {
  content: '';
  flex: none;
  width: 3px;
  height: 13px;
  border-radius: 2px;
  background: var(--ff-primary);
}
.league-group-header::after { display: none; }
.league-group-header .league-name {
  font-size: 12.5px;
  font-weight: 700;
  letter-spacing: 0.01em;
  color: var(--ff-text-strong);
}
.league-group-header .league-count {
  margin-left: auto;
  font-size: 11px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
  color: var(--ff-text-faint);
}
.matches-grid { display: block; }

/* ---- onboarding hint: quiet, one line, aligned with the column ---- */
.matches-onboarding {
  width: 100%;
  max-width: 1160px;
  margin: 16px auto 0;
  padding: 10px 14px;
  gap: 12px;
  border: 1px solid var(--ff-border);
  border-radius: 12px;
  background: var(--ff-surface);
  color: var(--ff-text-muted);
  font-size: 12.5px;
  font-weight: 500;
  box-shadow: var(--ff-shadow-sm);
}
.matches-onboarding-copy { margin: 0; }
.matches-onboarding-dismiss {
  flex: none;
  margin-left: auto;
  height: 28px;
  padding: 0 12px;
  border: 1px solid rgba(16, 105, 78, 0.22);
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.72);
  color: var(--ff-primary);
  font-size: 12px;
  font-weight: 650;
  cursor: pointer;
}
.matches-onboarding-dismiss:hover { background: #fff; }

@media (max-width: 860px) {
  .main-content { padding: 14px 12px 44px; }
  .date-rail { padding: 8px; gap: 6px; border-radius: 12px; }
  .date-rail-item { flex: 0 0 auto; min-width: 92px; padding: 9px 12px 10px; }
  .date-rail-item strong { font-size: 20px; }
  .match-list-panel :deep(.section-head) { padding: 10px 12px; }
  .match-list-actions .team-filter { flex: 1 1 auto; width: auto; min-width: 0; }
  .league-filter { flex: 0 0 auto; width: 148px; }
  .match-count-tag { margin-left: 0; }
  .matches-onboarding { margin: 12px 12px 0; padding: 10px 12px; font-size: 12px; }
  .league-group-header { padding: 8px 14px; }
  .league-group-header .league-count { font-size: 10.5px; }
}
</style>
