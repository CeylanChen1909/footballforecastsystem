/**
 * 路由表（契约 §1：20 条路由表条目，按 src/router/index.js 顺序）。
 *
 * Solid Router 2.0 的 API（与 1.x 不同，以实装的 2.0.1 与官方文档为准）：
 *  - 没有 <Router>/<Route>/<A>/<Navigate>/useRoutes：路由由模块作用域的
 *    `createRouter({ routes })` 一次创建，导出 `Router` 组件；
 *    链接用原生 `<a href>`，运行时由 router 认领点击做客户端导航；
 *    `useLocation` / `useNavigate` / `useParams` 仍在。
 *  - RouteDefinition 没有 `redirect` 字段：重定向用一个 onSettled 即 navigate
 *    的小组件实现（见 redirectTo）；`/login` 的组件是空渲染——守卫在
 *    AppShell 里先拦截（弹窗 + 回 /matches），与旧版 beforeEach 一致，
 *    Login.vue 本身是死代码，不搬（契约 §9.1）。
 *  - 20 条：13 条组件路由 + 1 条根重定向 + 6 条兼容重定向
 *    （/home、/workspace、/card-lab、/card-rogue、/news、/videos；
 *    /news 与 /videos 有 name（News/VideoHub）但无组件，是重定向）。
 *  - `/prediction/:fixtureId/detail` 是独立条目，与主入口共用 Prediction 组件；
 *    仅当路径以 /detail 结尾且带 resultLabel 查询参数时才从 query 水合快照
 *    （页内逻辑，样板页组实现）；canonical 去掉 /detail 后缀（见下）。
 *  - `*` 为 404 兜底，不是重定向。
 */
import { lazy, onSettled } from 'solid-js'
import { createRouter, defineRoutes, useNavigate } from '@solidjs/router'

const Matches = lazy(() => import('./views/user/Matches'))
const CompetitionHub = lazy(() => import('./views/user/CompetitionHub'))
const Agent = lazy(() => import('./views/user/Agent'))
const Prediction = lazy(() => import('./views/user/Prediction'))
const Profile = lazy(() => import('./views/user/Profile'))
const TeamSquad = lazy(() => import('./views/user/TeamSquad'))
const About = lazy(() => import('./views/user/About'))
const Privacy = lazy(() => import('./views/user/Privacy'))
const Terms = lazy(() => import('./views/user/Terms'))
const NotFound = lazy(() => import('./views/user/NotFound'))
const AdminDashboard = lazy(() => import('./views/admin/AdminDashboard'))

function redirectTo(to) {
  return function Redirect() {
    const navigate = useNavigate()
    onSettled(() => navigate(to, { replace: true }))
    return null
  }
}

/** /login 的组件：永不渲染内容（守卫先拦截，见 App.jsx）。 */
function LoginDeadRoute() {
  return null
}

export const ROUTE_META = [
  { path: '/', name: null, requiresAuth: false },
  { path: '/login', name: 'Login', requiresAuth: false },
  { path: '/privacy', name: 'Privacy', requiresAuth: false },
  { path: '/about', name: 'About', requiresAuth: false },
  { path: '/terms', name: 'Terms', requiresAuth: false },
  { path: '/home', name: null, requiresAuth: false },
  { path: '/matches', name: 'Matches', requiresAuth: false },
  { path: '/workspace', name: null, requiresAuth: false },
  { path: '/competitions', name: 'CompetitionHub', requiresAuth: false },
  { path: '/card-lab', name: null, requiresAuth: false },
  { path: '/card-rogue', name: null, requiresAuth: false },
  { path: '/news', name: 'News', requiresAuth: false },
  { path: '/videos', name: 'VideoHub', requiresAuth: false },
  { path: '/agent', name: 'Agent', requiresAuth: false },
  { path: '/prediction/:fixtureId', name: 'Prediction', requiresAuth: false },
  { path: '/prediction/:fixtureId/detail', name: 'PredictionDetail', requiresAuth: false },
  { path: '/profile', name: 'Profile', requiresAuth: true, roles: ['USER', 'ADMIN', 'SUPER_ADMIN'] },
  { path: '/team/:teamId/squad', name: 'TeamSquad', requiresAuth: false },
  { path: '/admin', name: 'AdminDashboard', requiresAuth: true, roles: ['ADMIN', 'SUPER_ADMIN'] },
]

