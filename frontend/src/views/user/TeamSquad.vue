<template>
  <div class="squad-page ff-page-shell">
    <AppTopNav
      title="ChenFootball"
      :subtitle="t('prediction.teamSquad.subtitle')"
      :brand-icon="Trophy"
      active-path="/competitions"
    />

    <el-main id="app-main" class="main-content" tabindex="-1">
      <div class="page-toolbar">
        <el-button text :icon="ArrowLeft" @click="backToCompetitions">{{ t('prediction.teamSquad.backToCompetitions') }}</el-button>
        <div class="toolbar-actions">
          <el-button plain size="small" @click="goTeamMatches">{{ t('prediction.teamSquad.viewFixtures') }}</el-button>
          <el-button size="small" type="primary" plain :loading="loading" @click="loadSquad(true)">{{ t('prediction.teamSquad.refreshSquad') }}</el-button>
        </div>
      </div>

      <PageState v-if="loading && !squad.length" type="loading" :title="t('prediction.teamSquad.loadingTitle')" :size="40" />
      <PageState v-else-if="error" type="error" :title="t('prediction.teamSquad.loadFailedTitle')" :description="error" :action-text="t('prediction.teamSquad.retry')" @action="loadSquad(true)" />
      <template v-else>
        <section class="team-page-head ff-appear" aria-labelledby="team-page-title">
          <div class="team-identity">
            <img v-if="teamLogo" :src="getMediaAssetUrl(teamLogo)" :alt="teamLabel" class="team-logo" @error="handleTeamLogoError" />
            <span v-else class="team-logo placeholder">{{ firstLetter(teamLabel) }}</span>
            <div>
              <span class="ff-kicker">{{ leagueLabel }} · {{ seasonLabel }}</span>
              <h1 id="team-page-title">{{ teamLabel }}</h1>
            </div>
          </div>
          <div class="hero-actions">
            <el-tag :type="squadTone" effect="plain">{{ squadLabel }}</el-tag>
            <el-button size="small" type="warning" plain @click="toggleFavorite">{{ favorited ? t('prediction.teamSquad.followed') : t('prediction.teamSquad.follow') }}</el-button>
          </div>
        </section>

        <section class="info-strip" :aria-label="t('prediction.teamSquad.infoAria')">
          <div><span>{{ t('prediction.teamSquad.dataSource') }}</span><strong>{{ sourceLabel }}</strong></div>
          <div><span>{{ t('prediction.teamSquad.playerCount') }}</span><strong>{{ squad.length || '—' }}</strong></div>
          <div><span>{{ t('prediction.teamSquad.lastSynced') }}</span><strong>{{ syncedLabel }}</strong></div>
          <div class="info-note">{{ t('prediction.teamSquad.infoNote') }}<template v-if="cacheState === 'STALE'">{{ t('prediction.teamSquad.staleNote') }}</template><template v-else-if="cacheState === 'CACHED'">{{ t('prediction.teamSquad.cachedNote') }}</template></div>
        </section>

        <PageSection :title="t('prediction.teamSquad.registeredSquad')" :subtitle="squadMessage || t('prediction.teamSquad.squadSubtitle')">
          <div v-if="squad.length" class="squad-toolbar" :aria-label="t('prediction.teamSquad.filterAria')">
            <el-input v-model="playerKeyword" clearable size="small" :placeholder="t('prediction.teamSquad.searchPlaceholder')" :aria-label="t('prediction.teamSquad.searchPlaceholder')" />
            <el-select v-model="positionFilter" size="small" :aria-label="t('prediction.teamSquad.filterByPositionAria')"><el-option :label="t('prediction.teamSquad.allPositions')" value="all" /><el-option :label="t('prediction.teamSquad.goalkeeper')" value="goalkeeper" /><el-option :label="t('prediction.teamSquad.defender')" value="defender" /><el-option :label="t('prediction.teamSquad.midfielder')" value="midfielder" /><el-option :label="t('prediction.teamSquad.forward')" value="forward" /><el-option :label="t('prediction.teamSquad.other')" value="other" /></el-select>
            <span>{{ t('prediction.teamSquad.showing', { shown: filteredSquad.length, total: squad.length }) }}</span>
          </div>
          <div v-if="filteredSquad.length" class="squad-groups">
            <div v-for="group in filteredSquadGroups" :key="group.key" class="squad-group">
              <div class="group-heading"><strong>{{ group.label }}</strong><span>{{ t('prediction.teamSquad.peopleCount', { count: group.items.length }) }}</span></div>
              <div class="player-grid">
                <article v-for="player in group.items" :key="playerKey(player)" class="player-card">
                  <img v-if="player.photo && !player.photoBroken" :src="getMediaAssetUrl(player.photo)" :alt="playerName(player)" @error="handlePlayerPhotoError(player)" />
                  <span v-else class="player-avatar">{{ firstLetter(playerName(player)) }}</span>
                  <div class="player-copy">
                    <strong>{{ playerName(player) }}<b v-if="player.number" class="player-number">#{{ player.number }}</b></strong>
                    <span>{{ playerPositionLabel(player) }}<template v-if="player.number"> · {{ t('prediction.teamSquad.jerseyNumber', { number: player.number }) }}</template></span>
                    <small><template v-if="player.nationality">{{ player.nationality }}</template><template v-if="playerAgeLabel(player)"> · {{ playerAgeLabel(player) }}</template></small>
                  </div>
                </article>
              </div>
            </div>
          </div>
          <div v-else class="empty-state">
            <span class="empty-mark">◎</span>
            <div>
              <strong>{{ squad.length ? t('prediction.teamSquad.noMatches') : squadLabel }}</strong>
              <p>{{ squad.length ? t('prediction.teamSquad.adjustFilters') : (squadMessage || t('prediction.teamSquad.emptySquad')) }}</p>
              <el-button v-if="!squad.length" size="small" type="primary" plain :loading="loading" @click="loadSquad(true)">{{ t('prediction.teamSquad.reload') }}</el-button>
            </div>
          </div>
        </PageSection>
      </template>
    </el-main>
      <AppFooter />
  </div>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ArrowLeft, Trophy } from '@element-plus/icons-vue'
