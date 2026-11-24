/**
 * AdminDashboard（自 Vue src/views/admin/AdminDashboard.vue 204 行移植）。
 *
 * 对齐要点（契约 §1.4/§2.3、§9.6）：
 *  - 深色侧栏 #202c25（r11 2026 层）+ 9 菜单（总览/比赛工作台/更新日志/
 *    数据源控制台/模型实验室/行为分析/用户运营/系统配置/审计日志；
 *    sources/models/analytics 为 Dashboard 内联区块，非独立面板）。
 *  - 总览 loadDashboardSummary 用 Promise.allSettled 并行 7 路，逐路失败
 *    记 sectionErrors.summary（其余 4 路 loadMatches/loadUsers/loadConfig/
 *    loadLogs 并行跑，互不阻塞）。
 *  - el-menu → 手写按钮导航（.admin-menu-item）；el-table → .ff-admin-table；
 *    el-pagination → .ff-pager；el-timeline → 简单列表；el-dialog 同步框 →
 *    Modal wide + 手写进度条 div（syncUpcomingMatches 120s 超时，见
 *    api/admin.js）；el-drawer 日志详情 → Modal + .ff-desc-list。
 *  - el-date-picker/time-picker → 原生 date/time input（见
 *    AdminCrawlerMatchEditorDialog）；AdminConfigPanel 的 el-descriptions →
 *    .ff-desc-list、el-switch → .ff-switch checkbox。
 *  - 删除比赛用共享 ConfirmDialog（仅返回 true 时执行）。
 *  - 不移植 AdminCardReportsPanel / AdminPersonaCatalogPanel /
 *    AdminOverviewCharts（全树无 import 的孤儿面板/文件，契约 §9.6 明确
 *    丢掉；其独占的 3 个 API 未在 admin.js 实现）。
 *  - 偏差：Vue 模板 header 只渲染"最近刷新"，pageTitle 计算属性定义了却
 *    未使用；Solid 侧把 pageTitle 渲染为 h1（与 .page-title 样式对应），
 *    否则页头只有一行小字。见 full-migration.md。
 *  - Solid 2.0 注意：config / crawlerMatchForm / currentLog / sectionErrors
 *    用 createStore；但 2.0 的 store setter 只有单函数形态 setState(fn)，
 *    1.x 的 setX({...}) / setX(key, value) 会抛 "fn is not a function"
 *    （2026-11-24 实测），因此这里用 utils/legacyStore 的
 *    createLegacyStore（同名导入），调用点不变；
 *    loadMatches 默认参数读信号在调用时求值，无暂缓写入问题。
 */
import { createMemo, createSignal, For, Show, onSettled } from 'solid-js'
import { createLegacyStore as createStore } from '../../utils/legacyStore'
import { useNavigate } from '@solidjs/router'
import { adminApi } from '../../api/admin'
import { agentApi, crawlerApi, predictionApi } from '../../api'
import { toastSuccess, toastWarning } from '../../utils/toast'
import { createConfirm } from '../../components/ui/ConfirmDialog'
import Modal from '../../components/ui/Modal'
import PageState from '../../components/layout/PageState'
import MatchWorkbench from './MatchWorkbench'
import AdminUsersPanel from './AdminUsersPanel'
import AdminConfigPanel from './AdminConfigPanel'
import AdminLogPanel from './AdminLogPanel'
import AdminChangelogPanel from './AdminChangelogPanel'
import AdminCrawlerMatchEditorDialog from './AdminCrawlerMatchEditorDialog'
import '../../styles/admin.css'
import '../../styles/page-common.css'

const menuItems = [
  { key: 'dashboard', label: '总览' },
  { key: 'matches', label: '比赛工作台' },
  { key: 'changelog', label: '更新日志' },
  { key: 'sources', label: '数据源控制台' },
  { key: 'models', label: '模型实验室' },
  { key: 'analytics', label: '行为分析' },
  { key: 'users', label: '用户运营' },
  { key: 'config', label: '系统配置' },
  { key: 'logs', label: '审计日志' },
]
const pageTitles = {
  dashboard: '管理总览',
  matches: '比赛维护工作台',
  changelog: '更新日志',
  sources: '数据源控制台',
  models: '模型实验室',
  analytics: '行为分析',
  users: '用户运营中心',
  config: '系统配置中心',
  logs: '审计与追踪',
}

