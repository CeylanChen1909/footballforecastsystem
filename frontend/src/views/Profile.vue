<template>
  <div class="layout">
    <el-container>
      <el-header class="header">
        <div class="header-left">
          <el-icon :size="28" color="#409EFF"><Football /></el-icon>
          <span class="title">ChenFootball</span>
        </div>

          <el-menu mode="horizontal" :default-active="activeMenu" class="utop-menu unified-menu" @select="handleMenuSelect">
            <el-menu-item index="/matches" class="menu-item"><el-icon><Calendar /></el-icon><span>比赛</span></el-menu-item>
            <el-menu-item index="/news" class="menu-item"><el-icon><Notebook /></el-icon><span>资讯</span></el-menu-item>
            <el-menu-item index="/videos" class="menu-item"><el-icon><VideoCamera /></el-icon><span>视频</span></el-menu-item>
            <el-menu-item index="/profile" class="menu-item"><el-icon><User /></el-icon><span>我的</span></el-menu-item>
          </el-menu>

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
        <section class="page-hero reveal">
          <div class="hero-left">
            <el-avatar :size="88" class="hero-avatar">{{ userStore.username?.[0]?.toUpperCase() }}</el-avatar>
            <div class="hero-copy">
              <div class="hero-title">{{ userStore.username || '游客' }}</div>
              <div class="hero-subtitle">{{ userStore.token ? '欢迎回来，继续管理你的收藏与预测记录' : '登录后可同步收藏与预测历史' }}</div>
              <div class="hero-meta-row">
                <el-tag :type="userStore.token ? 'success' : 'info'" effect="dark" round>{{ userStore.token ? '已登录' : '游客模式' }}</el-tag>
                <span class="hero-meta">ID：{{ userStore.userId || '未登录' }}</span>
              </div>
            </div>
          </div>
          <div class="hero-right">
            <el-button type="primary" plain @click="router.push('/matches')"><el-icon><Football /></el-icon> 去看比赛</el-button>
            <el-button type="success" plain @click="router.push('/news')"><el-icon><News /></el-icon> 浏览资讯</el-button>
          </div>
        </section>

        <div class="stats-row reveal">
          <div class="stat-card glass"><span class="stat-num">{{ favorites.length }}</span><span class="stat-text">收藏球队</span></div>
          <div class="stat-card glass"><span class="stat-num">{{ matchFavorites.length }}</span><span class="stat-text">收藏比赛</span></div>
          <div class="stat-card glass"><span class="stat-num">{{ history.length }}</span><span class="stat-text">预测历史</span></div>
        </div>

        <div class="section-grid">
          <el-card class="favorites-card panel-card reveal" shadow="hover">
            <template #header>
              <div class="card-header">
                <span><el-icon><Star /></el-icon> 收藏球队</span>
                <el-tag type="info" size="small">{{ favorites.length }} 支球队</el-tag>
              </div>
            </template>
            <div v-if="favoritesLoading" class="loading-state"><el-skeleton :rows="2" animated /></div>
            <div v-else-if="favorites.length === 0" class="empty-state">
              <el-icon :size="48" color="#ddd"><Star /></el-icon>
              <p>暂无收藏球队</p>
              <p class="hint">去比赛列表收藏你喜欢的球队吧！</p>
            </div>
            <div v-else class="favorites-grid">
              <div v-for="fav in favorites" :key="fav.id" class="fav-team-item">
                <div class="fav-team-info">
                  <span class="fav-team-name">{{ fav.teamName }}</span>
                  <span class="fav-date">收藏于 {{ formatDate(fav.createdAt) }}</span>
                </div>
                <div class="fav-actions">
                  <el-button size="small" type="primary" plain @click="goTeam(fav.teamId)">查看</el-button>
                  <el-button size="small" type="danger" plain @click="removeFav(fav.teamId)">取消</el-button>
                </div>
              </div>
            </div>
          </el-card>

          <el-card class="favorites-card panel-card reveal" shadow="hover">
            <template #header>
              <div class="card-header">
                <span><el-icon><Star /></el-icon> 收藏比赛</span>
                <el-tag type="info" size="small">{{ matchFavorites.length }} 场比赛</el-tag>
              </div>
            </template>
            <div v-if="matchFavoritesLoading" class="loading-state"><el-skeleton :rows="2" animated /></div>
            <div v-else-if="matchFavorites.length === 0" class="empty-state">
              <el-icon :size="48" color="#ddd"><Star /></el-icon>
              <p>暂无收藏比赛</p>
              <p class="hint">去比赛列表收藏你喜欢的比赛吧！</p>
            </div>
            <div v-else class="favorites-grid">
              <div v-for="fav in matchFavorites" :key="fav.id" class="fav-team-item">
                <div class="fav-team-info">
                  <span class="fav-team-name">{{ fav.homeTeamName }} VS {{ fav.awayTeamName }}</span>
                  <span class="fav-date">收藏于 {{ formatDate(fav.createdAt) }}</span>
                </div>
                <div class="fav-actions">
                  <el-button size="small" type="primary" plain @click="goPrediction(fav.fixtureId, fav.homeTeamId, fav.awayTeamId)">查看</el-button>
                  <el-button size="small" type="danger" plain @click="removeMatchFav(fav.fixtureId)">取消</el-button>
                </div>
              </div>
            </div>
          </el-card>
        </div>

        <el-card class="history-card panel-card reveal" shadow="hover">
          <template #header>
            <div class="card-header">
              <div class="section-title-wrap"><el-icon><Histogram /></el-icon><span>预测历史</span></div>
              <el-button link type="primary" @click="loadHistory(100)">加载更多</el-button>
            </div>
          </template>
          <el-tabs v-model="historyFilter" class="history-filter-tabs">
            <el-tab-pane label="全部" name="all" />
            <el-tab-pane label="主胜" name="HOME_WIN" />
            <el-tab-pane label="平局" name="DRAW" />
            <el-tab-pane label="客胜" name="AWAY_WIN" />
          </el-tabs>
          <div v-if="historyLoading" class="loading-state"><el-skeleton :rows="4" animated /></div>
          <div v-else-if="filteredHistory.length === 0" class="empty-state">
            <el-icon :size="48" color="#ddd"><Tickets /></el-icon>
            <p>暂无预测记录</p>
            <p class="hint">去比赛页面尝试预测一下吧！</p>
          </div>
          <div v-else class="history-list">
            <div v-for="item in filteredHistory" :key="item.id" class="history-item" @click="goPrediction(item.fixtureId, item.homeTeamId, item.awayTeamId)">
              <div class="history-main">
                <div class="history-date"><el-icon><Calendar /></el-icon>{{ formatDate(item.createdAt) }}</div>
                <div class="history-league"><el-tag size="small" type="info">{{ item.leagueName || '联赛' }}</el-tag></div>
              </div>
              <div class="history-match">
                <span class="team-name">{{ item.homeTeamName || '球队A' }}</span>
                <span class="vs">VS</span>
                <span class="team-name">{{ item.awayTeamName || '球队B' }}</span>
              </div>
              <div class="history-result">
                <div class="prob-bar-group">
                  <div class="prob-item"><span class="label">主胜</span><el-progress :percentage="Math.round((item.homeWinProb || 0) * 100)" :color="homeColor" :stroke-width="10" :show-text="false" /><span class="val">{{ ((item.homeWinProb || 0) * 100).toFixed(0) }}%</span></div>
                  <div class="prob-item"><span class="label">平</span><el-progress :percentage="Math.round((item.drawProb || 0) * 100)" :color="drawColor" :stroke-width="10" :show-text="false" /><span class="val">{{ ((item.drawProb || 0) * 100).toFixed(0) }}%</span></div>
                  <div class="prob-item"><span class="label">客胜</span><el-progress :percentage="Math.round((item.awayWinProb || 0) * 100)" :color="awayColor" :stroke-width="10" :show-text="false" /><span class="val">{{ ((item.awayWinProb || 0) * 100).toFixed(0) }}%</span></div>
                </div>
                <el-tag :type="resultTagType(item.resultLabel)" size="small">{{ resultLabel(item.resultLabel) }}</el-tag>
                <el-tag v-if="item.isCorrect === 1" type="success" size="small" style="margin-left:4px">正确</el-tag>
                <el-tag v-else-if="item.isCorrect === 0" type="danger" size="small" style="margin-left:4px">错误</el-tag>
              </div>
            </div>
          </div>
        </el-card>
      </el-main>
    </el-container>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useUserStore } from '../stores/user'