import AppFooter from '../../components/layout/AppFooter.vue'
import AppTopNav from '../../components/layout/AppTopNav.vue'
import PageSection from '../../components/layout/PageSection.vue'
import PageState from '../../components/layout/PageState.vue'
import { crawlerApi, favoriteApi } from '../../api'
import { useUserStore } from '../../stores/user'
import { getMediaAssetUrl } from '../../utils/mediaAsset'
import { displayLeagueName, displayPersonName, displayPositionName, displayTeamName } from '../../utils/teamNames'
import { useI18n } from '../../i18n'

const { t, locale } = useI18n()
const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const teamName = ref('')
const teamDisplay = ref('')
const teamId = ref('')
const league = ref('英超')
const leagueDisplay = ref('')
const season = ref('')
const loading = ref(false)
const error = ref('')
const teamLogo = ref('')
const squad = ref([])
const squadStatus = ref('UNKNOWN')
const squadMessage = ref('')
const squadSource = ref('')
const syncedAt = ref('')
const cacheState = ref('')
const playerKeyword = ref('')
const positionFilter = ref('all')
const favorited = ref(false)
const favorites = ref([])
const favoriteRecords = computed(() => favorites.value.filter(item => isCurrentFavorite(item)))

const teamLabel = computed(() => displayTeamName(teamName.value, teamDisplay.value))
const leagueLabel = computed(() => displayLeagueName(league.value, leagueDisplay.value))
const seasonLabel = computed(() => season.value || t('prediction.teamSquad.currentSeason'))
const sourceLabel = computed(() => ({
  'espn-squad': t('prediction.teamSquad.sourceEspn'),
  'espn-squad+transfermarkt-photos': t('prediction.teamSquad.sourceEspnTransfermarkt'),
  'api-football': 'API-Football'
}[squadSource.value] || squadSource.value || t('prediction.teamSquad.statusPending')))
const syncedLabel = computed(() => formatDateTime(syncedAt.value) || t('prediction.teamSquad.syncedPending'))
const squadStatusKeys = {
  AVAILABLE: 'prediction.teamSquad.statusAvailable',
  EMPTY: 'prediction.teamSquad.statusEmpty',
  NOT_CONFIGURED: 'prediction.teamSquad.statusNotConfigured',
  REQUEST_FAILED: 'prediction.teamSquad.statusRequestFailed',
  QUOTA_LIMITED: 'prediction.teamSquad.statusQuotaLimited'
}
const squadLabel = computed(() => cacheState.value === 'STALE'
  ? t('prediction.teamSquad.statusStale')
  : cacheState.value === 'CACHED'
    ? t('prediction.teamSquad.statusCached')
    : squadStatusKeys[squadStatus.value]
      ? t(squadStatusKeys[squadStatus.value])
      : t('prediction.teamSquad.statusPending'))
