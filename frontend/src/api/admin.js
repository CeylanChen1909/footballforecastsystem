/**
 * 管理端 API（adminApi，契约 §2.7；端点按 src/api/admin.js 一字不差）。
 *
 * 只实现在用的 16 个函数：孤儿面板（AdminPersonaCatalogPanel /
 * AdminCardReportsPanel，未被 Dashboard 挂载，契约 §9.6）独占的
 * previewPersonaCatalog / listPersonaCatalogVersions / listCardLabAudit
 * 随面板一起丢掉；其余 33 个未调用函数按附录 A 不搬。
 * 与 userApi/systemApi 的同端点重复封装（getConfig/saveConfig/getLogs、
 * updateUserRole）以本文件为准：管理端统一走 adminApi。
 */
import { api } from './index'

export const adminApi = {
  listCrawlerMatches(params = {}) { return api.get('/crawler/matches/db/page', { params, preserveMeta: true }) },
  saveCrawlerMatch(data) { return api.post('/crawler/matches', data) },
  updateCrawlerMatch(id, data) { return api.put(`/crawler/matches/${id}/edit`, data) },
  deleteCrawlerMatch(id) { return api.delete(`/crawler/matches/${id}`) },
  listUsers(keyword) { return api.get('/admin/users', { params: { keyword } }) },
  updateUserRole(userId, role) { return api.put('/users/role', { userId, role }) },
  updateUserStatus(userId, status) { return api.put('/admin/users/status', { userId, status }) },
  getConfig() { return api.get('/admin/config') },
  saveConfig(data) { return api.put('/admin/config', data) },
  getLogs() { return api.get('/admin/config/logs') },
  getAnalyticsSummary(days = 7) { return api.get('/analytics/summary', { params: { days } }) },
  listChangelog(params = {}) { return api.get('/admin/changelog', { params }) },
  saveChangelog(data) { return data.id ? api.put(`/admin/changelog/${data.id}`, data) : api.post('/admin/changelog', data) },
  setChangelogStatus(id, status) { return api.put(`/admin/changelog/${id}/status`, null, { params: { status } }) },
  deleteChangelog(id) { return api.delete(`/admin/changelog/${id}`) },
  // “更新主爬虫数据”：未来 7 天逐日访问主爬虫，BBC 解析需留足时间，故 120s 超时。
  syncUpcomingMatches() { return api.post('/crawler/sync/upcoming', null, { timeout: 120000 }) },
}

export default adminApi
