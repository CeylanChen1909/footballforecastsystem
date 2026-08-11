<template>
  <div class="layout">
    <el-container>
      <el-header class="header">
        <div class="header-left">
          <el-icon :size="28" color="#409EFF"><Football /></el-icon>
          <span class="title">ChenFootball</span>
        </div>
        <div class="header-nav">
          <el-menu mode="horizontal" :default-active="'2'" @select="handleMenuSelect">
            <el-menu-item index="/matches"><el-icon><Calendar /></el-icon> 比赛</el-menu-item>
            <el-menu-item index="/news"><el-icon><News /></el-icon> 资讯</el-menu-item>
            <el-menu-item index="/profile"><el-icon><User /></el-icon> 我的</el-menu-item>
          </el-menu>
        </div>
        <div class="header-right">
          <el-dropdown @command="handleCommand">
            <el-avatar :size="36" style="cursor:pointer;background:#409EFF">
              {{ userStore.username?.[0]?.toUpperCase() }}
            </el-avatar>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="profile"><el-icon><User /></el-icon> 个人中心</el-dropdown-item>
                <el-dropdown-item command="history"><el-icon><Histogram /></el-icon> 预测历史</el-dropdown-item>
                <el-dropdown-item divided command="logout"><el-icon><SwitchButton /></el-icon> 退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </el-header>

      <el-main class="main-content">
        <div class="team-detail" v-loading="loading">
          <!-- 球队基本信息卡片 -->
          <el-card class="team-info-card" shadow="hover">
            <template #header>
              <div class="card-header">
                <span>球队信息</span>
                <el-button v-if="!isFavorited" type="primary" size="small" @click="addFavorite">
                  <el-icon><Star /></el-icon> 收藏
                </el-button>
                <el-button v-else type="info" plain size="small" @click="removeFavorite">
                  <el-icon><StarFilled /></el-icon> 已收藏
                </el-button>
              </div>
            </template>
            <div class="team-profile" v-if="teamData">
              <div class="team-logo-section">
                <img v-if="displayTeam.logo" :src="displayTeam.logo" class="team-logo" />
                <div v-else class="logo-placeholder">{{ displayTeam.name?.[0] }}</div>
              </div>
              <div class="team-details">
                <h2 class="team-name">{{ displayTeam.name }}</h2>
                <div class="team-meta">
                  <el-tag v-if="displayTeam.country" size="small">
                    <el-icon><Location /></el-icon> {{ displayTeam.country }}
                  </el-tag>
                  <el-tag v-if="displayTeam.leagueName" size="small" type="info">
                    <el-icon><Office /></el-icon> {{ displayTeam.leagueName }}
                  </el-tag>
                  <el-tag v-if="teamRecentForm" size="small" type="warning">
                    近5场 {{ teamRecentForm }}
                  </el-tag>
                </div>
                <div class="stats-overview" v-if="formData">
                  <div class="stat-chip">
                    <span class="chip-val">{{ formData.matches || '-' }}</span>
                    <span class="chip-label">比赛</span>
                  </div>
                  <div class="stat-chip win">
                    <span class="chip-val">{{ formData.wins || '-' }}</span>
                    <span class="chip-label">胜场</span>
                  </div>
                  <div class="stat-chip draw">
                    <span class="chip-val">{{ formData.draws || '-' }}</span>
                    <span class="chip-label">平局</span>
                  </div>
                  <div class="stat-chip lose">
                    <span class="chip-val">{{ formData.losses || '-' }}</span>
                    <span class="chip-label">败场</span>
                  </div>
                  <div class="stat-chip">
                    <span class="chip-val">{{ formData.goalsFor || '-' }}</span>
                    <span class="chip-label">进球</span>
                  </div>
                  <div class="stat-chip">
                    <span class="chip-val">{{ formData.goalsAgainst || '-' }}</span>
                    <span class="chip-label">失球</span>
                  </div>
                </div>
              </div>
            </div>
            <div v-else class="loading-state">
              <el-skeleton :rows="4" animated />
            </div>
          </el-card>

          <!-- 近期战绩 -->
          <el-card class="form-card" shadow="hover">
            <template #header>
              <div class="card-header">
                <span>
                  <el-icon><TrendCharts /></el-icon> 近期战绩
                </span>
                <div class="form-legend">
                  <span class="fl-item"><span class="fl-dot win"></span>胜</span>
                  <span class="fl-item"><span class="fl-dot draw"></span>平</span>
                  <span class="fl-item"><span class="fl-dot lose"></span>负</span>
                </div>
              </div>
            </template>
            <div v-if="loading || recentLoading" class="loading-state">
              <el-skeleton :rows="3" animated />
            </div>
            <div v-else-if="topRecentMatches.length === 0" class="empty-state">
              <el-icon :size="48" color="#ddd"><Calendar /></el-icon>
              <p>暂无近期比赛数据</p>
            </div>
            <div v-else class="form-badges">
              <div v-for="(match, idx) in topRecentMatches" :key="idx" class="form-badge" :class="formClass(match.fixture?.status?.short === 'FT' ? (match.goals?.home > match.goals?.away ? 'W' : match.goals?.home === match.goals?.away ? 'D' : 'L') : 'D')">
                <span class="badge-result">{{ match.fixture?.status?.short || 'NS' }}</span>
                <span class="badge-score">{{ match.goals?.home ?? '-' }}-{{ match.goals?.away ?? '-' }}</span>
              </div>
            </div>
          </el-card>

          <!-- AI 近期交锋分析 -->
          <el-card class="ai-card" shadow="hover" v-if="aiH2h">
            <template #header>
              <div class="card-header">
                <span>
                  <el-icon><Connection /></el-icon> AI 近期交锋分析
                </span>
                <el-tag size="small" :type="aiH2h?.source === 'fallback' ? 'warning' : 'success'">
                  {{ aiH2h?.source || 'deepseek' }}
                </el-tag>
              </div>
            </template>
            <div class="ai-analysis">
              <div class="ai-block">
                <div class="ai-title">总结</div>
                <div class="ai-text">{{ getAiText(aiH2h) }}</div>
              </div>
              <div v-if="getAiList(aiH2h, 'trend').length" class="ai-block">
                <div class="ai-title">趋势</div>
                <ul class="ai-list">
                  <li v-for="(item, idx) in getAiList(aiH2h, 'trend')" :key="idx">{{ item }}</li>
                </ul>
              </div>
              <div v-if="getAiText(aiH2h, 'conclusion')" class="ai-block">
                <div class="ai-title">结论</div>
                <div class="ai-text">{{ getAiText(aiH2h, 'conclusion') }}</div>
              </div>
            </div>
          </el-card>

          <!-- AI球队分析 -->
          <el-card class="ai-card" shadow="hover" v-if="aiAnalysis">
            <template #header>
              <div class="card-header">
                <span>
                  <el-icon><MagicStick /></el-icon> AI 球队分析
                </span>
                <el-tag size="small" :type="aiAnalysis?.source === 'fallback' ? 'warning' : 'success'">
                  {{ aiAnalysis?.source || 'deepseek' }}
                </el-tag>
              </div>
            </template>
            <div class="ai-analysis">
              <div class="ai-block">
                <div class="ai-title">总结</div>
                <div class="ai-text">{{ aiAnalysis?.content || aiAnalysis?.summary || '暂无AI分析' }}</div>
              </div>
            </div>
          </el-card>

