<template>
  <div class="layout">
    
    <el-container>
      <el-header class="app-header">
        <div class="brand">
          <div class="brand-icon"><el-icon :size="22"><Football /></el-icon></div>
          <div>
            <div class="brand-title">ChenFootball</div>
            <div class="brand-subtitle">智能赛程 · 预测分析 · 数据洞察</div>
          </div>
        </div>
        <el-menu mode="horizontal" :default-active="'0'" class="top-menu unified-menu" @select="handleMenuSelect">
          <el-menu-item index="0" class="menu-item"><el-icon><Calendar /></el-icon><span>比赛</span></el-menu-item>
          <el-menu-item index="1" class="menu-item"><el-icon><Notebook /></el-icon><span>资讯</span></el-menu-item>
          <el-menu-item index="2" class="menu-item"><el-icon><VideoCamera /></el-icon><span>视频</span></el-menu-item>
          <el-menu-item index="3" class="menu-item"><el-icon><User /></el-icon><span>我的</span></el-menu-item>
        </el-menu>
        <div class="header-actions">
          <el-button class="icon-btn" :icon="Refresh" circle @click="loadMatches" :loading="loading" />
          <el-dropdown @command="handleCommand">
            <el-avatar :size="36" class="header-avatar">{{ userStore.username?.[0]?.toUpperCase() }}</el-avatar>
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
        <div class="hero-banner reveal">
          <div class="hero-copy">
            <div class="hero-tag">WORLD FOOTBALL DATA HUB</div>
            <h1>专业足球数据中心</h1>
            <p>实时赛程、积分榜、球队详情、交锋记录与历史赛果，一站式掌握核心足球数据。</p>
          </div>
          <div class="hero-metrics">
            <div class="metric"><span>{{ upcomingCount }}</span><label>待开赛</label></div>
