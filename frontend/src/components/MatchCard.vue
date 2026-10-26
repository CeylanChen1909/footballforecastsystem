<template>
  <article
    class="match-card"
    :class="{ 'is-focus': focused, 'is-live': isLive }"
    :data-match-id="getMatchId(match)"
    :data-state="state"
  >
    <span v-if="focused" class="focus-bar" aria-hidden="true"></span>

    <div class="match-when">
      <span class="when-primary" :class="{ 'is-live': isLive, 'is-status': whenIsStatus }">
        <span v-if="isLive" class="live-dot" aria-hidden="true"></span>{{ whenPrimary }}
      </span>
      <span v-if="whenSecondary" class="when-secondary">{{ whenSecondary }}</span>
    </div>

    <div class="match-side match-side--home">
      <span class="team-name" role="button" tabindex="0" :title="homeDisplayName" :aria-label="t('matches.viewTeamProfileAria', { team: homeDisplayName })" @click.stop="$emit('teamClick', homeTeam?.name, 'home', match)" @keydown.enter.stop="$emit('teamClick', homeTeam?.name, 'home', match)" @keydown.space.prevent.stop="$emit('teamClick', homeTeam?.name, 'home', match)">{{ homeDisplayName }}</span>
      <img v-if="homeLogoVisible" :src="homeLogoSrc" class="team-logo" width="24" height="24" loading="lazy" decoding="async" :alt="t('matches.teamLogoAlt', { team: homeDisplayName })" @error="homeLogoBroken = true" @click.stop="$emit('teamClick', homeTeam?.name, 'home', match)" />
      <span v-else class="logo-placeholder" :title="t('matches.noLogo')" :aria-label="t('matches.noLogoViewProfileAria', { team: homeDisplayName })" role="button" tabindex="0" @click.stop="$emit('teamClick', homeTeam?.name, 'home', match)" @keydown.enter.stop="$emit('teamClick', homeTeam?.name, 'home', match)" @keydown.space.prevent.stop="$emit('teamClick', homeTeam?.name, 'home', match)">{{ homeDisplayName?.[0] }}</span>
    </div>

    <div class="match-center">
      <span v-if="hasScore" class="score-line">
        <span class="score-num">{{ goals.home }}</span><span class="score-sep">–</span><span class="score-num">{{ goals.away }}</span>
      </span>
      <span v-else class="score-line score-line--vs">vs</span>
    </div>

    <div class="match-side match-side--away">
      <img v-if="awayLogoVisible" :src="awayLogoSrc" class="team-logo" width="24" height="24" loading="lazy" decoding="async" :alt="t('matches.teamLogoAlt', { team: awayDisplayName })" @error="awayLogoBroken = true" @click.stop="$emit('teamClick', awayTeam?.name, 'away', match)" />
      <span v-else class="logo-placeholder" :title="t('matches.noLogo')" :aria-label="t('matches.noLogoViewProfileAria', { team: awayDisplayName })" role="button" tabindex="0" @click.stop="$emit('teamClick', awayTeam?.name, 'away', match)" @keydown.enter.stop="$emit('teamClick', awayTeam?.name, 'away', match)" @keydown.space.prevent.stop="$emit('teamClick', awayTeam?.name, 'away', match)">{{ awayDisplayName?.[0] }}</span>
      <span class="team-name" role="button" tabindex="0" :title="awayDisplayName" :aria-label="t('matches.viewTeamProfileAria', { team: awayDisplayName })" @click.stop="$emit('teamClick', awayTeam?.name, 'away', match)" @keydown.enter.stop="$emit('teamClick', awayTeam?.name, 'away', match)" @keydown.space.prevent.stop="$emit('teamClick', awayTeam?.name, 'away', match)">{{ awayDisplayName }}</span>
    </div>

    <div class="action-area" :aria-label="t('matches.matchActions')">
      <el-button class="action-details" text size="small" @click.stop="$emit('details', match)">{{ t('matches.matchData') }}</el-button>
      <el-tooltip :content="favorited ? t('matches.unfavorite') : t('matches.favoriteMatch')" placement="top">
        <el-button class="favorite-btn" :class="{ 'is-favorited': favorited }" text circle size="small" :aria-label="favorited ? t('matches.unfavoriteMatch') : t('matches.favoriteMatch')" @click.stop="$emit('favorite-match', match)">
          <el-icon><Star v-if="!favorited" /><StarFilled v-else /></el-icon>
        </el-button>
      </el-tooltip>
      <el-dropdown class="more-actions" trigger="click" @command="handleMoreCommand">
        <el-button class="more-btn" text circle size="small" :aria-label="t('matches.moreActions')" :title="t('matches.moreActions')">
          <el-icon><MoreFilled /></el-icon>
        </el-button>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item command="h2h">
              <el-icon><DataLine /></el-icon>
              {{ t('matches.h2hTitle') }}
            </el-dropdown-item>
            <el-dropdown-item command="agent">
              <el-icon><ChatLineSquare /></el-icon>
              {{ t('matches.aiAssistant') }}
            </el-dropdown-item>
            <el-dropdown-item command="details" divided>
              {{ t('matches.matchData') }}
            </el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
      <el-button class="action-primary" size="small" :aria-label="t('matches.openAnalysis', { label: primaryActionLabel })" @click.stop="$emit('predict', match)">
        {{ isFinished ? t('matches.review') : t('matches.predict') }}
      </el-button>
    </div>

    <div v-if="fixture?.venue?.name || match?.league?.round" class="match-meta">
      <span v-if="match?.league?.round" class="meta-item"><el-icon :size="12"><Trophy /></el-icon>{{ match.league.round }}</span>
      <span v-if="fixture?.venue?.name" class="meta-item"><el-icon :size="12"><Location /></el-icon>{{ fixture.venue.name }}</span>
      <span v-if="focused" class="meta-item meta-item--focus">{{ t('matches.focus') }}</span>
    </div>
  </article>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { ChatLineSquare, DataLine, Location, MoreFilled, Star, StarFilled, Trophy } from '@element-plus/icons-vue'
