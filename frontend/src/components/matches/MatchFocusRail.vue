<template>
  <section class="focus-rail" aria-labelledby="match-focus-title">
    <div class="focus-head">
      <div>
        <span class="focus-kicker">{{ t('matches.focusKicker') }}</span>
        <h2 id="match-focus-title">{{ t('matches.focusTitle') }}</h2>
      </div>
      <div class="focus-head-actions">
        <span v-if="meta?.returnedCount != null" class="focus-count">{{ t('matches.matchCount', { count: meta.returnedCount }) }}</span>
        <button v-if="error && items.length" type="button" class="focus-retry-link" @click="$emit('retry')">{{ t('matches.focusRetry') }}</button>
      </div>
    </div>

    <div v-if="loading" class="focus-list focus-list-skeleton" aria-live="polite" :aria-label="t('matches.focusLoadingAria')">
      <div v-for="index in 3" :key="index" class="focus-skeleton-card"><i></i><b></b><em></em></div>
    </div>
    <div v-else-if="error && !items.length" class="focus-state focus-state-error" role="alert">
      <strong>{{ t('matches.focusUnavailable') }}</strong>
      <span>{{ error }}</span>
      <button type="button" @click="$emit('retry')">{{ t('matches.retry') }}</button>
    </div>
    <div v-else-if="!items.length" class="focus-state" aria-live="polite">
      <strong>{{ emptyTitle }}</strong>
      <span>{{ emptyDescription }}</span>
      <button type="button" @click="$emit('view-all')">{{ t('matches.viewAllFixtures') }}</button>
    </div>
    <div v-else class="focus-list" :class="{ 'is-stale': stale }">
      <article v-for="(match, index) in items" :key="recommendationKey(match, index)" class="focus-card" :class="`is-${tier(match)}`">
        <button type="button" class="focus-main" @click="$emit('open', match)">
          <span class="focus-card-top">
            <span class="focus-rank">{{ String(index + 1).padStart(2, '0') }}</span>
            <span class="focus-league">{{ leagueName(match) }}</span>
            <span class="focus-status"><i v-if="tier(match) === 'LIVE'"></i>{{ statusText(match) }}</span>
          </span>
          <span class="focus-matchup">
            <span class="focus-team"><img v-if="homeLogo(match)" :src="homeLogo(match)" width="24" height="24" loading="lazy" decoding="async" :alt="t('matches.teamLogoAlt', { team: homeName(match) })" @error="onLogoError" /><b v-else :title="t('matches.noLogo')" :aria-label="t('matches.noLogoAria', { team: homeName(match) })">{{ initial(homeName(match)) }}</b><strong>{{ homeName(match) }}</strong></span>
            <span class="focus-center"><b>{{ scoreOrTime(match) }}</b><small>{{ tier(match) === 'LIVE' ? t('matches.liveNow') : kickoffLabel(match) }}</small></span>
            <span class="focus-team away"><strong>{{ awayName(match) }}</strong><img v-if="awayLogo(match)" :src="awayLogo(match)" width="24" height="24" loading="lazy" decoding="async" :alt="t('matches.teamLogoAlt', { team: awayName(match) })" @error="onLogoError" /><b v-else :title="t('matches.noLogo')" :aria-label="t('matches.noLogoAria', { team: awayName(match) })">{{ initial(awayName(match)) }}</b></span>
          </span>
          <span class="focus-reasons">
            <em v-for="reason in reasons(match).slice(0, 2)" :key="reason">{{ reason }}</em>
          </span>
        </button>
        <div class="focus-card-actions">
          <button type="button" @click="$emit('predict', match)">{{ tier(match) === 'RECENT' ? t('matches.viewReview') : t('matches.viewPrediction') }}</button>
        </div>
      </article>
    </div>
    <p v-if="stale" class="focus-stale-note" role="status">{{ t('matches.focusStale') }}</p>
  </section>
</template>

<script setup>
import { computed } from 'vue'
import { formatMatchTime, getLeagueDisplay, getMatchTimestamp, getStatusText, getTeamDisplay, isFinished as isFinishedMatch, isLive as isLiveMatch } from '../../utils/match'
import { displayLeagueName, getTeamDisplayName } from '../../utils/teamNames'
import { getMediaAssetUrl } from '../../utils/mediaAsset'
import { useI18n } from '../../i18n'

const { t, locale } = useI18n()

const props = defineProps({
  items: { type: Array, default: () => [] },
  meta: { type: Object, default: () => ({}) },
  loading: { type: Boolean, default: false },
  error: { type: String, default: '' },
  stale: { type: Boolean, default: false },
  teamNameMode: { type: String, default: 'zh' }
})
defineEmits(['open', 'predict', 'retry', 'view-all'])

