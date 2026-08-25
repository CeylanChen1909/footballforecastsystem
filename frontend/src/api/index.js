import axios from 'axios'
import { ElMessage } from 'element-plus'
import { authStorage } from '../utils/authStorage'
import { canTrackAnalytics } from '../utils/privacyConsent'

const api = axios.create({
  baseURL: '/api',
  timeout: 30000,
  withCredentials: true,
  headers: { 'Content-Type': 'application/json' }
})

const normalizePayload = (payload) => {
  if (payload == null) return payload
  if (typeof payload !== 'object') return payload
  if ('items' in payload && Array.isArray(payload.items)) return payload
  if ('records' in payload && Array.isArray(payload.records)) return payload.records
  if ('response' in payload && Array.isArray(payload.response)) return payload.response
  if ('data' in payload) return payload.data
  return payload
}

const isApiErrorPayload = (payload) => {
  if (!payload || typeof payload !== 'object') return false
  if (payload.ok === false) return true
  if (payload.success === false) return true
  if (payload.code && String(payload.code) !== '0') return true
  return false
}

api.interceptors.request.use(config => {
  const token = authStorage.get('football_token')
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
}, error => Promise.reject(error))

api.interceptors.response.use(response => {
  if (response.config.responseType === 'blob') return response
  const data = response.data
  // Some legacy controllers return { success:false, data:null } while the
  // newer envelope puts the error code inside data.  Check the outer envelope
  // first, otherwise a real 401/500 can be silently normalized to null and
  // the UI will look as if the request succeeded.
  if (isApiErrorPayload(data)) {
    const message = String(data.message || data.msg || '请求失败')
    if (message.toLowerCase().includes('unauthorized') || String(data.code || '').startsWith('401')) {
      if (response.config.notifyOnError !== false) ElMessage.error('登录已过期，请重新登录')
      forceLogout()
    } else if (response.config.notifyOnError !== false) {
      ElMessage.error(message)
    }
    return Promise.reject(Object.assign(new Error(message), { response: { data } }))
  }
  if (data && typeof data === 'object' && 'data' in data) {
    const payload = data.data
  if (isApiErrorPayload(payload)) {
      const message = String(payload.message || payload.msg || '请求失败')
      if (message.toLowerCase().includes('unauthorized') || String(payload.code || '').startsWith('401')) {
        if (response.config.notifyOnError !== false) ElMessage.error('登录已过期，请重新登录')
        forceLogout()
        return Promise.reject(new Error('unauthorized'))
      }
      if (response.config.notifyOnError !== false) ElMessage.error(message)
      return Promise.reject(Object.assign(new Error(message), { response: { data: payload } }))
    }
    if (response.config.preserveMeta) return payload
    return normalizePayload(payload)
  }
  if (isApiErrorPayload(data)) {
    const message = String(data.message || data.msg || '请求失败')
    if (response.config.notifyOnError !== false) ElMessage.error(message)
    return Promise.reject(Object.assign(new Error(message), { response: { data } }))
  }
  return normalizePayload(data)
}, error => {
  const { response, config } = error
  if (response && response.status === 401 && config && !config._retried
      && !String(config.url || '').includes('/users/refresh')
      && !String(config.url || '').includes('/users/login')
      && authStorage.get('football_refresh_token')) {
    config._retried = true
    return refreshAndRetry(config).catch(() => {
      forceLogout()
      return Promise.reject(error)
    })
  }
  if (response) {
    const { status, data } = response
    const message = data?.message || data?.msg || '请求失败'
    const notify = config?.notifyOnError !== false
    if (status === 401) {
      if (notify) ElMessage.error('登录已过期，请重新登录')
      forceLogout()
    } else if (status === 403) {
      if (notify) ElMessage.error('没有权限访问该资源')
    } else if (status === 404) {
      if (notify) ElMessage.error('请求的资源不存在')
    } else if (status === 429) {
      if (notify) ElMessage.warning('请求过于频繁，请稍后再试')
    } else if (status === 500) {
      if (notify) ElMessage.error('服务器错误，请联系管理员')
    } else {
      if (notify) ElMessage.error(message)
    }
  } else if (error.code === 'ERR_CANCELED' || error.name === 'CanceledError') {
    // Route changes and component unmounts intentionally cancel stale requests.
    // They are not user-visible network errors.
    return Promise.reject(error)
  } else if (error.code === 'ECONNABORTED') {
    if (config?.notifyOnError !== false) ElMessage.error('请求超时，请检查网络连接')
  } else {
    if (config?.notifyOnError !== false) ElMessage.error('网络错误，请检查网络连接')
  }
  return Promise.reject(error)
})

