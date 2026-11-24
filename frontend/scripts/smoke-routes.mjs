#!/usr/bin/env node
/**
 * SolidJS 迁移版 smoke 测试（路由与关键字符串断言）。
 *
 * 来源：Vue 版 frontend-r11/frontend/scripts/smoke-routes.mjs（178 行）
 * 的逐项移植。Vue 版断言的是 Vue 源码文本（.vue 单文件、router/index.js
 * 的 `path: '…', redirect: '…'` 写法）；Solid 版断言 .jsx 源码文本，
 * 写法差异已按 Solid 树逐项改写（见各行注释）。只做源码文本断言，
 * 不启动 dev server、不调后端。
 *
 * 与 Vue 版的差异（均已核对 Solid 树实测）：
 *  - 路由重定向：Solid 的 RouteDefinition 没有 `redirect` 字段，改用
 *    `redirectTo('…')` 组件（router.jsx:36）；断言改为
 *    `component: redirectTo('…')` 形态。
 *  - 日期轨道箭头：Solid 用 i18n key `matches.previousDay/nextDay`
 *   （值“前一天/后一天”），Vue smoke 的“查看前一天”在 r11 源码里
 *    本就不存在（属 Vue smoke 的陈旧断言）；此处断言 key + i18n 值。
 *  - AuthDialog：关闭/重置文案走 i18n（`auth.closeDialog` /
 *    `auth.resetPassword`）；断言 key 存在 + i18n 值存在。
 *  - About 页：Vue smoke 的“产品定位”在 r11 About.vue 里本就不存在
 *    （只有“产品简介”）；此处断言“产品简介”。
 *  - `#app-main`：Solid 里主 landmark 在 App.jsx（`<main id="app-main">`），
 *    不在 Matches.jsx；断言 App.jsx。
 *  - 新增（2026-11-24 修复回归项）：authStorage 必须是 sessionStorage +
 *    遗留 localStorage 一次性迁移；mediaAsset 必须走 /api/media/image
 *    代理；teamNames 词典必须含原件球队（如布伦特福德）；推荐缓存键
 *    必须是 'match-focus:rolling' 且无 TTL。
 *
 * 用法：npm run smoke（或 node scripts/smoke-routes.mjs）
 */
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const read = relative => fs.readFileSync(path.join(root, relative), 'utf8')
const exists = relative => fs.existsSync(path.join(root, relative))

const routes = read('src/router.jsx')
const nav = read('src/components/layout/AppTopNav.jsx')
const app = read('src/App.jsx')
const admin = read('src/views/admin/AdminDashboard.jsx')
const prediction = read('src/views/user/Prediction.jsx')
const matches = read('src/views/user/Matches.jsx')
const focus = read('src/components/matches/MatchFocusRail.jsx')
const recommendations = read('src/composables/useMatchRecommendations.js')
const teamNames = read('src/utils/teamNames.js')
const matchUtils = read('src/utils/match.js')
const markdown = read('src/utils/markdown.js')
const consent = read('src/components/privacy/ConsentBanner.jsx')
const auth = read('src/components/auth/AuthDialog.jsx')
const squad = read('src/views/user/TeamSquad.jsx')
const card = read('src/components/MatchCard.jsx')
const profile = read('src/views/user/Profile.jsx')
const pageState = read('src/components/layout/PageState.jsx')
const competition = read('src/views/user/CompetitionHub.jsx')
const agent = read('src/views/user/Agent.jsx')
const privacy = read('src/views/user/Privacy.jsx')
const i18n = read('src/i18n.js')
const authStorage = read('src/utils/authStorage.js')
const mediaAsset = read('src/utils/mediaAsset.js')