<!--            <div class="metric"><span>{{ rawMatches.length }}</span><label>总场次</label></div>-->
            <div class="metric"><span>{{ favoritesCount }}</span><label>收藏</label></div>
          </div>
        </div>

        <!-- 概览卡片 -->
        <div class="overview-grid reveal">
          <el-card class="overview-card hot" shadow="never">
            <div class="overview-value">{{ upcomingCount }}</div>
            <div class="overview-label">即将开始</div>
          </el-card>
          <el-card class="overview-card" shadow="never">
            <div class="overview-value">{{ filteredMatches.length }}</div>
            <div class="overview-label">筛选结果</div>
          </el-card>
        </div>

        <!-- 筛选栏 -->
        <div class="filter-bar reveal">
          <div class="filter-info">
            <span>
              <el-icon><Calendar /></el-icon>
              比赛
            </span>
            <span class="match-count">
              共 {{ rawMatches.length }} 场比赛
            </span>
            <span class="date-window">{{ dateWindowText }}</span>
          </div>
          <div class="filter-actions">
            <el-date-picker
              v-model="selectedDate"
              type="date"
              value-format="YYYY-MM-DD"
              format="YYYY-MM-DD"
              placeholder="选择比赛日期"
              clearable
              size="small"
              @change="handleDateChange"
            />
            <el-button size="small" text @click="resetDateFilter">今日开始</el-button>
          </div>
        </div>

        <!-- 加载状态 -->
        <div v-if="loading" class="loading-state">
          <el-icon class="is-loading" :size="40"><Loading /></el-icon>
          <p>正在加载比赛数据...</p>
        </div>

        <!-- 错误状态 -->
        <div v-else-if="errorMsg" class="error-state">
          <el-icon :size="48" color="#F56C6C"><WarningFilled /></el-icon>
          <p>{{ errorMsg }}</p>
          <el-button type="primary" @click="loadMatches">重试</el-button>
        </div>

        <!-- 比赛列表 -->
        <div v-else class="matches-container">
          <div class="hot-panel reveal" :class="{ 'is-collapsed': !showHotPanel }">
            <button class="hot-panel-toggle" type="button" @click="showHotPanel = !showHotPanel">
              <span class="section-title">
                <el-icon><Star /></el-icon>
                热门推荐
                <el-tag size="small" type="warning" effect="plain">{{ hotMatches.length }} 场</el-tag>
              </span>
              <span class="hot-panel-actions">
                <el-button size="small" text @click.stop="loadHotMatches">刷新</el-button>
                <span class="collapse-text">{{ showHotPanel ? '收起' : '展开' }}</span>
                <el-icon class="collapse-icon"><ArrowDown /></el-icon>
              </span>
            </button>
            <el-collapse-transition>
              <div v-show="showHotPanel" class="hot-panel-body">
                <div v-if="hotMatches.length === 0" class="hot-empty">暂无热门推荐，刷新或同步比赛数据后再试</div>
                <div v-else class="matches-grid">
                  <MatchCard
                    v-for="m in hotMatches"
                    :key="'hot-' + m.fixture?.id"
                    :match="m"
                    :favorited="isFavoritedMatch(m.fixture?.id)"
                    @predict="goPredict"
                    @teamClick="goTeamDetail"
                    @h2h="showH2H"
                    @favorite-match="toggleMatchFavorite"
                  />
                </div>
              </div>
            </el-collapse-transition>
          </div>

          <!-- 全部比赛 -->
          <div class="section-header reveal" v-if="filteredMatches.length > 0">
            <span class="section-title">比赛列表</span>
          </div>
          <div v-if="filteredMatches.length === 0" class="empty-state">
            <el-icon :size="64" color="#ddd"><Tickets /></el-icon>
            <p>当天暂无比赛</p>
            <p class="empty-hint">请先在后台同步数据源，或稍后刷新重试</p>
          </div>
          <div v-else class="date-group-list">
            <div v-for="group in groupedMatches" :key="group.date" class="date-group reveal">
              <div class="date-group-header">
                <div>
                  <strong>{{ group.label }}</strong>
                  <span>{{ group.weekday }}</span>
                </div>
                <el-tag type="info" effect="plain">{{ group.items.length }} 场</el-tag>
              </div>
              <div class="matches-grid">
                <MatchCard
                  v-for="m in group.items"
                  :key="m.fixture?.id"
                  :match="m"
                  :favorited="isFavoritedMatch(m.fixture?.id)"
                  @predict="goPredict"
                  @teamClick="goTeamDetail"
                  @h2h="showH2H"
                  @favorite-match="toggleMatchFavorite"
                />
              </div>
            </div>
          </div>
        </div>
      </el-main>
    </el-container>

    <!-- 历史交锋弹窗 -->
    <el-dialog v-model="h2hVisible" title="历史交锋" width="600px">
      <div v-if="h2hLoading" class="loading-state">
        <el-skeleton :rows="5" animated />
      </div>
      <div v-else-if="h2hData" class="h2h-content">
        <div class="h2h-summary">
          <div class="summary-item">
            <span class="summary-value">{{ h2hData.summary?.homeWins || 0 }}</span>
            <span class="summary-label">主队胜</span>
          </div>
          <div class="summary-item">
            <span class="summary-value">{{ h2hData.summary?.draws || 0 }}</span>
            <span class="summary-label">平局</span>
          </div>
          <div class="summary-item">
            <span class="summary-value">{{ h2hData.summary?.awayWins || 0 }}</span>
            <span class="summary-label">客队胜</span>
          </div>
        </div>
        <div class="h2h-matches">
          <div v-for="(match, idx) in h2hData.recentMatches" :key="idx" class="h2h-match">
            <span class="h2h-date">{{ match.date }}</span>
            <span class="h2h-score">{{ match.homeScore }} - {{ match.awayScore }}</span>
          </div>
        </div>
      </div>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useUserStore } from '../stores/user'
