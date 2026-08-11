import axios from 'axios'
import { ElMessage } from 'element-plus'

const api = axios.create({
  baseURL: '/api',
  timeout: 30000,
  headers: { 'Content-Type': 'application/json' }
})

api.interceptors.request.use(config => {
  const token = localStorage.getItem('football_token')
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
}, error => Promise.reject(error))

api.interceptors.response.use(response => {
  if (response.config.responseType === 'blob') return response
  const data = response.data
  if (data && typeof data === 'object' && 'data' in data) {
    const payload = data.data
    if (payload && typeof payload === 'object' && payload.ok === false && String(payload.message || '').toLowerCase() === 'unauthorized') {
      localStorage.removeItem('football_token')
      localStorage.removeItem('football_user')
      ElMessage.error('登录已过期，请重新登录')
      window.location.href = '/#/login'
      return Promise.reject(new Error('unauthorized'))
    }
    return payload
  }
  return data
}, error => {
  if (error.response) {
    const { status, data } = error.response
    if (status === 401) {
      ElMessage.error('登录已过期，请重新登录')
      localStorage.removeItem('football_token')
      localStorage.removeItem('football_user')
      window.location.href = '/#/login'
    } else if (status === 403) {
      ElMessage.error('没有权限访问该资源')
    } else if (status === 404) {
      ElMessage.error('请求的资源不存在')
    } else if (status === 429) {
      ElMessage.warning('请求过于频繁，请稍后再试')
    } else if (status === 500) {
      ElMessage.error('服务器错误，请联系管理员')
    } else {
      ElMessage.error(data?.message || '请求失败')
    }
  } else if (error.code === 'ECONNABORTED') {
    ElMessage.error('请求超时，请检查网络连接')
  } else {
    ElMessage.error('网络错误，请检查网络连接')
  }
  return Promise.reject(error)
})

export const userApi = {
  register(username, password) { return api.post('/users/register', { username, password }) },
  login(username, password) { return api.post('/users/login', { username, password }) },
  getCurrentUser() { return api.get('/users/me') },
  updateProfile(data) { return api.put('/users/profile', data) },
  changePassword(oldPassword, newPassword) { return api.post('/users/change-password', { oldPassword, newPassword }) },
  listUsers() { return api.get('/admin/users') },
  updateUserRole(userId, role) { return api.put('/users/role', { userId, role }) }
}

export const matchApi = {
  getToday() { return api.get('/matches/db/today') },
  getByDate(date) { return api.get('/matches/db/page', { params: { date, page: 1, size: 200 } }) },
  getDetail(fixtureId) { return api.get('/matches/' + fixtureId) },
  getLeagues() { return api.get('/matches/leagues') },
  getStatistics(fixtureId) { return api.get('/matches/' + fixtureId + '/statistics') },
  getEvents(fixtureId) { return api.get('/matches/' + fixtureId + '/events') },
  adminList() { return api.get('/admin/matches') },
  adminSave(data) { return api.post('/admin/matches', data) },
  adminDelete(id) { return api.delete('/admin/matches/' + id) },
  adminToggleStatus(id, status) { return api.put(`/admin/matches/${id}/status`, null, { params: { status } }) }
}