<!--          &lt;!&ndash; AI 队内射手分析 &ndash;&gt;-->
<!--          <el-card class="ai-card" shadow="hover" v-if="getAiList(scorers[0], 'note').length || scorers.length">-->
<!--            <template #header>-->
<!--              <div class="card-header">-->
<!--                <span>-->
<!--                  <el-icon><Trophy /></el-icon> AI 队内射手分析-->
<!--                </span>-->
<!--                <el-tag size="small" :type="scorers[0]?.source === 'fallback' ? 'warning' : 'success'">-->
<!--                  {{ scorers[0]?.source || 'deepseek' }}-->
<!--                </el-tag>-->
<!--              </div>-->
<!--            </template>-->
<!--            <div class="ai-analysis">-->
<!--              <div v-for="(item, idx) in scorers" :key="idx" class="ai-block">-->
<!--                <div class="ai-title">{{ item.player?.name || `球员 ${idx + 1}` }}</div>-->
<!--                <div class="ai-text" v-if="item.note">{{ item.note }}</div>-->
<!--                <div class="ai-text" v-else>{{ getAiText(item) }}</div>-->
<!--              </div>-->
<!--            </div>-->
<!--          </el-card>-->

<!--          &lt;!&ndash; 射手榜 &ndash;&gt;-->
<!--          <el-card class="scorers-card" shadow="hover">-->
<!--            <template #header>-->
<!--              <div class="card-header">-->
<!--                <span>-->
<!--                  <el-icon><Trophy /></el-icon> 队内射手榜-->
<!--                </span>-->
<!--              </div>-->
<!--            </template>-->
<!--            <div v-if="scorers.length === 0" class="empty-state">-->
<!--              <el-icon :size="48" color="#ddd"><Trophy /></el-icon>-->
<!--              <p>暂无射手数据</p>-->
<!--            </div>-->
<!--            <el-table v-else :data="scorers" stripe size="small">-->
<!--              <el-table-column label="排名" width="50" align="center">-->
<!--                <template #default="{ $index }">-->
<!--                  <span class="rank-badge" :class="rankClass($index)">{{ $index + 1 }}</span>-->
<!--                </template>-->
<!--              </el-table-column>-->
<!--              <el-table-column label="球员" min-width="140">-->
<!--                <template #default="{ row }">-->
<!--                  <div class="scorer-cell">-->
<!--                    <img v-if="row.player?.photo" :src="row.player.photo" class="scorer-photo" />-->
<!--                    <div v-else class="scorer-photo-placeholder">{{ (row.player?.name || '?')[0] }}</div>-->
<!--                    <span class="scorer-name">{{ row.player?.name }}</span>-->
<!--                  </div>-->
<!--                </template>-->
<!--              </el-table-column>-->
<!--              <el-table-column prop="goals.total" label="进球" width="60" align="center" />-->
<!--              <el-table-column prop="goals.assists" label="助攻" width="60" align="center" />-->
<!--              <el-table-column prop="games.appearences" label="出场" width="60" align="center" />-->
<!--            </el-table>-->
<!--          </el-card>-->
        </div>
      </el-main>
    </el-container>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useUserStore } from '../stores/user'
