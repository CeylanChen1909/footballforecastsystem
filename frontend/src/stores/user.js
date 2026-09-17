import { defineStore } from 'pinia'
import { userApi } from '../api'
import { ElMessage } from 'element-plus'
import { authStorage } from '../utils/authStorage'

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
  if (/captcha|验证码|图形/.test(lower)) {
    return '安全验证未通过，请刷新验证码后重试'
  }
  if (/exist|已存在|already/.test(lower)) {
    return '该邮箱可能已注册，请直接登录或使用忘记密码'
  }
  if (/network|timeout|网络|超时/.test(lower)) {
    return '网络不稳定，请稍后重试。你的数据不会因此丢失'
  }
  if (/[\u4e00-\u9fff]/.test(msg) && msg.length <= 80) return msg
  return kind === 'register'
    ? (msg.length <= 80 ? msg : '注册未完成，请稍后重试或检查验证码是否过期')
    : (msg.length <= 80 ? msg : '登录未成功，请稍后重试')
}

export const useUserStore = defineStore('user', {
  state: () => {
    const savedUser = (() => {
      try { return JSON.parse(authStorage.get('football_user') || '{}') } catch { return {} }
    })()
    return {
      token: authStorage.get('football_token') || '',
      refreshToken: authStorage.get('football_refresh_token') || '',
      username: savedUser.username || '',
      email: savedUser.email || '',
      emailVerified: Boolean(savedUser.emailVerified),
      avatarData: savedUser.avatarData || '',
      userId: savedUser.userId || null,
      role: savedUser.role || 'USER',
      sessionChecked: false,
      authDialogVisible: false,
      authDialogTab: 'login',
      authDialogRedirect: '',
      loginChallenge: null,
    }
  },
  actions: {
    async hydrateProfile() {
      try {
        const res = await userApi.getCurrentUser()
        const data = res?.data ?? res
        if (!data || !data.loggedIn || data.userId == null) return null
        this.username = data.nickname || data.username || this.username
        this.email = data.email || ''
        this.emailVerified = Boolean(data.emailVerified)
        this.avatarData = data.avatarData || ''
        this.userId = data.userId
        this.role = data.role || this.role
        authStorage.set('football_user', JSON.stringify({ username: this.username, email: this.email, emailVerified: this.emailVerified, avatarData: this.avatarData, userId: this.userId, role: this.role }))
        return data
      } catch {
        return null
      }
    },
    openAuthDialog(redirect = '', tab = 'login') {
      this.authDialogRedirect = typeof redirect === 'string' ? redirect : ''
      this.authDialogTab = tab === 'register' ? 'register' : 'login'
      this.authDialogVisible = true
    },
    closeAuthDialog() {
      this.authDialogVisible = false
    },
    async ensureSession() {
      if (!this.token) {
        this.sessionChecked = true
        return false
      }
      try {
        const res = await userApi.getCurrentUser()
        const data = res?.data ?? res
        if (data && data.loggedIn && data.userId != null) {
          this.username = data.nickname || data.username || ''
          this.email = data.email || ''
          this.emailVerified = Boolean(data.emailVerified)
          this.avatarData = data.avatarData || ''
          this.userId = data.userId
          this.role = data.role || 'USER'
          authStorage.set('football_user', JSON.stringify({ username: this.username, email: this.email, emailVerified: this.emailVerified, avatarData: this.avatarData, userId: this.userId, role: this.role }))
          this.sessionChecked = true
          return true
        }
      } catch {
        // fall through to refresh/logout
      }
      const refreshed = await this.tryRefresh()
      this.sessionChecked = true
      return refreshed
    },
    /** 用 refreshToken 换新 token；成功返回 true */
    async tryRefresh() {
      // Production can use the HttpOnly refresh cookie; local development
      // keeps the legacy body token for backwards compatibility.
      if (!this.refreshToken && !this.token) return false
      try {
        const res = await userApi.refresh(this.refreshToken || null)
        const data = res?.data ?? res
        if (data && data.ok && data.token) {
          this.token = data.token
          if (data.refreshToken) {
            this.refreshToken = data.refreshToken
            authStorage.set('football_refresh_token', data.refreshToken)
          }
          this.username = data.username || this.username
          this.userId = data.userId ?? this.userId
          this.role = data.role || this.role
          authStorage.set('football_token', data.token)
          await this.hydrateProfile()
          return true
        }
      } catch {
        // refresh 失败，走登出
      }
      this.logout()
      return false
    },
    async login(account, password, captchaId = '', captchaAnswer = '') {
      const res = await userApi.login(account, password, captchaId, captchaAnswer)
      const data = res?.data ?? res
      this.loginChallenge = data?.captchaRequired ? data : null
      if (data.ok) {
        this.token = data.token
        this.refreshToken = data.refreshToken || ''
        this.username = data.username || account
        this.userId = data.userId
        this.role = data.role || 'USER'
        this.sessionChecked = true
        authStorage.set('football_token', data.token)
        if (this.refreshToken) authStorage.set('football_refresh_token', this.refreshToken)
        else authStorage.remove('football_refresh_token')
        authStorage.set('football_user', JSON.stringify({ username: this.username, email: data.email || '', emailVerified: Boolean(data.emailVerified), avatarData: data.avatarData || '', userId: data.userId, role: this.role }))
        await this.hydrateProfile()
        return true
      } else {
        ElMessage.error(commercialAuthError(data.message, 'login'))
        return false
      }
    },
    async register(email, nickname, password, verificationCode, captchaId = '', captchaAnswer = '') {
      const res = await userApi.register(email, nickname, password, verificationCode, captchaId, captchaAnswer)
      const data = res?.data ?? res
      if (data.ok) {
        ElMessage.success('注册成功，请使用邮箱登录以同步收藏与预测')
        return true
      } else {
        ElMessage.error(commercialAuthError(data.message, 'register'))
        return false
      }
    },
    logout() {
      // 通知后端作废 refresh token（失败不影响本地登出）
      userApi.logout(this.refreshToken || null).catch(() => {})
      this.token = ''
      this.refreshToken = ''
      this.username = ''
      this.email = ''
      this.emailVerified = false
      this.avatarData = ''
      this.userId = null
      this.role = 'USER'
      this.sessionChecked = true
      authStorage.clear()
    }
  }
})
