/**
 * API 客户端（fetch 封装，替代旧版 axios 实例；契约 §2.1 逐字复现拦截器）。
 *
 * 只实现 UI/Store 实际调用的函数（契约 §2.8：80 个在用；122 个仅定义未调用
 * 的按附录 A 不搬——newsApi/videoApi/contentApi/systemApi/cardWorkshopApi/
 * cardRogueApi 全灭，matchApi/crawlerApi/predictionApi/agentApi/userApi 的
 * 未调用函数亦不搬；adminApi 另见 src/api/admin.js）。
 * 本文件实际导出 63 个函数 + admin.js 16 个 = 79 个（契约称 80，差 1 为
 * agentApi 计数口径：契约 §2.8 写 13 个、实为 14 个定义，见 scaffold.md）。
 *
 * 传输层（与旧版 src/api/index.js:1-152 一致）：
 *  - baseURL 同源 '/api'（Vite dev 经 proxy 到网关 :8082；生产 nginx /api/
 *    代理到 football-gateway:8082；可用 VITE_API_BASE_URL 覆盖）；超时 30s
 *    （AbortController；syncUpcomingMatches 用 120s）；credentials: 'include'
 *    （等价 withCredentials）；Content-Type: application/json。
 *  - 请求带 `Authorization: Bearer <football_token>`。
 *  - 响应信封（两层错误形态 + 四种 payload 改形）：
 *    外层 isApiErrorPayload（ok===false / success===false / code 存在且≠'0'）
 *    → 含 unauthorized 或 code 以 401 开头 → toast“登录已过期”并 forceLogout；
 *    内层 data.data 同样规则；通过后 normalizePayload（items/records/response
 *    数组直通、含 data 取 data、否则原样）；preserveMeta: true 时不归一化，
 *    返回 data.data 整个 payload（total、dataQuality、zoneRules 都在里面，
 *    调用方：getMatchesWindow/getMatchesPage/standings/阵容）。
 *    responseType: 'blob' 时直接返回 Response（目前无调用方，保留行为）。
 *    notifyOnError: false 时不弹 toast（静默查询）。
 *  - HTTP 错误映射：401 → 刷新或登出（下）；403“没有权限访问该资源”；
 *    404“请求的资源不存在”；429 warning“请求过于频繁”；500“服务器错误”；
 *    超时 ECONNABORTED →“请求超时”；其余网络错 →“网络错误”；
 *    调用方主动取消（AbortSignal）静默，不 toast（旧版 ERR_CANCELED 同款）。
 *  - 401 刷新：未重试过、URL 不含 /users/refresh 与 /users/login、且存在
 *    football_refresh_token 时调 POST /users/refresh；并发共享同一个
 *    refreshPromise；成功写回 token（与新的 refreshToken 如有）后重放原请求；
 *    失败才 forceLogout。doRefresh 要求返回体 data.ok && data.token。
 *  - forceLogout()：authStorage.clear()，不跳路由，只派发
 *    `football-auth-required`（detail { redirect: 当前完整路径 }），
 *    由 App 监听后 logout + openAuthDialog。事件名与 detail 形状不得改。
 *  - Agent 流式接口（SSE）单独用 fetch 直连，保留 401 刷新后重试一次。
 */
import { authStorage } from '../utils/authStorage'
import { canTrackAnalytics } from '../utils/privacyConsent'
import { toastError, toastWarning } from '../utils/toast'

const API_BASE = (import.meta.env.VITE_API_BASE_URL || '/api').replace(/\/$/, '')
const REQUEST_TIMEOUT_MS = 30000

function buildUrl(path, params) {
  const url = path.startsWith('http') ? path : `${API_BASE}${path.startsWith('/') ? path : `/${path}`}`
  if (!params) return url
  const query = new URLSearchParams()
  for (const [key, value] of Object.entries(params)) {
    if (value === undefined || value === null) continue
    query.append(key, String(value))
  }
  const qs = query.toString()
  return qs ? `${url}${url.includes('?') ? '&' : '?'}${qs}` : url
}