export const routeDefs = defineRoutes([
  { path: '/', component: redirectTo('/matches') },
  { path: '/login', component: LoginDeadRoute },
  { path: '/privacy', component: Privacy },
  { path: '/about', component: About },
  { path: '/terms', component: Terms },
  { path: '/home', component: redirectTo('/matches') },
  { path: '/matches', component: Matches },
  { path: '/workspace', component: redirectTo('/matches') },
  { path: '/competitions', component: CompetitionHub },
  { path: '/card-lab', component: redirectTo('/matches') },
  { path: '/card-rogue', component: redirectTo('/matches') },
  { path: '/news', component: redirectTo('/competitions') },
  { path: '/videos', component: redirectTo('/competitions') },
  { path: '/agent', component: Agent },
  { path: '/prediction/:fixtureId', component: Prediction },
  { path: '/prediction/:fixtureId/detail', component: Prediction },
  { path: '/profile', component: Profile },
  { path: '/team/:teamId/squad', component: TeamSquad },
  { path: '/admin', component: AdminDashboard },
  { path: '*', component: NotFound },
])

export const Router = createRouter({ routes: routeDefs })
export const { paths } = Router

/** 按路径找 ROUTE_META（:param 段通配），找不到返回 null（即 404）。 */
export function matchRouteMeta(path) {
  for (const route of ROUTE_META) {
    const pattern = `^${route.path.replace(/:[^/]+/g, '[^/]+')}$`
    if (new RegExp(pattern).test(path)) return route
  }
  return null
}

/** 路径是否命中已知路由（不含 '*' 兜底），用于 404 标题判定。 */
export function isKnownRoute(path) {
  return matchRouteMeta(path) !== null
}

// --- 标题 / 描述 / canonical（旧版 router afterEach 的同款映射，契约 §1.3） ---
// 注意：/matches 的 title 值实际是一句描述文案，疑似错放——按契约照原样搬，不“修正”。

const DEFAULT_TITLE = 'ChenFootball - 足球赛程与预测'
const TITLES = {
  '/login': '登录 - ChenFootball',
  '/matches': '按日查看已同步赛程，点开看预测概率、数据覆盖与质量门槛。',
  '/competitions': '赛事资料 - ChenFootball',
  '/news': '赛事资料 - ChenFootball',
  '/videos': '赛事资料 - ChenFootball',
  '/agent': '助手 - ChenFootball',
  '/prediction': '比赛预测 - ChenFootball',
  '/profile': '个人中心 - ChenFootball',
  '/team': '球队阵容 - ChenFootball',
  '/admin': '管理后台 - ChenFootball',
  '/privacy': '隐私政策 - ChenFootball',
  '/about': '关于我们 - ChenFootball',
  '/terms': '使用条款 - ChenFootball',
  '/404': '页面未找到 - ChenFootball',
}
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
  '/profile': '管理收藏、提醒偏好与账号安全。',
}
const SITE_ORIGIN = 'https://chenfootball.asia'

function upsertMeta(attr, key, content) {
  if (!content) return
  let el = document.head.querySelector(`meta[${attr}="${key}"]`)
  if (!el) {
    el = document.createElement('meta')
    el.setAttribute(attr, key)
    document.head.appendChild(el)
  }
  el.setAttribute('content', content)
}

function upsertCanonical(href) {
  let link = document.head.querySelector('link[rel="canonical"]')
  if (!link) {
    link = document.createElement('link')
    link.setAttribute('rel', 'canonical')
    document.head.appendChild(link)
  }
  link.setAttribute('href', href)
}

function canonicalPathFor(path) {
  if (path.startsWith('/prediction/')) return path.replace(/\/detail$/, '')
  if (path.startsWith('/team/')) return path
  const known = ['/matches', '/competitions', '/privacy', '/about', '/terms', '/agent', '/profile', '/admin', '/login']
  const hit = known.find((key) => path === key || path.startsWith(key + '/'))
  return hit || '/matches'
}

export function applyRouteMeta(path, isNotFound) {
  const titleMatch = Object.keys(TITLES).find((key) => path.startsWith(key))
  document.title = isNotFound ? '页面未找到 - ChenFootball' : titleMatch ? TITLES[titleMatch] : DEFAULT_TITLE
  const descKey = Object.keys(DESCRIPTIONS).find((key) => path.startsWith(key))
  const description = isNotFound
    ? '你访问的页面不存在或链接已失效，可返回比赛赛程继续浏览。'
    : descKey
      ? DESCRIPTIONS[descKey]
      : DEFAULT_DESCRIPTION
  upsertMeta('name', 'description', description)
  upsertMeta('property', 'og:title', document.title)
  upsertMeta('property', 'og:description', description)
  upsertMeta('property', 'og:url', SITE_ORIGIN + canonicalPathFor(path))
  upsertMeta('name', 'twitter:title', document.title)
  upsertMeta('name', 'twitter:description', description)
  upsertMeta('property', 'og:image', SITE_ORIGIN + '/og.png')
  upsertMeta('property', 'og:type', 'website')
  upsertMeta('name', 'twitter:image', SITE_ORIGIN + '/og.png')
  upsertMeta('name', 'twitter:card', 'summary_large_image')
  upsertCanonical(SITE_ORIGIN + canonicalPathFor(path))
}