import { crawlerApi, favoriteApi } from '../api'
import { ElMessage } from 'element-plus'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const loading = ref(false)
const recentLoading = ref(false)
const h2hLoading = ref(false)
const teamData = ref(null)
const recentMatches = ref([])
const scorers = ref([])
const h2hData = ref(null)
const aiAnalysis = ref(null)
const aiH2h = ref(null)
const isFavorited = ref(false)
const favorites = ref([])
const selectedLeagueName = ref('')

const teamName = computed(() => decodeURIComponent(route.params.teamName || route.params.teamId || ''))
const displayTeam = computed(() => {
  const team = teamData.value?.team || {}
  return {
    name: team.name || teamName.value,
    logo: team.logo || '',
    country: team.country || team.nationality || '',
    leagueName: team.leagueName || selectedLeagueName.value || '',
    id: team.id || route.params.teamId
  }
})
const formData = computed(() => teamData.value?.form || {})
const topRecentMatches = computed(() => (recentMatches.value || []).slice(0, 10))
const teamRecentForm = computed(() => formData.value?.recentForm || '')

const formClass = (r) => {
  if (r === 'W') return 'form-win'
  if (r === 'D') return 'form-draw'
  if (r === 'L') return 'form-lose'
  return ''
}
const rankClass = (idx) => (idx === 0 ? 'gold' : idx === 1 ? 'silver' : idx === 2 ? 'bronze' : '')

const parseAi = (value) => {
  if (!value) return null
  if (typeof value === 'object') return value
  const text = String(value)
  const match = text.match(/```json\s*([\s\S]*?)```/i)
  const body = (match ? match[1] : text).trim()
  const json = body.match(/\{[\s\S]*\}/)?.[0] || body.match(/\[[\s\S]*\]/)?.[0] || body
  try { return JSON.parse(json) } catch { return { summary: text } }
}
const getAiText = (obj, key = 'summary') => {
  const data = parseAi(obj)
  if (!data) return ''
  const v = data?.[key] ?? data?.[key === 'summary' ? 'content' : key]
  if (Array.isArray(v)) return v.join('；')
  if (v == null) return data?.summary || data?.content || ''
  return typeof v === 'object' ? JSON.stringify(v) : String(v)
}
const getAiList = (obj, key) => {
  const data = parseAi(obj)
  const v = data?.[key]
  if (Array.isArray(v)) return v
  if (typeof v === 'string') return v.split(/[,;，；\n]/).map(s => s.trim()).filter(Boolean)
  return []
}

