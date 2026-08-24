<template>
  <article
    class="persona-card game-player-card"
    :class="[`tier-${tier}`, { selected, compared, readonly: readOnly }]"
    :aria-label="`${card.player_name || '虚拟角色'}卡牌${readOnly ? '（只读）' : ''}`"
    role="group"
    tabindex="0"
    @click="$emit('select', card)"
    @keydown.enter="$emit('select', card)"
    @keydown.space.prevent="$emit('select', card)"
  >
    <span class="card-shine"></span>
    <span class="card-tier-label">{{ tierLabel }}</span>
    <span class="card-overall">{{ card.overall }}</span>
    <div class="game-card-photo">
      <img v-if="imageUrl" :src="imageUrl" :alt="card.player_name" loading="lazy" @error="imageUrl = ''" />
      <span v-else>{{ initials(card.player_name) }}</span>
    </div>
    <strong class="game-card-name">{{ card.player_name }}</strong>
    <span class="game-card-position">{{ shortPosition(card.position) }} · {{ card.team_name || 'PERSONA' }}</span>
    <div v-if="tags.length" class="persona-tags"><span v-for="tag in tags.slice(0, 3)" :key="tag" :title="tag">{{ tagLabel(tag) }}</span><b v-if="tags.length > 3">+{{ tags.length - 3 }}</b></div>
    <div class="game-card-stats">
      <span v-for="item in statList" :key="item.key" :title="item.label"><b>{{ card[item.key] }}</b>{{ item.short }}</span>
    </div>
    <span v-if="readOnly" class="card-readonly-mark">只读公开</span>
    <span v-else-if="owned" class="card-owned-mark">✓ 已入阵</span>
    <button type="button" class="card-compare-button" :aria-label="`比较${card.player_name || '角色'}`" @click.stop="$emit('compare', card)">比较</button>
  </article>
</template>

<script setup>
import { computed, ref, watch } from 'vue'

const props = defineProps({
  card: { type: Object, required: true },
  selected: { type: Boolean, default: false },
  compared: { type: Boolean, default: false },
  owned: { type: Boolean, default: false },
  readOnly: { type: Boolean, default: false }
})

defineEmits(['select', 'compare'])

const imageUrl = ref(props.card.photo_url || '')
watch(() => props.card.photo_url, value => { imageUrl.value = value || '' })
const statList = [
  { key: 'pace', label: '速度', short: 'PAC' },
  { key: 'shooting', label: '射门', short: 'SHO' },
  { key: 'passing', label: '传球', short: 'PAS' },
  { key: 'dribbling', label: '盘带', short: 'DRI' },
  { key: 'defending', label: '防守', short: 'DEF' },
  { key: 'physical', label: '身体', short: 'PHY' }
]
const tier = computed(() => { const overall = Number(props.card.overall || 0); if (props.card.catalog_id || props.card.rarity) return overall >= 90 ? 'ur' : overall >= 85 ? 'ssr' : overall >= 78 ? 'sr' : overall >= 70 ? 'r' : 'n'; return props.card.card_type === 'CUSTOM_PERSONA' ? 'persona' : overall >= 85 ? 'elite' : overall >= 78 ? 'gold' : overall >= 70 ? 'silver' : 'bronze' })
const tierLabel = computed(() => ({ ur: 'UR', ssr: 'SSR', sr: 'SR', r: 'R', n: 'N', persona: 'PERSONA', elite: 'ELITE', gold: 'GOLD', silver: 'SILVER', bronze: 'BASE' }[tier.value]))
const initials = value => String(value || '?').trim().slice(0, 1).toUpperCase()
const shortPosition = value => String(value || '全能').replace('边前卫', '边翼').slice(0, 4)
const tags = computed(() => { const personal = Array.isArray(props.card.tags) ? props.card.tags : []; let canonical = []; try { const parsed = JSON.parse(props.card.tags_json || '[]'); canonical = Array.isArray(parsed) ? parsed : [] } catch { canonical = [] } return [...new Set([...canonical, ...personal])].filter(tag => tag !== 'favorite') })
const tagLabel = value => String(value || '').replace(/^[^:：]+[:：]/, '')
</script>

