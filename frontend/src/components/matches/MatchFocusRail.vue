<template>
  <section class="focus-rail" aria-labelledby="match-focus-title">
    <div class="focus-head">
      <div>
        <span class="focus-kicker">FOCUS</span>
        <h2 id="match-focus-title">比赛焦点</h2>
        <p>{{ contextLabel }}</p>
      </div>
      <div class="focus-head-actions">
        <span v-if="meta?.returnedCount != null" class="focus-count">{{ meta.returnedCount }} 场</span>
        <button v-if="error && items.length" type="button" class="focus-retry-link" @click="$emit('retry')">重新更新</button>
      </div>
    </div>

    <div v-if="loading" class="focus-list focus-list-skeleton" aria-live="polite" aria-label="正在加载比赛焦点">
      <div v-for="index in 3" :key="index" class="focus-skeleton-card"><i></i><b></b><em></em></div>
    </div>
    <div v-else-if="error && !items.length" class="focus-state focus-state-error" role="alert">
      <strong>比赛焦点暂时无法更新</strong>
      <span>{{ error }}</span>
      <button type="button" @click="$emit('retry')">重试</button>
    </div>
    <div v-else-if="!items.length" class="focus-state" aria-live="polite">
      <strong>{{ emptyTitle }}</strong>
      <span>{{ emptyDescription }}</span>
      <button type="button" @click="$emit('view-all')">查看全部赛程</button>
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
            <span class="focus-team"><img v-if="homeLogo(match)" :src="homeLogo(match)" :alt="`${homeName(match)}队徽`" @error="onLogoError" /><b v-else>{{ initial(homeName(match)) }}</b><strong>{{ homeName(match) }}</strong></span>
            <span class="focus-center"><b>{{ scoreOrTime(match) }}</b><small>{{ tier(match) === 'LIVE' ? '进行中' : kickoffLabel(match) }}</small></span>
            <span class="focus-team away"><strong>{{ awayName(match) }}</strong><img v-if="awayLogo(match)" :src="awayLogo(match)" :alt="`${awayName(match)}队徽`" @error="onLogoError" /><b v-else>{{ initial(awayName(match)) }}</b></span>
          </span>
          <span class="focus-reasons">
            <em v-for="reason in reasons(match).slice(0, 2)" :key="reason">{{ reason }}</em>
          </span>
        </button>
        <div class="focus-card-actions">
          <button type="button" @click="$emit('predict', match)">{{ tier(match) === 'RECENT' ? '查看复盘' : '查看预测' }}</button>
        </div>
      </article>
    </div>
    <p v-if="stale" class="focus-stale-note" role="status">推荐数据暂未刷新，显示上次成功读取的结果。</p>
  </section>
</template>

<script setup>
import { computed } from 'vue'
import { formatMatchTime, getMatchTimestamp, getStatusText, isFinished as isFinishedMatch, isLive as isLiveMatch } from '../../utils/match'
import { getTeamDisplayName } from '../../utils/teamNames'

const props = defineProps({
  items: { type: Array, default: () => [] },
  meta: { type: Object, default: () => ({}) },
  date: { type: String, default: '' },
  loading: { type: Boolean, default: false },
  error: { type: String, default: '' },
  stale: { type: Boolean, default: false },
  teamNameMode: { type: String, default: 'en' }
})
defineEmits(['open', 'predict', 'retry', 'view-all'])

const contextLabel = computed(() => props.date ? `${props.date} 的重点比赛` : '正在进行、即将开始或值得关注的比赛')
const emptyTitle = computed(() => props.meta?.emptyReason === 'NO_MATCHES_IN_WINDOW' ? '当前日期没有比赛' : '暂无重点比赛')
const emptyDescription = computed(() => props.meta?.emptyReason === 'NO_VISIBLE_FOCUS_MATCHES' ? '当前数据已读取，但没有符合焦点规则的赛事。' : '当前没有正在进行或临近开赛的重点赛事。')
const team = (match, side) => match?.teams?.[side] || {}
const homeName = match => getTeamDisplayName(team(match, 'home').name || match?.homeTeamName || '主队', props.teamNameMode)
const awayName = match => getTeamDisplayName(team(match, 'away').name || match?.awayTeamName || '客队', props.teamNameMode)
const leagueName = match => match?.league?.name || match?.leagueName || '其他赛事'
const homeLogo = match => team(match, 'home').logo || match?.homeTeamLogo || ''
const awayLogo = match => team(match, 'away').logo || match?.awayTeamLogo || ''
const initial = name => String(name || '?').trim().slice(0, 1).toUpperCase()
const recommendationKey = (match, index) => match?.matchId || match?.id || match?.fixtureId || `${homeName(match)}-${awayName(match)}-${index}`
const tier = match => String(match?.recommendation?.tier || (isLiveMatch(match) ? 'LIVE' : isFinishedMatch(match) ? 'RECENT' : 'UPCOMING')).toUpperCase()
const statusText = match => getStatusText(match) || (tier(match) === 'LIVE' ? '进行中' : tier(match) === 'RECENT' ? '已结束' : '未开始')
const reasons = match => Array.isArray(match?.recommendation?.reasonTexts) && match.recommendation.reasonTexts.length ? match.recommendation.reasonTexts : ['当前赛程焦点']
const timestamp = match => getMatchTimestamp(match)
const kickoffLabel = match => timestamp(match) ? formatMatchTime(match) : '时间待同步'
const scoreOrTime = match => {
  const home = match?.goals?.home ?? match?.homeScore
  const away = match?.goals?.away ?? match?.awayScore
  if ((tier(match) === 'LIVE' || tier(match) === 'RECENT') && home != null && away != null) return `${home} - ${away}`
  if (!timestamp(match)) return '—'
  return new Intl.DateTimeFormat('zh-CN', { timeZone: 'Asia/Shanghai', hour: '2-digit', minute: '2-digit' }).format(new Date(timestamp(match)))
}
const onLogoError = event => { event.target.style.display = 'none' }
</script>

<style scoped>
.focus-rail { margin: 0 0 18px; padding: 18px; border: 1px solid var(--ff-border); border-radius: var(--ff-radius-lg); background: var(--ff-surface); }
.focus-head { display:flex; align-items:flex-start; justify-content:space-between; gap:16px; margin-bottom:14px; }
.focus-kicker { color:var(--ff-primary); font:700 10px/1 var(--ff-mono); letter-spacing:.14em; }
.focus-head h2 { margin:6px 0 3px; color:var(--ff-text-strong); font-size:18px; letter-spacing:-.02em; }
.focus-head p { margin:0; color:var(--ff-text-muted); font-size:12px; }
.focus-head-actions { display:flex; align-items:center; gap:10px; color:var(--ff-text-faint); font-size:12px; white-space:nowrap; }
.focus-count { font-family:var(--ff-mono); }
.focus-retry-link,.focus-state button,.focus-card-actions button { border:0; background:none; color:var(--ff-primary); cursor:pointer; font:inherit; }
.focus-retry-link:hover,.focus-state button:hover,.focus-card-actions button:hover { text-decoration:underline; }
.focus-list { display:grid; grid-template-columns:repeat(3,minmax(0,1fr)); gap:10px; }
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
@media (max-width:620px) { .focus-rail { padding:14px; } .focus-list { grid-template-columns:1fr; } .focus-card-actions { justify-content:space-between; } }
</style>
