<template>
  <div class="matches-page ff-page-shell">
    <AppTopNav
      title="ChenFootball"
      subtitle="比赛"
      :brand-icon="Football"
      active-path="/matches"
    >
      <template #actions>
        <ChangelogButton />
      </template>
    </AppTopNav>

    <div v-if="onboardingVisible" class="matches-onboarding" role="region" aria-label="使用引导">
      <div class="matches-onboarding-copy">
        <strong>快速上手</strong>
        <ol>
          <li>看赛程</li>
          <li>看预测</li>
          <li>收藏提醒</li>
          <li>试试 Agent</li>
        </ol>
      </div>
      <button type="button" class="matches-onboarding-dismiss" aria-label="关闭引导" @click="dismissOnboarding">知道了</button>
    </div>

    <el-container class="matches-layout">
      <el-main id="app-main" class="main-content" tabindex="-1">
        <nav class="date-rail" :aria-label="`${matchesHeading}日期导航`">
          <button type="button" class="date-rail-arrow" :disabled="loading" aria-label="查看前一天" title="查看前一天" @click="shiftDate(-1)">
            <el-icon><ArrowLeft /></el-icon>
          </button>
          <button v-for="item in dateRail" :key="item.date" type="button" class="date-rail-item" :class="{ active: item.date === (selectedDate || todayDate) }" :disabled="loading" @click="selectRailDate(item.date)">
            <span>{{ item.label }}</span><strong>{{ item.day }}</strong><small>{{ item.weekday }} · {{ countsReady ? (item.count + " 场") : "…" }}</small>
          </button>
          <button type="button" class="date-rail-arrow" :disabled="loading" aria-label="查看后一天" title="查看后一天" @click="shiftDate(1)">
            <el-icon><ArrowRight /></el-icon>
          </button>
        </nav>

        <PredictionDiscovery
          :items="hotMatches"
          :team-name-mode="teamNameMode"
          @predict="goPredict"
        />

        <div class="matches-workspace">
          <aside class="matches-focus-sidebar" aria-label="比赛焦点侧栏" :class="{ 'is-collapsed': focusCollapsed }">
            <div class="focus-collapse-bar">
              <span>比赛焦点</span>
              <button type="button" class="focus-collapse-btn" :aria-expanded="!focusCollapsed" :aria-label="focusCollapsed ? '展开焦点' : '收起焦点'" @click="toggleFocusCollapsed">
                {{ focusCollapsed ? '展开焦点' : '收起焦点' }}
              </button>
            </div>
            <MatchFocusRail
              v-show="!focusCollapsed"
              :items="hotMatches"
              :meta="hotMeta"
              :loading="hotLoading"
              :error="hotError"
              :stale="hotStale"
              :team-name-mode="teamNameMode"
              @open="openFocusMatch"
              @predict="goPredict"
              @retry="loadHotMatches()"
              @view-all="scrollToMatchList"
            />
          </aside>

          <!-- 比赛列表 -->
          <PageSection class="match-list-panel" title="比赛列表" subtitle="点击上方日期查看当天的比赛" variant="compact">
            <template #actions>
              <div class="match-list-actions">
                <el-select v-model="selectedLeague" class="league-filter" size="small" aria-label="按联赛筛选" popper-class="league-filter-popper">
                  <el-option v-for="option in leagueOptions" :key="option.value" :label="option.label" :value="option.value" />
                </el-select>
                <el-input v-model="teamKeyword" class="team-filter" size="small" clearable placeholder="球队" aria-label="按球队筛选" />
                <el-button class="team-language-button" size="small" plain :aria-label="teamNameMode === 'zh' ? '切换为英文球队名' : '切换为中文球队名'" @click="toggleTeamNameMode">
                  {{ teamNameMode === 'zh' ? 'English' : '中文名' }}
                </el-button>
                <el-checkbox v-model="onlyFavorites" size="small">只看收藏</el-checkbox>
                <el-button class="reminder-button" size="small" plain :loading="remindersChanging" :type="remindersEnabled ? 'success' : 'default'" @click="toggleMatchReminders">
                  <el-icon><Bell /></el-icon>{{ remindersEnabled ? '已开启提醒' : '开启提醒' }}
                </el-button>
                <div class="match-count-tag" :aria-busy="loading ? 'true' : 'false'">
                  <span class="match-count-num">{{ loading ? '…' : matchCount }}</span>
                  <span class="match-count-unit">场比赛</span>
                </div>
              </div>
            </template>

            <div v-if="loading" class="matches-skeleton" role="status" aria-live="polite" aria-busy="true" aria-label="正在加载比赛数据">
              <MatchCardSkeleton v-for="n in 6" :key="n" />
            </div>
            <PageState v-else-if="errorMsg" type="error" :title="errorMsg" action-text="重试" @action="loadCurrentView" />
            <template v-else>
              <div v-if="filteredMatches.length > 0" class="date-group-list">
                <div v-for="group in groupedMatches" :key="group.date" class="date-group reveal">
                  <div class="date-group-heading">
                    <div>
                      <span class="ff-kicker">{{ group.weekday }}</span>
                      <strong>{{ group.label }}</strong>
                    </div>
                  </div>

                  <div v-for="lg in group.leagueGroups" :key="lg.name" class="league-group">
                    <div class="league-group-header">
                      <span class="league-name">{{ lg.name }}</span>

                    </div>
                    <div class="matches-grid">
                      <MatchCard
                        v-for="m in lg.items"
                        :key="getMatchId(m)"
                        :match="m"
                        :team-name-mode="teamNameMode"
                        :favorited="isFavoritedMatch(getMatchId(m))"
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
              <PageState v-else :title="dataQuality.status === 'SOURCE_LIMITED' ? '数据源额度受限' : dataQuality.status === 'SYNC_FAILED' ? '数据同步失败' : (selectedLeague === 'all' ? '当天暂无比赛' : '该联赛当天暂无比赛')" :description="dataQuality.message || (selectedLeague === 'all' ? '点击左右箭头切换日期，或点击日期选择具体日期' : '可以切换其他联赛，或点击日期轨道查看其他赛程')" :action-text="['SOURCE_LIMITED','SYNC_FAILED'].includes(dataQuality.status) ? '重试' : (selectedLeague === 'all' ? '查看昨天' : '查看全部联赛')" @action="['SOURCE_LIMITED','SYNC_FAILED'].includes(dataQuality.status) ? loadCurrentView() : (selectedLeague === 'all' ? shiftDate(-1) : (selectedLeague = 'all'))" />
            </template>
          </PageSection>

        </div>
      </el-main>
    </el-container>

    <!-- 历史交锋弹窗 -->
    <el-dialog v-model="h2hVisible" title="历史交锋" width="min(600px, 94vw)">
      <div v-if="h2hLoading" class="loading-state">
        <el-skeleton :rows="5" animated />
      </div>
      <div v-else-if="h2hData" class="h2h-content">
        <div class="h2h-summary">
          <div class="summary-item">
            <span class="summary-value">{{ h2hData.summary?.homeWins || 0 }}</span>
            <span class="summary-label">主队胜</span>
          </div>
          <div class="summary-item">
            <span class="summary-value">{{ h2hData.summary?.draws || 0 }}</span>
            <span class="summary-label">平局</span>
          </div>
          <div class="summary-item">
            <span class="summary-value">{{ h2hData.summary?.awayWins || 0 }}</span>
            <span class="summary-label">客队胜</span>
          </div>
        </div>
        <div class="h2h-matches">
          <div v-for="(match, idx) in h2hData.recentMatches" :key="idx" class="h2h-match">
            <span class="h2h-date">{{ match.date }}</span>
            <span class="h2h-score">{{ match.homeScore }} - {{ match.awayScore }}</span>
          </div>
          <div v-if="!h2hData.recentMatches?.length" class="h2h-empty">暂无历史交锋记录</div>
        </div>
      </div>
    </el-dialog>

    <el-dialog v-model="contentVisible" :title="contentDialogTitle" width="min(820px, 94vw)">
      <div class="match-detail-tabs">
          <PageState v-if="detailsLoading" type="loading" title="正在读取赛事数据..." :size="36" />
          <div v-else class="match-detail-content">
            <div class="detail-source-line">
              <span>数据源：{{ matchDetails?.source || '未连接' }}</span>
              <el-tag size="small" :type="detailStatusType">{{ detailStatusLabel }}</el-tag>
              <el-tag v-if="matchDetails?.fallback?.status === 'NORMAL'" size="small" type="info">基础赛程已兜底</el-tag>
              <el-button v-if="matchDetails?.refreshable" size="small" text :loading="detailsRefreshing" @click="refreshMatchDetails">刷新赛事数据</el-button>
            </div>
            <el-alert v-if="detailStatusLabel !== '正常'" :title="detailStatusText" type="warning" :closable="false" show-icon />
            <div v-if="detailEvents.length" class="event-timeline">
              <div v-for="(event, index) in detailEvents" :key="'event-' + index" class="event-row">
                <span class="event-minute">{{ formatEventMinute(event) }}</span>
                <span class="event-team">{{ event.team?.name || '比赛事件' }}</span>
                <span class="event-icon">{{ eventIcon(event) }}</span>
                <span class="event-main">{{ event.player?.name || event.type || '事件' }}</span>
                <small>{{ event.detail || event.assist?.name || '' }}</small>
              </div>
            </div>
            <div v-else class="detail-empty">暂无事件时间线。若比赛尚未开始或联赛不提供事件数据，这里会保持为空。</div>

            <div v-if="detailStats.length" class="detail-section-block">
              <div class="detail-section-title">技术统计</div>
              <div class="detail-stat-grid">
                <div v-for="(row, index) in detailStats" :key="'stat-' + index" class="detail-stat-row">
                  <span>{{ row.label }}</span><strong>{{ row.home }}</strong><small>{{ row.type }}</small><strong>{{ row.away }}</strong>
                </div>
              </div>
            </div>

            <div class="detail-section-block prematch-info-block">
              <div class="detail-section-title">赛前信息</div>
              <div class="prematch-info-grid">
                <div><span>伤停</span><strong>{{ prematchInjurySummary }}</strong></div>
                <div><span>首发</span><strong>{{ prematchLineupSummary }}</strong></div>
                <div><span>xG / 射门</span><strong>{{ prematchStatsSummary }}</strong></div>
              </div>
              <small class="prematch-info-note">数据以最近一次赛前快照为准；首发通常在开赛前约一小时确认。</small>
            </div>

            <div v-if="detailLineups.length" class="detail-section-block">
              <div class="detail-section-title">阵容</div>
              <div class="lineup-grid">
                <div v-for="(lineup, index) in detailLineups" :key="'lineup-' + index" class="lineup-card">
                  <strong>{{ lineup.team?.name || '球队' }}</strong>
                  <span>阵型 {{ lineup.formation || '待公布' }}</span>
                  <small>首发 {{ lineup.startXI?.length || 0 }} 人 · 替补 {{ lineup.substitutes?.length || 0 }} 人</small>
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
import PredictionDiscovery from '../../components/matches/PredictionDiscovery.vue'
import { getBusinessDate } from '../../utils/match'
import { getTeamSearchTokens, normalizeTeamSearch } from '../../utils/teamNames'
import { useMatchRecommendations } from '../../composables/useMatchRecommendations'

