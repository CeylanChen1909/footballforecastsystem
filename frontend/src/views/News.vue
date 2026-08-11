<template>
  <div class="page-shell">
    <el-container class="app-container">
      <el-header class="app-header">
        <div class="brand">
          <div class="brand-icon">
            <el-icon :size="24"><Bell /></el-icon>
          </div>
          <div>
            <div class="brand-title">ChenFootball</div>
            <div class="brand-subtitle">赛事动态 · 战报快讯 · 深度解读</div>
          </div>
        </div>

        <el-menu mode="horizontal" :default-active="activeMenu" class="top-menu unified-menu" @select="handleMenuSelect">
          <el-menu-item index="/matches" class="menu-item"><el-icon><Calendar /></el-icon><span>比赛</span></el-menu-item>
          <el-menu-item index="/news" class="menu-item"><el-icon><Notebook /></el-icon><span>资讯</span></el-menu-item>
          <el-menu-item index="/videos" class="menu-item"><el-icon><VideoCamera /></el-icon><span>视频</span></el-menu-item>
          <el-menu-item index="/profile" class="menu-item"><el-icon><User /></el-icon><span>我的</span></el-menu-item>
        </el-menu>

        <div class="header-actions">
          <el-button :icon="Refresh" circle :loading="loading" @click="loadAll" />
          <el-dropdown @command="handleCommand">
            <el-avatar :size="36" style="cursor:pointer;background:#409EFF">
              {{ userStore.username?.[0]?.toUpperCase() }}
            </el-avatar>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="profile">个人中心</el-dropdown-item>
                <el-dropdown-item command="history">预测历史</el-dropdown-item>
                <el-dropdown-item divided command="logout">退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </el-header>

      <el-main class="app-main">
        <section class="page-hero reveal">
          <div class="hero-left">
            <el-tag type="danger" effect="dark">REAL-TIME</el-tag>
            <h1>专业足球资讯与数据系统</h1>
            <p>整合最新战报、热点新闻、赛程提醒与专题分析，帮助你快速掌握比赛走势。</p>
            <div class="hero-actions">
              <el-button type="primary" @click="scrollToFeed">浏览最新资讯</el-button>
              <el-button plain @click="reloadLatest">刷新数据</el-button>
            </div>
          </div>
          <div class="hero-right">
            <div class="hero-stat">
              <span class="stat-value">{{ stats.total }}</span>
              <span class="stat-label">条资讯</span>
            </div>
            <div class="hero-stat">
              <span class="stat-value">{{ stats.hot }}</span>
              <span class="stat-label">热门专题</span>
            </div>
            <div class="hero-stat">
              <span class="stat-value">{{ stats.todayMatches }}</span>
              <span class="stat-label">近期比赛</span>
            </div>
          </div>
        </section>

        <section class="toolbar-card reveal">
          <div class="toolbar-left">
            <el-input
              v-model="searchKeyword"
              clearable
              placeholder="搜索新闻标题、球队或关键词"
              class="search-input"
              @keyup.enter="applyFilters"
            >
              <template #prefix>
                <el-icon><Search /></el-icon>
              </template>
            </el-input>
            <el-select v-model="sortBy" class="sort-select" @change="applyFilters">
              <el-option label="最新优先" value="latest" />
              <el-option label="最热优先" value="hot" />
              <el-option label="评论优先" value="comments" />
            </el-select>
          </div>
          <div class="toolbar-right">
            <el-segmented v-model="activeCategory" :options="categoryOptions" @change="applyFilters" />
          </div>
        </section>

        <section class="content-grid reveal">
          <div class="main-column">
            <el-card class="feature-card" shadow="hover">
              <template #header>
                <div class="card-header">
                  <span>焦点专题</span>
                  <el-tag type="warning" effect="dark">FEATURED</el-tag>
                </div>
              </template>
              <div v-if="loading" class="skeleton-wrap">
                <el-skeleton :rows="4" animated />
              </div>
              <div v-else-if="featuredArticle" class="featured-article" @click="openArticle(featuredArticle)">
                <div class="featured-cover">
                  <div class="featured-cover-text">
                    <span class="feature-chip">{{ featuredArticle.category }}</span>
                    <h2>{{ featuredArticle.title }}</h2>
                    <p>{{ featuredArticle.excerpt }}</p>
                  </div>
                </div>
                <div class="featured-meta">
                  <div class="meta-group">
                    <span>{{ featuredArticle.dateLabel }}</span>
                    <span>·</span>
                    <span>{{ featuredArticle.author }}</span>
                  </div>
                  <div class="meta-group">
                    <span>{{ featuredArticle.views }} 阅读</span>
                    <span>{{ featuredArticle.comments }} 评论</span>
                  </div>
                </div>
              </div>
              <el-empty v-else description="暂无焦点资讯" />
            </el-card>

            <el-card id="feed-anchor" class="feed-card" shadow="hover">
              <template #header>
                <div class="card-header">
                  <span>最新资讯</span>
                  <div class="card-tools">
                    <el-select v-model="pageSize" class="page-size" @change="applyFilters">
                      <el-option label="6 条/页" :value="6" />
                      <el-option label="9 条/页" :value="9" />
                      <el-option label="12 条/页" :value="12" />
                    </el-select>
                  </div>
                </div>
              </template>
              <div v-if="loading" class="list-loading">
                <el-skeleton v-for="n in 3" :key="n" :rows="3" animated class="mb16" />
              </div>
              <template v-else>
                <div class="article-list">
                  <article v-for="item in pagedArticles" :key="item.id" class="article-item" @click="openArticle(item)">
                    <div class="article-thumb"><span>{{ item.category }}</span></div>
                    <div class="article-body">
                      <div class="article-topline">
                        <el-tag size="small" :type="item.isHot ? 'danger' : 'info'">{{ item.isHot ? 'HOT' : 'NEWS' }}</el-tag>
                        <span class="article-date">{{ item.dateLabel }}</span>
                      </div>
                      <h3 class="article-title">{{ item.title }}</h3>
                      <p class="article-excerpt">{{ item.excerpt }}</p>
                      <div class="article-meta">
                        <span>{{ item.author }}</span>
                        <span>{{ item.views }} 阅读</span>
                        <span>{{ item.comments }} 评论</span>
                      </div>
                    </div>
                  </article>
                </div>
                <div class="pager-wrap" v-if="filteredArticles.length > pageSize">
                  <el-pagination
                    layout="prev, pager, next, total"
                    background
                    :total="filteredArticles.length"
                    :page-size="pageSize"
                    v-model:current-page="currentPage"
                  />
                </div>
              </template>
            </el-card>
          </div>

          <aside class="side-column">
            <el-card class="side-card" shadow="hover">
              <template #header><div class="card-header"><span>热门关键词</span></div></template>
              <div class="tag-cloud">
                <el-tag v-for="tag in hotTags" :key="tag" class="tag-item" effect="plain" @click="quickSearch(tag)">{{ tag }}</el-tag>
              </div>
            </el-card>

            <el-card class="side-card" shadow="hover">
              <template #header><div class="card-header"><span>热度排行</span><el-tag size="small" type="danger">TOP</el-tag></div></template>
              <div class="trend-list">
                <div v-for="item in hotRanking" :key="item.id" class="trend-item" @click="openArticle(item)">
                  <div class="trend-rank">{{ item.rank }}</div>
                  <div class="trend-content">
                    <div class="trend-title">{{ item.title }}</div>
                    <div class="trend-sub">{{ item.category }} · {{ item.views }} 阅读</div>
                  </div>
                </div>
              </div>
            </el-card>

            <el-card class="side-card" shadow="hover">
              <template #header>
                <div class="card-header">
                  <span>今日赛程看板</span>
                  <el-button text type="primary" @click="goMatches">查看比赛</el-button>
                </div>
              </template>
              <div v-if="matchesLoading" class="skeleton-wrap"><el-skeleton :rows="4" animated /></div>
              <div v-else class="match-cards">
                <div v-for="m in hotMatches" :key="m.externalMatchId || m.id" class="match-card">
                  <div class="match-league">{{ m.leagueName || m.league?.name || '联赛' }}</div>
                  <div class="match-teams">
                    <span>{{ m.homeTeamName || m.teams?.home?.name || '主队' }}</span>
                    <strong>VS</strong>
                    <span>{{ m.awayTeamName || m.teams?.away?.name || '客队' }}</span>
                  </div>
                  <div class="match-meta">
                    <span>{{ formatMatchTime(m.matchTime || m.fixture?.date) }}</span>
                    <el-tag size="small" :type="statusTagType(m.status || m.fixture?.status?.short)">{{ statusText(m.status || m.fixture?.status?.short) }}</el-tag>
                  </div>
                </div>
                <el-empty v-if="!hotMatches.length" description="暂无赛程数据" />
              </div>
            </el-card>

            <el-card class="side-card" shadow="hover">
              <template #header><div class="card-header"><span>编辑推荐</span></div></template>
              <div class="recommend-list">
                <div v-for="item in topRecommendations" :key="item.id" class="recommend-item" @click="openArticle(item)">
                  <div class="recommend-title">{{ item.title }}</div>
                  <div class="recommend-desc">{{ item.excerpt }}</div>
                </div>
              </div>
            </el-card>
          </aside>
        </section>
      </el-main>
    </el-container>

    <el-dialog v-model="detailVisible" :title="currentArticle?.title || '资讯详情'" width="920px" class="article-dialog">
      <div v-if="currentArticle" class="article-detail">
        <div class="detail-hero">
          <div class="detail-chip">{{ currentArticle.category }}</div>
          <h2>{{ currentArticle.title }}</h2>
          <div class="detail-meta">
            <span>{{ currentArticle.author }}</span>
            <span>{{ currentArticle.dateLabel }}</span>
            <span>{{ currentArticle.views }} 阅读</span>
            <span>{{ currentArticle.comments }} 评论</span>
          </div>
        </div>

        <div class="detail-body">
          <p v-for="(para, idx) in currentArticle.contentBlocks" :key="idx">{{ para }}</p>
        </div>

        <div class="detail-footer">
          <el-tag v-for="tag in currentArticle.tags" :key="tag" effect="plain">{{ tag }}</el-tag>
        </div>

        <el-divider />

        <div class="detail-actions">
          <el-button :type="currentArticle.liked ? 'danger' : 'primary'" plain @click="toggleLike(currentArticle)">{{ currentArticle.liked ? '已点赞' : '点赞' }}</el-button>
          <el-button :type="currentArticle.favorited ? 'warning' : 'success'" plain @click="toggleFavorite(currentArticle)">{{ currentArticle.favorited ? '已收藏' : '收藏' }}</el-button>
        </div>

        <el-divider />

        <div class="comment-block">
          <div class="block-title">评论区</div>
          <div v-if="replyingTo" class="reply-banner">
            <span>回复 @{{ replyingTo.username || `用户${replyingTo.userId}` }}</span>
            <el-button text type="danger" @click="cancelReply">取消回复</el-button>
          </div>
          <el-input
            v-model="commentText"
            type="textarea"
            :rows="4"
            :placeholder="replyingTo ? `回复 @${replyingTo.username || ('用户' + replyingTo.userId)}` : '写下你的评论...'"
          />
          <div class="comment-actions">
            <el-button type="primary" :loading="commentSubmitting" @click="submitComment">
              {{ replyingTo ? '提交回复' : '发表评论' }}
            </el-button>
            <span class="hint">登录用户可发表评论与回复</span>
          </div>

          <div v-if="commentsLoading" class="comment-loading">
            <el-skeleton :rows="3" animated />
          </div>
          <div v-else class="comment-list">
            <div v-for="c in topLevelComments" :key="c.id" class="comment-item">
              <div class="comment-top">
                <strong>{{ c.username || `用户${c.userId}` }}</strong>
                <span>{{ formatCommentTime(c.createdAt) }}</span>
              </div>
              <div class="comment-content">{{ c.content }}</div>
              <div class="comment-ops">
                <el-button text type="primary" size="small" @click="startReply(c)">回复</el-button>
                <el-button text type="success" size="small" @click="toggleCommentLike(c)">{{ c.liked ? '取消点赞' : '点赞' }}</el-button>
              </div>
              <div v-if="repliesFor(c.id).length" class="reply-list">
                <div v-for="r in repliesFor(c.id)" :key="r.id" class="reply-item">
                  <div class="comment-top">
                    <strong>{{ r.username || `用户${r.userId}` }}</strong>
                    <span>{{ formatCommentTime(r.createdAt) }}</span>
                  </div>
                  <div class="comment-content">回复 @{{ c.username || `用户${c.userId}` }}：{{ r.content }}</div>
                  <div class="comment-ops">
                    <el-button text type="primary" size="small" @click="startReply(r, c)">回复</el-button>
                    <el-button text type="success" size="small" @click="toggleCommentLike(r)">{{ r.liked ? '取消点赞' : '点赞' }}</el-button>
                  </div>
                </div>
              </div>
            </div>
            <el-empty v-if="!topLevelComments.length" description="暂无评论，快来抢沙发" />
          </div>
        </div>
      </div>
      <template #footer>
        <el-button @click="detailVisible = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'

