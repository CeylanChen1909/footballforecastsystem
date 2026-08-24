import { createRouter, createWebHistory } from 'vue-router'
import { useUserStore } from '../stores/user'

const routes = [
  { path: '/', redirect: '/matches' },
  { path: '/login', name: 'Login', component: () => import('../views/user/Login.vue'), meta: { requiresAuth: false } },
  { path: '/privacy', name: 'Privacy', component: () => import('../views/user/Privacy.vue'), meta: { requiresAuth: false } },
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
  { path: '/agent', name: 'Agent', component: () => import('../views/user/Agent.vue'), meta: { requiresAuth: true, roles: ['USER', 'ADMIN', 'SUPER_ADMIN'] } },
  { path: '/prediction/:fixtureId', name: 'Prediction', component: () => import('../views/user/Prediction.vue'), meta: { requiresAuth: false } },
  { path: '/prediction/:fixtureId/detail', name: 'PredictionDetail', component: () => import('../views/user/Prediction.vue'), meta: { requiresAuth: false } },
  { path: '/profile', name: 'Profile', component: () => import('../views/user/Profile.vue'), meta: { requiresAuth: true, roles: ['USER', 'ADMIN', 'SUPER_ADMIN'] } },
  { path: '/team/:teamId/squad', name: 'TeamSquad', component: () => import('../views/user/TeamSquad.vue'), meta: { requiresAuth: false } },
  { path: '/admin', name: 'AdminDashboard', component: () => import('../views/admin/AdminDashboard.vue'), meta: { requiresAuth: true, roles: ['ADMIN', 'SUPER_ADMIN'] } },
  { path: '/:pathMatch(.*)*', redirect: '/matches' }
]

const router = createRouter({ history: createWebHistory(), routes })

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


const TITLES = {
  '/login': '登录 - ChenFootball',
  '/matches': '比赛 - ChenFootball',
  '/competitions': '赛事资料 - ChenFootball',
  '/news': '赛事资料 - ChenFootball',
  '/videos': '赛事资料 - ChenFootball',
  '/agent': 'Agent - ChenFootball',
  '/prediction': '预测 - ChenFootball',
  '/profile': '个人中心 - ChenFootball',
  '/team': '球队 - ChenFootball',
  '/admin': '管理后台 - ChenFootball',
}

router.afterEach((to) => {
  const match = Object.keys(TITLES).find((key) => to.path.startsWith(key))
  document.title = match ? TITLES[match] : 'ChenFootball'
})

export default router