import { crawlerApi, newsApi, favoriteApi } from '../api'
import { ElMessage } from 'element-plus'
import {ArrowDown, Notebook} from '@element-plus/icons-vue'
import MatchCard from '../components/MatchCard.vue'

const router = useRouter()
const userStore = useUserStore()

const loading = ref(false)
const errorMsg = ref('')
const rawMatches = ref([])
const showHotPanel = ref(true)
const hotMatches = ref([])
const selectedDate = ref('')
const matchFavorites = ref([])

// 历史交锋
const h2hVisible = ref(false)
const h2hLoading = ref(false)
const h2hData = ref(null)

const getMatchDate = (match) => match?.matchDate || String(match?.fixture?.date || '').slice(0, 10)
const formatDateLabel = (date) => {
  if (!date) return '日期未知'
  const today = new Date().toISOString().slice(0, 10)
  const tomorrow = new Date(Date.now() + 86400000).toISOString().slice(0, 10)
  const yesterday = new Date(Date.now() - 86400000).toISOString().slice(0, 10)
  if (date === today) return `${date} · 今天`
  if (date === tomorrow) return `${date} · 明天`
  if (date === yesterday) return `${date} · 昨天`
  return date
}
const weekdayLabel = (date) => {
  if (!date) return '-'
  return new Intl.DateTimeFormat('zh-CN', { weekday: 'long' }).format(new Date(`${date}T00:00:00`))
}
const filteredMatches = computed(() => rawMatches.value || [])
const groupedMatches = computed(() => {
  const map = new Map()
  ;(filteredMatches.value || []).forEach(match => {
    const date = getMatchDate(match) || 'unknown'
    if (!map.has(date)) map.set(date, [])
    map.get(date).push(match)
  })
  return Array.from(map.entries()).sort(([a], [b]) => a.localeCompare(b)).map(([date, items]) => ({
    date,
    label: formatDateLabel(date),
    weekday: weekdayLabel(date),
    items: items.sort((a, b) => String(a?.fixture?.date || '').localeCompare(String(b?.fixture?.date || '')))
  }))
})
const todayDate = computed(() => new Date().toISOString().slice(0, 10))
const windowEndDate = computed(() => new Date(Date.now() + 7 * 86400000).toISOString().slice(0, 10))
const dateWindowText = computed(() => selectedDate.value ? `当前筛选 ${selectedDate.value}` : `默认从今日 ${todayDate.value} 开始，显示至 ${windowEndDate.value}`)
const handleDateChange = async () => {
  if (!selectedDate.value) {
    await loadMatches()
    return
  }
  await loadMatchesByDate(selectedDate.value)
}
const resetDateFilter = async () => { selectedDate.value = ''; await loadMatches() }

const upcomingCount = computed(() => {
  return (rawMatches.value || []).filter(m => m?.fixture?.status?.short === 'NS').length
})

const favoritesCount = computed(() => matchFavorites.value?.length || 0)

const loadHotMatches = async () => {
  try {
    const res = await crawlerApi.getHotMatches(8)
    hotMatches.value = res?.response || res?.data?.response || []
  } catch (e) {
    hotMatches.value = []
  }
}

const loadFavorites = async () => {
  try {
    const res = await favoriteApi.listMatches()
    const payload = res?.data ?? res
    matchFavorites.value = Array.isArray(payload) ? payload : (payload?.data || [])
  } catch {
    matchFavorites.value = []
  }
}

const normalizeMatchList = (res) => {
  if (Array.isArray(res)) return res
  if (res && Array.isArray(res.response)) return res.response
  if (res && Array.isArray(res.data)) return res.data
  if (res && res.data && Array.isArray(res.data.response)) return res.data.response
  return []
}

const loadMatches = async () => {
  loading.value = true
  errorMsg.value = ''
  
  try {
    const res = await crawlerApi.getMatchesWindow(1, 300)
    rawMatches.value = normalizeMatchList(res).filter(match => getMatchDate(match) >= todayDate.value)
    
    if (rawMatches.value.length === 0) {
      ElMessage.info('今日开始暂无比赛数据')
    }
  } catch (e) {
    errorMsg.value = e.message || '加载比赛失败，请检查后端服务是否启动'
  } finally {
    loading.value = false
  }
}