defineOptions({ name: 'NewsPage' })
import { useUserStore } from '../stores/user'
import { crawlerApi, newsApi } from '../api'
import { ElMessage } from 'element-plus'
import {Notebook} from "@element-plus/icons-vue";

const router = useRouter()
const userStore = useUserStore()

const activeMenu = ref('/news')
const loading = ref(false)
const matchesLoading = ref(false)
const commentsLoading = ref(false)
const commentSubmitting = ref(false)
const detailVisible = ref(false)
const currentArticle = ref(null)
const searchKeyword = ref('')
const activeCategory = ref('全部')
const sortBy = ref('latest')
const currentPage = ref(1)
const pageSize = ref(6)
const articles = ref([])
const hotMatches = ref([])
const comments = ref([])
const spotlights = ref([])
const tags = ref([])
const recommendations = ref([])
const stats = ref({ total: 0, hot: 0, todayMatches: 0 })
const commentText = ref('')
const replyingTo = ref(null)

const categoryOptions = computed(() => ['全部', ...new Set([
  ...articles.value.map(a => a.category).filter(Boolean),
  ...spotlights.value.map(s => s.position).filter(Boolean)
])])

const hotTags = computed(() => tags.value.length ? tags.value.map(t => t.name || t) : ['英超', '西甲', '欧冠', '转会', '战术', '伤停', '预测', '赔率', '爆冷'])

