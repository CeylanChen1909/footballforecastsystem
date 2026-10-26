import { locale, t } from '../i18n'

const dateLocale = () => (locale.value === 'zh' ? 'zh-CN' : 'en-US')

const LIVE_STATUSES = ['LIVE', 'IN_PLAY', 'PAUSED', 'HT', '1H', '2H', 'ET']
const FINISHED_STATUSES = ['FT', 'AET', 'PEN', 'FINISHED']
const CANCELLED_STATUSES = ['CANC', 'CANCELED', 'CANCELLED', 'PST', 'POSTPONED', 'PPD', 'ABD', 'AWD', 'WO', 'SOURCE_REMOVED']

export const normalizeList = value => Array.isArray(value)
  ? value
  : Array.isArray(value?.items) ? value.items
    : Array.isArray(value?.records) ? value.records
      : Array.isArray(value?.response) ? value.response : []

const firstDisplayText = (...values) => values
  .map(value => (value == null ? '' : String(value).trim()))
  .find(Boolean) || ''
const hasChinese = value => /[\u4e00-\u9fff]/.test(String(value || ''))
const isEnglishLabel = value => /[A-Za-z]/.test(String(value || '')) && !hasChinese(value)
// Legacy payloads may keep the canonical name in `teams.*.name`/`league.name`
// and put the localised value in a top-level field. Only accept that field
// when it matches the active locale so internal filter values never leak into
// the wrong language.
const topLevelDisplay = (value, source) => {
  if (!value || value === source) return ''
  return locale.value === 'en' ? (isEnglishLabel(value) ? value : '') : (hasChinese(value) ? value : '')
}

export const getMatchId = match => String(match?.matchId || match?.id || match?.fixtureId || match?.fixture?.id || match?.externalMatchId || '')
export const getHomeTeam = match => match?.teams?.home || { name: match?.homeTeamName, logo: match?.homeTeamLogo, id: match?.homeTeamId }
export const getAwayTeam = match => match?.teams?.away || { name: match?.awayTeamName, logo: match?.awayTeamLogo, id: match?.awayTeamId }
export const getHomeName = match => getHomeTeam(match)?.name || match?.homeTeamName || t('matches.homeTeam')
export const getAwayName = match => getAwayTeam(match)?.name || match?.awayTeamName || t('matches.awayTeam')
export const getHomeLogo = match => getHomeTeam(match)?.logo || match?.homeTeamLogo || ''
export const getAwayLogo = match => getAwayTeam(match)?.logo || match?.awayTeamLogo || ''
export const getLeagueName = match => match?.league?.name || match?.leagueName || t('matches.leaguePending')

/** Optional backend-localised display values; empty strings mean "not provided". */
export const getTeamDisplay = (match, side = 'home') => {
  const team = side === 'home' ? getHomeTeam(match) : getAwayTeam(match)
  const explicit = firstDisplayText(
    team?.teamDisplayName,
    team?.teamDisplay,
    team?.displayName,
    team?.display,
    team?.nameEn,
    match?.[`${side}TeamDisplayName`],
    match?.[`${side}TeamDisplay`],
    match?.[`${side}TeamNameEn`]
  )
  if (explicit) return explicit
  return topLevelDisplay(match?.[`${side}TeamName`] || '', team?.name || '')
}

export const getLeagueDisplay = match => firstDisplayText(
  match?.league?.leagueDisplay,
  match?.league?.leagueDisplayName,
  match?.league?.displayName,
  match?.league?.display,
  match?.league?.nameEn,
  match?.leagueDisplay,
  match?.leagueDisplayName,
  topLevelDisplay(match?.leagueName || '', match?.league?.name || ''),
  // The backend already localises league.name; keep it as the last fallback
  // so an unknown competition still shows a label instead of going blank.
  match?.league?.name
)

export const getMatchDate = match => match?.matchDate || String(match?.fixture?.date || match?.matchTime || '').slice(0, 10)

const todayInBusinessZone = () => new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Shanghai' }).format(new Date())
export const getBusinessDate = value => {
  const date = value instanceof Date ? value : new Date(value)
  return Number.isNaN(date.getTime()) ? '' : new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Shanghai' }).format(date)
}
const hasSyntheticBbcTime = match => {
  if (String(match?.source || '').toLowerCase() !== 'bbc-scores') return false
  const value = String(match?.fixture?.date || match?.matchTime || '')
  return /(?:t|\s)00:00(?::00)?(?:z|[+.\-]|$)/i.test(value)
}

export const getMatchTimestamp = match => {
  const value = match?.fixture?.date || match?.matchTime
  if (!value) return 0
  const normalized = String(value).includes('T') ? String(value) : String(value).replace(' ', 'T')
  const withZone = /(?:Z|[+-]\d{2}:?\d{2})$/i.test(normalized) ? normalized : `${normalized}+08:00`
  const timestamp = new Date(withZone).getTime()
  return Number.isNaN(timestamp) ? 0 : timestamp
}

