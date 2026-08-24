const LIVE_STATUSES = ['LIVE', 'IN_PLAY', 'PAUSED', 'HT', '1H', '2H', 'ET']
const FINISHED_STATUSES = ['FT', 'AET', 'PEN', 'FINISHED']
const CANCELLED_STATUSES = ['CANC', 'CANCELED', 'CANCELLED', 'PST', 'POSTPONED', 'PPD', 'ABD', 'AWD', 'WO', 'SOURCE_REMOVED']

export const normalizeList = value => Array.isArray(value)
  ? value
  : Array.isArray(value?.items) ? value.items
    : Array.isArray(value?.records) ? value.records
      : Array.isArray(value?.response) ? value.response : []

export const getMatchId = match => String(match?.matchId || match?.id || match?.fixtureId || match?.fixture?.id || match?.externalMatchId || '')
export const getHomeTeam = match => match?.teams?.home || { name: match?.homeTeamName, logo: match?.homeTeamLogo, id: match?.homeTeamId }
export const getAwayTeam = match => match?.teams?.away || { name: match?.awayTeamName, logo: match?.awayTeamLogo, id: match?.awayTeamId }
export const getHomeName = match => getHomeTeam(match)?.name || match?.homeTeamName || '主队'
export const getAwayName = match => getAwayTeam(match)?.name || match?.awayTeamName || '客队'
export const getHomeLogo = match => getHomeTeam(match)?.logo || match?.homeTeamLogo || ''
export const getAwayLogo = match => getAwayTeam(match)?.logo || match?.awayTeamLogo || ''
export const getLeagueName = match => match?.league?.name || match?.leagueName || '联赛待同步'
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
export const getStatusText = match => ({
  NS: '未开赛', LIVE: '进行中', IN_PLAY: '进行中', PAUSED: '暂停', HT: '中场', '1H': '上半场', '2H': '下半场', ET: '加时',
  FT: '已结束', AET: '已结束', PEN: '已结束', FINISHED: '已结束', PST: '已推迟', POSTPONED: '已推迟', CANC: '已取消', CANCELLED: '已取消', STALE: '状态待更新', SOURCE_REMOVED: '赛程已变更'
}[getDisplayStatusKey(match)] || '待同步')
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
  if (!timestamp) return '时间待定'
  if (hasSyntheticBbcTime(match)) {
    const date = new Intl.DateTimeFormat('zh-CN', { timeZone: 'Asia/Shanghai', month: '2-digit', day: '2-digit' }).format(new Date(timestamp))
    return `${date} · 时间待同步`
  }
  return new Intl.DateTimeFormat('zh-CN', {
    timeZone: 'Asia/Shanghai', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', ...options
  }).format(new Date(timestamp))
}

export const formatShortDate = value => value
  ? new Intl.DateTimeFormat('zh-CN', { timeZone: 'Asia/Shanghai', month: '2-digit', day: '2-digit' }).format(new Date(value))
  : '日期未知'

export const formatCountdown = (match, now = Date.now()) => {
  if (isFinished(match) || isCancelled(match)) return ''
  const timestamp = getMatchTimestamp(match)
  if (!timestamp || timestamp <= now) return isLive(match) ? '正在进行' : timestamp && timestamp < now ? '状态待更新' : '即将开始'
  const seconds = Math.max(0, Math.floor((timestamp - now) / 1000))
  const days = Math.floor(seconds / 86400)
  const hours = Math.floor((seconds % 86400) / 3600)
  const minutes = Math.floor((seconds % 3600) / 60)
  if (days > 0) return `${days}天 ${String(hours).padStart(2, '0')}小时后`
  if (hours > 0) return `${hours}小时 ${String(minutes).padStart(2, '0')}分后`
  return `${Math.max(1, minutes)}分钟后`
}

export const teamInitial = value => String(value || '?').trim().slice(0, 1).toUpperCase()
