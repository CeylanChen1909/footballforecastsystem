<template>
  <div class="squad-page ff-page-shell">
    <AppTopNav
      title="ChenFootball"
      subtitle="球队阵容"
      :brand-icon="Trophy"
      active-path="/competitions"
    />

    <el-main id="app-main" class="main-content" tabindex="-1">
      <div class="page-toolbar">
        <el-button text :icon="ArrowLeft" @click="backToCompetitions">返回赛事资料</el-button>
        <div class="toolbar-actions">
          <el-button plain size="small" @click="goTeamMatches">查看赛程</el-button>
          <el-button size="small" type="primary" plain :loading="loading" @click="loadSquad(true)">刷新阵容</el-button>
        </div>
      </div>

      <PageState v-if="loading && !squad.length" type="loading" title="正在加载球队阵容..." :size="40" />
      <PageState v-else-if="error" type="error" title="球队阵容加载失败" :description="error" action-text="重试" @action="loadSquad(true)" />
      <template v-else>
        <section class="team-page-head ff-appear" aria-labelledby="team-page-title">
          <div class="team-identity">
            <img v-if="teamLogo" :src="teamLogo" :alt="teamName" class="team-logo" @error="handleTeamLogoError" />
            <span v-else class="team-logo placeholder">{{ firstLetter(teamName) }}</span>
            <div>
              <span class="ff-kicker">{{ league }} · {{ seasonLabel }}</span>
              <h1 id="team-page-title">{{ teamName }}</h1>
            </div>
          </div>
          <div class="hero-actions">
            <el-tag :type="squadTone" effect="plain">{{ squadLabel }}</el-tag>
            <el-button size="small" type="warning" plain @click="toggleFavorite">{{ favorited ? '已关注' : '关注球队' }}</el-button>
          </div>
        </section>

        <section class="info-strip" aria-label="阵容数据说明">
          <div><span>数据源</span><strong>{{ sourceLabel }}</strong></div>
          <div><span>球员数</span><strong>{{ squad.length || '—' }}</strong></div>
          <div><span>最近同步</span><strong>{{ syncedLabel }}</strong></div>
          <div class="info-note">阵容来自公开球队页面，仅展示已抓取并可核验的名单，不用模型补全。<template v-if="cacheState === 'STALE'">当前展示最近快照，后台正在更新。</template><template v-else-if="cacheState === 'CACHED'">已使用最近快照；需要时可手动刷新。</template></div>
        </section>

        <PageSection title="注册阵容" :subtitle="squadMessage || '按位置查看当前公开注册名单'">
          <div v-if="squad.length" class="squad-toolbar" aria-label="阵容筛选">
            <el-input v-model="playerKeyword" clearable size="small" placeholder="搜索球员" aria-label="搜索球员" />
            <el-select v-model="positionFilter" size="small" aria-label="按位置筛选"><el-option label="全部位置" value="all" /><el-option label="门将" value="goalkeeper" /><el-option label="后卫" value="defender" /><el-option label="中场" value="midfielder" /><el-option label="前锋" value="forward" /><el-option label="其他 / 待确认" value="other" /></el-select>
            <span>显示 {{ filteredSquad.length }} / {{ squad.length }} 人</span>
          </div>
          <div v-if="filteredSquad.length" class="squad-groups">
            <div v-for="group in filteredSquadGroups" :key="group.key" class="squad-group">
              <div class="group-heading"><strong>{{ group.label }}</strong><span>{{ group.items.length }} 人</span></div>
              <div class="player-grid">
                <article v-for="player in group.items" :key="playerKey(player)" class="player-card">
                  <img v-if="player.photo && !player.photoBroken" :src="player.photo" :alt="player.name" @error="handlePlayerPhotoError(player)" />
                  <span v-else class="player-avatar">{{ firstLetter(player.name) }}</span>
                  <div class="player-copy">
                    <strong>{{ player.name || '未知球员' }}<b v-if="player.number" class="player-number">#{{ player.number }}</b></strong>
                    <span>{{ player.position || '位置待确认' }}<template v-if="player.number"> · {{ player.number }}号</template></span>
                    <small><template v-if="player.nationality">{{ player.nationality }}</template><template v-if="player.age"> · {{ player.age }}岁</template></small>
                  </div>
                </article>
              </div>
            </div>
          </div>
          <div v-else class="empty-state">
            <span class="empty-mark">◎</span>
            <div>
              <strong>{{ squad.length ? '没有匹配球员' : squadLabel }}</strong>
              <p>{{ squad.length ? '换一个关键词或位置筛选后再试。' : (squadMessage || '当前公开阵容页面没有返回可核验的名单，请稍后重试。') }}</p>
              <el-button v-if="!squad.length" size="small" type="primary" plain :loading="loading" @click="loadSquad(true)">重新获取</el-button>
            </div>
          </div>
        </PageSection>
      </template>
    </el-main>
  </div>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ArrowLeft, Trophy } from '@element-plus/icons-vue'