const normalizeArticle = (item, index = 0) => {
  const views = Number(item.views ?? item.viewCount ?? item.likeCount ?? 0)
  const commentsCount = Number(item.comments ?? item.commentCount ?? 0)
  return {
    id: item.id ?? item.newsId ?? index + 1,
    title: item.title ?? item.name ?? item.headline ?? `资讯 ${index + 1}`,
    category: item.category ?? item.leagueName ?? item.league ?? '资讯',
    author: item.author ?? '系统',
    dateLabel: item.publishTime ?? item.date ?? item.createdAt ?? new Date().toLocaleDateString('zh-CN'),
    views,
    comments: commentsCount,
    isHot: Boolean((item.isHot ?? item.hot) || item.isFeatured || item.isTop || index < 2),
    excerpt: item.summary ?? item.content ?? item.excerpt ?? '暂无摘要，点击查看详情。',
    contentBlocks: Array.isArray(item.contentBlocks)
      ? item.contentBlocks
      : String(item.content ?? item.summary ?? item.excerpt ?? '暂无正文内容。').split(/[。！？\n]/).map(s => s.trim()).filter(Boolean),
    tags: Array.isArray(item.tags) && item.tags.length ? item.tags : [item.category ?? item.leagueName ?? '足球'],
    liked: Boolean(item.liked),
    favorited: Boolean(item.favorited)
  }
}

