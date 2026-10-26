<template>
  <div class="login-page">
    <a class="skip-link" href="#app-main">{{ t('account.login.skipToMain') }}</a>
    <main id="app-main" class="login-card reveal" tabindex="-1">
      <div class="logo">
        <div class="logo-icon"><el-icon :size="36"><Football /></el-icon></div>
        <h1>ChenFootball</h1>
        <p>{{ t('account.login.subtitle') }}</p>
      </div>

      <el-tabs v-model="activeTab" class="auth-tabs">
        <el-tab-pane :label="t('account.login.tab')" name="login">
          <el-form ref="loginFormRef" :model="loginForm" :rules="loginRules" label-position="top">
            <el-form-item :label="t('account.login.accountLabel')" prop="account">
              <el-input v-model="loginForm.account" :placeholder="t('account.login.accountPlaceholder')" prefix-icon="Message" clearable size="large" />
            </el-form-item>
            <el-form-item v-if="userStore.loginChallenge" :label="t('account.login.securityCheck')" prop="captchaAnswer">
              <div class="captcha-row"><span class="captcha-question">{{ userStore.loginChallenge.captchaQuestion }}</span><el-input v-model="loginForm.captchaAnswer" :placeholder="t('account.login.captchaPlaceholder')" /></div>
            </el-form-item>
            <el-form-item :label="t('account.login.passwordLabel')" prop="password">
              <el-input v-model="loginForm.password" type="password" :placeholder="t('account.login.passwordPlaceholder')" prefix-icon="Lock"
                show-password @keyup.enter="handleLogin" size="large" />
            </el-form-item>
            <div class="forgot-password" @click="showForgotPassword">{{ t('account.login.forgotPassword') }}</div>
            <el-button type="primary" :loading="loading" class="submit-btn" @click="handleLogin">
              {{ t('account.login.loginButton') }}
            </el-button>
          </el-form>
        </el-tab-pane>

        <el-tab-pane :label="t('account.login.registerTab')" name="register">
          <el-form ref="registerFormRef" :model="registerForm" :rules="registerRules" label-position="top">
            <el-form-item :label="t('account.register.emailLabel')" prop="email">
              <el-input v-model="registerForm.email" :placeholder="t('account.register.emailPlaceholder')" prefix-icon="Message" clearable size="large" />
            </el-form-item>
            <el-form-item :label="t('account.register.nicknameLabel')" prop="nickname">
              <el-input v-model="registerForm.nickname" :placeholder="t('account.register.nicknamePlaceholder')" prefix-icon="User" clearable size="large" />
            </el-form-item>
            <el-form-item :label="t('account.register.captchaLabel')" prop="captchaAnswer">
              <ImageCaptcha :image="registerCaptchaImage" v-model:answer="registerForm.captchaAnswer" @refresh="refreshRegisterCaptcha" />
            </el-form-item>
            <el-form-item :label="t('account.common.emailCode')" prop="verificationCode">
              <div class="code-row"><el-input v-model="registerForm.verificationCode" :placeholder="t('account.login.codePlaceholder')" /><el-button :disabled="codeCountdown > 0" @click="sendRegisterCode">{{ codeCountdown > 0 ? t('account.login.resend', { seconds: codeCountdown }) : t('account.login.sendCode') }}</el-button></div>
            </el-form-item>
            <el-form-item :label="t('account.login.passwordLabel')" prop="password">
              <el-input v-model="registerForm.password" type="password" :placeholder="t('account.register.passwordPlaceholder')"
                prefix-icon="Lock" show-password size="large" />
            </el-form-item>
            <el-form-item :label="t('account.register.confirmPasswordLabel')" prop="confirmPassword">
              <el-input v-model="registerForm.confirmPassword" type="password" :placeholder="t('account.register.confirmPasswordPlaceholder')"
                prefix-icon="Lock" show-password @keyup.enter="handleRegister" size="large" />
            </el-form-item>
            <el-button type="primary" :loading="loading" class="submit-btn" @click="handleRegister">
              {{ t('account.register.registerButton') }}
            </el-button>
          </el-form>
        </el-tab-pane>
      </el-tabs>

      <div class="login-footnote">{{ t('account.login.footnote') }} <router-link to="/privacy">{{ t('account.login.privacyLink') }}</router-link></div>
      <div class="login-footnote">{{ t('account.login.copyright') }}</div>
    </main>
    <el-dialog v-model="resetVisible" :title="t('account.reset.title')" width="min(420px, 92vw)">
      <el-form label-position="top">
        <el-form-item :label="t('account.reset.emailLabel')"><el-input v-model="resetForm.email" type="email" :placeholder="t('account.reset.emailPlaceholder')" /></el-form-item>
        <el-form-item :label="t('account.common.verificationCode')"><div class="code-row"><el-input v-model="resetForm.verificationCode" :placeholder="t('account.login.codePlaceholder')" /><el-button :disabled="resetCountdown > 0" @click="sendResetCode">{{ resetCountdown > 0 ? t('account.login.resend', { seconds: resetCountdown }) : t('account.login.sendCode') }}</el-button></div></el-form-item>
        <el-form-item :label="t('account.reset.newPasswordLabel')"><el-input v-model="resetForm.newPassword" type="password" show-password :placeholder="t('account.reset.newPasswordPlaceholder')" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="resetVisible=false">{{ t('account.common.cancel') }}</el-button><el-button type="primary" @click="submitReset">{{ t('account.reset.submit') }}</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, ref, reactive, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useUserStore } from '../../stores/user'
