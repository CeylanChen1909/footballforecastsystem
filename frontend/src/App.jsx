/**
 * 应用根组件（start mode 约定：src/App.jsx 默认导出即根组件）。
 *
 * 布局在 Router 的函数子组件里（官方文档要求：共享布局放 router 内，
 * 这样才能读 location；放外面会抛 “只能在 Route 内使用”）。
 *
 * 路由守卫（契约 §1.2，6 步顺序复现；Solid Router 没有 beforeEach，
 * 用 AppShell 内的 createEffect + navigate 实现）：
 *  1. 首次会话检查：token 在且 sessionChecked 为 false 时先 ensureSession，
 *     本轮不分流，等 sessionChecked 翻转后 effect 重跑再判。
 *  2. /login：未登录 → openAuthDialog(query.redirect 或 '') + 回 /matches；
 *     已登录 → 管理员 /admin、其余 /matches。/login 页面永远不可达。
 *  3. requiresAuth 且未登录 → openAuthDialog(完整目标路径) + 回 /matches
 *     （不是跳 /login；弹窗代替跳转）。
 *  4. 角色不符 → 管理员 /admin、其余 /matches（role 缺省 'USER'）。
 *  5. '/' 分支是死代码：路由表已重定向到 /matches，该分支不可达，不复现。
 * 滚动：createRouter 默认开 scrollRestoration（savedPosition 恢复、新导航回顶），
 * 本文件只补 hash 平滑滚动（旧版 scrollBehavior 的 hash 分支）。
 * 法律门（契约 §3.6）：遮罩时路由/悬浮球/登录框/同意条全部不渲染。
 */
import { createEffect, createSignal, Loading, onSettled, Show } from 'solid-js'
import { useLocation, useNavigate } from '@solidjs/router'
import './styles/design-tokens-2026.css'
import './styles/app.css'
import { Router, applyRouteMeta, isKnownRoute, matchRouteMeta } from './router'
import AppTopNav from './components/layout/AppTopNav'
import AppFooter from './components/layout/AppFooter'
import PageState from './components/layout/PageState'
import AgentLauncher from './components/layout/AgentLauncher'
import AuthDialog from './components/auth/AuthDialog'
import ConsentBanner from './components/privacy/ConsentBanner'
import LegalConsentGate from './components/privacy/LegalConsentGate'
import ToastViewport from './components/ui/ToastViewport'
import auth from './stores/auth'
import { initTheme } from './stores/theme'
import { userApi, analyticsApi } from './api'
import { hasLegalConsent, saveLegalConsent } from './utils/legalConsent'
import { canTrackAnalytics } from './utils/privacyConsent'

const ADMIN_HOME = '/admin'
const USER_HOME = '/matches'