const applyFeedResponse = (payload) => {
  const items = Array.isArray(payload) ? payload : (Array.isArray(payload?.items) ? payload.items : [])
  articles.value = items.map(normalizeArticle)
  stats.value.total = payload?.total ?? articles.value.length
  stats.value.hot = articles.value.filter(a => a.isHot).length
}

const loadAll = async () => {
  loading.value = true
  matchesLoading.value = true
  try {
    const [feedRes, spotRes, tagsRes, hotRes] = await Promise.allSettled([
      newsApi.getFeed(1, 12, null, null, sortBy.value),
      newsApi.getSpotlights(),
      newsApi.getTags(30),
      crawlerApi.getHotMatches(5)
    ])

    if (feedRes.status === 'fulfilled') applyFeedResponse(feedRes.value)
    if (!articles.value.length) articles.value = []

    const spot = spotRes.status === 'fulfilled' ? spotRes.value : []
    spotlights.value = Array.isArray(spot) ? spot : (Array.isArray(spot?.data) ? spot.data : [])

    const tagPayload = tagsRes.status === 'fulfilled' ? tagsRes.value : []
    tags.value = Array.isArray(tagPayload) ? tagPayload : (Array.isArray(tagPayload?.data) ? tagPayload.data : [])

    const hotMatchList = hotRes.status === 'fulfilled' ? hotRes.value : []
    hotMatches.value = Array.isArray(hotMatchList) ? hotMatchList : (Array.isArray(hotMatchList?.response) ? hotMatchList.response : [])

    stats.value.todayMatches = hotMatches.value.length

    if (!articles.value.length) {
      await loadFeed()
    }
  } catch {
    ElMessage.warning('资讯加载失败')
  } finally {
    loading.value = false
    matchesLoading.value = false
  }
}

