<template>
  <article
    class="catalog-item game-panel"
    :class="`catalog-rarity-${String(item.rarity || 'N').toLowerCase()}`"
    role="button"
    tabindex="0"
    :aria-label="t('agent.card.viewDetailAria', { name: item.name })"
    @click="$emit('detail', item)"
    @keydown.enter.prevent="$emit('detail', item)"
    @keydown.space.prevent="$emit('detail', item)"
  >
    <div class="catalog-item-head">
      <div class="catalog-photo">
        <img v-if="item.photo_url && !item.photoBroken" loading="lazy" :src="item.photo_url" :alt="item.name" @error="item.photoBroken = true" />
        <span v-else>{{ initial(item.name) }}</span>
      </div>
      <div>
        <span class="game-eyebrow">{{ t('agent.card.position', { position: item.position || t('agent.card.allRound') }) }} · <b class="rarity-label">{{ item.rarity || 'N' }}</b></span>
        <h3>{{ item.name }}</h3>
        <small>{{ shortPosition(item.position) }} · {{ t('agent.card.overall', { overall: item.overall }) }}</small>
      </div>
    </div>
    <p>{{ item.description || t('agent.card.defaultDescription') }}</p>
    <div v-if="tags.length" class="catalog-tags"><span v-for="tag in tags.slice(0, 4)" :key="tag" :title="tag">{{ tag }}</span></div>
    <div class="catalog-stats"><span v-for="key in statKeys" :key="key">{{ t(statHelp[key]) }} <b>{{ item[key] }}</b></span></div>
    <div class="catalog-item-foot"><strong>{{ t('agent.card.points', { points: item.price_points || 0 }) }}</strong><el-tag v-if="item.owned" type="success">{{ t('agent.card.owned') }}</el-tag><el-button v-else type="primary" size="small" :loading="loading" :disabled="!authenticated" @click.stop="$emit('redeem', item)">{{ authenticated ? t('agent.card.redeem') : t('agent.card.loginToRedeem') }}</el-button></div>
    <small v-if="item.source_attribution" class="catalog-source">{{ item.source_attribution }} · {{ item.source_license || 'CC BY-SA 4.0' }}</small>
  </article>
</template>

<script setup>
import { computed } from 'vue'
import { useI18n } from '../../i18n'
const { t } = useI18n()
const props = defineProps({ item: { type: Object, required: true }, authenticated: Boolean, loading: Boolean })
defineEmits(['detail', 'redeem'])
const statKeys = ['pace', 'shooting', 'passing', 'dribbling', 'defending', 'physical']
const statHelp = { pace: 'agent.card.stat.pace', shooting: 'agent.card.stat.shooting', passing: 'agent.card.stat.passing', dribbling: 'agent.card.stat.dribbling', defending: 'agent.card.stat.defending', physical: 'agent.card.stat.physical' }
const tags = computed(() => { try { const parsed = JSON.parse(props.item?.tags_json || '[]'); return Array.isArray(parsed) ? parsed : [] } catch { return [] } })
const initial = name => String(name || '?').trim().slice(0, 1).toUpperCase()
const shortPosition = value => { const text = String(value || ''); return text.includes('Goal') || text.includes('门') ? 'GK' : text.includes('Def') || text.includes('后') ? 'DEF' : text.includes('Mid') || text.includes('中') ? 'MID' : text.includes('For') || text.includes('前') || text.includes('Strik') ? 'FWD' : 'ALL' }
</script>

<style scoped>
.catalog-item{display:flex;min-width:0;flex-direction:column;padding:15px;cursor:pointer}.catalog-item:focus-visible{outline:2px solid #f4d276;outline-offset:3px}.catalog-item-head{display:flex;align-items:center;gap:10px}.catalog-photo{display:grid;place-items:center;width:58px;height:58px;flex:none;overflow:hidden;border:1px solid rgba(227,185,84,.55);border-radius:50%;background:linear-gradient(145deg,#25526a,#10271d);color:#f4d276;font-size:24px;font-weight:800}.catalog-photo img{width:100%;height:100%;object-fit:cover}.catalog-item-head h3{max-width:170px;margin:4px 0 2px;overflow:hidden;color:#eef8f1;font-size:15px;text-overflow:ellipsis;white-space:nowrap}.catalog-item-head small{color:#9eb8a7;font:10px var(--ff-mono)}.catalog-item>p{display:-webkit-box;min-height:40px;margin:13px 0;color:#9eb8a7;font-size:11px;line-height:1.65;overflow:hidden;-webkit-box-orient:vertical;-webkit-line-clamp:2}.catalog-tags{display:flex;gap:4px;flex-wrap:wrap;margin:0 0 8px}.catalog-tags span{max-width:110px;padding:2px 6px;overflow:hidden;border:1px solid rgba(227,185,84,.25);border-radius:999px;color:#d8e8b0;font-size:9px;text-overflow:ellipsis;white-space:nowrap}.catalog-stats{display:grid;grid-template-columns:repeat(3,1fr);gap:5px}.catalog-stats span{padding:5px;border-radius:4px;color:#789587;background:rgba(255,255,255,.04);font:9px var(--ff-mono);text-align:center}.catalog-stats b{display:block;margin-top:2px;color:#f4d276;font-size:11px}.catalog-item-foot{display:flex;align-items:center;justify-content:space-between;gap:8px;margin-top:14px}.catalog-item-foot strong{color:#f4d276;font:700 14px var(--ff-mono)}.catalog-source{display:block;margin-top:10px;color:#6f8b7a;font-size:9px;line-height:1.5}.catalog-rarity-ur{border:1px solid rgba(244,210,118,.78);box-shadow:0 0 22px rgba(244,210,118,.12)}.catalog-rarity-ssr{border:1px solid rgba(226,145,255,.58)}.catalog-rarity-sr{border:1px solid rgba(130,190,255,.55)}.catalog-rarity-r{border:1px solid rgba(120,201,155,.4)}.catalog-rarity-n{border:1px solid rgba(158,204,178,.18)}
</style>