// ---- refresh token 流程 ----
let refreshPromise = null

async function doRefresh() {
  const refreshToken = authStorage.get('football_refresh_token')
  if (!refreshToken) throw new Error('no refresh token')
  const res = await api.post('/users/refresh', { refreshToken })
  const data = res?.data ?? res
  if (!data || !data.ok || !data.token) throw new Error('refresh rejected')
  authStorage.set('football_token', data.token)
  if (data.refreshToken) authStorage.set('football_refresh_token', data.refreshToken)
  return data
}

async function refreshAndRetry(config) {
  if (!refreshPromise) {
    refreshPromise = doRefresh().finally(() => { refreshPromise = null })
  }
  const data = await refreshPromise
  config.headers = config.headers || {}
  config.headers.Authorization = `Bearer ${data.token}`
  return api(config)
}

function forceLogout() {
  authStorage.clear()
  const currentPath = `${window.location.pathname}${window.location.search}${window.location.hash}`
  // Keep the current public page visible and let App.vue open the auth modal;
  // a full redirect used to destroy the user's context and caused a flash of
  // the login page after a background 401.
  window.dispatchEvent(new CustomEvent('football-auth-required', { detail: { redirect: currentPath } }))
}

export async function refreshAuthSession() {
  if (!refreshPromise) refreshPromise = doRefresh().finally(() => { refreshPromise = null })
  return refreshPromise
}

export const userApi = {
  register(email, nickname, password, verificationCode, captchaId = '', captchaAnswer = '') { return api.post('/users/register', { email, nickname, password, verificationCode, captchaId, captchaAnswer }) },
  sendEmailCode(email, scene = 'REGISTER', captchaId = '', captchaAnswer = '') { return api.post('/users/email/verification-code', { email, scene, captchaId, captchaAnswer }) },
  getCaptcha() { return api.get('/users/captcha') },
  getRegistrationCaptcha() { return api.get('/users/register/captcha') },
  getLegalConsentStatus(config = {}) { return api.get('/users/legal-consent/status', { ...config, notifyOnError: false }) },
  acceptLegalConsent() { return api.post('/users/legal-consent/accept', {}) },
  requestPasswordReset(email) { return api.post('/users/password/reset-request', { email }) },
  resetPassword(email, verificationCode, newPassword) { return api.post('/users/password/reset', { email, verificationCode, newPassword }) },
  login(account, password, captchaId, captchaAnswer) { return api.post('/users/login', { account, password, captchaId, captchaAnswer }) },
  getCurrentUser() { return api.get('/users/me') },
  refresh(refreshToken) { return api.post('/users/refresh', refreshToken ? { refreshToken } : null) },
  logout(refreshToken) { return api.post('/users/logout', refreshToken ? { refreshToken } : null) },
  getSessions(refreshToken) { return api.get('/users/sessions', { headers: { 'X-Refresh-Token': refreshToken } }) },
  revokeAllSessions(refreshToken) { return api.post('/users/sessions/revoke-all', { refreshToken }) },
  changePassword(currentPassword, newPassword) { return api.put('/users/password', { currentPassword, newPassword }) },
  updateProfile(profile) { return api.put('/users/profile', profile) },
  updateAvatar(avatarData) { return api.put('/users/avatar', { avatarData }) },
  clearAvatar() { return api.delete('/users/avatar') },
  disableAccount() { return api.delete('/users/account') },
  getPreferences(config = {}) { return api.get('/users/preferences', config) },
  updatePreferences(preferences) { return api.put('/users/preferences', preferences) },
  getNotifications(limit = 20, unreadOnly = false, config = {}) { return api.get('/users/notifications', { ...config, params: { limit, unreadOnly, ...(config.params || {}) } }) },
  readNotification(id, config = {}) { return api.post(`/users/notifications/${id}/read`, null, config) },
  readAllNotifications(config = {}) { return api.post('/users/notifications/read-all', null, config) },
  listUsers() { return api.get('/admin/users') },
  updateUserRole(userId, role) { return api.put('/users/role', { userId, role }) }
}

