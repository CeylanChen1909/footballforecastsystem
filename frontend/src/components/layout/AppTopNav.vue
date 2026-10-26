<template>
  <el-header class="app-top-nav ff-surface">
    <a class="skip-link" href="#app-main">{{ t('nav.skipToContent') }}</a>
    <div class="nav-brand" role="link" tabindex="0" :aria-label="t('nav.backToMatches')" @click="handleBrandClick" @keydown.enter="handleBrandClick" @keydown.space.prevent="handleBrandClick">
      <div class="brand-icon football-brand">
        <component :is="brandIcon" :size="22" />
      </div>
      <div class="brand-text">
        <div class="brand-title" :title="title">{{ title }}</div>
        <div v-if="subtitle" class="brand-subtitle" :title="subtitle">{{ subtitle }}</div>
      </div>
    </div>

    <nav class="nav-menu-wrap" :class="{ 'is-open': mobileMenuOpen }" :aria-label="t('nav.mainNavigation')">
      <div class="top-menu sport-menu" role="menubar">
        <button
          v-for="item in navItems"
          :key="item.path"
          type="button"
          role="menuitem"
          class="menu-item sport-menu-item"
          :class="{
            'is-active': activePath === item.path || activePath.startsWith(item.path + '/'),
            'is-soft': item.soft
          }"
          :aria-current="activePath === item.path || activePath.startsWith(item.path + '/') ? 'page' : undefined"
          @click="handleSelect(item.path)"
        >
          <el-icon><component :is="item.icon" /></el-icon><span>{{ item.label }}</span>
        </button>
      </div>
      <div class="lang-switch mobile-lang-switch" role="group" :aria-label="t('nav.language')">
        <button type="button" class="lang-option" :class="{ 'is-active': locale === 'en' }" :aria-pressed="locale === 'en'" :aria-label="t('nav.english')" @click="setLocale('en')">EN</button>
        <button type="button" class="lang-option" :class="{ 'is-active': locale === 'zh' }" :aria-pressed="locale === 'zh'" :aria-label="t('nav.chinese')" @click="setLocale('zh')">{{ t('components.language.zh') }}</button>
      </div>
    </nav>

    <div class="nav-actions">
      <slot name="actions" />
      <div class="lang-switch desktop-lang-switch" role="group" :aria-label="t('nav.language')">
        <button type="button" class="lang-option" :class="{ 'is-active': locale === 'en' }" :aria-pressed="locale === 'en'" :aria-label="t('nav.english')" @click="setLocale('en')">EN</button>
        <button type="button" class="lang-option" :class="{ 'is-active': locale === 'zh' }" :aria-pressed="locale === 'zh'" :aria-label="t('nav.chinese')" @click="setLocale('zh')">{{ t('components.language.zh') }}</button>
      </div>
      <el-button text class="mobile-menu-toggle" :aria-expanded="mobileMenuOpen" :aria-label="mobileMenuOpen ? t('nav.closeMenu') : t('nav.openMenu')" :title="mobileMenuOpen ? t('nav.closeMenu') : t('nav.menu')" @click="mobileMenuOpen = !mobileMenuOpen"><el-icon><Menu /></el-icon></el-button>
      <el-button text class="global-search-btn" :aria-label="t('nav.search')" :title="t('nav.search')" @click="openSearch"><el-icon><Search /></el-icon><span class="nav-action-label">{{ t('nav.search') }}</span></el-button>
      <el-badge v-if="userStore.token" :value="notificationUnread" :hidden="notificationUnread === 0" :max="99" class="notification-badge">
        <el-button text class="global-search-btn" :aria-label="t('nav.notificationCenter')" :title="t('nav.notificationCenter')" @click="openNotifications"><el-icon><Bell /></el-icon></el-button>
      </el-badge>
      <el-dropdown trigger="click" @command="handleUserCommand">
        <div class="nav-account-trigger" :aria-label="userStore.token ? t('nav.openAccountMenu') : t('nav.openLoginMenu')">
          <el-avatar :size="36" class="nav-avatar" :src="userStore.avatarData || undefined">{{ userStore.username?.[0]?.toUpperCase() || t('nav.guest') }}</el-avatar>
          <el-icon class="nav-account-arrow" aria-hidden="true"><ArrowDown /></el-icon>
        </div>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item v-if="userStore.token" command="profile"><el-icon><User /></el-icon> {{ t('nav.profile') }}</el-dropdown-item>
            <el-dropdown-item v-else command="login"><el-icon><SwitchButton /></el-icon> {{ t('nav.loginRegister') }}</el-dropdown-item>
            <el-dropdown-item command="privacy"><el-icon><Notebook /></el-icon> {{ t('nav.privacy') }}</el-dropdown-item>
            <el-dropdown-item v-if="userStore.token" divided command="logout"><el-icon><SwitchButton /></el-icon> {{ t('nav.logout') }}</el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
    </div>
    <el-dialog v-if="dialogsReady" v-model="searchVisible" :title="t('nav.searchTitle')" width="min(620px, 92vw)" append-to-body @opened="focusSearchInput">
      <el-input ref="searchInputRef" v-model="searchKeyword" clearable autofocus :placeholder="t('nav.searchPlaceholder')" @keyup.enter="runSearch" @input="onSearchInput" />
      <div v-if="searchLoading" class="global-search-state">{{ t('nav.searching') }}</div>
      <div v-else-if="hasSearchHits" class="global-search-results">
        <div v-if="searchResults.teams.length" class="global-search-section">
          <div class="global-search-section-title">{{ t('nav.searchTeams') }}</div>
          <button v-for="item in searchResults.teams" :key="`t-${item.id || item.name}`" type="button" class="global-search-item" @click="openTeam(item)">
            <strong>{{ item.name }}</strong>
            <small>{{ item.league || t('nav.searchTeamFallback') }}</small>
          </button>
        </div>
        <div v-if="searchResults.leagues.length" class="global-search-section">
          <div class="global-search-section-title">{{ t('nav.searchLeagues') }}</div>
          <button v-for="item in searchResults.leagues" :key="`l-${item.name}`" type="button" class="global-search-item" @click="openLeague(item)">
            <strong>{{ item.name }}</strong>
            <small>{{ item.matchCount ? t('nav.searchLeagueMatchCount', { count: item.matchCount }) : t('nav.searchLeagueFallback') }}</small>
          </button>
        </div>
        <div v-if="searchResults.matches.length" class="global-search-section">
          <div class="global-search-section-title">{{ t('nav.searchMatches') }}</div>
          <button v-for="item in searchResults.matches" :key="`m-${item.fixtureId || item.matchId || item.id}-${item.homeTeamName}-${item.awayTeamName}`" type="button" class="global-search-item" @click="openMatch(item)">
            <strong>{{ searchMatchTeam(item, 'home') }} vs {{ searchMatchTeam(item, 'away') }}</strong>
            <small>{{ searchMatchLeague(item) || t('nav.searchMatchFallback') }}{{ item.matchTime ? ` · ${formatSearchTime(item.matchTime)}` : '' }} · {{ t('nav.searchViewPrediction') }}</small>
          </button>
        </div>
        <div v-if="searchResults.articles.length" class="global-search-section">
          <div class="global-search-section-title">{{ t('nav.searchArticles') }}</div>
          <button v-for="item in searchResults.articles" :key="`a-${item.id || item.title}`" type="button" class="global-search-item" @click="openArticle(item)">
            <strong>{{ item.title || t('nav.searchArticleFallback') }}</strong>
            <small>{{ item.source || t('nav.searchArticleSourceFallback') }}</small>
          </button>
        </div>
      </div>
      <div v-else-if="searchKeyword.trim()" class="global-search-state global-search-empty">
        <p>{{ t('nav.searchNoResults', { keyword: searchKeyword.trim() }) }}</p>
        <div class="global-search-empty-links">
          <el-button type="primary" link @click="goBrowse('/matches')">{{ t('nav.searchBrowseMatches') }}</el-button>
          <el-button type="primary" link @click="goBrowse('/competitions')">{{ t('nav.searchBrowseCompetitions') }}</el-button>
        </div>
      </div>
    </el-dialog>
    <el-dialog v-if="dialogsReady" v-model="notificationVisible" :title="t('nav.notificationCenter')" width="min(520px, 92vw)" append-to-body>
      <div class="notification-head">
        <span>{{ t('nav.notificationHint') }}</span>
        <el-button v-if="notificationUnread" link type="primary" @click="markAllNotifications">{{ t('nav.markAllRead') }}</el-button>
      </div>
      <div v-if="notificationLoading" class="global-search-state">{{ t('nav.notificationLoading') }}</div>
      <div v-else-if="notifications.length === 0" class="notification-empty">
        <p class="notification-empty-title">{{ t('nav.noNotifications') }}</p>
        <p class="notification-empty-hint">{{ t('nav.notificationEmptyHint') }}</p>
        <div class="notification-empty-actions">
          <el-button type="primary" plain size="small" @click="goBrowse('/matches')">{{ t('nav.favoriteMatch') }}</el-button>
          <el-button v-if="browserNotifyState === 'default'" size="small" plain @click="requestBrowserNotify">{{ t('nav.enableBrowserNotifications') }}</el-button>
        </div>
      </div>
      <div v-else class="notification-list">
        <button v-for="item in notifications" :key="item.id" type="button" class="notification-item" :class="{ 'is-unread': !item.read_at }" @click="markNotification(item)">
          <span class="notification-dot"></span>
          <span class="notification-copy"><strong>{{ item.title }}</strong><small>{{ item.body || t('nav.noDetails') }}</small><em>{{ formatNotificationTime(item.created_at) }}</em></span>
        </button>
      </div>
    </el-dialog>
  </el-header>
  <div
    v-if="mobileMenuOpen"
    class="nav-menu-backdrop"
    aria-hidden="true"
    @click="mobileMenuOpen = false"
  />