const loadFeed = async () => {
  const res = await newsApi.getFeed(currentPage.value, pageSize.value, activeCategory.value, searchKeyword.value || null, sortBy.value)
  applyFeedResponse(res)
}

const filteredArticles = computed(() => articles.value)
const featuredArticle = computed(() => filteredArticles.value.find(item => item.isHot) || filteredArticles.value[0] || null)
const hotRanking = computed(() => [...articles.value].sort((a, b) => b.views - a.views).slice(0, 5).map((item, idx) => ({ ...item, rank: idx + 1 })))
const topRecommendations = computed(() => recommendations.value.length ? recommendations.value : articles.value.slice(0, 3))
const pagedArticles = computed(() => filteredArticles.value.slice((currentPage.value - 1) * pageSize.value, currentPage.value * pageSize.value))
const topLevelComments = computed(() => comments.value.filter(c => !c.parentId))
const repliesFor = (parentId) => comments.value.filter(c => String(c.parentId || '') === String(parentId))

const applyFilters = async () => {
  currentPage.value = 1
  loading.value = true
  try {
    await loadFeed()
  } finally {
    loading.value = false
  }
}

const quickSearch = (keyword) => {
  searchKeyword.value = keyword
  activeCategory.value = '全部'
  applyFilters()
}

const openArticle = async (article) => {
  currentArticle.value = { ...article, contentBlocks: article.contentBlocks || [] }
  detailVisible.value = true
  await loadArticleDetail(article.id)
}

const loadArticleDetail = async (id) => {
  commentsLoading.value = true
  try {
    const [detailRes, commentsRes, relatedRes] = await Promise.allSettled([
      newsApi.getDetail(id, userStore.userId),
      newsApi.getComments(id),
      newsApi.getRelated(id, 6)
    ])
    const detail = detailRes.status === 'fulfilled' ? detailRes.value : null
    const detailData = detail?.data ?? detail
    if (detailData) {
      currentArticle.value = normalizeArticle(detailData)
      currentArticle.value.liked = Boolean(detailData.liked)
      currentArticle.value.favorited = Boolean(detailData.favorited)
      currentArticle.value.likeCount = detailData.likeCount || 0
      currentArticle.value.favoriteCount = detailData.favoriteCount || 0
      currentArticle.value.commentCount = detailData.commentCount || 0
    }
    const commentPayload = commentsRes.status === 'fulfilled' ? commentsRes.value : []
    comments.value = Array.isArray(commentPayload) ? commentPayload : (Array.isArray(commentPayload?.data) ? commentPayload.data : [])
    const relatedPayload = relatedRes.status === 'fulfilled' ? relatedRes.value : []
    const relatedItems = Array.isArray(relatedPayload) ? relatedPayload : (Array.isArray(relatedPayload?.data) ? relatedPayload.data : [])
    if (relatedItems.length) recommendations.value = relatedItems.map(normalizeArticle)
  } catch {
    comments.value = []
  } finally {
    commentsLoading.value = false
  }
}