const loadMatchesByDate = async (date) => {
  loading.value = true
  errorMsg.value = ''
  try {
    const res = await crawlerApi.getMatchesPage(1, 300, date)
    rawMatches.value = normalizeMatchList(res)
    if (rawMatches.value.length === 0) ElMessage.info('该日期暂无比赛数据')
  } catch (e) {
    errorMsg.value = e.message || '加载比赛失败，请检查后端服务是否启动'
  } finally {
    loading.value = false
  }
}

const showH2H = async (fixtureId, homeTeamId, awayTeamId, homeTeamName, awayTeamName) => {
  const safeHomeName = homeTeamName || ''
  const safeAwayName = awayTeamName || ''
  if (!safeHomeName || !safeAwayName) return
  h2hVisible.value = true
  h2hLoading.value = true
  try {
    const res = await crawlerApi.getHeadToHead(safeHomeName, safeAwayName)
    const data = res?.data || res || {}
    h2hData.value = { ...data, homeTeamName: safeHomeName, awayTeamName: safeAwayName, fixtureId, homeTeamId, awayTeamId }
  } catch (e) {
    h2hData.value = { recentMatches: [], summary: { homeWins: 0, draws: 0, awayWins: 0 }, homeTeamName: safeHomeName, awayTeamName: safeAwayName, fixtureId, homeTeamId, awayTeamId }
  } finally {
    h2hLoading.value = false
  }
}

const goPredict = (matchOrFixtureId, homeId, awayId, homeTeamNameArg, awayTeamNameArg) => {
  let fixtureId, hId, aId, homeTeamName, awayTeamName, leagueName, leagueId, round, matchTime, homeLogo, awayLogo

  if (typeof matchOrFixtureId === 'object' && matchOrFixtureId !== null) {
    const match = matchOrFixtureId
    fixtureId = match.fixture?.id
    hId = match.teams?.home?.id || 0
    aId = match.teams?.away?.id || 0
    homeTeamName = match.teams?.home?.name || '主队'
    awayTeamName = match.teams?.away?.name || '客队'
    homeLogo = match.teams?.home?.logo || ''
    awayLogo = match.teams?.away?.logo || ''
    leagueName = match.league?.name || ''
    leagueId = match.league?.id || ''
    round = match.league?.round || ''
    matchTime = match.fixture?.date || ''
  } else {
    fixtureId = matchOrFixtureId
    hId = homeId || 0
    aId = awayId || 0
    homeTeamName = homeTeamNameArg || '主队'
    awayTeamName = awayTeamNameArg || '客队'
    homeLogo = ''
    awayLogo = ''
    leagueName = ''
    leagueId = ''
    round = ''
    matchTime = ''
  }

  if (fixtureId) {
    router.push({
      path: `/prediction/${fixtureId}`,
      query: {
        home: homeTeamName,
        away: awayTeamName,
        homeId: hId,
        awayId: aId,
        homeName: homeTeamName,
        awayName: awayTeamName,
        homeLogo,
        awayLogo,
        leagueName,
        leagueId,
        round,
        matchTime,
      }
    })
  }
}

const goTeamDetail = (teamName, side) => {
  const target = teamName
  if (target) {
    router.push(`/team/${encodeURIComponent(target)}`)
  }
}

const handleMenuSelect = (index) => {
  if (index === '0') return
  else if (index === '1') router.push('/news')
  else if (index === '2') router.push('/videos')
  else if (index === '3') router.push('/profile')
}

const handleCommand = (cmd) => {
  if (cmd === 'logout') {
    userStore.logout()
    router.push('/login')
  } else if (cmd === 'profile') {
    router.push('/profile')
  } else if (cmd === 'history') {
    router.push('/profile?tab=history')
  }
}

