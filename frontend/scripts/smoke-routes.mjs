import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const read = relative => fs.readFileSync(path.join(root, relative), 'utf8')
const exists = relative => fs.existsSync(path.join(root, relative))

const routes = read('src/router/index.js')
const nav = read('src/components/layout/AppTopNav.vue')
const admin = read('src/views/admin/AdminDashboard.vue')
const prediction = read('src/views/user/Prediction.vue')
const matches = read('src/views/user/Matches.vue')
const focus = read('src/components/matches/MatchFocusRail.vue')
const recommendations = read('src/composables/useMatchRecommendations.js')
const teamNames = read('src/utils/teamNames.js')
const matchUtils = read('src/utils/match.js')
const markdown = read('src/utils/markdown.js')
const consent = read('src/components/privacy/ConsentBanner.vue')
const auth = read('src/components/auth/AuthDialog.vue')
const squad = read('src/views/user/TeamSquad.vue')
const card = read('src/components/MatchCard.vue')
const profile = read('src/views/user/Profile.vue')
const pageState = read('src/components/layout/PageState.vue')
const competition = read('src/views/user/CompetitionHub.vue')
const agent = read('src/views/user/Agent.vue')
const privacy = read('src/views/user/Privacy.vue')

for (const route of ['/home', '/matches', '/news', '/videos', '/profile', '/prediction/:fixtureId', '/admin', '/card-lab', '/card-rogue']) {
  assert(routes.includes(`path: '${route}'`), `missing route: ${route}`)
}
for (const component of [
  'src/views/admin/AdminConfigPanel.vue',
  'src/views/admin/AdminCrawlerMatchEditorDialog.vue',
  'src/views/admin/AdminUsersPanel.vue',
  'src/views/admin/AdminLogPanel.vue',
  'src/views/admin/MatchWorkbench.vue'
]) assert(exists(component), `missing component: ${component}`)

assert(admin.includes('loadDashboardSummary') && admin.includes('部分模块刷新失败'), 'admin partial refresh feedback is missing')
assert(admin.includes('主爬虫监控') && admin.includes('更新主爬虫数据'), 'primary crawler controls are missing')
assert(!admin.includes('AdminNewsPanel') && !admin.includes('AdminVideoPanel') && !admin.includes('AdminContentSourcePanel'), 'retired content modules are still wired into admin')
assert(prediction.includes('crawlerApi.getMatchDetail') && prediction.includes('statusActionLabel'), 'prediction deep-link or status fallback is missing')
assert(matches.includes('return [0, 1, 2, 3, 4, 5, 6].map(offset =>'), 'matches date rail should expose seven dates')
assert(matches.includes('查看前一天') && matches.includes('查看后一天'), 'matches date rail arrows are missing')
assert(matches.includes('dateCounts') && matches.includes('matchesHeading') && matches.includes('openFocusMatch'), 'matches context, rail counts or focus navigation is missing')
assert(!matches.includes('未来 7 天') && !matches.includes('date-nav-picker'), 'redundant date controls remain')
assert(matches.includes('preserveMeta: true') && matches.includes('toggleMatchReminders') && matches.includes('remindersChanging'), 'matches metadata or reminder toggle is missing')
assert(matches.includes('teamNameMode') && matches.includes('toggleTeamNameMode') && matches.includes('getTeamSearchTokens'), 'bilingual team display or fuzzy search is not wired')
assert(focus.includes('比赛焦点') && focus.includes('推荐数据暂未刷新') && focus.includes('查看预测') && !focus.includes('查看比赛'), 'match focus states or actions are missing')
assert(recommendations.includes('getRecommendations') && recommendations.includes('sessionStorage') && recommendations.includes('requestSerial'), 'match recommendations cache or stale-request guard is missing')
assert(teamNames.includes('曼城') && teamNames.includes('巴萨') && teamNames.includes('getTeamDisplayName'), 'team bilingual alias dictionary is incomplete')
assert(routes.includes("{ path: '/', redirect: '/matches' }") && routes.includes("path: '/home', redirect: '/matches'"), 'root/home redirects are missing')
assert(!routes.includes("component: () => import('../views/user/Home.vue')"), 'retired home component is still mounted')
assert(routes.includes("path: '/card-lab', redirect: '/matches'") && routes.includes("path: '/card-rogue', redirect: '/matches'"), 'retired card modules should redirect to matches')
assert(!nav.includes('index="/home"') && !nav.includes('我的足球') && !nav.includes('角色卡') && !nav.includes('幻想远征'), 'retired modules remain in the main navigation')
assert(nav.includes('skip-link') && matches.includes('id="app-main"'), 'keyboard skip navigation is missing')
assert(matchUtils.includes('状态待更新') && matchUtils.includes('formatCountdown') && matchUtils.includes('时间待同步') && matchUtils.includes('getBusinessDate'), 'shared match lifecycle utilities are missing')
assert(markdown.includes('markdown-table-wrap') && markdown.includes('isTableSeparator'), 'agent markdown table rendering is missing')
assert(consent.includes('role="region"') && consent.includes('aria-live="polite"'), 'privacy consent semantics are missing')
assert(auth.includes('requestPasswordReset') && auth.includes('重置密码') && auth.includes('关闭登录窗口'), 'auth recovery and close controls are missing')
assert(squad.includes('playerKeyword') && squad.includes('positionFilter') && squad.includes('sessionStorage'), 'squad filtering/cache controls are missing')
assert(card.includes('getDisplayStatusKey') && card.includes('getMatchId') && card.includes('时间待同步'), 'match card lifecycle display is missing')
assert(profile.includes('onHistoryKeydown') && profile.includes('moveProfileTab') && profile.includes('profile-panel-history'), 'profile keyboard interaction is missing')
assert(competition.includes('积分数据尚未形成') && competition.includes('standingSort'), 'competition incomplete standings handling is missing')
assert(agent.includes('globalModelLabel') && agent.includes('没有读取到的数据会明确标注'), 'agent model and data-boundary disclosure is missing')
assert(privacy.includes('privacy-toc') && privacy.includes('privacy-rights'), 'privacy page navigation is missing')
assert(admin.includes('lastReloadAt') && admin.includes('最近刷新') && admin.includes('管理后台导航'), 'admin freshness or landmark is missing')
assert(pageState.includes('aria-live') && pageState.includes("type === 'error' ? 'alert'"), 'loading and error states need live-region semantics')

console.log(`Smoke checks passed: ${new Date().toISOString()}`)