import { predictionApi, favoriteApi } from '../api'
import { ElMessage } from 'element-plus'
import {Notebook} from "@element-plus/icons-vue";

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const activeMenu = '/profile'
const historyFilter = ref('all')
const history = ref([])
const favorites = ref([])
const matchFavorites = ref([])
const historyLoading = ref(false)
const favoritesLoading = ref(false)
const matchFavoritesLoading = ref(false)

const homeColor = '#409EFF'
const drawColor = '#909399'
const awayColor = '#E6A23C'

const stats = computed(() => {
  const total = history.value.length
  const correct = history.value.filter(h => h.isCorrect === 1).length
  const wrong = history.value.filter(h => h.isCorrect === 0).length
  const accuracy = total > 0 ? Math.round(correct / total * 100) : 0
  return { total, correct, wrong, accuracy }
})

const filteredHistory = computed(() => {
  if (historyFilter.value === 'all') return history.value
  return history.value.filter(h => h.resultLabel === historyFilter.value)
})

const normalizeHistoryItem = (item) => ({
  id: item.id,
  userId: item.userId,
  fixtureId: item.fixtureId,
  homeTeamId: item.homeTeamId,
  awayTeamId: item.awayTeamId,
  homeTeamName: item.homeTeamName,
  awayTeamName: item.awayTeamName,
  leagueName: item.leagueName,
  resultLabel: item.resultLabel,
  homeWinProb: item.homeWinProb,
  drawProb: item.drawProb,
  awayWinProb: item.awayWinProb,
  modelVersion: item.modelVersion,
  explanation: item.explanation,
  isCorrect: item.isCorrect,
  actualResult: item.actualResult,
  createdAt: item.createdAt,
  verifiedAt: item.verifiedAt
})