import { formatMatchTime, getAwayTeam, getDisplayStatusKey, getHomeTeam, getMatchId, getMatchTimestamp, getStatusText, getTeamDisplay, isFinished as isFinishedMatch, isLive as isLiveMatch } from '../utils/match'
import { getTeamDisplayName } from '../utils/teamNames'
import { getMediaAssetUrl } from '../utils/mediaAsset'

import { useI18n } from '../i18n'
const { t, locale } = useI18n()

const props = defineProps({ match: Object, favorited: { type: Boolean, default: false }, focused: { type: Boolean, default: false }, teamNameMode: { type: String, default: 'zh' } })
const emit = defineEmits(['predict', 'teamClick', 'h2h', 'agent', 'details', 'favorite-match'])

const fixture = computed(() => ({
  ...(props.match?.fixture || {}),
  date: props.match?.fixture?.date || props.match?.matchTime,
  id: props.match?.fixture?.id || getMatchId(props.match),
  status: { ...(props.match?.fixture?.status || {}), short: props.match?.fixture?.status?.short || props.match?.status }
}))
const homeTeam = computed(() => {
  const team = getHomeTeam(props.match) || {}
  return { ...team, name: team.name || props.match?.homeTeamName || t('matches.homeTeam'), logo: team.logo || props.match?.homeTeamLogo || '' }
})
const awayTeam = computed(() => {
  const team = getAwayTeam(props.match) || {}
  return { ...team, name: team.name || props.match?.awayTeamName || t('matches.awayTeam'), logo: team.logo || props.match?.awayTeamLogo || '' }
})
const homeDisplayName = computed(() => getTeamDisplayName(homeTeam.value.name, props.teamNameMode, getTeamDisplay(props.match, 'home')))
const awayDisplayName = computed(() => getTeamDisplayName(awayTeam.value.name, props.teamNameMode, getTeamDisplay(props.match, 'away')))
const goals = computed(() => props.match?.goals || { home: props.match?.homeScore, away: props.match?.awayScore })
const hasScore = computed(() => (isLiveMatch(props.match) || isFinishedMatch(props.match)) && goals.value?.home != null && goals.value?.away != null)
const homeLogoBroken = ref(false)
const awayLogoBroken = ref(false)
const normalizeLogoUrl = (url) => {
  if (!url) return ''
  const value = String(url).trim()
  if (!value) return ''
  if (value.includes('commons.wikimedia.org/wiki/Special:FilePath/')) return value
  const wikiMatch = value.match(/\/([^/]+)$/)
  if (value.includes('upload.wikimedia.org') && wikiMatch?.[1]) return `https://commons.wikimedia.org/wiki/Special:FilePath/${wikiMatch[1]}`
  return value
}
const homeLogoSrc = computed(() => getMediaAssetUrl(normalizeLogoUrl(homeTeam.value?.logo)))
const awayLogoSrc = computed(() => getMediaAssetUrl(normalizeLogoUrl(awayTeam.value?.logo)))
const homeLogoVisible = computed(() => !!homeLogoSrc.value && !homeLogoBroken.value)
const awayLogoVisible = computed(() => !!awayLogoSrc.value && !awayLogoBroken.value)
watch(homeLogoSrc, () => { homeLogoBroken.value = false })
watch(awayLogoSrc, () => { awayLogoBroken.value = false })