export const matchApi = {
  getToday() { return api.get('/matches/today') },
  getByDate(date) { return api.get('/matches/date/' + date) },
  getDetail(fixtureId) { return api.get('/matches/' + fixtureId) },
  getDetails(fixtureId, refresh = false) { return api.get(`/matches/${fixtureId}/details`, { params: { refresh } }) },
  getPrematchData(fixtureId) { return api.get(`/matches/${fixtureId}/prematch-data`) },
  refreshDetails(fixtureId) { return api.post(`/matches/${fixtureId}/details/refresh`) },
  getLeagues() { return api.get('/matches/leagues') },
  adminList() { return api.get('/admin/matches') },
  adminSave(data) { return api.post('/admin/matches', data) },
  adminDelete(id) { return api.delete('/admin/matches/' + id) },
  adminToggleStatus(id, status) { return api.put(`/admin/matches/${id}/status`, null, { params: { status } }) }
}

export const newsApi = {
  getLatest() { return api.get('/news/latest') },
  getHomeFeed(page = 1, size = 10) { return api.get('/news/feed', { params: { page, size, sortBy: 'latest' } }) },
  getFeed(page = 1, size = 10, category, keyword, sortBy = 'latest') { return api.get('/news/feed', { params: { page, size, category, keyword, sortBy } }) },
  getDetail(id) { return api.get(`/news/articles/${id}`) },
  getRelated(id, limit = 6) { return api.get(`/news/articles/${id}/related`, { params: { limit } }) },
  getComments(id) { return api.get(`/news/articles/${id}/comments`) },
  addComment(id, content, parentId) { return api.post(`/news/articles/${id}/comments`, null, { params: { content, parentId } }) },
  toggleLike(id) { return api.post(`/news/articles/${id}/like`) },
  toggleFavorite(id) { return api.post(`/news/articles/${id}/favorite`) },
  toggleCommentLike(commentId) { return api.post(`/news/articles/comments/${commentId}/like`) },
  reportComment(commentId, reason = '用户举报') { return api.post(`/news/articles/comments/${commentId}/report`, null, { params: { reason } }) },
  getSpotlights() { return api.get('/news/spotlights') },
  getCategories() { return api.get('/news/categories') },
  getTags(limit = 20) { return api.get('/news/tags', { params: { limit } }) },
  getRecommendations(articleId, limit = 8) { return articleId ? api.get(`/news/articles/${articleId}/related`, { params: { limit } }) : Promise.resolve([]) },
  adminList(keyword, status) { return api.get('/admin/news', { params: { keyword, status } }) },
  adminGet(id) { return api.get(`/admin/news/${id}`) },
  adminSave(data) { return api.post('/admin/news', data) },
  adminUpdate(id, data) { return api.put(`/admin/news/${id}`, data) },
  adminDelete(id) { return api.delete(`/admin/news/${id}`) },
  adminStatus(id, status) { return api.put(`/admin/news/${id}/status`, null, { params: { status } }) }
}

export const videoApi = {
  list({ keyword, leagueName, platform, videoType, limit = 24 } = {}) {
    return api.get('/videos', { params: { keyword, leagueName, platform, videoType, limit } })
  }
}

export const contentApi = {
  feed({ type = 'all', page = 1, size = 24, category, keyword, sortBy = 'latest' } = {}) {
    return api.get('/content/feed', { params: { type, page, size, category, keyword, sortBy } })
  },
  match(matchId, { homeTeamName, awayTeamName, matchTime, limit = 8 } = {}) {
    return api.get(`/content/match/${matchId}`, { params: { homeTeamName, awayTeamName, matchTime, limit } })
  },
  types() { return api.get('/content/types') }
}

export const changelogApi = {
  list(limit = 10, config = {}) { return api.get('/changelog', { ...config, params: { limit, ...(config.params || {}) }, notifyOnError: false }) }
}

export const systemApi = {
  getConfig() { return api.get('/admin/config') },
  saveConfig(data) { return api.put('/admin/config', data) },
  getLogs() { return api.get('/admin/config/logs') }
}