const loadHistory = async (limit = 20) => {
  historyLoading.value = true
  try {
    const res = await predictionApi.getHistory(limit)
    const list = Array.isArray(res) ? res : (res?.response || res?.data?.response || res?.data || [])
    history.value = list.map(normalizeHistoryItem)
  } catch {
    history.value = []
  } finally {
    historyLoading.value = false
  }
}

const loadFavorites = async () => {
  favoritesLoading.value = true
  try {
    const res = await favoriteApi.list()
    favorites.value = Array.isArray(res) ? res : (res?.response || res?.data?.response || res?.data || [])
  } catch {
    favorites.value = []
  } finally {
    favoritesLoading.value = false
  }
}

const loadMatchFavorites = async () => {
  matchFavoritesLoading.value = true
  try {
    const res = await favoriteApi.listMatches()
    matchFavorites.value = Array.isArray(res) ? res : (res?.response || res?.data?.response || res?.data || [])
  } catch {
    matchFavorites.value = []
  } finally {
    matchFavoritesLoading.value = false
  }
}

const removeFav = async (teamId) => {
  try {
    await favoriteApi.remove(teamId)
    ElMessage.success('已取消收藏')
    await loadFavorites()
  } catch {
    ElMessage.error('取消收藏失败')
  }
}

const removeMatchFav = async (fixtureId) => {
  try {
    await favoriteApi.removeMatch(fixtureId)
    ElMessage.success('已取消收藏比赛')
    await loadMatchFavorites()
  } catch {
    ElMessage.error('取消收藏比赛失败')
  }
}

const goTeam = (teamId) => {
  router.push(`/team/${teamId}`)
}

const goPrediction = (fixtureId, homeId, awayId) => {
  router.push(`/prediction/${fixtureId}?home=${homeId}&away=${awayId}`)
}

const formatDate = (dateStr) => {
  if (!dateStr) return ''
  return new Date(dateStr).toLocaleString('zh-CN', {
    year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit'
  })
}

const resultLabel = (label) => {
  if (label === 'HOME_WIN') return '预测:主胜'
  if (label === 'AWAY_WIN') return '预测:客胜'
  return '预测:平局'
}

const resultTagType = (label) => {
  if (label === 'HOME_WIN') return 'primary'
  if (label === 'AWAY_WIN') return 'warning'
  return 'info'
}

const handleMenuSelect = (index) => {
  if (index === '/matches') router.push('/matches')
  else if (index === '/news') router.push('/news')
  else if (index === '/videos') router.push('/videos')
  else if (index === '/profile') router.push('/profile')
}

const activeTab = computed(() => route.query.tab === 'history' ? 'history' : 'profile')

const handleCommand = (cmd) => {
  if (cmd === 'logout') { userStore.logout(); router.push('/login') }
  else if (cmd === 'profile') router.push('/profile')
  else if (cmd === 'history') router.push('/profile?tab=history')
}

