<template>
  <div class="login-page">
    <div class="login-card reveal">
      <div class="logo">
        <div class="logo-icon"><el-icon :size="36"><Football /></el-icon></div>
        <h1>ChenFootball</h1>
        <p>登录或注册账号</p>
      </div>

      <el-tabs v-model="activeTab" class="auth-tabs">
        <el-tab-pane label="登录" name="login">
          <el-form ref="loginFormRef" :model="loginForm" :rules="loginRules" label-position="top">
            <el-form-item label="邮箱或账号" prop="account">
              <el-input v-model="loginForm.account" placeholder="请输入邮箱或账号" prefix-icon="Message" clearable size="large" />
            </el-form-item>
            <el-form-item v-if="userStore.loginChallenge" label="安全验证" prop="captchaAnswer">
              <div class="captcha-row"><span class="captcha-question">{{ userStore.loginChallenge.captchaQuestion }}</span><el-input v-model="loginForm.captchaAnswer" placeholder="计算结果" /></div>
            </el-form-item>
            <el-form-item label="密码" prop="password">
              <el-input v-model="loginForm.password" type="password" placeholder="请输入密码" prefix-icon="Lock"
                show-password @keyup.enter="handleLogin" size="large" />
            </el-form-item>
            <div class="forgot-password" @click="showForgotPassword">忘记密码？</div>
            <el-button type="primary" :loading="loading" class="submit-btn" @click="handleLogin">
              登录
            </el-button>
          </el-form>
        </el-tab-pane>

        <el-tab-pane label="注册" name="register">
          <el-form ref="registerFormRef" :model="registerForm" :rules="registerRules" label-position="top">
            <el-form-item label="邮箱" prop="email">
              <el-input v-model="registerForm.email" placeholder="用于登录和接收验证码" prefix-icon="Message" clearable size="large" />
            </el-form-item>
            <el-form-item label="昵称" prop="nickname">
              <el-input v-model="registerForm.nickname" placeholder="对外展示的昵称" prefix-icon="User" clearable size="large" />
            </el-form-item>
            <el-form-item label="邮箱验证码" prop="verificationCode">
              <div class="code-row"><el-input v-model="registerForm.verificationCode" placeholder="6 位验证码" /><el-button :disabled="codeCountdown > 0" @click="sendRegisterCode">{{ codeCountdown > 0 ? `${codeCountdown}s 后重发` : '发送验证码' }}</el-button></div>
            </el-form-item>
            <el-form-item label="密码" prop="password">
              <el-input v-model="registerForm.password" type="password" placeholder="请输入密码（至少8位）"
                prefix-icon="Lock" show-password size="large" />
            </el-form-item>
            <el-form-item label="确认密码" prop="confirmPassword">
              <el-input v-model="registerForm.confirmPassword" type="password" placeholder="请再次输入密码"
                prefix-icon="Lock" show-password @keyup.enter="handleRegister" size="large" />
            </el-form-item>
            <el-button type="primary" :loading="loading" class="submit-btn" @click="handleRegister">
              注册
            </el-button>
          </el-form>
        </el-tab-pane>
      </el-tabs>

      <div class="login-footnote">登录后可同步收藏、预测历史与提醒设置；没有账号可直接注册。<router-link to="/privacy">隐私与数据说明</router-link></div>
      <div class="login-footnote">© ChenFootball · 智能预测平台</div>
    </div>
    <el-dialog v-model="resetVisible" title="通过邮箱重置密码" width="min(420px, 92vw)">
      <el-form label-position="top">
        <el-form-item label="注册邮箱"><el-input v-model="resetForm.email" type="email" placeholder="请输入注册邮箱" /></el-form-item>
        <el-form-item label="验证码"><div class="code-row"><el-input v-model="resetForm.verificationCode" placeholder="6 位验证码" /><el-button :disabled="resetCountdown > 0" @click="sendResetCode">{{ resetCountdown > 0 ? `${resetCountdown}s 后重发` : '发送验证码' }}</el-button></div></el-form-item>
        <el-form-item label="新密码"><el-input v-model="resetForm.newPassword" type="password" show-password placeholder="至少 8 位" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="resetVisible=false">取消</el-button><el-button type="primary" @click="submitReset">重置密码</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive } from 'vue'
