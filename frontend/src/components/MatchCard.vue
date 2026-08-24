<template>
  <div class="match-card" :data-match-id="getMatchId(match)">
    <div class="match-info">
      <div class="team-row">
        <div class="team">
          <img v-if="homeLogoVisible" :src="homeLogoSrc" class="team-logo" :alt="`${homeDisplayName}队徽`" @error="homeLogoBroken = true" @click.stop="$emit('teamClick', homeTeam?.name, 'home', match)" />
          <span v-else class="logo-placeholder" :aria-label="`查看${homeDisplayName}资料`" role="button" tabindex="0" @click.stop="$emit('teamClick', homeTeam?.name, 'home', match)" @keydown.enter.stop="$emit('teamClick', homeTeam?.name, 'home', match)" @keydown.space.prevent.stop="$emit('teamClick', homeTeam?.name, 'home', match)">{{ homeDisplayName?.[0] }}</span>
          <span class="team-name" role="button" tabindex="0" :aria-label="`查看${homeDisplayName}资料`" @click.stop="$emit('teamClick', homeTeam?.name, 'home', match)" @keydown.enter.stop="$emit('teamClick', homeTeam?.name, 'home', match)" @keydown.space.prevent.stop="$emit('teamClick', homeTeam?.name, 'home', match)">{{ homeDisplayName }}</span>
        </div>
        <div class="score">
          <span v-if="(isFinished || isLive) && goals?.home != null && goals?.away != null" class="score-text">
            {{ goals.home }} - {{ goals.away }}
          </span>
          <span v-else class="match-time" :class="{ 'is-unknown': !matchTimestamp }">{{ isFinished ? '比分待同步' : matchTimestamp ? formatTime(match) : '时间待同步' }}</span>
        </div>
        <div class="team">
          <span class="team-name" role="button" tabindex="0" :aria-label="`查看${awayDisplayName}资料`" @click.stop="$emit('teamClick', awayTeam?.name, 'away', match)" @keydown.enter.stop="$emit('teamClick', awayTeam?.name, 'away', match)" @keydown.space.prevent.stop="$emit('teamClick', awayTeam?.name, 'away', match)">{{ awayDisplayName }}</span>
          <img v-if="awayLogoVisible" :src="awayLogoSrc" class="team-logo" :alt="`${awayDisplayName}队徽`" @error="awayLogoBroken = true" @click.stop="$emit('teamClick', awayTeam?.name, 'away', match)" />
          <span v-else class="logo-placeholder" :aria-label="`查看${awayDisplayName}资料`" role="button" tabindex="0" @click.stop="$emit('teamClick', awayTeam?.name, 'away', match)" @keydown.enter.stop="$emit('teamClick', awayTeam?.name, 'away', match)" @keydown.space.prevent.stop="$emit('teamClick', awayTeam?.name, 'away', match)">{{ awayDisplayName?.[0] }}</span>
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
          <el-tag :type="statusType" size="small" effect="plain">
            <span v-if="isLive" class="live-dot"></span>{{ statusLabel }}
          </el-tag>
        </div>
      </div>
    </div>
    <div class="action-area" aria-label="比赛操作">
      <el-button class="action-primary" type="primary" size="small" plain @click.stop="$emit('predict', match)">
        <el-icon><TrendCharts /></el-icon>
        {{ primaryActionLabel }}
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
          </el-dropdown-menu>
        </template>
      </el-dropdown>
    </div>
  </div>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { ChatLineSquare, DataLine, MoreFilled, Star, StarFilled, TrendCharts } from '@element-plus/icons-vue'
import { formatMatchTime, getAwayTeam, getDisplayStatusKey, getHomeTeam, getMatchId, getMatchTimestamp, getStatusText, isFinished as isFinishedMatch, isLive as isLiveMatch } from '../utils/match'
import { getTeamDisplayName } from '../utils/teamNames'
import { getMediaAssetUrl } from '../utils/mediaAsset'

const props = defineProps({ match: Object, favorited: { type: Boolean, default: false }, teamNameMode: { type: String, default: 'en' } })
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
  background: #ffffff;
  border-radius: 8px;
  padding: 16px;
  overflow: hidden;
  transition: background-color var(--ff-transition), border-color var(--ff-transition);
  border: 1px solid var(--ff-border);
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.match-card:hover {
  border-color: var(--ff-border-strong);
  background: var(--ff-surface-soft);
}
.match-card.focus-target {
  border-color: var(--ff-primary);
  box-shadow: 0 0 0 3px var(--ff-primary-soft);
}
.match-card:active {
  background: var(--ff-primary-soft);
}