// 路由表：20 条（19 条 ROUTE_META + '*' 兜底），路径一字不差。
for (const route of ['/', '/login', '/privacy', '/about', '/terms', '/home', '/matches', '/workspace', '/competitions', '/card-lab', '/card-rogue', '/news', '/videos', '/agent', '/prediction/:fixtureId', '/prediction/:fixtureId/detail', '/profile', '/team/:teamId/squad', '/admin', '*']) {
  assert(routes.includes(`path: '${route}'`), `missing route: ${route}`)
}
// 兼容重定向（Solid 用 redirectTo 组件，无 redirect 字段）。
for (const [from, to] of [['/', '/matches'], ['/home', '/matches'], ['/workspace', '/matches'], ['/card-lab', '/matches'], ['/card-rogue', '/matches']]) {
  assert(routes.includes(`{ path: '${from}', component: redirectTo('${to}') }`), `missing redirect: ${from} -> ${to}`)
}
assert(routes.includes("{ path: '/news', component: redirectTo('/competitions') }"), 'news redirect is missing')
assert(routes.includes("{ path: '/videos', component: redirectTo('/competitions') }"), 'videos redirect is missing')
assert(!routes.includes("Home.vue"), 'retired home component is still mounted')
assert(routes.includes("path: '/profile'") && routes.includes("roles: ['USER', 'ADMIN', 'SUPER_ADMIN']"), 'profile auth roles are missing')
assert(routes.includes("path: '/admin'") && routes.includes("roles: ['ADMIN', 'SUPER_ADMIN']"), 'admin auth roles are missing')

for (const component of [
  'src/views/admin/AdminConfigPanel.jsx',
  'src/views/admin/AdminCrawlerMatchEditorDialog.jsx',
  'src/views/admin/AdminUsersPanel.jsx',
  'src/views/admin/AdminLogPanel.jsx',
  'src/views/admin/MatchWorkbench.jsx',
]) assert(exists(component), `missing component: ${component}`)

