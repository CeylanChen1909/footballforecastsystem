import { createRouter, createWebHistory } from 'vue-router'
import { useUserStore } from '../stores/user'

const routes = [
  { path: '/', redirect: '/matches' },
  { path: '/login', name: 'Login', component: () => import('../views/user/Login.vue'), meta: { requiresAuth: false } },
  { path: '/privacy', name: 'Privacy', component: () => import('../views/user/Privacy.vue'), meta: { requiresAuth: false } },
  { path: '/about', name: 'About', component: () => import('../views/user/About.vue'), meta: { requiresAuth: false } },
  { path: '/terms', name: 'Terms', component: () => import('../views/user/Terms.vue'), meta: { requiresAuth: false } },
  // Home 已下线；保留旧地址，避免历史书签失效。
  { path: '/home', redirect: '/matches', meta: { requiresAuth: false } },
  { path: '/matches', name: 'Matches', component: () => import('../views/user/Matches.vue'), meta: { requiresAuth: false } },
  // 保留旧地址兼容书签与历史链接；工作台已收敛到比赛页。
  { path: '/workspace', redirect: '/matches', meta: { requiresAuth: false } },
  { path: '/competitions', name: 'CompetitionHub', component: () => import('../views/user/CompetitionHub.vue'), meta: { requiresAuth: false } },
  // 已下线的实验模块保留兼容重定向，避免历史书签进入孤立页面。
  { path: '/card-lab', redirect: '/matches', meta: { requiresAuth: false } },
  { path: '/card-rogue', redirect: '/matches', meta: { requiresAuth: false } },
  { path: '/news', name: 'News', redirect: { path: '/competitions' }, meta: { requiresAuth: false } },
  { path: '/videos', name: 'VideoHub', redirect: { path: '/competitions' }, meta: { requiresAuth: false } },
  { path: '/agent', name: 'Agent', component: () => import('../views/user/Agent.vue'), meta: { requiresAuth: false } },
  { path: '/prediction/:fixtureId', name: 'Prediction', component: () => import('../views/user/Prediction.vue'), meta: { requiresAuth: false } },
  { path: '/prediction/:fixtureId/detail', name: 'PredictionDetail', component: () => import('../views/user/Prediction.vue'), meta: { requiresAuth: false } },
  { path: '/profile', name: 'Profile', component: () => import('../views/user/Profile.vue'), meta: { requiresAuth: true, roles: ['USER', 'ADMIN', 'SUPER_ADMIN'] } },
  { path: '/team/:teamId/squad', name: 'TeamSquad', component: () => import('../views/user/TeamSquad.vue'), meta: { requiresAuth: false } },
  { path: '/admin', name: 'AdminDashboard', component: () => import('../views/admin/AdminDashboard.vue'), meta: { requiresAuth: true, roles: ['ADMIN', 'SUPER_ADMIN'] } },
  { path: '/:pathMatch(.*)*', name: 'NotFound', component: () => import('../views/user/NotFound.vue'), meta: { requiresAuth: false } }
]

const router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior(to, from, savedPosition) {
    if (savedPosition) return savedPosition
    if (to.hash) return { el: to.hash, behavior: 'smooth' }
    return { top: 0 }
  }
})

router.beforeEach(async (to, from, next) => {
  const userStore = useUserStore()

  if (!userStore.sessionChecked) {
    await userStore.ensureSession().catch(() => {})
  }

  const isAuthenticated = !!userStore.token
  const role = userStore.role || 'USER'

  if (to.path === '/login') {
    if (!isAuthenticated) {
      userStore.openAuthDialog(typeof to.query.redirect === 'string' ? to.query.redirect : '')
      next('/matches')
    } else {
      next(role === 'ADMIN' || role === 'SUPER_ADMIN' ? '/admin' : '/matches')
    }
    return
  }

  if (to.meta.requiresAuth && !isAuthenticated) {
    userStore.openAuthDialog(to.fullPath)
    next('/matches')
    return
  }

  if (to.meta.roles && !to.meta.roles.includes(role)) {
    next(role === 'ADMIN' || role === 'SUPER_ADMIN' ? '/admin' : '/matches')
    return
  }

  if (to.path === '/' || to.path === '') {
    next(isAuthenticated ? (role === 'ADMIN' || role === 'SUPER_ADMIN' ? '/admin' : '/matches') : '/matches')
    return
  }

  next()
})