const submitComment = async () => {
  if (!currentArticle.value?.id || !commentText.value.trim()) return
  commentSubmitting.value = true
  try {
    if (replyingTo.value?.id) {
      await newsApi.replyComment(currentArticle.value.id, userStore.userId, commentText.value.trim(), replyingTo.value.id)
      ElMessage.success('回复发布成功')
    } else {
      await newsApi.addComment(currentArticle.value.id, userStore.userId, commentText.value.trim())
      ElMessage.success('评论发布成功')
    }
    commentText.value = ''
    replyingTo.value = null
    await loadArticleDetail(currentArticle.value.id)
  } catch {
    ElMessage.error('评论发布失败')
  } finally {
    commentSubmitting.value = false
  }
}

const toggleLike = async (article) => {
  try {
    const res = await newsApi.toggleLike(article.id, userStore.userId)
    const liked = Boolean(res?.liked ?? res?.data?.liked)
    article.liked = liked
    if (currentArticle.value && currentArticle.value.id === article.id) currentArticle.value.liked = liked
    await loadArticleDetail(article.id)
  } catch {
    ElMessage.error('点赞失败')
  }
}

const toggleFavorite = async (article) => {
  try {
    const res = await newsApi.toggleFavorite(article.id, userStore.userId)
    const favorited = Boolean(res?.favorited ?? res?.data?.favorited)
    article.favorited = favorited
    if (currentArticle.value && currentArticle.value.id === article.id) currentArticle.value.favorited = favorited
    await loadArticleDetail(article.id)
  } catch {
    ElMessage.error('收藏失败')
  }
}
const startReply = (comment, parent = null) => {
  replyingTo.value = comment
  commentText.value = parent ? `@${parent.username || `用户${parent.userId}`} ` : ''
}
const cancelReply = () => {
  replyingTo.value = null
  commentText.value = ''
}
const toggleCommentLike = async (comment) => {
  try {
    comment.liked = !comment.liked
    await loadArticleDetail(currentArticle.value.id)
  } catch {
    ElMessage.error('评论点赞失败')
  }
}

const reloadLatest = () => loadAll()
const scrollToFeed = () => document.getElementById('feed-anchor')?.scrollIntoView({ behavior: 'smooth', block: 'start' })
const goMatches = () => router.push('/matches')

const handleMenuSelect = (index) => {
  if (index === '/matches') router.push('/matches')
  else if (index === '/news') router.push('/news')
  else if (index === '/videos') router.push('/videos')
  else if (index === '/profile') router.push('/profile')
  else if (index === '/admin') router.push('/admin')
}

const handleCommand = (cmd) => {
  if (cmd === 'logout') { userStore.logout(); router.push('/login') }
  else if (cmd === 'profile') router.push('/profile')
  else if (cmd === 'history') router.push('/profile?tab=history')
}

const formatMatchTime = (value) => {
  if (!value) return '时间待定'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return String(value)
  return date.toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' })
}

const formatCommentTime = (value) => {
  if (!value) return ''
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return String(value)
  return date.toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' })
}

const statusText = (status) => ({ NS: '未开始', LIVE: '进行中', IN_PLAY: '进行中', HT: '中场', FINISHED: '完场', FT: '完场', SCHEDULED: '未开始', PAUSED: '暂停', POSTPONED: '延期' }[status] || '未知')
const statusTagType = (status) => (['LIVE', 'IN_PLAY', 'HT'].includes(status) ? 'danger' : ['FINISHED', 'FT'].includes(status) ? 'success' : ['PAUSED', 'POSTPONED'].includes(status) ? 'warning' : 'info')

watch([currentPage, pageSize, activeCategory, sortBy], () => { /* feed loading is explicit */ })
onMounted(() => { loadAll() })
</script>