const loadTeamDetail = async () => {
  loading.value = true
  try {
    const res = await crawlerApi.getTeamDetail(teamName.value, selectedLeagueName.value || undefined)
    const data = res?.data || res?.response || res || null
    teamData.value = data
    recentMatches.value = data?.recentMatches || []
    scorers.value = data?.scorers || []
    aiAnalysis.value = data?.aiAnalysis || null
    aiH2h.value = data?.aiH2h || null
    h2hData.value = data?.aiH2h || h2hData.value
  } catch {
    teamData.value = null
    recentMatches.value = []
    scorers.value = []
    aiAnalysis.value = null
    aiH2h.value = null
  } finally {
    loading.value = false
  }
}

const loadHeadToHead = async () => {
  if (!teamData.value?.recentMatches?.length) return
  const first = teamData.value.recentMatches[0]
  const homeTeam = first?.teams?.home?.name
  const awayTeam = first?.teams?.away?.name
  if (!homeTeam || !awayTeam) return
  h2hLoading.value = true
  try {
    const res = await crawlerApi.getHeadToHead(homeTeam, awayTeam, 5)
    const data = res?.data || res?.response || res || null
    h2hData.value = data
  } catch {
    h2hData.value = null
  } finally {
    h2hLoading.value = false
  }
}

const loadFavorites = async () => {
  try {
    const res = await favoriteApi.list()
    favorites.value = Array.isArray(res) ? res : (res?.data || [])
    isFavorited.value = favorites.value.some(f => String(f.teamId) === String(route.params.teamId))
  } catch {
    favorites.value = []
  }
}

const addFavorite = async () => {
  try {
    await favoriteApi.add(route.params.teamId, displayTeam.value?.name)
    ElMessage.success('收藏成功')
    isFavorited.value = true
    await loadFavorites()
  } catch {
    ElMessage.error('收藏失败，请先登录')
  }
}

const removeFavorite = async () => {
  try {
    await favoriteApi.remove(route.params.teamId)
    ElMessage.success('已取消收藏')
    isFavorited.value = false
    await loadFavorites()
  } catch {
    ElMessage.error('取消收藏失败')
  }
}

const handleMenuSelect = (index) => {
  if (index === '/matches') router.push('/matches')
  else if (index === '/news') router.push('/news')
  else if (index === '/profile') router.push('/profile')
}

const handleCommand = (cmd) => {
  if (cmd === 'logout') { userStore.logout(); router.push('/login') }
  else if (cmd === 'profile') router.push('/profile')
  else if (cmd === 'history') router.push('/profile?tab=history')
}

onMounted(async () => {
  await loadTeamDetail()
  await Promise.all([loadFavorites(), loadHeadToHead()])
})

watch(() => [route.params.teamId, route.params.teamName], async () => {
  await loadTeamDetail()
  await Promise.all([loadFavorites(), loadHeadToHead()])
})
</script>