const DEFAULT_TITLE = 'ChenFootball - 足球赛程与预测'
const TITLES = {
  '/login': '登录 - ChenFootball',
  '/matches': '比赛赛程 - ChenFootball',
  '/competitions': '赛事资料 - ChenFootball',
  '/news': '赛事资料 - ChenFootball',
  '/videos': '赛事资料 - ChenFootball',
  '/agent': 'Agent - ChenFootball',
  '/prediction': '比赛预测 - ChenFootball',
  '/profile': '个人中心 - ChenFootball',
  '/team': '球队阵容 - ChenFootball',
  '/admin': '管理后台 - ChenFootball',
  '/privacy': '隐私政策 - ChenFootball',
  '/about': '关于我们 - ChenFootball',
  '/terms': '使用条款 - ChenFootball',
  '/404': '页面未找到 - ChenFootball',
}

const SITE_ORIGIN = 'https://chenfootball.asia'
const DEFAULT_DESCRIPTION = 'ChenFootball 按日列出已同步赛程，点开看预测概率、数据覆盖与质量门槛。不提供投注建议。'
const DESCRIPTIONS = {
  '/matches': '按日查看已同步赛程，点开看预测概率、数据覆盖与质量门槛。',
  '/competitions': '查看联赛积分榜、参赛俱乐部与球队资料。',
  '/privacy': '了解 ChenFootball 如何保存、使用与删除账号及赛程相关数据。',
  '/about': '了解 ChenFootball 的产品定位、数据来源、模型边界与免责声明。',
  '/terms': '查阅 ChenFootball 使用条款、账号规则、合理使用与责任限制。',
  '/agent': '用自然语言查询赛程、球队状态与预测依据。',
  '/prediction': '查看单场比赛的统一预测结论、概率分布与数据覆盖。',
  '/team': '查看球队阵容与相关资料。',
  '/profile': '管理收藏、提醒偏好与账号安全。'
}

const upsertMeta = (attr, key, content) => {
  if (!content) return
  let el = document.head.querySelector('meta[' + attr + '="' + key + '"]')
  if (!el) {
    el = document.createElement('meta')
    el.setAttribute(attr, key)
    document.head.appendChild(el)
  }
  el.setAttribute('content', content)
}

const upsertCanonical = (href) => {
  let link = document.head.querySelector('link[rel="canonical"]')
  if (!link) {
    link = document.createElement('link')
    link.setAttribute('rel', 'canonical')
    document.head.appendChild(link)
  }
  link.setAttribute('href', href)
}

const canonicalPathFor = (path) => {
  if (path.startsWith('/prediction/')) return path.replace(/\/detail$/, '')
  if (path.startsWith('/team/')) return path
  const known = ['/matches', '/competitions', '/privacy', '/about', '/terms', '/agent', '/profile', '/admin', '/login']
  const hit = known.find((key) => path === key || path.startsWith(key + '/'))
  return hit || '/matches'
}

router.afterEach((to) => {
  const match = Object.keys(TITLES).find((key) => to.path.startsWith(key))
  document.title = to.name === 'NotFound'
    ? '页面未找到 - ChenFootball'
    : (match ? TITLES[match] : DEFAULT_TITLE)

  const descKey = Object.keys(DESCRIPTIONS).find((key) => to.path.startsWith(key))
  const description = to.name === 'NotFound'
    ? '你访问的页面不存在或链接已失效，可返回比赛赛程继续浏览。'
    : (descKey ? DESCRIPTIONS[descKey] : DEFAULT_DESCRIPTION)
  upsertMeta('name', 'description', description)
  upsertMeta('property', 'og:title', document.title)
  upsertMeta('property', 'og:description', description)
  upsertMeta('property', 'og:url', SITE_ORIGIN + canonicalPathFor(to.path))
  upsertMeta('name', 'twitter:title', document.title)
  upsertMeta('name', 'twitter:description', description)
  upsertMeta('property', 'og:image', SITE_ORIGIN + '/og.png')
  upsertMeta('property', 'og:type', 'website')
  upsertMeta('name', 'twitter:image', SITE_ORIGIN + '/og.png')
  upsertMeta('name', 'twitter:card', 'summary_large_image')

  upsertCanonical(SITE_ORIGIN + canonicalPathFor(to.path))
})


export default router