import { useRouter } from 'vue-router'
import { useUserStore } from '../../stores/user'
import { ElMessage } from 'element-plus'
import { Football } from '@element-plus/icons-vue'
import { userApi } from '../../api'

const router = useRouter()
const userStore = useUserStore()

const activeTab = ref('login')
const loading = ref(false)
const loginFormRef = ref()
const registerFormRef = ref()

const loginForm = reactive({ account: '', password: '', captchaAnswer: '' })
const registerForm = reactive({ email: '', nickname: '', password: '', confirmPassword: '', verificationCode: '' })
const codeCountdown = ref(0)
let codeTimer = null
const resetVisible = ref(false)
const resetForm = reactive({ email: '', verificationCode: '', newPassword: '' })
const resetCountdown = ref(0)
let resetTimer = null

const validateConfirm = (rule, value, callback) => {
  if (value !== registerForm.password) callback(new Error('两次密码不一致'))
  else callback()
}

const loginRules = {
  account: [{ required: true, message: '请输入邮箱或账号', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
  captchaAnswer: [{ required: true, message: '请输入计算结果', trigger: 'blur' }]
}
const registerRules = {
  account: [{ required: true, message: '请输入邮箱或账号', trigger: 'blur' }],
  email: [{ required: true, message: '请输入邮箱', trigger: 'blur' }, { type: 'email', message: '邮箱格式不正确', trigger: 'blur' }],
  nickname: [{ required: true, message: '请输入昵称', trigger: 'blur' }, { max: 32, message: '昵称不能超过32个字符', trigger: 'blur' }],
  verificationCode: [{ required: true, message: '请输入邮箱验证码', trigger: 'blur' }, { len: 6, message: '验证码为6位数字', trigger: 'blur' }],
  captchaAnswer: [{ required: true, message: '请输入计算结果', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }, { min: 8, message: '密码至少8位', trigger: 'blur' }],
  confirmPassword: [{ required: true, message: '请确认密码', trigger: 'blur' }, { validator: validateConfirm, trigger: 'blur' }]
}

const showForgotPassword = () => { resetVisible.value = true }

const sendRegisterCode = async () => {
  if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(registerForm.email)) { ElMessage.warning('请先输入有效邮箱'); return }
  try {
    const result = await userApi.sendEmailCode(registerForm.email, 'REGISTER')
    if (result?.ok === false) throw new Error(result.message || '验证码发送失败')
    ElMessage.success(result?.delivery === 'console' ? '验证码已写入后端开发日志' : '验证码已发送，请查收邮件')
    codeCountdown.value = 60
    codeTimer = window.setInterval(() => { codeCountdown.value -= 1; if (codeCountdown.value <= 0) { window.clearInterval(codeTimer); codeTimer = null } }, 1000)
  } catch (error) { ElMessage.error(error?.message || '验证码发送失败') }
}

const sendResetCode = async () => {
  if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(resetForm.email)) { ElMessage.warning('请先输入有效邮箱'); return }
  try {
    const result = await userApi.requestPasswordReset(resetForm.email)
    if (result?.ok === false) throw new Error(result.message || '验证码发送失败')
    ElMessage.success('如果该邮箱已注册，验证码已发送')
    resetCountdown.value = 60
    resetTimer = window.setInterval(() => { resetCountdown.value -= 1; if (resetCountdown.value <= 0) { window.clearInterval(resetTimer); resetTimer = null } }, 1000)
  } catch (error) { ElMessage.error(error?.message || '验证码发送失败') }
}
const submitReset = async () => {
  if (!resetForm.email || !/^\d{6}$/.test(resetForm.verificationCode) || resetForm.newPassword.length < 8) { ElMessage.warning('请完整填写邮箱、验证码和至少8位新密码'); return }
  try {
    const result = await userApi.resetPassword(resetForm.email, resetForm.verificationCode, resetForm.newPassword)
    if (result?.ok === false) throw new Error(result.message || '密码重置失败')
    ElMessage.success('密码已重置，请使用新密码登录')
    resetVisible.value = false
    loginForm.account = resetForm.email
    resetForm.verificationCode = ''; resetForm.newPassword = ''
  } catch (error) { ElMessage.error(error?.message || '密码重置失败') }
}