<style scoped>
.page-shell { min-height: 100vh; background: linear-gradient(180deg, #f4f7fb 0%, #eef3f8 100%); }
.app-container { min-height: 100vh; }
.app-header { display:flex; align-items:center; justify-content:space-between; gap:24px; background:rgba(255,255,255,.92); backdrop-filter:blur(16px); box-shadow:0 10px 30px rgba(16,24,40,.08); position:sticky; top:0; z-index:10; padding:0 24px; }
.brand { display:flex; align-items:center; gap:14px; min-width:260px; }
.brand-icon { width:44px; height:44px; border-radius:14px; display:flex; align-items:center; justify-content:center; background:linear-gradient(135deg,#409eff 0%,#3c6fe8 100%); color:#fff; box-shadow:0 10px 20px rgba(64,158,255,.25); }
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
.app-main { max-width:1440px; margin:0 auto; width:100%; padding:24px; }
.page-hero { display:grid; grid-template-columns:1.7fr 1fr; gap:20px; padding:28px; border-radius:24px; color:#fff; overflow:hidden; background:linear-gradient(135deg,#0f172a 0%,#1d4ed8 52%,#0ea5e9 100%); box-shadow:0 18px 40px rgba(2,8,23,.18); }
.hero-left h1 { font-size:34px; margin:14px 0 10px; line-height:1.15; }
.hero-left p { color:rgba(255,255,255,.86); font-size:15px; line-height:1.8; max-width:720px; }
.hero-actions { display:flex; gap:12px; margin-top:18px; }
.hero-right { display:grid; grid-template-columns:1fr; gap:14px; align-content:center; }
.hero-stat { background:rgba(255,255,255,.12); border:1px solid rgba(255,255,255,.18); border-radius:18px; padding:18px; display:flex; flex-direction:column; gap:4px; align-items:flex-start; transition:transform .2s ease, background .2s ease; }
.hero-stat:hover { transform: translateY(-2px); background: rgba(255,255,255,.16); }
.stat-value { font-size:30px; font-weight:800; }
.stat-label { color:rgba(255,255,255,.85); font-size:13px; }
.toolbar-card, .feature-card, .feed-card, .side-card { border-radius:20px; border:none; box-shadow:0 10px 30px rgba(15,23,42,.07); transition: transform .2s ease, box-shadow .2s ease; }
.toolbar-card { margin-top:20px; padding:16px 18px; display:flex; justify-content:space-between; align-items:center; gap:16px; }
.toolbar-left { display:flex; align-items:center; gap:12px; flex-wrap:wrap; }
.search-input { width:360px; }
.sort-select { width:150px; }
.content-grid { display:grid; grid-template-columns:minmax(0,1fr) 360px; gap:20px; margin-top:20px; }
.main-column, .side-column { display:flex; flex-direction:column; gap:20px; }
.card-header { display:flex; align-items:center; justify-content:space-between; }
.featured-article { cursor:pointer; }
.featured-cover { min-height:240px; border-radius:18px; padding:24px; display:flex; align-items:flex-end; background:linear-gradient(180deg, rgba(15,23,42,0.1), rgba(15,23,42,.72)), url('https://images.unsplash.com/photo-1518091043644-c1d4457512c6?auto=format&fit=crop&w=1400&q=80') center/cover; }
.featured-cover-text { max-width:640px; color:#fff; }
.feature-chip, .detail-chip { display:inline-flex; padding:6px 12px; border-radius:999px; background:rgba(255,255,255,.16); backdrop-filter:blur(10px); font-size:12px; font-weight:700; letter-spacing:.2px; }
.featured-cover h2 { margin:14px 0 10px; font-size:28px; line-height:1.2; }
.featured-cover p { color:rgba(255,255,255,.88); line-height:1.8; font-size:14px; }
.featured-meta { display:flex; justify-content:space-between; gap:12px; margin-top:16px; color:#6b7280; font-size:13px; }
.meta-group { display:flex; gap:8px; flex-wrap:wrap; }
.article-list { display:flex; flex-direction:column; gap:14px; }
.article-item { display:grid; grid-template-columns:120px 1fr; gap:16px; padding:14px; border-radius:18px; background:#fff; border:1px solid #eef2f7; cursor:pointer; transition:all .2s ease; }
.article-item:hover { transform:translateY(-2px); border-color:rgba(64,158,255,.45); box-shadow:0 14px 24px rgba(15,23,42,.08); }
.article-thumb { border-radius:14px; background:linear-gradient(135deg, #dbeafe, #bfdbfe); color:#1d4ed8; min-height:120px; display:flex; align-items:flex-end; justify-content:flex-start; padding:12px; font-weight:800; transition:transform .2s ease; }
.article-item:hover .article-thumb { transform: scale(1.02); }
.article-body { display:flex; flex-direction:column; gap:8px; }
.article-topline, .article-meta { display:flex; align-items:center; gap:12px; flex-wrap:wrap; color:#6b7280; font-size:12px; }
.article-title { margin:0; font-size:17px; color:#111827; line-height:1.35; }
.article-excerpt { margin:0; color:#4b5563; line-height:1.7; }
.pager-wrap { display:flex; justify-content:center; margin-top:18px; }
.side-card .tag-cloud { display:flex; flex-wrap:wrap; gap:10px; }
.tag-item { cursor:pointer; }
.trend-list, .recommend-list, .match-cards { display:flex; flex-direction:column; gap:12px; }
.trend-item, .recommend-item, .match-card { border:1px solid #eef2f7; border-radius:16px; padding:14px; background:#fff; cursor:pointer; transition:all .2s ease; }
.trend-item:hover, .recommend-item:hover, .match-card:hover { border-color:rgba(64,158,255,.35); box-shadow:0 10px 22px rgba(15,23,42,.06); }
.trend-item { display:flex; gap:12px; align-items:flex-start; }
.trend-rank { width:28px; height:28px; border-radius:8px; background:#eff6ff; color:#2563eb; font-weight:800; display:flex; align-items:center; justify-content:center; flex:none; }
.trend-title, .recommend-title { font-weight:700; color:#111827; line-height:1.45; }
.trend-sub, .recommend-desc { color:#6b7280; font-size:12px; margin-top:4px; line-height:1.6; }
.match-league { font-weight:700; color:#1d4ed8; margin-bottom:8px; }
.match-teams { display:flex; align-items:center; justify-content:space-between; gap:10px; color:#111827; }
.match-meta { display:flex; align-items:center; justify-content:space-between; gap:10px; margin-top:10px; color:#6b7280; font-size:12px; }
.skeleton-wrap, .list-loading { padding:10px; }
.mb16 { margin-bottom:16px; }
.article-dialog :deep(.el-dialog__body) { padding-top:10px; }
.detail-hero { padding:18px 20px; border-radius:18px; color:#fff; background:linear-gradient(135deg,#0f172a 0%,#1d4ed8 70%,#0ea5e9 100%); }
.detail-hero h2 { margin:14px 0 10px; font-size:28px; line-height:1.2; }
.detail-meta { display:flex; flex-wrap:wrap; gap:14px; color:rgba(255,255,255,.82); font-size:13px; }
.detail-body { padding:10px 4px 0; }
.detail-body p { line-height:1.9; color:#374151; font-size:15px; margin:0 0 14px; }
.detail-footer { display:flex; flex-wrap:wrap; gap:8px; margin-top:10px; }
.detail-actions, .comment-actions { display:flex; align-items:center; gap:12px; margin-top: 12px; }
.comment-block { margin-top: 10px; }
.block-title { font-size: 16px; font-weight: 700; margin-bottom: 12px; }
.comment-list { display:flex; flex-direction:column; gap:12px; margin-top: 16px; }
.comment-item { border:1px solid #eef2f7; border-radius:14px; padding:12px 14px; background:#fff; }
.comment-top { display:flex; justify-content:space-between; gap: 12px; color:#6b7280; font-size:12px; margin-bottom:8px; }
.comment-content { color:#374151; line-height:1.75; }
.hint { color:#6b7280; font-size:12px; }
@media (max-width:1200px) { .content-grid { grid-template-columns:1fr; } .page-hero { grid-template-columns:1fr; } .search-input { width:100%; } }
@media (max-width:768px) { .app-header { flex-wrap:wrap; padding:12px 16px; } .top-menu { width:100%; justify-content:flex-start; } .toolbar-card { flex-direction:column; align-items:stretch; } .article-item { grid-template-columns:1fr; } .article-thumb { min-height:90px; } .hero-left h1 { font-size:26px; } }
.reveal { animation: fadeUp .55s ease both; }
@keyframes fadeUp { from { opacity: 0; transform: translateY(14px); } to { opacity: 1; transform: translateY(0); } }
</style>