import { ElMessage } from 'element-plus'
import { Football } from '@element-plus/icons-vue'
import { userApi } from '../../api'
import ImageCaptcha from '../../components/auth/ImageCaptcha.vue'
import { useI18n } from '../../i18n'

const router = useRouter()
const userStore = useUserStore()
const { t } = useI18n()

const activeTab = ref('login')
const loading = ref(false)
const loginFormRef = ref()
const registerFormRef = ref()

const loginForm = reactive({ account: '', password: '', captchaAnswer: '' })
const registerForm = reactive({ email: '', nickname: '', password: '', confirmPassword: '', verificationCode: '', captchaId: '', captchaAnswer: '' })
const registerCaptchaImage = ref('')
const codeCountdown = ref(0)
let codeTimer = null
const resetVisible = ref(false)
const resetForm = reactive({ email: '', verificationCode: '', newPassword: '' })
const resetCountdown = ref(0)
let resetTimer = null

watch(activeTab, value => { if (value === 'register' && !registerCaptchaImage.value) refreshRegisterCaptcha() })

const validateConfirm = (rule, value, callback) => {
  if (value !== registerForm.password) callback(new Error(t('account.validation.passwordMismatch')))
  else callback()
}

const loginRules = computed(() => ({
  account: [{ required: true, message: t('account.validation.accountRequired'), trigger: 'blur' }],
  password: [{ required: true, message: t('account.validation.passwordRequired'), trigger: 'blur' }],
  captchaAnswer: [{ required: true, message: t('account.validation.captchaRequired'), trigger: 'blur' }]
}))
const registerRules = computed(() => ({
  account: [{ required: true, message: t('account.validation.accountRequired'), trigger: 'blur' }],
  email: [{ required: true, message: t('account.validation.emailRequired'), trigger: 'blur' }, { type: 'email', message: t('account.validation.emailFormat'), trigger: 'blur' }],
  nickname: [{ required: true, message: t('account.validation.nicknameRequired'), trigger: 'blur' }, { max: 32, message: t('account.validation.nicknameMax'), trigger: 'blur' }],
  verificationCode: [{ required: true, message: t('account.validation.emailCodeRequired'), trigger: 'blur' }, { len: 6, message: t('account.validation.emailCodeLength'), trigger: 'blur' }],
  captchaAnswer: [{ required: true, message: t('account.validation.captchaRequired'), trigger: 'blur' }],
  password: [{ required: true, message: t('account.validation.passwordRequired'), trigger: 'blur' }, { min: 8, message: t('account.validation.passwordMin'), trigger: 'blur' }],
  confirmPassword: [{ required: true, message: t('account.validation.confirmRequired'), trigger: 'blur' }, { validator: validateConfirm, trigger: 'blur' }]
}))

