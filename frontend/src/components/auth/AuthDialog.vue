<template>
  <el-dialog
    v-model="visible"
    width="420px"
    class="auth-dialog"
    append-to-body
    destroy-on-close
    :show-close="false"
    aria-label="登录与注册"
    :close-on-click-modal="true"
    :close-on-press-escape="true"
  >
    <div class="auth-heading">
      <div class="auth-mark"><el-icon :size="24"><Football /></el-icon></div>
      <div>
        <h2>{{ activeTab === 'login' ? '欢迎回来' : '加入 ChenFootball' }}</h2>
        <p>{{ activeTab === 'login' ? '登录后同步收藏、预测记录与 Agent 会话' : '注册一个账号，保存你的足球分析轨迹' }}</p>
      </div>
      <button type="button" class="auth-close" aria-label="关闭登录窗口" title="关闭" @click="userStore.closeAuthDialog">×</button>
    </div>

    <el-tabs v-model="activeTab" class="auth-tabs">
      <el-tab-pane label="登录" name="login">
        <el-form ref="loginFormRef" :model="loginForm" :rules="loginRules" label-position="top" @submit.prevent="handleLogin">
          <el-form-item label="邮箱或账号" prop="account">
            <el-input v-model="loginForm.account" placeholder="请输入邮箱或账号" prefix-icon="Message" clearable size="large" />
          </el-form-item>
          <el-form-item v-if="userStore.loginChallenge" label="安全验证" prop="captchaAnswer">
            <div class="captcha-row"><span class="captcha-question">{{ userStore.loginChallenge.captchaQuestion }}</span><el-input v-model="loginForm.captchaAnswer" placeholder="计算结果" /></div>
          </el-form-item>
          <el-form-item label="密码" prop="password">
            <el-input v-model="loginForm.password" type="password" placeholder="请输入密码" prefix-icon="Lock" show-password size="large" @keyup.enter="handleLogin" />
          </el-form-item>
          <el-button type="primary" :loading="loading" class="submit-btn" @click="handleLogin">登录</el-button>
          <button type="button" class="aux-link" @click="activeTab = 'reset'">忘记密码？</button>
        </el-form>
      </el-tab-pane>

      <el-tab-pane label="注册" name="register">
        <el-form ref="registerFormRef" :model="registerForm" :rules="registerRules" label-position="top" @submit.prevent="handleRegister">
          <el-form-item label="邮箱" prop="email">
            <el-input v-model="registerForm.email" placeholder="用于登录和接收验证码" prefix-icon="Message" clearable size="large" />
          </el-form-item>
          <el-form-item label="昵称" prop="nickname">
            <el-input v-model="registerForm.nickname" placeholder="对外展示的昵称" prefix-icon="User" clearable size="large" />
          </el-form-item>
          <el-form-item label="图形验证" prop="captchaAnswer">
            <ImageCaptcha :image="registerCaptchaImage" v-model:answer="registerForm.captchaAnswer" @refresh="refreshRegisterCaptcha" />
          </el-form-item>
          <el-form-item label="邮箱验证码" prop="verificationCode">
            <div class="code-row"><el-input v-model="registerForm.verificationCode" placeholder="6 位验证码" /><el-button :disabled="codeCountdown > 0" @click="sendRegisterCode">{{ codeCountdown > 0 ? `${codeCountdown}s 后重发` : '发送验证码' }}</el-button></div>
          </el-form-item>
          <el-form-item label="密码" prop="password">
            <el-input v-model="registerForm.password" type="password" placeholder="至少 8 位密码" prefix-icon="Lock" show-password size="large" />
          </el-form-item>
          <el-form-item label="确认密码" prop="confirmPassword">
            <el-input v-model="registerForm.confirmPassword" type="password" placeholder="请再次输入密码" prefix-icon="Lock" show-password size="large" @keyup.enter="handleRegister" />
          </el-form-item>
          <el-button type="primary" :loading="loading" class="submit-btn" @click="handleRegister">注册</el-button>
          <p class="terms-note">注册即表示你同意阅读并遵守 <router-link to="/privacy" @click="userStore.closeAuthDialog">隐私与数据说明</router-link>。</p>
        </el-form>
      </el-tab-pane>
      <el-tab-pane label="找回密码" name="reset">
        <el-form ref="resetFormRef" :model="resetForm" :rules="resetRules" label-position="top" @submit.prevent="handleReset">
          <el-form-item label="注册邮箱" prop="email"><el-input v-model="resetForm.email" type="email" placeholder="请输入注册邮箱" prefix-icon="Message" clearable size="large" /></el-form-item>
          <el-form-item label="邮箱验证码" prop="verificationCode"><div class="code-row"><el-input v-model="resetForm.verificationCode" placeholder="6 位验证码" /><el-button :disabled="resetCodeCountdown > 0" @click="sendResetCode">{{ resetCodeCountdown > 0 ? `${resetCodeCountdown}s 后重发` : '发送验证码' }}</el-button></div></el-form-item>
          <el-form-item label="新密码" prop="password"><el-input v-model="resetForm.password" type="password" placeholder="至少 8 位密码" show-password size="large" /></el-form-item>
          <el-form-item label="确认新密码" prop="confirmPassword"><el-input v-model="resetForm.confirmPassword" type="password" placeholder="请再次输入新密码" show-password size="large" /></el-form-item>
          <el-button type="primary" :loading="loading" class="submit-btn" @click="handleReset">重置密码</el-button>
          <button type="button" class="aux-link" @click="activeTab = 'login'">返回登录</button>
        </el-form>
      </el-tab-pane>
    </el-tabs>

    <button type="button" class="guest-link" @click="userStore.closeAuthDialog">先逛逛，稍后再说</button>
  </el-dialog>