const emptyTitle = computed(() => props.meta?.emptyReason === 'NO_MATCHES_IN_WINDOW' ? t('matches.noMatchesInWindow') : t('matches.noFocusMatches'))
const emptyDescription = computed(() => props.meta?.emptyReason === 'NO_VISIBLE_FOCUS_MATCHES' ? t('matches.noVisibleFocusMatches') : t('matches.noUpcomingFocus'))
const team = (match, side) => match?.teams?.[side] || {}
const homeName = match => getTeamDisplayName(team(match, 'home').name || match?.homeTeamName || t('matches.homeTeam'), props.teamNameMode, getTeamDisplay(match, 'home'))
const awayName = match => getTeamDisplayName(team(match, 'away').name || match?.awayTeamName || t('matches.awayTeam'), props.teamNameMode, getTeamDisplay(match, 'away'))
const leagueName = match => displayLeagueName(match?.league?.name || match?.leagueName || t('matches.leagueOther'), getLeagueDisplay(match))
const homeLogo = match => getMediaAssetUrl(team(match, 'home').logo || match?.homeTeamLogo || '')
const awayLogo = match => getMediaAssetUrl(team(match, 'away').logo || match?.awayTeamLogo || '')
const initial = name => String(name || '?').trim().slice(0, 1).toUpperCase()
const recommendationKey = (match, index) => match?.matchId || match?.id || match?.fixtureId || `${homeName(match)}-${awayName(match)}-${index}`
const tier = match => String(match?.recommendation?.tier || (isLiveMatch(match) ? 'LIVE' : isFinishedMatch(match) ? 'RECENT' : 'UPCOMING')).toUpperCase()
const statusText = match => getStatusText(match) || (tier(match) === 'LIVE' ? t('matches.liveNow') : tier(match) === 'RECENT' ? t('matches.finished') : t('matches.notStarted'))
const reasons = match => {
  const texts = Array.isArray(match?.recommendation?.reasonTexts) ? match.recommendation.reasonTexts : []
  const codes = Array.isArray(match?.recommendation?.reasonCodes) ? match.recommendation.reasonCodes : []
  if (!texts.length) return [t('matches.focusDefaultReason')]
  const priority = { POPULAR_TEAM: 0, LIVE_NOW: 0, KICKOFF_SOON: 1, TOP_LEAGUE: 2, KEY_ROUND: 3, KICKOFF_TODAY: 4 }
  return texts.map((text, index) => ({ text, code: codes[index] || '' }))
    .sort((a, b) => (priority[a.code] ?? 9) - (priority[b.code] ?? 9))
    .map(item => item.text)
}
const timestamp = match => getMatchTimestamp(match)
const kickoffLabel = match => timestamp(match) ? formatMatchTime(match) : t('matches.timePending')
const scoreOrTime = match => {
  const home = match?.goals?.home ?? match?.homeScore
  const away = match?.goals?.away ?? match?.awayScore
  if ((tier(match) === 'LIVE' || tier(match) === 'RECENT') && home != null && away != null) return `${home} - ${away}`
  if (!timestamp(match)) return '—'
  return new Intl.DateTimeFormat(locale.value === 'zh' ? 'zh-CN' : 'en-US', { timeZone: 'Asia/Shanghai', hour: '2-digit', minute: '2-digit' }).format(new Date(timestamp(match)))
}
const onLogoError = event => { event.target.style.display = 'none' }

</script>