function normalizePayload(payload) {
  if (payload == null || typeof payload !== 'object') return payload
  if ('items' in payload && Array.isArray(payload.items)) return payload
  if ('records' in payload && Array.isArray(payload.records)) return payload.records
  if ('response' in payload && Array.isArray(payload.response)) return payload.response
  if ('data' in payload) return payload.data
  return payload
}

function isApiErrorPayload(payload) {
  if (!payload || typeof payload !== 'object') return false
  if (payload.ok === false) return true
  if (payload.success === false) return true
  if (payload.code && String(payload.code) !== '0') return true
  return false
}

function forceLogout() {
  authStorage.clear()
  const currentPath = `${window.location.pathname}${window.location.search}${window.location.hash}`
  window.dispatchEvent(new CustomEvent('football-auth-required', { detail: { redirect: currentPath } }))
}

let refreshPromise = null

async function doRefresh() {
  const refreshToken = authStorage.get('football_refresh_token')
  if (!refreshToken) throw new Error('no refresh token')
  const data = await requestRaw('/users/refresh', {
    method: 'POST',
    body: { refreshToken },
    notifyOnError: false,
  })
  const payload = data?.data ?? data
  if (!payload || !payload.ok || !payload.token) throw new Error('refresh rejected')
  authStorage.set('football_token', payload.token)
  if (payload.refreshToken) authStorage.set('football_refresh_token', payload.refreshToken)
  return payload
}

export async function refreshAuthSession() {
  if (!refreshPromise) refreshPromise = doRefresh().finally(() => { refreshPromise = null })
  return refreshPromise
}

function notifyHttpError(status, message) {
  if (status === 401) toastError('登录已过期，请重新登录')
  else if (status === 403) toastError('没有权限访问该资源')
  else if (status === 404) toastError('请求的资源不存在')
  else if (status === 429) toastWarning('请求过于频繁，请稍后再试')
  else if (status === 500) toastError('服务器错误，请联系管理员')
  else toastError(message || '请求失败')
}

function isRefreshable(path) {
  return (
    !String(path).includes('/users/refresh') &&
    !String(path).includes('/users/login') &&
    Boolean(authStorage.get('football_refresh_token'))
  )
}

/**
 * 原始请求：只做传输 + HTTP 状态处理，不碰信封、不登出。
 * 401 只抛错（由 request() 决定刷新重试还是 forceLogout），
 * 否则旧版“先刷新后重放”的语义会被 requestRaw 里的 forceLogout 提前破坏。
 */
async function requestRaw(path, options = {}) {
  const {
    method = 'GET',
    body,
    params,
    headers = {},
    notifyOnError = true,
    signal: outerSignal,
    timeout = REQUEST_TIMEOUT_MS,
    responseType,
  } = options
  const controller = new AbortController()
  const timeoutId = window.setTimeout(() => controller.abort(), timeout)
  const signal = outerSignal ? AbortSignal.any([outerSignal, controller.signal]) : controller.signal
  const token = authStorage.get('football_token')
  let response
  try {
    response = await fetch(buildUrl(path, params), {
      method,
      signal,
      credentials: 'include',
      headers: {
        'Content-Type': 'application/json',
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
        ...headers,
      },
      body: body === undefined || body === null ? undefined : JSON.stringify(body),
    })
  } catch (error) {
    if (error?.name === 'AbortError') {
      // 调用方主动取消（路由切换/组件卸载）静默；只有我方超时才 toast。
      if (outerSignal?.aborted) throw Object.assign(new Error('request canceled'), { code: 'ERR_CANCELED', silent: true })
      if (notifyOnError) toastError('请求超时，请检查网络连接')
      throw Object.assign(new Error('请求超时，请检查网络连接'), { code: 'ECONNABORTED' })
    }
    if (notifyOnError) toastError('网络错误，请检查网络连接')
    throw error
  } finally {
    window.clearTimeout(timeoutId)
  }

  if (responseType === 'blob') return response

  const contentType = response.headers.get('content-type') || ''
  const data = contentType.includes('application/json') ? await response.json().catch(() => null) : null

  if (response.status === 401) {
    throw Object.assign(new Error('unauthorized'), { status: 401, data })
  }
  if (!response.ok) {
    const message = data?.message || data?.msg || '请求失败'
    if (notifyOnError) notifyHttpError(response.status, message)
    throw Object.assign(new Error(message), { status: response.status, data })
  }
  return data
}