<style scoped>
.persona-card{position:relative;display:flex;flex-direction:column;align-items:center;gap:5px;width:100%;min-width:0;overflow:hidden;padding:13px 10px 11px;border:1px solid rgba(255,255,255,.15);border-radius:9px;background:linear-gradient(145deg,#25526a,#101e2b 65%);color:#eef8f1;text-align:center;cursor:pointer;isolation:isolate;transition:transform .16s ease,border-color .16s ease,box-shadow .16s ease}.persona-card:hover,.persona-card.selected{transform:translateY(-5px);border-color:#e3b954;box-shadow:0 9px 22px rgba(0,0,0,.32),0 0 0 1px rgba(227,185,84,.22)}.persona-card.compared{outline:2px solid #8be0ad;outline-offset:2px}.persona-card .card-shine{position:absolute;inset:-30% 40% 25% -35%;z-index:-1;background:linear-gradient(120deg,transparent,rgba(255,255,255,.16),transparent);transform:rotate(15deg);pointer-events:none}.persona-card .card-tier-label{align-self:flex-start;color:#f4d276;font:800 8px var(--ff-mono);letter-spacing:.12em}.persona-card .card-overall{position:absolute;top:25px;left:10px;color:#fff2b0;font:800 21px var(--ff-mono)}.persona-card .game-card-photo{display:grid;place-items:center;width:76px;height:76px;margin:4px 0 2px;border:2px solid rgba(255,255,255,.36);border-radius:50%;background:rgba(255,255,255,.14);overflow:hidden;color:#f4d276;font-size:28px;font-weight:800}.persona-card .game-card-photo img{width:100%;height:100%;object-fit:cover}.persona-card .game-card-name,.persona-card .game-card-position{width:100%;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.persona-card .game-card-name{color:#fff;font-size:12px}.persona-card .game-card-position{color:#b9d5c2;font:10px var(--ff-mono)}.persona-card .game-card-stats{display:grid;grid-template-columns:repeat(3,1fr);gap:3px;width:100%;margin-top:6px;color:#a1c4ae;font:8px var(--ff-mono)}.persona-card .game-card-stats b{display:block;color:#f4d276;font-size:10px}.card-readonly-mark{position:absolute;right:6px;bottom:6px;color:#8be0ad;font-size:9px}.card-owned-mark{position:absolute;right:6px;bottom:6px;color:#8be0ad;font-size:9px}.card-compare-button{position:absolute;top:6px;right:6px;padding:2px 5px;border:1px solid rgba(227,185,84,.35);border-radius:999px;background:rgba(0,0,0,.22);color:#f4d276;font-size:9px;cursor:pointer}.card-compare-button:hover,.card-compare-button:focus-visible{background:rgba(227,185,84,.2)}
.persona-card.tier-ur{background:linear-gradient(145deg,#8c6d21,#211a0d 65%)}.persona-card.tier-ssr{background:linear-gradient(145deg,#6c3b85,#1f132c 65%)}.persona-card.tier-sr{background:linear-gradient(145deg,#285c86,#101f31 65%)}.persona-card.tier-r{background:linear-gradient(145deg,#285f46,#10271d 65%)}.persona-card.tier-n{background:linear-gradient(145deg,#53616b,#172329 65%)}
.persona-tags{display:flex;align-items:center;justify-content:center;gap:3px;width:100%;overflow:hidden;color:#d8e8b0;font-size:8px;line-height:1.2}.persona-tags span{max-width:62px;overflow:hidden;padding:2px 4px;border:1px solid rgba(227,185,84,.34);border-radius:999px;background:rgba(227,185,84,.1);text-overflow:ellipsis;white-space:nowrap}.persona-tags b{color:#e3b954;font:700 8px var(--ff-mono)}
</style>