<style scoped>
.focus-rail { margin: 0 0 18px; padding: 18px; border: 1px solid var(--ff-border); border-radius: var(--ff-radius-lg); background: var(--ff-surface); width: 100%; max-width: 100%; min-width: 0; box-sizing: border-box; }
.focus-head { display:flex; align-items:flex-start; justify-content:space-between; gap:16px; margin-bottom:14px; min-width:0; flex-wrap:wrap; }
.focus-kicker { color:var(--ff-text-muted); font:600 12px/1.2 'PingFang SC','Microsoft YaHei',sans-serif; letter-spacing:0; }
.focus-head h2 { margin:6px 0 3px; color:var(--ff-text-strong); font-size:18px; letter-spacing:-.02em; }
.focus-head p { margin:0; color:var(--ff-text-muted); font-size:12px; }
.focus-head-actions { display:flex; align-items:center; gap:10px; color:var(--ff-text-faint); font-size:12px; white-space:nowrap; }
.focus-count { font-family:var(--ff-mono); }
.focus-retry-link,.focus-state button,.focus-card-actions button { border:0; background:none; color:var(--ff-primary); cursor:pointer; font:inherit; }
.focus-retry-link:hover,.focus-state button:hover,.focus-card-actions button:hover { text-decoration:underline; }
.focus-list { display:grid; grid-template-columns:repeat(3,minmax(0,1fr)); gap:10px; width:100%; min-width:0; }
.focus-card { min-width:0; overflow:hidden; border:1px solid var(--ff-border); border-radius:10px; background:var(--ff-surface-quiet); transition:border-color .16s ease,background .16s ease; }
.focus-card:hover { border-color:var(--ff-border-strong); background:var(--ff-surface-soft); }
.focus-card.is-LIVE { border-color:color-mix(in srgb, #d04444 45%, var(--ff-border)); }
.focus-main { display:block; width:100%; padding:12px 13px 9px; border:0; background:none; color:inherit; text-align:left; cursor:pointer; }
.focus-main:focus-visible { outline:2px solid var(--ff-primary); outline-offset:-2px; }
.focus-card-top { display:flex; align-items:center; gap:7px; min-width:0; font-size:11px; }
.focus-rank { color:var(--ff-text-faint); font:700 11px var(--ff-mono); }
.focus-league { min-width:0; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; color:var(--ff-text-muted); }
.focus-status { margin-left:auto; display:inline-flex; align-items:center; gap:4px; color:var(--ff-text-muted); white-space:nowrap; }
.focus-status i { width:6px; height:6px; border-radius:50%; background:#d04444; }
.focus-matchup { display:grid; grid-template-columns:minmax(0,1fr) 58px minmax(0,1fr); align-items:center; gap:8px; margin-top:15px; }
.focus-team { display:flex; align-items:center; gap:6px; min-width:0; color:var(--ff-text-strong); }
.focus-team.away { justify-content:flex-end; text-align:right; }
.focus-team img,.focus-team>b { width:24px; height:24px; flex:0 0 24px; border-radius:50%; object-fit:contain; }
.focus-team>b { display:grid; place-items:center; background:var(--ff-primary-soft); color:var(--ff-primary); font:700 11px var(--ff-mono); }
.focus-team strong { min-width:0; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; font-size:12px; }
.focus-center { display:flex; flex-direction:column; align-items:center; gap:3px; text-align:center; }
.focus-center b { color:var(--ff-text-strong); font:700 15px var(--ff-mono); font-variant-numeric:tabular-nums; white-space:nowrap; }
.focus-center small { color:var(--ff-text-faint); font-size:10px; white-space:nowrap; }
.focus-reasons { display:flex; gap:5px; margin-top:12px; min-height:18px; overflow:hidden; }
.focus-reasons em { padding:2px 6px; border-radius:999px; background:var(--ff-primary-soft); color:var(--ff-primary); font-size:10px; font-style:normal; white-space:nowrap; }
.focus-card-actions { display:flex; justify-content:flex-end; gap:12px; padding:8px 13px 10px; border-top:1px solid var(--ff-border); }
.focus-card-actions button:last-child { font-weight:700; }
.focus-state { display:flex; flex-direction:column; align-items:center; gap:8px; padding:25px 12px 20px; color:var(--ff-text-muted); text-align:center; }
.focus-state strong { color:var(--ff-text-strong); font-size:14px; }
.focus-state span { font-size:12px; }
.focus-state-error strong { color:var(--ff-danger,#c24141); }
.focus-state-error button { margin-top:3px; }
.focus-stale-note { margin:10px 0 0; color:var(--ff-text-faint); font-size:11px; }
.focus-list-skeleton { min-height:128px; }
.focus-skeleton-card { min-height:128px; border-radius:10px; background:linear-gradient(90deg,var(--ff-surface-quiet),var(--ff-surface-soft),var(--ff-surface-quiet)); background-size:200% 100%; animation:focus-skeleton 1.2s ease-in-out infinite; }
.focus-skeleton-card i,.focus-skeleton-card b,.focus-skeleton-card em { display:block; height:10px; margin:16px; border-radius:4px; background:color-mix(in srgb,var(--ff-border-strong) 42%,transparent); }
.focus-skeleton-card b { width:70%; margin-top:26px; }.focus-skeleton-card em { width:46%; margin-top:20px; }
@keyframes focus-skeleton { from{background-position:100% 0} to{background-position:-100% 0} }
@media (max-width:900px) { .focus-list { grid-template-columns:repeat(2,minmax(0,1fr)); } }
@media (max-width:620px) {
  .focus-rail { padding:14px; }
  .focus-list { grid-template-columns:1fr; }
  .focus-head > div:first-child { min-width:0; flex:1 1 160px; }
  .focus-head p { overflow:hidden; display:-webkit-box; -webkit-box-orient:vertical; -webkit-line-clamp:2; }
  .focus-card-actions { justify-content:space-between; }
  /* Vertical matchup: home / score / away */
  .focus-matchup {
    display: flex;
    flex-direction: column;
    align-items: stretch;
    gap: 10px;
  }
  .focus-team,
  .focus-team.away {
    justify-content: flex-start;
    text-align: left;
  }
  .focus-team.away {
    flex-direction: row-reverse;
  }
  .focus-team.away strong { text-align: left; }
  .focus-team strong {
    white-space: normal;
    overflow: hidden;
    text-overflow: unset;
    display: -webkit-box;
    -webkit-box-orient: vertical;
    -webkit-line-clamp: 2;
    line-clamp: 2;
  }
  .focus-center {
    width: 100%;
    align-items: center;
  }
  /* Reason chips wrap; larger action tap target */
  .focus-reasons {
    flex-wrap: wrap;
    overflow: visible;
    min-height: 0;
  }
  .focus-card-actions button {
    min-height: 36px;
    padding: 8px 4px;
  }
}
</style>
