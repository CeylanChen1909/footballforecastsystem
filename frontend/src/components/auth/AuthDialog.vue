<template>
  <el-dialog
    v-model="visible"
    width="420px"
    class="auth-dialog"
    append-to-body
    destroy-on-close
    :show-close="false"
    :aria-label="t('account.authDialog.ariaLabel')"
    :close-on-click-modal="true"
    :close-on-press-escape="true"
  >
    <div class="auth-heading">
      <div class="auth-mark"><el-icon :size="24"><Football /></el-icon></div>
      <div>
        <h2>{{ activeTab === 'login' ? t('account.authDialog.welcomeBack') : t('account.authDialog.joinTitle') }}</h2>
        <p>{{ activeTab === 'login' ? t('account.authDialog.loginSubtitle') : t('account.authDialog.registerSubtitle') }}</p>
      </div>
      <button type="button" class="auth-close" :aria-label="t('account.authDialog.closeAria')" :title="t('account.authDialog.closeTitle')" @click="userStore.closeAuthDialog">×</button>
    </div>

    <el-tabs v-model="activeTab" class="auth-tabs">
      <el-tab-pane :label="t('account.login.tab')" name="login">
        <el-form ref="loginFormRef" :model="loginForm" :rules="loginRules" label-position="top" @submit.prevent="handleLogin">
          <el-form-item :label="t('account.login.accountLabel')" prop="account">
            <el-input v-model="loginForm.account" :placeholder="t('account.login.accountPlaceholder')" prefix-icon="Message" clearable size="large" />
          </el-form-item>
          <el-form-item v-if="userStore.loginChallenge" :label="t('account.login.securityCheck')" prop="captchaAnswer">
            <div class="captcha-row"><span class="captcha-question">{{ userStore.loginChallenge.captchaQuestion }}</span><el-input v-model="loginForm.captchaAnswer" :placeholder="t('account.login.captchaPlaceholder')" /></div>
          </el-form-item>
          <el-form-item :label="t('account.login.passwordLabel')" prop="password">
            <el-input v-model="loginForm.password" type="password" :placeholder="t('account.login.passwordPlaceholder')" prefix-icon="Lock" show-password size="large" @keyup.enter="handleLogin" />
          </el-form-item>
          <el-button type="primary" :loading="loading" class="submit-btn" @click="handleLogin">{{ t('account.login.loginButton') }}</el-button>
          <button type="button" class="aux-link" @click="activeTab = 'reset'">{{ t('account.login.forgotPassword') }}</button>
        </el-form>
      </el-tab-pane>

      <el-tab-pane :label="t('account.login.registerTab')" name="register">
        <ul class="register-value-props" :aria-label="t('account.register.valuePropsAria')">
          <li>{{ t('account.register.valueProp1') }}</li>
          <li>{{ t('account.register.valueProp2') }}</li>
          <li>{{ t('account.register.valueProp3') }}</li>
          <li>{{ t('account.register.valueProp4') }}</li>
        </ul>
        <p class="register-why">{{ t('account.register.why') }}</p>
        <el-form ref="registerFormRef" :model="registerForm" :rules="registerRules" label-position="top" @submit.prevent="handleRegister">
          <el-form-item :label="t('account.register.emailLabel')" prop="email">
            <el-input v-model="registerForm.email" :placeholder="t('account.register.emailPlaceholder')" prefix-icon="Message" clearable size="large" />
          </el-form-item>
          <el-form-item :label="t('account.register.nicknameLabel')" prop="nickname">
            <el-input v-model="registerForm.nickname" :placeholder="t('account.register.nicknamePlaceholder')" prefix-icon="User" clearable size="large" />
          </el-form-item>
          <el-form-item :label="t('account.register.captchaLabel')" prop="captchaAnswer">
            <div v-if="registerCaptchaVerified" class="captcha-verified"><span aria-hidden="true">✓</span> {{ t('account.register.captchaPassed') }}</div>
            <ImageCaptcha v-else :image="registerCaptchaImage" v-model:answer="registerForm.captchaAnswer" @refresh="refreshRegisterCaptcha" />
          </el-form-item>
          <el-form-item :label="t('account.common.emailCode')" prop="verificationCode">
            <div class="code-row"><el-input v-model="registerForm.verificationCode" :placeholder="t('account.login.codePlaceholder')" /><el-button :disabled="codeCountdown > 0" @click="sendRegisterCode">{{ codeCountdown > 0 ? t('account.login.resend', { seconds: codeCountdown }) : t('account.login.sendCode') }}</el-button></div>
          </el-form-item>
          <el-form-item :label="t('account.login.passwordLabel')" prop="password">
            <el-input v-model="registerForm.password" type="password" :placeholder="t('account.register.passwordPlaceholderShort')" prefix-icon="Lock" show-password size="large" />
          </el-form-item>
          <el-form-item :label="t('account.register.confirmPasswordLabel')" prop="confirmPassword">
            <el-input v-model="registerForm.confirmPassword" type="password" :placeholder="t('account.register.confirmPasswordPlaceholder')" prefix-icon="Lock" show-password size="large" @keyup.enter="handleRegister" />
          </el-form-item>
          <el-button type="primary" :loading="loading" class="submit-btn" @click="handleRegister">{{ t('account.register.registerButton') }}</el-button>
          <p class="terms-note">{{ t('account.register.termsPrefix') }}<router-link to="/privacy" @click="userStore.closeAuthDialog">{{ t('account.login.privacyLink') }}</router-link>{{ t('account.register.termsSuffix') }}</p>
        </el-form>
      </el-tab-pane>
      <el-tab-pane :label="t('account.reset.tab')" name="reset">
        <el-form ref="resetFormRef" :model="resetForm" :rules="resetRules" label-position="top" @submit.prevent="handleReset">
          <el-form-item :label="t('account.reset.emailLabel')" prop="email"><el-input v-model="resetForm.email" type="email" :placeholder="t('account.reset.emailPlaceholder')" prefix-icon="Message" clearable size="large" /></el-form-item>
          <el-form-item :label="t('account.common.emailCode')" prop="verificationCode"><div class="code-row"><el-input v-model="resetForm.verificationCode" :placeholder="t('account.login.codePlaceholder')" /><el-button :disabled="resetCodeCountdown > 0" @click="sendResetCode">{{ resetCodeCountdown > 0 ? t('account.login.resend', { seconds: resetCodeCountdown }) : t('account.login.sendCode') }}</el-button></div></el-form-item>
          <el-form-item :label="t('account.reset.newPasswordLabel')" prop="password"><el-input v-model="resetForm.password" type="password" :placeholder="t('account.register.passwordPlaceholderShort')" show-password size="large" /></el-form-item>
          <el-form-item :label="t('account.reset.confirmPasswordLabel')" prop="confirmPassword"><el-input v-model="resetForm.confirmPassword" type="password" :placeholder="t('account.reset.confirmPasswordPlaceholder')" show-password size="large" /></el-form-item>
          <el-button type="primary" :loading="loading" class="submit-btn" @click="handleReset">{{ t('account.reset.submit') }}</el-button>
          <button type="button" class="aux-link" @click="activeTab = 'login'">{{ t('account.reset.backToLogin') }}</button>
        </el-form>
      </el-tab-pane>
    </el-tabs>

    <button type="button" class="guest-link" @click="userStore.closeAuthDialog">{{ t('account.authDialog.guestLink') }}</button>
  </el-dialog>
