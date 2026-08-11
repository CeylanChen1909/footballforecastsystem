import { createRouter, createWebHistory } from 'vue-router'
import { useUserStore } from '../stores/user'

const routes = [
  { path: '/', redirect: '/matches' },
  { path: '/login', name: 'Login', component: () => import('../views/Login.vue'), meta: { requiresAuth: false } },
  { path: '/matches', name: 'Matches', component: () => import('../views/Matches.vue'), meta: { requiresAuth: true, roles: ['USER', 'ADMIN'] } },
  { path: '/news', name: 'News', component: () => import('../views/News.vue'), meta: { requiresAuth: true, roles: ['USER', 'ADMIN'] } },
  { path: '/videos', name: 'VideoHub', component: () => import('../views/VideoHub.vue'), meta: { requiresAuth: true, roles: ['USER', 'ADMIN'] } },
  { path: '/prediction/:fixtureId', name: 'Prediction', component: () => import('../views/Prediction.vue'), meta: { requiresAuth: true, roles: ['USER', 'ADMIN'] } },
  { path: '/prediction/:fixtureId/detail', name: 'PredictionDetail', component: () => import('../views/Prediction.vue'), meta: { requiresAuth: true, roles: ['USER', 'ADMIN'] } },
  { path: '/profile', name: 'Profile', component: () => import('../views/Profile.vue'), meta: { requiresAuth: true, roles: ['USER', 'ADMIN'] } },
  { path: '/team/:teamId', name: 'TeamDetail', component: () => import('../views/TeamDetail.vue'), meta: { requiresAuth: true, roles: ['USER', 'ADMIN'] } },
  { path: '/admin', name: 'AdminDashboard', component: () => import('../views/AdminDashboard.vue'), meta: { requiresAuth: true, roles: ['ADMIN'] } }
]

const router = createRouter({ history: createWebHistory(), routes })

router.beforeEach((to, from, next) => {
  const userStore = useUserStore()
  const role = userStore.role || 'USER'
  if (to.meta.requiresAuth && !userStore.token) {
    next('/login')
  } else if (to.path === '/login' && userStore.token) {
    next(role === 'ADMIN' ? '/admin' : '/matches')
  } else if (to.meta.roles && !to.meta.roles.includes(role)) {
    next(role === 'ADMIN' ? '/admin' : '/matches')
  } else {
    next()
  }
})

export default router
