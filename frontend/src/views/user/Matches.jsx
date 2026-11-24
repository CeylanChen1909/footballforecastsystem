/**
 * Matches 页（自 Vue src/views/user/Matches.vue 1712 行移植）。
 *
 * 对齐要点（契约 §1.4/§2.3/§3.7/§4）：
 *  - 顶栏/页脚由 App.jsx 全局挂载，本页只写页面内容，不再嵌 AppTopNav。
 *  - 接口：getMatchesWindow(1,300) 今日起窗口、getMatchesPage(1,100,date)
 *    按日分页拉满（preserveMeta 取 total）、getHeadToHead、matchApi.getDetails /
 *    refreshDetails、favoriteApi.listMatches/addMatch/removeMatch、
 *    userApi.getPreferences/updatePreferences、analyticsApi.track。
 *  - localStorage 键逐字照搬：ff_team_name_mode、football_matches_onboarding_dismissed_v1、
 *    football_matches_focus_collapsed、football_match_reminders（写回 slice(-50)，
 *    与 Prediction 页的 slice(-100) 不一致——照搬原版）、
 *    football_match_reminders_enabled、football_browser_notify_dismissed。
 *  - 提醒上限以代码为准：Matches 写回 slice(-50)；契约 §3.7 把两页的数字写反了，
 *    以代码为准（见报告）。
 *  - gsap 入场 stagger → CSS 动画（.reveal，0.32s + 每组 0.045s 延迟，见 matches.css；
 *    脚手架 §2.5 / 契约 §9.5：不引入 gsap）。
 *  - ElMessageBox.confirm（浏览器通知询问）→ 共享 ConfirmDialog（Kobalte，
 *    全量迁移组新增，恢复取消/关闭区分；此前 window.confirm 的语义差已消除）。
 *  - locale 变化时重拉全部数据（loadCurrentView + loadDateCounts + loadFavorites +
 *    loadHotMatches silent），与 Vue watch(locale) 同款。
 *  - 阶段 B（恢复渲染）：MatchFocusRail + PredictionDiscovery 在本页挂载，
 *    接 useMatchRecommendations（重命名为 hot*），恢复 scrollToPredictionDiscovery、
 *    openFocusMatch 与 discover=predict 的 watch。Vue 原版只 import 后 void 掉，
 *    这是本次唯一一处对 Vue 行为的有意偏离，见报告。
 */
import { createEffect, createMemo, createSignal, For, onSettled, Show } from 'solid-js'
import { useNavigate, useSearchParams } from '@solidjs/router'
import { analyticsApi, crawlerApi, favoriteApi, matchApi, userApi } from '../../api'
import auth from '../../stores/auth'
import { toastError, toastInfo, toastSuccess, toastWarning } from '../../utils/toast'
import { locale, t } from '../../i18n'
import { getBusinessDate, getLeagueDisplay, getTeamDisplay } from '../../utils/match'
import { displayLeagueName, displayTeamName, getLeagueDisplayName, getTeamSearchTokens, normalizeTeamSearch } from '../../utils/teamNames'
import { useMatchRecommendations } from '../../composables/useMatchRecommendations'
import PageState from '../../components/layout/PageState'
import MatchCard from '../../components/MatchCard'
import MatchCardSkeleton from '../../components/matches/MatchCardSkeleton'
import MatchFocusRail from '../../components/matches/MatchFocusRail'
import PredictionDiscovery from '../../components/matches/PredictionDiscovery'
import Modal from '../../components/ui/Modal'
import { createConfirm } from '../../components/ui/ConfirmDialog'
import { BellIcon } from '../../components/ui/icons'
import '../../styles/matches.css'
import '../../styles/page-common.css'

const TEAM_NAME_MODE_KEY = 'ff_team_name_mode'
const ROUTE_HOME_FALLBACK = '主队'
const ROUTE_AWAY_FALLBACK = '客队'
const ONBOARDING_KEY = 'football_matches_onboarding_dismissed_v1'
const FOCUS_COLLAPSE_KEY = 'football_matches_focus_collapsed'
const BROWSER_NOTIFY_DISMISS_KEY = 'football_browser_notify_dismissed'
const PRODUCTION_LEAGUES = ['英超', '西甲', '意甲', '德甲', '法甲', '荷甲', '葡超', '英冠', '欧冠', '欧国联']
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
  欧国联: 'matches.league.nationsLeague',
}

function buildSearch(obj) {
  const sp = new URLSearchParams()
  Object.entries(obj || {}).forEach(([k, v]) => {
    if (v === '' || v == null) return
    sp.set(k, String(v))
  })
  const s = sp.toString()
  return s ? `?${s}` : ''
}

