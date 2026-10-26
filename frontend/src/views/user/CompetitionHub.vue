<template>
  <div class="competition-page ff-page-shell">
    <AppTopNav
      title="ChenFootball"
      :subtitle="t('competition.subtitle')"
      :brand-icon="Trophy"
      active-path="/competitions"
    />

    <el-main id="app-main" class="main-content" tabindex="-1">
      <section class="hub-grid">
        <PageSection class="standings-panel" :title="t('competition.standingsTitle')" :subtitle="t('competition.standingsSubtitle')">
          <template #actions>
            <div class="standings-toolbar" :aria-label="t('competition.standingsToolbarAria')">
              <div class="toolbar-group toolbar-primary">
                <label class="control-field">
                  <span>{{ t('competition.leagueLabel') }}</span>
                  <el-select v-model="selectedLeague" class="league-select" size="small" :aria-label="t('competition.selectLeague')" @change="loadLeagueData">
                    <el-option v-for="league in leagueOptions" :key="league.value" :label="league.label" :value="league.value" />
                  </el-select>
                </label>
                <label class="control-field">
                  <span>{{ t('competition.seasonLabel') }}</span>
                  <el-select v-model="selectedSeason" class="season-select" size="small" :aria-label="t('competition.selectSeason')" :disabled="!seasonOptions.length" @change="loadLeagueData">
                    <el-option v-for="season in seasonOptions" :key="season" :label="season" :value="season" />
                  </el-select>
                </label>
              </div>
              <div class="toolbar-group toolbar-secondary">
                <el-tag size="small" effect="plain" type="info">{{ loading ? '…' : t('competition.teamCount', { count: standings.length }) }}</el-tag>
                <el-tag v-if="qualityStatusLabel" size="small" effect="plain" :type="qualityTagType">{{ qualityStatusLabel }}</el-tag>
                <el-button :icon="Refresh" circle :aria-label="t('competition.reloadStandings')" :title="t('competition.reloadStandingsTitle')" :loading="loading" @click="loadLeagueData" />
                <el-button v-if="isAdmin" text type="primary" size="small" :loading="loading" @click="refreshStandings">{{ t('competition.adminSync') }}</el-button>
              </div>
            </div>
          </template>
          <div v-if="loading" class="standings-skeleton" role="status" aria-live="polite" aria-busy="true" :aria-label="t('competition.loadingStandings')">
            <div v-for="n in 8" :key="n" class="standings-skeleton-row">
              <i></i><b></b><em></em><em></em><em></em>
            </div>
          </div>
          <PageState v-else-if="loadError" type="error" :title="t('competition.standingsUnavailable')" :description="loadError || t('competition.standingsLoadFailed')" :action-text="t('competition.retry')" @action="loadLeagueData" />
          <div v-else-if="standings.length && standingDataState !== 'INCOMPLETE'" class="standings-data-wrap">
            <el-alert v-if="standingDataState !== 'READY'" :title="standingDataState === 'PRESEASON' || standingDataState === 'STALE' ? (isCupOrNationsLeague ? t('competition.noPointsYetCup') : t('competition.noPointsYetLeague')) : t('competition.incompleteStandings')" :description="qualityMessage || (isCupOrNationsLeague ? t('competition.cupPreseasonNote') : t('competition.leaguePreseasonNote'))" type="info" :closable="false" show-icon />
            <div v-if="zoneRules.zones?.length && !isCupOrNationsLeague" class="zone-legend" :aria-label="t('competition.zoneLegendAria')">
              <span v-for="rule in zoneRules.zones" :key="`${rule.code}-${rule.from}-${rule.to}`" class="zone-legend-item">
                <span class="zone-label" :class="`zone-${String(rule.code || '').toLowerCase()}`">{{ zoneLabel(rule.code) || rule.label }}</span>
                <span class="zone-range">{{ rule.from === rule.to ? t('competition.rankSingle', { rank: rule.from }) : t('competition.rankRange', { from: rule.from, to: rule.to }) }}</span>
              </span>
              <span v-if="zoneRules.note" class="zone-note">{{ zoneRules.note }}</span>
            </div>
            <p class="standings-scroll-hint" aria-hidden="true">{{ t('competition.scrollHint') }}</p>
            <div v-if="hasStandingGroups" class="standing-groups standing-groups-desktop">
              <div v-for="g in standingGroups" :key="g.key" class="standing-group">
                <h3 class="group-heading">{{ g.label }}</h3>
                <div class="standings-table-scroll" role="region" :aria-label="t('competition.groupTableAria', { group: g.label })">
                  <el-table :data="g.items" class="standings-table standings-table-desktop" size="small" :row-key="standingRowKey">
            <el-table-column prop="rank" label="#" width="48" align="center">
              <template #default="scope"><span class="rank-number" :class="rankClass(scope.row)">{{ scope.row.rank }}</span></template>
            </el-table-column>
            <el-table-column :label="t('competition.zoneColumn')" width="112" align="center">
              <template #default="scope"><span v-if="scope.row.zone" class="zone-label" :class="`zone-${String(scope.row.zone).toLowerCase()}`">{{ zoneLabel(scope.row.zone) || scope.row.zoneLabel }}</span><span v-else class="zone-label is-empty">—</span></template>
            </el-table-column>
            <el-table-column :label="t('competition.teamColumn')" min-width="180">
              <template #default="scope">
                <button type="button" class="team-cell" :aria-label="t('competition.viewTeamProfile', { name: teamLabel(scope.row.team) })" @click="openTeam(scope.row.team)">
                  <img v-if="scope.row.team?.logo" :src="getMediaAssetUrl(scope.row.team.logo)" alt="" aria-hidden="true" width="28" height="28" loading="lazy" decoding="async" @error="markLogoBroken(scope.row.team)" />
                  <span v-else class="mini-logo" :title="t('competition.noCrest')" :aria-label="t('competition.teamNoCrest', { name: teamLabel(scope.row.team) })">{{ firstLetter(teamLabel(scope.row.team)) }}</span>
                  <span class="team-name-text" :title="teamLabel(scope.row.team)">{{ teamLabel(scope.row.team) }}</span>
                </button>
              </template>
            </el-table-column>
            <el-table-column prop="played" :label="t('competition.playedColumn')" width="52" align="center" />
            <el-table-column prop="win" :label="t('competition.winColumn')" width="52" align="center" />
            <el-table-column prop="draw" :label="t('competition.drawColumn')" width="52" align="center" />
            <el-table-column prop="loss" :label="t('competition.lossColumn')" width="52" align="center" />
            <el-table-column prop="goalDifference" :label="t('competition.goalDifferenceColumn')" width="64" align="center" />
            <el-table-column :label="t('competition.formColumn')" width="118" align="center">
              <template #default="scope"><span class="form-strip" :aria-label="scope.row.recentForm || t('competition.noRecentForm')">{{ scope.row.recentForm || '—' }}</span></template>
            </el-table-column>
            <el-table-column prop="points" :label="t('competition.pointsColumn')" width="64" align="center">
              <template #default="scope"><strong>{{ scope.row.points ?? '—' }}</strong></template>
            </el-table-column>
                  </el-table>
                </div>
              </div>
            </div>
            <div v-else class="standings-table-scroll" role="region" :aria-label="t('competition.tableAria')">
              <el-table :data="standings" class="standings-table standings-table-desktop" size="small" :row-key="standingRowKey">
            <el-table-column prop="rank" label="#" width="48" align="center">
              <template #default="scope"><span class="rank-number" :class="rankClass(scope.row)">{{ scope.row.rank }}</span></template>
            </el-table-column>
            <el-table-column :label="t('competition.zoneColumn')" width="112" align="center">
              <template #default="scope"><span v-if="scope.row.zone" class="zone-label" :class="`zone-${String(scope.row.zone).toLowerCase()}`">{{ zoneLabel(scope.row.zone) || scope.row.zoneLabel }}</span><span v-else class="zone-label is-empty">—</span></template>
            </el-table-column>
            <el-table-column :label="t('competition.teamColumn')" min-width="180">
              <template #default="scope">
                <button type="button" class="team-cell" :aria-label="t('competition.viewTeamProfile', { name: teamLabel(scope.row.team) })" @click="openTeam(scope.row.team)">
                  <img v-if="scope.row.team?.logo" :src="getMediaAssetUrl(scope.row.team.logo)" alt="" aria-hidden="true" width="28" height="28" loading="lazy" decoding="async" @error="markLogoBroken(scope.row.team)" />
                  <span v-else class="mini-logo" :title="t('competition.noCrest')" :aria-label="t('competition.teamNoCrest', { name: teamLabel(scope.row.team) })">{{ firstLetter(teamLabel(scope.row.team)) }}</span>
                  <span class="team-name-text" :title="teamLabel(scope.row.team)">{{ teamLabel(scope.row.team) }}</span>
                </button>
              </template>
            </el-table-column>
            <el-table-column prop="played" :label="t('competition.playedColumn')" width="52" align="center" />
            <el-table-column prop="win" :label="t('competition.winColumn')" width="52" align="center" />
            <el-table-column prop="draw" :label="t('competition.drawColumn')" width="52" align="center" />
            <el-table-column prop="loss" :label="t('competition.lossColumn')" width="52" align="center" />
            <el-table-column prop="goalDifference" :label="t('competition.goalDifferenceColumn')" width="64" align="center" />
            <el-table-column :label="t('competition.formColumn')" width="118" align="center">
              <template #default="scope"><span class="form-strip" :aria-label="scope.row.recentForm || t('competition.noRecentForm')">{{ scope.row.recentForm || '—' }}</span></template>
            </el-table-column>
            <el-table-column prop="points" :label="t('competition.pointsColumn')" width="64" align="center">
              <template #default="scope"><strong>{{ scope.row.points ?? '—' }}</strong></template>
            </el-table-column>
              </el-table>
            </div>
            <div v-if="hasStandingGroups" class="standings-cards" :aria-label="t('competition.cardsAria')">
              <div v-for="g in standingGroups" :key="'cards-' + g.key" class="standing-group">
                <h3 class="group-heading">{{ g.label }}</h3>
                <button
                  v-for="row in g.items"
                  :key="'card-' + standingRowKey(row)"
                  type="button"
                  class="standing-card"
                  :aria-label="t('competition.viewTeamProfile', { name: teamLabel(row.team) })"
                  @click="openTeam(row.team)"
                >
                <div class="standing-card-top">
                  <span class="rank-number" :class="rankClass(row)">{{ row.rank }}</span>
                  <span v-if="row.zone" class="zone-label" :class="`zone-${String(row.zone).toLowerCase()}`">{{ zoneLabel(row.zone) || row.zoneLabel }}</span>
                  <strong class="standing-card-name" :title="teamLabel(row.team)">{{ teamLabel(row.team) }}</strong>
                  <span class="standing-card-pts"><b>{{ row.points ?? '—' }}</b> {{ t('competition.pointsShort') }}</span>
                </div>
                <div class="standing-card-stats">
                  <span>{{ t('competition.playedShort') }} {{ row.played ?? '—' }}</span>
                  <span>{{ t('competition.winShort') }} {{ row.win ?? '—' }}</span>
                  <span>{{ t('competition.drawShort') }} {{ row.draw ?? '—' }}</span>
                  <span>{{ t('competition.lossShort') }} {{ row.loss ?? '—' }}</span>
                </div>
                </button>
              </div>
            </div>
            <div v-else class="standings-cards" :aria-label="t('competition.cardsAria')">
              <button
                v-for="row in standings"
                :key="'card-' + standingRowKey(row)"
                type="button"
                class="standing-card"
                :aria-label="t('competition.viewTeamProfile', { name: teamLabel(row.team) })"
                @click="openTeam(row.team)"
              >
                <div class="standing-card-top">
                  <span class="rank-number" :class="rankClass(row)">{{ row.rank }}</span>
                  <span v-if="row.zone" class="zone-label" :class="`zone-${String(row.zone).toLowerCase()}`">{{ zoneLabel(row.zone) || row.zoneLabel }}</span>
                  <strong class="standing-card-name" :title="teamLabel(row.team)">{{ teamLabel(row.team) }}</strong>
                  <span class="standing-card-pts"><b>{{ row.points ?? '—' }}</b> {{ t('competition.pointsShort') }}</span>
                </div>
                <div class="standing-card-stats">
                  <span>{{ t('competition.playedShort') }} {{ row.played ?? '—' }}</span>
                  <span>{{ t('competition.winShort') }} {{ row.win ?? '—' }}</span>
                  <span>{{ t('competition.drawShort') }} {{ row.draw ?? '—' }}</span>
                  <span>{{ t('competition.lossShort') }} {{ row.loss ?? '—' }}</span>
                </div>
              </button>
            </div>
          </div>
          <PageState v-else-if="standings.length" :title="t('competition.standingsNotFormedTitle')" :description="t('competition.standingsNotFormedBody')" :action-text="t('competition.viewFixturesAndPredictions')" @action="goMatchesDiscover" />
          <PageState v-else :title="emptyStandingsTitle" :description="emptyStandingsDescription" :action-text="emptyStandingsActionText" @action="goMatchesDiscover" />
        </PageSection>

        <PageSection class="clubs-panel" :title="t('competition.clubsTitle')" :subtitle="t('competition.clubsSubtitle')">
          <template #actions>
            <el-input v-model="clubKeyword" class="club-search" clearable size="small" :placeholder="t('competition.searchTeamsPlaceholder')" :aria-label="t('competition.searchTeamsAria')" />
          </template>
          <PageState v-if="loading && !filteredClubs.length" type="loading" :title="t('competition.loadingClubs')" :size="32" />
          <div v-else-if="filteredClubs.length" class="club-grid">
            <button v-for="club in filteredClubs" :key="clubKey(club)" type="button" class="club-card" :title="teamLabel(club)" :aria-label="t('competition.viewTeam', { name: teamLabel(club) })" @click="openTeam(club)">
              <img v-if="club.logo && !club.logoBroken" :src="getMediaAssetUrl(club.logo)" alt="" aria-hidden="true" width="32" height="32" loading="lazy" decoding="async" @error="markLogoBroken(club)" />
              <span v-else class="club-logo-placeholder" :title="t('competition.noCrest')" :aria-label="t('competition.teamNoCrest', { name: teamLabel(club) })">{{ firstLetter(teamLabel(club)) }}</span>
              <span class="club-name" :title="teamLabel(club)">{{ teamLabel(club) }}</span>
              <span class="club-meta">{{ clubStandingLabel(club) }}</span>
              <span class="club-arrow" aria-hidden="true">→</span>
            </button>
          </div>
          <PageState v-else-if="clubKeyword.trim()" :title="t('competition.noMatchingTeams')" :description="t('competition.noMatchingTeamsBody')" :action-text="t('competition.clearSearch')" @action="clubKeyword = ''" />
          <PageState v-else :title="t('competition.noClubsTitle')" :description="t('competition.noClubsBody')" :action-text="t('competition.searchOnFixtures')" @action="goMatchesDiscover" />
        </PageSection>
      </section>

      <aside class="hub-predict-entry" :aria-label="t('competition.predictEntryAria')">
        <div>
          <strong>{{ t('competition.predictEntryTitle') }}</strong>
          <p>{{ t('competition.predictEntryBody') }}</p>
        </div>
        <router-link class="hub-predict-link" to="/matches?discover=predict">{{ t('competition.browsePredictions') }}</router-link>
      </aside>

    </el-main>
      <AppFooter />
  </div>
