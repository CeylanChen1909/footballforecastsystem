/**
 * Profile 页（自 Vue src/views/user/Profile.vue 388 行移植，需登录）。
 *
 * 对齐要点（契约 §1.4/§2.3）：
 *  - App 守卫已拦未登录；页内 onSettled 再判无 token 则回 /matches。
 *  - 4 个 tab（概览/收藏/预测记录/账户安全，键盘左右/Home/End 切换，
 *    切换后 workspace 滚动到视口顶）。
 *  - 模型表现条 getPerformance(7)；收藏（球队/比赛子 tab、搜索/排序/状态
 *    筛选）；预测记录 getHistoryPage(cursor, 20) 游标分页 + 失败回退
 *    getHistory(50)；账户安全（提醒偏好表单 getPreferences 字符串 JSON
 *    兼容 + updatePreferences；通知记录 50 条、全部已读/单条已读；会话
 *    getSessions(refreshToken)；改密码 ≥8 位且两次一致，成功后强制登出回
 *    /matches；退出其他设备；注销账号）。
 *  - 4 处 ElMessageBox.confirm 改用共享 ConfirmDialog（removeFav、
 *    removeMatchFav、revokeAllSessions、disableAccount；仅返回 true 时执行，
 *    取消/关闭均不执行且不报错）。
 *  - el-form/el-select 裸用处加统一样式（.ff-form-item，见 profile.css 附录）；
 *    el-tabs → 手写 tab 按钮；el-radio-group → 分段按钮
 *    （.history-status-seg）；编辑资料用 Modal 替代 el-dialog；单行压缩
 *    样式已在 profile.css 生成时展开。
 *  - Solid 2.0 注意：表单对象用 createStore；但 2.0 的 store setter 只有
 *    单函数形态 setState(fn)，1.x 的 setX({...}) / setX(key, value) 会抛
 *    "fn is not a function"（2026-11-24 实测），因此这里用
 *    utils/legacyStore 的 createLegacyStore（同名导入），调用点不变；
 *    通知已读状态用 setNotifications 映射新数组（直接改 item.read_at
 *    不触发重渲染）；effect 回调不返回 Promise。
 */
import { createMemo, createSignal, For, Show, onCleanup, onSettled } from 'solid-js'
import { createLegacyStore as createStore } from '../../utils/legacyStore'
import { useNavigate, useSearchParams } from '@solidjs/router'
import { favoriteApi, predictionApi, userApi } from '../../api'
import auth from '../../stores/auth'
import { authStorage } from '../../utils/authStorage'
import { toastError, toastSuccess, toastWarning } from '../../utils/toast'
import { createConfirm } from '../../components/ui/ConfirmDialog'
import Modal from '../../components/ui/Modal'
import PageState from '../../components/layout/PageState'
import '../../styles/profile.css'
import '../../styles/page-common.css'

const unwrap = (value) => value?.data ?? value
const parseBusinessDate = (value) => {
  if (!value) return null
  const text = String(value).trim().replace(' ', 'T')
  const normalized = /(?:Z|[+-]\d{2}:?\d{2})$/i.test(text) ? text : `${text}+08:00`
  const date = new Date(normalized)
  return Number.isNaN(date.getTime()) ? null : date
}
const formatDate = (value) => {
  const date = parseBusinessDate(value)
  return date ? date.toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }) : '时间未知'
}
const formatDateOnly = (value) => {
  const date = parseBusinessDate(value)
  return date ? date.toLocaleDateString('zh-CN', { timeZone: 'Asia/Shanghai', year: 'numeric', month: 'long', day: 'numeric' }) : ''
}
const normalizedProb = (value) => {
  const number = Number(value)
  if (!Number.isFinite(number)) return 0
  const ratio = number > 1 ? number / 100 : number
  return Math.max(0, Math.min(1, ratio))
}
const pct = (value) => `${Math.round(normalizedProb(value) * 100)}%`
const resultLabel = (label) => ({ HOME_WIN: '预测主胜', DRAW: '预测平局', AWAY_WIN: '预测客胜' }[label] || '未知结果')
const resultTagType = (label) => ({ HOME_WIN: 'success', DRAW: 'info', AWAY_WIN: 'warning' }[label] || 'info')
const favoriteMatchState = (item) => {
  const time = parseBusinessDate(item.matchTime)?.getTime() ?? NaN
  if (!Number.isFinite(time)) return { key: 'unknown', label: '赛程待同步', type: 'info' }
  if (time < Date.now()) return { key: 'finished', label: '已结束', type: 'info' }
  return { key: 'upcoming', label: '未开始', type: 'success' }
}
const formatMatchTime = (value) => {
  const date = parseBusinessDate(value)
  return date ? date.toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai', month: '2-digit', day: '2-digit', weekday: 'short', hour: '2-digit', minute: '2-digit' }) : '开赛时间待同步'
}
const formatSessionExpiry = (seconds) => {
  const value = Number(seconds)
  if (!Number.isFinite(value) || value <= 0) return '即将过期'
  if (value >= 86400) return `${Math.ceil(value / 86400)} 天`
  if (value >= 3600) return `${Math.ceil(value / 3600)} 小时`
  return `${Math.ceil(value / 60)} 分钟`
}
const verificationLabel = (item) => (Number(item.isCorrect) === 1 ? '已命中' : Number(item.isCorrect) === 0 ? '未命中' : '待验证')
const verificationClass = (item) => (Number(item.isCorrect) === 1 ? 'is-correct' : Number(item.isCorrect) === 0 ? 'is-wrong' : 'is-pending')
const notificationTime = (value) => (value ? new Date(value).toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }) : '')
const teamInitial = (name) => (name || '队').slice(0, 1).toUpperCase()

