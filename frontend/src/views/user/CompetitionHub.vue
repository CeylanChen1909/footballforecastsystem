<template>
  <div class="competition-page ff-page-shell">
    <AppTopNav
      title="ChenFootball"
      subtitle="赛事资料"
      :brand-icon="Trophy"
      active-path="/competitions"
    />

    <el-main id="app-main" class="main-content" tabindex="-1">
      <section class="hub-grid">
        <PageSection class="standings-panel" title="联赛积分榜" subtitle="以当前已采集赛季为准，点击球队查看资料">
          <template #actions>
            <el-select v-model="selectedLeague" class="league-select" size="small" aria-label="选择联赛" @change="loadLeagueData">
              <el-option v-for="league in leagueOptions" :key="league.value" :label="league.label" :value="league.value" />
            </el-select>
            <el-select v-model="selectedSeason" class="season-select" size="small" aria-label="选择赛季" :disabled="!seasonOptions.length" @change="loadLeagueData">
              <el-option v-for="season in seasonOptions" :key="season" :label="season" :value="season" />
            </el-select>
            <el-button :icon="Refresh" circle aria-label="重新读取积分榜" title="重新读取积分榜快照" :loading="loading" @click="loadLeagueData" />
            <el-button v-if="isAdmin" text type="primary" size="small" :loading="loading" @click="refreshStandings">管理员同步</el-button>
            <el-select v-model="standingSort" size="small" aria-label="积分榜排序"><el-option label="按积分" value="points" /><el-option label="按排名" value="rank" /></el-select>
            <el-tag size="small" effect="plain" type="info">{{ standings.length }} 队</el-tag>
            <el-tag v-if="quality.statusText" size="small" effect="plain" :type="qualityTagType">{{ quality.statusText }}</el-tag>
          </template>
          <PageState v-if="loading" type="loading" title="正在加载积分榜..." :size="36" />
          <PageState v-else-if="loadError" type="error" title="积分榜加载失败" :description="loadError" action-text="重试" @action="loadLeagueData" />
          <div v-else-if="standings.length && standingDataState !== 'INCOMPLETE'" class="standings-data-wrap">
            <el-alert v-if="standingDataState !== 'READY'" :title="standingDataState === 'PRESEASON' ? '赛季尚未产生积分' : '积分数据不完整'" :description="qualityMessage || '当前仅显示参赛名单或缓存快照，积分列不会被当作真实成绩。'" type="info" :closable="false" show-icon />
            <div v-if="zoneRules.zones?.length" class="zone-legend" aria-label="积分榜区域说明">
              <span v-for="rule in zoneRules.zones" :key="`${rule.code}-${rule.from}-${rule.to}`" class="zone-legend-item">
                <span class="zone-label" :class="`zone-${String(rule.code || '').toLowerCase()}`">{{ rule.label }}</span>
                <span class="zone-range">{{ rule.from === rule.to ? `第 ${rule.from} 名` : `第 ${rule.from}–${rule.to} 名` }}</span>
              </span>
              <span v-if="zoneRules.note" class="zone-note">{{ zoneRules.note }}</span>
            </div>
            <el-table :data="sortedStandings" class="standings-table" size="small" row-key="rank">
            <el-table-column prop="rank" label="#" width="48" align="center">
              <template #default="scope"><span class="rank-number" :class="rankClass(scope.row)">{{ scope.row.rank }}</span></template>
            </el-table-column>
            <el-table-column label="区域" width="112" align="center">
              <template #default="scope"><span v-if="scope.row.zone" class="zone-label" :class="`zone-${String(scope.row.zone).toLowerCase()}`">{{ scope.row.zoneLabel || zoneLabel(scope.row.zone) }}</span><span v-else class="zone-label is-empty">—</span></template>
            </el-table-column>
            <el-table-column label="球队" min-width="180">
              <template #default="scope">
                <button type="button" class="team-cell" :aria-label="`查看${scope.row.team?.name || '未知球队'}资料`" @click="openTeam(scope.row.team)">
                  <img v-if="scope.row.team?.logo" :src="scope.row.team.logo" alt="" aria-hidden="true" @error="markLogoBroken(scope.row.team)" />
                  <span v-else class="mini-logo">{{ firstLetter(scope.row.team?.name) }}</span>
                  <span>{{ scope.row.team?.name || '未知球队' }}</span>
                </button>
              </template>
            </el-table-column>
            <el-table-column prop="played" label="赛" width="52" align="center" />
            <el-table-column prop="win" label="胜" width="52" align="center" />
            <el-table-column prop="draw" label="平" width="52" align="center" />
            <el-table-column prop="loss" label="负" width="52" align="center" />
            <el-table-column prop="goalDifference" label="净胜" width="64" align="center" />
            <el-table-column label="近况" width="118" align="center">
              <template #default="scope"><span class="form-strip" :aria-label="scope.row.recentForm || '暂无近期战绩'">{{ scope.row.recentForm || '—' }}</span></template>
            </el-table-column>
            <el-table-column prop="points" label="积分" width="64" align="center">
              <template #default="scope"><strong>{{ scope.row.points ?? '—' }}</strong></template>
            </el-table-column>
            </el-table>
          </div>
          <PageState v-else-if="standings.length" title="积分数据尚未形成" description="当前只有参赛名单，没有可验证的积分、胜平负或净胜球数据；我们不会用一整页的 0 伪装成真实榜单。" />
          <PageState v-else title="暂无积分榜" :description="qualityMessage || '该联赛当前没有已同步的榜单数据，可稍后刷新或切换联赛。'" />
        </PageSection>

        <PageSection class="clubs-panel" title="参赛俱乐部" subtitle="从联赛名单进入球队资料，不再依赖资讯聚合">
          <template #actions>
            <el-input v-model="clubKeyword" class="club-search" clearable size="small" placeholder="搜索球队" aria-label="搜索球队" />
          </template>
          <div v-if="filteredClubs.length" class="club-grid">
            <button v-for="club in filteredClubs" :key="clubKey(club)" type="button" class="club-card" :title="club.name" :aria-label="`查看${club.name}球队资料`" @click="openTeam(club)">
              <img v-if="club.logo && !club.logoBroken" :src="club.logo" alt="" aria-hidden="true" @error="markLogoBroken(club)" />
              <span v-else class="club-logo-placeholder">{{ firstLetter(club.name) }}</span>
              <span class="club-name">{{ club.name }}</span>
              <span class="club-meta">{{ clubRank(club) ? `第 ${clubRank(club)} 名` : '查看球队资料' }}</span>
              <span class="club-arrow" aria-hidden="true">→</span>
            </button>
          </div>
          <PageState v-else title="暂无俱乐部" description="积分榜同步后，参赛俱乐部会自动出现在这里。" />
        </PageSection>
      </section>

    </el-main>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Refresh, Trophy } from '@element-plus/icons-vue'