export const crawlerApi = {
  getDataSourceHealth(config = {}) { return api.get('/crawler/data-sources/health', config) },
  getHealth() { return api.get('/crawler/health') },
  getTodayMatches() { return api.get('/crawler/matches/today') },
  getUpcomingMatches() { return api.get('/crawler/matches/upcoming') },
  getMatchesByDate(date) { return api.get('/crawler/matches/date/' + date) },
  getMatchesPage(page = 1, size = 20, date, config = {}) { return api.get('/crawler/matches/db/page', { ...config, params: { page, size, date, ...(config.params || {}) } }) },
  getMatchesWindow(page = 1, size = 300, params = {}, config = {}) { return api.get('/crawler/matches/window', { ...config, params: { page, size, ...params, ...(config.params || {}) }, preserveMeta: true }) },
  getRecommendations(params = {}, config = {}) { return api.get('/crawler/matches/recommendations', { ...config, params: { mode: 'focus', limit: 6, ...params, ...(config.params || {}) }, preserveMeta: true }) },
  getHotMatches(limit = 10, config = {}) { return api.get('/crawler/matches/hot', { ...config, params: { limit, ...(config.params || {}) }, preserveMeta: true }) },
  getMatchDetail(externalMatchId, config = {}) { return api.get('/crawler/matches/detail/' + externalMatchId, config) },
  searchMatches(keyword) { return api.get('/crawler/matches/search', { params: { keyword } }) },
  // H2H is a read-only database query; do not route it through the retired
  // LLM proxy (which would spend quota and is disabled in production).
  getHeadToHead(homeTeam, awayTeam, limit = 10) {
    return api.get('/crawler/matches/h2h', { params: { homeTeam, awayTeam, limit } }).then(payload => ({
      ...(payload || {}),
      // The crawler endpoint calls this field `matches`; keep the UI's
      // historical `recentMatches` contract without reviving the LLM proxy.
      recentMatches: payload?.recentMatches || payload?.matches || []
    }))
  },
  getProxyH2H(homeTeam, awayTeam, limit = 10) { return this.getHeadToHead(homeTeam, awayTeam, limit) },
  getProxyPrediction(fixtureId, homeTeam, awayTeam, leagueName) { return api.get('/proxy/prediction', { params: { fixtureId, homeTeam, awayTeam, leagueName } }) },
  getStandingsByLeagueName(leagueName, season = '') { return api.get('/crawler/standings/league/' + encodeURIComponent(leagueName), { preserveMeta: true, headers: { 'Cache-Control': 'no-cache' }, params: { season: season || undefined, _ts: Date.now() } }) },
  getStandingSeasons(leagueName) { return api.get('/crawler/standings/league/' + encodeURIComponent(leagueName) + '/seasons', { headers: { 'Cache-Control': 'no-cache' }, params: { _ts: Date.now() } }) },
  getStandingsHealth() { return api.get('/crawler/standings/health', { preserveMeta: true }) },
  refreshStandings(leagueName) { return api.post('/crawler/standings/league/' + encodeURIComponent(leagueName) + '/refresh', {}, { preserveMeta: true }) },
  getTeamsByLeague(leagueName) { return api.get('/crawler/teams/league/' + encodeURIComponent(leagueName), { preserveMeta: true }) },
  searchTeams(name) { return api.get('/crawler/teams/search', { params: { name } }) },
  getTeamSquad(teamName, leagueName, teamId, season, config = {}) {
    return api.get('/crawler/teams/squad/' + encodeURIComponent(teamName), { ...config, preserveMeta: true, params: { leagueName, teamId, season, ...(config.params || {}) } })
  },
}