export default function Profile() {
  const navigate = useNavigate()
  const [searchParams] = useSearchParams()
  const { ask: askConfirm, ConfirmDialog } = createConfirm()
  const [profileLoading, setProfileLoading] = createSignal(true)
  const [activeTab, setActiveTab] = createSignal('overview')
  const [favoriteTab, setFavoriteTab] = createSignal('teams')
  const [favoriteKeyword, setFavoriteKeyword] = createSignal('')
  const [favoriteSort, setFavoriteSort] = createSignal('recent')
  const [favoriteMatchFilter, setFavoriteMatchFilter] = createSignal('all')
  const [historyStatus, setHistoryStatus] = createSignal('all')
  const [historyOutcome, setHistoryOutcome] = createSignal('all')
  const [historyKeyword, setHistoryKeyword] = createSignal('')
  const [historyDateRange, setHistoryDateRange] = createSignal('all')
  const [profileIdentity, setProfileIdentity] = createStore({ userId: null, username: '', nickname: '', email: '', emailVerified: false, avatarData: '', nicknameUpdatedAt: null, role: 'USER', createdAt: null })
  const [accountProfile, setAccountProfile] = createStore({ email: '', phone: '', matchRemindersEnabled: false })
  const [passwordForm, setPasswordForm] = createStore({ currentPassword: '', newPassword: '', confirmPassword: '' })
  const [profileForm, setProfileForm] = createStore({ nickname: '' })
  const [profileDialogVisible, setProfileDialogVisible] = createSignal(false)
  const [profileSaving, setProfileSaving] = createSignal(false)
  const [avatarSaving, setAvatarSaving] = createSignal(false)
  const [avatarLoadFailed, setAvatarLoadFailed] = createSignal(false)
  const [passwordSaving, setPasswordSaving] = createSignal(false)
  const [favorites, setFavorites] = createSignal([])
  const [matchFavorites, setMatchFavorites] = createSignal([])
  const [history, setHistory] = createSignal([])
  const [personalStats, setPersonalStats] = createSignal(null)
  const [publicStats, setPublicStats] = createSignal(null)
  const [historyCursor, setHistoryCursor] = createSignal('')
  const [historyHasMore, setHistoryHasMore] = createSignal(false)
  const [historyLoading, setHistoryLoading] = createSignal(false)
  const [favoritesLoading, setFavoritesLoading] = createSignal(false)
  const [matchFavoritesLoading, setMatchFavoritesLoading] = createSignal(false)
  const [historyError, setHistoryError] = createSignal('')
  const [favoritesError, setFavoritesError] = createSignal('')
  const [matchFavoritesError, setMatchFavoritesError] = createSignal('')
  const [notifications, setNotifications] = createSignal([])
  const [notificationUnread, setNotificationUnread] = createSignal(0)
  const [notificationLoading, setNotificationLoading] = createSignal(false)
  const [sessions, setSessions] = createSignal([])
  const [sessionsLoading, setSessionsLoading] = createSignal(false)
  const [statsLoadedAt, setStatsLoadedAt] = createSignal(null)
  let workspaceRef = null
  let avatarInput = null

  const tabs = createMemo(() => [
    { key: 'overview', label: '概览' },
    { key: 'favorites', label: '收藏', count: favorites().length + matchFavorites().length },
    { key: 'history', label: '预测记录', count: personalStats()?.total },
    { key: 'security', label: '账户安全', count: notificationUnread() || undefined },
  ])
  const displayName = createMemo(() => profileIdentity.nickname || profileIdentity.username || auth.username() || '球迷')
  const avatarInitial = createMemo(() => displayName().slice(0, 1).toUpperCase())
  const avatarSrc = createMemo(() => (avatarLoadFailed() ? '' : profileIdentity.avatarData))
  const emailVerified = createMemo(() => Boolean(profileIdentity.email && profileIdentity.emailVerified))
  const publicAccuracy = createMemo(() => (publicStats()?.accuracy != null ? `${publicStats().accuracy}%` : '—'))
  const statsUpdatedText = createMemo(() =>
    statsLoadedAt()
      ? `本次读取于 ${statsLoadedAt().toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' })}`
      : '正在读取数据',
  )
  const filteredFavorites = createMemo(() => {
    const keyword = favoriteKeyword().trim().toLowerCase()
    const rows = favorites().filter((item) => !keyword || `${item.teamName || ''} ${item.leagueName || ''}`.toLowerCase().includes(keyword))
    return [...rows].sort((a, b) =>
      favoriteSort() === 'name'
        ? String(a.teamName || '').localeCompare(String(b.teamName || ''), 'zh-CN')
        : new Date(b.createdAt || 0) - new Date(a.createdAt || 0),
    )
  })
  const filteredMatchFavorites = createMemo(() => {
    const keyword = favoriteKeyword().trim().toLowerCase()
    const rows = matchFavorites().filter((item) => {
      const textMatch = !keyword || `${item.homeTeamName || ''} ${item.awayTeamName || ''} ${item.leagueName || ''}`.toLowerCase().includes(keyword)
      const state = favoriteMatchState(item)
      return textMatch && (favoriteMatchFilter() === 'all' || state.key === favoriteMatchFilter())
    })
    return [...rows].sort((a, b) =>
      favoriteSort() === 'name'
        ? String(a.homeTeamName || '').localeCompare(String(b.homeTeamName || ''), 'zh-CN')
        : new Date(a.matchTime || a.createdAt || 0) - new Date(b.matchTime || b.createdAt || 0),
    )
  })
  const overviewFavorites = createMemo(() =>
    [
      ...favorites()
        .slice(0, 2)
        .map((item) => ({ key: `team-${item.id}`, initial: teamInitial(item.teamName), title: item.teamName || '未命名球队', subtitle: '关注球队', action: () => goTeam(item.teamId, item.teamName, item.leagueName, item.logo) })),
      ...matchFavorites()
        .slice(0, 2)
        .map((item) => ({ key: `match-${item.id}`, initial: 'VS', title: `${item.homeTeamName || '主队'} VS ${item.awayTeamName || '客队'}`, subtitle: '关注比赛', action: () => goPrediction(item) })),
    ].slice(0, 3),
  )
  const filteredHistory = createMemo(() =>
    history().filter((item) => {
      const correct = Number(item.isCorrect) === 1
      const wrong = Number(item.isCorrect) === 0
      const statusMatch =
        historyStatus() === 'all' ||
        (historyStatus() === 'correct' && correct) ||
        (historyStatus() === 'wrong' && wrong) ||
        (historyStatus() === 'pending' && !correct && !wrong)
      const keyword = historyKeyword().trim().toLowerCase()
      const textMatch = !keyword || `${item.homeTeamName || ''} ${item.awayTeamName || ''} ${item.leagueName || ''}`.toLowerCase().includes(keyword)
      const date = new Date(item.createdAt || 0).getTime()
      const rangeDays = historyDateRange() === '7d' ? 7 : historyDateRange() === '30d' ? 30 : null
      const dateMatch = !rangeDays || (Number.isFinite(date) && date >= Date.now() - rangeDays * 86400000)
      return statusMatch && textMatch && dateMatch && (historyOutcome() === 'all' || item.resultLabel === historyOutcome())
    }),
  )

  const normalizeHistoryItem = (item) => ({ ...item, homeWinProb: item.homeWinProb, drawProb: item.drawProb, awayWinProb: item.awayWinProb })
  const loadIdentity = async () => {
    const result = unwrap(await userApi.getCurrentUser())
    setProfileIdentity({
      userId: result?.userId || auth.userId(),
      username: result?.username || auth.username(),
      nickname: result?.nickname || result?.username || auth.username(),
      email: result?.email || '',
      emailVerified: Boolean(result?.emailVerified),
      avatarData: result?.avatarData || '',
      nicknameUpdatedAt: result?.nicknameUpdatedAt || null,
      role: result?.role || auth.role(),
      createdAt: result?.createdAt || null,
    })
    setAvatarLoadFailed(false)
    // 本地值直传：set 后立即读 store 是旧值（2.0 暂缓写入）。
    setProfileForm('nickname', result?.nickname || result?.username || auth.username())
  }
  const loadHistoryPage = async (reset = false) => {
    if (historyLoading() || (!reset && !historyHasMore())) return
    setHistoryLoading(true)
    setHistoryError('')
    try {
      const result = unwrap(await predictionApi.getHistoryPage(reset ? '' : historyCursor(), 20))
      const items = Array.isArray(result?.items) ? result.items.map(normalizeHistoryItem) : []
      setHistory(reset ? items : [...history(), ...items])
      setHistoryCursor(result?.nextCursor || '')
      setHistoryHasMore(Boolean(result?.hasMore))
    } catch (error) {
      setHistoryError(error?.message || '请稍后重试')
      if (reset) {
        try {
          const fallback = unwrap(await predictionApi.getHistory(50))
          setHistory(Array.isArray(fallback) ? fallback.map(normalizeHistoryItem) : [])
          setHistoryHasMore(false)
          setHistoryError('')
        } catch {
          setHistory([])
        }
      }
    } finally {
      setHistoryLoading(false)
    }
  }
  const loadPredictionStats = async () => {
    try {
      setPersonalStats(unwrap(await predictionApi.getStatistics()))
    } catch {
      setPersonalStats(null)
    }
  }
  const loadPublicStats = async () => {
    try {
      setPublicStats(unwrap(await predictionApi.getPerformance(7)))
    } catch {
      setPublicStats(null)
    }
  }
  const loadFavorites = async () => {
    setFavoritesLoading(true)
    setFavoritesError('')
    try {
      const result = unwrap(await favoriteApi.list())
      setFavorites(Array.isArray(result?.items) ? result.items : Array.isArray(result) ? result : [])
    } catch (error) {
      setFavoritesError(error?.message || '请稍后重试')
      setFavorites([])
    } finally {
      setFavoritesLoading(false)
    }
  }
  const loadMatchFavorites = async () => {
    setMatchFavoritesLoading(true)
    setMatchFavoritesError('')
    try {
      const result = unwrap(await favoriteApi.listMatches())
      setMatchFavorites(Array.isArray(result?.items) ? result.items : Array.isArray(result) ? result : [])
    } catch (error) {
      setMatchFavoritesError(error?.message || '请稍后重试')
      setMatchFavorites([])
    } finally {
      setMatchFavoritesLoading(false)
    }
  }
  const loadAccountProfile = async () => {
    try {
      const result = unwrap(await userApi.getPreferences())
      const raw = result?.preferences || result || '{}'
      const saved = typeof raw === 'string' ? JSON.parse(raw || '{}') : raw || {}
      setAccountProfile({ email: saved.email || '', phone: saved.phone || '', matchRemindersEnabled: Boolean(saved.matchRemindersEnabled) })
    } catch {
      /* preferences are optional */
    }
  }
  const loadNotifications = async () => {
    setNotificationLoading(true)
    try {
      const result = unwrap(await userApi.getNotifications(50))
      setNotifications(Array.isArray(result?.items) ? result.items : [])
      setNotificationUnread(Number(result?.unread || 0))
    } catch {
      setNotifications([])
      setNotificationUnread(0)
    } finally {
      setNotificationLoading(false)
    }
  }
  const loadSessions = async () => {
    setSessionsLoading(true)
    try {
      const result = unwrap(await userApi.getSessions(auth.refreshToken()))
      setSessions(Array.isArray(result?.items) ? result.items : [])
    } catch {
      setSessions([])
    } finally {
      setSessionsLoading(false)
    }
  }

  const goMatches = () => navigate('/matches')
  const goTeam = (teamId, teamName, leagueName, logo) => {
    const target = teamName || teamId
    if (!target) return
    const query = new URLSearchParams({ name: target, teamId: String(teamId || ''), league: leagueName || '', logo: logo || '', season: String(new Date().getFullYear()) })
    navigate(`/team/${encodeURIComponent(String(target))}/squad?${query.toString()}`)
  }
  const getPublicMatchId = (item) => item?.matchId || item?.fixture?.id || item?.fixtureId || item?.externalMatchId || (item?.teams || item?.fixture ? item?.id : null)
  const goPrediction = (item) => {
    const fixtureId = getPublicMatchId(item)
    if (!fixtureId) {
      toastWarning('这条记录缺少比赛标识，暂时无法打开')
      return
    }
    const query = new URLSearchParams({
      homeId: item?.homeTeamId || item?.home?.id || '',
      awayId: item?.awayTeamId || item?.away?.id || '',
      homeName: item?.homeTeamName || item?.home?.name || '主队',
      awayName: item?.awayTeamName || item?.away?.name || '客队',
      homeLogo: item?.homeTeamLogo || item?.home?.logo || '',
      awayLogo: item?.awayTeamLogo || item?.away?.logo || '',
      leagueName: item?.leagueName || item?.league?.name || '',
      leagueId: item?.leagueId || item?.league?.id || '',
      matchTime: item?.matchTime || item?.fixture?.date || '',
    })
    navigate(`/prediction/${fixtureId}?${query.toString()}`)
  }
  const switchTab = (tab) => {
    setActiveTab(tab)
    queueMicrotask(() => workspaceRef?.scrollIntoView({ behavior: 'smooth', block: 'start' }))
  }
  const moveProfileTab = (direction) => {
    const items = tabs()
    const currentIndex = Math.max(0, items.findIndex((item) => item.key === activeTab()))
    const nextIndex = direction === 'first' ? 0 : direction === 'last' ? items.length - 1 : (currentIndex + Number(direction) + items.length) % items.length
    const key = items[nextIndex]?.key
    if (!key) return
    switchTab(key)
    window.requestAnimationFrame(() => document.getElementById(`profile-tab-${key}`)?.focus())
  }
  const openProfileEditor = () => {
    setProfileForm('nickname', displayName())
    setProfileDialogVisible(true)
  }
  const openAvatarPicker = () => {
    if (!avatarSaving()) avatarInput?.click()
  }
  const onAvatarSelected = (event) => {
    const file = event.target.files?.[0]
    event.target.value = ''
    if (!file) return
    const allowed = ['image/png', 'image/jpeg', 'image/webp']
    if (!allowed.includes(file.type)) {
      toastWarning('仅支持 PNG、JPG、JPEG 或 WebP 图片')
      return
    }
    if (file.size > 512 * 1024) {
      toastWarning('头像不能超过 512KB')
      return
    }
    const reader = new FileReader()
    reader.onerror = () => toastError('头像读取失败，请重试')
    reader.onload = async () => {
      const avatarData = String(reader.result || '')
      setAvatarSaving(true)
      try {
        const result = unwrap(await userApi.updateAvatar(avatarData))
        if (result?.ok === false) throw new Error(result.message || '头像更新失败')
        setProfileIdentity('avatarData', avatarData)
        setAvatarLoadFailed(false)
        auth.hydrateProfile().catch(() => {})
        toastSuccess('头像已更新')
      } catch (error) {
        toastError(error?.message || '头像更新失败')
      } finally {
        setAvatarSaving(false)
      }
    }
    reader.readAsDataURL(file)
  }
  const markAllNotifications = async () => {
    await userApi.readAllNotifications().catch(() => {})
    setNotifications((prev) => prev.map((item) => ({ ...item, read_at: item.read_at || new Date().toISOString() })))
    setNotificationUnread(0)
  }
  const markNotification = async (item) => {
    if (!item.read_at) {
      await userApi.readNotification(item.id).catch(() => {})
      setNotifications((prev) => prev.map((row) => (row.id === item.id ? { ...row, read_at: new Date().toISOString() } : row)))
      setNotificationUnread((n) => Math.max(0, n - 1))
    }
  }
  const removeFav = async (fav) => {
    const ok = await askConfirm({ title: '取消关注', message: `确定取消关注“${fav.teamName || '这支球队'}”吗？`, confirmText: '取消关注', cancelText: '保留' })
    if (ok !== true) return
    try {
      await favoriteApi.remove(fav.teamId)
      toastSuccess('已取消关注')
      await loadFavorites()
    } catch (error) {
      toastError(error?.message || '取消关注失败')
    }
  }
  const removeMatchFav = async (fav) => {
    const ok = await askConfirm({ title: '取消关注', message: '确定取消关注这场比赛吗？', confirmText: '取消关注', cancelText: '保留' })
    if (ok !== true) return
    try {
      await favoriteApi.removeMatch(getPublicMatchId(fav))
      toastSuccess('已取消关注')
      await loadMatchFavorites()
    } catch (error) {
      toastError(error?.message || '取消关注失败')
    }
  }
  const saveProfile = async () => {
    const nickname = profileForm.nickname.trim()
    if (!nickname || nickname.length > 32) {
      toastWarning('昵称不能为空且不能超过32个字符')
      return
    }
    setProfileSaving(true)
    try {
      const result = unwrap(await userApi.updateProfile({ nickname }))
      if (result?.ok === false) throw new Error(result.message || '保存失败')
      // 本地值直传：set 后立即读 store 是旧值（2.0 暂缓写入）。
      const newNickname = result?.nickname || nickname
      setProfileIdentity({ nickname: newNickname, nicknameUpdatedAt: result?.nicknameUpdatedAt || profileIdentity.nicknameUpdatedAt })
      setProfileForm('nickname', newNickname)
      authStorage.set('football_user', JSON.stringify({ username: newNickname, userId: auth.userId(), role: auth.role() }))
      auth.hydrateProfile().catch(() => {})
      setProfileDialogVisible(false)
      toastSuccess(result?.message || '个人资料已更新')
    } catch (error) {
      toastError(error?.message || '保存失败')
    } finally {
      setProfileSaving(false)
    }
  }
  const saveAccountProfile = async () => {
    setProfileSaving(true)
    try {
      await userApi.updatePreferences({ ...accountProfile })
      toastSuccess('提醒设置已保存')
    } catch (error) {
      toastError(error?.message || '保存失败')
    } finally {
      setProfileSaving(false)
    }
  }
  const revokeAllSessions = async () => {
    const ok = await askConfirm({ title: '退出其他设备', message: '这会让其他浏览器和设备重新登录，确定继续吗？', confirmText: '退出其他设备', cancelText: '取消' })
    if (ok !== true) return
    try {
      await userApi.revokeAllSessions(auth.refreshToken())
      toastSuccess('已退出其他设备')
      await loadSessions()
    } catch (error) {
      toastError(error?.message || '操作失败')
    }
  }
  const changePassword = async () => {
    if (passwordForm.newPassword.length < 8 || passwordForm.newPassword !== passwordForm.confirmPassword) {
      toastWarning('请确认新密码至少 8 位且两次输入一致')
      return
    }
    setPasswordSaving(true)
    try {
      const result = unwrap(await userApi.changePassword(passwordForm.currentPassword, passwordForm.newPassword))
      if (result?.ok === false) throw new Error(result.message || '密码更新失败')
      toastSuccess('密码已更新，请重新登录')
      auth.logout()
      navigate('/matches', { replace: true })
    } catch (error) {
      toastError(error?.message || '密码更新失败')
    } finally {
      setPasswordSaving(false)
    }
  }
  const disableAccount = async () => {
    const ok = await askConfirm({ title: '确认注销', message: '注销后账号将被停用，收藏和预测历史也会停止展示，确定继续吗？', confirmText: '确认注销', cancelText: '取消' })
    if (ok !== true) return
    try {
      const result = unwrap(await userApi.disableAccount())
      if (result?.ok === false) throw new Error(result.message || '注销失败')
      toastSuccess('账号已注销')
      auth.logout()
      navigate('/matches', { replace: true })
    } catch (error) {
      toastError(error?.message || '注销失败')
    }
  }
  const onHistoryKeydown = (event) => {
    if ((event.key !== ' ' && event.code !== 'Space') || !event.target?.closest?.('.history-item')) return
    event.preventDefault()
    event.target.closest('.history-item')?.click()
  }

  onSettled(async () => {
    document.addEventListener('keydown', onHistoryKeydown)
    if (!auth.token()) {
      navigate('/matches', { replace: true })
      return
    }
    const requestedTab = String(searchParams.tab || '')
    if (['overview', 'favorites', 'history', 'security'].includes(requestedTab)) setActiveTab(requestedTab)
    await Promise.allSettled([loadIdentity(), loadHistoryPage(true), loadPredictionStats(), loadPublicStats(), loadFavorites(), loadMatchFavorites(), loadAccountProfile(), loadNotifications(), loadSessions()])
    setStatsLoadedAt(new Date())
    setProfileLoading(false)
  })
  onCleanup(() => document.removeEventListener('keydown', onHistoryKeydown))

  return (
    <div class="profile-page">
      <main id="app-main" class="profile-main" tabindex="-1">
        <Show when={profileLoading()} fallback={
          <>
            <section class="identity-card ff-panel" aria-labelledby="identity-title">
              <div class="identity-main">
                <div class="avatar-editor">
                  <Show when={avatarSrc()} fallback={<span class="identity-avatar">{avatarInitial()}</span>}>
                    <img src={avatarSrc()} alt="" class="identity-avatar" onError={() => setAvatarLoadFailed(true)} />
                  </Show>
                  <button type="button" class="avatar-edit-button" aria-label="修改头像" title="修改头像" disabled={avatarSaving()} onClick={openAvatarPicker}>
                    📷
                  </button>
                  <input ref={(el) => { avatarInput = el }} class="avatar-input" type="file" accept="image/png,image/jpeg,image/webp" onChange={onAvatarSelected} />
                </div>
                <div class="identity-copy">
                  <span class="section-kicker">账户资料</span>
                  <h2 id="identity-title">{displayName()}</h2>
                  <p class="identity-email">{profileIdentity.email || '历史账号，暂未绑定邮箱'}</p>
                  <div class="identity-meta">
                    <span class={`ff-tag ${emailVerified() ? 'is-success' : 'is-warning'}`}>{emailVerified() ? '邮箱已验证' : '邮箱未验证'}</span>
                    <Show when={profileIdentity.createdAt}>
                      <span>加入于 {formatDateOnly(profileIdentity.createdAt)}</span>
                    </Show>
                    <Show when={profileIdentity.role && profileIdentity.role !== 'USER'}>
                      <span>管理员入口已启用</span>
                    </Show>
                  </div>
                </div>
              </div>
              <div class="identity-actions">
                <button type="button" class="ff-btn ff-btn-plain" onClick={goMatches}>
                  浏览比赛
                </button>
                <button type="button" class="ff-btn ff-btn-ghost" onClick={openProfileEditor}>
                  编辑资料
                </button>
                <button type="button" class="ff-btn ff-btn-ghost" onClick={() => switchTab('favorites')}>
                  查看收藏
                </button>
                <button type="button" class="ff-btn ff-btn-ghost" onClick={() => switchTab('security')}>
                  账户安全
                </button>
              </div>
            </section>

            <Show when={publicStats()}>
              <section class="benchmark-strip model-performance ff-panel" aria-label="模型表现">
                <div>
                  <span class="section-kicker">模型表现</span>
                  <strong>近 {publicStats().windowDays || 7} 日模型准确率</strong>
                  <span>基于全站已验证比赛，仅用于评估模型，不代表个人能力。{statsUpdatedText()}</span>
                  <div class="benchmark-meta">
                    <span>已验证 {publicStats().evaluated ?? 0} 场</span>
                    <Show when={publicStats().baselineAccuracy != null}>
                      <span>基线 {publicStats().baselineAccuracy}%</span>
                    </Show>
                    <Show when={publicStats().balancedAccuracy != null}>
                      <span>平衡准确率 {publicStats().balancedAccuracy}%</span>
                    </Show>
                  </div>
                </div>
                <div class="benchmark-value">
                  <strong>{publicAccuracy()}</strong>
                  <small>全站参考</small>
                </div>
              </section>
            </Show>

            <section ref={(el) => { workspaceRef = el }} class="workspace-panel ff-panel" aria-label="个人中心工作区">
              <nav class="workspace-tabs" aria-label="个人中心导航">
                <For each={tabs()}>
                  {(tab) => (
                    <button
                      type="button"
                      id={`profile-tab-${tab.key}`}
                      role="tab"
                      aria-selected={activeTab() === tab.key}
                      aria-controls={`profile-panel-${tab.key}`}
                      tabindex={activeTab() === tab.key ? 0 : -1}
                      class={activeTab() === tab.key ? 'active' : ''}
                      onClick={() => switchTab(tab.key)}
                      onKeyDown={(e) => {
                        if (e.key === 'ArrowLeft') { e.preventDefault(); moveProfileTab(-1) }
                        if (e.key === 'ArrowRight') { e.preventDefault(); moveProfileTab(1) }
                        if (e.key === 'Home') { e.preventDefault(); moveProfileTab('first') }
                        if (e.key === 'End') { e.preventDefault(); moveProfileTab('last') }
                      }}
                    >
                      <span>{tab.label}</span>
                      <Show when={tab.count !== undefined}>
                        <em>{tab.count}</em>
                      </Show>
                    </button>
                  )}
                </For>
              </nav>

              <Show when={activeTab() === 'overview'}>
                <div id="profile-panel-overview" class="workspace-content" role="tabpanel">
                  <div class="overview-grid">
                    <section class="overview-card">
                      <div class="content-heading">
                        <div>
                          <span class="section-kicker">关注内容</span>
                          <h2>关注概览</h2>
                        </div>
                        <button type="button" class="ff-btn ff-btn-ghost ff-btn-sm" onClick={() => switchTab('favorites')}>
                          管理收藏
                        </button>
                      </div>
                      <div class="overview-summary">
                        <strong>{favorites().length + matchFavorites().length}</strong>
                        <span>项关注内容</span>
                        <small>
                          球队 {favorites().length} · 比赛 {matchFavorites().length}
                        </small>
                      </div>
                      <Show when={favorites().length || matchFavorites().length} fallback={<PageState title="还没有关注内容" description="从比赛页收藏球队或比赛，建立你的赛前清单。" actionText="去发现比赛" onAction={goMatches} />}>
                        <div class="mini-follow-list">
                          <For each={overviewFavorites()}>
                            {(item) => (
                              <div class="mini-follow-item">
                                <span class="mini-icon">{item.initial}</span>
                                <div>
                                  <strong>{item.title}</strong>
                                  <small>{item.subtitle}</small>
                                </div>
                                <button type="button" class="ff-btn ff-btn-ghost ff-btn-sm" onClick={item.action}>
                                  查看
                                </button>
                              </div>
                            )}
                          </For>
                        </div>
                      </Show>
                    </section>
                    <section class="overview-card">
                      <div class="content-heading">
                        <div>
                          <span class="section-kicker">最近记录</span>
                          <h2>最近预测</h2>
                        </div>
                        <button type="button" class="ff-btn ff-btn-ghost ff-btn-sm" onClick={() => switchTab('history')}>
                          查看全部
                        </button>
                      </div>
                      <Show when={history().length} fallback={<PageState title="暂无预测记录" description="选择一场比赛，查看模型分析和概率分布。" actionText="去看比赛" onAction={goMatches} />}>
                        <div class="recent-predictions">
                          <For each={history().slice(0, 3)}>
                            {(item) => (
                              <div class="recent-prediction" onClick={() => goPrediction(item)}>
                                <div>
                                  <strong>
                                    {item.homeTeamName || '球队A'} <b>VS</b> {item.awayTeamName || '球队B'}
                                  </strong>
                                  <small>
                                    {item.leagueName || '未分类联赛'} · {formatDate(item.createdAt)}
                                  </small>
                                </div>
                                <span class={`ff-tag is-${resultTagType(item.resultLabel)}`}>{resultLabel(item.resultLabel)}</span>
                              </div>
                            )}
                          </For>
                        </div>
                      </Show>
                    </section>
                  </div>
                </div>
              </Show>

              <Show when={activeTab() === 'favorites'}>
                <div id="profile-panel-favorites" class="workspace-content" role="tabpanel">
                  <div class="content-heading workspace-title-row">
                    <div>
                      <span class="section-kicker">关注内容</span>
                      <h2>我的收藏</h2>
                      <p>关注的内容会优先出现在比赛页，方便你持续跟踪。</p>
                    </div>
                    <button type="button" class="ff-btn ff-btn-plain" onClick={goMatches}>
                      发现更多
                    </button>
                  </div>
                  <div class="favorite-tabs" role="tablist" aria-label="收藏分类">
                    <button type="button" role="tab" aria-selected={favoriteTab() === 'teams'} class={favoriteTab() === 'teams' ? 'active' : ''} onClick={() => setFavoriteTab('teams')}>
                      球队 {favorites().length}
                    </button>
                    <button type="button" role="tab" aria-selected={favoriteTab() === 'matches'} class={favoriteTab() === 'matches' ? 'active' : ''} onClick={() => setFavoriteTab('matches')}>
                      比赛 {matchFavorites().length}
                    </button>
                  </div>
                  <div class="library-toolbar">
                    <input class="ff-input" type="search" placeholder="搜索球队、联赛或比赛" aria-label="搜索收藏" value={favoriteKeyword()} onInput={(e) => setFavoriteKeyword(e.currentTarget.value)} />
                    <select class="ff-select" aria-label="收藏排序" value={favoriteSort()} onChange={(e) => setFavoriteSort(e.currentTarget.value)}>
                      <option value="recent">最近收藏</option>
                      <option value="name">按名称</option>
                    </select>
                    <Show when={favoriteTab() === 'matches'}>
                      <select class="ff-select" aria-label="比赛状态筛选" value={favoriteMatchFilter()} onChange={(e) => setFavoriteMatchFilter(e.currentTarget.value)}>
                        <option value="all">全部状态</option>
                        <option value="upcoming">未开始</option>
                        <option value="finished">已结束</option>
                        <option value="unknown">待同步</option>
                      </select>
                    </Show>
                  </div>
                  <Show when={favoriteTab() === 'teams'}>
                    <Show when={favoritesLoading()} fallback={
                      <Show when={favoritesError()} fallback={
                        <Show when={favorites().length} fallback={<PageState title="暂无收藏球队" description="从比赛或球队阵容页关注一支球队。" actionText="浏览球队" onAction={goMatches} />}>
                          <Show when={filteredFavorites().length} fallback={<PageState title="没有符合条件的球队" description="换一个关键词或调整排序后再试。" actionText="清除筛选" onAction={() => { setFavoriteKeyword(''); setFavoriteSort('recent') }} />}>
                            <div class="library-list">
                              <For each={filteredFavorites()}>
                                {(fav) => (
                                  <article class="library-item">
                                    <div class="library-avatar">{teamInitial(fav.teamName)}</div>
                                    <div class="library-copy">
                                      <strong>{fav.teamName || '未命名球队'}</strong>
                                      <small>
                                        {fav.leagueName || '联赛待同步'} · 收藏于 {formatDate(fav.createdAt)}
                                      </small>
                                    </div>
                                    <div class="library-actions">
                                      <button type="button" class="ff-btn ff-btn-plain ff-btn-sm" onClick={() => goTeam(fav.teamId, fav.teamName, fav.leagueName, fav.logo)}>
                                        查看球队
                                      </button>
                                      <button type="button" class="ff-btn ff-btn-danger-ghost ff-btn-sm" onClick={() => removeFav(fav)}>
                                        取消关注
                                      </button>
                                    </div>
                                  </article>
                                )}
                              </For>
                            </div>
                          </Show>
                        </Show>
                      }>
                        <PageState type="error" title="收藏球队加载失败" description={favoritesError()} actionText="重试" onAction={loadFavorites} />
                      </Show>
                    }>
                      <PageState type="loading" title="正在加载收藏球队…" />
                    </Show>
                  </Show>
                  <Show when={favoriteTab() === 'matches'}>
                    <Show when={matchFavoritesLoading()} fallback={
                      <Show when={matchFavoritesError()} fallback={
                        <Show when={matchFavorites().length} fallback={<PageState title="暂无收藏比赛" description="在比赛卡片中收藏一场你想持续关注的比赛。" actionText="浏览赛程" onAction={goMatches} />}>
                          <Show when={filteredMatchFavorites().length} fallback={<PageState title="没有符合条件的比赛" description="换一个关键词、状态筛选，或去比赛页发现新赛程。" actionText="清除筛选" onAction={() => { setFavoriteKeyword(''); setFavoriteMatchFilter('all'); setFavoriteSort('recent') }} />}>
                            <div class="library-list">
                              <For each={filteredMatchFavorites()}>
                                {(fav) => (
                                  <article class="library-item match-library-item">
                                    <div class="library-avatar match-avatar">VS</div>
                                    <div class="library-copy">
                                      <strong>
                                        {fav.homeTeamName || '未知主队'} <b>VS</b> {fav.awayTeamName || '未知客队'}
                                      </strong>
                                      <small>
                                        {fav.leagueName || '联赛待同步'} · {formatMatchTime(fav.matchTime)}
                                      </small>
                                    </div>
                                    <div class="library-status">
                                      <span class={`ff-tag is-${favoriteMatchState(fav).type}`}>{favoriteMatchState(fav).label}</span>
                                    </div>
                                    <div class="library-actions">
                                      <button type="button" class="ff-btn ff-btn-plain ff-btn-sm" onClick={() => goPrediction(fav)}>
                                        查看分析
                                      </button>
                                      <button type="button" class="ff-btn ff-btn-danger-ghost ff-btn-sm" onClick={() => removeMatchFav(fav)}>
                                        取消关注
                                      </button>
                                    </div>
                                  </article>
                                )}
                              </For>
                            </div>
                          </Show>
                        </Show>
                      }>
                        <PageState type="error" title="收藏比赛加载失败" description={matchFavoritesError()} actionText="重试" onAction={loadMatchFavorites} />
                      </Show>
                    }>
                      <PageState type="loading" title="正在加载收藏比赛…" />
                    </Show>
                  </Show>
                </div>
              </Show>

              <Show when={activeTab() === 'history'}>
                <div id="profile-panel-history" class="workspace-content" role="tabpanel">
                  <div class="content-heading workspace-title-row">
                    <div>
                      <span class="section-kicker">预测记录</span>
                      <h2>预测记录</h2>
                      <p>查看模型结论、概率分布和最终核验结果。</p>
                    </div>
                    <div class="history-total">
                      共 {personalStats()?.total ?? history().length} 场 · 待验证 {personalStats()?.pending ?? '—'} 场
                    </div>
                  </div>
                  <div class="history-toolbar">
                    <input class="ff-input history-search" type="search" placeholder="搜索球队或联赛" aria-label="搜索预测记录" value={historyKeyword()} onInput={(e) => setHistoryKeyword(e.currentTarget.value)} />
                    <div class="history-status-seg" role="group" aria-label="预测核验状态">
                      <For each={[['all', '全部'], ['correct', '命中'], ['wrong', '未命中'], ['pending', '待验证']]}>
                        {([value, label]) => (
                          <button type="button" class={historyStatus() === value ? 'is-active' : ''} aria-pressed={historyStatus() === value} onClick={() => setHistoryStatus(value)}>
                            {label}
                          </button>
                        )}
                      </For>
                    </div>
                    <select class="ff-select" aria-label="预测结果筛选" value={historyOutcome()} onChange={(e) => setHistoryOutcome(e.currentTarget.value)}>
                      <option value="all">全部结果</option>
                      <option value="HOME_WIN">主胜</option>
                      <option value="DRAW">平局</option>
                      <option value="AWAY_WIN">客胜</option>
                    </select>
                    <select class="ff-select" aria-label="预测时间范围" value={historyDateRange()} onChange={(e) => setHistoryDateRange(e.currentTarget.value)}>
                      <option value="all">全部时间</option>
                      <option value="7d">近7天</option>
                      <option value="30d">近30天</option>
                    </select>
                  </div>
                  <Show when={historyLoading() && !history().length} fallback={
                    <Show when={historyError() && !history().length} fallback={
                      <Show when={filteredHistory().length} fallback={<PageState title="没有符合条件的记录" description="调整筛选条件，或先去比赛页完成一次预测。" actionText="去看比赛" onAction={goMatches} />}>
                        <div class="history-list">
                          <For each={filteredHistory()}>
                            {(item) => (
                              <article class="history-item" tabindex="0" role="button" onClick={() => goPrediction(item)} onKeyDown={(e) => { if (e.key === 'Enter') goPrediction(item) }}>
                                <div class="history-main">
                                  <div class="history-date">{formatDate(item.createdAt)}</div>
                                  <strong>
                                    {item.homeTeamName || '球队A'} <b>VS</b> {item.awayTeamName || '球队B'}
                                  </strong>
                                  <small>{item.leagueName || '未分类联赛'}</small>
                                </div>
                                <div class="history-result">
                                  <span class={`ff-tag is-${resultTagType(item.resultLabel)}`}>{resultLabel(item.resultLabel)}</span>
                                  <span class={`verification-status ${verificationClass(item)}`}>{verificationLabel(item)}</span>
                                </div>
                                <div class="history-probability">
                                  <div class="prob-stack" title={`主 ${pct(item.homeWinProb)} · 平 ${pct(item.drawProb)} · 客 ${pct(item.awayWinProb)}`}>
                                    <span class="seg seg-home" style={{ width: pct(item.homeWinProb) }} />
                                    <span class="seg seg-draw" style={{ width: pct(item.drawProb) }} />
                                    <span class="seg seg-away" style={{ width: pct(item.awayWinProb) }} />
                                  </div>
                                  <div class="prob-nums">
                                    <span class="c-home">主 {pct(item.homeWinProb)}</span>
                                    <span class="c-draw">平 {pct(item.drawProb)}</span>
                                    <span class="c-away">客 {pct(item.awayWinProb)}</span>
                                  </div>
                                </div>
                                <button type="button" class="ff-btn ff-btn-ghost ff-btn-sm history-view" onClick={(e) => { e.stopPropagation(); goPrediction(item) }}>
                                  查看
                                </button>
                              </article>
                            )}
                          </For>
                        </div>
                        <Show when={history().length && !historyError()}>
                          <div class="history-footer">
                            <Show when={historyHasMore()} fallback={<span>已显示全部 {history().length} 条记录</span>}>
                              <button type="button" class="ff-btn ff-btn-ghost" disabled={historyLoading()} onClick={() => loadHistoryPage(false)}>
                                加载更多
                              </button>
                            </Show>
                          </div>
                        </Show>
                      </Show>
                    }>
                      <PageState type="error" title="预测记录加载失败" description={historyError()} actionText="重试" onAction={() => loadHistoryPage(true)} />
                    </Show>
                  }>
                    <PageState type="loading" title="正在加载预测记录…" />
                  </Show>
                </div>
              </Show>

              <Show when={activeTab() === 'security'}>
                <div id="profile-panel-security" class="workspace-content security-content" role="tabpanel">
                  <div class="content-heading workspace-title-row">
                    <div>
                      <span class="section-kicker">账户与安全</span>
                      <h2>账户安全</h2>
                      <p>管理登录账号、提醒偏好、通知记录和设备会话。</p>
                    </div>
                  </div>
                  <div class="security-grid">
                    <section class="security-card">
                      <div class="security-card-heading">
                        <h3>登录账号</h3>
                        <button type="button" class="ff-btn ff-btn-ghost ff-btn-sm" onClick={openProfileEditor}>
                          编辑资料
                        </button>
                      </div>
                      <div class="account-row">
                        <span>邮箱</span>
                        <strong>{profileIdentity.email || '历史账号未绑定邮箱'}</strong>
                        <Show when={profileIdentity.email}>
                          <span class={`ff-tag ${emailVerified() ? 'is-success' : 'is-warning'}`}>{emailVerified() ? '已验证' : '未验证'}</span>
                        </Show>
                      </div>
                      <div class="account-row">
                        <span>昵称</span>
                        <strong>{displayName()}</strong>
                      </div>
                      <Show when={!profileIdentity.email}>
                        <div class="ff-alert is-info">
                          <span>这是历史账号，邮箱注册账号可使用找回密码功能。</span>
                        </div>
                      </Show>
                    </section>
                    <section class="security-card">
                      <h3>提醒偏好</h3>
                      <p class="security-help">比赛页的开赛提醒使用浏览器站内通知；邮箱和手机号目前只保存为偏好，后台提醒任务启用后才会发送。</p>
                      <div class="ff-alert is-info">
                        <span>当前状态：站内提醒可用，邮件/短信提醒未启用</span>
                      </div>
                      <form
                        class="security-form"
                        onSubmit={(e) => {
                          e.preventDefault()
                          saveAccountProfile()
                        }}
                      >
                        <div class="ff-form-item">
                          <label for="profile-pref-email">提醒邮箱</label>
                          <input id="profile-pref-email" class="ff-input" type="email" placeholder="例如 user@example.com" value={accountProfile.email} onInput={(e) => setAccountProfile('email', e.currentTarget.value)} />
                        </div>
                        <div class="ff-form-item">
                          <label for="profile-pref-phone">提醒手机号</label>
                          <input id="profile-pref-phone" class="ff-input" placeholder="例如 13800000000" value={accountProfile.phone} onInput={(e) => setAccountProfile('phone', e.currentTarget.value)} />
                        </div>
                        <label class="ff-checkbox">
                          <input type="checkbox" checked={accountProfile.matchRemindersEnabled} onChange={(e) => setAccountProfile('matchRemindersEnabled', e.currentTarget.checked)} />
                          接收关注比赛提醒
                        </label>
                        <button type="submit" class="ff-btn ff-btn-ghost form-submit" disabled={profileSaving()}>
                          保存提醒偏好
                        </button>
                      </form>
                    </section>
                    <section class="security-card notification-card">
                      <div class="security-card-heading">
                        <h3>
                          通知记录
                          <Show when={notificationUnread()}>
                            <span class="ff-tag is-warning inline-badge">{notificationUnread()}</span>
                          </Show>
                        </h3>
                        <Show when={notificationUnread()}>
                          <button type="button" class="ff-btn ff-btn-ghost ff-btn-sm" onClick={markAllNotifications}>
                            全部已读
                          </button>
                        </Show>
                      </div>
                      <Show when={notificationLoading()} fallback={
                        <Show when={notifications().length} fallback={<PageState title="暂无通知" description="比赛提醒、数据同步和账号安全通知会显示在这里。" />}>
                          <div class="profile-notification-list">
                            <For each={notifications().slice(0, 6)}>
                              {(item) => (
                                <button type="button" class={`profile-notification-item${!item.read_at ? ' is-unread' : ''}`} onClick={() => markNotification(item)}>
                                  <span class="notification-dot" />
                                  <span>
                                    <strong>{item.title}</strong>
                                    <small>
                                      {item.body || '暂无详细内容'} · {notificationTime(item.created_at)}
                                    </small>
                                  </span>
                                </button>
                              )}
                            </For>
                          </div>
                        </Show>
                      }>
                        <PageState type="loading" title="正在加载通知…" />
                      </Show>
                    </section>
                    <section class="security-card session-card">
                      <div class="security-card-heading">
                        <h3>登录设备</h3>
                        <span class="security-count">{sessions().length} 个会话</span>
                      </div>
                      <Show when={sessionsLoading()} fallback={
                        <Show when={sessions().length} fallback={<PageState title="暂无会话信息" description="当前设备会话将在刷新后显示。" />}>
                          <div class="session-list">
                            <For each={sessions()}>
                              {(session, index) => (
                                <div class="session-item">
                                  <div>
                                    <strong>{session.label}</strong>
                                    <small>刷新令牌剩余 {formatSessionExpiry(session.expiresInSeconds)}</small>
                                  </div>
                                  <Show when={session.current}>
                                    <span class="ff-tag is-success">当前</span>
                                  </Show>
                                </div>
                              )}
                            </For>
                          </div>
                        </Show>
                      }>
                        <PageState type="loading" title="正在读取登录设备…" />
                      </Show>
                    </section>
                    <section class="security-card">
                      <h3>修改密码</h3>
                      <form
                        class="security-form"
                        onSubmit={(e) => {
                          e.preventDefault()
                          changePassword()
                        }}
                      >
                        <div class="ff-form-item">
                          <label for="profile-pw-current">当前密码</label>
                          <input id="profile-pw-current" class="ff-input" type="password" value={passwordForm.currentPassword} onInput={(e) => setPasswordForm('currentPassword', e.currentTarget.value)} />
                        </div>
                        <div class="ff-form-item">
                          <label for="profile-pw-new">新密码</label>
                          <input id="profile-pw-new" class="ff-input" type="password" placeholder="至少 8 位，建议包含字母和数字" value={passwordForm.newPassword} onInput={(e) => setPasswordForm('newPassword', e.currentTarget.value)} />
                        </div>
                        <div class="ff-form-item">
                          <label for="profile-pw-confirm">确认新密码</label>
                          <input id="profile-pw-confirm" class="ff-input" type="password" value={passwordForm.confirmPassword} onInput={(e) => setPasswordForm('confirmPassword', e.currentTarget.value)} />
                        </div>
                        <button type="submit" class="ff-btn ff-btn-primary form-submit" disabled={passwordSaving()}>
                          更新密码
                        </button>
                      </form>
                    </section>
                    <section class="security-card danger-card">
                      <h3>危险操作</h3>
                      <p class="security-help">退出其他设备会让其他浏览器重新登录。注销账号会立即停用当前账号且不可在此处恢复。</p>
                      <div class="danger-actions">
                        <button type="button" class="ff-btn ff-btn-ghost" onClick={revokeAllSessions}>
                          退出其他设备
                        </button>
                        <button type="button" class="ff-btn ff-btn-danger-ghost" onClick={disableAccount}>
                          注销账号
                        </button>
                      </div>
                    </section>
                  </div>
                </div>
              </Show>
            </section>
          </>
        }>
          <div class="profile-loading ff-panel">
            <PageState type="loading" title="正在加载个人中心…" />
          </div>
        </Show>

        <Modal open={profileDialogVisible()} title="编辑个人资料" onClose={() => setProfileDialogVisible(false)}>
          <form
            onSubmit={(e) => {
              e.preventDefault()
              saveProfile()
            }}
          >
            <div class="ff-form-item">
              <label for="profile-edit-nickname">昵称</label>
              <input
                id="profile-edit-nickname"
                class="ff-input"
                maxlength="32"
                placeholder="请输入展示昵称"
                value={profileForm.nickname}
                onInput={(e) => setProfileForm('nickname', e.currentTarget.value)}
                onKeyDown={(e) => {
                  if (e.key === 'Enter') {
                    e.preventDefault()
                    saveProfile()
                  }
                }}
              />
              <small class="form-hint">每月最多修改一次；昵称不能与他人重复，也不能包含违规词。</small>
            </div>
            <div class="ff-form-item">
              <label>登录邮箱</label>
              <input class="ff-input" value={profileIdentity.email || '历史账号未绑定邮箱'} disabled />
              <small class="form-hint">登录邮箱是账号标识，如需修改需要重新完成邮箱验证。</small>
            </div>
            <div class="ff-modal-actions">
              <button type="button" class="ff-btn ff-btn-ghost" onClick={() => setProfileDialogVisible(false)}>
                取消
              </button>
              <button type="submit" class="ff-btn ff-btn-primary" disabled={profileSaving()}>
                保存
              </button>
            </div>
          </form>
        </Modal>
        <ConfirmDialog />
      </main>
    </div>
  )
}