import AppTopNav from '../../components/layout/AppTopNav.vue'
import PageSection from '../../components/layout/PageSection.vue'
import PageState from '../../components/layout/PageState.vue'
import { crawlerApi, favoriteApi } from '../../api'
import { useUserStore } from '../../stores/user'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const teamName = ref('')
const teamId = ref('')
const league = ref('英超')
const season = ref('')
const loading = ref(false)
const error = ref('')
const teamLogo = ref('')
const squad = ref([])
const squadStatus = ref('UNKNOWN')
const squadMessage = ref('')
const squadSource = ref('')
const syncedAt = ref('')
const cacheState = ref('')
const playerKeyword = ref('')
const positionFilter = ref('all')
const favorited = ref(false)
const favorites = ref([])

const seasonLabel = computed(() => season.value || '当前赛季')
const sourceLabel = computed(() => ({ 'espn-squad': 'ESPN 公开球队页', 'espn-squad+transfermarkt-photos': 'ESPN 阵容 + Transfermarkt 头像', 'api-football': 'API-Football' }[squadSource.value] || squadSource.value || '待确认'))
const syncedLabel = computed(() => formatDateTime(syncedAt.value) || '待同步')
const squadLabel = computed(() => cacheState.value === 'STALE' ? '最近快照较旧' : cacheState.value === 'CACHED' ? '已使用最近快照' : ({ AVAILABLE: '已确认', EMPTY: '暂无名单', NOT_CONFIGURED: '未配置阵容源', REQUEST_FAILED: '请求失败', QUOTA_LIMITED: '额度受限' }[squadStatus.value] || '待确认'))
const squadTone = computed(() => ['STALE', 'CACHED'].includes(cacheState.value) ? 'warning' : squadStatus.value === 'AVAILABLE' ? 'success' : squadStatus.value === 'EMPTY' ? 'info' : 'warning')
const squadGroups = computed(() => {
  const definitions = [
    { key: 'goalkeeper', label: '门将', test: /goalkeeper|keeper|门将/i },
    { key: 'defender', label: '后卫', test: /defender|back|后卫/i },
    { key: 'midfielder', label: '中场', test: /midfielder|中场/i },
    { key: 'forward', label: '前锋', test: /forward|attacker|striker|前锋/i }
  ]
  const groups = definitions.map(item => ({ ...item, items: [] }))
  const other = { key: 'other', label: '其他 / 待确认', items: [] }
  squad.value.forEach(player => (groups.find(group => group.test.test(String(player.position || ''))) || other).items.push(player))
  return [...groups, other].filter(group => group.items.length)
})
const positionKey = value => {
  const text = String(value || '')
  if (/goalkeeper|keeper|门将/i.test(text)) return 'goalkeeper'
  if (/defender|back|后卫/i.test(text)) return 'defender'
  if (/midfielder|中场/i.test(text)) return 'midfielder'
  if (/forward|attacker|striker|前锋/i.test(text)) return 'forward'
  return 'other'
}
const filteredSquad = computed(() => {
  const keyword = playerKeyword.value.trim().toLowerCase()
  return squad.value.filter(player => {
    const keywordOk = !keyword || [player.name, player.position, player.nationality, player.number].some(value => String(value || '').toLowerCase().includes(keyword))
    return keywordOk && (positionFilter.value === 'all' || positionKey(player.position) === positionFilter.value)
  })
})
const filteredSquadGroups = computed(() => {
  const allowed = new Set(filteredSquad.value.map(player => playerKey(player)))
  return squadGroups.value.map(group => ({ ...group, items: group.items.filter(player => allowed.has(playerKey(player))) })).filter(group => group.items.length)
})

const firstLetter = value => String(value || '?').trim().slice(0, 1).toUpperCase()
const playerKey = player => `${player.id || player.name}-${player.number || ''}`
const formatDateTime = value => {
  if (!value) return ''
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? String(value).replace('T', ' ').slice(0, 16) : date.toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' })
}
const normalizeList = value => Array.isArray(value) ? value : Array.isArray(value?.response) ? value.response : []
const squadCacheKey = () => `chenfootball:squad:${teamId.value || teamName.value}:${league.value}:${season.value || 'current'}`
const applySquadPayload = data => {
  squad.value = normalizeList(data).map(player => ({ ...player, photoBroken: false }))
  squadStatus.value = data.status || (squad.value.length ? 'AVAILABLE' : 'EMPTY')
  squadMessage.value = data.message || ''
  squadSource.value = data.source || ''
  syncedAt.value = data.lastSyncedAt || ''
  cacheState.value = data.cacheState || ''
}

