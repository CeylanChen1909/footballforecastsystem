<template>
  <div class="login-page">
    <div class="login-card">
      <div class="logo">
        <el-icon :size="48" color="#409EFF"><Football /></el-icon>
        <h1>ChenFootball</h1>
        <p>基于 XGBoost 的智能预测平台</p>
      </div>

      <el-tabs v-model="activeTab" class="auth-tabs">
        <el-tab-pane label="登录" name="login">
          <el-form ref="loginFormRef" :model="loginForm" :rules="rules" label-position="top">
            <el-form-item label="用户名" prop="username">
              <el-input v-model="loginForm.username" placeholder="请输入用户名" prefix-icon="User" clearable />
            </el-form-item>
            <el-form-item label="密码" prop="password">
              <el-input v-model="loginForm.password" type="password" placeholder="请输入密码" prefix-icon="Lock"
                show-password @keyup.enter="handleLogin" />
            </el-form-item>
            <el-button type="primary" :loading="loading" class="submit-btn" @click="handleLogin">
              登录
            </el-button>
          </el-form>
        </el-tab-pane>

        <el-tab-pane label="注册" name="register">
          <el-form ref="registerFormRef" :model="registerForm" :rules="rules" label-position="top">
            <el-form-item label="用户名" prop="username">
              <el-input v-model="registerForm.username" placeholder="请输入用户名" prefix-icon="User" clearable />
            </el-form-item>
            <el-form-item label="密码" prop="password">
              <el-input v-model="registerForm.password" type="password" placeholder="请输入密码（至少6位）"
                prefix-icon="Lock" show-password />
            </el-form-item>
            <el-form-item label="确认密码" prop="confirmPassword">
              <el-input v-model="registerForm.confirmPassword" type="password" placeholder="请再次输入密码"
                prefix-icon="Lock" show-password @keyup.enter="handleRegister" />
            </el-form-item>
            <el-button type="primary" :loading="loading" class="submit-btn" @click="handleRegister">
              注册
            </el-button>
          </el-form>
        </el-tab-pane>
      </el-tabs>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive } from 'vue'
import { useRouter } from 'vue-router'
import { useUserStore } from '../stores/user'
import { ElMessage } from 'element-plus'

const router = useRouter()
const userStore = useUserStore()

const activeTab = ref('login')
const loading = ref(false)
const loginFormRef = ref()
const registerFormRef = ref()

const loginForm = reactive({ username: '', password: '' })
const registerForm = reactive({ username: '', password: '', confirmPassword: '' })

const validateConfirm = (rule, value, callback) => {
  if (value !== registerForm.password) callback(new Error('两次密码不一致'))
  else callback()
}

const rules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }, { min: 6, message: '密码至少6位', trigger: 'blur' }],
  confirmPassword: [{ required: true, message: '请确认密码', trigger: 'blur' }, { validator: validateConfirm, trigger: 'blur' }]
}

const handleLogin = async () => {
  const valid = await loginFormRef.value?.validate().catch(() => false)
  if (!valid) return
  loading.value = true
  const ok = await userStore.login(loginForm.username, loginForm.password)
  loading.value = false
  if (ok) {
    ElMessage.success('登录成功')
    router.push(userStore.role === 'ADMIN' ? '/admin' : '/matches')
  }
}

const handleRegister = async () => {
  const valid = await registerFormRef.value?.validate().catch(() => false)
  if (!valid) return
  loading.value = true
  const ok = await userStore.register(registerForm.username, registerForm.password)
  loading.value = false
  if (ok) {
    activeTab.value = 'login'
    loginForm.username = registerForm.username
  }
}
</script>

<style scoped>
.login-page { min-height: 100vh; background: linear-gradient(135deg, #1a1a2e 0%, #16213e 50%, #0f3460 100%); display: flex; align-items: center; justify-content: center; padding: 20px; }
.login-card { background: rgba(255, 255, 255, 0.95); border-radius: 16px; padding: 40px; width: 100%; max-width: 420px; box-shadow: 0 20px 60px rgba(0, 0, 0, 0.3); }
.logo { text-align: center; margin-bottom: 30px; }
.logo h1 { margin: 12px 0 8px; font-size: 24px; color: #1a1a2e; }
.logo p { color: #666; font-size: 14px; margin: 0; }
.auth-tabs { margin-top: 10px; }
.submit-btn { width: 100%; height: 42px; font-size: 16px; margin-top: 8px; }
</style>