const handleLogin = async () => {
  const valid = await loginFormRef.value?.validate().catch(() => false)
  if (!valid) return
  loading.value = true
  const ok = await userStore.login(loginForm.account, loginForm.password, userStore.loginChallenge?.captchaId, loginForm.captchaAnswer)
  loading.value = false
  if (ok) {
    ElMessage.success('登录成功')
    const redirect = router.currentRoute.value.query.redirect
    if (typeof redirect === 'string' && redirect.startsWith('/')) {
      router.replace(redirect)
      return
    }
    router.replace(['ADMIN', 'SUPER_ADMIN'].includes(userStore.role) ? '/admin' : '/matches')
  }
}

const handleRegister = async () => {
  const valid = await registerFormRef.value?.validate().catch(() => false)
  if (!valid) return
  loading.value = true
  const ok = await userStore.register(registerForm.email, registerForm.nickname, registerForm.password, registerForm.verificationCode)
  loading.value = false
  if (ok) {
    activeTab.value = 'login'
    loginForm.account = registerForm.email
  }
}
</script>

<style scoped>
.login-page {
  min-height: 100vh;
  background: var(--ff-bg-alt);
  display: flex; align-items: center; justify-content: center;
  padding: 24px; position: relative; overflow: hidden;
}
.login-page::before {
  content: '';
  position: absolute;
  top: -180px; right: -120px;
  width: 420px; height: 420px;
  border-radius: 50%;
  border: 1px solid rgba(15, 107, 77, 0.08);
  pointer-events: none;
}



.login-card {
  background: var(--ff-surface);
  border-radius: 8px; padding: 44px 40px; width: 100%; max-width: 420px;
  box-shadow: var(--ff-shadow-sm);
  position: relative; z-index: 1;
  border: 1px solid var(--ff-border);
}

.logo { text-align: center; margin-bottom: 28px; }
.logo-icon {
  width: 56px; height: 56px; border-radius: 8px; margin: 0 auto 14px;
  display: flex; align-items: center; justify-content: center;
  color: #ffffff;
  background: var(--ff-primary);
  box-shadow: none;
  transition: background-color var(--ff-transition-fast);
}
.logo-icon:hover { background: var(--ff-primary-hover); }
.logo h1 {
  margin: 0 0 6px; font-size: 26px; color: var(--ff-text-strong); font-weight: 600; letter-spacing: -0.01em;
}
.logo p { color: var(--ff-text-muted); font-size: 13px; margin: 0; letter-spacing: 0.02em; }

.auth-tabs { margin-top: 6px; }
.submit-btn {
  width: 100%; height: 48px; font-size: 16px; font-weight: 600; margin-top: 6px; border-radius: 6px;
  background: var(--ff-primary) !important;
  border: none !important;
  color: #ffffff !important;
  letter-spacing: 0.02em;
  transition: background-color var(--ff-transition-fast), border-color var(--ff-transition-fast);
}
.submit-btn:hover {
  background: var(--ff-primary-hover) !important;
}
.submit-btn:active {
  box-shadow: none;
}

.login-footnote { text-align: center; margin-top: 24px; font-size: 12px; color: var(--ff-text-muted); }
.forgot-password { margin: -4px 0 12px; color: var(--ff-primary); font-size: 12px; text-align: right; cursor: pointer; }
.code-row, .captcha-row { display:flex; gap:8px; width:100%; align-items:center; }
.code-row .el-input, .captcha-row .el-input { flex:1; }
.captcha-question { min-width:92px; padding:10px; border:1px solid var(--ff-border); border-radius:6px; background:var(--ff-surface-quiet); text-align:center; color:var(--ff-text-strong); }

@media (max-width: 480px) {
  .login-card { padding: 32px 24px; border-radius: 8px; }
  .logo h1 { font-size: 22px; }
}
</style>