<style scoped>
.layout { min-height: 100vh; background: transparent; }
.header {
  background: rgba(255,255,255,0.82); backdrop-filter: blur(18px);
  border: 1px solid rgba(255,255,255,0.65);
  box-shadow: 0 8px 30px rgba(15,23,42,0.08);
  display: flex; align-items: center; justify-content: space-between;
  padding: 0 24px; height: 60px; position: sticky; top: 0; z-index: 100;
}
.header-left { display: flex; align-items: center; gap: 10px; }
.title { font-size: 18px; font-weight: 700; color: #1a1a2e; }
.header-nav { flex: 1; margin: 0 40px; }
.header-right { display: flex; align-items: center; gap: 12px; }
.main-content { padding: 24px; max-width: 1000px; margin: 0 auto; }
.team-detail { display: flex; flex-direction: column; gap: 16px; }
.card-header { display: flex; justify-content: space-between; align-items: center; }

.team-profile { display: flex; gap: 24px; }
.team-logo-section { flex-shrink: 0; }
.team-logo { width: 120px; height: 120px; object-fit: contain; border-radius: 8px; }
.logo-placeholder {
  width: 120px; height: 120px; border-radius: 8px;
  background: linear-gradient(135deg, #409EFF, #66b1ff);
  display: flex; align-items: center; justify-content: center;
  font-size: 48px; color: #fff; font-weight: bold;
}
.team-details { flex: 1; }
.team-name { font-size: 24px; font-weight: 700; color: #1a1a2e; margin: 0 0 12px 0; }
.team-meta { display: flex; gap: 8px; margin-bottom: 16px; flex-wrap: wrap; }

.stats-overview {
  display: flex; gap: 8px; flex-wrap: wrap;
  background: #f5f7fa; border-radius: 10px; padding: 14px;
}
.stat-chip {
  display: flex; flex-direction: column; align-items: center;
  background: #fff; border-radius: 8px; padding: 10px 14px;
  min-width: 60px; border: 1px solid #eee;
}
.stat-chip.primary { background: #ecf5ff; border-color: #409EFF; }
.stat-chip.win .chip-val { color: #67c23a; }
.stat-chip.draw .chip-val { color: #909399; }
.stat-chip.lose .chip-val { color: #f56c6c; }
.chip-val { font-size: 22px; font-weight: 700; color: #333; }
.chip-label { font-size: 11px; color: #999; margin-top: 2px; }

.form-legend { display: flex; gap: 12px; font-size: 12px; color: #666; }
.fl-item { display: flex; align-items: center; gap: 4px; }
.fl-dot { width: 12px; height: 12px; border-radius: 2px; }
.fl-dot.win { background: #67c23a; }
.fl-dot.draw { background: #909399; }
.fl-dot.lose { background: #f56c6c; }

.form-badges { display: flex; gap: 8px; flex-wrap: wrap; }
.form-badge {
  display: flex; flex-direction: column; align-items: center;
  width: 56px; border-radius: 8px; padding: 8px 4px;
  border: 1px solid transparent;
}
.form-win { background: #f0f9eb; border-color: #67c23a; }
.form-draw { background: #f4f4f5; border-color: #909399; }
.form-lose { background: #fef0f0; border-color: #f56c6c; }
.badge-result { font-size: 18px; font-weight: 700; color: inherit; }
.badge-score { font-size: 10px; color: #999; margin-top: 2px; }
.form-win .badge-result { color: #67c23a; }
.form-draw .badge-result { color: #909399; }
.form-lose .badge-result { color: #f56c6c; }

.player-count { font-size: 13px; color: #999; }
.players-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(200px, 1fr)); gap: 10px; }
.h2h-summary.compact {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 10px;
  margin-bottom: 12px;
}
.h2h-matches.compact { gap: 6px; }
.player-card {
  display: flex; align-items: center; gap: 10px;
  background: #fafafa; border-radius: 8px; padding: 10px;
  transition: all 0.2s; border: 1px solid transparent;
}
.player-card:hover { background: #f0f7ff; border-color: #409EFF; }
.player-photo { width: 44px; height: 44px; border-radius: 50%; object-fit: cover; }
.player-photo-placeholder {
  width: 44px; height: 44px; border-radius: 50%;
  background: #ddd; display: flex; align-items: center; justify-content: center;
  font-size: 18px; color: #fff; flex-shrink: 0;
}
.player-info { flex: 1; min-width: 0; }
.player-name { font-size: 14px; font-weight: 600; color: #333; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.player-sub { display: flex; align-items: center; gap: 6px; margin-top: 3px; flex-wrap: wrap; }
.player-stat { display: flex; align-items: center; gap: 2px; font-size: 12px; color: #666; }

.scorer-cell { display: flex; align-items: center; gap: 8px; }
.scorer-photo { width: 32px; height: 32px; border-radius: 50%; object-fit: cover; }
.scorer-photo-placeholder {
  width: 32px; height: 32px; border-radius: 50%;
  background: #ddd; display: flex; align-items: center; justify-content: center;
  font-size: 14px; color: #fff;
}
.scorer-name { font-size: 13px; color: #333; }
.rank-badge {
  display: inline-block; width: 22px; height: 22px; border-radius: 4px;
  text-align: center; line-height: 22px; font-size: 11px; font-weight: 700;
  background: #f0f0f0; color: #666;
}
.rank-badge.gold { background: linear-gradient(135deg, #ffd700, #ffb800); color: #fff; }
.rank-badge.silver { background: #c0c0c0; color: #fff; }
.rank-badge.bronze { background: #cd7f32; color: #fff; }

.loading-state, .empty-state { text-align: center; padding: 40px; color: #999; }
.empty-state p { margin-top: 12px; }
</style>