const refreshRegisterCaptcha = async () => {
  registerForm.captchaAnswer = ''
  registerCaptchaImage.value = ''
  try {
    const result = await userApi.getRegistrationCaptcha()
    const data = result?.data ?? result
    if (data?.ok === false) throw new Error(data.message || t('account.errors.captchaLoadFailed'))
    registerForm.captchaId = data?.captchaId || ''
    registerCaptchaImage.value = data?.image || ''
  } catch (error) { ElMessage.error(error?.message || t('account.errors.captchaLoadFailedRetry')) }
}

const showForgotPassword = () => { resetVisible.value = true }

const sendRegisterCode = async () => {
  if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(registerForm.email)) { ElMessage.warning(t('account.errors.emailInvalid')); return }
  if (!registerForm.captchaId || !registerForm.captchaAnswer) { ElMessage.warning(t('account.errors.captchaNotCompleted')); return }
  try {
    const result = await userApi.sendEmailCode(registerForm.email, 'REGISTER', registerForm.captchaId, registerForm.captchaAnswer)
    if (result?.ok === false) throw new Error(result.message || t('account.errors.codeSendFailed'))
    ElMessage.success(result?.delivery === 'console' ? t('account.notice.codeInDevLog') : t('account.notice.codeSent'))
    await refreshRegisterCaptcha()
    codeCountdown.value = 60
    codeTimer = window.setInterval(() => { codeCountdown.value -= 1; if (codeCountdown.value <= 0) { window.clearInterval(codeTimer); codeTimer = null } }, 1000)
  } catch (error) { ElMessage.error(error?.message || t('account.errors.codeSendRetry')) }
}

const sendResetCode = async () => {
  if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(resetForm.email)) { ElMessage.warning(t('account.errors.emailInvalid')); return }
  try {
    const result = await userApi.requestPasswordReset(resetForm.email)
    if (result?.ok === false) throw new Error(result.message || t('account.errors.codeSendFailed'))
    ElMessage.success(t('account.notice.resetCodeSent'))
    resetCountdown.value = 60
    resetTimer = window.setInterval(() => { resetCountdown.value -= 1; if (resetCountdown.value <= 0) { window.clearInterval(resetTimer); resetTimer = null } }, 1000)
  } catch (error) { ElMessage.error(error?.message || t('account.errors.codeSendRetry')) }
}
const submitReset = async () => {
  if (!resetForm.email || !/^\d{6}$/.test(resetForm.verificationCode) || resetForm.newPassword.length < 8) { ElMessage.warning(t('account.errors.resetFormInvalid')); return }
  try {
    const result = await userApi.resetPassword(resetForm.email, resetForm.verificationCode, resetForm.newPassword)
    if (result?.ok === false) throw new Error(result.message || t('account.errors.resetFailed'))
    ElMessage.success(t('account.notice.resetSuccess'))
    resetVisible.value = false
    loginForm.account = resetForm.email
    resetForm.verificationCode = ''; resetForm.newPassword = ''
  } catch (error) { ElMessage.error(error?.message || t('account.errors.resetRetry')) }
}

const handleLogin = async () => {
  const valid = await loginFormRef.value?.validate().catch(() => false)
  if (!valid) return
  loading.value = true
  const ok = await userStore.login(loginForm.account, loginForm.password, userStore.loginChallenge?.captchaId, loginForm.captchaAnswer)
  loading.value = false
  if (ok) {
    ElMessage.success(t('account.notice.loginSuccess'))
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
  const ok = await userStore.register(registerForm.email, registerForm.nickname, registerForm.password, registerForm.verificationCode, registerForm.captchaId, registerForm.captchaAnswer)
  loading.value = false
  if (ok) {
    activeTab.value = 'login'
    loginForm.account = registerForm.email
  } else await refreshRegisterCaptcha()
}
</script>

<style scoped>
.login-page { position: relative; }
.skip-link { position:absolute; top:-48px; left:14px; z-index:30; padding:8px 12px; border-radius:6px; color:#fff; background:var(--ff-primary, #0f6b4d); font-size:12px; font-weight:700; }
.skip-link:focus, .skip-link:focus-visible { top:8px; outline:2px solid #d4a017; outline-offset:2px; }

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
