<template>
  <div class="match-card" @click="$emit('predict', match)">
    <div class="league-tag">
      <img v-if="match?.league?.logo" :src="match.league.logo" class="league-logo" />
      <span>{{ match?.league?.name }}</span>
    </div>
    <div class="match-info">
      <div class="team-row">
        <div class="team">
          <img v-if="homeLogoVisible" :src="homeLogoSrc" class="team-logo" @error="homeLogoBroken = true" @click.stop="$emit('teamClick', homeTeam?.name, 'home')" />
          <span v-else class="logo-placeholder" @click.stop="$emit('teamClick', homeTeam?.name, 'home')">{{ homeTeam?.name?.[0] }}</span>
          <span class="team-name" @click.stop="$emit('teamClick', homeTeam?.name, 'home')">{{ homeTeam?.name }}</span>
        </div>
        <div class="score">
          <span v-if="fixture?.status?.short === 'FT' || goals?.home != null" class="score-text">
            {{ goals?.home }} - {{ goals?.away }}
          </span>
          <span v-else class="match-time">{{ formatTime(fixture?.date) }}</span>
        </div>
        <div class="team">
          <span class="team-name" @click.stop="$emit('teamClick', awayTeam?.name, 'away')">{{ awayTeam?.name }}</span>
          <img v-if="awayLogoVisible" :src="awayLogoSrc" class="team-logo" @error="awayLogoBroken = true" @click.stop="$emit('teamClick', awayTeam?.name, 'away')" />
          <span v-else class="logo-placeholder" @click.stop="$emit('teamClick', awayTeam?.name, 'away')">{{ awayTeam?.name?.[0] }}</span>
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
          <el-tag :type="statusType" size="small">{{ statusText }}</el-tag>
        </div>
      </div>
    </div>
    <div class="action-area">
      <el-button type="primary" size="small" plain @click.stop="$emit('predict', match)">
        <el-icon><TrendCharts /></el-icon>
        智能预测
      </el-button>
      <el-button size="small" text @click.stop="$emit('h2h', fixture?.id, homeTeam?.id, awayTeam?.id, homeTeam?.name, awayTeam?.name)">
        <el-icon><DataLine /></el-icon>
        交锋
      </el-button>
      <el-button size="small" :type="favorited ? 'success' : 'info'" plain @click.stop="$emit('favorite-match', match)">
        <el-icon><Star v-if="!favorited" /><StarFilled v-else /></el-icon>
        {{ favorited ? '已收藏' : '收藏比赛' }}
      </el-button>
    </div>
  </div>
</template>

<script setup>
import { computed, ref, watch } from 'vue'

const props = defineProps({ match: Object, favorited: { type: Boolean, default: false } })
const emit = defineEmits(['predict', 'teamClick', 'h2h', 'favorite-match'])

const fixture = computed(() => props.match?.fixture || {})
const teams = computed(() => props.match?.teams || {})
const homeTeam = computed(() => teams.value.home || {})
const awayTeam = computed(() => teams.value.away || {})
const goals = computed(() => props.match?.goals || {})
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
const homeLogoSrc = computed(() => normalizeLogoUrl(homeTeam.value?.logo))
const awayLogoSrc = computed(() => normalizeLogoUrl(awayTeam.value?.logo))
const homeLogoVisible = computed(() => !!homeLogoSrc.value && !homeLogoBroken.value)
const awayLogoVisible = computed(() => !!awayLogoSrc.value && !awayLogoBroken.value)
watch(homeLogoSrc, () => { homeLogoBroken.value = false })
watch(awayLogoSrc, () => { awayLogoBroken.value = false })

const statusType = computed(() => {
  const s = fixture.value?.status?.short
  if (s === 'FT') return 'success'
  if (s === '1H' || s === '2H' || s === 'HT' || s === 'LIVE') return 'danger'
  if (s === 'PST' || s === 'CANC') return 'info'
  return 'warning'
})

const statusText = computed(() => {
  const s = fixture.value?.status?.short
  const texts = {
    'FT': '完赛',
    '1H': '半场',
    '2H': '下半场',
    'HT': '中场',
    'ET': '加时',
    'PEN': '点球',
    'LIVE': '进行中',
    'NS': '未开赛',
    'PST': '推迟',
    'CANC': '取消',
    'INT': '中断',
    'APD': '延期'
  }
  return texts[s] || s || '未知'
})

const formatTime = (dateStr) => {
  if (!dateStr) return ''
  try {
    const d = new Date(dateStr)
    return d.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
  } catch {
    return ''
  }
}
</script>

<style scoped>
.match-card {
  background: #fff;
  border-radius: 12px;
  padding: 16px;
  cursor: pointer;
  transition: all 0.2s;
  box-shadow: 0 2px 8px rgba(0,0,0,0.06);
  border: 1px solid #eee;
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.match-card:hover {
  transform: translateY(-2px);
  box-shadow: 0 8px 24px rgba(64, 158, 255, 0.15);
  border-color: #409EFF;
}

.league-tag {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: #888;
}
.league-logo {
  width: 16px;
  height: 16px;
  border-radius: 2px;
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
  width: 32px;
  height: 32px;
  object-fit: contain;
  border-radius: 4px;
  flex-shrink: 0;
}
.logo-placeholder {
  width: 32px;
  height: 32px;
  border-radius: 4px;
  background: #e8e8e8;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 14px;
  color: #999;
  flex-shrink: 0;
}
.team-name {
  font-size: 13px;
  font-weight: 600;
  color: #333;
  max-width: 80px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  cursor: pointer;
}
.team-name:hover {
  color: #409EFF;
  text-decoration: underline;
}
.team:last-child .team-name {
  text-align: right;
}

.score {
  min-width: 70px;
  text-align: center;
}
.score-text {
  font-size: 22px;
  font-weight: 700;
  color: #1a1a2e;
}
.match-time {
  font-size: 14px;
  color: #409EFF;
  font-weight: 600;
}

.match-meta {
  display: flex;
  align-items: center;
  gap: 12px;
  font-size: 11px;
  color: #999;
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

.action-area {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding-top: 8px;
  border-top: 1px solid #f0f0f0;
}
.action-area .el-button {
  flex: 1;
}
.action-area .el-button:first-child {
  flex: 2;
}
.action-area .el-button:not(:first-child) {
  margin-left: 8px;
}
</style>