async function request(path, options = {}) {
  const { preserveMeta = false, notifyOnError = true } = options
  let data
  try {
    data = await requestRaw(path, options)
  } catch (error) {
    if (error?.status === 401 && !options._retried && isRefreshable(path)) {
      try {
        await refreshAuthSession()
        data = await requestRaw(path, { ...options, _retried: true })
      } catch (retryError) {
        // 只有重放后仍 401 才强制登出；其他错误（500 等）已由 requestRaw toast，直接透传。
        if (retryError?.status === 401) {
          if (notifyOnError) toastError('登录已过期，请重新登录')
          forceLogout()
        }
        throw error
      }
    } else {
      if (error?.status === 401 && !error?.silent) {
        if (notifyOnError) toastError('登录已过期，请重新登录')
        forceLogout()
      }
      throw error
    }
  }

  // 与旧版 axios 响应拦截器同款包信封处理（外层 → 内层 → 归一化）。
  if (isApiErrorPayload(data)) {
    const message = String(data.message || data.msg || '请求失败')
    if (message.toLowerCase().includes('unauthorized') || String(data.code || '').startsWith('401')) {
      if (notifyOnError) toastError('登录已过期，请重新登录')
      forceLogout()
    } else if (notifyOnError) {
      toastError(message)
    }
    throw Object.assign(new Error(message), { data })
  }
  if (data && typeof data === 'object' && 'data' in data) {
    const payload = data.data
    if (isApiErrorPayload(payload)) {
      const message = String(payload.message || payload.msg || '请求失败')
      if (message.toLowerCase().includes('unauthorized') || String(payload.code || '').startsWith('401')) {
        if (notifyOnError) toastError('登录已过期，请重新登录')
        forceLogout()
        throw new Error('unauthorized')
      }
      if (notifyOnError) toastError(message)
      throw Object.assign(new Error(message), { data: payload })
    }
    if (preserveMeta) return payload
    return normalizePayload(payload)
  }
  return normalizePayload(data)
}

export const api = {
  get: (path, config = {}) => request(path, { ...config, method: 'GET' }),
  post: (path, body, config = {}) => request(path, { ...config, method: 'POST', body }),
  put: (path, body, config = {}) => request(path, { ...config, method: 'PUT', body }),
  patch: (path, body, config = {}) => request(path, { ...config, method: 'PATCH', body }),
  delete: (path, config = {}) => request(path, { ...config, method: 'DELETE' }),
  request,
}

// --- 用户 / 鉴权（userApi，22 个在用；未调用的 getCaptcha/clearAvatar/listUsers/updateUserRole 不搬） ---
export const userApi = {
  register(email, nickname, password, verificationCode, captchaId = '', captchaAnswer = '') { return api.post('/users/register', { email, nickname, password, verificationCode, captchaId, captchaAnswer }) },
  sendEmailCode(email, scene = 'REGISTER', captchaId = '', captchaAnswer = '') { return api.post('/users/email/verification-code', { email, scene, captchaId, captchaAnswer }) },
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
  disableAccount() { return api.delete('/users/account') },
  getPreferences(config = {}) { return api.get('/users/preferences', config) },
  updatePreferences(preferences) { return api.put('/users/preferences', preferences) },
  getNotifications(limit = 20, unreadOnly = false, config = {}) { return api.get('/users/notifications', { ...config, params: { limit, unreadOnly, ...(config.params || {}) } }) },
  readNotification(id, config = {}) { return api.post(`/users/notifications/${id}/read`, null, config) },
  readAllNotifications(config = {}) { return api.post('/users/notifications/read-all', null, config) },
}