// Defer focus rail + changelog off the matches critical path (LCP: date rail + list/skeleton).
const MatchFocusRail = defineAsyncComponent(() => import('../../components/matches/MatchFocusRail.vue'))
const ChangelogButton = defineAsyncComponent(() => import('../../components/matches/ChangelogButton.vue'))

const router = useRouter()
const route = useRoute()
const userStore = useUserStore()
const loading = ref(false)
const errorMsg = ref('')
const dataQuality = ref({ status: 'UNKNOWN', statusText: '未检测', message: '' })
const rawMatches = ref([])
const dateCountsCache = ref(new Map())
const { items: hotMatches, meta: hotMeta, loading: hotLoading, error: hotError, stale: hotStale, load: loadHotMatches } = useMatchRecommendations({ limit: 6 })
const selectedDate = ref('')
const currentPage = ref(1)
const matchFavorites = ref([])
const selectedLeague = ref('all')
const teamKeyword = ref('')
const teamNameMode = ref(localStorage.getItem('football_team_name_mode') === 'zh' ? 'zh' : 'en')
const ONBOARDING_KEY = 'football_matches_onboarding_dismissed_v1'
const FOCUS_COLLAPSE_KEY = 'football_matches_focus_collapsed'
const onboardingVisible = ref(false)
const focusCollapsed = ref(false)
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
  if (!match) return '赛事数据'
  return (match.homeTeamName || '主队') + ' vs ' + (match.awayTeamName || '客队') + ' · 赛事数据'
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
    const team = teamBlock?.team?.name || '球队'
    ;(teamBlock?.statistics || []).forEach(stat => {
      const type = stat?.type || '统计'
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
  return home && draw && away ? `${home.toFixed(2)} / ${draw.toFixed(2)} / ${away.toFixed(2)}` : '暂无'
})
const prematchInjurySummary = computed(() => {
  const rows = Array.isArray(matchDetails.value?.details?.injuries) ? matchDetails.value.details.injuries : []
  return rows.length ? `${rows.length} 人` : (matchDetails.value?.statuses?.injuries === 'EMPTY' ? '暂无伤停' : '待确认')
})
const prematchLineupSummary = computed(() => {
  if (!detailLineups.value.length) return matchDetails.value?.statuses?.lineups === 'EMPTY' ? '待公布' : '待确认'
  const starters = detailLineups.value.reduce((sum, lineup) => sum + (Array.isArray(lineup?.startXI) ? lineup.startXI.length : 0), 0)
  return `${starters} 名首发`
})
const prematchStatsSummary = computed(() => {
  const xg = detailStats.value.find(row => String(row.type).toLowerCase().includes('expected goals') || String(row.type).toLowerCase() === 'xg')
  const shots = detailStats.value.find(row => String(row.type).toLowerCase() === 'total shots')
  if (xg) return `${xg.home} / ${xg.away}`
  if (shots) return `射门 ${shots.home} / ${shots.away}`
  return matchDetails.value?.statuses?.statistics === 'UNSUPPORTED' ? '套餐不提供' : '暂无'
})
const detailStatus = computed(() => matchDetails.value?.dataStatus || 'UNKNOWN')
const detailStatusLabel = computed(() => matchDetails.value?.dataStatusText || '未检测')
const detailStatusText = computed(() => detailStatus.value === 'QUOTA_LIMITED'
  ? '外部赛事数据源当前受额度或套餐限制，已保留本地缓存。'
  : detailStatus.value === 'NOT_CONFIGURED'
    ? '尚未配置 API-Football 密钥，当前只能展示本地比赛基础数据。'
    : detailStatus.value === 'PARTIAL'
      ? '部分赛事数据已返回，其余数据暂不可用。'
      : detailStatus.value === 'UNSUPPORTED'
        ? '该比赛阶段暂不提供这类赛事数据。'
      : detailStatus.value === 'EMPTY'
        ? '数据源响应为空，该联赛可能暂不提供这类赛事数据。'
        : '赛事数据源暂时不可用，稍后可以再次刷新。')