assert(admin.includes('loadDashboardSummary') && admin.includes('部分模块刷新失败'), 'admin partial refresh feedback is missing')
assert(admin.includes('主爬虫监控') && admin.includes('更新主爬虫数据'), 'primary crawler controls are missing')
assert(!admin.includes('AdminNewsPanel') && !admin.includes('AdminVideoPanel') && !admin.includes('AdminContentSourcePanel'), 'retired content modules are still wired into admin')
assert(prediction.includes('crawlerApi.getMatchDetail') && prediction.includes('statusActionLabel'), 'prediction deep-link or status fallback is missing')
assert(matches.includes('dateCounts') && matches.includes('matchesHeading') && matches.includes('openFocusMatch'), 'matches context, rail counts or focus navigation is missing')
assert(!matches.includes('未来 7 天'), 'redundant date controls remain')
assert(matches.includes('preserveMeta: true') && matches.includes('toggleMatchReminders'), 'matches metadata or reminder toggle is missing')
assert(matches.includes('teamNameMode') && matches.includes('toggleTeamNameMode') && matches.includes('getTeamSearchTokens'), 'bilingual team display or fuzzy search is not wired')
// 日期轨道箭头：Solid 用 i18n key（值“前一天/后一天”）。
assert(matches.includes('matches.previousDay') && matches.includes('matches.nextDay') && matches.includes('shiftDate(-1)') && matches.includes('shiftDate(1)'), 'matches date rail arrows are missing')
assert(i18n.includes("'matches.previousDay': '前一天'") && i18n.includes("'matches.nextDay': '后一天'"), 'matches date rail i18n values are missing')
assert(focus.includes('比赛焦点') && focus.includes('推荐数据暂未刷新') && focus.includes('查看预测') && !focus.includes('查看比赛'), 'match focus states or actions are missing')
assert(recommendations.includes('getRecommendations') && recommendations.includes('sessionStorage') && recommendations.includes('requestSerial'), 'match recommendations cache or stale-request guard is missing')
assert(recommendations.includes("'match-focus:rolling'") && !recommendations.includes('CACHE_TTL_MS'), 'match recommendations cache key or TTL is wrong (must be match-focus:rolling with no TTL)')
assert(recommendations.includes('推荐数据暂时无法更新，当前显示上次成功读取的结果。'), 'match recommendations stale error copy is missing')
assert(teamNames.includes('曼城') && teamNames.includes('巴萨') && teamNames.includes('getTeamDisplayName'), 'team bilingual alias dictionary is incomplete')
assert(teamNames.includes('布伦特福德') && teamNames.includes('利兹联') && teamNames.includes('赫尔城'), 'team dictionary is missing r11 original entries (Brentford/Leeds/Hull)')
assert(!nav.includes('我的足球') && !nav.includes('角色卡') && !nav.includes('幻想远征'), 'retired modules remain in the main navigation')
assert(nav.includes('skip-link') && app.includes('id="app-main"'), 'keyboard skip navigation is missing')
assert(matchUtils.includes('formatCountdown') && matchUtils.includes('getBusinessDate'), 'shared match lifecycle utilities are missing')
assert(card.includes('时间待同步'), 'match card time fallback copy is missing')
// 注：Vue smoke 另断言的“状态待更新”在 r11 全树也不存在（属陈旧断言），此处不移植。
assert(markdown.includes('markdown-table-wrap') && markdown.includes('isTableSeparator'), 'agent markdown table rendering is missing')
assert(consent.includes('role="region"') && consent.includes('aria-live="polite"'), 'privacy consent semantics are missing')
assert(auth.includes('requestPasswordReset') && auth.includes('auth.closeDialog'), 'auth recovery and close controls are missing')
assert(i18n.includes("'auth.resetPassword': '重置密码'") && i18n.includes("'auth.closeDialog': '关闭登录窗口'"), 'auth recovery/close i18n values are missing')
assert(squad.includes('playerKeyword') && squad.includes('positionFilter') && squad.includes('sessionStorage'), 'squad filtering/cache controls are missing')
assert(card.includes('getDisplayStatusKey') && card.includes('getMatchId') && card.includes('时间待同步'), 'match card lifecycle display is missing')
assert(profile.includes('onHistoryKeydown') && profile.includes('moveProfileTab') && profile.includes('profile-panel-history'), 'profile keyboard interaction is missing')
assert(competition.includes('积分数据尚未形成') && !competition.includes('standingSort'), 'competition incomplete standings handling or redundant sort control is present')
assert(agent.includes('globalModelLabel') && agent.includes('没有读取到的数据会明确标注'), 'agent model and data-boundary disclosure is missing')
assert(privacy.includes('privacy-toc') && privacy.includes('privacy-rights'), 'privacy page navigation is missing')
assert(admin.includes('lastReloadAt') && admin.includes('最近刷新') && admin.includes('管理后台导航'), 'admin freshness or landmark is missing')
assert(pageState.includes('aria-live') && pageState.includes("type() === 'error' ? 'alert'"), 'loading and error states need live-region semantics')

const notFound = read('src/views/user/NotFound.jsx')
const footer = read('src/components/layout/AppFooter.jsx')
const about = read('src/views/user/About.jsx')
const terms = read('src/views/user/Terms.jsx')
assert(routes.includes("path: '/about'") && routes.includes("path: '/terms'"), 'about/terms routes are missing')
assert(about.includes('产品简介') && about.includes('免责声明'), 'about page content is incomplete')
assert(terms.includes('使用条款') && terms.includes('责任限制'), 'terms page content is incomplete')

// 2026-11-24 修复回归项：鉴权存储与媒体代理必须与 r11 原件一致。
assert(authStorage.includes('sessionStorage') && authStorage.includes('migrateLegacy'), 'authStorage must be sessionStorage with legacy localStorage migration')
assert(!authStorage.includes('window.localStorage.setItem'), 'authStorage must not write tokens to localStorage')
assert(mediaAsset.includes('/api/media/image?url='), 'mediaAsset must rewrite absolute URLs through the /api/media/image proxy')
assert(mediaAsset.includes("startsWith('/api/media/image?')"), 'mediaAsset must pass through already-proxied URLs')

console.log('smoke-routes: all assertions passed')