const isLive = computed(() => isLiveMatch(props.match))
const isFinished = computed(() => isFinishedMatch(props.match))
const matchTimestamp = computed(() => getMatchTimestamp(props.match))
const statusLabel = computed(() => getStatusText(props.match))
/* Kept for row state styling (stale / postponed fixtures read differently). */
const state = computed(() => isLive.value
  ? 'live'
  : isFinished.value
    ? 'finished'
    : getDisplayStatusKey(props.match) === 'STALE' ? 'stale' : 'scheduled')
const primaryActionLabel = computed(() => isFinished.value ? t('matches.viewReview') : t('matches.viewPrediction'))

/* Left rail carries the kickoff time (or FT / live state), so the score keeps
   the centre column and every row shares the same left edge. */
const whenIsStatus = computed(() => isLive.value || isFinished.value)
const whenPrimary = computed(() => {
  // Localised state, never a hardcoded "FT": the zh UI must read 已结束.
  if (whenIsStatus.value) return statusLabel.value
  return matchTimestamp.value ? formatTime(props.match) : t('matches.timePending')
})
const whenSecondary = computed(() => {
  if (isLive.value || isFinished.value) return ''
  return statusLabel.value
})

const handleMoreCommand = (command) => {
  if (command === 'h2h') {
    emit('h2h', fixture.value?.id, homeTeam.value?.id, awayTeam.value?.id, homeTeam.value?.name, awayTeam.value?.name)
  } else if (command === 'agent') {
    emit('agent', props.match)
  } else if (command === 'details') {
    emit('details', props.match)
  }
}

const formatTime = match => {
  const pendingText = t('matches.timePending')
  const full = formatMatchTime(match)
  if (full.includes(pendingText)) return pendingText
  const timestamp = getMatchTimestamp(match)
  return timestamp ? new Intl.DateTimeFormat(locale.value === 'zh' ? 'zh-CN' : 'en-US', { timeZone: 'Asia/Shanghai', hour: '2-digit', minute: '2-digit' }).format(new Date(timestamp)) : pendingText
}
</script>

<style scoped>
/* Row anatomy: [when] [home] [score] [away] [actions], plus an optional
   context line. Hairlines separate rows, hover tints the row, and the action
   cluster stays quiet so the score remains the loudest element. */
.match-card {
  position: relative;
  display: grid;
  grid-template-columns: 72px minmax(0, 1fr) 74px minmax(0, 1fr) auto;
  grid-template-areas:
    'when home center away actions'
    'when meta meta meta meta';
  align-items: center;
  column-gap: 16px;
  min-height: 62px;
  padding: 0 18px;
  background: var(--ff-surface);
  border-bottom: 1px solid var(--ff-border);
  transition: background-color 120ms ease;
}
.match-card:last-child { border-bottom: 0; }
.match-card:hover { background: var(--ff-surface-soft); }
.match-card.is-focus { background: linear-gradient(90deg, var(--ff-primary-soft), rgba(233, 243, 238, 0) 55%); }
.focus-bar {
  position: absolute;
  left: 0;
  top: 10px;
  bottom: 10px;
  width: 3px;
  border-radius: 0 3px 3px 0;
  background: var(--ff-primary);
}

.match-when {
  grid-area: when;
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 3px;
  text-align: right;
  font-variant-numeric: tabular-nums;
}
.when-primary {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 12.5px;
  font-weight: 650;
  letter-spacing: -0.01em;
  color: var(--ff-text);
  white-space: nowrap;
}
.when-primary.is-live { color: var(--ff-live); font-size: 11px; font-weight: 700; letter-spacing: 0.02em; }
.when-primary.is-status { font-size: 11.5px; font-weight: 650; letter-spacing: 0.005em; }
.when-secondary {
  font-size: 10px;
  font-weight: 600;
  letter-spacing: 0.02em;
  color: var(--ff-text-faint);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 72px;
}

.match-side {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}
.match-side--home { grid-area: home; justify-content: flex-end; }
.match-side--away { grid-area: away; justify-content: flex-start; }

.team-logo,
.logo-placeholder {
  width: 26px;
  height: 26px;
  flex: none;
  object-fit: contain;
}
.logo-placeholder {
  display: grid;
  place-items: center;
  border-radius: 6px;
  background: var(--ff-surface-quiet);
  color: var(--ff-text-faint);
  font-size: 11px;
  font-weight: 700;
  border: 1px solid var(--ff-border);
}
.team-logo { cursor: pointer; }

.team-name {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 14.5px;
  font-weight: 600;
  letter-spacing: -0.012em;
  line-height: 1.3;
  color: var(--ff-text-strong);
  cursor: pointer;
}
.match-side--home .team-name { text-align: right; }
.team-name:hover { color: var(--ff-primary); }
.team-name:focus-visible,
.logo-placeholder:focus-visible,
.team-logo:focus-visible { outline: 2px solid var(--ff-primary); outline-offset: 3px; border-radius: 4px; }