// --- 比赛详情（matchApi，仅 2 个在用；其余 9 个未调用不搬） ---
export const matchApi = {
  getDetails(fixtureId, refresh = false) { return api.get(`/matches/${fixtureId}/details`, { params: { refresh } }) },
  refreshDetails(fixtureId) { return api.post(`/matches/${fixtureId}/details/refresh`) },
}

// --- 爬虫 / 比赛主链路（crawlerApi，16 个在用；getRecommendations 由重建的 useMatchRecommendations 调用） ---
export const crawlerApi = {
  getMatchesWindow(page = 1, size = 300, params = {}, config = {}) { return api.get('/crawler/matches/window', { ...config, params: { page, size, ...params, ...(config.params || {}) }, preserveMeta: true }) },
  getMatchesPage(page = 1, size = 20, date, config = {}) { return api.get('/crawler/matches/db/page', { ...config, params: { page, size, date, ...(config.params || {}) }, preserveMeta: true }) },
  getMatchDetail(externalMatchId, config = {}) { return api.get('/crawler/matches/detail/' + externalMatchId, config) },
  getHeadToHead(homeTeam, awayTeam, limit = 10) {
    return api.get('/crawler/matches/h2h', { params: { homeTeam, awayTeam, limit } }).then((payload) => ({
      ...(payload || {}),
      // 爬虫端点称该字段 `matches`；保持 UI 的历史 `recentMatches` 契约，不走已退役 LLM proxy。
      recentMatches: payload?.recentMatches || payload?.matches || [],
    }))
  },
  getProxyH2H(homeTeam, awayTeam, limit = 10) { return this.getHeadToHead(homeTeam, awayTeam, limit) },
  searchMatches(keyword) { return api.get('/crawler/matches/search', { params: { keyword } }) },
  searchTeams(name) { return api.get('/crawler/teams/search', { params: { name } }) },
  getStandingsByLeagueName(leagueName, season = '') { return api.get('/crawler/standings/league/' + encodeURIComponent(leagueName), { preserveMeta: true, headers: { 'Cache-Control': 'no-cache' }, params: { season: season || undefined, _ts: Date.now() } }) },
  getStandingSeasons(leagueName) { return api.get('/crawler/standings/league/' + encodeURIComponent(leagueName) + '/seasons', { headers: { 'Cache-Control': 'no-cache' }, params: { _ts: Date.now() } }) },
  refreshStandings(leagueName) { return api.post('/crawler/standings/league/' + encodeURIComponent(leagueName) + '/refresh', {}, { preserveMeta: true }) },
  getTeamsByLeague(leagueName) { return api.get('/crawler/teams/league/' + encodeURIComponent(leagueName), { preserveMeta: true }) },
  getTeamSquad(teamName, leagueName, teamId, season, config = {}) {
    return api.get('/crawler/teams/squad/' + encodeURIComponent(teamName), { ...config, preserveMeta: true, params: { leagueName, teamId, season, ...(config.params || {}) } })
  },
  getTodayMatches() { return api.get('/crawler/matches/today') },
  getDataSourceHealth(config = {}) { return api.get('/crawler/data-sources/health', config) },
  getHealth() { return api.get('/crawler/health') },
  getRecommendations(params = {}, config = {}) { return api.get('/crawler/matches/recommendations', { ...config, params: { mode: 'focus', limit: 6, ...params, ...(config.params || {}) }, preserveMeta: true }) },
}

// --- 预测（predictionApi，5 个在用；getTodayPredictions/getByMatch/getHotPredictions/getReadySnapshots/saveMatchResult 不搬） ---
export const predictionApi = {
  getCurrentByMatch(fixtureId, config = {}) { return api.get('/predictions/match/' + fixtureId + '/current', config) },
  getHistoryPage(cursor = '', size = 20) { return api.get('/predictions/history/page', { params: { cursor: cursor || undefined, size } }) },
  getHistory(limit = 20, config = {}) { return api.get('/predictions/history', { ...config, params: { limit, ...(config.params || {}) } }) },
  getStatistics() { return api.get('/predictions/statistics') },
  getPerformance(days = 7, config = {}) { return api.get('/predictions/performance', { ...config, params: { days, ...(config.params || {}) } }) },
}