export const cardWorkshopApi = {
  players({ keyword = '', team = '', position = '', league = '', page = 1, size = 24, limit } = {}) {
    return api.get('/card-workshop/players', { params: { keyword: keyword || undefined, team: team || undefined, position: position || undefined, league: league || undefined, page, size, limit } })
  },
  listLineups() { return api.get('/card-workshop/lineups') },
  getLineup(id) { return api.get(`/card-workshop/lineups/${id}`) },
  getLineupShare(id) { return api.get(`/card-workshop/lineups/${id}/share`) },
  createLineup(data) { return api.post('/card-workshop/lineups', data) },
  updateLineup(id, data) { return api.put(`/card-workshop/lineups/${id}`, data) },
  deleteLineup(id) { return api.delete(`/card-workshop/lineups/${id}`) },
  updateCardTags(id, tags) { return api.put(`/card-workshop/custom-cards/${id}/tags`, { tags }) },
  upgradeCard(id, stat) { return api.post(`/card-workshop/custom-cards/${id}/upgrade`, { stat }) },
  reportCard(id, reason, detail = '') { return api.post(`/card-workshop/custom-cards/${id}/report`, { reason, detail }) },
  collectionSummary() { return api.get('/card-workshop/custom-cards/collection/summary') },
  shareLineup(id) { return api.post(`/card-workshop/lineups/${id}/share`) },
  revokeLineupShare(id) { return api.delete(`/card-workshop/lineups/${id}/share`) },
  getSharedLineup(token) { return api.get(`/card-workshop/lineups/shared/${encodeURIComponent(token)}`) },
  catalog({ keyword = '', position = '', sort = 'price', ownedOnly = false, page = 1, size = 24 } = {}) { return api.get('/card-workshop/catalog', { params: { keyword: keyword || undefined, position: position || undefined, sort, ownedOnly: ownedOnly || undefined, page, size } }) },
  points() { return api.get('/card-workshop/points') },
  pointsLedger({ page = 1, size = 20 } = {}) { return api.get('/card-workshop/points/ledger', { params: { page, size } }) },
  checkIn() { return api.post('/card-workshop/points/check-in') },
  dailyChallenge(lineupId) { return api.get('/card-workshop/daily-challenge', { params: { lineupId: lineupId || undefined } }) },
  claimDailyChallenge(lineupId) { return api.post('/card-workshop/daily-challenge/claim', { lineupId }) },
  redeemCatalogCard(id) { return api.post(`/card-workshop/catalog/${id}/redeem`) },
  synergyRules() { return api.get('/card-workshop/synergy-rules') }
}

export const cardRogueApi = {
  state() { return api.get('/card-rogue/state') },
  start(carriedCardIds = []) { return api.post('/card-rogue/runs', { carriedCardIds }) },
  history() { return api.get('/card-rogue/history') },
  selectNode(runId, nodeId) { return api.post(`/card-rogue/runs/${runId}/node`, { nodeId }) },
  choose(runId, choiceId) { return api.post(`/card-rogue/runs/${runId}/choice`, { choiceId }) },
  resolveEvent(runId, optionKey) { return api.post(`/card-rogue/runs/${runId}/event`, { optionKey }) },
  battle(runId, rosterIds = [], tactic = 'DIRECT') { return api.post(`/card-rogue/runs/${runId}/battle`, { rosterIds, tactic }) },
  claim(runId) { return api.post(`/card-rogue/runs/${runId}/claim`) },
  abandon(runId) { return api.post(`/card-rogue/runs/${runId}/abandon`) }
}

export const favoriteApi = {
  add(teamId, teamName, metadata = {}, config = {}) { return api.post('/users/favorites', { teamId, teamName, teamLogo: metadata.teamLogo || '', leagueName: metadata.leagueName || '' }, config) },
  remove(teamId, config = {}) { return api.delete('/users/favorites/' + encodeURIComponent(String(teamId || '')), config) },
  list(config = {}) { return api.get('/users/favorites', config) },
  addMatch(fixtureId, matchLabel, metadata = {}, config = {}) { return api.post('/users/favorites/matches', { fixtureId: String(fixtureId), matchLabel, leagueName: metadata.leagueName || '', matchTime: metadata.matchTime || '' }, config) },
  removeMatch(fixtureId, config = {}) { return api.delete('/users/favorites/matches/' + fixtureId, config) },
  listMatches(config = {}) { return api.get('/users/favorites/matches', config) }
}

export const predictionApi = {
  getTodayPredictions() { return api.get('/predictions/today') },
  getByMatch(fixtureId) { return api.get('/predictions/match/' + fixtureId) },
  getCurrentByMatch(fixtureId, config = {}) { return api.get('/predictions/match/' + fixtureId + '/current', config) },
  getHotPredictions(limit = 10, config = {}) { return api.get('/predictions/hot', { ...config, params: { limit, ...(config.params || {}) } }) },
  getReadySnapshots(limit = 100, config = {}) { return api.get('/predictions/ready', { ...config, params: { limit, ...(config.params || {}) } }) },
  getHistory(limit = 20, config = {}) { return api.get('/predictions/history', { ...config, params: { limit, ...(config.params || {}) } }) },
  getHistoryPage(cursor = '', size = 20) { return api.get('/predictions/history/page', { params: { cursor: cursor || undefined, size } }) },
  saveMatchResult(data) { return api.post('/predictions/match-result', data) },
  getStatistics() { return api.get('/predictions/statistics') },
  getPerformance(days = 7, config = {}) { return api.get('/predictions/performance', { ...config, params: { days, ...(config.params || {}) } }) }
}

