<template>
  <section
    v-if="visibleItems.length"
    id="prediction-discovery"
    class="prediction-discovery"
    aria-labelledby="prediction-discovery-title"
  >
    <div class="discovery-head">
      <div class="discovery-copy">
        <span class="discovery-kicker">{{ t('matches.discoveryKicker') }}</span>
        <h2 id="prediction-discovery-title">{{ t('matches.discoveryTitle') }}</h2>
        <p>{{ t('matches.discoveryDescription') }}</p>
      </div>
      <span class="discovery-more">{{ t('matches.discoveryMore') }}</span>
    </div>
    <div class="discovery-list" role="list">
      <button
        v-for="(match, index) in visibleItems"
        :key="itemKey(match, index)"
        type="button"
        class="discovery-chip"
        role="listitem"
        :aria-label="t('matches.discoveryAria', { home: homeName(match), away: awayName(match) })"
        @click="$emit('predict', match)"
      >
        <span class="discovery-league">{{ leagueName(match) }}</span>
        <strong>{{ homeName(match) }} <em>vs</em> {{ awayName(match) }}</strong>
        <span class="discovery-cta">{{ isFinished(match) ? t('matches.review') : t('matches.predict') }}</span>
      </button>
    </div>
  </section>
</template>

<script setup>
import { computed } from 'vue'
import { getLeagueDisplay, getTeamDisplay, isFinished as isFinishedMatch } from '../../utils/match'
import { displayLeagueName, getTeamDisplayName } from '../../utils/teamNames'
import { useI18n } from '../../i18n'

const { t } = useI18n()

const props = defineProps({
  items: { type: Array, default: () => [] },
  teamNameMode: { type: String, default: 'zh' },
  limit: { type: Number, default: 4 }
})
defineEmits(['predict'])

const visibleItems = computed(() => (Array.isArray(props.items) ? props.items : []).slice(0, props.limit))
const team = (match, side) => match?.teams?.[side] || {}
const homeName = match => getTeamDisplayName(team(match, 'home').name || match?.homeTeamName || t('matches.homeTeam'), props.teamNameMode, getTeamDisplay(match, 'home'))
const awayName = match => getTeamDisplayName(team(match, 'away').name || match?.awayTeamName || t('matches.awayTeam'), props.teamNameMode, getTeamDisplay(match, 'away'))
const leagueName = match => displayLeagueName(match?.league?.name || match?.leagueName || t('matches.leagueFallback'), getLeagueDisplay(match))
const itemKey = (match, index) => match?.matchId || match?.id || match?.fixtureId || `discover-${index}`
const isFinished = match => isFinishedMatch(match)
</script>

<style scoped>
.prediction-discovery {
  margin: 0 0 12px;
  padding: 0;
  border: 0;
  background: transparent;
  min-width: 0;
  max-width: 100%;
}
.discovery-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 6px;
  min-width: 0;
}
.discovery-kicker {
  color: var(--ff-text-muted);
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 0;
}
.discovery-copy { min-width: 0; }
.discovery-copy h2 {
  margin: 0;
  color: var(--ff-text-strong);
  font-size: 14px;
  font-weight: 700;
  letter-spacing: 0;
}
.discovery-copy p {
  margin: 2px 0 0;
  color: var(--ff-text-muted);
  font-size: 12px;
  line-height: 1.4;
}
.discovery-more {
  flex: none;
  color: var(--ff-text-faint);
  font-size: 12px;
  font-weight: 600;
  white-space: nowrap;
}
.discovery-list {
  display: flex;
  gap: 6px;
  overflow-x: auto;
  overscroll-behavior-x: contain;
  max-width: 100%;
  min-width: 0;
  padding-bottom: 2px;
  -webkit-overflow-scrolling: touch;
}
.discovery-chip {
  display: flex;
  align-items: center;
  gap: 8px;
  flex: 0 0 auto;
  max-width: min(280px, 78vw);
  min-height: 40px;
  min-width: 0;
  padding: 6px 10px;
  border: 1px solid var(--ff-border);
  border-radius: 4px;
  background: #fff;
  color: inherit;
  text-align: left;
  cursor: pointer;
}
.discovery-chip:hover,
.discovery-chip:focus-visible {
  border-color: var(--ff-primary);
  outline: none;
}
.discovery-league { display: none; }
.discovery-chip strong {
  min-width: 0;
  overflow: hidden;
  color: var(--ff-text-strong);
  font-size: 13px;
  font-weight: 650;
  line-height: 1.3;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.discovery-chip em { color: var(--ff-text-faint); font-style: normal; font-weight: 600; }
.discovery-cta { flex: none; color: var(--ff-primary); font-size: 12px; font-weight: 700; }
@media (max-width: 680px) {
  .discovery-head { flex-wrap: wrap; }
  .discovery-more { display: none; }
}
</style>
