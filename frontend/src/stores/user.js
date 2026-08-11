import { defineStore } from 'pinia'
import { userApi } from '../api'
import { ElMessage } from 'element-plus'

export const useUserStore = defineStore('user', {
  state: () => {
    const savedUser = (() => {
      try { return JSON.parse(localStorage.getItem('football_user') || '{}') } catch { return {} }
    })()
    return {
      token: localStorage.getItem('football_token') || '',
      username: savedUser.username || '',
      userId: savedUser.userId || null,
      role: savedUser.role || 'USER'
    }
  },
  actions: {
    async login(username, password) {
      const res = await userApi.login(username, password)
      const data = res?.data ?? res
      if (data.ok) {
        this.token = data.token
        this.username = data.username || username
        this.userId = data.userId
        this.role = data.role || 'USER'
        localStorage.setItem('football_token', data.token)
        localStorage.setItem('football_user', JSON.stringify({ username: this.username, userId: data.userId, role: this.role }))
        return true
      } else {
        ElMessage.error(data.message || '登录失败')
        return false
      }
    },
    async register(username, password) {
      const res = await userApi.register(username, password)
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
      this.token = ''
      this.username = ''
      this.userId = null
      this.role = 'USER'
      localStorage.removeItem('football_token')
      localStorage.removeItem('football_user')
    }
  }
})