const loadTeamLogo = async () => {
  if (teamLogo.value || !league.value) return
  try {
    const result = await crawlerApi.getTeamsByLeague(league.value)
    const item = normalizeList(result).find(team => String(team.name || '').toLowerCase() === teamName.value.toLowerCase()
      || String(team.id || '') === teamId.value || String(team.teamId || '') === teamId.value)
    if (item?.logo) teamLogo.value = item.logo
  } catch (_) {
    // 队徽不是阵容主链路，失败时使用球队首字母占位。
  }
}

const syncRouteState = () => {
  teamName.value = String(route.query.name || route.params.teamId || '').trim()
  try { teamName.value = decodeURIComponent(teamName.value) } catch (_) {}
  teamId.value = String(route.query.teamId || route.params.teamId || '')
  teamLogo.value = String(route.query.logo || '')
  league.value = String(route.query.league || '英超')
  season.value = String(route.query.season || '')
}

const handleTeamLogoError = () => { teamLogo.value = '' }

const handlePlayerPhotoError = (player) => {
  // 头像源偶尔会因地区/CDN 缓存返回 404，立即切换到首字母头像，
  // 避免用户看到破图或反复重试。
  player.photoBroken = true
}

const loadSquad = async (force = false) => {
  if (!teamName.value) return
  loading.value = true
  error.value = ''
  cacheState.value = ''
  try {
    if (!force) {
      try {
        const cached = JSON.parse(sessionStorage.getItem(squadCacheKey()) || 'null')
        if (cached?.savedAt && Date.now() - Number(cached.savedAt) < 15 * 60 * 1000 && cached.payload) {
          applySquadPayload({ ...cached.payload, cacheState: cached.payload.cacheState || 'CACHED' })
          loading.value = false
          return
        }
      } catch (_) { /* ignore malformed browser cache */ }
    }
    const squadResult = await crawlerApi.getTeamSquad(teamName.value, league.value, teamId.value, Number(String(season.value).slice(0, 4)) || new Date().getFullYear(), { params: force ? { force: true } : {} })
    const data = squadResult?.data || squadResult || {}
    applySquadPayload(data)
    try { sessionStorage.setItem(squadCacheKey(), JSON.stringify({ savedAt: Date.now(), payload: data })) } catch (_) { /* storage may be disabled */ }
  } catch (err) {
    error.value = err?.message || '球队资料暂时不可用'
    squadStatus.value = 'REQUEST_FAILED'
    squadMessage.value = '阵容请求失败，请稍后重试。'
  } finally {
    loading.value = false
  }
}

const loadFavorites = async () => {
  if (!userStore.token) { favorites.value = []; favorited.value = false; return }
  favorites.value = await favoriteApi.list().then(value => Array.isArray(value) ? value : value?.data || []).catch(() => [])
  favorited.value = favorites.value.some(item => String(item.teamId) === String(teamId.value) || item.teamName === teamName.value)
}
const toggleFavorite = async () => {
  if (!userStore.token) { userStore.openAuthDialog(route.fullPath); return }
  try {
    const id = teamId.value || teamName.value
    if (favorited.value) { await favoriteApi.remove(id); ElMessage.success('已取消关注') }
    else { await favoriteApi.add(id, teamName.value); ElMessage.success('已关注球队') }
    await loadFavorites()
  } catch (err) { ElMessage.warning(err?.message || '关注操作失败') }
}
const backToCompetitions = () => router.push({ path: '/competitions', query: { league: league.value, season: season.value } })
const goTeamMatches = () => router.push({ path: '/matches', query: { team: teamName.value, league: league.value } })
onMounted(async () => { syncRouteState(); await Promise.all([loadSquad(), loadFavorites(), loadTeamLogo()]) })
watch(() => route.fullPath, async () => { syncRouteState(); await Promise.all([loadSquad(), loadFavorites(), loadTeamLogo()]) })
watch(() => userStore.token, loadFavorites)
</script>