const unwrap = (result) => result?.data || result || {}
const normalizeList = (result) => {
  const value = unwrap(result)
  return Array.isArray(value) ? value : Array.isArray(value.items) ? value.items : Array.isArray(value.response) ? value.response : []
}
const normalizePageResult = (res) => {
  if (Array.isArray(res)) return { items: res, total: res.length }
  if (res && Array.isArray(res.items)) return res
  if (res && Array.isArray(res.response)) return { ...res, items: res.response, total: Number(res.total ?? res.response.length) }
  if (res && Array.isArray(res.records)) return { ...res, items: res.records, total: Number(res.total ?? res.records.length) }
  if (res && Array.isArray(res.data?.items)) return res.data
  if (res && Array.isArray(res.data?.response)) return { ...res.data, items: res.data.response, total: Number(res.data.total ?? res.data.response.length) }
  return { items: [], total: 0 }
}
const mapCrawlerMatchRow = (row) => ({
  id: row?.id ?? row?.fixture?.id ?? row?.fixtureId ?? null,
  fixtureId: row?.fixture?.id ?? row?.fixtureId ?? row?.id ?? null,
  externalMatchId: row?.externalMatchId ?? row?.external_id ?? '',
  source: row?.source ?? '',
  leagueId: row?.league?.id ?? row?.leagueId ?? '',
  leagueName: row?.league?.name ?? row?.leagueName ?? '',
  homeTeamId: row?.teams?.home?.id ?? row?.homeTeamId ?? '',
  homeTeamName: row?.teams?.home?.name ?? row?.homeTeamName ?? '',
  homeTeamLogo: row?.teams?.home?.logo ?? row?.homeTeamLogo ?? '',
  awayTeamId: row?.teams?.away?.id ?? row?.awayTeamId ?? '',
  awayTeamName: row?.teams?.away?.name ?? row?.awayTeamName ?? '',
  awayTeamLogo: row?.teams?.away?.logo ?? row?.awayTeamLogo ?? '',
  homeScore: row?.goals?.home ?? row?.homeScore ?? null,
  awayScore: row?.goals?.away ?? row?.awayScore ?? null,
  status: row?.fixture?.status?.short ?? row?.status ?? '',
  matchTime: row?.fixture?.date ?? row?.matchTime ?? '',
  matchDate: row?.fixture?.date ? String(row.fixture.date).slice(0, 10) : row?.matchTime ? String(row.matchTime).slice(0, 10) : '',
  note: row?.note ?? '',
})
const splitMatchDateTime = (value) => {
  if (!value) return { matchDate: '', matchTime: '' }
  const text = String(value)
  const [datePart, timePart = ''] = text.includes('T') ? text.split('T') : text.split(' ')
  return { matchDate: datePart || '', matchTime: (timePart || '').slice(0, 8) }
}
const formatAdminTime = (value) => (value ? new Date(value).toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }) : '—')
const formatAge = (value) => {
  const seconds = Number(value)
  if (!Number.isFinite(seconds) || seconds < 0) return '—'
  if (seconds < 60) return `${Math.round(seconds)} 秒`
  if (seconds < 3600) return `${Math.round(seconds / 60)} 分钟`
  return `${Math.round(seconds / 3600)} 小时`
}