export const getStatusKey = match => String(match?.fixture?.status?.short || match?.status || '').toUpperCase()
export const getDisplayStatusKey = match => {
  const key = getStatusKey(match)
  // BBC uses 00:00 as a placeholder when the kickoff clock is not published.
  // Never trust a LIVE flag together with that synthetic timestamp: it makes
  // a whole day of future fixtures look like live matches in the UI.
  if (hasSyntheticBbcTime(match)) {
    return getMatchDate(match) < todayInBusinessZone() ? 'STALE' : (LIVE_STATUSES.includes(key) ? 'NS' : key)
  }
  // Some crawlers mark every fixture as LIVE. A future kickoff is never live.
  // A provider occasionally keeps LIVE on a fixture while the kickoff is still
  // in the future.  A future fixture must never be rendered as live; the two
  // minute grace period only covers clock skew between the source and browser.
  if (LIVE_STATUSES.includes(key) && getMatchTimestamp(match) > Date.now() + 2 * 60 * 1000) return 'NS'
  // A stale NS row must not be presented as an upcoming match after kickoff.
  // Keep it visible in a date view, but make the missing status explicit.
  if ((key === 'NS' || !key) && getMatchTimestamp(match) && getMatchTimestamp(match) < Date.now() - 30 * 60 * 1000) return 'STALE'
  return key
}
export const isLive = match => LIVE_STATUSES.includes(getDisplayStatusKey(match))
export const isFinished = match => FINISHED_STATUSES.includes(getDisplayStatusKey(match))
export const isCancelled = match => CANCELLED_STATUSES.includes(getDisplayStatusKey(match))
export const isUpcoming = match => !isFinished(match) && !isCancelled(match) && getDisplayStatusKey(match) !== 'STALE'
const STATUS_TEXT_KEYS = {
  NS: 'matches.status.NS',
  LIVE: 'matches.status.LIVE',
  IN_PLAY: 'matches.status.LIVE',
  PAUSED: 'matches.status.PAUSED',
  HT: 'matches.status.HT',
  '1H': 'matches.status.1H',
  '2H': 'matches.status.2H',
  ET: 'matches.status.ET',
  FT: 'matches.status.FT',
  AET: 'matches.status.FT',
  PEN: 'matches.status.FT',
  FINISHED: 'matches.status.FT',
  PST: 'matches.status.PST',
  POSTPONED: 'matches.status.PST',
  CANC: 'matches.status.CANC',
  CANCELLED: 'matches.status.CANC',
  STALE: 'matches.status.STALE',
  SOURCE_REMOVED: 'matches.status.SOURCE_REMOVED'
}
export const getStatusText = match => t(STATUS_TEXT_KEYS[getDisplayStatusKey(match)] || 'matches.statusPending')
export const getStatusClass = match => isLive(match) ? 'live' : isFinished(match) ? 'finished' : isCancelled(match) ? 'cancelled' : getDisplayStatusKey(match) === 'STALE' ? 'stale' : 'upcoming'

export const normalizeMatches = value => {
  const seen = new Set()
  return normalizeList(value).filter(item => {
    const fallback = `${getLeagueName(item)}-${getHomeName(item)}-${getAwayName(item)}-${getMatchDate(item)}`
    const id = getMatchId(item) || fallback
    if (seen.has(id)) return false
    seen.add(id)
    return true
  })
}

export const formatMatchTime = (match, options = {}) => {
  const timestamp = getMatchTimestamp(match)
  if (!timestamp) return t('matches.timeTbd')
  if (hasSyntheticBbcTime(match)) {
    const date = new Intl.DateTimeFormat(dateLocale(), { timeZone: 'Asia/Shanghai', month: '2-digit', day: '2-digit' }).format(new Date(timestamp))
    return `${date} · ${t('matches.timePending')}`
  }
  return new Intl.DateTimeFormat(dateLocale(), {
    timeZone: 'Asia/Shanghai', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', ...options
  }).format(new Date(timestamp))
}

export const formatShortDate = value => value
  ? new Intl.DateTimeFormat(dateLocale(), { timeZone: 'Asia/Shanghai', month: '2-digit', day: '2-digit' }).format(new Date(value))
  : t('matches.dateUnknown')

export const formatCountdown = (match, now = Date.now()) => {
  if (isFinished(match) || isCancelled(match)) return ''
  const timestamp = getMatchTimestamp(match)
  if (!timestamp || timestamp <= now) return isLive(match) ? t('matches.countdownLive') : timestamp && timestamp < now ? t('matches.countdownStale') : t('matches.countdownSoon')
  const seconds = Math.max(0, Math.floor((timestamp - now) / 1000))
  const days = Math.floor(seconds / 86400)
  const hours = Math.floor((seconds % 86400) / 3600)
  const minutes = Math.floor((seconds % 3600) / 60)
  if (days > 0) return t('matches.countdownDays', { days, hours: String(hours).padStart(2, '0') })
  if (hours > 0) return t('matches.countdownHours', { hours, minutes: String(minutes).padStart(2, '0') })
  return t('matches.countdownMinutes', { minutes: Math.max(1, minutes) })
}

export const teamInitial = value => String(value || '?').trim().slice(0, 1).toUpperCase()