</template>

<script setup>
import { computed, getCurrentInstance, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Football } from '@element-plus/icons-vue'
import { useUserStore } from '../../stores/user'
import { registerElementPlusDialog } from '../../plugins/register-element-plus-dialog'

registerElementPlusDialog(getCurrentInstance()?.appContext.app)
import { userApi } from '../../api'
import ImageCaptcha from './ImageCaptcha.vue'
import { useI18n } from '../../i18n'

const router = useRouter()
const userStore = useUserStore()
const { t } = useI18n()
const visible = computed({
  get: () => userStore.authDialogVisible,
  set: value => { if (!value) userStore.closeAuthDialog() }
})
const activeTab = ref(userStore.authDialogTab || 'login')
const loading = ref(false)
const loginFormRef = ref()
const registerFormRef = ref()
const resetFormRef = ref()
const loginForm = reactive({ account: '', password: '', captchaAnswer: '' })
const registerForm = reactive({ email: '', nickname: '', password: '', confirmPassword: '', verificationCode: '', captchaId: '', captchaAnswer: '' })
const registerCaptchaImage = ref('')
const registerCaptchaVerified = ref(false)
const registerCodeEmail = ref('')
const resetForm = reactive({ email: '', verificationCode: '', password: '', confirmPassword: '' })
const codeCountdown = ref(0)
const resetCodeCountdown = ref(0)
let codeTimer = null
let resetCodeTimer = null

