import { defineStore } from 'pinia'
import { userApi } from '../api'
import { ElMessage } from 'element-plus'
import { authStorage } from '../utils/authStorage'

export const useUserStore = defineStore('user', {
  state: () => {
    const savedUser = (() => {
      try { return JSON.parse(authStorage.get('football_user') || '{}') } catch { return {} }
    })()
    return {
      token: authStorage.get('football_token') || '',
      refreshToken: authStorage.get('football_refresh_token') || '',
      username: savedUser.username || '',
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
          this.username = data.username || ''
          this.userId = data.userId
          this.role = data.role || 'USER'
          authStorage.set('football_user', JSON.stringify({ username: this.username, userId: this.userId, role: this.role }))
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
          authStorage.set('football_user', JSON.stringify({ username: this.username, userId: this.userId, role: this.role }))
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
        authStorage.set('football_user', JSON.stringify({ username: this.username, userId: data.userId, role: this.role }))
        return true
      } else {
        ElMessage.error(data.message || '登录失败')
        return false
      }
    },
    async register(email, nickname, password, verificationCode) {
      const res = await userApi.register(email, nickname, password, verificationCode)
      const data = res?.data ?? res
      if (data.ok) {
        ElMessage.success('注册成功，请登录')
        return true
      } else {
        ElMessage.error(data.message || '注册失败')
        return false
      }
    },
    logout() {
      // 通知后端作废 refresh token（失败不影响本地登出）
      userApi.logout(this.refreshToken || null).catch(() => {})
      this.token = ''
      this.refreshToken = ''
      this.username = ''
      this.userId = null
      this.role = 'USER'
      this.sessionChecked = true
      authStorage.clear()
    }
  }
})