const squadTone = computed(() => ['STALE', 'CACHED'].includes(cacheState.value) ? 'warning' : squadStatus.value === 'AVAILABLE' ? 'success' : squadStatus.value === 'EMPTY' ? 'info' : 'warning')
const playerPosition = player => String(player?.position || player?.positionLabel || '')
const playerName = player => displayPersonName(
  player?.name || t('prediction.teamSquad.unknownPlayer'),
  player?.playerDisplayName || player?.displayName || player?.nameEn
)
const playerPositionLabel = player => displayPositionName(player?.position, player?.positionLabel) || t('prediction.teamSquad.positionUnknown')
const squadGroups = computed(() => {
  const definitions = [
    { key: 'goalkeeper', label: t('prediction.teamSquad.goalkeeper'), test: /goalkeeper|keeper|门将/i },
    { key: 'defender', label: t('prediction.teamSquad.defender'), test: /defender|defence|defense|back|后卫/i },
    { key: 'midfielder', label: t('prediction.teamSquad.midfielder'), test: /midfielder|midfield|中场|前腰|后腰/i },
    { key: 'forward', label: t('prediction.teamSquad.forward'), test: /forward|attacker|striker|winger|前锋|边锋|中锋/i }
  ]
  const groups = definitions.map(item => ({ ...item, items: [] }))
  const other = { key: 'other', label: t('prediction.teamSquad.other'), items: [] }
  squad.value.forEach(player => (groups.find(group => group.test.test(playerPosition(player))) || other).items.push(player))
  return [...groups, other].filter(group => group.items.length)
})
const positionKey = value => {
  const text = String(value || '')
  if (/goalkeeper|keeper|门将/i.test(text)) return 'goalkeeper'
  if (/defender|defence|defense|back|后卫/i.test(text)) return 'defender'
  if (/midfielder|midfield|中场|前腰|后腰/i.test(text)) return 'midfielder'
  if (/forward|attacker|striker|winger|前锋|边锋|中锋/i.test(text)) return 'forward'
  return 'other'
}
const filteredSquad = computed(() => {
  const keyword = playerKeyword.value.trim().toLowerCase()
  return squad.value.filter(player => {
    const keywordOk = !keyword || [playerName(player), player.position, player.positionLabel, player.nationality, player.number]
      .some(value => String(value || '').toLowerCase().includes(keyword))
    return keywordOk && (positionFilter.value === 'all' || positionKey(playerPosition(player)) === positionFilter.value)
  })
})
const filteredSquadGroups = computed(() => {
  const allowed = new Set(filteredSquad.value.map(player => playerKey(player)))
  return squadGroups.value.map(group => ({ ...group, items: group.items.filter(player => allowed.has(playerKey(player))) })).filter(group => group.items.length)
})

