<template>
  <div class="match-card" :class="{ 'is-focus': focused }" :data-match-id="getMatchId(match)">
    <div class="match-info">
      <div class="team-row">
        <div class="team">
          <img v-if="homeLogoVisible" :src="homeLogoSrc" class="team-logo" width="30" height="30" loading="lazy" decoding="async" :alt="`${homeDisplayName}队徽`" @error="homeLogoBroken = true" @click.stop="$emit('teamClick', homeTeam?.name, 'home', match)" />
          <span v-else class="logo-placeholder" title="暂无队徽" :aria-label="`${homeDisplayName}暂无队徽，查看资料`" role="button" tabindex="0" @click.stop="$emit('teamClick', homeTeam?.name, 'home', match)" @keydown.enter.stop="$emit('teamClick', homeTeam?.name, 'home', match)" @keydown.space.prevent.stop="$emit('teamClick', homeTeam?.name, 'home', match)">{{ homeDisplayName?.[0] }}</span>
          <span class="team-name" role="button" tabindex="0" :title="homeDisplayName" :aria-label="`查看${homeDisplayName}资料`" @click.stop="$emit('teamClick', homeTeam?.name, 'home', match)" @keydown.enter.stop="$emit('teamClick', homeTeam?.name, 'home', match)" @keydown.space.prevent.stop="$emit('teamClick', homeTeam?.name, 'home', match)">{{ homeDisplayName }}</span>
        </div>
        <div class="score">
          <span v-if="(isFinished || isLive) && goals?.home != null && goals?.away != null" class="score-text">
            {{ goals.home }} - {{ goals.away }}
          </span>
          <span v-else class="match-time" :class="{ 'is-unknown': !matchTimestamp }">{{ isFinished ? '比分待同步' : matchTimestamp ? formatTime(match) : '时间待同步' }}</span>
        </div>
        <div class="team">
          <span class="team-name" role="button" tabindex="0" :title="awayDisplayName" :aria-label="`查看${awayDisplayName}资料`" @click.stop="$emit('teamClick', awayTeam?.name, 'away', match)" @keydown.enter.stop="$emit('teamClick', awayTeam?.name, 'away', match)" @keydown.space.prevent.stop="$emit('teamClick', awayTeam?.name, 'away', match)">{{ awayDisplayName }}</span>
          <img v-if="awayLogoVisible" :src="awayLogoSrc" class="team-logo" width="30" height="30" loading="lazy" decoding="async" :alt="`${awayDisplayName}队徽`" @error="awayLogoBroken = true" @click.stop="$emit('teamClick', awayTeam?.name, 'away', match)" />
          <span v-else class="logo-placeholder" title="暂无队徽" :aria-label="`${awayDisplayName}暂无队徽，查看资料`" role="button" tabindex="0" @click.stop="$emit('teamClick', awayTeam?.name, 'away', match)" @keydown.enter.stop="$emit('teamClick', awayTeam?.name, 'away', match)" @keydown.space.prevent.stop="$emit('teamClick', awayTeam?.name, 'away', match)">{{ awayDisplayName?.[0] }}</span>
        </div>
      </div>
      <div class="match-meta">
        <div class="venue" v-if="fixture?.venue?.name">
          <el-icon :size="12"><Location /></el-icon>
          {{ fixture.venue.name }}
        </div>
        <div class="round" v-if="match?.league?.round">
          <el-icon :size="12"><Trophy /></el-icon>
          {{ match.league.round }}
        </div>
        <div class="status">
          <span v-if="focused" class="focus-mark">焦点</span>
          <el-tag :type="statusType" size="small" effect="plain">
            <span v-if="isLive" class="live-dot"></span>{{ statusLabel }}
          </el-tag>
        </div>
      </div>
    </div>
    <div class="action-area" aria-label="比赛操作">
      <el-button class="action-primary" type="primary" size="small" plain :aria-label="primaryActionLabel + '：打开本场分析'" @click.stop="$emit('predict', match)">
        <span class="action-copy-full">{{ primaryActionLabel }}</span>
        <span class="action-copy-short">{{ isFinished ? '复盘' : '预测' }}</span>
      </el-button>
      <el-button class="action-details" size="small" plain @click.stop="$emit('details', match)">
        赛事数据
      </el-button>
      <el-tooltip :content="favorited ? '取消收藏' : '收藏比赛'" placement="top">
        <el-button class="favorite-btn" :class="{ 'is-favorited': favorited }" size="small" circle plain :aria-label="favorited ? '取消收藏比赛' : '收藏比赛'" @click.stop="$emit('favorite-match', match)">
        <el-icon><Star v-if="!favorited" /><StarFilled v-else /></el-icon>
        </el-button>
      </el-tooltip>
      <el-dropdown class="more-actions" trigger="click" @command="handleMoreCommand">
        <el-button class="more-btn" size="small" circle plain aria-label="更多比赛操作" title="更多比赛操作">
          <el-icon><MoreFilled /></el-icon>
        </el-button>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item command="h2h">
              <el-icon><DataLine /></el-icon>
              历史交锋
            </el-dropdown-item>
            <el-dropdown-item command="agent">
              <el-icon><ChatLineSquare /></el-icon>
              AI 助手
            </el-dropdown-item>
            <el-dropdown-item command="details" divided>
              赛事数据
            </el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
    </div>
  </div>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { ChatLineSquare, DataLine, Location, MoreFilled, Star, StarFilled, TrendCharts, Trophy } from '@element-plus/icons-vue'