import AppTopNav from '../../components/layout/AppTopNav.vue'
import PageSection from '../../components/layout/PageSection.vue'
import PageState from '../../components/layout/PageState.vue'
import { analyticsApi, crawlerApi } from '../../api'
import { useUserStore } from '../../stores/user'

const router = useRouter()
const userStore = useUserStore()

// 只展示当前主爬虫源具备稳定积分榜路径的生产联赛。
// 欧冠等未配置 BBC standings 路径的赛事不能伪装成“可同步”的联赛。
const leagueOptions = [
  { value: '英超', label: '英超', id: 39 },
  { value: '西甲', label: '西甲', id: 140 },
  { value: '意甲', label: '意甲', id: 135 },
  { value: '德甲', label: '德甲', id: 78 },
  { value: '法甲', label: '法甲', id: 61 },
  { value: '荷甲', label: '荷甲', id: 88 },
  { value: '葡超', label: '葡超', id: 94 },
  { value: '英冠', label: '英冠', id: 40 }
]
const selectedLeague = ref('英超')
const selectedSeason = ref('')
const seasonOptions = ref([])
const loading = ref(false)
const loadError = ref('')
const standings = ref([])
const standingSort = ref('points')
const clubsFromApi = ref([])
const clubKeyword = ref('')
const zoneRules = ref({ zones: [], note: '' })
const quality = ref({ status: 'UNKNOWN', statusText: '', message: '', source: '', lastSyncedAt: '', ageMinutes: -1 })
const isAdmin = computed(() => ['ADMIN', 'SUPER_ADMIN'].includes(userStore.role))