export default function AdminDashboard() {
  const navigate = useNavigate()
  const { ask: askConfirm, ConfirmDialog } = createConfirm()
  const [activeMenu, setActiveMenu] = createSignal('dashboard')
  const [crawlerMatches, setCrawlerMatches] = createSignal([])
  const [crawlerMatchTotal, setCrawlerMatchTotal] = createSignal(0)
  const [crawlerMatchPage, setCrawlerMatchPage] = createSignal(1)
  const [crawlerMatchPageSize, setCrawlerMatchPageSize] = createSignal(20)
  const [crawlerMatchKeyword, setCrawlerMatchKeyword] = createSignal('')
  const [crawlerMatchStatus, setCrawlerMatchStatus] = createSignal('')
  const [crawlerMatchDate, setCrawlerMatchDate] = createSignal('')
  const [crawlerMatchDialogVisible, setCrawlerMatchDialogVisible] = createSignal(false)
  const [crawlerMatchForm, setCrawlerMatchForm] = createStore({})
  const [users, setUsers] = createSignal([])
  const [logs, setLogs] = createSignal([])
  const [config, setConfig] = createStore({})
  const [userKeyword, setUserKeyword] = createSignal('')
  const [userRoleFilter, setUserRoleFilter] = createSignal('')
  const [userStatusFilter, setUserStatusFilter] = createSignal('')
  const [logModuleFilter, setLogModuleFilter] = createSignal('')
  const [dataSourceHealth, setDataSourceHealth] = createSignal({ providers: [], primaryOnly: true, primarySource: 'bbc-scores' })
  const [crawlerHealth, setCrawlerHealth] = createSignal({ taskSummary: {}, history: [] })
  const [agentMetrics, setAgentMetrics] = createSignal(null)
  const [agentHealth, setAgentHealth] = createSignal({ status: 'unknown', models: {} })
  const [modelPerformance, setModelPerformance] = createSignal({})
  const [analyticsSummary, setAnalyticsSummary] = createSignal({})
  const [sectionErrors, setSectionErrors] = createStore({ matches: '', users: '', config: '', logs: '', summary: '' })
  const [syncProgressVisible, setSyncProgressVisible] = createSignal(false)
  const [syncRunning, setSyncRunning] = createSignal(false)
  const [syncProgressFailed, setSyncProgressFailed] = createSignal(false)
  const [syncProgressTitle, setSyncProgressTitle] = createSignal('准备更新主爬虫数据')
  const [syncProgressSubtitle, setSyncProgressSubtitle] = createSignal('正在初始化 BBC 主爬虫同步任务')
  const [syncProgressPercent, setSyncProgressPercent] = createSignal(0)
  const [syncProgressSources, setSyncProgressSources] = createSignal([])
  const [logDetailVisible, setLogDetailVisible] = createSignal(false)
  const [currentLog, setCurrentLog] = createStore({})
  const [dashboardTodayMatches, setDashboardTodayMatches] = createSignal([])
  const [lastReloadAt, setLastReloadAt] = createSignal('')

  const pageTitle = createMemo(() => pageTitles[activeMenu()] || '管理后台')
  const activeUserCount = createMemo(() => users().filter((user) => user.status === 'ACTIVE').length)
  const todaysMatchCount = createMemo(() => dashboardTodayMatches().length)
  const metrics = createMemo(() => [
    { label: '今日比赛', value: todaysMatchCount(), desc: '主爬虫今日采集' },
    { label: '比赛总量', value: crawlerMatchTotal(), desc: '当前生产赛事库' },
    { label: '活跃用户', value: activeUserCount(), desc: '账号状态为 ACTIVE' },
    { label: '审计日志', value: logs().length, desc: '最近加载记录' },
  ])
  const globalModelPolicy = createMemo(() => agentHealth()?.models?.configuredPolicy || {})
  const agentPolicyDetail = createMemo(() => {
    const policy = globalModelPolicy()
    const configured = String(policy.provider || 'auto').toLowerCase()
    const providerItems = agentHealth()?.models?.providers || []
    const ready = (name) => providerItems.some((item) => item.provider === name && item.configured)
    const provider =
      configured === 'openrouter' && ready('openrouter')
        ? 'openrouter'
        : configured === 'scnet' && ready('scnet')
          ? 'scnet'
          : configured === 'deepseek' && ready('deepseek')
            ? 'deepseek'
            : ready('openrouter')
              ? 'openrouter'
              : ready('scnet')
                ? 'scnet'
                : 'deepseek'
    const labels = { openrouter: 'OpenRouter', scnet: 'SCNet', deepseek: 'DeepSeek' }
    const model = policy[provider]?.model || providerItems.find((item) => item.provider === provider)?.model || '环境变量默认模型'
    return `${labels[provider] || provider} · ${model}`
  })
  const agentPolicyLabel = createMemo(() => (agentHealth().status === 'ok' ? '在线' : '需关注'))
  const primarySourceLabel = createMemo(() => dataSourceHealth().primarySource || 'bbc-scores')
  const sourceHealthRows = createMemo(() =>
    (Array.isArray(dataSourceHealth()?.providers) ? dataSourceHealth().providers : [])
      .filter((item) => item.enabled !== false)
      .map((item) => ({
        name: item.name || item.source || 'unknown',
        label: item.name === 'bbc-scores' ? 'BBC 主爬虫' : item.name || item.source || '数据源',
        status: String(item.status || 'UNKNOWN'),
        statusText: item.statusText || '未检测',
        lastMatchCount: Number(item.lastMatchCount || 0),
        transportHealthy: item.transportHealthy,
        lastSuccess: item.lastSuccess,
        dataAgeSeconds: item.dataAgeSeconds,
      })),
  )
  const sourceAttentionCount = createMemo(() => sourceHealthRows().filter((item) => item.status !== 'NORMAL').length)
  const crawlerTaskSummary = createMemo(() => crawlerHealth()?.taskSummary || {})
  const sourceAlerts = createMemo(() => (Array.isArray(dataSourceHealth()?.alerts) ? dataSourceHealth().alerts : []))
  const crawlerHistory = createMemo(() => (Array.isArray(crawlerHealth()?.history) ? crawlerHealth().history : []))
  const recentLogs = createMemo(() => logs().slice(0, 5))
  const modelAccuracy = createMemo(() => (modelPerformance().accuracy == null ? '—' : `${Number(modelPerformance().accuracy).toFixed(1)}%`))

  const loadMatches = async (page = crawlerMatchPage()) => {
    setSectionErrors('matches', '')
    try {
      setCrawlerMatchPage(page)
      const result = normalizePageResult(
        await adminApi.listCrawlerMatches({ keyword: crawlerMatchKeyword(), status: crawlerMatchStatus(), date: crawlerMatchDate(), page, size: crawlerMatchPageSize() }),
      )
      setCrawlerMatches(result.items.map(mapCrawlerMatchRow))
      setCrawlerMatchTotal(Number(result.total || 0))
    } catch (error) {
      setSectionErrors('matches', error?.message || '请检查比赛服务是否可用')
      setCrawlerMatches([])
      setCrawlerMatchTotal(0)
    }
  }
  const loadUsers = async () => {
    setSectionErrors('users', '')
    try {
      let result = await adminApi.listUsers(userKeyword())
      if (userRoleFilter()) result = result.filter((user) => user.role === userRoleFilter())
      if (userStatusFilter()) result = result.filter((user) => user.status === userStatusFilter())
      setUsers(result)
    } catch (error) {
      setSectionErrors('users', error?.message || '请检查用户服务是否可用')
      setUsers([])
    }
  }
  const loadConfig = async () => {
    setSectionErrors('config', '')
    try {
      setConfig(unwrap(await adminApi.getConfig()))
    } catch (error) {
      setSectionErrors('config', error?.message || '请检查配置服务是否可用')
    }
  }
  const loadLogs = async () => {
    setSectionErrors('logs', '')
    try {
      const result = await adminApi.getLogs()
      setLogs(logModuleFilter() ? result.filter((item) => item.module === logModuleFilter()) : result)
    } catch (error) {
      setSectionErrors('logs', error?.message || '请检查日志服务是否可用')
      setLogs([])
    }
  }
  const loadDashboardSummary = async () => {
    setSectionErrors('summary', '')
    const [matchesResult, healthResult, crawlerResult, metricsResult, agentResult, modelResult, analyticsResult] = await Promise.allSettled([
      crawlerApi.getTodayMatches(),
      crawlerApi.getDataSourceHealth(),
      crawlerApi.getHealth(),
      agentApi.metrics(7),
      agentApi.health(),
      predictionApi.getPerformance(30),
      adminApi.getAnalyticsSummary(7),
    ])
    const errors = []
    if (matchesResult.status === 'fulfilled') setDashboardTodayMatches(normalizeList(matchesResult.value))
    else errors.push(`今日比赛：${matchesResult.reason?.message || '加载失败'}`)
    if (healthResult.status === 'fulfilled') setDataSourceHealth(unwrap(healthResult.value) || { providers: [] })
    else errors.push(`数据源状态：${healthResult.reason?.message || '加载失败'}`)
    if (crawlerResult.status === 'fulfilled') setCrawlerHealth(unwrap(crawlerResult.value) || { taskSummary: {} })
    else errors.push(`任务状态：${crawlerResult.reason?.message || '加载失败'}`)
    if (metricsResult.status === 'fulfilled') setAgentMetrics(unwrap(metricsResult.value))
    if (agentResult.status === 'fulfilled') setAgentHealth(unwrap(agentResult.value) || { status: 'unknown', models: {} })
    else errors.push(`Agent 状态：${agentResult.reason?.message || '加载失败'}`)
    if (modelResult.status === 'fulfilled') setModelPerformance(unwrap(modelResult.value) || {})
    if (analyticsResult.status === 'fulfilled') setAnalyticsSummary(unwrap(analyticsResult.value) || {})
    setSectionErrors('summary', errors.join('；'))
  }
  const loadDashboard = async () => {
    await Promise.allSettled([loadMatches(1), loadUsers(), loadConfig(), loadLogs(), loadDashboardSummary()])
    return Object.values(sectionErrors).every((value) => !value)
  }
  const reloadAll = async () => {
    const ok = await loadDashboard()
    setLastReloadAt(formatAdminTime(new Date()))
    if (ok) toastSuccess('已刷新')
    else toastWarning('部分模块刷新失败，请查看页面提示后重试')
    return ok
  }
  const goFront = () => navigate('/matches')
  const handleCrawlerMatchSizeChange = (size) => {
    setCrawlerMatchPageSize(size)
    loadMatches(1)
  }
  const createNewFixtureId = () => Date.now()
  const openCrawlerMatchDialog = (row = null) => {
    const mapped = row ? mapCrawlerMatchRow(row) : {}
    const dt = splitMatchDateTime(mapped.matchTime || row?.matchTime)
    setCrawlerMatchForm({
      id: row?.id ?? null,
      fixtureId: row?.fixtureId ?? createNewFixtureId(),
      externalMatchId: row?.externalMatchId || mapped.externalMatchId || '',
      source: row?.source || 'bbc-scores',
      leagueId: '',
      leagueName: '',
      round: '',
      venue: '',
      homeTeamId: '',
      homeTeamName: '',
      homeTeamLogo: '',
      awayTeamId: '',
      awayTeamName: '',
      awayTeamLogo: '',
      homeScore: null,
      awayScore: null,
      status: 'NS',
      matchDate: dt.matchDate,
      matchTime: dt.matchTime,
      note: '',
      ...mapped,
      matchDate: row ? dt.matchDate : '',
      matchTime: row ? dt.matchTime : '',
    })
    setCrawlerMatchDialogVisible(true)
  }
  const saveCrawlerMatch = async () => {
    const matchTime = crawlerMatchForm.matchDate ? `${crawlerMatchForm.matchDate}T${(crawlerMatchForm.matchTime || '00:00:00').slice(0, 8)}` : crawlerMatchForm.matchTime || null
    const payload = {
      ...crawlerMatchForm,
      fixtureId: crawlerMatchForm.fixtureId ? Number(crawlerMatchForm.fixtureId) : null,
      externalMatchId: crawlerMatchForm.externalMatchId || String(crawlerMatchForm.fixtureId || ''),
      matchTime,
      homeScore: crawlerMatchForm.homeScore === '' || crawlerMatchForm.homeScore == null ? null : Number(crawlerMatchForm.homeScore),
      awayScore: crawlerMatchForm.awayScore === '' || crawlerMatchForm.awayScore == null ? null : Number(crawlerMatchForm.awayScore),
    }
    if (payload.id) await adminApi.updateCrawlerMatch(payload.id, payload)
    else await adminApi.saveCrawlerMatch(payload)
    setCrawlerMatchDialogVisible(false)
    await loadMatches(crawlerMatchPage())
    toastSuccess('比赛已保存')
  }
  const deleteCrawlerMatch = async (row) => {
    const id = row?.id
    if (!id) {
      toastWarning('未找到可删除的比赛ID')
      return
    }
    const ok = await askConfirm({ title: '提示', message: '确认删除该比赛？', confirmText: '删除', cancelText: '取消' })
    if (ok !== true) return
    await adminApi.deleteCrawlerMatch(id)
    await loadMatches(crawlerMatchPage())
    toastSuccess('比赛已删除')
  }
  const handleUserCommand = async (command, row) => {
    if (command === 'toggleRole') await adminApi.updateUserRole(row.id, row.role === 'ADMIN' ? 'USER' : 'ADMIN')
    if (command === 'toggleStatus') await adminApi.updateUserStatus(row.id, row.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE')
    await loadUsers()
  }
  const saveConfig = async () => {
    await adminApi.saveConfig({ ...config })
    await loadConfig()
    toastSuccess('配置已保存')
  }
  const syncTasks = [{ key: 'bbc-scores', label: 'BBC 主爬虫：今日及未来 7 天赛事', action: () => adminApi.syncUpcomingMatches() }]
  const resetSyncProgress = () => {
    setSyncProgressFailed(false)
    setSyncProgressPercent(0)
    setSyncProgressTitle('准备更新主爬虫数据')
    setSyncProgressSubtitle('正在初始化 BBC 主爬虫同步任务')
    setSyncProgressSources(syncTasks.map((item) => ({ ...item, status: 'pending', message: '等待同步' })))
  }
  const updateSyncPercent = () => {
    const done = syncProgressSources().filter((item) => ['success', 'error'].includes(item.status)).length
    setSyncProgressPercent(syncProgressSources().length ? Math.round((done / syncProgressSources().length) * 100) : 0)
  }
  const syncStatusTag = (status) => ({ pending: 'info', running: 'warning', success: 'success', error: 'danger' }[status] || 'info')
  const syncStatusText = (status) => ({ pending: '等待', running: '同步中', success: '完成', error: '失败' }[status] || status)
  const syncMatchesNow = async () => {
    if (syncRunning()) return
    resetSyncProgress()
    setSyncProgressVisible(true)
    setSyncRunning(true)
    try {
      for (const task of syncTasks) {
        setSyncProgressTitle(`正在同步：${task.label}`)
        setSyncProgressSources((prev) => prev.map((item) => (item.key === task.key ? { ...item, status: 'running', message: '正在请求后端同步接口...' } : item)))
        try {
          const report = unwrap(await task.action())
          setSyncProgressSources((prev) => prev.map((item) => (item.key === task.key ? { ...item, status: 'success', message: `完成：总计 ${report?.total || 0}，新增 ${report?.inserted || 0}，更新 ${report?.updated || 0}` } : item)))
        } catch (error) {
          setSyncProgressFailed(true)
          const message = error?.response?.status === 504 ? '同步超时，主爬虫响应较慢' : error?.response?.data?.message || error?.message || '同步失败'
          setSyncProgressSources((prev) => prev.map((item) => (item.key === task.key ? { ...item, status: 'error', message } : item)))
        }
        updateSyncPercent()
      }
      setSyncProgressTitle(syncProgressFailed() ? '主爬虫同步失败' : '主爬虫同步完成')
      setSyncProgressSubtitle(syncProgressFailed() ? '请检查 BBC 来源状态后重试' : '比赛列表正在刷新')
      if (syncProgressFailed()) toastWarning('主爬虫同步失败')
      else toastSuccess('主爬虫同步完成')
    } finally {
      setSyncRunning(false)
      setSyncProgressPercent(100)
      await loadMatches(crawlerMatchPage())
      await loadDashboardSummary()
    }
  }
  const openLogDetail = (row) => {
    setCurrentLog(row || {})
    setLogDetailVisible(true)
  }

  onSettled(async () => {
    await loadDashboard()
    setLastReloadAt(formatAdminTime(new Date()))
  })

  return (
    <div class="admin-shell">
      <div class="admin-layout">
        <aside class="admin-aside" style={{ width: '270px', 'flex-shrink': 0 }}>
          <div class="brand-block">
            <div class="brand-mark">FF</div>
            <div>
              <div class="brand-name">Football Admin</div>
              <div class="brand-sub">赛事数据运营控制台</div>
            </div>
          </div>
          <nav class="side-section admin-menu" aria-label="管理后台导航">
            <div class="side-label">工作台</div>
            <For each={menuItems}>
              {(item) => (
                <button type="button" class={`admin-menu-item${activeMenu() === item.key ? ' is-active' : ''}`} aria-current={activeMenu() === item.key ? 'page' : undefined} onClick={() => setActiveMenu(item.key)}>
                  {item.label}
                </button>
              )}
            </For>
          </nav>
          <div class="side-section side-meta">
            <div class="side-label">当前概览</div>
            <div class="meta-item">
              <span>比赛维护</span>
              <strong>{crawlerMatchTotal()}</strong>
            </div>
            <div class="meta-item">
              <span>用户</span>
              <strong>{users().length}</strong>
            </div>
            <div class="meta-item">
              <span>Agent 完成</span>
              <strong>{agentMetrics()?.completed ?? '—'}</strong>
            </div>
            <div class="meta-item">
              <span>日志</span>
              <strong>{logs().length}</strong>
            </div>
          </div>
        </aside>
        <div class="admin-content">
          <header class="admin-header">
            <div class="header-left">
              <h1 class="page-title">{pageTitle()}</h1>
              <Show when={lastReloadAt()}>
                <span class="last-reload">最近刷新 {lastReloadAt()}</span>
              </Show>
            </div>
            <div class="header-actions">
              <Show when={activeMenu() === 'users'}>
                <input class="ff-input head-search" type="search" placeholder="搜索用户名" aria-label="搜索用户名" value={userKeyword()} onInput={(e) => setUserKeyword(e.currentTarget.value)} onChange={loadUsers} />
              </Show>
              <button type="button" class="ff-btn ff-btn-ghost" onClick={reloadAll}>
                刷新
              </button>
              <Show when={activeMenu() === 'matches'}>
                <button type="button" class="ff-btn ff-btn-plain" onClick={syncMatchesNow}>
                  更新主爬虫数据
                </button>
              </Show>
              <button type="button" class="ff-btn ff-btn-plain" onClick={goFront}>
                返回前台
              </button>
            </div>
          </header>
          <main id="app-main" class="admin-main" tabindex="-1">
            <Show when={activeMenu() === 'dashboard'}>
              <Show when={sectionErrors.summary}>
                <div class="ff-alert is-warning summary-alert">
                  <span>{sectionErrors.summary}</span>
                </div>
              </Show>
              <section class="dashboard-top">
                <For each={metrics()}>
                  {(item) => (
                    <div class="metric-card ff-panel">
                      <div class="metric-label">{item.label}</div>
                      <div class="metric-value">{item.value}</div>
                      <div class="metric-desc">{item.desc}</div>
                    </div>
                  )}
                </For>
              </section>
              <section class="dashboard-grid">
                <div class="panel-card ff-panel">
                  <div class="panel-header">
                    <span>Agent 运营</span>
                    <span class={`ff-tag ${agentHealth().status === 'ok' ? 'is-success' : 'is-warning'}`}>{agentPolicyLabel()}</span>
                  </div>
                  <div class="agent-metric-grid">
                    <div>
                      <span>发送</span>
                      <strong>{agentMetrics()?.sent ?? '—'}</strong>
                    </div>
                    <div>
                      <span>完成</span>
                      <strong>{agentMetrics()?.completed ?? '—'}</strong>
                    </div>
                    <div>
                      <span>平均延迟</span>
                      <strong>{agentMetrics()?.avgLatencyMs ? `${agentMetrics().avgLatencyMs}ms` : '—'}</strong>
                    </div>
                    <div>
                      <span>有帮助</span>
                      <strong>{agentMetrics()?.feedback?.helpful ?? '—'}</strong>
                    </div>
                  </div>
                  <div class="agent-policy-line">
                    当前策略：{agentPolicyDetail()} · 思考摘要{globalModelPolicy().thinkingEnabled === false ? '关闭' : '开启'} · 故障切换{globalModelPolicy().fallbackEnabled === false ? '关闭' : '开启'}
                  </div>
                  <div class="agent-provider-metrics">
                    <For each={agentMetrics()?.providers || []} fallback={<span>暂无模型调用记录</span>}>
                      {(item) => (
                        <span>
                          {item.provider || 'unknown'} · {item.total}
                        </span>
                      )}
                    </For>
                  </div>
                </div>
                <div class="panel-card ff-panel">
                  <div class="panel-header">
                    <span>主爬虫监控</span>
                    <span class={`ff-tag ${sourceAttentionCount() ? 'is-warning' : 'is-success'}`}>{sourceAttentionCount() ? `${sourceAttentionCount()} 项需关注` : '运行正常'}</span>
                  </div>
                  <div class="source-monitor-summary">
                    主源：{primarySourceLabel()} · 任务：{crawlerTaskSummary().running || 0} 运行 · {crawlerTaskSummary().success || 0} 成功 · {crawlerTaskSummary().failed || 0} 失败
                  </div>
                  <div class="source-monitor-list">
                    <For each={sourceHealthRows()} fallback={<div class="ff-empty">暂无启用数据源</div>}>
                      {(item) => (
                        <div class="source-monitor-item">
                          <span class={`source-monitor-dot is-${item.status.toLowerCase()}`} />
                          <div>
                            <strong>{item.label}</strong>
                            <small>
                              {item.statusText} · 最近 {item.lastMatchCount} 场
                            </small>
                          </div>
                          <span class={`ff-tag ${item.status === 'NORMAL' ? 'is-success' : 'is-warning'}`}>{item.statusText}</span>
                        </div>
                      )}
                    </For>
                  </div>
                  <Show when={sourceAlerts().length}>
                    <div class="source-alert-list">
                      <For each={sourceAlerts().slice(0, 5)}>
                        {(alert) => (
                          <div class="source-alert-item">
                            <span class="ff-tag is-warning">{alert.severity || '关注'}</span>
                            <span>
                              {alert.source} · {alert.message}
                            </span>
                          </div>
                        )}
                      </For>
                    </div>
                  </Show>
                  <Show when={crawlerHistory().length}>
                    <div class="crawler-history">
                      <small>最近任务</small>
                      <For each={crawlerHistory().slice(0, 3)}>
                        {(item) => (
                          <span>
                            {item.taskName} · {item.result} · {item.processedCount || 0} 条
                          </span>
                        )}
                      </For>
                    </div>
                  </Show>
                </div>
                <div class="panel-card ff-panel span-wide">
                  <div class="panel-header">
                    <span>快捷操作</span>
                    <span class="ff-tag is-info">Shortcuts</span>
                  </div>
                  <div class="shortcut-grid">
                    <button
                      type="button"
                      class="ff-btn ff-btn-plain"
                      onClick={() => {
                        setActiveMenu('matches')
                        openCrawlerMatchDialog()
                      }}
                    >
                      新增比赛维护
                    </button>
                    <button
                      type="button"
                      class="ff-btn ff-btn-plain"
                      onClick={() => {
                        setActiveMenu('matches')
                        syncMatchesNow()
                      }}
                    >
                      更新主爬虫
                    </button>
                    <button type="button" class="ff-btn ff-btn-ghost" onClick={() => setActiveMenu('users')}>
                      运营用户
                    </button>
                    <button type="button" class="ff-btn ff-btn-ghost" onClick={() => setActiveMenu('logs')}>
                      查看日志
                    </button>
                  </div>
                </div>
                <div class="panel-card ff-panel span-wide">
                  <div class="panel-header">
                    <span>最近操作</span>
                    <span class="ff-tag is-warning">Audit</span>
                  </div>
                  <Show when={recentLogs().length} fallback={<div class="ff-empty">暂无操作记录</div>}>
                    <div class="recent-log-list">
                      <For each={recentLogs()}>
                        {(item) => (
                          <div class="recent-log-item">
                            <span class={`recent-log-dot is-${item.result === 'SUCCESS' ? 'success' : 'error'}`} />
                            <span>
                              {item.operatorName || '系统'} · {item.action || '-'} · {item.content || '-'}
                            </span>
                            <time>{item.createdAt || ''}</time>
                          </div>
                        )}
                      </For>
                    </div>
                  </Show>
                </div>
              </section>
            </Show>

            <Show when={activeMenu() === 'sources'}>
              <section class="admin-panel-section source-console">
                <div class="panel-card ff-panel">
                  <div class="panel-header">
                    <span>数据源控制台</span>
                    <span class={`ff-tag ${sourceAttentionCount() ? 'is-warning' : 'is-success'}`}>{sourceAttentionCount() ? `${sourceAttentionCount()} 项需关注` : '主源正常'}</span>
                  </div>
                  <div class="ff-alert is-info">
                    <span>当前生产链路只写入 BBC 主爬虫，其他来源仅保留为诊断能力，不会造成重复比赛写入。</span>
                  </div>
                  <div class="source-console-grid">
                    <For each={sourceHealthRows()} fallback={<div class="ff-empty">暂无数据源状态</div>}>
                      {(item) => (
                        <div class="source-console-card">
                          <div class="source-console-card-head">
                            <span class={`source-monitor-dot is-${item.status.toLowerCase()}`} />
                            <strong>{item.label}</strong>
                            <span class={`ff-tag ${item.status === 'NORMAL' ? 'is-success' : item.status === 'EMPTY' ? 'is-info' : 'is-warning'}`}>{item.statusText}</span>
                          </div>
                          <dl>
                            <div>
                              <dt>最近返回</dt>
                              <dd>{item.lastMatchCount} 场</dd>
                            </div>
                            <div>
                              <dt>传输状态</dt>
                              <dd>{item.transportHealthy === false ? '不可用' : '可用'}</dd>
                            </div>
                            <div>
                              <dt>最近成功</dt>
                              <dd>{formatAdminTime(item.lastSuccess)}</dd>
                            </div>
                            <div>
                              <dt>数据年龄</dt>
                              <dd>{formatAge(item.dataAgeSeconds)}</dd>
                            </div>
                          </dl>
                        </div>
                      )}
                    </For>
                  </div>
                  <Show when={sourceAlerts().length}>
                    <div class="source-alert-list">
                      <For each={sourceAlerts()}>
                        {(alert) => (
                          <div class="source-alert-item">
                            <span class="ff-tag is-warning">{alert.severity || '关注'}</span>
                            <span>
                              {alert.source} · {alert.message}
                            </span>
                          </div>
                        )}
                      </For>
                    </div>
                  </Show>
                </div>
              </section>
            </Show>

            <Show when={activeMenu() === 'models'}>
              <section class="admin-panel-section model-lab">
                <div class="panel-card ff-panel">
                  <div class="panel-header">
                    <span>模型实验室</span>
                    <span class="ff-tag is-info">只读评估</span>
                  </div>
                  <div class="ff-alert is-info">
                    <span>生产模型由离线训练与门槛审核替换；本页只展示线上已验证表现，不提供网页端直接训练，避免误触导致服务抖动。</span>
                  </div>
                  <div class="model-lab-summary">
                    <div>
                      <span>评估窗口</span>
                      <strong>近 {modelPerformance().windowDays || 30} 日</strong>
                    </div>
                    <div>
                      <span>已验证</span>
                      <strong>{modelPerformance().evaluated ?? '—'} 场</strong>
                    </div>
                    <div>
                      <span>准确率</span>
                      <strong>{modelAccuracy()}</strong>
                    </div>
                    <div>
                      <span>生产版本</span>
                      <strong class="model-version">{(modelPerformance().modelVersions || [])[0] || '统一快照'}</strong>
                    </div>
                  </div>
                  <div class="model-lab-note">
                    <span>ⓘ</span>
                    <span>准确率接近基线时不自动宣传“高置信度”。模型适用范围、数据完整性和样本量应与每场预测一起阅读。</span>
                  </div>
                  <Show when={modelPerformance().byLeague}>
                    <div class="model-league-list">
                      <For each={Object.entries(modelPerformance().byLeague)}>
                        {([league, count]) => (
                          <div>
                            <span>{league}</span>
                            <strong>{count} 场预测</strong>
                          </div>
                        )}
                      </For>
                    </div>
                  </Show>
                </div>
              </section>
            </Show>

            <Show when={activeMenu() === 'analytics'}>
              <section class="admin-panel-section analytics-console">
                <div class="panel-card ff-panel">
                  <div class="panel-header">
                    <span>行为分析</span>
                    <span class="ff-tag is-info">近 {analyticsSummary().days || 7} 日</span>
                  </div>
                  <div class="analytics-summary-grid">
                    <div>
                      <span>事件总量</span>
                      <strong>{analyticsSummary().totalEvents ?? '—'}</strong>
                    </div>
                    <div>
                      <span>活跃用户</span>
                      <strong>{analyticsSummary().uniqueUsers ?? '—'}</strong>
                    </div>
                    <div>
                      <span>埋点状态</span>
                      <strong>{analyticsSummary().status === 'AVAILABLE' ? '可用' : '待初始化'}</strong>
                    </div>
                  </div>
                  <Show when={analyticsSummary().status !== 'AVAILABLE'}>
                    <div class="ff-alert is-warning">
                      <span>{analyticsSummary().message || '埋点数据暂不可用'}</span>
                    </div>
                  </Show>
                  <Show when={analyticsSummary().status === 'AVAILABLE'}>
                    <div class="analytics-funnel" aria-label="核心转化漏斗">
                      <span>比赛→预测 {analyticsSummary().funnel?.matchToPredictionRate ?? 0}%</span>
                      <span>预测→Agent {analyticsSummary().funnel?.predictionToAgentRate ?? 0}%</span>
                    </div>
                    <div class="analytics-event-list">
                      <For each={analyticsSummary().eventCounts || []} fallback={<div class="ff-empty">暂无行为数据</div>}>
                        {(item) => (
                          <div>
                            <span>{item.event_name || item.eventName}</span>
                            <strong>{item.total}</strong>
                          </div>
                        )}
                      </For>
                    </div>
                  </Show>
                </div>
              </section>
            </Show>

            <Show when={activeMenu() === 'matches'}>
              <section class="admin-panel-section">
                <MatchWorkbench
                  matches={crawlerMatches()}
                  total={crawlerMatchTotal()}
                  page={crawlerMatchPage()}
                  pageSize={crawlerMatchPageSize()}
                  keyword={crawlerMatchKeyword()}
                  status={crawlerMatchStatus()}
                  date={crawlerMatchDate()}
                  error={sectionErrors.matches}
                  onKeywordChange={setCrawlerMatchKeyword}
                  onStatusChange={setCrawlerMatchStatus}
                  onDateChange={setCrawlerMatchDate}
                  onReload={loadMatches}
                  onSizeChange={handleCrawlerMatchSizeChange}
                  onCreate={() => openCrawlerMatchDialog()}
                  onEdit={openCrawlerMatchDialog}
                  onDelete={deleteCrawlerMatch}
                />
              </section>
            </Show>
            <Show when={activeMenu() === 'changelog'}>
              <section class="admin-panel-section">
                <AdminChangelogPanel />
              </section>
            </Show>
            <Show when={activeMenu() === 'users'}>
              <section class="admin-panel-section">
                <AdminUsersPanel
                  users={users()}
                  roleFilter={userRoleFilter()}
                  statusFilter={userStatusFilter()}
                  error={sectionErrors.users}
                  onRoleFilterChange={setUserRoleFilter}
                  onStatusFilterChange={setUserStatusFilter}
                  onFilterChange={loadUsers}
                  onRetry={loadUsers}
                  onUserAction={handleUserCommand}
                />
              </section>
            </Show>
            <Show when={activeMenu() === 'config'}>
              <section class="admin-panel-section">
                <AdminConfigPanel config={config} error={sectionErrors.config} onConfigChange={(key, value) => setConfig(key, value)} onSave={saveConfig} onRetry={loadConfig} />
              </section>
            </Show>
            <Show when={activeMenu() === 'logs'}>
              <section class="admin-panel-section">
                <AdminLogPanel logs={logs()} moduleFilter={logModuleFilter()} error={sectionErrors.logs} onModuleFilterChange={setLogModuleFilter} onFilterChange={loadLogs} onRetry={loadLogs} onOpenDetail={openLogDetail} />
              </section>
            </Show>
          </main>
        </div>
      </div>

      <AdminCrawlerMatchEditorDialog
        open={crawlerMatchDialogVisible()}
        form={crawlerMatchForm}
        onClose={() => setCrawlerMatchDialogVisible(false)}
        onSave={saveCrawlerMatch}
        onFormChange={(key, value) => setCrawlerMatchForm(key, value)}
      />

      <Modal open={syncProgressVisible()} title="更新主爬虫数据" wide onClose={() => { if (!syncRunning()) setSyncProgressVisible(false) }}>
        <div class="sync-progress-head">
          <div>
            <div class="sync-progress-title">{syncProgressTitle()}</div>
            <div class="sync-progress-subtitle">{syncProgressSubtitle()}</div>
          </div>
          <span class={`ff-tag ${syncRunning() ? 'is-warning' : syncProgressFailed() ? 'is-danger' : 'is-success'}`}>{syncRunning() ? '同步中' : syncProgressFailed() ? '失败' : '已完成'}</span>
        </div>
        <div class={`sync-progress-bar${syncProgressFailed() && !syncRunning() ? ' is-error' : ''}`} role="progressbar" aria-valuenow={syncProgressPercent()} aria-valuemin="0" aria-valuemax="100">
          <i style={{ width: `${syncProgressPercent()}%` }} />
        </div>
        <div class="sync-source-list">
          <For each={syncProgressSources()}>
            {(item) => (
              <div class="sync-source-item">
                <div class="sync-source-main">
                  <span class={`sync-dot is-${item.status}`} />
                  <div>
                    <strong>{item.label}</strong>
                    <p>{item.message}</p>
                  </div>
                </div>
                <span class={`ff-tag is-${syncStatusTag(item.status)}`}>{syncStatusText(item.status)}</span>
              </div>
            )}
          </For>
        </div>
        <div class="ff-modal-actions">
          <button type="button" class="ff-btn ff-btn-ghost" disabled={syncRunning()} onClick={() => setSyncProgressVisible(false)}>
            关闭
          </button>
          <button type="button" class="ff-btn ff-btn-primary" disabled={syncRunning()} onClick={syncMatchesNow}>
            {syncRunning() ? '正在同步' : '重新同步'}
          </button>
        </div>
      </Modal>

      <Modal open={logDetailVisible()} title="日志详情" onClose={() => setLogDetailVisible(false)}>
        <dl class="ff-desc-list">
          <div class="ff-desc-row">
            <dt>时间</dt>
            <dd>{currentLog.createdAt || '-'}</dd>
          </div>
          <div class="ff-desc-row">
            <dt>操作人</dt>
            <dd>{currentLog.operatorName || '-'}</dd>
          </div>
          <div class="ff-desc-row">
            <dt>模块</dt>
            <dd>{currentLog.module || '-'}</dd>
          </div>
          <div class="ff-desc-row">
            <dt>动作</dt>
            <dd>{currentLog.action || '-'}</dd>
          </div>
          <div class="ff-desc-row">
            <dt>对象类型</dt>
            <dd>{currentLog.targetType || '-'}</dd>
          </div>
          <div class="ff-desc-row">
            <dt>对象ID</dt>
            <dd>{currentLog.targetId || '-'}</dd>
          </div>
          <div class="ff-desc-row">
            <dt>内容</dt>
            <dd>{currentLog.content || '-'}</dd>
          </div>
          <div class="ff-desc-row">
            <dt>结果</dt>
            <dd>
              <span class={`ff-tag ${currentLog.result === 'SUCCESS' ? 'is-success' : 'is-danger'}`}>{currentLog.result || '-'}</span>
            </dd>
          </div>
        </dl>
      </Modal>
      <ConfirmDialog />
    </div>
  )
}
