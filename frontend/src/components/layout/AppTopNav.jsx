/**
 * 顶栏（旧版 components/layout/AppTopNav.vue 的同款行为，视觉按 tokens）。
 * 64px 白底 + 毛玻璃、品牌绿；移动端汉堡菜单；搜索弹窗；登录后通知中心；
 * 账号下拉（登录/个人信息/隐私说明/退出登录）；明暗切换按钮。
 *
 * 注意：脚手架里顶栏由 App.jsx 全局挂载（每页不再各自嵌 AppTopNav，
 * 与旧版每页自带顶栏不同；样板页组迁移页面时只写页面内容）。
 * 搜索当前为“后端 /search + 本地空态”：无后端时显示“没有找到”空态，
 * 行为与旧版一致，待契约组确认搜索接口后再接线。
 */
import { createEffect, createSignal, For, Show, onCleanup } from 'solid-js'
import { useLocation, useNavigate } from '@solidjs/router'
import auth from '../../stores/auth'
import ChangelogButton from '../matches/ChangelogButton'
import { theme, toggleTheme } from '../../stores/theme'
import { t } from '../../i18n'
import { userApi, searchApi, crawlerApi } from '../../api'
import { expandSearchQueries, findLeagueAlias } from '../../utils/teamNames'

const NAV_ITEMS = [
  { path: '/matches', labelKey: 'nav.matches' },
  { path: '/competitions', labelKey: 'nav.competitions' },
  { path: '/agent', labelKey: 'nav.agent', soft: true },
]