</template>

<script setup>
import { computed, getCurrentInstance, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Refresh, Trophy } from '@element-plus/icons-vue'
import AppFooter from '../../components/layout/AppFooter.vue'
import AppTopNav from '../../components/layout/AppTopNav.vue'
import PageSection from '../../components/layout/PageSection.vue'
import PageState from '../../components/layout/PageState.vue'
import { analyticsApi, crawlerApi } from '../../api'
import { useUserStore } from '../../stores/user'
import { getMediaAssetUrl } from '../../utils/mediaAsset'
import { displayTeamName } from '../../utils/teamNames'
import { registerElementPlusTable } from '../../plugins/register-element-plus-table'
import { useI18n } from '../../i18n'

registerElementPlusTable(getCurrentInstance()?.appContext.app)
const { t, locale } = useI18n()

const router = useRouter()
const goMatchesDiscover = () => {
  router.push({ path: "/matches", query: { discover: "predict" } })
}
const userStore = useUserStore()

// Production scope now includes 欧冠/欧国联; standings may be empty until
// crawler syncs — UI must not crash when selecting them.
const leagueOptions = computed(() => [
  { value: '英超', label: t('competition.league.premierLeague'), id: 39 },
  { value: '西甲', label: t('competition.league.laLiga'), id: 140 },
  { value: '意甲', label: t('competition.league.serieA'), id: 135 },
  { value: '德甲', label: t('competition.league.bundesliga'), id: 78 },
  { value: '法甲', label: t('competition.league.ligue1'), id: 61 },
  { value: '荷甲', label: t('competition.league.eredivisie'), id: 88 },
  { value: '葡超', label: t('competition.league.primeiraLiga'), id: 94 },
  { value: '英冠', label: t('competition.league.championship'), id: 40 },
  { value: '欧冠', label: t('competition.league.championsLeague'), id: 2 },
  { value: '欧国联', label: t('competition.league.nationsLeague'), id: null }
])
const route = useRoute()
const selectedLeague = ref('英超')
const selectedSeason = ref('')
const seasonOptions = ref([])
const loading = ref(false)
const loadErrorKey = ref('')
const loadErrorRaw = ref('')
const loadError = computed(() => loadErrorRaw.value || (loadErrorKey.value ? t(loadErrorKey.value) : ''))
const standings = ref([])
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
  team: {
    ...(row?.team || {
      name: row?.teamName,
      logo: row?.teamLogo,
      id: row?.teamId
    }),
    teamDisplayName: row?.team?.teamDisplayName || row?.team?.displayName || row?.team?.nameEn || row?.teamDisplayName || row?.displayName || ''
  },
  win: Number(row?.win ?? row?.wins ?? 0),
  draw: Number(row?.draw ?? row?.draws ?? 0),
  loss: Number(row?.loss ?? row?.losses ?? 0),
  group: row?.group || '',
  groupLabel: row?.groupLabel || row?.group || ''
})
const normalizeClub = (club) => {
  const team = club?.team || club
  return {
    id: team?.id || club?.id || '',
    canonicalKey: team?.canonicalKey || club?.canonicalKey || '',
    name: team?.name || club?.name || '',
    displayName: team?.teamDisplayName || team?.displayName || team?.nameEn || club?.teamDisplayName || club?.displayName || '',
    logo: team?.logo || club?.logo || '',
    logoBroken: false
  }
}

