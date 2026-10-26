import { createRouter, createWebHistory } from 'vue-router'
import { watch } from 'vue'
import { useUserStore } from '../stores/user'
import { locale, t } from '../i18n'

const routes = [
  { path: '/', redirect: '/matches' },
  { path: '/login', name: 'Login', component: () => import('../views/user/Login.vue'), meta: { requiresAuth: false, titleKey: 'meta.login' } },
  { path: '/privacy', name: 'Privacy', component: () => import('../views/user/Privacy.vue'), meta: { requiresAuth: false, titleKey: 'meta.privacy', descriptionKey: 'meta.description.privacy' } },
  { path: '/about', name: 'About', component: () => import('../views/user/About.vue'), meta: { requiresAuth: false, titleKey: 'meta.about', descriptionKey: 'meta.description.about' } },
  { path: '/terms', name: 'Terms', component: () => import('../views/user/Terms.vue'), meta: { requiresAuth: false, titleKey: 'meta.terms', descriptionKey: 'meta.description.terms' } },
  // Home 已下线；保留旧地址，避免历史书签失效。
  { path: '/home', redirect: '/matches', meta: { requiresAuth: false } },
  { path: '/matches', name: 'Matches', component: () => import('../views/user/Matches.vue'), meta: { requiresAuth: false, titleKey: 'meta.matches', descriptionKey: 'meta.description.matches' } },
  // 保留旧地址兼容书签与历史链接；工作台已收敛到比赛页。
  { path: '/workspace', redirect: '/matches', meta: { requiresAuth: false } },
  { path: '/competitions', name: 'CompetitionHub', component: () => import('../views/user/CompetitionHub.vue'), meta: { requiresAuth: false, titleKey: 'meta.competitions', descriptionKey: 'meta.description.competitions' } },
  // 已下线的实验模块保留兼容重定向，避免历史书签进入孤立页面。
  { path: '/card-lab', redirect: '/matches', meta: { requiresAuth: false } },
  { path: '/card-rogue', redirect: '/matches', meta: { requiresAuth: false } },
  { path: '/news', name: 'News', redirect: { path: '/competitions' }, meta: { requiresAuth: false } },
  { path: '/videos', name: 'VideoHub', redirect: { path: '/competitions' }, meta: { requiresAuth: false } },
  { path: '/agent', name: 'Agent', component: () => import('../views/user/Agent.vue'), meta: { requiresAuth: false, titleKey: 'meta.agent', descriptionKey: 'meta.description.agent' } },
  { path: '/prediction/:fixtureId', name: 'Prediction', component: () => import('../views/user/Prediction.vue'), meta: { requiresAuth: false, titleKey: 'meta.prediction', descriptionKey: 'meta.description.prediction' } },
  { path: '/prediction/:fixtureId/detail', name: 'PredictionDetail', component: () => import('../views/user/Prediction.vue'), meta: { requiresAuth: false, titleKey: 'meta.prediction', descriptionKey: 'meta.description.prediction' } },
  { path: '/profile', name: 'Profile', component: () => import('../views/user/Profile.vue'), meta: { requiresAuth: true, roles: ['USER', 'ADMIN', 'SUPER_ADMIN'], titleKey: 'meta.profile', descriptionKey: 'meta.description.profile' } },
  { path: '/team/:teamId/squad', name: 'TeamSquad', component: () => import('../views/user/TeamSquad.vue'), meta: { requiresAuth: false, titleKey: 'meta.team', descriptionKey: 'meta.description.team' } },
  { path: '/admin', name: 'AdminDashboard', component: () => import('../views/admin/AdminDashboard.vue'), meta: { requiresAuth: true, roles: ['ADMIN', 'SUPER_ADMIN'], titleKey: 'meta.admin' } },
  { path: '/:pathMatch(.*)*', name: 'NotFound', component: () => import('../views/user/NotFound.vue'), meta: { requiresAuth: false, titleKey: 'meta.notFound' } }
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


const SITE_ORIGIN = 'https://chenfootball.asia'

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

const syncDocumentMeta = (to) => {
  if (!to) return
  const title = t(to.meta?.titleKey || 'meta.default')
  const description = t(to.meta?.descriptionKey || 'meta.description.default')

  document.title = title
  upsertMeta('name', 'description', description)
  upsertMeta('property', 'og:title', title)
  upsertMeta('property', 'og:description', description)
  upsertMeta('property', 'og:locale', locale.value === 'zh' ? 'zh_CN' : 'en_US')
  upsertMeta('property', 'og:url', SITE_ORIGIN + canonicalPathFor(to.path))
  upsertMeta('name', 'twitter:title', title)
  upsertMeta('name', 'twitter:description', description)
  upsertMeta('property', 'og:image', SITE_ORIGIN + '/og.png')
  upsertMeta('property', 'og:type', 'website')
  upsertMeta('name', 'twitter:image', SITE_ORIGIN + '/og.png')
  upsertMeta('name', 'twitter:card', 'summary_large_image')

  upsertCanonical(SITE_ORIGIN + canonicalPathFor(to.path))
}

router.afterEach(syncDocumentMeta)
watch(locale, () => syncDocumentMeta(router.currentRoute.value))

export default router