export const analyticsApi = {
  track(eventName, { page, entityType, entityId, properties } = {}) {
    if (!canTrackAnalytics()) return Promise.resolve({ skipped: true })
    const eventId = typeof crypto !== 'undefined' && crypto.randomUUID
      ? crypto.randomUUID()
      : `${Date.now()}-${Math.random().toString(36).slice(2)}`
    return api.post('/analytics/events', { eventId, eventName, page, entityType, entityId, properties })
  },
  summary(days = 7) { return api.get('/analytics/summary', { params: { days } }) }
}

export const searchApi = {
  search(keyword, limit = 8) { return api.get('/search', { params: { q: keyword, limit } }) }
}

export const agentApi = {
  analyze(data) { return api.post('/agent/analyze', data, { preserveMeta: true }) },
  chat(data) { return api.post('/agent/chat', data, { preserveMeta: true }) },
  capabilities() { return api.get('/agent/capabilities') },
  async streamChat(data, { onEvent, signal } = {}) {
    const createRequest = () => fetch('/api/agent/chat/stream', {
      method: 'POST',
      signal,
      headers: {
        'Content-Type': 'application/json',
        Accept: 'text/event-stream',
        ...(authStorage.get('football_token') ? { Authorization: `Bearer ${authStorage.get('football_token')}` } : {})
      },
      body: JSON.stringify(data)
    })
    let response = await createRequest()
    if (response.status === 401 && authStorage.get('football_refresh_token')) {
      try {
        await refreshAuthSession()
        response = await createRequest()
      } catch { /* 下方统一抛出 401 */ }
    }
    if (!response.ok) {
      let message = `Agent 请求失败（${response.status}）`
      try {
        const payload = await response.json()
        message = payload?.message || payload?.msg || message
      } catch { /* 网关可能只返回状态码 */ }
      const error = new Error(message)
      error.status = response.status
      throw error
    }
    if (!response.body) {
      throw new Error('Agent 未返回流式响应')
    }

    const reader = response.body.getReader()
    const decoder = new TextDecoder()
    let buffer = ''
    const events = []
    const emit = (line) => {
      const raw = line.replace(/^data:\s?/, '').trim()
      if (!raw || raw === '[DONE]') return
      try {
        const event = JSON.parse(raw)
        events.push(event)
        onEvent?.(event)
      } catch {
        // 忽略 SSE 心跳或非 JSON 行，避免中断当前会话。
      }
    }

    try {
      while (true) {
        const { value, done } = await reader.read()
        buffer += decoder.decode(value || new Uint8Array(), { stream: !done })
        const lines = buffer.split(/\r?\n/)
        buffer = lines.pop() || ''
        lines.forEach(emit)
        if (done) break
      }
    } finally {
      try { reader.releaseLock() } catch { /* reader may already be released */ }
    }
    if (buffer.trim()) emit(buffer)
    return events
  },
  cancelStream(streamId) { return api.post(`/agent/chat/stream/${encodeURIComponent(streamId)}/cancel`) },
  health() { return api.get('/agent/health') },
  tools() { return api.get('/agent/tools') },
  models() { return api.get('/agent/models') },
  metrics(days = 7) { return api.get('/agent/metrics', { params: { days } }) },
  getConversation(sessionId) { return api.get(`/agent/conversation/${encodeURIComponent(sessionId)}`) },
  listSessions() { return api.get('/agent/sessions') },
  renameSession(sessionId, title) { return api.patch(`/agent/sessions/${encodeURIComponent(sessionId)}`, { title }) },
  deleteSession(sessionId) { return api.delete(`/agent/sessions/${encodeURIComponent(sessionId)}`) },
  getSession(sessionId) { return api.get(`/agent/session/${encodeURIComponent(sessionId)}`) }
}

export default api