const teamLabel = team => displayTeamName(
  team?.name || t('competition.unknownTeam'),
  team?.teamDisplayName || team?.displayName || team?.nameEn || ''
)
const clubs = computed(() => {
  const map = new Map()
  standings.value.forEach(row => {
    const club = normalizeClub(row.team)
    if (club.name) map.set(club.canonicalKey || club.name, { ...club, rank: row.rank, points: row.points, zone: row.zone || '', group: row.group || '', groupLabel: row.groupLabel || row.group || '' })
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
  if (!keyword) return clubs.value
  return clubs.value.filter(club => {
    const en = String(club.name || '').toLowerCase()
    const zh = String(teamLabel(club) || '').toLowerCase()
    return en.includes(keyword) || zh.includes(keyword)
  })
})
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
const isCupOrNationsLeague = computed(() => ['欧冠', '欧国联'].includes(selectedLeague.value))
const isOpsSpeakQuality = (msg) => {
  const s = String(msg || '')
  return !s ? false : (s.includes('已返回') || s.includes('主数据源') || s.includes('完成本次检查') || /status code|request failed/i.test(s))
}
const qualityStatusLabel = computed(() => {
  const status = String(quality.value.status || '').toUpperCase()
  const key = {
    AVAILABLE: 'competition.qualityAvailable',
    NORMAL: 'competition.qualityAvailable',
    PRESEASON: 'competition.qualityPreseason',
    STALE: 'competition.qualityStale',
    SYNC_FAILED: 'competition.qualitySyncFailed',
    NO_DATA: 'competition.qualityNoData',
    NOT_CONFIGURED: 'competition.qualityNotConfigured'
  }[status]
  return key ? t(key) : (quality.value.statusText || '')
})
const qualityMessage = computed(() => {
  const raw = quality.value.message || ''
  const status = String(quality.value.status || '').toUpperCase()
  if (status === 'SYNC_FAILED') return loadError.value || t('competition.loadGenericError')
  if (status === 'STALE') return t('competition.staleWarning')
  if (status === 'PRESEASON') {
    return isCupOrNationsLeague.value ? t('competition.cupPreseasonNote') : t('competition.noVerifiedPoints')
  }
  if (status === 'NO_DATA' || status === 'NOT_CONFIGURED') {
    return isCupOrNationsLeague.value ? t('competition.emptyCupBody') : t('competition.emptyLeagueBody')
  }
  if (isOpsSpeakQuality(raw)) {
    if (['欧冠', '欧国联'].includes(selectedLeague.value)) {
      return t('competition.cupPreseasonNote')
    }
    return t('competition.noVerifiedPoints')
  }
  return raw
})
const qualityTagType = computed(() => ({ PRESEASON: 'warning', STALE: 'warning', AVAILABLE: 'success', NORMAL: 'success' }[String(quality.value.status || '').toUpperCase()] || 'info'))
const emptyStandingsTitle = computed(() => isCupOrNationsLeague.value ? t('competition.emptyCupTitle') : t('competition.emptyLeagueTitle'))
const emptyStandingsDescription = computed(() => {
  if (isCupOrNationsLeague.value) {
    return t('competition.emptyCupBody')
  }
  return qualityMessage.value || t('competition.emptyLeagueBody')
})
const emptyStandingsActionText = computed(() => isCupOrNationsLeague.value ? t('competition.viewFixtures') : t('competition.browseFixturesAndPredictions'))

const rankClass = row => {
  const zone = String(row?.zone || '').toUpperCase()
  if (zone.startsWith('CHAMPIONS_') || zone.startsWith('EUROPA_') || zone.startsWith('CONFERENCE_') || zone === 'PROMOTION' || zone === 'PROMOTION_PLAYOFF') return 'rank-top'
  if (zone.startsWith('RELEGATION')) return 'rank-bottom'
  return ''
}
const zoneLabel = zone => ({
  CHAMPIONS_LEAGUE: t('competition.zoneChampionsLeague'),
  CHAMPIONS_LEAGUE_QUALIFYING: t('competition.zoneChampionsLeagueQualifying'),
  EUROPA_LEAGUE: t('competition.zoneEuropaLeague'),
  EUROPA_LEAGUE_QUALIFYING: t('competition.zoneEuropaLeagueQualifying'),
  CONFERENCE_LEAGUE: t('competition.zoneConferenceLeague'),
  CONFERENCE_LEAGUE_QUALIFYING: t('competition.zoneConferenceLeagueQualifying'),
  CONFERENCE_PLAYOFF: t('competition.zoneConferencePlayoff'),
  PROMOTION: t('competition.zonePromotion'),
  PROMOTION_PLAYOFF: t('competition.zonePromotionPlayoff'),
  RELEGATION_PLAYOFF: t('competition.zoneRelegationPlayoff'),
  RELEGATION: t('competition.zoneRelegation'),
  // Compatibility with snapshots produced by older API instances.
  EUROPA: t('competition.zoneEuropa')
}[zone] || '')
const firstLetter = name => String(name || '?').trim().slice(0, 1).toUpperCase()
const clubKey = club => `${club.id || 'name'}-${club.name}`
const clubRank = club => club.rank || standings.value.find(row => row.team?.name === club.name)?.rank || 0
const formatGroupLabel = (group) => {
  const s = String(group || '').trim()
  if (!s) return t('competition.standingsTitle')
  // League A Group 3 → A联 · 第3组
  const m = s.match(/League\s*([A-D])\s*Group\s*(\d+)/i)
  if (m) return t('competition.groupLabel', { letter: m[1], number: m[2] })
  return s
}
const standingGroups = computed(() => {
  const rows = standings.value || []
  const map = new Map()
  rows.forEach(row => {
    const key = row.group || row.groupLabel || '_default'
    if (!map.has(key)) map.set(key, [])
    map.get(key).push(row)
  })
  return [...map.entries()].map(([key, items]) => ({
    key,
    label: formatGroupLabel(items[0]?.groupLabel || items[0]?.group || key),
    items: [...items].sort((a, b) => Number(a.rank || 999) - Number(b.rank || 999))
  })).sort((a, b) => String(a.label).localeCompare(String(b.label), 'zh-CN'))
})
const hasStandingGroups = computed(() => standingGroups.value.length > 1 || (standingGroups.value[0] && standingGroups.value[0].key !== '_default'))
const standingRowKey = (row) => `${row?.group || row?.groupLabel || ''}-${row?.team?.id || row?.team?.name || row?.rank || ''}`
const clubStandingLabel = (club) => {
  const rank = clubRank(club)
  if (!rank) return t('competition.viewTeamStanding')
  if (club.group || club.groupLabel) {
    return t('competition.groupRank', { group: formatGroupLabel(club.groupLabel || club.group), rank })
  }
  return t('competition.rankSingle', { rank })
}
const markLogoBroken = item => { item.logoBroken = true; item.logo = '' }
const loadLeagueData = async () => {
  loading.value = true
  loadErrorKey.value = ''
  loadErrorRaw.value = ''
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
      throw standingsResult.reason || clubsResult.reason || Object.assign(new Error('SERVICE_UNAVAILABLE'), { i18nKey: 'competition.serviceUnavailable' })
    }
    standings.value = nextStandings
    clubsFromApi.value = nextClubs
    quality.value = standingsResult.status === 'fulfilled'
      ? (standingsResult.value?.dataQuality || standingsResult.value?.data?.dataQuality || quality.value)
      : { status: 'SYNC_FAILED', statusText: '', message: '', source: '' }
    zoneRules.value = standingsResult.status === 'fulfilled'
      ? (standingsResult.value?.zoneRules || standingsResult.value?.data?.zoneRules || { zones: [], note: '' })
      : { zones: [], note: '' }
    analyticsApi.track('competition_viewed', { page: '/competitions', entityType: 'league', entityId: selectedLeague.value, properties: { season: selectedSeason.value, standings: nextStandings.length, clubs: nextClubs.length, dataStatus: quality.value.status } }).catch(() => {})
  } catch (error) {
    standings.value = []
    clubsFromApi.value = []
    zoneRules.value = { zones: [], note: '' }
    // Never surface axios/status codes or admin-speak to fans.
    const raw = String(error?.message || error?.response?.status || '')
    const isCup = ['欧冠', '欧国联'].includes(selectedLeague.value)
    if (isCup) {
      loadErrorKey.value = 'competition.loadCupError'
    } else if (error?.i18nKey) {
      loadErrorKey.value = error.i18nKey
    } else if (/request failed|status code|500|network|timeout/i.test(raw)) {
      loadErrorKey.value = 'competition.loadNetworkError'
    } else if (raw && !/status code|request failed/i.test(raw)) {
      loadErrorRaw.value = raw
    } else {
      loadErrorKey.value = 'competition.loadGenericError'
    }
    quality.value = { status: 'SYNC_FAILED', statusText: '', message: '', source: '' }
  } finally {
    loading.value = false
  }
}