import { formatMatchTime, getAwayTeam, getDisplayStatusKey, getHomeTeam, getMatchId, getMatchTimestamp, getStatusText, isFinished as isFinishedMatch, isLive as isLiveMatch } from '../utils/match'
import { getTeamDisplayName } from '../utils/teamNames'
import { getMediaAssetUrl } from '../utils/mediaAsset'

const props = defineProps({ match: Object, favorited: { type: Boolean, default: false }, focused: { type: Boolean, default: false }, teamNameMode: { type: String, default: 'en' } })
const emit = defineEmits(['predict', 'teamClick', 'h2h', 'agent', 'details', 'favorite-match'])

const fixture = computed(() => ({
  ...(props.match?.fixture || {}),
  date: props.match?.fixture?.date || props.match?.matchTime,
  id: props.match?.fixture?.id || getMatchId(props.match),
  status: { ...(props.match?.fixture?.status || {}), short: props.match?.fixture?.status?.short || props.match?.status }
}))
const homeTeam = computed(() => {
  const team = getHomeTeam(props.match) || {}
  return { ...team, name: team.name || props.match?.homeTeamName || '主队', logo: team.logo || props.match?.homeTeamLogo || '' }
})
const awayTeam = computed(() => {
  const team = getAwayTeam(props.match) || {}
  return { ...team, name: team.name || props.match?.awayTeamName || '客队', logo: team.logo || props.match?.awayTeamLogo || '' }
})
const homeDisplayName = computed(() => getTeamDisplayName(homeTeam.value.name, props.teamNameMode))
const awayDisplayName = computed(() => getTeamDisplayName(awayTeam.value.name, props.teamNameMode))
const goals = computed(() => props.match?.goals || { home: props.match?.homeScore, away: props.match?.awayScore })
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
const statusType = computed(() => isLive.value ? 'danger' : isFinished.value ? 'success' : getDisplayStatusKey(props.match) === 'STALE' ? 'warning' : 'info')
const primaryActionLabel = computed(() => isFinished.value ? '查看复盘' : '查看预测')

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
  const full = formatMatchTime(match)
  if (full.includes('时间待同步')) return '时间待同步'
  const timestamp = getMatchTimestamp(match)
  return timestamp ? new Intl.DateTimeFormat('zh-CN', { timeZone: 'Asia/Shanghai', hour: '2-digit', minute: '2-digit' }).format(new Date(timestamp)) : '时间待同步'
}
</script>