const normalizeList = (value) => {
  if (Array.isArray(value)) return value
  if (Array.isArray(value?.response)) return value.response
  if (Array.isArray(value?.items)) return value.items
  if (Array.isArray(value?.data?.response)) return value.data.response
  return []
}
const normalizeStanding = (row) => ({
  ...row,
  rank: Number(row?.rank || row?.position || 0),
  team: row?.team || { name: row?.teamName, logo: row?.teamLogo, id: row?.teamId },
  win: Number(row?.win ?? row?.wins ?? 0),
  draw: Number(row?.draw ?? row?.draws ?? 0),
  loss: Number(row?.loss ?? row?.losses ?? 0)
})
const normalizeClub = (club) => {
  const team = club?.team || club
  return { id: team?.id || club?.id || '', canonicalKey: team?.canonicalKey || club?.canonicalKey || '', name: team?.name || club?.name || '', logo: team?.logo || club?.logo || '', logoBroken: false }
}

const clubs = computed(() => {
  const map = new Map()
  standings.value.forEach(row => {
    const club = normalizeClub(row.team)
    if (club.name) map.set(club.canonicalKey || club.name, { ...club, rank: row.rank, points: row.points, zone: row.zone || '' })
  })
  if (!standings.value.length) {
    clubsFromApi.value.forEach(item => {
      const club = normalizeClub(item)
      if (club.name && !map.has(club.canonicalKey || club.name)) map.set(club.canonicalKey || club.name, club)
    })
  }
  return [...map.values()].sort((a, b) => (a.rank || 999) - (b.rank || 999) || a.name.localeCompare(b.name, 'zh-CN'))
})
const filteredClubs = computed(() => {
  const keyword = clubKeyword.value.trim().toLowerCase()
  return keyword ? clubs.value.filter(club => club.name.toLowerCase().includes(keyword)) : clubs.value
})
const sortedStandings = computed(() => [...standings.value].sort((a, b) => standingSort.value === 'rank'
  ? Number(a.rank || 999) - Number(b.rank || 999)
  : Number(b.points || 0) - Number(a.points || 0) || Number(a.rank || 999) - Number(b.rank || 999)))
const standingDataState = computed(() => {
  if (!standings.value.length) return 'EMPTY'
  const hasPlayedData = standings.value.some(row => Number(row.played || 0) > 0 || Number(row.points || 0) > 0 || Number(row.win || 0) > 0 || Number(row.draw || 0) > 0 || Number(row.loss || 0) > 0)
  if (hasPlayedData) return 'READY'
  // 后端使用 AVAILABLE/STALE/NO_DATA；兼容旧实例的 NORMAL，避免
  // 新赛季全 0 的真实参赛名单被误判为“没有积分榜”并整表隐藏。
  const status = String(quality.value.status || '').toUpperCase()
  return ['AVAILABLE', 'NORMAL', 'PRESEASON', 'STALE'].includes(status) && quality.value.lastSyncedAt
    ? (status === 'STALE' ? 'STALE' : 'PRESEASON')
    : 'INCOMPLETE'
})
const qualityMessage = computed(() => quality.value.message || '')
const qualityTagType = computed(() => ({ PRESEASON: 'warning', STALE: 'warning', AVAILABLE: 'success', NORMAL: 'success' }[String(quality.value.status || '').toUpperCase()] || 'info'))