</template>

<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Football } from '@element-plus/icons-vue'
import { useUserStore } from '../../stores/user'
import { userApi } from '../../api'
import ImageCaptcha from './ImageCaptcha.vue'

const router = useRouter()
const userStore = useUserStore()
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
const resetForm = reactive({ email: '', verificationCode: '', password: '', confirmPassword: '' })
const codeCountdown = ref(0)
const resetCodeCountdown = ref(0)
let codeTimer = null
let resetCodeTimer = null

watch(() => userStore.authDialogTab, value => { activeTab.value = value || 'login' })
watch(activeTab, value => { if (value === 'register' && !registerCaptchaImage.value) refreshRegisterCaptcha() })
watch(() => userStore.authDialogVisible, value => {
  if (value) {
    activeTab.value = userStore.authDialogTab || 'login'
    loginFormRef.value?.clearValidate()
    registerFormRef.value?.clearValidate()
    if (activeTab.value === 'register') refreshRegisterCaptcha()
  }
})

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
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }, { min: 8, message: '密码至少 8 位', trigger: 'blur' }],
  confirmPassword: [{ required: true, message: '请确认密码', trigger: 'blur' }, { validator: validateConfirm, trigger: 'blur' }]
}
const resetRules = {
  email: registerRules.email,
  verificationCode: registerRules.verificationCode,
  password: registerRules.password,
  confirmPassword: [{ required: true, message: '请确认新密码', trigger: 'blur' }, { validator: (rule, value, callback) => value !== resetForm.password ? callback(new Error('两次密码不一致')) : callback(), trigger: 'blur' }]
}

const refreshRegisterCaptcha = async () => {
  registerForm.captchaAnswer = ''
  registerCaptchaImage.value = ''
  try {
    const result = await userApi.getRegistrationCaptcha()
    const data = result?.data ?? result
    if (data?.ok === false) throw new Error(data.message || '图形验证码加载失败')
    registerForm.captchaId = data?.captchaId || ''
    registerCaptchaImage.value = data?.image || ''
  } catch (error) {
    ElMessage.error(error?.message || '图形验证码加载失败')
  }
}

const sendRegisterCode = async () => {
  if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(registerForm.email)) { ElMessage.warning('请先输入有效邮箱'); return }
  if (!registerForm.captchaId || !registerForm.captchaAnswer) { ElMessage.warning('请先完成图形验证'); return }
  try {
    const result = await userApi.sendEmailCode(registerForm.email, 'REGISTER', registerForm.captchaId, registerForm.captchaAnswer)
    if (result?.ok === false) throw new Error(result.message || '验证码发送失败')
    ElMessage.success(result?.delivery === 'console' ? '验证码已写入后端开发日志' : '验证码已发送，请查收邮件')
    await refreshRegisterCaptcha()
    codeCountdown.value = 60
    codeTimer = window.setInterval(() => { codeCountdown.value -= 1; if (codeCountdown.value <= 0) { window.clearInterval(codeTimer); codeTimer = null } }, 1000)
  } catch (error) { ElMessage.error(error?.message || '验证码发送失败') }
}

const sendResetCode = async () => {
  if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(resetForm.email)) { ElMessage.warning('请先输入有效邮箱'); return }
  try {
    const result = await userApi.requestPasswordReset(resetForm.email)
    if (result?.ok === false) throw new Error(result.message || '验证码发送失败')
    ElMessage.success(result?.delivery === 'console' ? '验证码已写入后端开发日志' : '验证码已发送，请查收邮件')
    resetCodeCountdown.value = 60
    resetCodeTimer = window.setInterval(() => { resetCodeCountdown.value -= 1; if (resetCodeCountdown.value <= 0) { window.clearInterval(resetCodeTimer); resetCodeTimer = null } }, 1000)
  } catch (error) { ElMessage.error(error?.message || '验证码发送失败') }
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
      ElMessage.success('登录成功')
      goAfterLogin()
    }
  } catch (error) {
    ElMessage.error(error?.message || '登录失败，请稍后重试')
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
    } else await refreshRegisterCaptcha()
  } catch (error) {
    ElMessage.error(error?.message || '注册失败，请稍后重试')
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
    if (result?.ok === false) throw new Error(result.message || '密码重置失败')
    ElMessage.success('密码已重置，请使用新密码登录')
    loginForm.account = resetForm.email
    loginForm.password = ''
    activeTab.value = 'login'
  } catch (error) { ElMessage.error(error?.message || '密码重置失败') } finally { loading.value = false }
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
.captcha-question { min-width:92px; padding:10px; border:1px solid var(--ff-border); border-radius:6px; background:var(--ff-surface-quiet); text-align:center; color:var(--ff-text-strong); }
.guest-link { display:block; margin:18px auto 0; border:0; padding:0; color:var(--ff-text-muted); background:transparent; font-size:12px; cursor:pointer; }
.guest-link:hover { color:var(--ff-primary); }
.aux-link { display:block; margin:12px auto 0; border:0; padding:0; color:var(--ff-primary); background:transparent; font-size:12px; cursor:pointer; }.aux-link:hover { text-decoration:underline; }.terms-note { margin:12px 0 0; color:var(--ff-text-faint); font-size:11px; line-height:1.5; text-align:center; }.terms-note a { color:var(--ff-primary); }
</style>