</template>

<script setup>
import { useRouter } from 'vue-router'
import { useUserStore } from '../../stores/user'
import { computed, getCurrentInstance, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { crawlerApi, searchApi, userApi } from '../../api'
import { displayLeagueName, displayTeamName, expandSearchQueries, findLeagueAlias, matchLocalSearch } from '../../utils/teamNames'
import { ArrowDown, ChatLineSquare, Football, Notebook, User, SwitchButton, Setting, Search, Bell, Menu } from '@element-plus/icons-vue'
import { registerElementPlusDialog } from '../../plugins/register-element-plus-dialog'
import { useI18n } from '../../i18n'

const epApp = getCurrentInstance()?.appContext.app

const props = defineProps({
  title: { type: String, required: true },
  subtitle: { type: String, required: true },
  brandIcon: { type: [Object, Function, String], required: true },
  brandHref: { type: String, default: '/matches' },
  activePath: { type: String, default: '/matches' },
})

const router = useRouter()
const userStore = useUserStore()
const { locale, t, setLocale } = useI18n()
const dialogsReady = ref(false)
const searchVisible = ref(false)
const searchLoading = ref(false)
const searchKeyword = ref('')
const searchInputRef = ref(null)
const searchResults = reactive({ matches: [], teams: [], leagues: [], articles: [] })
const notificationVisible = ref(false)
const mobileMenuOpen = ref(false)
const notificationLoading = ref(false)
const notifications = ref([])
const notificationUnread = ref(0)
const knownNotificationIds = ref(new Set())
const browserNotifyState = ref(typeof Notification === 'undefined' ? 'unsupported' : Notification.permission)
let searchDebounceTimer = null
let notificationPollTimer = null
const isAdmin = computed(() => ['ADMIN', 'SUPER_ADMIN'].includes(userStore.role))
const navItems = computed(() => {
  const items = [
    { path: '/matches', label: t('nav.matches'), icon: Football },
    { path: '/competitions', label: t('nav.competitions'), icon: Notebook },
    { path: '/agent', label: t('nav.agent'), icon: ChatLineSquare, soft: true },
  ]
  if (isAdmin.value) items.push({ path: '/admin', label: t('nav.admin'), icon: Setting })
  return items
})
const protectedPaths = ['/profile', '/admin']
const hasSearchHits = computed(() =>
  searchResults.matches.length + searchResults.teams.length + searchResults.leagues.length + searchResults.articles.length > 0
)

const requiresLogin = path => protectedPaths.some(value => path === value || path.startsWith(`${value}/`))

const handleBrandClick = () => {
  router.push(props.brandHref)
}

const resetSearchResults = () => {
  searchResults.matches = []
  searchResults.teams = []
  searchResults.leagues = []
  searchResults.articles = []
}

const uniqBy = (rows, keyFn) => {
  const seen = new Set()
  return rows.filter(item => {
    const key = keyFn(item)
    if (!key || seen.has(key)) return false
    seen.add(key)
    return true
  })
}

const unwrapList = payload => {
  if (Array.isArray(payload)) return payload
  if (Array.isArray(payload?.items)) return payload.items
  if (Array.isArray(payload?.data?.response)) return payload.data.response
  if (Array.isArray(payload?.response)) return payload.response
  if (Array.isArray(payload?.data)) return payload.data
  return []
}

const normalizeMatchHit = item => {
  const fixtureId = item?.fixtureId || item?.matchId || item?.id || item?.fixture?.id || item?.fixture?.publicMatchId
  return {
    fixtureId,
    matchId: fixtureId,
    id: fixtureId,
    homeTeamName: item?.homeTeamName || item?.teams?.home?.name || '',
    homeTeamDisplayName: item?.homeTeamDisplayName || item?.homeTeamDisplay || item?.teams?.home?.teamDisplayName || item?.teams?.home?.displayName || '',
    awayTeamName: item?.awayTeamName || item?.teams?.away?.name || '',
    awayTeamDisplayName: item?.awayTeamDisplayName || item?.awayTeamDisplay || item?.teams?.away?.teamDisplayName || item?.teams?.away?.displayName || '',
    leagueName: item?.leagueName || item?.league?.name || '',
    leagueDisplay: item?.leagueDisplay || item?.leagueDisplayName || item?.league?.leagueDisplay || item?.league?.displayName || '',
    matchTime: item?.matchTime || item?.fixture?.date || ''
  }
}

const searchMatchTeam = (item, side) => displayTeamName(
  item?.[`${side}TeamName`] || '',
  item?.[`${side}TeamDisplayName`] || ''
)
const searchMatchLeague = item => displayLeagueName(item?.leagueName || '', item?.leagueDisplay || '')

const normalizeTeamHit = item => ({
  id: item?.id || item?.teamId || 0,
  name: item?.name || item?.teamName || '',
  league: item?.league || item?.leagueName || '',
  logo: item?.logo || ''
})

const mergeSearchPayload = (base, extra) => {
  const matches = uniqBy([...(base.matches || []), ...(extra.matches || [])].map(normalizeMatchHit).filter(item => item.homeTeamName || item.awayTeamName), item => `${item.fixtureId || ''}:${item.homeTeamName}:${item.awayTeamName}:${item.matchTime || ''}`)
  const teams = uniqBy([...(base.teams || []), ...(extra.teams || [])].map(normalizeTeamHit).filter(item => item.name), item => `${item.id || ''}:${item.name}`)
  const leagues = uniqBy([...(base.leagues || []), ...(extra.leagues || [])].filter(item => item?.name), item => item.name)
  const articles = uniqBy([...(base.articles || []), ...(extra.articles || [])], item => item?.id || item?.title)
  return { matches, teams, leagues, articles }
}

const localAliasFallback = async (keyword) => {
  const queries = expandSearchQueries(keyword).slice(0, 4)
  const leagueAlias = findLeagueAlias(keyword)
  const tasks = [
    ...queries.map(q => searchApi.search(q, 8).catch(() => null)),
    ...queries.map(q => crawlerApi.searchTeams(q).catch(() => null)),
    ...queries.map(q => crawlerApi.searchMatches(q).catch(() => null))
  ]
  const settled = await Promise.all(tasks)
  let merged = { matches: [], teams: [], leagues: [], articles: [] }
  settled.forEach((result, index) => {
    if (!result) return
    if (index < queries.length) {
      merged = mergeSearchPayload(merged, {
        matches: result.matches || result?.data?.matches || [],
        teams: result.teams || result?.data?.teams || [],
        leagues: result.leagues || result?.data?.leagues || [],
        articles: result.articles || result?.data?.articles || []
      })
    } else if (index < queries.length * 2) {
      merged = mergeSearchPayload(merged, { teams: unwrapList(result) })
    } else {
      merged = mergeSearchPayload(merged, { matches: unwrapList(result).map(normalizeMatchHit) })
    }
  })
  if (leagueAlias) {
    merged.leagues = uniqBy([{ name: leagueAlias.name, matchCount: 0 }, ...merged.leagues], item => item.name)
  }
  // Keep only rows that still relate to the original query / aliases.
  merged.matches = merged.matches.filter(item => matchLocalSearch(`${item.homeTeamName} ${item.awayTeamName} ${item.leagueName}`, keyword)).slice(0, 8)
  merged.teams = merged.teams.filter(item => matchLocalSearch(`${item.name} ${item.league}`, keyword)).slice(0, 8)
  merged.leagues = merged.leagues.filter(item => matchLocalSearch(item.name, keyword) || findLeagueAlias(keyword)?.name === item.name).slice(0, 8)
  return merged
}

const runSearch = async () => {
  const keyword = searchKeyword.value.trim()
  if (!keyword) { resetSearchResults(); return }
  searchLoading.value = true
  try {
    const [globalResult, teamResult, matchResult] = await Promise.all([
      searchApi.search(keyword, 8).catch(() => null),
      crawlerApi.searchTeams(keyword).catch(() => null),
      crawlerApi.searchMatches(keyword).catch(() => null)
    ])
    let merged = mergeSearchPayload({
      matches: globalResult?.matches || globalResult?.data?.matches || [],
      teams: globalResult?.teams || globalResult?.data?.teams || [],
      leagues: globalResult?.leagues || globalResult?.data?.leagues || [],
      articles: globalResult?.articles || globalResult?.data?.articles || []
    }, {
      teams: unwrapList(teamResult),
      matches: unwrapList(matchResult).map(normalizeMatchHit)
    })
    if (!(merged.matches.length || merged.teams.length || merged.leagues.length)) {
      merged = await localAliasFallback(keyword)
    }
    searchResults.matches = merged.matches.slice(0, 8)
    searchResults.teams = merged.teams.slice(0, 8)
    searchResults.leagues = merged.leagues.slice(0, 8)
    searchResults.articles = merged.articles.slice(0, 6)
  } catch {
    resetSearchResults()
  } finally {
    searchLoading.value = false
  }
}

const onSearchInput = () => {
  if (searchDebounceTimer) window.clearTimeout(searchDebounceTimer)
  searchDebounceTimer = window.setTimeout(() => { runSearch() }, 280)
}
const focusSearchInput = () => {
  window.setTimeout(() => searchInputRef.value?.focus?.(), 30)
}
const formatSearchTime = value => {
  if (!value) return ''
  const date = new Date(String(value).includes('T') ? value : String(value).replace(' ', 'T'))
  if (Number.isNaN(date.getTime())) return String(value)
  return date.toLocaleString(locale.value === 'zh' ? 'zh-CN' : 'en-US', { timeZone: 'Asia/Shanghai', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' })
}
const goBrowse = (path) => {
  searchVisible.value = false
  notificationVisible.value = false
  router.push(path)
}
const openMatch = item => {
  searchVisible.value = false
  const id = item?.matchId || item?.id || item?.fixtureId
  if (!id) return
  router.push(`/prediction/${id}?homeName=${encodeURIComponent(item.homeTeamName || '')}&awayName=${encodeURIComponent(item.awayTeamName || '')}&leagueName=${encodeURIComponent(item.leagueName || '')}&matchTime=${encodeURIComponent(item.matchTime || '')}`)
}
const openTeam = item => {
  searchVisible.value = false
  const id = item?.id || item?.name
  if (!id) return
  router.push({
    path: `/team/${encodeURIComponent(String(id))}/squad`,
    query: { name: item.name || '', league: item.league || '', logo: item.logo || '' }
  })
}
const openLeague = item => {
  searchVisible.value = false
  const name = item?.name
  if (!name) return
  router.push({ path: '/competitions', query: { league: name } })
}
const openArticle = item => {
  searchVisible.value = false
  if (item?.link) router.push(item.link)
  else router.push('/competitions')
}

const maybeBrowserNotify = (item) => {
  if (typeof Notification === 'undefined' || Notification.permission !== 'granted') return
  if (!item || item.read_at) return
  const type = String(item.type || item.category || '')
  if (type && !/MATCH_KICKOFF|PREDICTION_READY|REMINDER|FAVORITE/i.test(type) && !/提醒|开赛|预测/.test(String(item.title || ''))) return
  try {
    new Notification(item.title || t('nav.browserReminderTitle'), {
      body: item.body || t('nav.browserReminderBody'),
      tag: `cf-notif-${item.id}`
    })
  } catch { /* ignore */ }
}

const loadNotifications = async ({ silent = false } = {}) => {
  if (!userStore.token) return
  if (!silent) notificationLoading.value = true
  try {
    const result = await userApi.getNotifications(20)
    const items = result?.items || []
    const previous = knownNotificationIds.value
    if (previous.size) {
      items.filter(item => item?.id != null && !previous.has(item.id) && !item.read_at).forEach(maybeBrowserNotify)
    }
    knownNotificationIds.value = new Set(items.map(item => item.id).filter(id => id != null))
    notifications.value = items
    notificationUnread.value = Number(result?.unread || 0)
  } catch {
    if (!silent) { notifications.value = []; notificationUnread.value = 0 }
  } finally {
    if (!silent) notificationLoading.value = false
  }
}
const openNotifications = async () => { ensureDialog(); await nextTick(); notificationVisible.value = true; await loadNotifications() }
const markNotification = async item => {
  if (!item.read_at) {
    await userApi.readNotification(item.id).catch(() => {})
    item.read_at = new Date().toISOString()
    notificationUnread.value = Math.max(0, notificationUnread.value - 1)
  }
  notificationVisible.value = false
  if (item.link) router.push(item.link)
}
const markAllNotifications = async () => {
  await userApi.readAllNotifications().catch(() => {})
  notifications.value.forEach(item => { item.read_at = item.read_at || new Date().toISOString() })
  notificationUnread.value = 0
}
const requestBrowserNotify = async () => {
  if (typeof Notification === 'undefined') return
  const permission = Notification.permission === 'default' ? await Notification.requestPermission() : Notification.permission
  browserNotifyState.value = permission
}
const formatNotificationTime = value => value ? new Date(value).toLocaleString(locale.value === 'zh' ? 'zh-CN' : 'en-US', { timeZone: 'Asia/Shanghai', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }) : ''

const startNotificationPolling = () => {
  stopNotificationPolling()
  if (!userStore.token) return
  notificationPollTimer = window.setInterval(() => loadNotifications({ silent: true }), 60 * 1000)
}
const stopNotificationPolling = () => {
  if (notificationPollTimer) {
    window.clearInterval(notificationPollTimer)
    notificationPollTimer = null
  }
}


const ensureDialog = () => { registerElementPlusDialog(epApp); dialogsReady.value = true }
const openSearch = async () => { ensureDialog(); await nextTick(); searchVisible.value = true }
const handleSelect = (index) => {
  mobileMenuOpen.value = false
  if (index === props.activePath) return
  if (requiresLogin(index) && !userStore.token) {
    userStore.openAuthDialog(index)
    return
  }
  router.push(index)
}

const handleUserCommand = (cmd) => {
  if (cmd === 'logout') {
    userStore.logout()
    if (requiresLogin(router.currentRoute.value.path)) router.replace('/matches')
  } else if (cmd === 'profile') {
    if (userStore.token) router.push('/profile')
    else userStore.openAuthDialog('/profile')
  } else if (cmd === 'login') {
    userStore.openAuthDialog(router.currentRoute.value.fullPath)
  } else if (cmd === 'privacy') {
    router.push('/privacy')
  }
}

watch(() => userStore.token, token => {
  if (token) { loadNotifications(); startNotificationPolling() }
  else { notifications.value = []; notificationUnread.value = 0; knownNotificationIds.value = new Set(); stopNotificationPolling() }
})
watch(searchVisible, visible => { if (!visible && searchDebounceTimer) window.clearTimeout(searchDebounceTimer) })
watch(locale, () => { if (userStore.token) loadNotifications({ silent: true }) })
onMounted(() => { if (userStore.token) { loadNotifications(); startNotificationPolling() } })
onBeforeUnmount(() => {
  stopNotificationPolling()
  if (searchDebounceTimer) window.clearTimeout(searchDebounceTimer)
})
</script>

<style scoped>
.app-top-nav {
  display: flex;
  align-items: center;
  gap: 24px;
  position: sticky;
  top: 0;
  z-index: 50;
  padding: 0 28px;
  min-height: 64px;
  width: 100%;
  max-width: 100%;
  min-width: 0;
  box-sizing: border-box;
  border-radius: 0;
  border-top: none;
  border-left: none;
  border-right: none;
  border-bottom: 1px solid var(--ff-border);
  background: rgba(255, 255, 255, 0.96);
}
.skip-link { position:absolute; top:-48px; left:14px; z-index:30; padding:8px 12px; border-radius:6px; color:#fff; background:var(--ff-primary); font-size:12px; font-weight:700; transition:top var(--ff-transition-fast); }
.skip-link:focus, .skip-link:focus-visible { top:8px; outline:2px solid var(--ff-gold); outline-offset:2px; }
.global-search-btn { color: var(--ff-text-muted); }
.notification-badge { display: inline-flex; }
.notification-head { display:flex; align-items:center; justify-content:space-between; gap:12px; color:var(--ff-text-muted); font-size:12px; }
.notification-list { display:flex; flex-direction:column; gap:6px; margin-top:14px; max-height:420px; overflow:auto; }
.notification-item { display:flex; gap:10px; width:100%; padding:12px; border:1px solid transparent; border-radius:8px; background:var(--ff-surface-quiet); color:var(--ff-text); text-align:left; cursor:pointer; }
.notification-item:hover { border-color:var(--ff-primary); }
.notification-item.is-unread { background:rgba(15,107,77,.06); }
.notification-dot { width:7px; height:7px; margin-top:6px; border-radius:50%; background:transparent; flex:none; }
.notification-item.is-unread .notification-dot { background:var(--ff-primary); }
.notification-copy { display:flex; flex-direction:column; gap:4px; min-width:0; }
.notification-copy strong { font-size:13px; }
.notification-copy small, .notification-copy em { color:var(--ff-text-muted); font-size:12px; font-style:normal; }
.global-search-results { display:flex; flex-direction:column; gap:14px; margin-top:16px; max-height:60vh; overflow:auto; }
.global-search-section { display:flex; flex-direction:column; gap:8px; }
.global-search-section-title { color:var(--ff-text-muted); font-size:12px; font-weight:700; letter-spacing:.04em; }
.global-search-item { display:flex; flex-direction:column; align-items:flex-start; gap:3px; padding:10px 12px; border:1px solid var(--ff-border); border-radius:8px; background:var(--ff-surface-quiet); color:var(--ff-text); text-align:left; cursor:pointer; }
.global-search-item:hover { border-color:var(--ff-primary); }
.global-search-item small, .global-search-state { color:var(--ff-text-muted); font-size:12px; }
.global-search-state { padding:20px 0; text-align:center; }
.global-search-empty-links { display:flex; justify-content:center; gap:12px; margin-top:8px; }
.notification-empty { padding:8px 0 4px; text-align:center; }
.notification-empty-title { margin: 10px 0 0; color: var(--ff-text-strong); font-size: 14px; font-weight: 700; }
.notification-empty-hint { margin:6px 0 0; color:var(--ff-text-muted); font-size:12px; }
.notification-empty-actions { display:flex; justify-content:center; gap:8px; flex-wrap:wrap; margin-top:4px; }


.nav-brand {
  display: flex;
  align-items: center;
  gap: 12px;
  cursor: pointer;
  flex: none;
  transition: opacity var(--ff-transition-fast);
}
.nav-brand:hover { opacity: 0.88; }
.nav-brand:focus-visible { outline:2px solid var(--ff-primary); outline-offset:4px; border-radius:6px; }

.brand-icon.football-brand {
  width: 32px;
  height: 32px;
  border-radius: 5px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #ffffff;
  background: var(--ff-primary);
  flex: none;
  box-shadow: none;
  transition: background-color var(--ff-transition-fast);
}
.nav-brand:hover .brand-icon.football-brand {
  background: var(--ff-primary-hover);
}

.brand-text { min-width: 0; overflow: visible; }
.brand-title {
  font-size: 17px;
  font-weight: 600;
  color: var(--ff-text-strong);
  line-height: 1.1;
  letter-spacing: -0.01em;
  white-space: nowrap;
}
.brand-title em {
  font-style: normal;
  color: var(--ff-primary);
}
.brand-subtitle {
  font-size: 11px;
  font-weight: 400;
  color: var(--ff-text-muted);
  margin-top: 2px;
  line-height: 1.4;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.nav-menu-wrap {
  flex: 1;
  display: flex;
  justify-content: center;
}

.nav-actions {
  display: flex;
  align-items: center;
  gap: 6px;
  flex: none;
}
.lang-switch {
  display: inline-flex;
  align-items: center;
  flex: none;
  padding: 2px;
  border: 1px solid var(--ff-border);
  border-radius: 999px;
  background: var(--ff-surface-quiet);
}
.lang-option {
  min-width: 38px;
  min-height: 28px;
  padding: 0 8px;
  border: 0;
  border-radius: 999px;
  background: transparent;
  color: var(--ff-text-muted);
  font: inherit;
  font-size: 12px;
  font-weight: 600;
  line-height: 1;
  cursor: pointer;
}
.lang-option:hover { color: var(--ff-primary); }
.lang-option.is-active { color: #ffffff; background: var(--ff-primary); }
.lang-option:focus-visible { outline: 2px solid var(--ff-primary); outline-offset: 2px; }
.mobile-lang-switch { display: none; }
.mobile-menu-toggle { display:none; color:var(--ff-text-muted); }
.nav-account-trigger {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 2px 3px 2px 2px;
  border: 1px solid transparent;
  border-radius: 22px;
  color: var(--ff-text-muted);
  background: transparent;
  cursor: pointer;
  outline: none;
  transition: border-color var(--ff-transition-fast), background-color var(--ff-transition-fast), color var(--ff-transition-fast);
}
.nav-account-trigger:hover,
.nav-account-trigger:focus-visible {
  border-color: var(--ff-border-strong);
  background: var(--ff-surface-quiet);
  color: var(--ff-primary);
}
.nav-account-arrow {
  font-size: 14px;
}

.nav-avatar {
  background: var(--ff-primary);
  color: #ffffff;
  font-weight: 600;
  cursor: pointer;
  border: none;
  box-shadow: none;
  transition: background-color var(--ff-transition-fast), color var(--ff-transition-fast);
}
.nav-avatar:hover {
  background: var(--ff-primary-hover);
}

.sport-menu {
  display: flex;
  align-items: center;
  gap: 2px;
  background: transparent;
  padding: 0;
  border-radius: 0;
  border: none;
  box-shadow: none;
}
.sport-menu-item {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  height: 40px;
  padding: 0 12px;
  border: none;
  border-bottom: 2px solid transparent;
  background: transparent;
  font: inherit;
  cursor: pointer;
  line-height: 40px;
  border-radius: 4px;
  margin: 0 1px;
  color: var(--ff-text-muted);
  font-weight: 600;
  transition: background-color var(--ff-transition-fast), color var(--ff-transition-fast), border-color var(--ff-transition-fast);
  border-bottom: 2px solid transparent !important;
}
.sport-menu-item:hover {
  background: var(--ff-surface-quiet);
  color: var(--ff-primary);
}
.sport-menu-item.is-active {
  background: transparent;
  color: var(--ff-primary) !important;
  border-bottom-color: var(--ff-primary) !important;
  box-shadow: none;
}
.sport-menu-item.is-soft {
  font-weight: 500;
  color: var(--ff-text-faint, var(--ff-text-muted));
}
.sport-menu-item.is-soft .el-icon { opacity: 0.72; }
.sport-menu-item.is-soft.is-active {
  font-weight: 600;
  color: var(--ff-primary) !important;
}
.sport-menu-item.is-soft.is-active .el-icon { opacity: 1; }
.menu-item {
  display: flex;
  align-items: center;
  gap: 5px;
  font-weight: 600;
}

/* r13: mobile menu is an overlay panel under sticky header; backdrop catches outside taps */
.nav-menu-backdrop {
  display: none;
}
@media (max-width: 768px) {
  .app-top-nav {
    display: grid;
    grid-template-columns: minmax(min-content, 1fr) auto;
    grid-template-areas:
      "brand actions"
      "menu menu";
    align-items: center;
    height: auto;
    min-height: 60px;
    padding: 10px 12px;
    gap: 8px 8px;
    width: 100%;
    max-width: 100%;
    min-width: 0;
    z-index: 50;
  }
  .nav-brand { grid-area: brand; min-width: min-content; max-width: 100%; overflow: visible; }
  .nav-brand .brand-icon.football-brand { width: 28px; height: 28px; }
  .nav-brand .brand-title { font-size: 16px; }
  .nav-menu-backdrop {
    display: block;
    position: fixed;
    inset: 0;
    z-index: 45;
    background: rgba(15, 23, 20, 0.42);
    border: 0;
    cursor: pointer;
    -webkit-tap-highlight-color: transparent;
  }
  .nav-menu-wrap {
    grid-area: menu;
    display: none;
    position: absolute;
    z-index: 50;
    top: calc(100% + 0px);
    left: 0;
    right: 0;
    width: 100%;
    max-width: 100%;
    min-width: 0;
    box-sizing: border-box;
    padding: 10px 12px 14px;
    border: 0;
    border-bottom: 1px solid var(--ff-border);
    border-radius: 0 0 12px 12px;
    background: var(--ff-surface);
    box-shadow: 0 12px 28px rgba(15, 23, 20, 0.18);
  }
  .nav-menu-wrap.is-open { display: flex; flex-direction: column; }
  .desktop-lang-switch { display: none; }
  .mobile-lang-switch { display: inline-flex; align-self: flex-start; margin: 8px 2px 0; }
  .mobile-menu-toggle { display: inline-flex; }
  .nav-menu-wrap .sport-menu {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    width: 100%;
    min-width: 0;
  }
  .nav-menu-wrap .sport-menu-item {
    justify-content: flex-start;
    margin: 2px;
    padding: 0 12px;
  }
  .nav-menu-wrap .sport-menu-item.is-active { border-bottom-color: transparent !important; }
  .nav-menu-wrap::-webkit-scrollbar { display: none; }
  .nav-menu-wrap .sport-menu-item {
    height: 34px;
    line-height: 34px;
  }
  .nav-actions { grid-area: actions; gap: 2px; min-width: 0; }
  .nav-actions :deep(.changelog-trigger) { padding: 0 8px; }
  .nav-account-arrow { display: none; }
}

.nav-action-label { display:none; margin-left:4px; font-size:12px; }
@media (min-width: 900px) and (max-width: 1100px) {
  .nav-action-label { display:inline; }
}
/* r4 a11y nav */
.global-search-btn,
.mobile-menu-toggle {
  min-width: 40px;
  min-height: 40px;
}
.nav-account-trigger {
  min-height: 40px;
  padding: 2px 4px;
}
.top-menu .sport-menu-item {
  min-height: 44px;
}
.global-search-item {
  min-height: 44px;
}
.global-search-item:focus-visible {
  outline: 2px solid var(--ff-primary);
  outline-offset: 2px;
}
</style>