const refreshStandings = async () => {
  if (!isAdmin.value) return
  loading.value = true
  try {
    await crawlerApi.refreshStandings(selectedLeague.value)
    ElMessage.success(t('competition.syncComplete'))
    await loadLeagueData()
  } catch (error) {
    ElMessage.warning(error?.message || t('competition.syncFailedMessage'))
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
    query: {
      name: next.name,
      teamDisplayName: next.displayName || '',
      teamId: String(next.id || ''),
      logo: next.logo || '',
      league: selectedLeague.value,
      leagueDisplay: leagueOptions.value.find(item => item.value === selectedLeague.value)?.label || '',
      season: selectedSeason.value
    }
  })
}

const applyLeagueFromQuery = () => {
  const requested = String(route.query.league || route.query.q || '').trim()
  if (!requested) return
  const hit = leagueOptions.value.find(item => item.value === requested || item.label === requested)
  if (hit) selectedLeague.value = hit.value
}
onMounted(() => {
  applyLeagueFromQuery()
  loadLeagueData()
})
watch(() => route.query.league, () => {
  applyLeagueFromQuery()
  loadLeagueData()
})
watch(locale, () => loadLeagueData())
</script>

<style scoped>
.main-content { max-width: 1360px; margin: 0 auto; padding: clamp(16px, 2.5vw, 28px); }
.hub-grid { display:grid; grid-template-columns:minmax(0,1.55fr) minmax(320px,.75fr); gap:20px; align-items:start; }
.standings-panel :deep(.section-actions), .clubs-panel :deep(.section-actions) { flex:1; min-width:0; }
.clubs-panel :deep(.section-actions) { justify-content:flex-end; }
.standings-toolbar { display:flex; align-items:flex-end; justify-content:space-between; gap:14px; width:100%; flex-wrap:wrap; }
.toolbar-group { display:flex; align-items:flex-end; gap:8px; flex-wrap:wrap; }
.toolbar-secondary { margin-left:auto; justify-content:flex-end; }
.control-field { display:flex; flex-direction:column; align-items:flex-start; gap:4px; color:var(--ff-text-faint); font-size:10px; line-height:1; letter-spacing:.04em; white-space:nowrap; }
.control-field .el-select { width:100%; }
.control-field .league-select { width:146px; min-width:146px; }
.control-field .season-select { width:126px; min-width:126px; }
.league-select :deep(.el-select__wrapper), .season-select :deep(.el-select__wrapper) {
  min-height:36px;
  padding:0 11px;
  border:1px solid var(--ff-border);
  background:var(--ff-surface);
  box-shadow:inset 0 0 0 1px transparent;
}
.league-select :deep(.el-select__wrapper:hover), .season-select :deep(.el-select__wrapper:hover) { border-color:var(--ff-border-strong); }
.league-select :deep(.el-select__wrapper.is-focused), .season-select :deep(.el-select__wrapper.is-focused) { border-color:var(--ff-primary); box-shadow:0 0 0 3px rgba(18,107,80,.1); }
.league-select :deep(.el-select__selected-item), .season-select :deep(.el-select__selected-item) {
  overflow:hidden;
  color:var(--ff-text-strong) !important;
  font-size:13px;
  font-weight:700;
  text-overflow:ellipsis;
  white-space:nowrap;
}
.league-select :deep(.el-select__placeholder), .season-select :deep(.el-select__placeholder) { color:var(--ff-text-muted); }
.league-select :deep(.el-select__suffix), .season-select :deep(.el-select__suffix) { color:var(--ff-primary); }
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
.club-search { width:180px; }.club-grid { display:grid; grid-template-columns:1fr; gap:0; border:1px solid var(--ff-border); border-radius:6px; overflow:hidden; }
.club-card { display:flex; align-items:center; gap:9px; min-width:0; padding:9px 10px; border:0; border-bottom:1px solid var(--ff-border); border-radius:0; background:#fff; color:var(--ff-text); text-align:left; cursor:pointer; }
.club-card:hover,.club-card:focus-visible { border-color:var(--ff-primary); background:var(--ff-primary-soft); outline:none; }.club-card img,.club-logo-placeholder { width:32px; height:32px; object-fit:contain; flex:none; }.club-logo-placeholder { display:inline-flex; align-items:center; justify-content:center; border-radius:8px; background:var(--ff-bg-alt); color:var(--ff-primary); font-weight:700; }.club-name { min-width:0; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; font-size:13px; font-weight:700; }.club-meta { margin-left:auto; color:var(--ff-text-faint); font-size:10px; white-space:nowrap; }.club-arrow { margin-left:auto; color:var(--ff-primary); font-size:18px; opacity:.6; transition:opacity var(--ff-transition-fast); }.club-card:hover .club-arrow,.club-card:focus-visible .club-arrow { opacity:1; }
@media (max-width: 980px) { .hub-grid { grid-template-columns:1fr; } }
@media (max-width: 680px) {
  .main-content { padding:12px; }
  .standings-toolbar, .toolbar-group { align-items:stretch; }
  .standings-toolbar { flex-direction:column; gap:12px; }
  .toolbar-group { width:100%; }
  .toolbar-secondary { margin-left:0; align-items:center; }
  .control-field { flex:1; }
  .control-field .league-select, .control-field .season-select { width:100%; min-width:0; }
  .club-grid { grid-template-columns:1fr; }
  .club-meta { display:none; }
}
.club-card { min-height: 54px; }
.club-name { overflow: visible; text-overflow: clip; white-space: normal; line-height: 1.3; }
@media (max-width: 680px) { .toolbar-secondary .el-tag { display:none; } }

.team-name-text {
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  line-height: 1.25;
  white-space: normal;
}
.standings-scroll-hint { display:none; margin:0; color:var(--ff-text-faint); font-size:11px; }
.standings-table-scroll { width:100%; overflow-x:auto; -webkit-overflow-scrolling:touch; }
.standings-cards { display:none; flex-direction:column; gap:8px; }
.standing-groups { display:flex; flex-direction:column; gap:18px; }
.standing-group { display:flex; flex-direction:column; gap:8px; }
.group-heading { margin:0; color:var(--ff-text-strong); font-size:14px; font-weight:700; letter-spacing:-.01em; }
.standings-cards .group-heading { margin-top:4px; }
.standings-cards .standing-group { gap:8px; }
.standing-card {
  display:flex; flex-direction:column; gap:8px; width:100%;
  padding:12px; border:1px solid var(--ff-border); border-radius:10px;
  background:var(--ff-surface-quiet); color:var(--ff-text); text-align:left; cursor:pointer;
}
.standing-card:hover, .standing-card:focus-visible { border-color:var(--ff-primary); outline:none; }
.standing-card-top { display:flex; align-items:center; gap:8px; min-width:0; }
.standing-card-name {
  flex:1; min-width:0;
  display:-webkit-box; -webkit-line-clamp:2; -webkit-box-orient:vertical;
  overflow:hidden; line-height:1.25; font-size:13px;
}
.standing-card-pts { margin-left:auto; color:var(--ff-text-strong); font-size:12px; white-space:nowrap; }
.standing-card-stats { display:flex; flex-wrap:wrap; gap:10px; color:var(--ff-text-muted); font-size:12px; font-family:var(--ff-mono); }
@media (max-width: 768px) {
  .standings-scroll-hint { display:block; }
  .standings-table-desktop { display:none !important; }
  .standings-table-scroll { display:none; }
  .standing-groups-desktop { display:none; }
  .standings-cards { display:flex; }
  .standings-scroll-hint { display:none; }
}

.standings-skeleton { display: grid; gap: 8px; padding: 4px 0 8px; }
.standings-skeleton-row {
  display: grid;
  grid-template-columns: 28px minmax(0, 1.8fr) repeat(3, 44px);
  gap: 8px;
  align-items: center;
  padding: 10px 12px;
  border: 1px solid var(--ff-border);
  border-radius: 10px;
  background: var(--ff-surface);
}
.standings-skeleton-row i, .standings-skeleton-row b, .standings-skeleton-row em {
  display: block;
  height: 12px;
  border-radius: 6px;
  background: linear-gradient(90deg, var(--ff-surface-soft) 0%, #eef3f0 45%, var(--ff-surface-soft) 100%);
  background-size: 200% 100%;
  animation: standings-skeleton 1.2s ease-in-out infinite;
}
.standings-skeleton-row i { width: 22px; height: 22px; border-radius: 999px; }
.standings-skeleton-row b { width: 70%; height: 14px; }
.standings-skeleton-row em { width: 100%; }
@keyframes standings-skeleton { from { background-position: 100% 0; } to { background-position: -100% 0; } }
@media (prefers-reduced-motion: reduce) {
  .standings-skeleton-row i, .standings-skeleton-row b, .standings-skeleton-row em { animation: none; }
}

.hub-predict-entry {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin: 4px 0 8px;
  padding: 14px 16px;
  border: 0;
  border-top: 1px solid var(--ff-border);
  border-radius: 0;
  background: transparent;
  padding: 12px 2px;
}
.hub-predict-entry strong { color: var(--ff-text-strong); font-size: 13px; }
.hub-predict-entry p { margin: 4px 0 0; color: var(--ff-text-muted); font-size: 12px; line-height: 1.5; }
.hub-predict-link {
  flex: none;
  color: var(--ff-primary);
  font-size: 13px;
  font-weight: 700;
  white-space: nowrap;
}
.hub-predict-link:hover { text-decoration: underline; text-underline-offset: 3px; }
@media (max-width: 680px) {
  .hub-predict-entry { flex-direction: column; align-items: flex-start; }
  .standings-skeleton-row {
    grid-template-columns: 28px minmax(0, 1fr) 40px 40px;
    overflow: hidden;
  }
  .standings-skeleton-row em:nth-child(n+5) { display: none; }
}

/* r14 visual pass — keep the two columns stable and stop the clubs toolbar
   from clipping its own search field. */
.hub-grid { grid-template-columns: minmax(0, 1fr) 340px; gap: 18px; }
.club-search { width: 150px; min-width: 0; max-width: 100%; flex: 0 1 150px; }
.club-grid { border-radius: 10px; }
.club-card { min-height: 48px; padding: 6px 10px; }
.club-card img, .club-logo-placeholder { width: 26px; height: 26px; }
.club-name { font-size: 13px; font-weight: 600; }
.zone-label { font-size: 9.5px; padding: 2px 6px; }
@media (max-width: 980px) { .hub-grid { grid-template-columns: 1fr; } }
</style>