const detailStatusType = computed(() => detailStatus.value === 'NORMAL' ? 'success' : detailStatus.value === 'EMPTY' || detailStatus.value === 'UNSUPPORTED' ? 'info' : 'warning')

const getMatchDate = (match) => match?.matchDate || String(match?.fixture?.date || '').slice(0, 10)
const formatDateLabel = (date) => {
  if (!date) return '日期未知'
  const today = formatLocalDate(new Date())
  const tomorrow = formatLocalDate(new Date(Date.now() + 86400000))
  const yesterday = formatLocalDate(new Date(Date.now() - 86400000))
  if (date === today) return `${date} · 今天`
  if (date === tomorrow) return `${date} · 明天`
  if (date === yesterday) return `${date} · 昨天`
  return date
}
const weekdayLabel = (date) => {
  if (!date) return '-'
  return new Intl.DateTimeFormat('zh-CN', { timeZone: 'Asia/Shanghai', weekday: 'long' }).format(new Date(`${date}T00:00:00+08:00`))
}
const getLeagueName = (match) => match?.league?.name || match?.leagueName || '其他联赛'
// All new actions use the stable local matchId. fixture.id remains a provider
// compatibility field for legacy clients and must not be used as a route key.
const getMatchId = (match) => match?.matchId || match?.id || match?.fixture?.id || match?.fixtureId || match?.externalMatchId
const leagueOptions = computed(() => {
  const names = Array.from(new Set((rawMatches.value || []).map(getLeagueName).filter(Boolean)))
    .sort((a, b) => String(a).localeCompare(String(b), 'zh-CN'))
  return [{ label: '全部联赛', value: 'all' }, ...names.map(name => ({ label: name, value: name }))]
})
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
    const label = iso === today ? '今天' : iso === tomorrow ? '明天' : `${iso.slice(5, 7)}月`
    return {
      date: iso,
      label,
      day: iso.slice(-2),
      weekday: new Intl.DateTimeFormat('zh-CN', { weekday: 'short' }).format(date),
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
      if (!leagueMap.has(lg)) leagueMap.set(lg, [])
      leagueMap.get(lg).push(m)
    })
    const leagueGroups = Array.from(leagueMap.entries()).map(([name, list]) => ({
      name,
      items: list.sort((a, b) => String(a?.fixture?.date || '').localeCompare(String(b?.fixture?.date || '')))
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
  if (date === todayDate.value) return '今日比赛'
  const tomorrow = formatLocalDate(new Date(Date.now() + 86400000))
  if (date === tomorrow) return '明日比赛'
  return `${date}比赛`
})
const favoritesCount = computed(() => matchFavorites.value?.length || 0)
const toggleTeamNameMode = () => {
  teamNameMode.value = teamNameMode.value === 'zh' ? 'en' : 'zh'
  localStorage.setItem('football_team_name_mode', teamNameMode.value)
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
    dataQuality.value = res?.dataQuality || dataQuality.value
    rawMatches.value = deduplicateMatches(normalizeMatchList(res).filter(match => getMatchDate(match) >= todayDate.value))
    if (rawMatches.value.length === 0) {
      ElMessage.info('暂无今日比赛数据，可点击日期切换查看历史比赛')
    }
  } catch (e) {
    errorMsg.value = e.message || '加载比赛失败，请稍后重试'
  } finally {
    loading.value = false
  }
}