<style scoped>
.match-card {
  position: relative;
  background: #fff;
  border-radius: 0;
  padding: 8px 12px;
  overflow: hidden;
  width: 100%;
  max-width: 100%;
  min-width: 0;
  border: 0;
  border-bottom: 1px solid var(--ff-border);
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: center;
  gap: 6px 10px;
}
.match-card:hover { background: var(--ff-surface-quiet); }
.match-card.focus-target { background: var(--ff-primary-soft); box-shadow: inset 3px 0 0 var(--ff-primary); }
.match-info { display: flex; flex-direction: column; gap: 2px; min-width: 0; }
.team-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto minmax(0, 1fr);
  align-items: center;
  gap: 8px;
  min-width: 0;
  width: 100%;
}
.team { display: flex; align-items: center; gap: 6px; min-width: 0; }
.team:last-child { flex-direction: row-reverse; }
.team-logo, .logo-placeholder {
  width: 22px; height: 22px; object-fit: contain; border-radius: 2px; flex-shrink: 0;
}
.logo-placeholder {
  background: var(--ff-bg-alt);
  border: 1px solid var(--ff-border);
  display: flex; align-items: center; justify-content: center;
  font-size: 11px; color: var(--ff-text-muted); font-weight: 700;
}
.team-name {
  font-size: 14px; font-weight: 650; color: var(--ff-text-strong);
  min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
  cursor: pointer; line-height: 1.25;
}
.team:last-child .team-name { text-align: right; }
.team-name:hover { color: var(--ff-primary); }
.team-name:focus-visible, .logo-placeholder:focus-visible {
  outline: 2px solid var(--ff-primary); outline-offset: 2px;
}
.score { text-align: center; min-width: 52px; }
.score-text {
  font-size: 18px; font-weight: 700; font-family: var(--ff-mono);
  font-variant-numeric: tabular-nums; color: var(--ff-text-strong); line-height: 1;
}
.match-time {
  font-size: 13px; font-weight: 700; font-family: var(--ff-mono);
  font-variant-numeric: tabular-nums; color: var(--ff-text-strong);
}
.match-time.is-unknown { color: var(--ff-text-muted); font-size: 11px; font-weight: 600; }
.match-meta {
  display: flex; align-items: center; gap: 8px;
  font-size: 11px; color: var(--ff-text-muted); min-width: 0; width: 100%;
}
.match-meta > div { display: flex; align-items: center; gap: 3px; min-width: 0; }
.venue { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; max-width: 42%; }
.round { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; max-width: 36%; }
.status { margin-left: auto; flex: none; }
.live-dot {
  display: inline-block; width: 6px; height: 6px; border-radius: 50%;
  background: var(--ff-live); margin-right: 4px; vertical-align: middle;
}
.action-area { display: flex; gap: 6px; align-items: center; min-width: 0; }
.action-area :deep(.el-button) { min-width: 0; margin-left: 0 !important; }
.action-primary {
  --el-button-bg-color: transparent;
  --el-button-border-color: var(--ff-primary);
  --el-button-text-color: var(--ff-primary);
  --el-button-hover-bg-color: var(--ff-primary);
  --el-button-hover-text-color: #fff;
  --el-button-hover-border-color: var(--ff-primary);
  background: transparent;
  border-color: var(--ff-primary);
  color: var(--ff-primary);
  font-weight: 650;
  font-size: 12px;
  border-radius: 4px;
  box-shadow: none;
  height: 32px;
  padding: 0 10px;
}
.action-details {
  --el-button-bg-color: transparent;
  border-color: var(--ff-border);
  color: var(--ff-text);
  background: transparent;
  font-size: 12px;
  height: 32px;
  border-radius: 4px;
  box-shadow: none;
}
.action-details:hover { border-color: var(--ff-primary); color: var(--ff-primary); }
.favorite-btn, .more-btn {
  width: 32px; height: 32px; padding: 0;
  border-color: var(--ff-border);
  color: var(--ff-text-muted);
  background: transparent;
}
.favorite-btn.is-favorited, .favorite-btn:hover, .more-btn:hover {
  border-color: var(--ff-primary);
  color: var(--ff-primary);
  background: var(--ff-primary-soft);
}
.more-actions { display: inline-flex; }
.more-actions :deep(.el-tooltip__trigger) { display: inline-flex; }
.action-primary:focus-visible,
.action-details:focus-visible,
.favorite-btn:focus-visible,
.more-btn:focus-visible {
  outline: 2px solid var(--ff-primary);
  outline-offset: 2px;
}
.focus-mark { margin-right: 6px; color: var(--ff-primary); font-size: 11px; font-weight: 700; }
.is-focus { box-shadow: inset 2px 0 0 var(--ff-primary); }
.action-copy-short { display: none; }
@media (max-width: 720px) {
  .match-card { grid-template-columns: minmax(0, 1fr); align-items: stretch; padding: 8px 10px 6px; gap: 4px; }
  .team-row { gap: 8px; }
  .team-name { font-size: 14px; }
  .action-area { width: 100%; display: flex; justify-content: flex-end; gap: 4px; }
  .action-copy-full { display: none; }
  .action-copy-short { display: inline; }
  .action-primary { width: auto; min-width: 0; min-height: 32px; height: 32px; padding: 0 8px; flex: none; }
  .action-details { display: none; }
  .favorite-btn, .more-btn { width: 36px; height: 36px; flex: none; }
}
@media (pointer: coarse) {
  .favorite-btn, .more-btn { width: 36px; height: 36px; }
  .action-primary { min-height: 36px; height: 36px; }
}
</style>