watch(() => userStore.authDialogTab, value => { activeTab.value = value || 'login' })
watch(activeTab, value => { if (value === 'register' && !registerCaptchaImage.value) refreshRegisterCaptcha() })
watch(() => registerForm.email, value => {
  const email = String(value || '').trim().toLowerCase()
  if (registerCodeEmail.value && email !== registerCodeEmail.value) {
    registerCodeEmail.value = ''
    registerForm.verificationCode = ''
    registerCaptchaVerified.value = false
    refreshRegisterCaptcha()
  }
})
watch(() => userStore.authDialogVisible, value => {
  if (value) {
    activeTab.value = userStore.authDialogTab || 'login'
    loginFormRef.value?.clearValidate()
    registerFormRef.value?.clearValidate()
    if (activeTab.value === 'register') refreshRegisterCaptcha()
  }
})

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
const resetRules = computed(() => ({
  email: registerRules.value.email,
  verificationCode: registerRules.value.verificationCode,
  password: registerRules.value.password,
  confirmPassword: [{ required: true, message: t('account.validation.newPasswordConfirmRequired'), trigger: 'blur' }, { validator: (rule, value, callback) => value !== resetForm.password ? callback(new Error(t('account.validation.passwordMismatch'))) : callback(), trigger: 'blur' }]
}))

const refreshRegisterCaptcha = async () => {
  registerForm.captchaAnswer = ''
  registerCaptchaImage.value = ''
  registerCaptchaVerified.value = false
  try {
    const result = await userApi.getRegistrationCaptcha()
    const data = result?.data ?? result
    if (data?.ok === false) throw new Error(data.message || t('account.errors.captchaLoadFailed'))
    registerForm.captchaId = data?.captchaId || ''
    registerCaptchaImage.value = data?.image || ''
  } catch (error) {
    ElMessage.error(error?.message || t('account.errors.captchaLoadFailedRetry'))
  }
}

const sendRegisterCode = async () => {
  if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(registerForm.email)) { ElMessage.warning(t('account.errors.emailInvalid')); return }
  if (registerCaptchaVerified.value) {
    await refreshRegisterCaptcha()
    ElMessage.info(t('account.errors.completeNewCaptcha'))
    return
  }
  if (!registerForm.captchaId || !registerForm.captchaAnswer) { ElMessage.warning(t('account.errors.captchaNotCompleted')); return }
  try {
    const result = await userApi.sendEmailCode(registerForm.email, 'REGISTER', registerForm.captchaId, registerForm.captchaAnswer)
    if (result?.ok === false) throw new Error(result.message || t('account.errors.codeSendFailed'))
    ElMessage.success(result?.delivery === 'console' ? t('account.notice.codeInDevLog') : t('account.notice.codeSent'))
    registerCodeEmail.value = registerForm.email.trim().toLowerCase()
    registerCaptchaVerified.value = true
    codeCountdown.value = 60
    codeTimer = window.setInterval(() => { codeCountdown.value -= 1; if (codeCountdown.value <= 0) { window.clearInterval(codeTimer); codeTimer = null } }, 1000)
  } catch (error) { ElMessage.error(error?.message || t('account.errors.codeSendRetry')) }
}

const sendResetCode = async () => {
  if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(resetForm.email)) { ElMessage.warning(t('account.errors.emailInvalid')); return }
  try {
    const result = await userApi.requestPasswordReset(resetForm.email)
    if (result?.ok === false) throw new Error(result.message || t('account.errors.codeSendFailed'))
    ElMessage.success(result?.delivery === 'console' ? t('account.notice.codeInDevLog') : t('account.notice.codeSent'))
    resetCodeCountdown.value = 60
    resetCodeTimer = window.setInterval(() => { resetCodeCountdown.value -= 1; if (resetCodeCountdown.value <= 0) { window.clearInterval(resetCodeTimer); resetCodeTimer = null } }, 1000)
  } catch (error) { ElMessage.error(error?.message || t('account.errors.codeSendRetry')) }
}

const goAfterLogin = () => {
  const redirect = userStore.authDialogRedirect
  userStore.closeAuthDialog()
  userStore.authDialogRedirect = ''
  if (typeof redirect === 'string' && redirect.startsWith('/') && !redirect.startsWith('//')) {
    router.replace(redirect)
    return
  }
  router.replace(['ADMIN', 'SUPER_ADMIN'].includes(userStore.role) ? '/admin' : '/matches')
}