onMounted(() => {
  loadHistory(50)
  loadFavorites()
  loadMatchFavorites()
})
</script>

<style scoped>
.layout { min-height: 100vh; background:
  radial-gradient(circle at top left, rgba(64,158,255,0.12), transparent 28%),
  radial-gradient(circle at top right, rgba(103,194,58,0.08), transparent 24%),
  #f4f7fb; }
.header {
  background: rgba(255,255,255,0.92); backdrop-filter: blur(10px);
  box-shadow: 0 10px 30px rgba(31,45,61,0.08);
  display: flex; align-items: center; justify-content: space-between;
  padding: 0 24px; height: 64px; position: sticky; top: 0; z-index: 100;
}
.header-left { display: flex; align-items: center; gap: 10px; }
.title { font-size: 18px; font-weight: 800; color: #172033; letter-spacing: .2px; }
.header-nav { flex: 1; margin: 0 40px; }
.unified-menu {
  background: rgba(15,23,42,.03);
  padding: 6px;
  border-radius: 999px;
  border: 1px solid rgba(148,163,184,.18);
}
.unified-menu :deep(.el-menu-item) {
  height: 40px;
  line-height: 40px;
  border-bottom: none !important;
  border-radius: 999px;
  margin: 0 4px;
  color: #334155;
  transition: all .22s ease;
}
.unified-menu :deep(.el-menu-item:hover) { background: rgba(64,158,255,.08); color: #1d4ed8; }
.unified-menu :deep(.el-menu-item.is-active) {
  background: linear-gradient(135deg, #409eff, #1d4ed8);
  color: #fff !important;
  box-shadow: 0 10px 18px rgba(64,158,255,.24);
}
.menu-item { display:flex; align-items:center; gap:6px; font-weight:600; }
.header-right { display: flex; align-items: center; gap: 12px; }
.main-content { padding: 24px; max-width: 1180px; margin: 0 auto; }
.page-hero {
  display: flex; justify-content: space-between; align-items: center; gap: 24px;
  background: linear-gradient(135deg, #0f172a 0%, #1d4ed8 55%, #38bdf8 100%);
  color: #fff; border-radius: 22px; padding: 28px 30px; margin-bottom: 18px;
  box-shadow: 0 20px 50px rgba(15,23,42,0.20);
}
.hero-left { display: flex; align-items: center; gap: 18px; }
.hero-avatar { border: 2px solid rgba(255,255,255,0.3); background: rgba(255,255,255,0.12); font-weight: 800; }
.hero-copy { display: flex; flex-direction: column; gap: 8px; }
.hero-title { font-size: 28px; font-weight: 800; line-height: 1.1; }
.hero-subtitle { font-size: 14px; opacity: .9; max-width: 520px; }
.hero-meta-row { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
.hero-meta { font-size: 13px; opacity: .9; }
.hero-right { display: flex; gap: 10px; flex-wrap: wrap; }
.stats-row { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 14px; margin-bottom: 16px; }
.stat-card { border-radius: 18px; padding: 18px; }
.glass { background: rgba(255,255,255,0.72); backdrop-filter: blur(14px); box-shadow: 0 12px 30px rgba(15,23,42,.08); border:1px solid rgba(255,255,255,.75); }
.stat-num { display:block; font-size: 30px; font-weight: 800; color:#172033; }
.stat-text { display:block; margin-top:4px; font-size: 13px; color:#64748b; }
.main-content { padding: 24px; max-width: 1180px; margin: 0 auto; }

.page-hero {
  display: flex; justify-content: space-between; align-items: center; gap: 24px;
  background: linear-gradient(135deg, #0f172a, #1d4ed8 55%, #38bdf8);
  color: #fff; border-radius: 22px; padding: 28px 30px; margin-bottom: 18px;
  box-shadow: 0 20px 50px rgba(15,23,42,0.20); transition: transform .25s ease;
}
.page-hero:hover { transform: translateY(-2px); }
.hero-left { display: flex; align-items: center; gap: 18px; }
.hero-avatar { border: 2px solid rgba(255,255,255,0.3); background: rgba(255,255,255,0.12); font-weight: 800; }
.hero-copy { display: flex; flex-direction: column; gap: 8px; }
.hero-title { font-size: 28px; font-weight: 800; line-height: 1.1; }
.hero-subtitle { font-size: 14px; opacity: .9; max-width: 520px; }
.hero-meta-row { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
.hero-meta { font-size: 13px; opacity: .9; }
.hero-right { display: flex; gap: 10px; flex-wrap: wrap; }

.stats-row { display:grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 14px; margin-bottom: 18px; }
.stat-card.glass {
  border-radius: 18px; padding: 18px 20px; background: rgba(255,255,255,.72); backdrop-filter: blur(12px);
  border: 1px solid rgba(255,255,255,.7); box-shadow: 0 10px 24px rgba(15,23,42,.06);
  transition: transform .2s ease, box-shadow .2s ease;
}
.stat-card.glass:hover { transform: translateY(-2px); box-shadow: 0 16px 28px rgba(64,158,255,.10); }
.stat-num { display:block; font-size:30px; font-weight:800; color:#172033; line-height:1; }
.stat-text { display:block; margin-top:8px; font-size:13px; color:#6b7280; font-weight:600; }

.section-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 18px; margin-bottom: 18px; }
@media (max-width: 920px) { .section-grid, .stats-row { grid-template-columns: 1fr; } .page-hero { flex-direction: column; align-items: flex-start; } }

.panel-card { border: 1px solid rgba(15,23,42,0.06); border-radius: 18px; overflow: hidden; box-shadow: 0 10px 30px rgba(15,23,42,0.07); transition: transform .2s ease, box-shadow .2s ease; }
.panel-card:hover { transform: translateY(-2px); box-shadow: 0 18px 36px rgba(15,23,42,0.10); }
.card-header { display: flex; justify-content: space-between; align-items: center; }
.section-title-wrap { display: flex; align-items: center; gap: 8px; font-weight: 700; color: #172033; }

.loading-state, .empty-state { text-align: center; padding: 40px; color: #8b97a8; }
.empty-state p { margin-top: 12px; }
.hint { color: #aab4c3; font-size: 13px; }

.favorites-grid { display: grid; grid-template-columns: 1fr; gap: 12px; }
.fav-team-item {
  background: linear-gradient(180deg, #fff, #fbfcfe); border-radius: 14px; padding: 16px;
  display: flex; justify-content: space-between; align-items: center; gap: 12px;
  border: 1px solid #eef2f7; transition: all 0.22s ease;
}
.fav-team-item:hover { transform: translateY(-1px); box-shadow: 0 10px 24px rgba(15,23,42,0.08); border-color: #d7e6ff; }
.fav-team-info { display: flex; flex-direction: column; gap: 4px; min-width: 0; }
.fav-team-name { font-size: 15px; font-weight: 700; color: #172033; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.fav-date { font-size: 12px; color: #93a1b5; }
.fav-actions { display: flex; gap: 8px; flex-wrap: wrap; }

.history-card { margin-bottom: 0; }
.history-filter-tabs { margin-bottom: 14px; }
.history-list { display: flex; flex-direction: column; gap: 12px; }
.history-item {
  background: linear-gradient(180deg, #fff, #fcfdff); border-radius: 16px; padding: 16px 18px;
  cursor: pointer; transition: all 0.22s ease; border: 1px solid #e9eef5;
  display: flex; align-items: center; gap: 16px; flex-wrap: wrap;
}
.history-item:hover { transform: translateY(-2px); border-color: #cfe0ff; box-shadow: 0 14px 30px rgba(64,158,255,0.10); }
.history-main { display: flex; flex-direction: column; gap: 6px; width: 140px; }
.history-date { display: flex; align-items: center; gap: 6px; font-size: 12px; color: #93a1b5; }
.history-match { flex: 1; display: flex; align-items: center; gap: 10px; font-size: 14px; }
.team-name { font-weight: 700; color: #172033; }
.vs { color: #93a1b5; font-size: 12px; font-weight: 700; }
.history-result { display: flex; flex-direction: column; gap: 8px; align-items: flex-end; min-width: 240px; }

.prob-bar-group { display: flex; flex-direction: column; gap: 6px; width: 180px; }
.prob-item { display: flex; align-items: center; gap: 8px; }
.prob-item .label { font-size: 11px; color: #7b8796; width: 28px; }
.prob-item .el-progress { flex: 1; }
.prob-item .val { font-size: 11px; color: #556070; width: 38px; text-align: right; font-weight: 700; }
.reveal { animation: fadeUp .55s ease both; }
@keyframes fadeUp { from { opacity: 0; transform: translateY(14px); } to { opacity: 1; transform: translateY(0); } }
</style>