export default function AppTopNav(props) {
  const location = useLocation()
  const navigate = useNavigate()
  const [mobileMenuOpen, setMobileMenuOpen] = createSignal(false)
  const [accountOpen, setAccountOpen] = createSignal(false)
  const [searchVisible, setSearchVisible] = createSignal(false)
  const [searchKeyword, setSearchKeyword] = createSignal('')
  const [searchLoading, setSearchLoading] = createSignal(false)
  const [searchResults, setSearchResults] = createSignal({ teams: [], leagues: [], matches: [], articles: [] })
  const [notificationVisible, setNotificationVisible] = createSignal(false)
  const [notificationLoading, setNotificationLoading] = createSignal(false)
  const [notifications, setNotifications] = createSignal([])
  const [notificationUnread, setNotificationUnread] = createSignal(0)

  const activePath = () => props.activePath || location.pathname
  const navItems = () => {
    const items = [...NAV_ITEMS]
    if (auth.isAdmin()) items.push({ path: '/admin', labelKey: 'nav.admin' })
    return items
  }
  const hasSearchHits = () => {
    const r = searchResults()
    return r.teams.length + r.leagues.length + r.matches.length + r.articles.length > 0
  }

  // Solid 2.0 的 createEffect 是双参：compute 追踪依赖，effect 不追踪。
  createEffect(
    () => location.pathname,
    () => {
      // 路由变化时收起移动端菜单与账号下拉。
      setMobileMenuOpen(false)
      setAccountOpen(false)
    },
  )

  // 全局搜索（契约 §2.6）：searchApi.search + searchTeams + searchMatches
  // 三路并行，任一失败 → null 后合并；联赛别名命中时把该联赛置顶；
  // 无结果时走本地别名回退（findLeagueAlias）。
  const runSearch = async (keyword) => {
    const q = String(keyword || '').trim()
    if (!q) {
      setSearchResults({ teams: [], leagues: [], matches: [], articles: [] })
      setSearchLoading(false)
      return
    }
    setSearchLoading(true)
    try {
      const queries = expandSearchQueries(q).slice(0, 4)
      const [general, teams, matches] = await Promise.all([
        searchApi.search(q, 8).catch(() => null),
        crawlerApi.searchTeams(queries[0] || q).catch(() => null),
        crawlerApi.searchMatches(queries[0] || q).catch(() => null),
      ])
      const asList = (value) => (Array.isArray(value) ? value : value?.items || value?.results || [])
      const generalList = asList(general)
      const leagues = generalList.filter((i) => i?.kind === 'league')
      const leagueAlias = findLeagueAlias(q)
      if (leagueAlias && !leagues.some((l) => l?.name === leagueAlias.name)) {
        leagues.unshift({ name: leagueAlias.name, kind: 'league' })
      }
      const merged = {
        teams: asList(teams),
        leagues,
        matches: asList(matches),
        articles: generalList.filter((i) => i?.kind === 'article'),
      }
      if (!merged.teams.length && !merged.leagues.length && !merged.matches.length && !merged.articles.length && leagueAlias) {
        merged.leagues = [{ name: leagueAlias.name, kind: 'league' }]
      }
      setSearchResults(merged)
    } catch {
      setSearchResults({ teams: [], leagues: [], matches: [], articles: [] })
    } finally {
      setSearchLoading(false)
    }
  }
  let searchTimer = null
  const onSearchInput = (value) => {
    setSearchKeyword(value)
    window.clearTimeout(searchTimer)
    searchTimer = window.setTimeout(() => runSearch(value), 300)
  }
  onCleanup(() => window.clearTimeout(searchTimer))

  const loadNotifications = async () => {
    setNotificationLoading(true)
    try {
      const result = await userApi.getNotifications(20, false, { notifyOnError: false })
      const items = Array.isArray(result) ? result : result?.items || result?.notifications || []
      setNotifications(items)
      setNotificationUnread(Number(result?.unread || 0))
    } catch {
      setNotifications([])
      setNotificationUnread(0)
    } finally {
      setNotificationLoading(false)
    }
  }
  const openNotifications = async () => {
    setNotificationVisible(true)
    await loadNotifications()
  }
  const markAllNotifications = async () => {
    try {
      await userApi.readAllNotifications({ notifyOnError: false })
    } catch { /* ignore */ }
    setNotifications((list) => list.map((item) => ({ ...item, read_at: item.read_at || new Date().toISOString() })))
    setNotificationUnread(0)
  }

  const handleBrandClick = () => navigate('/matches')
  const goBrowse = (path) => {
    setSearchVisible(false)
    setNotificationVisible(false)
    navigate(path)
  }
  // TeamSquad 读 query（name/teamId/logo/league/season），params.teamId 只是球队名回退值（契约 §1.4）。
  const openTeam = (item) => {
    const name = item?.name || item?.teamName || ''
    const id = item?.id || item?.teamId || name
    const query = new URLSearchParams({ name })
    if (item?.league) query.set('league', item.league)
    if (item?.logo) query.set('logo', item.logo)
    goBrowse(`/team/${encodeURIComponent(id)}/squad?${query.toString()}`)
  }
  const openMatch = (item) => goBrowse(`/prediction/${item.fixtureId || item.matchId || item.id}`)

  return (
    <>
      <header class="ff-topnav">
        <a class="ff-skip-link" href="#app-main">
          {t('nav.skipToMain')}
        </a>
        <button type="button" class="ff-brand" aria-label={t('nav.backToMatches')} onClick={handleBrandClick}>
          <span class="ff-brand-mark" aria-hidden="true">
            足
          </span>
          <span>
            <span class="ff-brand-title">{props.title || 'ChenFootball'}</span>
            {props.subtitle ? <span class="ff-brand-subtitle" style="display:block">{props.subtitle}</span> : null}
          </span>
        </button>

        <nav class={`ff-nav-menu ${mobileMenuOpen() ? 'is-open' : ''}`} aria-label="主导航">
          <For each={navItems()}>
            {(item) => (
              <a
                href={item.path}
                class={`ff-menu-item ${item.soft ? 'is-soft' : ''} ${
                  activePath() === item.path || activePath().startsWith(item.path + '/') ? 'is-active' : ''
                }`}
                aria-current={activePath() === item.path ? 'page' : undefined}
              >
                {t(item.labelKey)}
              </a>
            )}
          </For>
        </nav>

        <div class="ff-nav-actions">
          {/* 更新日志按钮：Vue 原版只在 Matches 页的 AppTopNav actions slot 里渲染；
              Solid 侧顶栏全局挂载，故仅在 /matches 时出现（见 ChangelogButton.jsx）。 */}
          <Show when={location.pathname === '/matches'}>
            <ChangelogButton />
          </Show>
          <button
            type="button"
            class="ff-icon-btn ff-mobile-toggle"
            aria-expanded={mobileMenuOpen()}
            aria-label={mobileMenuOpen() ? t('nav.closeMenu') : t('nav.openMenu')}
            onClick={() => setMobileMenuOpen((v) => !v)}
          >
            ☰
          </button>
          <button
            type="button"
            class="ff-icon-btn"
            aria-label={theme() === 'dark' ? t('nav.toggleThemeLight') : t('nav.toggleThemeDark')}
            title={theme() === 'dark' ? t('nav.toggleThemeLight') : t('nav.toggleThemeDark')}
            onClick={toggleTheme}
          >
            {theme() === 'dark' ? '☀' : '☾'}
          </button>
          <button type="button" class="ff-icon-btn" aria-label={t('nav.search')} title={t('nav.search')} onClick={() => setSearchVisible(true)}>
            <span aria-hidden="true">⌕</span>
            <span class="ff-nav-action-label">{t('nav.search')}</span>
          </button>
          <Show when={auth.token()}>
            <button type="button" class="ff-icon-btn" aria-label={t('nav.notifications')} title={t('nav.notifications')} onClick={openNotifications}>
              <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true">
                <path d="M18 8a6 6 0 1 0-12 0c0 7-3 9-3 9h18s-3-2-3-9" stroke-linejoin="round" />
                <path d="M13.7 21a2 2 0 0 1-3.4 0" stroke-linecap="round" />
              </svg>
              <Show when={notificationUnread() > 0}>
                <span class="ff-badge">{notificationUnread() > 99 ? '99+' : notificationUnread()}</span>
              </Show>
            </button>
          </Show>
          <div class="ff-account">
            <button
              type="button"
              class="ff-avatar"
              aria-label={auth.token() ? '打开个人菜单' : '打开登录菜单'}
              aria-expanded={accountOpen()}
              onClick={() => setAccountOpen((v) => !v)}
            >
              {auth.avatarData() ? (
                <img src={auth.avatarData()} alt="" />
              ) : (
                (auth.username()?.[0]?.toUpperCase() || '客')
              )}
            </button>
            <Show when={accountOpen()}>
              <div class="ff-dropdown" role="menu">
                <Show when={auth.token()} fallback={<button type="button" role="menuitem" onClick={() => { setAccountOpen(false); auth.openAuthDialog('') }}>{t('nav.loginRegister')}</button>}>
                  <button type="button" role="menuitem" onClick={() => { setAccountOpen(false); navigate('/profile') }}>{t('nav.profile')}</button>
                </Show>
                <button type="button" role="menuitem" onClick={() => { setAccountOpen(false); navigate('/privacy') }}>{t('nav.privacy')}</button>
                <Show when={auth.token()}>
                  <hr />
                  <button type="button" role="menuitem" onClick={() => { setAccountOpen(false); auth.logout(); navigate('/matches') }}>{t('nav.logout')}</button>
                </Show>
              </div>
            </Show>
          </div>
        </div>
      </header>

      <Show when={searchVisible()}>
        <div class="ff-dialog-overlay" onClick={() => setSearchVisible(false)} />
        <div class="ff-dialog-content is-wide" role="dialog" aria-modal="true" aria-label={t('nav.search')}>
          <h2 class="ff-dialog-title">{t('nav.search')}</h2>
          <div style="margin-top:12px">
            <input
              class="ff-input"
              value={searchKeyword()}
              placeholder={t('nav.searchPlaceholder')}
              autofocus
              onInput={(e) => onSearchInput(e.currentTarget.value)}
              onKeyDown={(e) => { if (e.key === 'Enter') runSearch(searchKeyword()) }}
            />
          </div>
          <Show when={searchLoading()}>
            <div class="ff-search-state">{t('nav.searching')}</div>
          </Show>
          <Show when={!searchLoading() && hasSearchHits()}>
            <Show when={searchResults().teams.length}>
              <div class="ff-search-section">
                <div class="ff-search-section-title">{t('nav.teams')}</div>
                <For each={searchResults().teams}>
                  {(item) => (
                    <button type="button" class="ff-search-item" onClick={() => openTeam(item)}>
                      <strong>{item.name}</strong>
                      <small>{item.league || '球队资料'}</small>
                    </button>
                  )}
                </For>
              </div>
            </Show>
            <Show when={searchResults().leagues.length}>
              <div class="ff-search-section">
                <div class="ff-search-section-title">{t('nav.leagues')}</div>
                <For each={searchResults().leagues}>
                  {(item) => (
                    <button type="button" class="ff-search-item" onClick={() => goBrowse(`/competitions?league=${encodeURIComponent(item.name)}`)}>
                      <strong>{item.name}</strong>
                      <small>{item.matchCount ? `${item.matchCount} 场相关比赛` : '赛事资料'}</small>
                    </button>
                  )}
                </For>
              </div>
            </Show>
            <Show when={searchResults().matches.length}>
              <div class="ff-search-section">
                <div class="ff-search-section-title">{t('nav.matchesSection')}</div>
                <For each={searchResults().matches}>
                  {(item) => (
                    <button type="button" class="ff-search-item" onClick={() => openMatch(item)}>
                      <strong>
                        {item.homeTeamName} vs {item.awayTeamName}
                      </strong>
                      <small>
                        {item.leagueName || '比赛'} · {t('nav.viewPrediction')}
                      </small>
                    </button>
                  )}
                </For>
              </div>
            </Show>
            <Show when={searchResults().articles.length}>
              <div class="ff-search-section">
                <div class="ff-search-section-title">{t('nav.articles')}</div>
                <For each={searchResults().articles}>
                  {(item) => (
                    <button type="button" class="ff-search-item" onClick={() => goBrowse('/competitions')}>
                      <strong>{item.title || '资讯'}</strong>
                      <small>{item.source || '文章'}</small>
                    </button>
                  )}
                </For>
              </div>
            </Show>
          </Show>
          <Show when={!searchLoading() && !hasSearchHits() && searchKeyword().trim()}>
            <div class="ff-search-state">
              <p>{t('nav.noResultsFor', { keyword: searchKeyword().trim() })}</p>
              <div style="display:flex;gap:8px;justify-content:center;margin-top:12px">
                <button type="button" class="ff-link" onClick={() => goBrowse('/matches')}>
                  {t('nav.browseMatches')}
                </button>
                <button type="button" class="ff-link" onClick={() => goBrowse('/competitions')}>
                  {t('nav.viewCompetitions')}
                </button>
              </div>
            </div>
          </Show>
        </div>
      </Show>

      <Show when={notificationVisible()}>
        <div class="ff-dialog-overlay" onClick={() => setNotificationVisible(false)} />
        <div class="ff-dialog-content" role="dialog" aria-modal="true" aria-label={t('nav.notifications')}>
          <h2 class="ff-dialog-title">{t('nav.notifications')}</h2>
          <p class="ff-dialog-desc">收藏比赛后将在开赛前站内提醒；也可开启浏览器通知。</p>
          <div style="display:flex;justify-content:flex-end;margin-top:8px">
            <Show when={notificationUnread() > 0}>
              <button type="button" class="ff-link" onClick={markAllNotifications}>
                全部已读
              </button>
            </Show>
          </div>
          <Show when={notificationLoading()}>
            <div class="ff-search-state">{t('common.loading')}</div>
          </Show>
          <Show when={!notificationLoading() && notifications().length === 0}>
            <div class="ff-search-state">
              <p style="font-weight:700;color:var(--ff-text-strong)">暂无通知</p>
              <p style="margin-top:6px">收藏比赛后，开赛前 30 分钟会写在这里。</p>
              <div style="margin-top:12px">
                <button type="button" class="ff-btn ff-btn-primary ff-btn-sm" onClick={() => goBrowse('/matches')}>
                  去收藏一场比赛
                </button>
              </div>
            </div>
          </Show>
          <For each={notifications()}>
            {(item) => (
              <button type="button" class="ff-search-item" onClick={() => setNotificationVisible(false)}>
                <strong>{item.title}</strong>
                <small>{item.body || '暂无详细内容'}</small>
                <small>{item.created_at || ''}</small>
              </button>
            )}
          </For>
        </div>
      </Show>
    </>
  )
}