<style scoped>
.main-content { max-width: 1180px; margin: 0 auto; padding: 24px; }
.page-toolbar { display:flex; justify-content:space-between; align-items:center; gap:12px; margin-bottom:16px; }
.toolbar-actions { display:flex; gap:8px; }
.team-hero { display:flex; justify-content:space-between; align-items:center; gap:20px; padding:20px 24px; border:1px solid var(--ff-border); border-radius:var(--ff-radius-lg); background:var(--ff-surface); }
.team-identity { display:flex; align-items:center; gap:16px; min-width:0; }.team-logo { width:80px; height:80px; object-fit:contain; flex:none; }.team-logo.placeholder { display:inline-flex; align-items:center; justify-content:center; border-radius:16px; background:var(--ff-primary-soft); color:var(--ff-primary); font-size:30px; font-weight:800; }.team-identity h1 { margin:6px 0; color:var(--ff-ink); font-size:clamp(28px,4vw,42px); letter-spacing:-.05em; }.team-identity p { margin:0; color:var(--ff-text-muted); }.hero-actions { display:flex; align-items:center; gap:10px; flex-wrap:wrap; }
.info-strip { display:grid; grid-template-columns:repeat(3,minmax(120px,auto)) minmax(260px,1fr); gap:10px; align-items:center; margin:16px 0; padding:14px 16px; border:1px solid var(--ff-border); border-radius:var(--ff-radius-md); background:var(--ff-surface); }.info-strip div:not(.info-note) { display:flex; flex-direction:column; gap:3px; }.info-strip span { color:var(--ff-text-faint); font-size:11px; }.info-strip strong { color:var(--ff-text-strong); font-family:var(--ff-mono); font-size:14px; }.info-note { color:var(--ff-text-muted); font-size:12px; line-height:1.6; }
.squad-groups { display:flex; flex-direction:column; gap:22px; }.group-heading { display:flex; align-items:center; gap:10px; margin-bottom:10px; color:var(--ff-text-strong); }.group-heading span { color:var(--ff-text-faint); font-size:12px; }.player-grid { display:grid; grid-template-columns:repeat(4,minmax(0,1fr)); gap:10px; }.player-card { display:flex; align-items:center; gap:10px; min-width:0; padding:12px; border:1px solid var(--ff-border); border-radius:var(--ff-radius-md); background:var(--ff-surface-quiet); }.player-card img,.player-avatar { width:42px; height:42px; flex:none; object-fit:cover; border-radius:50%; }.player-avatar { display:inline-flex; align-items:center; justify-content:center; background:var(--ff-primary-soft); color:var(--ff-primary); font-weight:800; }.player-copy { display:flex; flex-direction:column; gap:3px; min-width:0; }.player-copy strong { overflow:hidden; color:var(--ff-text-strong); text-overflow:ellipsis; white-space:nowrap; }.player-copy span,.player-copy small { color:var(--ff-text-muted); font-size:11px; }.empty-state { display:flex; align-items:center; gap:14px; padding:22px 10px; color:var(--ff-text-muted); }.empty-mark { color:var(--ff-primary); font-size:30px; }.empty-state strong { color:var(--ff-text-strong); }.empty-state p { margin:6px 0 12px; }
.squad-toolbar { display:flex; align-items:center; gap:8px; margin:-2px 0 16px; }.squad-toolbar .el-input { width:240px; }.squad-toolbar .el-select { width:150px; }.squad-toolbar > span { margin-left:auto; color:var(--ff-text-muted); font:11px var(--ff-mono); }
@media (max-width: 900px) { .info-strip { grid-template-columns:repeat(3,1fr); }.info-note { grid-column:1/-1; }.player-grid { grid-template-columns:repeat(2,minmax(0,1fr)); } }
@media (max-width: 620px) { .main-content { padding:16px; }.page-toolbar,.team-hero { align-items:flex-start; flex-direction:column; }.team-hero { padding:20px; }.team-logo { width:62px; height:62px; }.info-strip { grid-template-columns:repeat(2,1fr); }.player-grid { grid-template-columns:1fr; }.squad-toolbar { align-items:stretch; flex-direction:column; }.squad-toolbar .el-input,.squad-toolbar .el-select { width:100%; }.squad-toolbar > span { margin-left:0; } }
.player-number { margin-left:6px; color:var(--ff-primary); font:11px var(--ff-mono); }
.team-page-head { display:flex; justify-content:space-between; align-items:center; gap:16px; padding:4px 2px 14px; border-bottom:1px solid var(--ff-border); }
.team-page-head .team-identity { gap:12px; }
.team-page-head .team-logo { width:52px; height:52px; }
.team-page-head .team-logo.placeholder { border-radius:12px; font-size:22px; }
.team-page-head .team-identity h1 { margin:4px 0 0; font-size:clamp(24px,3vw,32px); }
.team-page-head .hero-actions { flex:none; }
@media (max-width:620px) {
  .team-page-head { align-items:flex-start; flex-direction:column; padding:2px 0 12px; }
  .team-page-head .hero-actions { width:100%; justify-content:space-between; }
}
</style>