const loadMatchesByDate = async (date) => {
  loading.value = true
  errorMsg.value = ''
  try {
    const firstPage = await crawlerApi.getMatchesPage(1, 100, date, { preserveMeta: true })
    dataQuality.value = firstPage?.dataQuality || dataQuality.value
    const total = Number(firstPage?.total) || normalizeMatchList(firstPage).length
    const pages = Math.max(1, Math.ceil(total / 100))
    const restPages = pages > 1
      ? await Promise.all(Array.from({ length: pages - 1 }, (_, index) => crawlerApi.getMatchesPage(index + 2, 100, date, { preserveMeta: true })))
      : []
    rawMatches.value = deduplicateMatches([firstPage, ...restPages].flatMap(normalizeMatchList))
    const nextCounts = new Map(dateCountsCache.value)
    nextCounts.set(date, rawMatches.value.length)
    dateCountsCache.value = nextCounts
    if (rawMatches.value.length === 0) ElMessage.info('该日期暂无比赛数据，可尝试其他日期')
  } catch (e) {
    errorMsg.value = e.message || '加载比赛失败，请稍后重试'
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
    ElMessage.info('当前比赛缺少可关联的赛事编号')
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
    ElMessage.warning('赛事数据暂时不可用，请稍后重试')
  } finally {
    detailsLoading.value = false
  }
}