export default function Matches() {
  const navigate = useNavigate()
  const [searchParams] = useSearchParams()
  // 共享确认弹窗（全量迁移组新增；替代 window.confirm，恢复取消/关闭区分）。
  const { ask: askConfirm, ConfirmDialog } = createConfirm()
  const [loading, setLoading] = createSignal(false)
  const [errorMsg, setErrorMsg] = createSignal('')
  const [dataQuality, setDataQuality] = createSignal({ status: 'UNKNOWN', statusText: '', message: '' })
  const [rawMatches, setRawMatches] = createSignal([])
  const [dateCountsCache, setDateCountsCache] = createSignal(new Map())
  const { items: hotMatches, meta: hotMeta, loading: hotLoading, error: hotError, stale: hotStale, load: loadHotMatches } = useMatchRecommendations({ limit: 6 })
  const [selectedDate, setSelectedDate] = createSignal('')
  const [currentPage, setCurrentPage] = createSignal(1)
  const [matchFavorites, setMatchFavorites] = createSignal([])
  const [selectedLeague, setSelectedLeague] = createSignal('all')
  const [teamKeyword, setTeamKeyword] = createSignal('')
  const [teamNameMode, setTeamNameMode] = createSignal(localStorage.getItem(TEAM_NAME_MODE_KEY) === 'en' ? 'en' : 'zh')
  const [onboardingVisible, setOnboardingVisible] = createSignal(false)
  const [focusCollapsed, setFocusCollapsed] = createSignal(false)
  const [moreFilters, setMoreFilters] = createSignal(false)
  const [countsReady, setCountsReady] = createSignal(false)
  const [onlyFavorites, setOnlyFavorites] = createSignal(false)
  const [remindersEnabled, setRemindersEnabled] = createSignal(localStorage.getItem('football_match_reminders_enabled') === '1')
  const [remindersChanging, setRemindersChanging] = createSignal(false)
  let reminderTimer = null
  let focusTimer = null
  let discoverScrolled = false

  const [h2hVisible, setH2hVisible] = createSignal(false)
  const [h2hLoading, setH2hLoading] = createSignal(false)
  const [h2hData, setH2hData] = createSignal(null)
  const [contentVisible, setContentVisible] = createSignal(false)
  const [contentMatch, setContentMatch] = createSignal(null)
  const [detailsLoading, setDetailsLoading] = createSignal(false)
  const [detailsRefreshing, setDetailsRefreshing] = createSignal(false)
  const [matchDetails, setMatchDetails] = createSignal(null)

  const formatLocalDate = d => getBusinessDate(d)
  const todayDate = createMemo(() => formatLocalDate(new Date()))

  const contentDialogTitle = createMemo(() => {
    const match = contentMatch()
    if (!match) return t('matches.matchDataTitle')
    return t('matches.matchDataTitleWithTeams', {
      home: matchTeamLabel(match, 'home') || t('matches.homeTeam'),
      away: matchTeamLabel(match, 'away') || t('matches.awayTeam'),
    })
  })
  const detailEvents = createMemo(() => Array.isArray(matchDetails()?.details?.events) ? matchDetails().details.events : [])
  const detailLineups = createMemo(() => Array.isArray(matchDetails()?.details?.lineups) ? matchDetails().details.lineups : [])
  const detailStats = createMemo(() => {
    const rows = Array.isArray(matchDetails()?.details?.statistics) ? matchDetails().details.statistics : []
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
  const prematchInjurySummary = createMemo(() => {
    const rows = Array.isArray(matchDetails()?.details?.injuries) ? matchDetails().details.injuries : []
    return rows.length ? t('matches.injuryCount', { count: rows.length }) : (matchDetails()?.statuses?.injuries === 'EMPTY' ? t('matches.noInjuries') : t('matches.toBeConfirmed'))
  })
  const prematchLineupSummary = createMemo(() => {
    if (!detailLineups().length) return matchDetails()?.statuses?.lineups === 'EMPTY' ? t('matches.toBeAnnounced') : t('matches.toBeConfirmed')
    const starters = detailLineups().reduce((sum, lineup) => sum + (Array.isArray(lineup?.startXI) ? lineup.startXI.length : 0), 0)
    return t('matches.startingCount', { count: starters })
  })
  const prematchStatsSummary = createMemo(() => {
    const xg = detailStats().find(row => String(row.type).toLowerCase().includes('expected goals') || String(row.type).toLowerCase() === 'xg')
    const shots = detailStats().find(row => String(row.type).toLowerCase() === 'total shots')
    if (xg) return `${xg.home} / ${xg.away}`
    if (shots) return t('matches.shots', { home: shots.home, away: shots.away })
    return matchDetails()?.statuses?.statistics === 'UNSUPPORTED' ? t('matches.planNotIncluded') : t('matches.none')
  })
  const detailStatus = createMemo(() => matchDetails()?.dataStatus || 'UNKNOWN')
  const detailStatusLabel = createMemo(() => {
    const status = detailStatus()
    const key = `matches.dataStatus.${status}`
    const translated = t(key)
    if (translated !== key) return translated
    return matchDetails()?.dataStatusText || t('matches.notChecked')
  })
  const detailStatusText = createMemo(() => {
    const key = {
      QUOTA_LIMITED: 'matches.statusQuotaLimitedText',
      NOT_CONFIGURED: 'matches.statusNotConfiguredText',
      PARTIAL: 'matches.statusPartialText',
      UNSUPPORTED: 'matches.statusUnsupportedText',
      EMPTY: 'matches.statusEmptyText',
    }[detailStatus()] || 'matches.statusUnavailableText'
    return t(key)
  })
  const detailStatusType = createMemo(() => detailStatus() === 'NORMAL' ? 'is-success' : detailStatus() === 'EMPTY' || detailStatus() === 'UNSUPPORTED' ? 'is-info' : 'is-warning')

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
    return new Intl.DateTimeFormat(locale() === 'zh' ? 'zh-CN' : 'en-US', { timeZone: 'Asia/Shanghai', weekday: 'long' }).format(new Date(`${date}T00:00:00+08:00`))
  }
  // 页内同名函数：经 getLeagueDisplayName 显示化（与 utils/match.js 的原始值版同名不同义，不得合并）。
  const getLeagueName = (match) => getLeagueDisplayName(match?.league?.name || match?.leagueName || match?.leagueDisplayName || match?.league?.nameEn || t('matches.leagueOther'))
  const displayTeamLabel = (team) => displayTeamName(
    team?.name || team?.teamName || '',
    team?.teamDisplayName || team?.displayName || team?.nameEn,
  )
  const matchTeamLabel = (match, side) => displayTeamName(
    match?.[`${side}TeamName`] || match?.teams?.[side]?.name || '',
    getTeamDisplay(match, side),
  )
  // All new actions use the stable local matchId. fixture.id remains a provider
  // compatibility field for legacy clients and must not be used as a route key.
  const getMatchId = (match) => match?.matchId || match?.id || match?.fixture?.id || match?.fixtureId || match?.externalMatchId
  const leagueLabel = (name, displayName = '') => {
    if (name) return displayLeagueName(name, displayName)
    const key = LEAGUE_LABEL_KEYS[name]
    return key ? t(key) : name
  }
  const leagueOptions = createMemo(() => [
    { label: t('matches.allLeagues'), value: 'all' },
    ...PRODUCTION_LEAGUES.map(name => ({ label: leagueLabel(name), value: name })),
  ])
  const filteredMatches = createMemo(() => {
    const matches = rawMatches() || []
    const keyword = normalizeTeamSearch(teamKeyword())
    const matchesTeamSearch = match => {
      if (!keyword) return true
      const names = [match?.teams?.home?.name || match?.homeTeamName, match?.teams?.away?.name || match?.awayTeamName]
      return names.flatMap(getTeamSearchTokens).some(name => normalizeTeamSearch(name).includes(keyword))
    }
    return matches.filter(match => {
      const leagueOk = selectedLeague() === 'all' || getLeagueName(match) === selectedLeague()
      const fixtureId = String(getMatchId(match) || '')
      const favoriteOk = !onlyFavorites() || matchFavorites().some(item => String(item.fixtureId || item.id || item.matchId || '') === fixtureId)
      return leagueOk && favoriteOk && matchesTeamSearch(match)
    })
  })
  const matchCount = createMemo(() => filteredMatches().length)
  const isOpsEmptyStatus = createMemo(() => ['SOURCE_LIMITED', 'SYNC_FAILED'].includes(dataQuality()?.status))
  /** Never surface ops-speak in fan UI (strict review FAIL). */
  const isOpsSpeakMessage = (msg) => {
    const s = String(msg || '')
    return !s ? false : (s.includes('主数据源') || s.includes('完成本次检查') || s.includes('已返回') || s.includes('部分赛事数据'))
  }
  const fanEmptyDescription = createMemo(() => selectedLeague() === 'all'
    ? t('matches.fanEmptyAll')
    : t('matches.fanEmptyLeague'))
  const emptyStateTitle = createMemo(() => {
    if (onlyFavorites()) {
      return matchFavorites().length ? t('matches.favoritesEmptyDay') : t('matches.favoritesEmpty')
    }
    if (isOpsEmptyStatus()) {
      if (dataQuality()?.status === 'SOURCE_LIMITED') return t('matches.sourceLimited')
      if (dataQuality()?.status === 'SYNC_FAILED') return t('matches.syncFailed')
    }
    return selectedLeague() === 'all' ? t('matches.emptyDay') : t('matches.emptyLeagueDay')
  })
  const emptyStateDescription = createMemo(() => {
    if (onlyFavorites()) {
      return matchFavorites().length
        ? t('matches.favoritesEmptyDayDesc')
        : t('matches.favoritesEmptyDesc')
    }
    if (isOpsEmptyStatus()) {
      const raw = dataQuality()?.message || ''
      if (!isOpsSpeakMessage(raw) && raw) return raw
      return dataQuality()?.status === 'SOURCE_LIMITED'
        ? t('matches.sourceLimitedDesc')
        : t('matches.syncFailedDesc')
    }
    return fanEmptyDescription()
  })
  const emptyStateActionText = createMemo(() => {
    if (onlyFavorites()) return matchFavorites().length ? t('matches.emptyActionAll') : t('matches.emptyActionBrowse')
    if (isOpsEmptyStatus()) return t('matches.retry')
    return selectedLeague() === 'all' ? t('matches.emptyActionYesterday') : t('matches.emptyActionAllLeagues')
  })
  const onEmptyStateAction = () => {
    if (onlyFavorites()) {
      setOnlyFavorites(false)
      return
    }
    if (isOpsEmptyStatus()) return loadCurrentView()
    if (selectedLeague() === 'all') return shiftDate(-1)
    setSelectedLeague('all')
  }
  const focusKeys = createMemo(() => {
    const ids = new Set()
    const keys = new Set()
    for (const item of hotMatches() || []) {
      const id = String(getMatchId(item) || '')
      if (id) ids.add(id)
      keys.add(matchIdentity(item))
    }
    return { ids, keys }
  })
  const isFocusMatch = (match) => {
    const id = String(getMatchId(match) || '')
    return Boolean((id && focusKeys().ids.has(id)) || focusKeys().keys.has(matchIdentity(match)))
  }

  const dateCounts = createMemo(() => {
    const counts = new Map(dateCountsCache())
    // `rawMatches` is the currently selected day's result.  That date is
    // already written into `dateCountsCache` by loadMatchesByDate(), so adding
    // it again here used to turn 6 matches into 12 after clicking the rail.
    // Only hydrate dates that are not present in the cache (for example when a
    // selected date falls outside the initial 7-day window).
    const currentDateCounts = new Map()
    ;(rawMatches() || []).forEach(match => {
      const date = getMatchDate(match)
      if (date) currentDateCounts.set(date, (currentDateCounts.get(date) || 0) + 1)
    })
    currentDateCounts.forEach((count, date) => {
      if (!counts.has(date)) counts.set(date, count)
    })
    return counts
  })
  const dateRail = createMemo(() => {
    const base = selectedDate() || todayDate()
    const today = todayDate()
    const tomorrow = formatLocalDate(new Date(Date.now() + 86400000))
    return [0, 1, 2, 3, 4, 5, 6].map(offset => {
      const date = new Date(`${base}T00:00:00`)
      date.setDate(date.getDate() + offset)
      const iso = formatLocalDate(date)
      const label = iso === today
        ? t('matches.today')
        : iso === tomorrow
          ? t('matches.tomorrow')
          : new Intl.DateTimeFormat(locale() === 'zh' ? 'zh-CN' : 'en-US', { month: 'short', timeZone: 'Asia/Shanghai' }).format(new Date(`${iso}T00:00:00+08:00`))
      return {
        date: iso,
        label,
        day: iso.slice(-2),
        weekday: new Intl.DateTimeFormat(locale() === 'zh' ? 'zh-CN' : 'en-US', { weekday: 'short' }).format(date),
        count: dateCounts().get(iso) || 0,
      }
    })
  })
  const selectRailDate = async (date) => {
    setSelectedDate(date)
    await loadMatchesByDate(date)
  }
  const shiftDate = async (delta) => {
    const base = selectedDate() || todayDate()
    const date = new Date(`${base}T00:00:00`)
    date.setDate(date.getDate() + delta)
    await selectRailDate(formatLocalDate(date))
  }
  const groupedMatches = createMemo(() => {
    const map = new Map()
    ;(filteredMatches() || []).forEach(match => {
      const date = getMatchDate(match) || 'unknown'
      if (!map.has(date)) map.set(date, [])
      map.get(date).push(match)
    })
    let staggerIndex = 0
    return Array.from(map.entries()).sort(([a], [b]) => a.localeCompare(b)).map(([date, items]) => {
      const leagueMap = new Map()
      items.forEach(m => {
        const lg = getLeagueName(m)
        if (!leagueMap.has(lg)) leagueMap.set(lg, { name: lg, displayName: getLeagueDisplay(m), items: [] })
        leagueMap.get(lg).items.push(m)
      })
      const leagueGroups = Array.from(leagueMap.values()).map(group => ({
        ...group,
        staggerIndex: staggerIndex++,
        items: group.items.sort((a, b) => String(a?.fixture?.date || '').localeCompare(String(b?.fixture?.date || ''))),
      }))
      return {
        date,
        label: formatDateLabel(date),
        weekday: weekdayLabel(date),
        items,
        leagueGroups,
      }
    })
  })
  const matchesHeading = createMemo(() => {
    const date = selectedDate() || todayDate()
    if (date === todayDate()) return t('matches.headingToday')
    const tomorrow = formatLocalDate(new Date(Date.now() + 86400000))
    if (date === tomorrow) return t('matches.headingTomorrow')
    return t('matches.headingDate', { date })
  })
  const toggleTeamNameMode = () => {
    const next = teamNameMode() === 'zh' ? 'en' : 'zh'
    setTeamNameMode(next)
    localStorage.setItem(TEAM_NAME_MODE_KEY, next)
  }

  const loadFavorites = async () => {
    try {
      const res = await favoriteApi.listMatches()
      setMatchFavorites(Array.isArray(res) ? res : (res?.items || []))
    } catch {
      setMatchFavorites([])
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
    if (selectedDate()) {
      await loadMatchesByDate(selectedDate())
    } else {
      await loadMatches()
    }
  }
  const loadMatches = async () => {
    setLoading(true)
    setErrorMsg('')
    try {
      const res = await crawlerApi.getMatchesWindow(1, 300)
      setDataQuality(scrubDataQuality(res?.dataQuality) || dataQuality())
      setRawMatches(deduplicateMatches(normalizeMatchList(res).filter(match => getMatchDate(match) >= todayDate())))
    } catch (e) {
      setErrorMsg(e.message || t('matches.loadFailed'))
    } finally {
      setLoading(false)
    }
  }

  const loadMatchesByDate = async (date) => {
    setLoading(true)
    setErrorMsg('')
    try {
      const firstPage = await crawlerApi.getMatchesPage(1, 100, date, { preserveMeta: true })
      setDataQuality(scrubDataQuality(firstPage?.dataQuality) || dataQuality())
      const total = Number(firstPage?.total) || normalizeMatchList(firstPage).length
      const pages = Math.max(1, Math.ceil(total / 100))
      const restPages = pages > 1
        ? await Promise.all(Array.from({ length: pages - 1 }, (_, index) => crawlerApi.getMatchesPage(index + 2, 100, date, { preserveMeta: true })))
        : []
      setRawMatches(deduplicateMatches([firstPage, ...restPages].flatMap(normalizeMatchList)))
      const nextCounts = new Map(dateCountsCache())
      nextCounts.set(date, rawMatches().length)
      setDateCountsCache(nextCounts)
    } catch (e) {
      setErrorMsg(e.message || t('matches.loadFailed'))
    } finally {
      setLoading(false)
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
      setDateCountsCache(counts)
      setCountsReady(true)
    } catch { /* 当前日期数据仍可独立展示 */ setCountsReady(true) }
  }

  const showH2H = async (fixtureId, homeTeamId, awayTeamId, homeTeamName, awayTeamName) => {
    const safeHomeName = homeTeamName || ''
    const safeAwayName = awayTeamName || ''
    if (!safeHomeName || !safeAwayName) return
    setH2hVisible(true)
    setH2hLoading(true)
    try {
      const res = await crawlerApi.getHeadToHead(safeHomeName, safeAwayName)
      const data = res?.data || res || {}
      setH2hData({ ...data, homeTeamName: safeHomeName, awayTeamName: safeAwayName, fixtureId, homeTeamId, awayTeamId })
    } catch (e) {
      setH2hData({ recentMatches: [], summary: { homeWins: 0, draws: 0, awayWins: 0 }, homeTeamName: safeHomeName, awayTeamName: safeAwayName, fixtureId, homeTeamId, awayTeamId })
    } finally {
      setH2hLoading(false)
    }
  }

  const openMatchDetails = async (match) => {
    const fixtureId = getMatchId(match)
    if (!fixtureId) {
      toastInfo(t('matches.missingMatchId'))
      return
    }
    analyticsApi.track('match_opened', { page: '/matches', entityType: 'match', entityId: fixtureId, properties: { league: match?.league?.name || match?.leagueName } }).catch(() => {})
    const homeTeamName = match?.teams?.home?.name || match?.homeTeamName || ''
    const awayTeamName = match?.teams?.away?.name || match?.awayTeamName || ''
    setContentMatch({ ...match, homeTeamName, awayTeamName, fixtureId, fixture: match?.fixture || { id: fixtureId } })
    setMatchDetails(null)
    setContentVisible(true)
    setDetailsLoading(true)
    try {
      const result = await matchApi.getDetails(fixtureId)
      setMatchDetails(result?.data || result)
    } catch {
      toastWarning(t('matches.detailsUnavailable'))
    } finally {
      setDetailsLoading(false)
    }
  }

  const scrollToPredictionDiscovery = async () => {
    if (discoverScrolled) return
    const first = (hotMatches() || [])[0]
    if (!first) return
    discoverScrolled = true
    await openFocusMatch(first)
  }

  const scrollToMatchList = () => {
    document.querySelector('.match-list-panel')?.scrollIntoView({ behavior: 'smooth', block: 'start' })
  }

  const focusMatchCard = async (match) => {
    const id = String(getMatchId(match) || '')
    if (!id) return
    await new Promise(resolve => requestAnimationFrame(() => resolve()))
    const target = Array.from(document.querySelectorAll('[data-match-id]')).find(node => node.dataset.matchId === id)
    if (!target) return
    target.scrollIntoView({ behavior: 'smooth', block: 'center' })
    target.classList.remove('focus-target')
    window.requestAnimationFrame(() => target.classList.add('focus-target'))
    window.setTimeout(() => target.classList.remove('focus-target'), 1800)
  }

  const openFocusMatch = async (match) => {
    const id = getMatchId(match)
    const current = rawMatches().find(item => String(getMatchId(item)) === String(id) || matchIdentity(item) === matchIdentity(match))
    if (current) {
      await focusMatchCard(current)
      return
    }
    const date = getMatchDate(match)
    if (date && date !== (selectedDate() || todayDate())) {
      await selectRailDate(date)
      await focusMatchCard(match)
      return
    }
    openMatchDetails(match)
  }

  const refreshMatchDetails = async () => {
    const fixtureId = getMatchId(contentMatch())
    if (!fixtureId) return
    setDetailsRefreshing(true)
    try {
      const res = await matchApi.refreshDetails(fixtureId)
      setMatchDetails(res?.data || res)
      toastSuccess(t('matches.detailsRefreshed'))
    } catch {
      toastWarning(t('matches.detailsRefreshFailed'))
    } finally {
      setDetailsRefreshing(false)
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
      navigate(`/prediction/${fixtureId}${buildSearch({
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
        returnDate: selectedDate() || '',
        returnLeague: selectedLeague() || 'all',
        returnKeyword: teamKeyword() || '',
        returnFavorites: onlyFavorites() ? '1' : '',
        returnPage: String(currentPage() || 1),
      })}`)
    }
  }

  const openAgent = (match) => {
    const matchId = getMatchId(match)
    if (!matchId) return
    navigate(`/agent${buildSearch({
      fixtureId: String(matchId),
      homeTeamId: match.teams?.home?.id || '',
      awayTeamId: match.teams?.away?.id || '',
      homeName: match.teams?.home?.name || ROUTE_HOME_FALLBACK,
      awayName: match.teams?.away?.name || ROUTE_AWAY_FALLBACK,
      leagueName: match.league?.name || '',
      matchTime: match.fixture?.date || '',
    })}`)
  }

  const goTeamSquad = (teamName, side, match) => {
    const selectedTeam = match?.teams?.[side] || {}
    const target = selectedTeam.name || teamName
    if (!target) return
    navigate(`/team/${encodeURIComponent(target)}/squad${buildSearch({
      name: target,
      teamDisplayName: getTeamDisplay(match, side),
      teamId: String(selectedTeam.id || ''),
      logo: selectedTeam.logo || '',
      league: match?.league?.name || match?.leagueName || '',
      leagueDisplay: getLeagueDisplay(match),
      season: String(new Date().getFullYear()),
    })}`)
  }

  const toggleMatchFavorite = async (match) => {
    const fixtureId = getMatchId(match)
    if (!fixtureId) return
    const exists = isFavoritedMatch(fixtureId)
    try {
      if (exists) {
        await favoriteApi.removeMatch(fixtureId)
        removeMatchReminder(fixtureId)
        toastSuccess(t('matches.unfavorited'))
      } else {
        const homeName = match?.teams?.home?.name || ROUTE_HOME_FALLBACK
        const awayName = match?.teams?.away?.name || ROUTE_AWAY_FALLBACK
        await favoriteApi.addMatch(fixtureId, `${homeName} vs ${awayName}`, {
          leagueName: match?.league?.name || match?.leagueName || '',
          matchTime: match?.fixture?.date || match?.matchTime || '',
        })
        saveMatchReminder({ fixtureId: String(fixtureId), title: `${homeName} vs ${awayName}`, matchTime: match?.fixture?.date || match?.matchTime || '', notified: false })
        toastSuccess(t('matches.favorited'))
        maybeAskBrowserNotifyAfterFavorite()
      }
      await loadFavorites()
    } catch (e) {
      const msg = String(e?.message || e?.response?.data?.message || '')
      if (msg.toLowerCase().includes('unauthorized')) {
        toastError(t('matches.sessionExpired'))
      } else {
        toastError(t('matches.favoriteFailed'))
      }
    }
  }

  const readMatchReminders = () => {
    try { return JSON.parse(localStorage.getItem('football_match_reminders') || '[]') } catch { return [] }
  }
  const writeMatchReminders = (items) => {
    const normalized = items.slice(-50)
    localStorage.setItem('football_match_reminders', JSON.stringify(normalized))
    if (auth.token()) {
      userApi.updatePreferences({ matchRemindersEnabled: remindersEnabled(), matchReminders: normalized }).catch(() => {})
    }
  }
  const saveMatchReminder = (item) => writeMatchReminders([...readMatchReminders().filter(existing => String(existing.fixtureId) !== String(item.fixtureId)), item])
  const removeMatchReminder = (fixtureId) => writeMatchReminders(readMatchReminders().filter(item => String(item.fixtureId) !== String(fixtureId)))
  const checkMatchReminders = () => {
    if (!remindersEnabled() || typeof Notification === 'undefined' || Notification.permission !== 'granted') return
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
  // 原版 ElMessageBox.confirm：点“取消”按钮才记 dismissed，点 × 关闭不记。
  // 全量迁移组改用共享 ConfirmDialog 恢复该区分（ask 返回 true/false/null）。
  const maybeAskBrowserNotifyAfterFavorite = async () => {
    if (typeof Notification === 'undefined') return
    if (Notification.permission !== 'default') return
    if (localStorage.getItem(BROWSER_NOTIFY_DISMISS_KEY) === '1') return
    const answer = await askConfirm({
      title: t('matches.browserNotifyTitle'),
      message: t('matches.browserNotifyPrompt'),
      confirmText: t('matches.browserNotifyConfirm'),
      cancelText: t('matches.browserNotifyCancel'),
    })
    if (answer === true) {
      const permission = await Notification.requestPermission()
      if (permission === 'granted' && !remindersEnabled()) {
        setRemindersEnabled(true)
        localStorage.setItem('football_match_reminders_enabled', '1')
      }
    } else if (answer === false) {
      localStorage.setItem(BROWSER_NOTIFY_DISMISS_KEY, '1')
    }
  }

  const enableMatchReminders = async () => {
    if (typeof Notification === 'undefined') return toastInfo(t('matches.browserUnsupported'))
    const permission = Notification.permission === 'default' ? await Notification.requestPermission() : Notification.permission
    if (permission !== 'granted') return toastWarning(t('matches.notificationsBlocked'))
    setRemindersEnabled(true)
    localStorage.setItem('football_match_reminders_enabled', '1')
    if (auth.token()) userApi.updatePreferences({ matchRemindersEnabled: true }).catch(() => {})
    toastSuccess(t('matches.remindersEnabled'))
    checkMatchReminders()
  }
  const disableMatchReminders = () => {
    setRemindersEnabled(false)
    localStorage.setItem('football_match_reminders_enabled', '0')
    if (auth.token()) userApi.updatePreferences({ matchRemindersEnabled: false }).catch(() => {})
    toastSuccess(t('matches.remindersDisabled'))
  }
  const toggleMatchReminders = async () => {
    if (remindersChanging()) return
    setRemindersChanging(true)
    try {
      if (remindersEnabled()) disableMatchReminders()
      else await enableMatchReminders()
    } finally {
      setRemindersChanging(false)
    }
  }

  const isFavoritedMatch = (fixtureId) => {
    const id = String(fixtureId)
    return matchFavorites().some(item => String(item.fixtureId || item.id || item.matchId || '') === id)
  }

  const dismissOnboarding = () => {
    setOnboardingVisible(false)
    localStorage.setItem(ONBOARDING_KEY, '1')
  }

  // --- 挂载（Vue onMounted 同款；Solid 2.0 用 onSettled，返回 cleanup） ---
  // 注意：Solid 2.0 在有未决异步时可能暂缓（hold）信号写入，set 后立即读
  // 信号可能仍是旧值（实测 onSettled 内 setSelectedDate 后读到 ''，导致
  // db/page 请求带空 date 挂起）。先取本地值，信号与 loader 都用本地值。
  onSettled(() => {
    setOnboardingVisible(localStorage.getItem(ONBOARDING_KEY) !== '1')
    const preferCollapse = localStorage.getItem(FOCUS_COLLAPSE_KEY)
    if (preferCollapse === '1' || preferCollapse === '0') {
      setFocusCollapsed(preferCollapse === '1')
    } else if (typeof window !== 'undefined' && window.matchMedia('(max-width: 768px)').matches) {
      setFocusCollapsed(true)
    }
    const initialDate = String(searchParams.returnDate || todayDate())
    const initialLeague = String(searchParams.returnLeague || 'all')
    const initialKeyword = String(searchParams.team || searchParams.returnKeyword || '')
    setSelectedDate(initialDate)
    setSelectedLeague(initialLeague)
    setTeamKeyword(initialKeyword)
    if (searchParams.league) setSelectedLeague(String(searchParams.league))
    setOnlyFavorites(searchParams.returnFavorites === '1')
    setCurrentPage(Number(searchParams.returnPage) || 1)
    void Promise.all([loadMatchesByDate(initialDate), loadDateCounts()])
    loadHotMatches()
    focusTimer = window.setInterval(() => loadHotMatches('', { silent: true }), 60 * 1000)
    loadFavorites()
    if (auth.token()) {
      userApi.getPreferences().then(res => {
        const raw = res?.preferences || res?.data?.preferences
        try {
          const prefs = typeof raw === 'string' ? JSON.parse(raw) : raw
          if (prefs?.matchRemindersEnabled === true) setRemindersEnabled(true)
          if (Array.isArray(prefs?.matchReminders)) {
            localStorage.setItem('football_match_reminders', JSON.stringify(prefs.matchReminders.slice(-50)))
          }
        } catch { /* ignore malformed preference */ }
      }).catch(() => {})
    }
    reminderTimer = window.setInterval(checkMatchReminders, 60 * 1000)
    if (searchParams.discover === 'predict') scrollToPredictionDiscovery()
    return () => {
      if (reminderTimer) window.clearInterval(reminderTimer)
      if (focusTimer) window.clearInterval(focusTimer)
    }
  })

  // Vue watch(route.query.discover, { immediate: true })：immediate 部分由 onSettled 覆盖，
  // 这里只处理挂载后 query 变化的情形。
  let firstDiscoverRun = true
  createEffect(
    () => searchParams.discover,
    (value) => {
      if (firstDiscoverRun) { firstDiscoverRun = false; return }
      if (value === 'predict') scrollToPredictionDiscovery()
    },
  )
  // Vue watch(hotMatches)：焦点数据到达后，若带 discover=predict 则滚动到精选区。
  // discover 必须进 compute：在 effect 回调里直接读会报 STRICT_READ_UNTRACKED。
  let firstHotRun = true
  createEffect(
    () => ({ items: hotMatches(), discover: searchParams.discover }),
    ({ discover }) => {
      if (firstHotRun) { firstHotRun = false; return }
      if (discover === 'predict') scrollToPredictionDiscovery()
    },
  )
  // Locale changes must refresh server-provided display fields (league/team
  // names, data-quality copy) instead of only re-rendering local translations.
  let firstLocaleRun = true
  createEffect(
    () => locale(),
    () => {
      if (firstLocaleRun) { firstLocaleRun = false; return }
      void Promise.allSettled([loadCurrentView(), loadDateCounts(), loadFavorites()])
      loadHotMatches('', { silent: true })
    },
  )

  return (
    <div class="matches-page">
      <Show when={onboardingVisible() && (loading() || matchCount() > 0)}>
        <div class="matches-onboarding" role="region" aria-label={t('matches.onboardingAria')}>
          <p class="matches-onboarding-copy">{t('matches.onboardingCopy')}</p>
          <button type="button" class="matches-onboarding-dismiss" aria-label={t('matches.onboardingDismissAria')} onClick={dismissOnboarding}>{t('matches.onboardingDismiss')}</button>
        </div>
      </Show>

      <div class="matches-main">
        <nav class="date-rail" aria-label={t('matches.dateNavAria', { heading: matchesHeading() })}>
          <button type="button" class="date-rail-arrow" disabled={loading()} aria-label={t('matches.previousDay')} title={t('matches.previousDay')} onClick={() => shiftDate(-1)}>
            ‹
          </button>
          <For each={dateRail()}>
            {(item) => (
              <button type="button" class={`date-rail-item ${item.date === (selectedDate() || todayDate()) ? 'active' : ''}`} disabled={loading()} onClick={() => selectRailDate(item.date)}>
                <span>{item.label}</span><strong>{item.day}</strong><small>{item.weekday} · {countsReady() ? t('matches.matchCount', { count: item.count }) : '…'}</small>
              </button>
            )}
          </For>
          <button type="button" class="date-rail-arrow" disabled={loading()} aria-label={t('matches.nextDay')} title={t('matches.nextDay')} onClick={() => shiftDate(1)}>
            ›
          </button>
        </nav>

        {/* 阶段 B：焦点栏 + 精选预测入口（Vue 原版 void 掉未渲染，此处恢复挂载） */}
        <MatchFocusRail
          items={hotMatches()}
          meta={hotMeta()}
          loading={hotLoading()}
          error={hotError() ? String(hotError().message || hotError()) : ''}
          stale={hotStale()}
          teamNameMode={teamNameMode()}
          onOpen={openFocusMatch}
          onPredict={goPredict}
          onRetry={() => loadHotMatches()}
          onViewAll={scrollToMatchList}
        />
        <PredictionDiscovery items={hotMatches()} teamNameMode={teamNameMode()} onPredict={goPredict} />

        <div class="matches-workspace">
          {/* 与脚手架 PageSection 同款 DOM（其组件不接受自定义 class，而
              .match-list-panel 选择器必须落在 section 本身上；空的首个 div
              是 r10 规则隐藏标题位所依赖的结构，保持与 PageSection 一致）。 */}
          <section class="ff-panel ff-page-section is-compact match-list-panel">
            <div class="ff-section-head">
              <div />
              <div class="ff-section-actions">
                <div class={`match-list-actions sport-filters ${moreFilters() ? 'is-open' : ''}`}>
                  <select value={selectedLeague()} class="league-filter native-filter-select" aria-label={t('matches.filterByLeague')} onChange={(e) => setSelectedLeague(e.currentTarget.value)}>
                    <For each={leagueOptions()}>{(option) => <option value={option.value}>{option.label}</option>}</For>
                  </select>
                  <input value={teamKeyword()} class="team-filter native-filter-input" type="search" enterkeyhint="search" placeholder={t('matches.searchTeamsPlaceholder')} aria-label={t('matches.filterByTeam')} onInput={(e) => setTeamKeyword(e.currentTarget.value)} />
                  <button type="button" class="filter-chip team-language-button" aria-pressed={teamNameMode() === 'en' ? 'true' : 'false'} aria-label={teamNameMode() === 'zh' ? t('matches.switchToEnglishNames') : t('matches.switchToChineseNames')} onClick={toggleTeamNameMode}>
                    {teamNameMode() === 'zh' ? t('matches.teamNameEnglish') : t('matches.teamNameChinese')}
                  </button>
                  <button type="button" class={`filter-chip favorites-chip ${onlyFavorites() ? 'active' : ''}`} aria-pressed={onlyFavorites() ? 'true' : 'false'} onClick={() => setOnlyFavorites(!onlyFavorites())}>{t('matches.onlyFavorites')}</button>
                  <button type="button" class={`filter-chip reminder-button ${remindersEnabled() ? 'active' : ''}`} aria-pressed={remindersEnabled() ? 'true' : 'false'} disabled={remindersChanging()} onClick={toggleMatchReminders}>
                    <BellIcon size={14} /><span class="reminder-label">{remindersEnabled() ? t('matches.remindersOn') : t('matches.reminders')}</span>
                  </button>
                  <button type="button" class="filters-more" aria-expanded={moreFilters() ? 'true' : 'false'} onClick={() => setMoreFilters(!moreFilters())}>{moreFilters() ? t('matches.collapseFilters') : t('matches.filters')}</button>
                  <div class="match-count-tag" aria-busy={loading() ? 'true' : 'false'}>
                    <span class="match-count-num">{loading() ? '…' : matchCount()}</span>
                    <span class="match-count-unit">{t('matches.matchCountUnit')}</span>
                  </div>
                </div>
              </div>
            </div>
            <div>
              <Show
                when={!loading()}
                fallback={
                  <div class="matches-skeleton" role="status" aria-live="polite" aria-busy="true" aria-label={t('matches.loadingMatchesAria')}>
                    <For each={[1, 2, 3, 4, 5, 6]}>{() => <MatchCardSkeleton />}</For>
                  </div>
                }
              >
                <Show
                  when={!errorMsg()}
                  fallback={<PageState type="error" title={errorMsg()} actionText={t('matches.retry')} onAction={loadCurrentView} />}
                >
                  <Show
                    when={filteredMatches().length > 0}
                    fallback={<PageState type="empty" title={emptyStateTitle()} description={emptyStateDescription()} actionText={emptyStateActionText()} onAction={onEmptyStateAction} />}
                  >
                    <div class="date-group-list">
                      <For each={groupedMatches()}>
                        {(group) => (
                          <div class="date-group reveal">
                            <For each={group.leagueGroups}>
                              {(lg) => (
                                <div class="league-group reveal" style={{ 'animation-delay': `${Math.min(lg.staggerIndex, 10) * 0.045}s` }}>
                                  <div class="league-group-header">
                                    <span class="league-name">{leagueLabel(lg.name, lg.displayName)}</span>
                                  </div>
                                  <div class="matches-grid">
                                    <For each={lg.items}>
                                      {(m) => (
                                        <MatchCard
                                          match={m}
                                          teamNameMode={teamNameMode()}
                                          favorited={isFavoritedMatch(getMatchId(m))}
                                          focused={isFocusMatch(m)}
                                          onPredict={goPredict}
                                          onTeamClick={goTeamSquad}
                                          onH2h={showH2H}
                                          onAgent={openAgent}
                                          onDetails={openMatchDetails}
                                          onFavoriteMatch={toggleMatchFavorite}
                                        />
                                      )}
                                    </For>
                                  </div>
                                </div>
                              )}
                            </For>
                          </div>
                        )}
                      </For>
                    </div>
                  </Show>
                </Show>
              </Show>
            </div>
          </section>
        </div>
      </div>

      {/* 历史交锋弹窗 */}
      <Modal open={h2hVisible()} title={t('matches.h2hTitle')} onClose={() => setH2hVisible(false)}>
        <Show
          when={!h2hLoading()}
          fallback={<div class="loading-state"><div class="h2h-skeleton"><i /><i /><i /><i /><i /></div></div>}
        >
          <Show when={h2hData()}>
            <div class="h2h-content">
              <div class="h2h-summary">
                <div class="summary-item">
                  <span class="summary-value">{h2hData().summary?.homeWins || 0}</span>
                  <span class="summary-label">{t('matches.homeWins')}</span>
                </div>
                <div class="summary-item">
                  <span class="summary-value">{h2hData().summary?.draws || 0}</span>
                  <span class="summary-label">{t('matches.draws')}</span>
                </div>
                <div class="summary-item">
                  <span class="summary-value">{h2hData().summary?.awayWins || 0}</span>
                  <span class="summary-label">{t('matches.awayWins')}</span>
                </div>
              </div>
              <div class="h2h-matches">
                <For each={h2hData().recentMatches || []}>
                  {(match, idx) => (
                    <div class="h2h-match">
                      <span class="h2h-date">{match.date}</span>
                      <span class="h2h-score">{match.homeScore} - {match.awayScore}</span>
                    </div>
                  )}
                </For>
                <Show when={!h2hData().recentMatches?.length}>
                  <div class="h2h-empty">{t('matches.noH2H')}</div>
                </Show>
              </div>
            </div>
          </Show>
        </Show>
      </Modal>

      {/* 赛事数据弹窗 */}
      <Modal open={contentVisible()} title={contentDialogTitle()} onClose={() => setContentVisible(false)} wide>
        <div class="match-detail-tabs">
          <Show
            when={!detailsLoading()}
            fallback={<PageState type="loading" title={t('matches.loadingDetails')} size={36} />}
          >
            <div class="match-detail-content">
              <div class="detail-source-line">
                <span>{t('matches.dataSource', { source: matchDetails()?.source || t('matches.notConnected') })}</span>
                <span class={`ff-tag ${detailStatusType()}`}>{detailStatusLabel()}</span>
                <Show when={matchDetails()?.fallback?.status === 'NORMAL'}>
                  <span class="ff-tag is-info">{t('matches.fallbackSchedule')}</span>
                </Show>
                <Show when={matchDetails()?.refreshable}>
                  <button type="button" class="ff-btn ff-btn-ghost ff-btn-sm" disabled={detailsRefreshing()} onClick={refreshMatchDetails}>{t('matches.refreshDetails')}</button>
                </Show>
              </div>
              <Show when={detailStatus() !== 'NORMAL'}>
                <div class="ff-alert-warning"><span>⚠</span><span>{detailStatusText()}</span></div>
              </Show>
              <Show
                when={detailEvents().length}
                fallback={<div class="detail-empty">{t('matches.noEvents')}</div>}
              >
                <div class="event-timeline">
                  <For each={detailEvents()}>
                    {(event, index) => (
                      <div class="event-row">
                        <span class="event-minute">{formatEventMinute(event)}</span>
                        <span class="event-team">{displayTeamLabel(event.team) || t('matches.matchEvent')}</span>
                        <span class="event-icon">{eventIcon(event)}</span>
                        <span class="event-main">{event.player?.name || event.type || t('matches.event')}</span>
                        <small>{event.detail || event.assist?.name || ''}</small>
                      </div>
                    )}
                  </For>
                </div>
              </Show>

              <Show when={detailStats().length}>
                <div class="detail-section-block">
                  <div class="detail-section-title">{t('matches.statistics')}</div>
                  <div class="detail-stat-grid">
                    <For each={detailStats()}>
                      {(row, index) => (
                        <div class="detail-stat-row">
                          <span>{row.label}</span><strong>{row.home}</strong><small>{row.type}</small><strong>{row.away}</strong>
                        </div>
                      )}
                    </For>
                  </div>
                </div>
              </Show>

              <div class="detail-section-block prematch-info-block">
                <div class="detail-section-title">{t('matches.prematchInfo')}</div>
                <div class="prematch-info-grid">
                  <div><span>{t('matches.injuries')}</span><strong>{prematchInjurySummary()}</strong></div>
                  <div><span>{t('matches.lineup')}</span><strong>{prematchLineupSummary()}</strong></div>
                  <div><span>{t('matches.xgShots')}</span><strong>{prematchStatsSummary()}</strong></div>
                </div>
                <small class="prematch-info-note">{t('matches.prematchNote')}</small>
              </div>

              <Show when={detailLineups().length}>
                <div class="detail-section-block">
                  <div class="detail-section-title">{t('matches.lineups')}</div>
                  <div class="lineup-grid">
                    <For each={detailLineups()}>
                      {(lineup, index) => (
                        <div class="lineup-card">
                          <strong>{displayTeamLabel(lineup.team) || t('matches.team')}</strong>
                          <span>{t('matches.formation', { formation: lineup.formation || t('matches.toBeAnnounced') })}</span>
                          <small>{t('matches.lineupSummary', { starters: lineup.startXI?.length || 0, subs: lineup.substitutes?.length || 0 })}</small>
                        </div>
                      )}
                    </For>
                  </div>
                </div>
              </Show>
            </div>
          </Show>
        </div>
      </Modal>
      <ConfirmDialog />
    </div>
  )
}
