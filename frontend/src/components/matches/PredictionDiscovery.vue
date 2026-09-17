<template>
  <section
    v-if="visibleItems.length"
    id="prediction-discovery"
    class="prediction-discovery"
    aria-labelledby="prediction-discovery-title"
  >
    <div class="discovery-head">
      <div class="discovery-copy">
        <span class="discovery-kicker">PREDICT</span>
        <h2 id="prediction-discovery-title">精选预测入口</h2>
        <p>从焦点赛程快速进入单场分析，不额外刷屏。</p>
      </div>
      <span class="discovery-more">来自比赛焦点</span>
    </div>
    <div class="discovery-list" role="list">
      <button
        v-for="(match, index) in visibleItems"
        :key="itemKey(match, index)"
        type="button"
        class="discovery-chip"
        role="listitem"
        :aria-label="`查看 ${homeName(match)} 对 ${awayName(match)} 的预测`"
        @click="$emit('predict', match)"
      >
        <span class="discovery-league">{{ leagueName(match) }}</span>
        <strong>{{ homeName(match) }} <em>vs</em> {{ awayName(match) }}</strong>
        <span class="discovery-cta">{{ isFinished(match) ? '复盘' : '预测' }}</span>
      </button>
    </div>
  </section>
</template>

<script setup>
import { computed } from 'vue'
import { isFinished as isFinishedMatch } from '../../utils/match'
import { getTeamDisplayName } from '../../utils/teamNames'

const props = defineProps({
  items: { type: Array, default: () => [] },
  teamNameMode: { type: String, default: 'en' },
  limit: { type: Number, default: 4 }
})
defineEmits(['predict'])

const visibleItems = computed(() => (Array.isArray(props.items) ? props.items : []).slice(0, props.limit))
const team = (match, side) => match?.teams?.[side] || {}
const homeName = match => getTeamDisplayName(team(match, 'home').name || match?.homeTeamName || '主队', props.teamNameMode)
const awayName = match => getTeamDisplayName(team(match, 'away').name || match?.awayTeamName || '客队', props.teamNameMode)
const leagueName = match => match?.league?.name || match?.leagueName || '赛事'
const itemKey = (match, index) => match?.matchId || match?.id || match?.fixtureId || `discover-${index}`
const isFinished = match => isFinishedMatch(match)
</script>

<style scoped>
.prediction-discovery {
  margin: 0 0 14px;
  padding: 14px 16px;
  border: 1px solid var(--ff-border);
  border-radius: var(--ff-radius-lg, 14px);
  background: color-mix(in srgb, var(--ff-primary-soft) 55%, var(--ff-surface));
  min-width: 0;
}
.discovery-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 10px;
}
.discovery-kicker {
  color: var(--ff-primary);
  font: 700 10px/1 var(--ff-mono);
  letter-spacing: .14em;
}
.discovery-copy h2 {
  margin: 5px 0 2px;
  color: var(--ff-text-strong);
  font-size: 15px;
  letter-spacing: -.02em;
}
.discovery-copy p {
  margin: 0;
  color: var(--ff-text-muted);
  font-size: 12px;
  line-height: 1.5;
}
.discovery-more {
  flex: none;
  color: var(--ff-primary);
  font-size: 12px;
  font-weight: 600;
  white-space: nowrap;
}
.discovery-list {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 8px;
}
.discovery-chip {
  display: grid;
  gap: 4px;
  min-width: 0;
  padding: 10px 11px;
  border: 1px solid var(--ff-border);
  border-radius: 10px;
  background: var(--ff-surface);
  color: inherit;
  text-align: left;
  cursor: pointer;
  transition: border-color .16s ease, background .16s ease;
}
.discovery-chip:hover,
.discovery-chip:focus-visible {
  border-color: var(--ff-primary);
  background: var(--ff-surface-soft);
  outline: none;
}
.discovery-league,
.discovery-cta {
  color: var(--ff-text-faint);
  font-size: 11px;
}
.discovery-chip strong {
  display: block;
  min-width: 0;
  overflow: hidden;
  color: var(--ff-text-strong);
  font-size: 12px;
  line-height: 1.35;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.discovery-chip em {
  color: var(--ff-text-faint);
  font-style: normal;
  font-weight: 600;
}
.discovery-cta {
  color: var(--ff-primary);
  font-weight: 700;
}
@media (max-width: 980px) {
  .discovery-list { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}
@media (max-width: 680px) {
  .prediction-discovery { padding: 12px; margin-bottom: 12px; }
  .discovery-head { flex-wrap: wrap; }
  .discovery-list { grid-template-columns: 1fr; }
  .discovery-chip strong { white-space: normal; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; }
}
</style>