const scrollToPredictionDiscovery = async () => {
  await nextTick()
  const el = document.getElementById('prediction-discovery')
  if (el) el.scrollIntoView({ behavior: 'smooth', block: 'start' })
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
    ElMessage.success('赛事数据已刷新')
  } catch {
    ElMessage.warning('赛事数据刷新失败，请稍后重试')
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

  if (typeof matchOrFixtureId === 'object' && matchOrFixtureId !== null) {
    const match = matchOrFixtureId
    fixtureId = getMatchId(match)
    hId = match.teams?.home?.id || 0
    aId = match.teams?.away?.id || 0
    homeTeamName = match.teams?.home?.name || '主队'
    awayTeamName = match.teams?.away?.name || '客队'
    homeLogo = match.teams?.home?.logo || ''
    awayLogo = match.teams?.away?.logo || ''
    leagueName = match.league?.name || ''
    leagueId = match.league?.id || ''
    round = match.league?.round || ''
    matchTime = match.fixture?.date || ''
  } else {
      fixtureId = matchOrFixtureId
    hId = homeId || 0
    aId = awayId || 0
    homeTeamName = homeTeamNameArg || '主队'
    awayTeamName = awayTeamNameArg || '客队'
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
        homeLogo,
        awayLogo,
        leagueName,
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
      homeName: match.teams?.home?.name || '主队',
      awayName: match.teams?.away?.name || '客队',
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
      teamId: String(selectedTeam.id || ''),
      logo: selectedTeam.logo || '',
      league: match?.league?.name || match?.leagueName || '',
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
      ElMessage.success('已取消收藏比赛')
    } else {
      const homeName = match?.teams?.home?.name || '主队'
      const awayName = match?.teams?.away?.name || '客队'
      await favoriteApi.addMatch(fixtureId, `${homeName} vs ${awayName}`, {
        leagueName: match?.league?.name || match?.leagueName || '',
        matchTime: match?.fixture?.date || match?.matchTime || ''
      })
      saveMatchReminder({ fixtureId: String(fixtureId), title: `${homeName} vs ${awayName}`, matchTime: match?.fixture?.date || match?.matchTime || '', notified: false })
      ElMessage.success('比赛收藏成功，开赛前将站内提醒')
      maybeAskBrowserNotifyAfterFavorite()
    }
    await loadFavorites()
  } catch (e) {
    const msg = String(e?.message || e?.response?.data?.message || '')
    if (msg.toLowerCase().includes('unauthorized')) {
      ElMessage.error('登录已过期，请重新登录')
    } else {
      ElMessage.error('收藏操作失败，请先登录')
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
    new Notification('比赛即将开始', { body: `${item.title} · 30 分钟内开赛`, tag: `football-${item.fixtureId}` })
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
      '收藏后可在开赛前收到站内提醒。是否同时开启浏览器通知？可随时忽略。',
      '开启浏览器通知',
      { confirmButtonText: '开启', cancelButtonText: '暂时不用', type: 'info', distinguishCancelAndClose: true }
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
  if (typeof Notification === 'undefined') return ElMessage.info('当前浏览器不支持开赛提醒')
  const permission = Notification.permission === 'default' ? await Notification.requestPermission() : Notification.permission
  if (permission !== 'granted') return ElMessage.warning('请在浏览器设置中允许通知后再开启提醒')
  remindersEnabled.value = true
  localStorage.setItem('football_match_reminders_enabled', '1')
  if (userStore.token) userApi.updatePreferences({ matchRemindersEnabled: true }).catch(() => {})
  ElMessage.success('已开启收藏比赛的开赛提醒')
  checkMatchReminders()
}
const disableMatchReminders = () => {
  remindersEnabled.value = false
  localStorage.setItem('football_match_reminders_enabled', '0')
  if (userStore.token) userApi.updatePreferences({ matchRemindersEnabled: false }).catch(() => {})
  ElMessage.success('已关闭开赛提醒')
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
.date-rail { display:flex; gap:8px; align-items:stretch; overflow-x:auto; overscroll-behavior-x:contain; -webkit-overflow-scrolling:touch; padding:2px 0 16px; width:100%; max-width:100%; min-width:0; }
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
.league-filter :deep(.el-input__wrapper) { box-shadow: inset 0 0 0 1px var(--ff-border); background: var(--ff-surface-quiet); }
.league-filter :deep(.el-input__wrapper.is-focus) { box-shadow: inset 0 0 0 1px var(--ff-primary); }

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
  .match-list-actions .el-checkbox { flex:1 1 100%; }
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

</style>