.match-center {
  grid-area: center;
  display: flex;
  align-items: center;
  justify-content: center;
  min-width: 0;
}
.score-line {
  display: inline-flex;
  align-items: baseline;
  gap: 1px;
  font-size: 20px;
  font-weight: 700;
  letter-spacing: -0.02em;
  line-height: 1.1;
  color: var(--ff-text-strong);
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}
.score-num { min-width: 0.62em; text-align: center; }
.score-sep { margin: 0 0.24em; color: var(--ff-text-faint); font-weight: 500; }
.score-line--vs { font-size: 12px; font-weight: 600; letter-spacing: 0.02em; color: var(--ff-text-faint); }

.live-dot {
  width: 6px;
  height: 6px;
  flex: none;
  border-radius: 50%;
  background: var(--ff-live);
  animation: livePulse 1.8s ease-in-out infinite;
}
@keyframes livePulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.35; }
}

.action-area {
  grid-area: actions;
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 2px;
  min-width: 0;
  align-self: stretch;
  padding-left: 16px;
  border-left: 1px solid var(--ff-border);
}
.action-area :deep(.el-button) { margin-left: 0 !important; }
.action-details {
  --el-button-text-color: var(--ff-text-muted);
  height: 30px;
  padding: 0 8px;
  border-radius: 8px;
  font-size: 12px;
  font-weight: 600;
}
.action-details:hover { --el-button-text-color: var(--ff-text-strong); background: var(--ff-surface-quiet); }
.favorite-btn,
.more-btn {
  --el-button-text-color: var(--ff-text-faint);
  width: 30px;
  height: 30px;
  padding: 0;
  border-radius: 8px;
  font-size: 15px;
}
.favorite-btn:hover,
.more-btn:hover { --el-button-text-color: var(--ff-primary); background: var(--ff-surface-quiet); }
.favorite-btn.is-favorited { --el-button-text-color: var(--ff-gold); }
.more-actions { display: inline-flex; }
.more-actions :deep(.el-tooltip__trigger) { display: inline-flex; }

.action-primary {
  height: 30px;
  margin-left: 8px;
  padding: 0 13px;
  border: 0;
  border-radius: 8px;
  font-size: 12px;
  font-weight: 650;
  letter-spacing: 0.01em;
  color: var(--ff-primary);
  background: var(--ff-primary-soft);
  box-shadow: none;
  transition: background-color 120ms ease, color 120ms ease;
}
.action-primary:hover,
.action-primary:focus-visible {
  color: #fff;
  background: var(--ff-primary);
}
.action-details:focus-visible,
.favorite-btn:focus-visible,
.more-btn:focus-visible { outline: 2px solid var(--ff-primary); outline-offset: 2px; }

.match-meta {
  grid-area: meta;
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 0 0 10px;
  margin-top: -4px;
  font-size: 11px;
  color: var(--ff-text-faint);
  min-width: 0;
}
.match-meta .meta-item { display: inline-flex; align-items: center; gap: 4px; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.match-meta .meta-item--focus { color: var(--ff-primary); font-weight: 600; }

@media (max-width: 860px) {
  .match-card {
    grid-template-columns: 58px minmax(0, 1fr) auto;
    grid-template-areas:
      'when home center'
      'when away center'
      'actions actions actions'
      'meta meta meta';
    column-gap: 10px;
    row-gap: 2px;
    padding: 12px 14px;
    min-height: 0;
  }
  .match-when { align-items: flex-start; text-align: left; }
  .when-secondary { max-width: 58px; }
  .when-primary.is-status { font-size: 10.5px; }
  .match-side--home,
  .match-side--away { justify-content: flex-start; }
  .match-side--home { flex-direction: row-reverse; justify-content: flex-end; }
  .match-side--home .team-name { text-align: left; }
  .match-side--away { margin-top: 2px; }
  .team-logo,
  .logo-placeholder { width: 24px; height: 24px; }
  .match-center { align-self: center; justify-content: flex-end; padding-left: 8px; }
  .score-line { font-size: 19px; }
  .action-area {
    align-self: auto;
    border-left: 0;
    padding-left: 0;
    justify-content: flex-end;
    margin-top: 4px;
    padding-top: 6px;
    border-top: 1px dashed var(--ff-border);
  }
  .action-details { display: none; }
  .action-primary { margin-left: 4px; height: 32px; }
  .favorite-btn,
  .more-btn { width: 32px; height: 32px; }
  .focus-bar { top: 14px; bottom: 14px; }
}
@media (pointer: coarse) {
  .favorite-btn,
  .more-btn { width: 34px; height: 34px; }
}
@media (prefers-reduced-motion: reduce) {
  .live-dot { animation: none; }
}
</style>