const playerAgeLabel = (player) => {
  const age = Number(player?.age)
  if (!Number.isFinite(age) || age <= 0) return ''
  // Privacy: only surface ages for 18+ players; under-18 stays N/A.
  if (age < 18) return 'N/A'
  return t('prediction.teamSquad.age', { age: Math.trunc(age) })
}
const firstLetter = value => String(value || '?').trim().slice(0, 1).toUpperCase()
const playerKey = player => `${player.id || player.name}-${player.number || ''}`
const formatDateTime = value => {
  if (!value) return ''
  const date = new Date(value)
  return Number.isNaN(date.getTime())
    ? String(value).replace('T', ' ').slice(0, 16)
    : date.toLocaleString(locale.value === 'zh' ? 'zh-CN' : 'en-US', { timeZone: 'Asia/Shanghai', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' })
}
const normalizeList = value => Array.isArray(value) ? value : Array.isArray(value?.response) ? value.response : []
const squadCacheKey = () => `chenfootball:squad:${locale.value}:${teamId.value || teamName.value}:${league.value}:${season.value || 'current'}`
const applySquadPayload = data => {
  squad.value = normalizeList(data).map(player => ({ ...player, photoBroken: false }))
  if (data?.teamDisplayName || data?.teamNameDisplay) teamDisplay.value = data.teamDisplayName || data.teamNameDisplay
  if (data?.leagueDisplay || data?.leagueDisplayName) leagueDisplay.value = data.leagueDisplay || data.leagueDisplayName
  squadStatus.value = data.status || (squad.value.length ? 'AVAILABLE' : 'EMPTY')
  squadMessage.value = data.message || ''
  squadSource.value = data.source || ''
  syncedAt.value = data.lastSyncedAt || ''
  cacheState.value = data.cacheState || ''
}

const loadTeamLogo = async () => {
  if (teamLogo.value || !league.value) return
  try {
    const result = await crawlerApi.getTeamsByLeague(league.value)
    const item = normalizeList(result).find(team => String(team.name || '').toLowerCase() === teamName.value.toLowerCase()
      || String(team.id || '') === teamId.value || String(team.teamId || '') === teamId.value)
    if (item?.logo) teamLogo.value = item.logo
  } catch (_) {
    // 队徽不是阵容主链路，失败时使用球队首字母占位。
  }
}

const syncRouteState = () => {
  teamName.value = String(route.query.name || route.params.teamId || '').trim()
  try { teamName.value = decodeURIComponent(teamName.value) } catch (_) {}
  // The route param is a name fallback, not a canonical favorite id. Using
  // it as the id made removal send an encoded team name while the row had
  // been stored under a provider id (or vice versa).
  teamId.value = String(route.query.teamId || '').trim()
  teamLogo.value = String(route.query.logo || '')
  teamDisplay.value = String(route.query.teamDisplayName || route.query.teamDisplay || route.query.nameDisplay || '').trim()
  league.value = String(route.query.league || '英超')
  leagueDisplay.value = String(route.query.leagueDisplay || route.query.leagueDisplayName || '').trim()
  season.value = String(route.query.season || '')
}

const handleTeamLogoError = () => { teamLogo.value = '' }

const handlePlayerPhotoError = (player) => {
  // 头像源偶尔会因地区/CDN 缓存返回 404，立即切换到首字母头像，
  // 避免用户看到破图或反复重试。
  player.photoBroken = true
}

const loadSquad = async (force = false) => {
  if (!teamName.value) return
  loading.value = true
  error.value = ''
  cacheState.value = ''
  try {
    if (!force) {
      try {
        const cached = JSON.parse(sessionStorage.getItem(squadCacheKey()) || 'null')
        if (cached?.savedAt && Date.now() - Number(cached.savedAt) < 15 * 60 * 1000 && cached.payload) {
          applySquadPayload({ ...cached.payload, cacheState: cached.payload.cacheState || 'CACHED' })
          loading.value = false
          return
        }
      } catch (_) { /* ignore malformed browser cache */ }
    }
    const squadResult = await crawlerApi.getTeamSquad(teamName.value, league.value, teamId.value, Number(String(season.value).slice(0, 4)) || new Date().getFullYear(), { params: force ? { force: true } : {} })
    const data = squadResult?.data || squadResult || {}
    applySquadPayload(data)
    try { sessionStorage.setItem(squadCacheKey(), JSON.stringify({ savedAt: Date.now(), payload: data })) } catch (_) { /* storage may be disabled */ }
  } catch (err) {
    error.value = err?.message || t('prediction.teamSquad.teamUnavailable')
    squadStatus.value = 'REQUEST_FAILED'
    squadMessage.value = t('prediction.teamSquad.squadRequestFailed')
  } finally {
    loading.value = false
  }
}

const loadFavorites = async () => {
  if (!userStore.token) { favorites.value = []; favorited.value = false; return }
  favorites.value = await favoriteApi.list().then(value => Array.isArray(value) ? value : value?.items || value?.data || []).catch(() => [])
  favorited.value = favoriteRecords.value.length > 0
}
const normalizeFavoriteIdentity = value => String(value || '').trim().toLowerCase()
const isCurrentFavorite = item => {
  const currentIds = [teamId.value, teamName.value].map(normalizeFavoriteIdentity).filter(Boolean)
  return [item?.teamId, item?.teamName].map(normalizeFavoriteIdentity).some(value => value && currentIds.includes(value))
}
const toggleFavorite = async () => {
  if (!userStore.token) { userStore.openAuthDialog(route.fullPath); return }
  try {
    if (favorited.value && favoriteRecords.value.length) {
      // Clean up both legacy name-keyed rows and newer provider-id rows so a
      // previous identity migration cannot leave the button stuck on “已关注”.
      await Promise.all(favoriteRecords.value.map(item => favoriteApi.remove(item.teamId || item.teamName || teamName.value)))
      ElMessage.success(t('prediction.teamSquad.unfollowed'))
    } else {
      const id = teamId.value || teamName.value
      const result = await favoriteApi.add(id, teamName.value, { teamLogo: teamLogo.value, leagueName: league.value })
      if (result?.ok === false) throw new Error(result.message || t('prediction.teamSquad.followFailed'))
      ElMessage.success(t('prediction.teamSquad.followSaved'))
    }
    await loadFavorites()
  } catch (err) { ElMessage.warning(err?.message || t('prediction.teamSquad.followActionFailed')) }
}
const backToCompetitions = () => router.push({ path: '/competitions', query: { league: league.value, season: season.value } })
const goTeamMatches = () => router.push({ path: '/matches', query: { team: teamName.value, league: league.value } })
onMounted(async () => { syncRouteState(); await Promise.all([loadSquad(), loadFavorites(), loadTeamLogo()]) })
watch(() => route.fullPath, async () => { syncRouteState(); await Promise.all([loadSquad(), loadFavorites(), loadTeamLogo()]) })
watch(locale, () => loadSquad(true))
watch(() => userStore.token, loadFavorites)
</script>

<style scoped>
.main-content { max-width: 1180px; margin: 0 auto; padding: 24px; }
.page-toolbar { display:flex; justify-content:space-between; align-items:center; gap:12px; margin-bottom:16px; }
.toolbar-actions { display:flex; gap:8px; }
.team-hero { display:flex; justify-content:space-between; align-items:center; gap:20px; padding:20px 24px; border:1px solid var(--ff-border); border-radius:var(--ff-radius-lg); background:var(--ff-surface); }
.team-identity { display:flex; align-items:center; gap:16px; min-width:0; }.team-logo { width:80px; height:80px; object-fit:contain; flex:none; }.team-logo.placeholder { display:inline-flex; align-items:center; justify-content:center; border-radius:16px; background:var(--ff-primary-soft); color:var(--ff-primary); font-size:30px; font-weight:800; }.team-identity h1 { margin:6px 0; color:var(--ff-ink); font-size:clamp(28px,4vw,42px); letter-spacing:-.05em; }.team-identity p { margin:0; color:var(--ff-text-muted); }.hero-actions { display:flex; align-items:center; gap:10px; flex-wrap:wrap; }
.info-strip { display:grid; grid-template-columns:repeat(3,minmax(120px,auto)) minmax(260px,1fr); gap:10px; align-items:center; margin:16px 0; padding:14px 16px; border:1px solid var(--ff-border); border-radius:var(--ff-radius-md); background:var(--ff-surface); }.info-strip div:not(.info-note) { display:flex; flex-direction:column; gap:3px; }.info-strip span { color:var(--ff-text-faint); font-size:11px; }.info-strip strong { color:var(--ff-text-strong); font-family:var(--ff-mono); font-size:14px; }.info-note { color:var(--ff-text-muted); font-size:12px; line-height:1.6; }
.squad-groups { display:flex; flex-direction:column; gap:22px; }.group-heading { display:flex; align-items:center; gap:10px; margin-bottom:10px; color:var(--ff-text-strong); }.group-heading span { color:var(--ff-text-faint); font-size:12px; }.player-grid { display:grid; grid-template-columns:repeat(4,minmax(0,1fr)); gap:10px; }.player-card { display:flex; align-items:center; gap:10px; min-width:0; padding:12px; border:1px solid var(--ff-border); border-radius:var(--ff-radius-md); background:var(--ff-surface-quiet); }.player-card img,.player-avatar { width:42px; height:42px; flex:none; object-fit:cover; border-radius:50%; }.player-avatar { display:inline-flex; align-items:center; justify-content:center; background:var(--ff-primary-soft); color:var(--ff-primary); font-weight:800; }.player-copy { display:flex; flex-direction:column; gap:3px; min-width:0; }.player-copy strong { overflow:hidden; color:var(--ff-text-strong); text-overflow:ellipsis; white-space:nowrap; }.player-copy span,.player-copy small { color:var(--ff-text-muted); font-size:11px; }.empty-state { display:flex; align-items:center; gap:14px; padding:22px 10px; color:var(--ff-text-muted); }.empty-mark { color:var(--ff-primary); font-size:30px; }.empty-state strong { color:var(--ff-text-strong); }.empty-state p { margin:6px 0 12px; }
.squad-toolbar { display:flex; align-items:center; gap:8px; margin:-2px 0 16px; }.squad-toolbar .el-input { width:240px; }.squad-toolbar .el-select { width:150px; }.squad-toolbar > span { margin-left:auto; color:var(--ff-text-muted); font:11px var(--ff-mono); }
@media (max-width: 900px) { .info-strip { grid-template-columns:repeat(3,1fr); }.info-note { grid-column:1/-1; }.player-grid { grid-template-columns:repeat(2,minmax(0,1fr)); } }
@media (max-width: 620px) { .main-content { padding:16px; }.page-toolbar,.team-hero { align-items:flex-start; flex-direction:column; }.team-hero { padding:20px; }.team-logo { width:62px; height:62px; }.info-strip { grid-template-columns:repeat(2,1fr); }.player-grid { grid-template-columns:1fr; }.squad-toolbar { align-items:stretch; flex-direction:column; }.squad-toolbar .el-input,.squad-toolbar .el-select { width:100%; }.squad-toolbar > span { margin-left:0; } }
.player-number { margin-left:6px; color:var(--ff-primary); font:11px var(--ff-mono); }
.team-page-head { display:flex; justify-content:space-between; align-items:center; gap:16px; padding:4px 2px 14px; border-bottom:1px solid var(--ff-border); }
.team-page-head .team-identity { gap:12px; }
.team-page-head .team-logo { width:52px; height:52px; }
.team-page-head .team-logo.placeholder { border-radius:12px; font-size:22px; }
.team-page-head .team-identity h1 { margin:4px 0 0; font-size:clamp(24px,3vw,32px); }
.team-page-head .hero-actions { flex:none; }
@media (max-width:620px) {
  .team-page-head { align-items:flex-start; flex-direction:column; padding:2px 0 12px; }
  .team-page-head .hero-actions { width:100%; justify-content:space-between; }
}
</style>
