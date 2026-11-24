/**
 * 鉴权 store（替代旧版 Pinia useUserStore）。
 *
 * 模块级 signal（Solid 里即单例 store），key 与旧版一致：
 * football_token / football_refresh_token / football_user，
 * 已登录会话在 Vue→Solid 切换后继续有效。
 * 登录接口的接线等契约组核对后再校准；状态流转（登录/刷新/登出/
 * 打开登录框并带 redirect）已按旧版 stores/user.js 完整实现。
 */
import { createSignal } from 'solid-js'
import { userApi } from '../api'
import { authStorage } from '../utils/authStorage'
import { toastError } from '../utils/toast'

function readSavedUser() {
  try {
    return JSON.parse(authStorage.get('football_user') || '{}')
  } catch {
    return {}
  }
}

const savedUser = readSavedUser()

const [token, setToken] = createSignal(authStorage.get('football_token') || '')
const [refreshToken, setRefreshToken] = createSignal(authStorage.get('football_refresh_token') || '')
const [username, setUsername] = createSignal(savedUser.username || '')
const [email, setEmail] = createSignal(savedUser.email || '')
const [emailVerified, setEmailVerified] = createSignal(Boolean(savedUser.emailVerified))
const [avatarData, setAvatarData] = createSignal(savedUser.avatarData || '')
const [userId, setUserId] = createSignal(savedUser.userId ?? null)
const [role, setRole] = createSignal(savedUser.role || 'USER')
const [sessionChecked, setSessionChecked] = createSignal(false)
const [authDialogVisible, setAuthDialogVisible] = createSignal(false)
const [authDialogTab, setAuthDialogTab] = createSignal('login')
const [authDialogRedirect, setAuthDialogRedirect] = createSignal('')
const [loginChallenge, setLoginChallenge] = createSignal(null)

const isAdmin = () => role() === 'ADMIN' || role() === 'SUPER_ADMIN'
const isAuthenticated = () => Boolean(token())

function persistUser() {
  authStorage.set(
    'football_user',
    JSON.stringify({
      username: username(),
      email: email(),
      emailVerified: emailVerified(),
      avatarData: avatarData(),
      userId: userId(),
      role: role(),
    }),
  )
}

function applyProfile(data) {
  if (!data) return
  setUsername(data.nickname || data.username || username())
  setEmail(data.email || '')
  setEmailVerified(Boolean(data.emailVerified))
  setAvatarData(data.avatarData || '')
  setUserId(data.userId ?? userId())
  setRole(data.role || role())
  persistUser()
}

export function openAuthDialog(redirect = '', tab = 'login') {
  setAuthDialogRedirect(typeof redirect === 'string' ? redirect : '')
  setAuthDialogTab(tab === 'register' ? 'register' : 'login')
  setAuthDialogVisible(true)
}

export function closeAuthDialog() {
  setAuthDialogVisible(false)
}

/** 登录成功后消费 redirect（goAfterLogin，契约 §3.1）：仅 '/' 开头且非 '//' 开头才回跳。 */
export function clearAuthRedirect() {
  setAuthDialogRedirect('')
}

export async function hydrateProfile() {
  try {
    const data = await userApi.getCurrentUser()
    if (!data || !data.loggedIn || data.userId == null) return null
    applyProfile(data)
    return data
  } catch {
    return null
  }
}

export async function tryRefresh() {
  if (!refreshToken() && !token()) return false
  try {
    const data = await userApi.refresh(refreshToken() || null)
    if (data && data.ok && data.token) {
      setToken(data.token)
      if (data.refreshToken) {
        setRefreshToken(data.refreshToken)
        authStorage.set('football_refresh_token', data.refreshToken)
      }
      setUsername(data.username || username())
      setUserId(data.userId ?? userId())
      setRole(data.role || role())
      authStorage.set('football_token', data.token)
      await hydrateProfile()
      return true
    }
  } catch {
    /* 刷新失败，走登出 */
  }
  logout()
  return false
}

export async function ensureSession() {
  if (!token()) {
    setSessionChecked(true)
    return false
  }
  try {
    const data = await userApi.getCurrentUser()
    if (data && data.loggedIn && data.userId != null) {
      applyProfile(data)
      setSessionChecked(true)
      return true
    }
  } catch {
    /* fall through to refresh/logout */
  }
  const refreshed = await tryRefresh()
  setSessionChecked(true)
  return refreshed
}

function commercialAuthError(raw, kind = 'login') {
  const msg = String(raw || '').trim()
  const lower = msg.toLowerCase()
  if (!msg) {
    return kind === 'register'
      ? '注册未完成，请检查邮箱、验证码与密码后重试'
      : '登录未成功，请确认账号与密码，或稍后再试'
  }
  if (/password|密码/.test(lower) && /incorrect|wrong|错误|不匹配|不正确/.test(lower)) {
    return '账号或密码不正确。可使用「忘记密码」重置，或核对大小写后重试'
  }
  if (/captcha|验证码|图形/.test(lower)) return '安全验证未通过，请刷新验证码后重试'
  if (/exist|已存在|already/.test(lower)) return '该邮箱可能已注册，请直接登录或使用忘记密码'
  if (/network|timeout|网络|超时/.test(lower)) return '网络不稳定，请稍后重试。你的数据不会因此丢失'
  if (/[\u4e00-\u9fff]/.test(msg) && msg.length <= 80) return msg
  return kind === 'register'
    ? msg.length <= 80
      ? msg
      : '注册未完成，请稍后重试或检查验证码是否过期'
    : msg.length <= 80
      ? msg
      : '登录未成功，请稍后重试'
}

export async function login(account, password, captchaId = '', captchaAnswer = '') {
  const data = await userApi.login(account, password, captchaId, captchaAnswer)
  setLoginChallenge(data?.captchaRequired ? data : null)
  if (data.ok) {
    setToken(data.token)
    setRefreshToken(data.refreshToken || '')
    setUsername(data.username || account)
    setUserId(data.userId)
    setRole(data.role || 'USER')
    setSessionChecked(true)
    authStorage.set('football_token', data.token)
    if (data.refreshToken) authStorage.set('football_refresh_token', data.refreshToken)
    else authStorage.remove('football_refresh_token')
    setEmail(data.email || '')
    setEmailVerified(Boolean(data.emailVerified))
    setAvatarData(data.avatarData || '')
    persistUser()
    await hydrateProfile()
    return true
  }
  toastError(commercialAuthError(data.message, 'login'))
  return false
}

export async function register(email, nickname, password, verificationCode, captchaId = '', captchaAnswer = '') {
  const data = await userApi.register(email, nickname, password, verificationCode, captchaId, captchaAnswer)
  if (data.ok) return true
  toastError(commercialAuthError(data.message, 'register'))
  return false
}

export function logout() {
  const currentRefresh = refreshToken()
  setToken('')
  setRefreshToken('')
  setUsername('')
  setEmail('')
  setEmailVerified(false)
  setAvatarData('')
  setUserId(null)
  setRole('USER')
  setSessionChecked(true)
  setLoginChallenge(null)
  authStorage.clear()
  if (currentRefresh) userApi.logout(currentRefresh).catch(() => {})
}

export const auth = {
  token,
  refreshToken,
  username,
  email,
  emailVerified,
  avatarData,
  userId,
  role,
  sessionChecked,
  authDialogVisible,
  authDialogTab,
  authDialogRedirect,
  loginChallenge,
  isAdmin,
  isAuthenticated,
  openAuthDialog,
  closeAuthDialog,
  clearAuthRedirect,
  ensureSession,
  tryRefresh,
  hydrateProfile,
  login,
  register,
  logout,
}

export default auth