export const newsApi = {
  getLatest() { return api.get('/news/latest') },
  getFeed(page = 1, size = 10, category, keyword, sortBy = 'latest') { return api.get('/news/feed', { params: { page, size, category, keyword, sortBy } }) },
  getDetail(id, userId) { return api.get(`/news/articles/${id}`, { params: userId ? { userId } : {} }) },
  getRelated(id, limit = 6) { return api.get(`/news/articles/${id}/related`, { params: { limit } }) },
  getComments(id) { return api.get(`/news/articles/${id}/comments`) },
  addComment(id, userId, content, parentId) { return api.post(`/news/articles/${id}/comments`, null, { params: { userId, content, parentId } }) },
  replyComment(id, userId, content, parentId) { return api.post(`/news/articles/${id}/comments`, null, { params: { userId, content, parentId } }) },
  toggleLike(id, userId) { return api.post(`/news/articles/${id}/like`, null, { params: { userId } }) },
  toggleFavorite(id, userId) { return api.post(`/news/articles/${id}/favorite`, null, { params: { userId } }) },
  getSpotlights() { return api.get('/news/spotlights') },
  getCategories() { return api.get('/news/categories') },
  getTags(limit = 20) { return api.get('/news/tags', { params: { limit } }) },
  getRecommendations(articleId, limit = 8) { return api.get('/news/recommendations', { params: { articleId, limit } }) },
  adminList(keyword, status) { return api.get('/admin/news', { params: { keyword, status } }) },
  adminGet(id) { return api.get(`/admin/news/${id}`) },
  adminSave(data) { return api.post('/admin/news', data) },
  adminUpdate(id, data) { return api.put(`/admin/news/${id}`, data) },
  adminDelete(id) { return api.delete(`/admin/news/${id}`) },
  adminStatus(id, status) { return api.put(`/admin/news/${id}/status`, null, { params: { status } }) }
}

export const systemApi = {
  getConfig() { return api.get('/admin/config') },
  saveConfig(data) { return api.put('/admin/config', data) },
  getLogs() { return api.get('/admin/config/logs') }
}

export const crawlerApi = {
  getTodayMatches() { return api.get('/crawler/matches/today') },
  getUpcomingMatches() { return api.get('/crawler/matches/upcoming') },
  getMatchesByDate(date) { return api.get('/crawler/matches/date/' + date) },
  getMatchesPage(page = 1, size = 20, date) { return api.get('/crawler/matches/db/page', { params: { page, size, date } }) },
  getMatchesWindow(page = 1, size = 300) { return api.get('/crawler/matches/window', { params: { page, size } }) },
  getHotMatches(limit = 10) { return api.get('/crawler/matches/hot', { params: { limit } }) },
  getMatchDetail(externalMatchId) { return api.get('/crawler/matches/detail/' + externalMatchId) },
  searchMatches(keyword) { return api.get('/crawler/matches/search', { params: { keyword } }) },
  getHeadToHead(homeTeam, awayTeam, limit = 10) { return api.get('/proxy/h2h', { params: { homeTeam, awayTeam, limit } }) },
  getProxyH2H(homeTeam, awayTeam, limit = 10) { return api.get('/proxy/h2h', { params: { homeTeam, awayTeam, limit } }) },
  getProxyPrediction(fixtureId, homeTeam, awayTeam, leagueName) { return api.get('/proxy/prediction', { params: { fixtureId, homeTeam, awayTeam, leagueName } }) },
  getPredictionAnalysis(params) { return api.get('/proxy/analysis', { params }) },
  getStandingsByLeagueName(leagueName) { return api.get('/crawler/standings/league/' + leagueName) },
  getTeamsByLeague(leagueName) { return api.get('/crawler/teams/league/' + leagueName) },
  searchTeams(name) { return api.get('/crawler/teams/search', { params: { name } }) },
  getTeamDetail(teamName, leagueName) { return api.get('/crawler/teams/detail/' + teamName, { params: { leagueName } }) }
}

export const favoriteApi = {
  add(teamId, teamName) { return api.post('/users/favorites', { teamId, teamName }) },
  remove(teamId) { return api.delete('/users/favorites/' + teamId) },
  list() { return api.get('/users/favorites') },
  addMatch(fixtureId, matchLabel) { return api.post('/users/favorites/matches', { fixtureId: String(fixtureId), matchLabel }) },
  removeMatch(fixtureId) { return api.delete('/users/favorites/matches/' + fixtureId) },
  listMatches() { return api.get('/users/favorites/matches') }
}

export const predictionApi = {
  getTodayPredictions() { return api.get('/predictions/today') },
  getByMatch(fixtureId) { return api.get('/predictions/match/' + fixtureId) },
  getHotPredictions(limit = 10) { return api.get('/predictions/hot', { params: { limit } }) },
  getHistory(limit = 20) { return api.get('/predictions/history', { params: { limit } }) },
  saveMatchResult(data) { return api.post('/predictions/match-result', data) }
}

export default api