const handleLogin = async () => {
  const valid = await loginFormRef.value?.validate().catch(() => false)
  if (!valid) return
  loading.value = true
  try {
    if (await userStore.login(loginForm.account, loginForm.password, userStore.loginChallenge?.captchaId, loginForm.captchaAnswer)) {
      ElMessage.success(t('account.notice.loginSuccess'))
      goAfterLogin()
    }
  } catch (error) {
    ElMessage.error(error?.message || t('account.errors.loginRetry'))
  } finally {
    loading.value = false
  }
}

const handleRegister = async () => {
  const valid = await registerFormRef.value?.validate().catch(() => false)
  if (!valid) return
  loading.value = true
  try {
    if (await userStore.register(registerForm.email, registerForm.nickname, registerForm.password, registerForm.verificationCode, registerForm.captchaId, registerForm.captchaAnswer)) {
      activeTab.value = 'login'
      loginForm.account = registerForm.email
      loginForm.password = ''
    }
  } catch (error) {
    ElMessage.error(error?.message || t('account.errors.registerRetry'))
  } finally {
    loading.value = false
  }
}

const handleReset = async () => {
  const valid = await resetFormRef.value?.validate().catch(() => false)
  if (!valid) return
  loading.value = true
  try {
    const result = await userApi.resetPassword(resetForm.email, resetForm.verificationCode, resetForm.password)
    if (result?.ok === false) throw new Error(result.message || t('account.errors.resetFailed'))
    ElMessage.success(t('account.notice.resetSuccess'))
    loginForm.account = resetForm.email
    loginForm.password = ''
    activeTab.value = 'login'
  } catch (error) { ElMessage.error(error?.message || t('account.errors.resetRetry')) } finally { loading.value = false }
}
</script>

<style scoped>
.auth-heading { display:flex; align-items:center; gap:12px; margin-bottom:8px; }.auth-heading > div:nth-child(2) { min-width:0; flex:1; }.auth-close { display:grid; place-items:center; flex:none; width:30px; height:30px; border:0; border-radius:7px; color:var(--ff-text-muted); background:transparent; cursor:pointer; font-size:22px; line-height:1; }.auth-close:hover,.auth-close:focus-visible { color:var(--ff-primary); background:var(--ff-primary-soft); outline:none; }
.auth-mark { width:42px; height:42px; display:flex; align-items:center; justify-content:center; border-radius:12px; color:#fff; background:var(--ff-primary); box-shadow:var(--ff-shadow-sm); }
.auth-heading h2 { color:var(--ff-text-strong); font-size:20px; letter-spacing:-.02em; }
.auth-heading p { margin-top:4px; color:var(--ff-text-muted); font-size:12px; line-height:1.5; }
.auth-tabs { margin-top:12px; }
.submit-btn { width:100%; margin-top:4px; }
.code-row, .captcha-row { display:flex; gap:8px; width:100%; align-items:center; }
.code-row .el-input, .captcha-row .el-input { flex:1; }
.captcha-verified { display:flex; align-items:center; gap:6px; min-height:42px; padding:0 12px; border:1px solid color-mix(in srgb, var(--ff-primary) 35%, var(--ff-border)); border-radius:7px; color:var(--ff-primary); background:var(--ff-primary-soft); font-size:12px; }
.captcha-question { min-width:92px; padding:10px; border:1px solid var(--ff-border); border-radius:6px; background:var(--ff-surface-quiet); text-align:center; color:var(--ff-text-strong); }
.guest-link { display:block; margin:18px auto 0; border:0; padding:0; color:var(--ff-text-muted); background:transparent; font-size:12px; cursor:pointer; }
.guest-link:hover { color:var(--ff-primary); }
.aux-link { display:block; margin:12px auto 0; border:0; padding:0; color:var(--ff-primary); background:transparent; font-size:12px; cursor:pointer; }.aux-link:hover { text-decoration:underline; }.terms-note { margin:12px 0 0; color:var(--ff-text-faint); font-size:11px; line-height:1.5; text-align:center; }.terms-note a { color:var(--ff-primary); }
.register-value-props { margin:0 0 10px; padding:10px 12px 10px 28px; border:1px solid var(--ff-border); border-radius:10px; background:var(--ff-surface-quiet); color:var(--ff-text); font-size:12px; line-height:1.7; }
.register-value-props li { list-style:disc; }
.register-why { margin:0 0 12px; color:var(--ff-text-muted); font-size:11px; line-height:1.5; }
</style>