.match-info {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.team-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.team {
  display: flex;
  align-items: center;
  gap: 8px;
  flex: 1;
  min-width: 0;
}
.team:last-child {
  flex-direction: row-reverse;
}
.team-logo {
  width: 30px;
  height: 30px;
  object-fit: contain;
  border-radius: 4px;
  flex-shrink: 0;
}
.logo-placeholder {
  width: 30px;
  height: 30px;
  border-radius: 6px;
  background: var(--ff-bg-alt);
  border: 1px solid var(--ff-border);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  color: var(--ff-text-muted);
  flex-shrink: 0;
  font-weight: 600;
  font-family: var(--ff-mono);
}
.team-name {
  font-size: 13px;
  font-weight: 600;
  color: var(--ff-text);
  max-width: 112px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  cursor: pointer;
  transition: color 0.15s ease;
}
.team-name:hover {
  color: var(--ff-primary);
}
.team-name:focus-visible,
.logo-placeholder:focus-visible {
  outline: 2px solid var(--ff-primary);
  outline-offset: 2px;
}
.team:last-child .team-name {
  text-align: right;
}

.score {
  min-width: 76px;
  text-align: center;
}
.score-text {
  display: inline-block;
  font-size: 20px;
  font-weight: 600;
  font-family: var(--ff-mono);
  font-variant-numeric: tabular-nums;
  color: var(--ff-text-strong);
  letter-spacing: 0.02em;
  min-width: 48px;
  line-height: 1.3;
}
.match-time {
  font-size: 13px;
  font-weight: 600;
  font-family: var(--ff-mono);
  font-variant-numeric: tabular-nums;
  letter-spacing: 0.02em;
  color: var(--ff-primary);
}
.match-time.is-unknown { color:var(--ff-text-muted); font-size:11px; }

.match-meta {
  display: flex;
  align-items: center;
  gap: 12px;
  font-size: 11px;
  color: var(--ff-text-muted);
  border-top: 1px solid var(--ff-border);
  padding-top: 10px;
}
.match-meta > div {
  display: flex;
  align-items: center;
  gap: 3px;
}
.venue {
  max-width: 100px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.status { margin-left: auto; }
.live-dot {
  display: inline-block;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--ff-success);
  margin-right: 4px;
  animation: livePulse 1.2s infinite;
  vertical-align: middle;
}
@keyframes livePulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.4; }
}

.action-area {
  display: grid;
  grid-template-columns: minmax(0, 1.35fr) minmax(0, 1.15fr) 34px 34px;
  gap: 8px;
  align-items: center;
  padding-top: 4px;
}
.action-area .el-button {
  min-width: 0;
  margin-left: 0 !important;
}
.action-primary {
  background: var(--ff-primary);
  border: none;
  color: #ffffff;
  font-weight: 600;
  letter-spacing: 0.02em;
  border-radius: 6px;
  box-shadow: var(--ff-shadow-sm);
  transition: border-color var(--ff-transition-fast), background-color var(--ff-transition-fast), color var(--ff-transition-fast);
}
.action-primary:hover {
  background: var(--ff-primary-hover);
  color: #ffffff;
}
.action-details { border-color:var(--ff-border); color:var(--ff-text-muted); background:var(--ff-surface-quiet); }
.action-details:hover { border-color:var(--ff-primary); color:var(--ff-primary); background:var(--ff-primary-soft); }
.favorite-btn,
.more-btn {
  width: 34px;
  height: 34px;
  padding: 0;
  border-color: var(--ff-border);
  color: var(--ff-text-muted);
  background: var(--ff-surface-quiet);
}
.favorite-btn:hover,
.more-btn:hover,
.favorite-btn.is-favorited {
  border-color: var(--ff-primary);
  color: var(--ff-primary);
  background: var(--ff-primary-soft);
}
.more-actions {
  display: inline-flex;
  width: 34px;
}
.more-actions :deep(.el-tooltip__trigger) {
  display: inline-flex;
}
.action-area .el-button:active { box-shadow: none; }

@media (max-width: 420px) {
  .action-area {
    grid-template-columns: minmax(0, 1fr) 34px 34px;
  }
  .action-details {
    grid-column: 1 / -1;
    grid-row: 2;
  }
}
</style>