const rankClass = row => {
  const zone = String(row?.zone || '').toUpperCase()
  if (zone.startsWith('CHAMPIONS_') || zone.startsWith('EUROPA_') || zone.startsWith('CONFERENCE_') || zone === 'PROMOTION' || zone === 'PROMOTION_PLAYOFF') return 'rank-top'
  if (zone.startsWith('RELEGATION')) return 'rank-bottom'
  return ''
}
const zoneLabel = zone => ({
  CHAMPIONS_LEAGUE: '欧冠正赛',
  CHAMPIONS_LEAGUE_QUALIFYING: '欧冠资格赛',
  EUROPA_LEAGUE: '欧联正赛',
  EUROPA_LEAGUE_QUALIFYING: '欧联资格赛',
  CONFERENCE_LEAGUE: '欧协联正赛',
  CONFERENCE_LEAGUE_QUALIFYING: '欧协联资格赛',
  CONFERENCE_PLAYOFF: '欧协联附加赛',
  PROMOTION: '直接升级',
  PROMOTION_PLAYOFF: '升级附加赛',
  RELEGATION_PLAYOFF: '降级附加赛',
  RELEGATION: '降级',
  // Compatibility with snapshots produced by older API instances.
  EUROPA: '欧战区'
}[zone] || '')
const firstLetter = name => String(name || '?').trim().slice(0, 1).toUpperCase()
const clubKey = club => `${club.id || 'name'}-${club.name}`
const clubRank = club => club.rank || standings.value.find(row => row.team?.name === club.name)?.rank || 0
const markLogoBroken = item => { item.logoBroken = true; item.logo = '' }
const loadLeagueData = async () => {
  loading.value = true
  loadError.value = ''
  try {
    const seasonsResult = await crawlerApi.getStandingSeasons(selectedLeague.value).catch(() => [])
    const seasons = normalizeList(seasonsResult).map(String).filter(Boolean)
    seasonOptions.value = seasons
    if (!selectedSeason.value || !seasons.includes(selectedSeason.value)) selectedSeason.value = seasons[0] || ''
    const [standingsResult, clubsResult] = await Promise.allSettled([
      crawlerApi.getStandingsByLeagueName(selectedLeague.value, selectedSeason.value),
      crawlerApi.getTeamsByLeague(selectedLeague.value)
    ])
    const nextStandings = standingsResult.status === 'fulfilled' ? normalizeList(standingsResult.value).map(normalizeStanding).filter(row => row.team?.name) : []
    const nextClubs = clubsResult.status === 'fulfilled' ? normalizeList(clubsResult.value) : []
    if (!nextStandings.length && !nextClubs.length && standingsResult.status === 'rejected' && clubsResult.status === 'rejected') {
      throw standingsResult.reason || clubsResult.reason || new Error('资料服务暂时不可用')
    }
    standings.value = nextStandings
    clubsFromApi.value = nextClubs
    quality.value = standingsResult.status === 'fulfilled'
      ? (standingsResult.value?.dataQuality || standingsResult.value?.data?.dataQuality || quality.value)
      : { status: 'SYNC_FAILED', statusText: '同步失败', message: standingsResult.reason?.message || '积分榜请求失败', source: '' }
    zoneRules.value = standingsResult.status === 'fulfilled'
      ? (standingsResult.value?.zoneRules || standingsResult.value?.data?.zoneRules || { zones: [], note: '' })
      : { zones: [], note: '' }
    analyticsApi.track('competition_viewed', { page: '/competitions', entityType: 'league', entityId: selectedLeague.value, properties: { season: selectedSeason.value, standings: nextStandings.length, clubs: nextClubs.length, dataStatus: quality.value.status } }).catch(() => {})
  } catch (error) {
    standings.value = []
    clubsFromApi.value = []
    zoneRules.value = { zones: [], note: '' }
    loadError.value = error?.message || '请检查后端服务或数据同步状态'
    quality.value = { status: 'SYNC_FAILED', statusText: '同步失败', message: loadError.value, source: '' }
  } finally {
    loading.value = false
  }
}