function AppShell(props) {
  const location = useLocation()
  const navigate = useNavigate()
  const [legalGateVisible, setLegalGateVisible] = createSignal(true)
  const [agentLauncherReady, setAgentLauncherReady] = createSignal(false)
  let legalConsentSyncSequence = 0
  let launcherArmed = false

  const homeForRole = () => (auth.isAdmin() ? ADMIN_HOME : USER_HOME)

  const showAgentLauncher = () => {
    if (!auth.token()) return false
    const path = location.pathname
    if (path === '/agent' || path === '/login' || path.startsWith('/admin')) return false
    if (path === '/matches' || path.startsWith('/matches/') || path === '/about') return false
    return true
  }

  const armAgentLauncher = () => {
    if (launcherArmed) return
    launcherArmed = true
    const enable = () => setAgentLauncherReady(true)
    if (typeof window !== 'undefined' && 'requestIdleCallback' in window) {
      window.requestIdleCallback(enable, { timeout: 2500 })
    } else {
      window.setTimeout(enable, 1200)
    }
  }

  const syncLegalConsent = async (token) => {
    const sequence = ++legalConsentSyncSequence
    if (!token) {
      setLegalGateVisible(!hasLegalConsent())
      return
    }
    try {
      const data = await userApi.getLegalConsentStatus()
      if (sequence !== legalConsentSyncSequence) return
      if (data?.accepted) {
        saveLegalConsent()
        setLegalGateVisible(false)
      } else {
        setLegalGateVisible(true)
      }
    } catch {
      if (sequence !== legalConsentSyncSequence) return
      // 登录用户请求失败时不允许静默绕过（契约 §3.6）。
      setLegalGateVisible(true)
    }
  }

  const handleLegalAccepted = () => {
    legalConsentSyncSequence += 1
    setLegalGateVisible(false)
  }

  const handleAuthRequired = (event) => {
    if (auth.token()) auth.logout()
    auth.openAuthDialog(event?.detail?.redirect || '')
  }

  // Solid 2.0：onMount 已删，挂载后的一次性工作用 onSettled（可返回 cleanup）。
  onSettled(() => {
    initTheme()
    armAgentLauncher()
    window.addEventListener('football-auth-required', handleAuthRequired)
    return () => window.removeEventListener('football-auth-required', handleAuthRequired)
  })

  // token 变化时同步法律确认状态（旧版 watch(token)）。
  // 注意：effect 回调的返回值会被当作 cleanup——syncLegalConsent 是 async
  // 函数会返回 Promise，必须用块体丢弃返回值，否则报 invalid cleanup value。
  createEffect(
    () => auth.token(),
    (token) => {
      syncLegalConsent(token)
    },
  )
  createEffect(
    () => legalGateVisible(),
    (visible) => {
      if (!visible) armAgentLauncher()
    },
  )

  // 路由守卫（契约 §1.2 第 1–4 步）。
  // Solid 2.0 的 createEffect 是双参：compute 追踪依赖并返回值，effect 不追踪。
  createEffect(
    () => ({
      path: location.pathname,
      search: location.search,
      token: auth.token(),
      checked: auth.sessionChecked(),
      role: auth.role(),
    }),
    ({ path, search, token, checked, role }) => {
      if (token && !checked) {
        auth.ensureSession()
        return
      }
      const isAuthenticated = Boolean(token)

      if (path === '/login') {
        if (!isAuthenticated) {
          const redirect = new URLSearchParams(search).get('redirect') || ''
          auth.openAuthDialog(redirect)
          navigate(USER_HOME, { replace: true })
        } else {
          navigate(homeForRole(), { replace: true })
        }
        return
      }

      const meta = matchRouteMeta(path)
      if (meta?.requiresAuth && !isAuthenticated) {
        auth.openAuthDialog(path + search)
        navigate(USER_HOME, { replace: true })
        return
      }
      if (meta?.roles && !meta.roles.includes(role || 'USER')) {
        navigate(homeForRole(), { replace: true })
        return
      }
    },
  )

  // hash 平滑滚动 + 标题/meta + 匿名统计 page_view。
  createEffect(
    () => ({ path: location.pathname, hash: location.hash, search: location.search }),
    ({ path, hash, search }) => {
      if (hash) {
        const el = document.querySelector(hash)
        if (el) el.scrollIntoView({ behavior: 'smooth' })
      }
      applyRouteMeta(path, !isKnownRoute(path))
      if (canTrackAnalytics()) {
        analyticsApi.track('page_view', { page: path + search }).catch(() => {})
      }
    },
  )

  return (
    <div class="ff-shell">
      <Show when={!legalGateVisible()}>
        <AppTopNav title="ChenFootball" subtitle="" />
        <main id="app-main" class="ff-main">
          {props.children}
        </main>
        <AppFooter />
        <Show when={showAgentLauncher() && agentLauncherReady()}>
          <AgentLauncher />
        </Show>
        <AuthDialog />
        <ConsentBanner />
      </Show>
      <LegalConsentGate visible={legalGateVisible()} onAccepted={handleLegalAccepted} />
      <ToastViewport />
    </div>
  )
}

export default function App() {
  // Solid 2.0：lazy 路由组件是异步值，读取发生在 Router 的路由匹配里，
  // 边界必须包住整个 Router 才“拥有”这次读取，否则根挂载会被推迟到
  // 全部异步 settle（ASYNC_OUTSIDE_LOADING_BOUNDARY）。
  return (
    <Loading fallback={<PageState type="loading" title="页面加载中…" />}>
      <Router>{(props) => <AppShell>{props.children}</AppShell>}</Router>
    </Loading>
  )
}