const toggleMatchFavorite = async (match) => {
  const fixtureId = match?.fixture?.id
  if (!fixtureId) return
  const exists = isFavoritedMatch(fixtureId)
  try {
    if (exists) {
      await favoriteApi.removeMatch(fixtureId)
      ElMessage.success('已取消收藏比赛')
    } else {
      const homeName = match?.teams?.home?.name || '主队'
      const awayName = match?.teams?.away?.name || '客队'
      await favoriteApi.addMatch(fixtureId, `${homeName} vs ${awayName}`)
      ElMessage.success('比赛收藏成功')
    }
    await loadFavorites()
  } catch (e) {
    const msg = String(e?.message || e?.response?.data?.message || '')
    if (msg.toLowerCase().includes('unauthorized')) {
      ElMessage.error('登录已过期，请重新登录')
    } else {
      ElMessage.error('收藏操作失败，请先登录')
    }
  }
}

const isFavoritedMatch = (fixtureId) => {
  const id = String(fixtureId)
  return matchFavorites.value.some(item => String(item.fixtureId || item.id || item.matchId || '') === id)
}

onMounted(() => {
  loadMatches()
  loadHotMatches()
  loadFavorites()
})

// 比赛页固定展示今天前后 7 天窗口。
</script>

<style scoped>
.layout {
  min-height: 100vh;
  background: linear-gradient(180deg, #f4f7fb 0%, #eef3f8 100%);
}
.app-header {
  display:flex; align-items:center; justify-content:space-between; gap:24px;
  background:rgba(255,255,255,.92); backdrop-filter:blur(16px);
  box-shadow:0 10px 30px rgba(16,24,40,.08);
  position:sticky; top:0; z-index:10; padding:0 24px; height:64px;
}
.brand { display:flex; align-items:center; gap:14px; min-width:260px; }
.brand-icon {
  width:44px; height:44px; border-radius:14px; display:flex; align-items:center; justify-content:center;
  background:linear-gradient(135deg,#409eff 0%,#3c6fe8 100%); color:#fff; box-shadow:0 10px 20px rgba(64,158,255,.25);
}
.brand-title { font-size:18px; font-weight:800; color:#172033; line-height:1.1; }
.brand-subtitle { font-size:12px; color:#6b7280; margin-top:4px; }
.top-menu { flex:1; border-bottom:none; justify-content:center; }
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
.header-actions { display:flex; align-items:center; gap:12px; }
.header-avatar { background:#409EFF; }
.icon-btn { box-shadow:0 8px 18px rgba(64,158,255,.18); }
.main-content { padding:24px; max-width:1400px; margin:0 auto; }

.hero-banner {
  display:grid; grid-template-columns:1.55fr .95fr; gap:20px; margin-bottom:18px; padding:28px;
  border-radius:24px; color:#fff; overflow:hidden;
  background:linear-gradient(135deg,#0f172a 0%,#1d4ed8 52%,#0ea5e9 100%);
  box-shadow:0 18px 40px rgba(2,8,23,.18); transition:transform .25s ease;
}
.hero-banner:hover { transform: translateY(-2px); }
.hero-copy { display:flex; flex-direction:column; justify-content:center; min-width:0; }
.hero-kicker {
  font-size: 12px; letter-spacing: 1.8px; text-transform: uppercase; opacity: 0.75; margin-bottom: 10px;
}
.hero-banner h1 { font-size: 34px; margin-bottom: 10px; }
.hero-banner p { color: rgba(255,255,255,0.82); max-width: 640px; line-height: 1.7; }
.hero-metrics {
  display: grid; grid-template-columns: 1fr 1fr; gap: 12px; align-items: stretch; align-content:center;
}
.hero-stat {
  min-height: 100px; padding: 14px 16px; border-radius: 16px; background: rgba(255,255,255,0.12);
  backdrop-filter: blur(10px); text-align: center; display:flex; flex-direction:column; justify-content:center;
  transition:transform .2s ease, background .2s ease;
}
.hero-stat:hover { transform: translateY(-2px); background: rgba(255,255,255,0.16); }
.hero-stat span { display:block; font-size:28px; font-weight:700; }
.hero-stat small { opacity:0.8; }
.hero-stat.live { background: rgba(239, 68, 68, 0.18); }

.overview-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14px;
  margin-bottom: 16px;
}
.overview-card {
  text-align: left;
  border-radius: 18px;
  padding: 18px 18px 16px;
  background: linear-gradient(180deg, #fff, #fbfdff);
  border: 1px solid #e8eef5;
  box-shadow: 0 10px 24px rgba(15,23,42,.06);
  transition: transform .2s ease, box-shadow .2s ease, border-color .2s ease;
  position: relative;
  overflow: hidden;
}
.overview-card::after {
  content: '';
  position: absolute; inset: auto -20% -45% auto;
  width: 120px; height: 120px; border-radius: 50%;
  background: radial-gradient(circle, rgba(64,158,255,.14), transparent 70%);
}
.overview-card:hover { transform: translateY(-3px); border-color: #d4e5ff; box-shadow: 0 16px 28px rgba(64,158,255,.12); }
.overview-card.hot { background: linear-gradient(180deg, #fffdf5, #fff); }
.overview-value {
  font-size: 30px;
  font-weight: 800;
  color: #172033;
  line-height: 1;
}
.overview-label {
  margin-top: 10px;
  font-size: 13px;
  color: #6b7280;
  font-weight: 600;
}
.filter-bar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
  padding: 14px 16px;
  background: rgba(255,255,255,.78);
  backdrop-filter: blur(12px);
  border: 1px solid rgba(255,255,255,.65);
  border-radius: 14px;
  box-shadow: 0 10px 24px rgba(15,23,42,.05);
}
.filter-info {
  display: flex;
  align-items: center;
  gap: 16px;
  color: #425466;
  font-size: 14px;
}
.filter-info span {
  display: flex;
  align-items: center;
  gap: 6px;
}
.match-count {
  color: #94a3b8;
}
.filter-actions {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  justify-content: flex-end;
}
.date-window {
  color: #2563eb;
  background: rgba(37,99,235,.08);
  border: 1px solid rgba(37,99,235,.12);
  border-radius: 999px;
  padding: 4px 10px;
}

.loading-state,
.error-state,
.empty-state {
  text-align: center;
  padding: 60px 20px;
  color: #666;
}
.loading-state p,
.error-state p,
.empty-state p {
  margin-top: 16px;
  font-size: 16px;
}
.empty-hint {
  color: #999;
  font-size: 13px !important;
}

.matches-container { display: flex; flex-direction: column; gap: 24px; }
.hot-panel {
  background: linear-gradient(180deg, #fffdf5 0%, #ffffff 100%);
  border-radius: 18px;
  padding: 0;
  border: 1px solid #f3e7c3;
  box-shadow: 0 12px 28px rgba(15,23,42,.06);
  overflow: hidden;
}
.hot-panel-toggle {
  width: 100%;
  border: 0;
  background: transparent;
  cursor: pointer;
  padding: 16px 18px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  text-align: left;
}
.hot-panel-toggle:hover { background: rgba(245,158,11,.06); }
.hot-panel-actions { display:flex; align-items:center; gap:10px; color:#64748b; font-size:13px; }
.collapse-icon { transition: transform .22s ease; }
.hot-panel.is-collapsed .collapse-icon { transform: rotate(-90deg); }
.hot-panel-body { padding: 0 18px 18px; }
.hot-empty { padding: 26px 12px; text-align:center; color:#94a3b8; background:rgba(255,255,255,.55); border-radius:14px; }
.live-section {
  background: linear-gradient(180deg, #fff5f5 0%, #fff0f0 100%);
  border-radius: 18px;
  padding: 18px;
  border: 1px solid #fde2e2;
}
.section-header {
  display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px;
}
.section-title {
  font-size: 16px; font-weight: 700; color: #172033; display: flex; align-items: center; gap: 8px;
}
.live-dot {
  width: 8px; height: 8px; background: #f56c6c; border-radius: 50%; animation: pulse 1.5s infinite;
}
@keyframes pulse { 0%, 100% { opacity: 1; } 50% { opacity: 0.5; } }
.reveal { animation: fadeUp .55s ease both; }
@keyframes fadeUp { from { opacity: 0; transform: translateY(14px); } to { opacity: 1; transform: translateY(0); } }
.date-group-list { display:flex; flex-direction:column; gap:22px; }
.date-group { background: rgba(255,255,255,.68); border:1px solid rgba(226,232,240,.9); border-radius:18px; padding:16px; box-shadow:0 12px 28px rgba(15,23,42,.05); }
.date-group-header { display:flex; align-items:center; justify-content:space-between; margin-bottom:14px; padding-bottom:12px; border-bottom:1px solid #e8eef5; }
.date-group-header strong { display:block; color:#0f172a; font-size:16px; }
.date-group-header span { display:block; margin-top:4px; color:#64748b; font-size:13px; }
.matches-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(340px, 1fr)); gap: 16px; }

/* 历史交锋样式 */
.h2h-content {}
.h2h-summary {
  display: flex;
  justify-content: space-around;
  padding: 20px;
  background: linear-gradient(180deg, #f8fbff, #ffffff);
  border-radius: 14px;
  margin-bottom: 20px;
  border: 1px solid #e8eef5;
  box-shadow: 0 10px 24px rgba(15,23,42,.05);
}
.summary-item {
  text-align: center;
}
.summary-value {
  display: block;
  font-size: 32px;
  font-weight: 800;
  color: #172033;
}
.summary-label {
  font-size: 13px;
  color: #6b7280;
}
.h2h-matches {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.h2h-match {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 10px 16px;
  background: #fafafa;
  border-radius: 10px;
  border: 1px solid #eef2f7;
}
.h2h-date {
  font-size: 13px;
  color: #999;
}
.h2h-score {
  font-size: 16px;
  font-weight: 600;
  color: #333;
}
.hero-metrics {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
  min-width: 320px;
  align-items: stretch-rg;
}
.metric {
  position: relative;
  display: flex;
  flex-direction: column;
  justify-content: center;
  align-items: flex-start;
  min-height: 108px;
  padding: 18px 18px 16px;
  border-radius: 18px;
  background: rgba(255,255,255,0.12);
  border: 1px solid rgba(255,255,255,0.16);
  box-shadow: inset 0 1px 0 rgba(255,255,255,0.12);
  backdrop-filter: blur(10px);
  transition: transform .22s ease, background .22s ease, box-shadow .22s ease;
}
.metric::after {
  content: '';
  position: absolute;
  inset: auto -12px -20px auto;
  width: 78px;
  height: 78px;
  border-radius: 50%;
  background: radial-gradient(circle, rgba(255,255,255,.22), transparent 68%);
  pointer-events: none;
}
.metric:hover {
  transform: translateY(-3px);
  background: rgba(255,255,255,0.16);
  box-shadow: 0 14px 28px rgba(15,23,42,.12);
}
.metric span {
  font-size: 30px;
  line-height: 1;
  font-weight: 800;
  letter-spacing: .02em;
}
.metric label {
  margin-top: 10px;
  font-size: 13px;
  font-weight: 600;
  color: rgba(255,255,255,.78);
}
@media (max-width: 768px) {
  .hero-banner {
    grid-template-columns: 1fr;
  }
  .hero-metrics {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .overview-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .matches-grid {
    grid-template-columns: 1fr;
  }
  .header,
  .filter-bar {
    flex-direction: column;
    gap: 12px;
    height: auto;
  }
  .header-nav {
    margin: 0;
  }
}
</style>