const refreshStandings = async () => {
  if (!isAdmin.value) return
  loading.value = true
  try {
    await crawlerApi.refreshStandings(selectedLeague.value)
    ElMessage.success('积分榜同步任务已完成')
    await loadLeagueData()
  } catch (error) {
    ElMessage.warning(error?.message || '积分榜同步失败，请检查数据源状态')
  } finally {
    loading.value = false
  }
}

const openTeam = (team) => {
  const next = normalizeClub(team)
  if (!next.name) return
  analyticsApi.track('competition_team_opened', { page: '/competitions', entityType: 'team', entityId: next.id || next.name, properties: { league: selectedLeague.value, season: selectedSeason.value } }).catch(() => {})
  router.push({
    name: 'TeamSquad',
    params: { teamId: String(next.id || next.name) },
    query: { name: next.name, teamId: String(next.id || ''), logo: next.logo || '', league: selectedLeague.value, season: selectedSeason.value }
  })
}

onMounted(loadLeagueData)
</script>

<style scoped>
.main-content { max-width: 1440px; margin: 0 auto; padding: 24px; }
.competition-hero { display:flex; justify-content:space-between; align-items:flex-end; gap:24px; padding:20px 22px; border:1px solid var(--ff-border); border-radius:var(--ff-radius-lg); background:var(--ff-surface); }
.hero-copy { max-width:720px; }
.hero-copy h1 { margin:8px 0 8px; color:var(--ff-ink); font-size:clamp(28px,4vw,46px); letter-spacing:-.06em; }
.hero-copy p { margin:0; color:var(--ff-text-muted); line-height:1.7; }
.hero-controls { display:flex; gap:10px; flex-wrap:wrap; min-width:270px; }
.hero-controls label { display:flex; flex-direction:column; gap:6px; color:var(--ff-text-faint); font-size:11px; }
.league-select { width:150px; }
.season-select { width:130px; }
.overview-grid { display:grid; grid-template-columns:repeat(3,minmax(0,1fr)); gap:12px; margin:16px 0; }
.overview-card { display:flex; flex-direction:column; gap:5px; padding:16px 18px; border:1px solid var(--ff-border); border-radius:var(--ff-radius-md); background:var(--ff-surface); box-shadow:var(--ff-shadow-sm); }
.overview-card span,.overview-card small { color:var(--ff-text-muted); font-size:12px; }
.overview-card strong { color:var(--ff-text-strong); font-size:24px; font-family:var(--ff-mono); }
.overview-card strong.is-good { color:var(--ff-primary); }.overview-card strong.is-bad { color:var(--ff-danger); }.overview-card strong.is-muted { color:var(--ff-text-muted); }.overview-card strong.is-stale { color:var(--ff-warning); }
.hub-grid { display:grid; grid-template-columns:minmax(0,1.25fr) minmax(360px,.75fr); gap:16px; align-items:start; }
.standings-table { width:100%; }
.standings-data-wrap { display:flex; flex-direction:column; gap:12px; }
.standings-table :deep(.el-table__inner-wrapper::before) { display:none; }
.rank-number { display:inline-flex; align-items:center; justify-content:center; width:24px; height:24px; border-radius:6px; color:var(--ff-text-muted); font-family:var(--ff-mono); font-size:12px; }
.rank-number.rank-top { color:var(--ff-primary); background:var(--ff-primary-soft); }.rank-number.rank-bottom { color:var(--ff-danger); background:rgba(220,72,72,.08); }
.zone-label { display:inline-flex; align-items:center; justify-content:center; padding:2px 6px; border-radius:999px; color:var(--ff-primary); background:var(--ff-primary-soft); font-size:10px; white-space:nowrap; }
.zone-label.zone-champions_league_qualifying { color:#3a69a3; background:rgba(58,105,163,.1); }
.zone-label.zone-europa, .zone-label.zone-europa_league, .zone-label.zone-europa_league_qualifying { color:#8a641b; background:rgba(191,145,51,.12); }
.zone-label.zone-conference_league, .zone-label.zone-conference_league_qualifying, .zone-label.zone-conference_playoff { color:#5f6b88; background:rgba(95,107,136,.12); }
.zone-label.zone-promotion, .zone-label.zone-promotion_playoff { color:var(--ff-primary); background:var(--ff-primary-soft); }
.zone-label.zone-relegation_playoff { color:#a15c26; background:rgba(161,92,38,.1); }
.zone-label.zone-relegation { color:var(--ff-danger); background:rgba(220,72,72,.08); }
.zone-label.is-empty { color:var(--ff-text-faint); background:transparent; }
.zone-legend { display:flex; align-items:center; flex-wrap:wrap; gap:7px 12px; padding:2px 2px 0; color:var(--ff-text-muted); font-size:11px; }
.zone-legend-item { display:inline-flex; align-items:center; gap:5px; }
.zone-range { color:var(--ff-text-faint); white-space:nowrap; }
.zone-note { flex:1 0 100%; color:var(--ff-text-faint); line-height:1.5; }
.form-strip { display:inline-flex; max-width:100%; overflow:hidden; color:var(--ff-text-muted); font-family:var(--ff-mono); font-size:11px; letter-spacing:1px; white-space:nowrap; }
.team-cell { display:flex; align-items:center; gap:8px; width:100%; border:0; background:transparent; color:var(--ff-text); font-weight:600; text-align:left; cursor:pointer; }
.team-cell:hover { color:var(--ff-primary); }.team-cell img,.mini-logo { width:26px; height:26px; object-fit:contain; flex:none; }.mini-logo { display:inline-flex; align-items:center; justify-content:center; border-radius:6px; background:var(--ff-primary-soft); color:var(--ff-primary); font-size:11px; }
.club-search { width:150px; }.club-grid { display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); gap:8px; }
.club-card { display:flex; align-items:center; gap:9px; min-width:0; padding:10px; border:1px solid var(--ff-border); border-radius:var(--ff-radius-md); background:var(--ff-surface-quiet); color:var(--ff-text); text-align:left; cursor:pointer; transition:border-color var(--ff-transition-fast),background var(--ff-transition-fast); }
.club-card:hover,.club-card:focus-visible { border-color:var(--ff-primary); background:var(--ff-primary-soft); outline:none; }.club-card img,.club-logo-placeholder { width:32px; height:32px; object-fit:contain; flex:none; }.club-logo-placeholder { display:inline-flex; align-items:center; justify-content:center; border-radius:8px; background:var(--ff-bg-alt); color:var(--ff-primary); font-weight:700; }.club-name { min-width:0; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; font-size:13px; font-weight:700; }.club-meta { margin-left:auto; color:var(--ff-text-faint); font-size:10px; white-space:nowrap; }.club-arrow { margin-left:auto; color:var(--ff-primary); font-size:18px; opacity:.6; transition:opacity var(--ff-transition-fast); }.club-card:hover .club-arrow,.club-card:focus-visible .club-arrow { opacity:1; }
@media (max-width: 980px) { .hub-grid { grid-template-columns:1fr; } }
@media (max-width: 680px) { .main-content { padding:12px; }.competition-hero { align-items:flex-start; flex-direction:column; padding:20px; }.hero-controls { width:100%; }.hero-controls label,.league-select,.season-select { flex:1; width:auto; }.overview-grid { grid-template-columns:1fr; }.club-grid { grid-template-columns:1fr; }.club-meta { display:none; } }
.competition-hero { padding: 20px 24px; align-items: center; }
.hero-copy h1 { font-size: clamp(25px, 3.2vw, 38px); }
.overview-grid { margin: 12px 0; }
.club-card { min-height: 54px; }
.club-name { overflow: visible; text-overflow: clip; white-space: normal; line-height: 1.3; }
@media (max-width: 680px) { .competition-hero { padding: 16px; } .overview-card strong { font-size: 21px; } }

/* 赛事资料页头只呈现当前联赛、赛季和必要筛选。 */
</style>