// --- 收藏（favoriteApi，6 个全部在用） ---
export const favoriteApi = {
  add(teamId, teamName, metadata = {}, config = {}) { return api.post('/users/favorites', { teamId, teamName, teamLogo: metadata.teamLogo || '', leagueName: metadata.leagueName || '' }, config) },
  remove(teamId, config = {}) { return api.delete('/users/favorites/' + encodeURIComponent(String(teamId || '')), config) },
  list(config = {}) { return api.get('/users/favorites', config) },
  addMatch(fixtureId, matchLabel, metadata = {}, config = {}) { return api.post('/users/favorites/matches', { fixtureId: String(fixtureId), matchLabel, leagueName: metadata.leagueName || '', matchTime: metadata.matchTime || '' }, config) },
  removeMatch(fixtureId, config = {}) { return api.delete('/users/favorites/matches/' + fixtureId, config) },
  listMatches(config = {}) { return api.get('/users/favorites/matches', config) },
}

// --- 搜索 / 埋点 / 更新日志（analyticsApi 仅 track 在用；summary 不搬） ---
export const searchApi = {
  search(keyword, limit = 8) { return api.get('/search', { params: { q: keyword, limit } }) },
}

export const analyticsApi = {
  track(eventName, { page, entityType, entityId, properties } = {}) {
    if (!canTrackAnalytics()) return Promise.resolve({ skipped: true })
    const eventId = typeof crypto !== 'undefined' && crypto.randomUUID
      ? crypto.randomUUID()
      : `${Date.now()}-${Math.random().toString(36).slice(2)}`
    return api.post('/analytics/events', { eventId, eventName, page, entityType, entityId, properties })
  },
}

export const changelogApi = {
  list(limit = 10, config = {}) { return api.get('/changelog', { ...config, params: { limit, ...(config.params || {}) }, notifyOnError: false }) },
}

// --- Agent 助手（agentApi，9 个在用；analyze/chat/capabilities/models/getSession 不搬） ---
export const agentApi = {
  async streamChat(data, { onEvent, signal } = {}) {
    const createRequest = () => fetch(`${API_BASE}/agent/chat/stream`, {
      method: 'POST',
      signal,
      credentials: 'include',
      headers: {
        'Content-Type': 'application/json',
        Accept: 'text/event-stream',
        ...(authStorage.get('football_token') ? { Authorization: `Bearer ${authStorage.get('football_token')}` } : {}),
      },
      body: JSON.stringify(data),
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
    if (!response.body) throw new Error('Agent 未返回流式响应')
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
      } catch { /* 忽略 SSE 心跳或非 JSON 行 */ }
    }
    try {
      for (;;) {
        const { value, done } = await reader.read()
        buffer += decoder.decode(value || new Uint8Array(), { stream: !done })
        const lines = buffer.split(/\r?\n/)
        buffer = lines.pop() || ''
        lines.forEach(emit)
        if (done) break
      }
    } finally {
      try { reader.releaseLock() } catch { /* ignore */ }
    }
    if (buffer.trim()) emit(buffer)
    return events
  },
  cancelStream(streamId) { return api.post(`/agent/chat/stream/${encodeURIComponent(streamId)}/cancel`) },
  health() { return api.get('/agent/health') },
  tools() { return api.get('/agent/tools') },
  metrics(days = 7) { return api.get('/agent/metrics', { params: { days } }) },
  getConversation(sessionId) { return api.get(`/agent/conversation/${encodeURIComponent(sessionId)}`) },
  listSessions() { return api.get('/agent/sessions') },
  renameSession(sessionId, title) { return api.patch(`/agent/sessions/${encodeURIComponent(sessionId)}`, { title }) },
  deleteSession(sessionId) { return api.delete(`/agent/sessions/${encodeURIComponent(sessionId)}`) },
}

export default api
